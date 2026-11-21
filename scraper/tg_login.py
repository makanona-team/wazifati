"""تسجيل دخول حساب القراءة الخاص على مرحلتين (من GitHub Actions):
  1) send : يرسل رمز الدخول إلى تيليغرام ويحفظ حالة مؤقتة في الخادم.
  2) verify: يُدخل الرمز (وكلمة سر التحقق بخطوتين إن وُجدت) ويحفظ الجلسة في الخادم.
لا يطبع أي سر في السجل.
"""
import asyncio
import json
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from scrape import api  # noqa: E402
from telethon import TelegramClient, errors  # noqa: E402
from telethon.sessions import StringSession  # noqa: E402

API_ID = int(os.environ["TG_API_ID"].strip())
API_HASH = os.environ["TG_API_HASH"].strip()


def client(sess=""):
    return TelegramClient(StringSession(sess), API_ID, API_HASH, device_model="Wazifati Reader",
                          system_version="Linux", app_version="2.8", lang_code="ar")


async def send(phone):
    c = client()
    await c.connect()
    r = await c.send_code_request(phone)
    api("/tg/secret", {"k": "login", "v": json.dumps({"s": c.session.save(), "h": r.phone_code_hash, "p": phone})})
    await c.disconnect()
    t = type(r.type).__name__
    where = {"SentCodeTypeApp": "داخل تطبيق تيليغرام: محادثة «Telegram» الرسمية (علامة زرقاء) على الجهاز المسجَّل بهذا الرقم",
             "SentCodeTypeSms": "برسالة SMS إلى الرقم", "SentCodeTypeCall": "بمكالمة هاتفية",
             "SentCodeTypeFlashCall": "بمكالمة فائتة (آخر أرقام الرقم المتصل هي الرمز)",
             "SentCodeTypeEmailCode": "إلى البريد الإلكتروني المربوط بحساب تيليغرام",
             "SentCodeTypeFragmentSms": "عبر Fragment (رقم مجهول)",
             "SentCodeTypeSetUpEmailRequired": "تيليغرام يطلب ربط بريد إلكتروني بالحساب أولاً من إعدادات التطبيق"}.get(t, t)
    nxt = type(r.next_type).__name__ if getattr(r, "next_type", None) else "—"
    print(f"✅ أُرسل رمز الدخول — طريقة الإرسال: {where}  [{t} / التالي: {nxt}]")
    print("ثم شغّل المرحلة 2 بسرعة. لا تطلب رمزاً جديداً أكثر من 3 مرات في الساعة حتى لا يحظر تيليغرام الطلبات مؤقتاً.")


async def verify(code, pw):
    st = json.loads(api("/tg/secret?k=login").get("v") or "{}")
    if not st:
        sys.exit("❌ لا توجد مرحلة 1 محفوظة. شغّل المرحلة 1 أولاً.")
    c = client(st["s"])
    await c.connect()
    try:
        await c.sign_in(st["p"], code.strip().replace(" ", ""), phone_code_hash=st["h"])
    except errors.SessionPasswordNeededError:
        if not pw:
            sys.exit("❌ الحساب عليه تحقق بخطوتين: أضف كلمة السر في خانة password أو كسرّ TG_2FA ثم أعد المرحلة 2.")
        await c.sign_in(password=pw)
    except (errors.PhoneCodeInvalidError, errors.PhoneCodeExpiredError) as e:
        sys.exit(f"❌ الرمز خاطئ أو انتهت صلاحيته ({type(e).__name__}). أعد المرحلة 1 ثم 2.")
    me = await c.get_me()
    api("/tg/secret", {"k": "session", "v": c.session.save()})
    api("/tg/secret", {"k": "login", "v": ""})
    api("/tg/status", {"ok": True, "name": me.first_name or ""})
    await c.disconnect()
    print(f"✅ تم تسجيل الدخول باسم {me.first_name}. حساب القراءة جاهز، يبدأ العمل من الجلب القادم.")


if __name__ == "__main__":
    step = os.environ.get("STEP", "")
    if step.startswith("1"):
        phone = (os.environ.get("PHONE") or os.environ.get("TG_PHONE") or "").strip().replace(" ", "")
        if not phone:
            sys.exit("❌ اكتب رقم الهاتف بالصيغة الدولية مثل +9639xxxxxxxx")
        asyncio.run(send(phone))
    else:
        asyncio.run(verify(os.environ.get("CODE", ""), os.environ.get("PASSWORD") or os.environ.get("TG_2FA") or ""))
