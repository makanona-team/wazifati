// خادم «وظيفتي» على Cloudflare Workers + Durable Object (قاعدة SQLite مجانية).
import { classify, dupKeys, LABELS } from './classify.js';
import { PRIVACY, TERMS } from './pages.js';

const VERSION = '1.0';
const DAY = 864e5;
const KEEP_DAYS = 60;          // مدة الاحتفاظ بالإعلانات
const FIRST_DAYS = 30;         // أول مزامنة للهاتف: آخر 30 يوماً
const MAX_TEXT = 3500;

const SEED_SOURCES = [
  ['tg', 'kafaatsyria', 'كفاءات'],
  ['tg', 'syrianpower', 'Syrian Power'],
  ['tg', 'KhotwaInsideSyria', 'خطوة سوريا'],
  ['tg', 'JobsvacanciesinSyriaandTurkey', 'وظائف سوريا وتركيا'],
  ['tg', 'syrjob', 'فرص عمل للسوريين'],
  ['reliefweb', 'Syria', 'ReliefWeb'],
  ['site', 'https://www.nsjobs.net/', 'North Syria Jobs'],
  ['site', 'https://sams-usa.net/careers/', 'SAMS'],
  ['site', 'https://untalent.org/jobs/in-anything/contract-all/syria', 'UN Talent'],
  ['link', 'https://lnkd.in/em7E7KPT', 'HR Reem'],
  ['link', 'https://lnkd.in/e8XJ_75', 'Job Syria'],
  ['link', 'https://lnkd.in/epR_QtMK', 'SAMS (رابط)'],
  ['link', 'https://lnkd.in/eXWSqRxn', 'Good Idea Jobs'],
  ['link', 'https://lnkd.in/e9hCCShg', 'Qutayba Al Aloush'],
  ['link', 'https://lnkd.in/eJXhKK-W', 'Nasser Darwich'],
  ['link', 'https://lnkd.in/eFg-vJss', 'Bilal Al Khatib'],
  ['link', 'https://lnkd.in/eyam-PUv', 'Wallada Almshhour'],
];

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type,X-Key',
};
const json = (o, st = 200) => new Response(JSON.stringify(o), { status: st, headers: { 'Content-Type': 'application/json; charset=utf-8', ...CORS } });

export default {
  async fetch(req, env) {
    if (req.method === 'OPTIONS') return new Response(null, { headers: CORS });
    const stub = env.DB.get(env.DB.idFromName('main'));
    return stub.fetch(req);
  },
};

