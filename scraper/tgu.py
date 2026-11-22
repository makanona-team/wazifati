"""حساب القراءة الخاص في تيليغرام (Telethon): مجموعات، قنوات خاصة، وفهرسة بوتات المكتبات.

يُستدعى من scrape.py بعد المصادر العادية. الجلسة محفوظة في الخادم (/tg/secret) وليست في المستودع.
"""
import asyncio
import hashlib
import json
import os
import re
import time

from telethon import TelegramClient, errors, functions, types
from telethon.sessions import StringSession

DOC_EXT = re.compile(r"\.(pdf|docx?|pptx?|xlsx?|epub)$", re.I)
NAV = re.compile(r"^\W*(go back|back|main menu|menu|home|رجوع|عودة|العودة|القائمة الرئيسية|الرئيسية|تواصل معنا|اتصل بنا|حول\b.*|about\b.*|contact\b.*|help|مساعدة|⬅|🔙|🏠)\W*$", re.I)
NEXT = re.compile(r"(?i)(next|التالي|الصفحة التالية|المزيد|more)[^\n/]*?(/[A-Za-z0-9_]+)")
TIME_BUDGET = 9 * 60          # أقصى وقت لحساب القراءة في كل تشغيل
BOT_ACTIONS = 70              # أقصى عدد ضغطات لكل بوت في التشغيل الواحد (يُكمل في التشغيل التالي)
BOT_CYCLE_DAYS = 6            # إعادة فهرسة البوت كل أسبوع تقريباً
MAX_JOINS = 2                 # انضمامات جديدة في كل تشغيل (تجنّب الاشتباه)
DEAD = (errors.AuthKeyUnregisteredError, errors.SessionRevokedError, errors.UserDeactivatedError,
        errors.UserDeactivatedBanError, errors.AuthKeyDuplicatedError)


def size_str(n):
    n = float(n or 0)
    for u in ("B", "KB", "MB", "GB"):
        if n < 1024 or u == "GB":
            return (f"{n:.0f} {u}" if u in ("B", "KB") else f"{n:.1f} {u}")
        n /= 1024


def file_of(m):
    f = getattr(m, "file", None)
    name = getattr(f, "name", None) if f else None
    if name and DOC_EXT.search(name.strip()):
        return {"name": name.strip(), "size": size_str(getattr(f, "size", 0))}
    return None


def links_of(m):
    out = [e.url for e in (m.entities or []) if isinstance(e, types.MessageEntityTextUrl) and e.url]
    out += re.findall(r"https?://[^\s<>\"'()\]]+", m.message or "")
    return out


def menu_of(msgs):
    for m in reversed(msgs):
        rm = getattr(m, "reply_markup", None)
        if isinstance(rm, types.ReplyKeyboardMarkup):
            return [b.text for row in rm.rows for b in row.buttons if getattr(b, "text", "").strip()]
    return None


class Ctx:
    def __init__(self, api, send, log):
        self.api, self.send, self.log = api, send, log
        self.t0 = time.time()
        self.joins = 0
        self.lib, self.libmap, self.no_fwd, self.dirty = None, {}, {}, False
        self.groups = None

    def left(self):
        return TIME_BUDGET - (time.time() - self.t0)


# ---------- مجموعات وقنوات ----------
async def entity_for(client, ctx, s):
    kind, value = s["kind"], s["value"]
    if kind == "tgc":   # مجموعة/قناة خاصة الحساب عضو فيها، مضافة برابط رسالة t.me/c/<id>/<msg>
        if ctx.groups is None:
            ctx.groups = {}
            async for d in client.iter_dialogs():
                if isinstance(d.entity, (types.Channel, types.Chat)):
                    ctx.groups[d.entity.id] = d.entity
        ent = ctx.groups.get(int(value))
        if not ent:
            raise RuntimeError("الحساب الخاص ليس عضواً في هذه المجموعة؛ أضفه إليها أولاً")
        return ent
    if kind == "tgp":
        h = value.rsplit("+", 1)[-1].rsplit("/", 1)[-1]
        inv = await client(functions.messages.CheckChatInviteRequest(h))
        if isinstance(inv, types.ChatInviteAlready):
            return inv.chat
        if ctx.joins >= MAX_JOINS:
            raise RuntimeError("⏸ ينتظر دوره للانضمام (انضمامان في كل ساعة)")
        ctx.joins += 1
        try:
            r = await client(functions.messages.ImportChatInviteRequest(h))
            return r.chats[0]
        except errors.InviteRequestSentError:
            raise RuntimeError("⏸ أُرسل طلب انضمام، بانتظار موافقة مشرف المجموعة")
    return await client.get_entity(value)


