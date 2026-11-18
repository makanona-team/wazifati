#!/usr/bin/env python3
"""جالب «وظيفتي»: يقرأ المصادر من الخادم، يجلب الإعلانات الجديدة ويرسلها للخادم ليصنّفها ويحفظها.
يعمل كل ساعة على GitHub Actions. مكتبة بايثون القياسية فقط.
"""
import html
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

API = os.environ.get("WZ_API", "").rstrip("/")
KEY = os.environ.get("WZ_KEY", "")
UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
MAX_NEW_PER_SITE = 15
LOG = []


def log(*a):
    s = " ".join(str(x) for x in a)
    LOG.append(s)
    print(s, flush=True)


def http(url, data=None, headers=None, timeout=30):
    h = {"User-Agent": UA, "Accept-Language": "ar,en;q=0.8"}
    if headers:
        h.update(headers)
    body = None
    if data is not None:
        body = json.dumps(data).encode() if not isinstance(data, (bytes, str)) else (data.encode() if isinstance(data, str) else data)
        h.setdefault("Content-Type", "application/json")
    req = urllib.request.Request(url, data=body, headers=h, method="POST" if body is not None else "GET")
    with urllib.request.urlopen(req, timeout=timeout) as r:
        raw = r.read()
        cs = r.headers.get_content_charset() or "utf-8"
        return r.geturl(), raw.decode(cs, "replace")


def api(path, data=None):
    _, t = http(API + path, data, {"X-Key": KEY}, timeout=60)
    return json.loads(t)


# ---------- HTML إلى نص ----------
def to_text(h):
    h = re.sub(r"(?is)<(script|style|noscript|svg|head|nav|footer|header|form)[^>]*>.*?</\1>", " ", h)
    h = re.sub(r"(?i)<br\s*/?>", "\n", h)
    h = re.sub(r"(?i)</(p|div|li|h[1-6]|tr|section|article|ul|ol|table)>", "\n", h)
    h = re.sub(r"(?i)<li[^>]*>", "\n• ", h)
    h = re.sub(r"(?s)<[^>]+>", "", h)
    h = html.unescape(h)
    h = re.sub(r"[ \t ]+", " ", h)
    h = re.sub(r"\n\s*\n+", "\n", h)
    return h.strip()


def links_of(h):
    return [html.unescape(u) for u in re.findall(r'href="(https?://[^"]+)"', h)]


# ---------- تيليغرام (صفحة القناة العامة t.me/s) ----------
def telegram(name):
    _, page = http(f"https://t.me/s/{name}")
    if "tgme_widget_message" not in page:
        raise Exception("القناة غير متاحة للمعاينة العامة")
    title = re.search(r'<meta property="og:title" content="([^"]*)"', page)
    posts = []
    for blk in page.split('data-post="')[1:]:
        pid = blk.split('"', 1)[0]
        m = re.search(r'<div class="tgme_widget_message_text[^"]*"[^>]*>(.*?)</div>', blk, re.S)
        if not m:
            continue
        inner = m.group(1)
        text = to_text(inner)
        dt = re.search(r'<time datetime="([^"]+)"', blk)
        ts = int(datetime.fromisoformat(dt.group(1)).timestamp() * 1000) if dt else int(time.time() * 1000)
        posts.append({"url": f"https://t.me/{pid}", "text": text, "date": ts, "links": links_of(inner)})
    return posts, (html.unescape(title.group(1)) if title else "")


# ---------- ReliefWeb ----------
def reliefweb(country):
    q = {
        "filter": {"field": "country.name", "value": country},
        "fields": {"include": ["title", "body", "url", "source.name", "date.closing", "date.created", "city.name", "how_to_apply"]},
        "sort": ["date.created:desc"],
        "limit": 40,
    }
    _, t = http("https://api.reliefweb.int/v2/jobs?appname=wazifati-syria", q)
    data = json.loads(t)
    posts = []
    for it in data.get("data", []):
        f = it.get("fields", {})
        org = ", ".join(s.get("name", "") for s in f.get("source", []) if s.get("name"))
        cities = ", ".join(c.get("name", "") for c in f.get("city", []) if c.get("name"))
        body = re.sub(r"[#*_]+", "", f.get("body", "") or "")
        apply_txt = re.sub(r"[#*_]+", "", f.get("how_to_apply", "") or "")
        text = f"{f.get('title','')}\nOrganization: {org}\nLocation: {cities or country}\n\n{body[:2500]}\n\nHow to apply:\n{apply_txt[:600]}"
        created = f.get("date", {}).get("created")
        ts = int(datetime.fromisoformat(created).timestamp() * 1000) if created else int(time.time() * 1000)
        posts.append({
            "url": f.get("url") or f"https://reliefweb.int/job/{it.get('id')}",
            "title": f.get("title", ""), "org": org, "text": text, "date": ts,
            "deadline": (f.get("date", {}).get("closing") or "")[:10], "sector": "ngo",
        })
    return posts


# ---------- مواقع عامة: روابط تبدو إعلانات وظائف ثم صفحة كل إعلان ----------
JOB_HREF = re.compile(r"(job|vacanc|career|position|employment|opportunit|recruit|apply|وظ|فرص)", re.I)
JOB_TEXT = re.compile(r"(officer|manager|assistant|coordinator|engineer|specialist|advisor|consultant|director|analyst|supervisor|nurse|doctor|teacher|driver|accountant|developer|technician|lead|head of|intern|associate|worker|مسؤول|مدير|مساعد|منسق|مهندس|اخصائي|أخصائي|مستشار|محاسب|ممرض|طبيب|معلم|سائق|مشرف|فني|موظف|مطور|مدرب|باحث|مسؤولة|مديرة|منسقة)", re.I)
NAV = re.compile(r"^(home|about|contact|login|sign|register|privacy|terms|more|next|previous|read more|apply now|الرئيسية|من نحن|اتصل|تسجيل|المزيد|التالي|السابق)$", re.I)