export class Db {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
    this.sql = ctx.storage.sql;
    ctx.blockConcurrencyWhile(async () => this.init());
  }

  init() {
    const s = this.sql;
    s.exec(`CREATE TABLE IF NOT EXISTS jobs (
      id INTEGER PRIMARY KEY AUTOINCREMENT, k1 TEXT, k2 TEXT, url TEXT, title TEXT, org TEXT,
      specs TEXT, govs TEXT, sector TEXT, deadline TEXT, posted INTEGER, created INTEGER,
      src TEXT, srcs TEXT, apply TEXT, text TEXT, shared_by TEXT)`);
    s.exec(`CREATE INDEX IF NOT EXISTS jobs_k1 ON jobs(k1)`);
    s.exec(`CREATE INDEX IF NOT EXISTS jobs_k2 ON jobs(k2)`);
    s.exec(`CREATE INDEX IF NOT EXISTS jobs_url ON jobs(url)`);
    s.exec(`CREATE INDEX IF NOT EXISTS jobs_created ON jobs(created)`);
    s.exec(`CREATE TABLE IF NOT EXISTS seen (url TEXT PRIMARY KEY, at INTEGER)`);
    s.exec(`CREATE TABLE IF NOT EXISTS users (uid TEXT PRIMARY KEY, code TEXT UNIQUE, prefs TEXT, created INTEGER, updated INTEGER)`);
    s.exec(`CREATE TABLE IF NOT EXISTS devices (dev TEXT PRIMARY KEY, uid TEXT, seen INTEGER, ver TEXT, model TEXT)`);
    s.exec(`CREATE TABLE IF NOT EXISTS sources (
      id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, value TEXT UNIQUE, name TEXT, enabled INTEGER DEFAULT 1,
      added_by TEXT, last_try INTEGER, last_ok INTEGER, last_err TEXT, last_count INTEGER DEFAULT 0, total INTEGER DEFAULT 0, orig TEXT)`);
    s.exec(`CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT)`);
    const seeded = s.exec(`SELECT v FROM meta WHERE k='seeded'`).toArray()[0];
    if (!seeded) {
      for (const [kind, value, name] of SEED_SOURCES)
        s.exec(`INSERT OR IGNORE INTO sources(kind,value,name,added_by,orig) VALUES(?,?,?,?,?)`, kind, value, name, 'seed', value);
      s.exec(`INSERT OR REPLACE INTO meta(k,v) VALUES('seeded','1')`);
    }
  }

  async fetch(req) {
    const url = new URL(req.url);
    const p = url.pathname.replace(/\/+$/, '') || '/';
    try {
      if (req.method === 'GET') {
        if (p === '/') return this.home();
        if (p === '/privacy') return new Response(PRIVACY, { headers: { 'Content-Type': 'text/html; charset=utf-8' } });
        if (p === '/terms') return new Response(TERMS, { headers: { 'Content-Type': 'text/html; charset=utf-8' } });
        if (p === '/api') return json({ ok: true, app: 'wazifati', v: VERSION, now: Date.now() });
        if (p === '/jobs') return this.jobs(url);
        if (p === '/user') return this.getUser(url.searchParams.get('dev'), url.searchParams);
        if (p === '/sources') return this.listSources(url);
        if (p === '/labels') return json(LABELS);
        if (p === '/stats') return json(this.stats());
      }
      if (req.method === 'POST') {
        const body = await req.json().catch(() => ({}));
        if (p === '/ingest') return this.ingest(req, body);
        if (p === '/known') return this.known(req, body);
        if (p === '/report') return this.report(req, body);
        if (p === '/share') return this.share(body);
        if (p === '/user') return this.saveUser(body);
        if (p === '/restore') return this.restore(body);
        if (p === '/sources') return this.addSource(body);
        if (p === '/sources/toggle') return this.toggleSource(body);
        if (p === '/sources/delete') return this.deleteSource(body);
      }
      return json({ error: 'not found' }, 404);
    } catch (e) {
      return json({ error: String(e && e.message || e) }, 500);
    }
  }

  keyOk(req) {
    const k = this.env.INGEST_KEY;
    return k && req.headers.get('X-Key') === k;
  }

  // ===== الوظائف =====
  jobs(url) {
    const since = +url.searchParams.get('since') || 0;
    const limit = Math.min(+url.searchParams.get('limit') || 800, 2000);
    let rows;
    if (since > 0) {
      rows = this.sql.exec(`SELECT * FROM jobs WHERE id > ? ORDER BY id ASC LIMIT ?`, since, limit).toArray();
    } else {
      rows = this.sql.exec(`SELECT * FROM jobs WHERE created > ? ORDER BY id DESC LIMIT ?`, Date.now() - FIRST_DAYS * DAY, limit).toArray().reverse();
    }
    const maxRow = this.sql.exec(`SELECT MAX(id) AS m FROM jobs`).toArray()[0];
    return json({
      now: Date.now(),
      last: rows.length ? rows[rows.length - 1].id : since,
      max: (maxRow && maxRow.m) || 0,
      more: rows.length === limit,
      jobs: rows.map(r => ({
        id: r.id, t: r.title, o: r.org, s: r.specs, g: r.govs, c: r.sector, d: r.deadline,
        p: r.posted, src: r.src, n: (r.srcs || '').split('\n').filter(Boolean).length, u: r.url, a: r.apply, x: r.text, sh: r.shared_by ? 1 : 0,
      })),
    });
  }

  addJob(post, opts = {}) {
    const text = String(post.text || '').trim();
    if (text.length < 20) return 'short';
    const url = String(post.url || '').slice(0, 500);
    if (url && this.sql.exec(`SELECT 1 FROM seen WHERE url=?`, url).toArray().length) return 'seen';
    const now = Date.now();
    const posted = +post.date || now;
    if (posted < now - FIRST_DAYS * DAY && !opts.force) { this.markSeen(url); return 'old'; }
    const c = classify(text, { now: new Date(), links: post.links || [], force: opts.force, deadline: post.deadline || '', sectorHint: post.sector || '', srcName: post.srcName || '' });
    if (post.title && post.title.length > 3) c.title = String(post.title).slice(0, 140);
    if (post.org) c.org = String(post.org).slice(0, 80);
    if (!c.isJob) { this.markSeen(url); return 'notjob'; }
    if (c.deadline && Date.parse(c.deadline) < now - 2 * DAY) { this.markSeen(url); return 'expired'; }
    const { k1, k2 } = dupKeys(c, text);
    const src = String(post.srcName || post.src || '').slice(0, 60);
    const dup = this.sql.exec(`SELECT id, srcs FROM jobs WHERE k1=? OR (?<>'' AND k2=?) LIMIT 1`, k1, k2, k2).toArray()[0];
    this.markSeen(url);
    if (dup) {
      const srcs = (dup.srcs || '').split('\n');
      if (src && !srcs.includes(src)) this.sql.exec(`UPDATE jobs SET srcs=? WHERE id=?`, [...srcs, src].filter(Boolean).join('\n'), dup.id);
      return 'dup';
    }
    this.sql.exec(
      `INSERT INTO jobs(k1,k2,url,title,org,specs,govs,sector,deadline,posted,created,src,srcs,apply,text,shared_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
      k1, k2, url, c.title, c.org, c.specs.join(','), c.govs.join(','), c.sector, c.deadline, posted, now, src, src,
      c.apply, text.slice(0, MAX_TEXT), opts.sharedBy || null);
    return 'added';
  }

  markSeen(url) {
    if (url) this.sql.exec(`INSERT OR REPLACE INTO seen(url,at) VALUES(?,?)`, url, Date.now());
  }

  ingest(req, body) {
    if (!this.keyOk(req)) return json({ error: 'key' }, 403);
    const out = { added: 0, dup: 0, seen: 0, notjob: 0, old: 0, expired: 0, short: 0 };
    for (const post of (body.posts || []).slice(0, 60)) {
      const r = this.addJob(post);
      out[r] = (out[r] || 0) + 1;
    }
    if (body.sourceId && out.added) this.sql.exec(`UPDATE sources SET total = total + ? WHERE id=?`, out.added, body.sourceId);
    this.cleanup();
    return json(out);
  }

  known(req, body) {
    if (!this.keyOk(req)) return json({ error: 'key' }, 403);
    const urls = (body.urls || []).slice(0, 300);
    const known = urls.filter(u => this.sql.exec(`SELECT 1 FROM seen WHERE url=?`, u).toArray().length);
    return json({ known });
  }

  cleanup() {
    const last = +((this.sql.exec(`SELECT v FROM meta WHERE k='cleanup'`).toArray()[0] || {}).v || 0);
    if (Date.now() - last < 6 * 3600e3) return;
    const cut = Date.now() - KEEP_DAYS * DAY;
    this.sql.exec(`DELETE FROM jobs WHERE created < ?`, cut);
    this.sql.exec(`DELETE FROM seen WHERE at < ?`, Date.now() - 120 * DAY);
    this.sql.exec(`INSERT OR REPLACE INTO meta(k,v) VALUES('cleanup',?)`, String(Date.now()));
  }

  share(body) {
    const dev = String(body.dev || '').slice(0, 80);
    if (!dev) return json({ error: 'dev' }, 400);
    let text = String(body.text || '').trim();
    const link = String(body.url || '').trim();
    if (link && !text.includes(link)) text += '\n' + link;
    if (text.length < 10) return json({ error: 'empty' }, 400);
    const found = text.match(/https?:\/\/\S+/);
    const url = link || (found ? found[0] : '') || 'share:' + dev + ':' + Date.now();
    const who = String(body.name || '').slice(0, 40);
    const r = this.addJob({ text, url, srcName: 'مشاركة' + (who ? ' من ' + who : ''), date: Date.now() }, { force: true, sharedBy: dev });
    return json({ result: r });
  }

  // ===== المستخدمون (بدون تسجيل دخول: معرّف الهاتف + رمز استرجاع) =====
  newCode() {
    for (let i = 0; i < 50; i++) {
      const c = String(100000 + Math.floor(Math.random() * 900000));
      if (!this.sql.exec(`SELECT 1 FROM users WHERE code=?`, c).toArray().length) return c;
    }
    return String(Date.now()).slice(-6);
  }

  userOf(dev, create, meta = {}) {
    const d = this.sql.exec(`SELECT uid FROM devices WHERE dev=?`, dev).toArray()[0];
    if (d) {
      this.sql.exec(`UPDATE devices SET seen=?, ver=COALESCE(?,ver), model=COALESCE(?,model) WHERE dev=?`, Date.now(), meta.ver || null, meta.model || null, dev);
      return this.sql.exec(`SELECT * FROM users WHERE uid=?`, d.uid).toArray()[0];
    }
    if (!create) return null;
    const uid = crypto.randomUUID();
    const code = this.newCode();
    this.sql.exec(`INSERT INTO users(uid,code,prefs,created,updated) VALUES(?,?,?,?,?)`, uid, code, '', Date.now(), 0);
    this.sql.exec(`INSERT OR REPLACE INTO devices(dev,uid,seen,ver,model) VALUES(?,?,?,?,?)`, dev, uid, Date.now(), meta.ver || '', meta.model || '');
    return { uid, code, prefs: '', updated: 0, created: Date.now() };
  }

  getUser(dev, sp) {
    if (!dev) return json({ error: 'dev' }, 400);
    const u = this.userOf(dev, true, { ver: sp.get('ver'), model: sp.get('model') });
    return json({ code: u.code, prefs: u.prefs ? JSON.parse(u.prefs) : null, updated: u.updated });
  }

  saveUser(body) {
    const dev = String(body.dev || '');
    if (!dev) return json({ error: 'dev' }, 400);
    const u = this.userOf(dev, true);
    const prefs = JSON.stringify(body.prefs || {}).slice(0, 200000);
    const t = Date.now();
    this.sql.exec(`UPDATE users SET prefs=?, updated=? WHERE uid=?`, prefs, t, u.uid);
    return json({ ok: true, code: u.code, updated: t });
  }

  restore(body) {
    const dev = String(body.dev || ''), code = String(body.code || '').replace(/\D/g, '');
    if (!dev || code.length !== 6) return json({ error: 'رمز غير صحيح' }, 400);
    const u = this.sql.exec(`SELECT * FROM users WHERE code=?`, code).toArray()[0];
    if (!u) return json({ error: 'لا يوجد حساب بهذا الرمز' }, 404);
    this.sql.exec(`INSERT OR REPLACE INTO devices(dev,uid,seen) VALUES(?,?,?)`, dev, u.uid, Date.now());
    return json({ ok: true, code: u.code, prefs: u.prefs ? JSON.parse(u.prefs) : null, updated: u.updated });
  }

  // ===== المصادر =====
  listSources(url) {
    const all = url.searchParams.get('all') === '1';
    const rows = this.sql.exec(`SELECT * FROM sources ${all ? '' : 'WHERE enabled=1'} ORDER BY id`).toArray();
    return json({ sources: rows });
  }

  addSource(body) {
    let v = String(body.value || '').trim();
    if (!v) return json({ error: 'empty' }, 400);
    let kind = 'site', name = String(body.name || '').trim();
    let m;
    if ((m = v.match(/^(?:https?:\/\/)?(?:t\.me|telegram\.me)\/(?:s\/)?([A-Za-z0-9_]{4,})/i)) || (m = v.match(/^@?([A-Za-z][A-Za-z0-9_]{3,})$/))) {
      kind = 'tg'; v = m[1];
    } else if (/lnkd\.in|bit\.ly|tinyurl|t\.co\//i.test(v)) {
      kind = 'link';
    } else if (/facebook\.com|fb\.com|fb\.me|linkedin\.com|instagram\.com/i.test(v)) {
      kind = 'manual';
    } else if (!/^https?:\/\//i.test(v)) {
      v = 'https://' + v;
    }
    const exists = this.sql.exec(`SELECT id FROM sources WHERE lower(value)=lower(?)`, v).toArray()[0];
    if (exists) { this.sql.exec(`UPDATE sources SET enabled=1 WHERE id=?`, exists.id); return json({ ok: true, id: exists.id, existed: true }); }
    this.sql.exec(`INSERT INTO sources(kind,value,name,added_by,orig) VALUES(?,?,?,?,?)`, kind, v, name || v, String(body.dev || ''), v);
    const id = this.sql.exec(`SELECT last_insert_rowid() AS id`).toArray()[0].id;
    return json({ ok: true, id, kind });
  }

  toggleSource(body) {
    this.sql.exec(`UPDATE sources SET enabled=? WHERE id=?`, body.enabled ? 1 : 0, +body.id);
    return json({ ok: true });
  }

  deleteSource(body) {
    this.sql.exec(`DELETE FROM sources WHERE id=?`, +body.id);
    return json({ ok: true });
  }

  report(req, b) {
    if (!this.keyOk(req)) return json({ error: 'key' }, 403);
    const id = +b.id;
    if (b.resolved && b.resolved.kind) {
      const { kind, value } = b.resolved;
      const clash = this.sql.exec(`SELECT id FROM sources WHERE lower(value)=lower(?) AND id<>?`, value, id).toArray()[0];
      if (clash) this.sql.exec(`UPDATE sources SET enabled=0, kind=?, last_err=? WHERE id=?`, kind, 'مكرر مع مصدر آخر', id);
      else this.sql.exec(`UPDATE sources SET kind=?, value=? WHERE id=?`, kind, value, id);
    }
    if (b.name) this.sql.exec(`UPDATE sources SET name=? WHERE id=? AND (name=value OR name='' OR name IS NULL)`, String(b.name).slice(0, 60), id);
    if (b.ok) this.sql.exec(`UPDATE sources SET last_try=?, last_ok=?, last_err=NULL, last_count=? WHERE id=?`, Date.now(), Date.now(), +b.count || 0, id);
    else this.sql.exec(`UPDATE sources SET last_try=?, last_err=? WHERE id=?`, Date.now(), String(b.err || 'خطأ').slice(0, 200), id);
    if (b.final) this.sql.exec(`INSERT OR REPLACE INTO meta(k,v) VALUES('last_run',?)`, String(Date.now()));
    return json({ ok: true });
  }

  stats() {
    const one = q => this.sql.exec(q).toArray()[0];
    return {
      jobs: one(`SELECT COUNT(*) AS n FROM jobs`).n,
      today: this.sql.exec(`SELECT COUNT(*) AS n FROM jobs WHERE created > ?`, Date.now() - DAY).toArray()[0].n,
      users: one(`SELECT COUNT(*) AS n FROM users`).n,
      devices: one(`SELECT COUNT(*) AS n FROM devices`).n,
      lastRun: +((one(`SELECT v FROM meta WHERE k='last_run'`) || {}).v || 0),
    };
  }

  home() {
    const st = this.stats();
    const src = this.sql.exec(`SELECT * FROM sources ORDER BY enabled DESC, id`).toArray();
    const ago = t => !t ? '—' : (Date.now() - t < 3600e3 ? Math.round((Date.now() - t) / 60e3) + ' د' : Math.round((Date.now() - t) / 3600e3) + ' س');
    const esc = s => String(s == null ? '' : s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
    const kinds = { tg: 'تيليغرام', site: 'موقع', reliefweb: 'ReliefWeb', link: 'رابط مختصر', manual: 'بالمشاركة فقط' };
    const rows = src.map(s => `<tr class="${s.enabled ? '' : 'off'}"><td>${esc(s.name)}<br><small>${esc(s.value)}</small></td><td>${kinds[s.kind] || s.kind}</td><td>${s.last_err ? '❌ ' + esc(s.last_err) : (s.last_ok ? '✅' : '⏳')}</td><td>${ago(s.last_ok)}</td><td>${s.last_count || 0} / ${s.total || 0}</td></tr>`).join('');
    const html = `<!doctype html><html lang="ar" dir="rtl"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>وظيفتي — حالة الخادم</title>
<style>body{font-family:system-ui,sans-serif;margin:12px;background:#f4f6fb;color:#1c2333}h1{font-size:20px}.c{display:flex;gap:8px;flex-wrap:wrap}.c div{background:#fff;border-radius:12px;padding:10px 14px;box-shadow:0 1px 3px #0001}.c b{display:block;font-size:22px;color:#1a73e8}table{width:100%;border-collapse:collapse;background:#fff;border-radius:12px;overflow:hidden;margin-top:12px;font-size:13px}td{padding:8px;border-bottom:1px solid #eee;vertical-align:top}small{color:#888;word-break:break-all}.off{opacity:.45}</style>
<h1>💼 وظيفتي — حالة الخادم (v${VERSION})</h1>
<div class="c"><div><b>${st.jobs}</b>إعلان محفوظ</div><div><b>${st.today}</b>جديد آخر 24 ساعة</div><div><b>${st.users}</b>مستخدم</div><div><b>${ago(st.lastRun)}</b>آخر جلب</div></div>
<table><tr><td><b>المصدر</b></td><td><b>النوع</b></td><td><b>الحالة</b></td><td><b>آخر نجاح</b></td><td><b>آخر جلب / المضاف</b></td></tr>${rows}</table></html>`;
    return new Response(html, { headers: { 'Content-Type': 'text/html; charset=utf-8' } });
  }
}