async def chat_posts(client, ctx, s):
    ent = await entity_for(client, ctx, s)
    # حماية الخصوصية: لا نقرأ أبداً محادثة شخصية مع مستخدم أو بوت، فقط قنوات ومجموعات
    if isinstance(ent, types.User):
        raise RuntimeError("⛔ هذا حساب شخص وليس قناة أو مجموعة؛ لا تُقرأ المحادثات الخاصة")
    uname = getattr(ent, "username", None)
    posts = []
    async for m in client.iter_messages(ent, limit=60):
        if not isinstance(m, types.Message):
            continue
        url = f"https://t.me/{uname}/{m.id}" if uname else f"https://t.me/c/{ent.id}/{m.id}"
        text = (m.message or "").strip()
        p = {"url": url, "text": text, "date": int(m.date.timestamp() * 1000), "links": links_of(m)}
        f = file_of(m)
        if f:
            p["file"], p["open"] = f, url
            if not text:
                p["text"] = f["name"]
        if p["text"]:
            posts.append(p)
    return posts, getattr(ent, "title", "") or ""


# ---------- بوتات المكتبات ----------
async def say(client, bot, text):
    sent = await client.send_message(bot, text)
    got, quiet, t0 = [], 0, time.time()
    await asyncio.sleep(2)
    while time.time() - t0 < 25:
        ms = [m for m in await client.get_messages(bot, min_id=sent.id, limit=100) if not m.out]
        if len(ms) == len(got):
            quiet += 1
        else:
            quiet, got = 0, ms
        if (got and quiet >= 2) or quiet >= 4:
            break
        await asyncio.sleep(1.5)
    await asyncio.sleep(1.5)
    return sorted(got, key=lambda m: m.id)


def collect(bot, title, msgs, path, page):
    where = " ← ".join(path) + (f" ← الصفحة {page}" if page > 1 else "")
    out = []
    for m in msgs:
        f = file_of(m)
        if f:
            key = hashlib.md5(f"{f['name']}|{f['size']}".encode()).hexdigest()[:10]
            out.append({"_mid": m.id, "_key": key, "url": f"https://t.me/{bot}?f={key}", "open": f"https://t.me/{bot}", "file": f,
                        "text": f"{f['name']}\n{' • '.join(path)}", "date": int(time.time() * 1000),
                        "howto": f"افتح البوت ← اضغط: {where}", "srcName": title})
        elif len((m.message or "").strip()) > 80 and not NEXT.search(m.message or ""):
            out.append({"url": f"https://t.me/{bot}?m={hashlib.md5((m.message or '').encode()).hexdigest()[:10]}",
                        "text": m.message.strip(), "date": int(m.date.timestamp() * 1000), "links": links_of(m),
                        "srcName": title})
    return out


async def crawl_bot(client, ctx, s, name):
    bot = s["value"]
    ent = await client.get_entity(bot)
    if not (isinstance(ent, types.User) and getattr(ent, "bot", False)):
        raise RuntimeError("⛔ هذا المعرّف ليس بوتاً؛ الفهرسة للبوتات فقط")
    st = {}
    try:
        st = json.loads(s.get("state") or "{}")
    except Exception:  # noqa
        pass
    todo, cycle_at = st.get("todo") or [], st.get("at", 0)
    if not todo and cycle_at and time.time() - cycle_at < BOT_CYCLE_DAYS * 86400:
        return 0, None, st   # فُهرس مؤخراً
    root = menu_of(await say(client, bot, "/start"))
    if not root:
        raise RuntimeError("البوت لم يُظهر لوحة أزرار بعد /start")
    if not todo:
        todo, cycle_at = [[b] for b in root if not NAV.match(b)], 0
    actions, total = 1, 0
    while todo and actions < BOT_ACTIONS and ctx.left() > 60:
        path = todo.pop(0)
        parent = root
        await say(client, bot, "/start")
        actions += 1
        msgs = []
        for i, step in enumerate(path):
            if i == len(path) - 1:
                parent = menu_of(msgs) or parent if msgs else parent
            msgs = await say(client, bot, step)
            actions += 1
        posts, page = collect(bot, name, msgs, path, 1), 1
        while actions < BOT_ACTIONS and page < 40:
            nx = None
            for m in msgs:
                mm = NEXT.search(m.message or "")
                if mm:
                    nx = mm.group(2)
            if not nx:
                break
            msgs = await say(client, bot, nx)
            actions, page = actions + 1, page + 1
            posts += collect(bot, name, msgs, path, page)
        sub = menu_of(msgs)
        if sub and len(path) < 3 and set(sub) != set(parent) and set(sub) != set(root):
            todo += [path + [b] for b in sub if not NAV.match(b) and b not in path]
        if posts:
            await to_library(client, ctx, bot, posts)
            for p in posts:
                p.pop("_mid", None); p.pop("_key", None)
            res = ctx.send(posts, s["id"], name)
            total += len(posts)
            ctx.log(f"   🤖 {' ← '.join(path)}: {len(posts)} عنصر → {res}")
    state = {"todo": todo, "at": cycle_at if todo else time.time()}
    return total, len(todo), state