def site(url):
    final, page = http(url)
    host = urllib.parse.urlparse(final).netloc.replace("www.", "")
    cands, seen = [], set()
    for m in re.finditer(r'<a\s[^>]*href="([^"#]+)"[^>]*>(.*?)</a>', page, re.S | re.I):
        href = urllib.parse.urljoin(final, html.unescape(m.group(1)))
        text = to_text(m.group(2)).replace("\n", " ").strip()
        if not href.startswith("http") or href.rstrip("/") == final.rstrip("/") or href in seen:
            continue
        h2 = urllib.parse.urlparse(href).netloc.replace("www.", "")
        same = h2 == host or h2.endswith("." + host) or host.endswith("." + h2) or "recruit" in h2 or "career" in h2
        if not same or not (8 <= len(text) <= 160) or NAV.match(text):
            continue
        path = urllib.parse.urlparse(href).path
        if not (JOB_TEXT.search(text) or (JOB_HREF.search(path) and len(path.strip("/").split("/")) >= 2)):
            continue
        if re.search(r"(/tag/|/category/|/page/|/author/|login|signup|\.pdf$|\.jpg$|\.png$)", href, re.I):
            continue
        seen.add(href)
        cands.append((href, text))
    cands = cands[:60]
    if not cands:
        return [], 0
    known = set(api("/known", {"urls": [c[0] for c in cands]}).get("known", []))
    posts = []
    for href, title in cands:
        if href in known:
            continue
        if len(posts) >= MAX_NEW_PER_SITE:
            break
        try:
            _, dp = http(href, timeout=25)
            main = re.search(r"(?is)<(article|main)[^>]*>(.*?)</\1>", dp)
            body = to_text(main.group(2) if main else dp)
            posts.append({"url": href, "title": title, "text": (title + "\n" + body)[:4000], "date": int(time.time() * 1000), "links": links_of(dp)[:40]})
        except Exception as e:  # noqa
            log("   تفاصيل فشلت:", href, e)
        time.sleep(0.7)
    return posts, len(cands)


# ---------- روابط مختصرة (lnkd.in …) ----------
def resolve(url):
    final, page = http(url)
    target = final
    if "lnkd.in" in final or "linkedin.com/safety" in final:
        m = re.search(r'data-tracking-control-name="external_url_click"[^>]*href="([^"]+)"', page) or \
            re.search(r'<a[^>]+class="[^"]*artdeco-button[^"]*"[^>]+href="([^"]+)"', page) or \
            re.search(r'href="(https?://(?!(?:www\.)?(?:lnkd\.in|linkedin\.com/(?:safety|redir|legal|help)))[^"]+)"', page)
        if not m:
            raise Exception("تعذّر فك الرابط المختصر")
        target = html.unescape(m.group(1))
        try:
            target, _ = http(target)
        except Exception:
            pass
    tg = re.match(r"https?://(?:t\.me|telegram\.me)/(?:s/)?([A-Za-z0-9_]{4,})", target)
    if tg:
        return {"kind": "tg", "value": tg.group(1)}
    if re.search(r"(facebook\.com|fb\.com|fb\.me|linkedin\.com|instagram\.com)", target, re.I):
        return {"kind": "manual", "value": target.split("?")[0]}
    return {"kind": "site", "value": target}


def send(posts, sid, name):
    tot = {}
    for i in range(0, len(posts), 12):
        batch = [dict(p, srcName=p.get("srcName") or name) for p in posts[i:i + 12]]
        r = api("/ingest", {"posts": batch, "sourceId": sid})
        for k, v in r.items():
            if isinstance(v, int):
                tot[k] = tot.get(k, 0) + v
    return tot


def main():
    if not API or not KEY:
        print("WZ_API / WZ_KEY غير مضبوطين", file=sys.stderr)
        sys.exit(1)
    sources = api("/sources").get("sources", [])
    log(f"المصادر المفعّلة: {len(sources)}")
    added_all = 0
    for s in sources:
        sid, kind, value, name = s["id"], s["kind"], s["value"], s.get("name") or s["value"]
        try:
            if kind == "link":
                r = resolve(value)
                api("/report", {"id": sid, "ok": True, "count": 0, "resolved": r})
                log(f"🔗 {name}: {value} → {r['kind']} {r['value']}")
                kind, value = r["kind"], r["value"]
            if kind == "manual":
                api("/report", {"id": sid, "ok": True, "count": 0})
                continue
            if kind == "tg":
                posts, title = telegram(value)
                if title and name == value:
                    name = title
            elif kind == "reliefweb":
                posts = reliefweb(value)
            elif kind == "site":
                posts, _ = site(value)
            else:
                continue
            res = send(posts, sid, name) if posts else {}
            added = res.get("added", 0)
            added_all += added
            api("/report", {"id": sid, "ok": True, "count": len(posts), "name": name})
            log(f"✅ {name} ({kind}): {len(posts)} منشور → {res}")
        except urllib.error.HTTPError as e:
            api("/report", {"id": sid, "ok": False, "err": f"HTTP {e.code}"})
            log(f"❌ {name}: HTTP {e.code}")
        except Exception as e:  # noqa
            api("/report", {"id": sid, "ok": False, "err": str(e)[:180]})
            log(f"❌ {name}: {e}")
        time.sleep(1)
    api("/report", {"id": 0, "ok": True, "final": True})
    log(f"انتهى. أُضيف {added_all} إعلاناً جديداً.")


if __name__ == "__main__":
    main()