# ---------- «مكتبة وظيفتي»: قناة عامة يُوجَّه إليها كل ملف ليصير له رابط مباشر ----------
LIB_USERNAME = "wazifati_lib"


async def ensure_library(client, ctx):
    """يجد القناة أو ينشئها مرة واحدة ويحفظ بياناتها في الخادم."""
    try:
        ctx.lib = json.loads(ctx.api("/tg/secret?k=lib").get("v") or "{}")
        ctx.libmap = json.loads(ctx.api("/tg/secret?k=libmap").get("v") or "{}")
    except Exception:  # noqa
        ctx.lib, ctx.libmap = {}, {}
    if ctx.lib.get("username"):
        return
    ch = None
    async for d in client.iter_dialogs():
        e = d.entity
        if isinstance(e, types.Channel) and getattr(e, "creator", False) and (e.username or "").lower() in (LIB_USERNAME, LIB_USERNAME + "_sy"):
            ch = e
            break
    if not ch:
        r = await client(functions.channels.CreateChannelRequest(
            title="مكتبة وظيفتي 📚", about="ملفات وأدلة تدريبية مجانية يجمعها تطبيق «وظيفتي» من مصادرها، مع ذكر المصدر الأصلي.", megagroup=False))
        ch = r.chats[0]
        for un in (LIB_USERNAME, LIB_USERNAME + "_sy"):
            try:
                await client(functions.channels.UpdateUsernameRequest(ch, un))
                ch.username = un
                break
            except errors.RPCError as e:  # اسم محجوز أو غير متاح
                ctx.log(f"تعذّر حجز @{un}: {e}")
        if not ch.username:
            raise RuntimeError("أُنشئت القناة لكن تعذّر حجز اسم عام لها")
        ctx.api("/tg/notify", {"text": f"📚 أُنشئت قناة «مكتبة وظيفتي»: https://t.me/{ch.username}\nسيُوجَّه إليها كل ملف من بوتات المكتبات ليصير له رابط مباشر."})
    ctx.lib = {"id": ch.id, "username": ch.username}
    ctx.api("/tg/secret", {"k": "lib", "v": json.dumps(ctx.lib)})


async def to_library(client, ctx, bot, posts):
    """يوجّه ملفات الدفعة إلى القناة (مرة واحدة لكل ملف) ويضع الرابط المباشر في open."""
    if ctx.lib is None or ctx.no_fwd.get(bot) or not ctx.lib.get("username"):
        return
    un = ctx.lib["username"]
    todo = []
    for p in posts:
        k = f"{bot}:{p.get('_key')}"
        if not p.get("_mid"):
            continue
        if k in ctx.libmap:
            p["open"] = f"https://t.me/{un}/{ctx.libmap[k]}"
        else:
            todo.append(p)
    for i in range(0, len(todo), 20):
        chunk = todo[i:i + 20]
        try:
            res = await client.forward_messages(un, [p["_mid"] for p in chunk], from_peer=bot)
        except errors.ChatForwardsRestrictedError:
            ctx.no_fwd[bot] = True
            ctx.log(f"🔒 البوت {bot} يمنع التوجيه؛ تبقى ملفاته بطريقة «افتح البوت ← اضغط…»")
            return
        res = res if isinstance(res, list) else [res]
        for p, m in zip(chunk, res):
            if m:
                ctx.libmap[f"{bot}:{p['_key']}"] = m.id
                p["open"] = f"https://t.me/{un}/{m.id}"
        ctx.dirty = True
        await asyncio.sleep(4)


# ---------- التشغيل ----------
def report_all(ctx, sources, err):
    for s in sources:
        ctx.api("/report", {"id": s["id"], "ok": False, "err": err})


def main(sources, api, send, log):
    ctx = Ctx(api, send, log)
    api_id, api_hash = os.environ.get("TG_API_ID", "").strip(), os.environ.get("TG_API_HASH", "").strip()
    sess = ""
    try:
        sess = api("/tg/secret?k=session").get("v", "")
    except Exception as e:  # noqa
        log(f"تعذّر جلب الجلسة: {e}")
    if not (api_id and api_hash and sess):
        log(f"⏸ حساب القراءة غير مفعّل؛ {len(sources)} مصدر بانتظاره")
        report_all(ctx, sources, "⏸ بانتظار تفعيل حساب القراءة الخاص")
        return 0
    return asyncio.run(run(ctx, sources, int(api_id), api_hash, sess))


async def run(ctx, sources, api_id, api_hash, sess):
    client = TelegramClient(StringSession(sess), api_id, api_hash, device_model="Wazifati Reader",
                            system_version="Linux", app_version="2.8", lang_code="ar")
    added = 0
    await client.connect()
    try:
        if not await client.is_user_authorized():
            ctx.api("/tg/status", {"ok": False, "alert": True, "err": "الجلسة منتهية أو أُلغيت"})
            report_all(ctx, sources, "⏸ حساب القراءة يحتاج تسجيل دخول جديد")
            return 0
        me = await client.get_me()
        if any(x["kind"] == "tgbot" for x in sources):
            try:
                await ensure_library(client, ctx)
            except Exception as e:  # noqa — المكتبة اختيارية، لا توقف الفهرسة
                ctx.lib = None
                ctx.log(f"⚠️ مكتبة وظيفتي: {e}")
        ctx.api("/tg/status", {"ok": True, "name": (me.first_name or "") + (f" @{me.username}" if me.username else "")})
        # المجموعات والقنوات أولاً (سريعة)، ثم فهرسة البوتات الطويلة في ما تبقى من الوقت
        for s in sorted(sources, key=lambda x: x["kind"] == "tgbot"):
            if ctx.left() < 60:
                ctx.log("⏱ انتهى وقت حساب القراءة لهذا التشغيل؛ الباقي في التشغيل القادم")
                break
            sid, name = s["id"], s.get("name") or s["value"]
            try:
                if s["kind"] == "tgbot":
                    n, left, state = await crawl_bot(client, ctx, s, name)
                    note = "فُهرس مؤخراً" if left is None else (f"باقٍ {left} قسم" if left else "اكتملت الفهرسة")
                    ctx.api("/report", {"id": sid, "ok": True, "count": n, "state": json.dumps(state, ensure_ascii=False)})
                    ctx.log(f"🤖 {name}: {n} عنصر ({note})")
                else:
                    posts, title = await chat_posts(client, ctx, s)
                    res = ctx.send(posts, sid, title or name) if posts else {}
                    added += res.get("added", 0)
                    ctx.api("/report", {"id": sid, "ok": True, "count": len(posts), "name": title or name})
                    ctx.log(f"👤 {title or name}: {len(posts)} منشور → {res}")
            except errors.FloodWaitError as e:
                ctx.api("/report", {"id": sid, "ok": False, "err": f"⏸ تيليغرام طلب الانتظار {e.seconds} ثانية"})
                ctx.log(f"⏸ FloodWait {e.seconds}s — إيقاف حساب القراءة لهذا التشغيل")
                break
            except DEAD as e:
                ctx.api("/tg/status", {"ok": False, "alert": True, "err": type(e).__name__})
                report_all(ctx, [s], "⏸ حساب القراءة متوقف")
                break
            except Exception as e:  # noqa
                msg = str(e)[:180] or type(e).__name__
                ctx.api("/report", {"id": sid, "ok": False, "err": msg})
                ctx.log(f"❌ {name}: {msg}")
            await asyncio.sleep(3)
    finally:
        if ctx.dirty:
            try:
                ctx.api("/tg/secret", {"k": "libmap", "v": json.dumps(ctx.libmap)})
            except Exception as e:  # noqa
                ctx.log(f"تعذّر حفظ خريطة المكتبة: {e}")
        await client.disconnect()
    return added
