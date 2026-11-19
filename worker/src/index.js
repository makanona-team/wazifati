// خادم «وظيفتي» على Cloudflare Workers + Durable Object (قاعدة SQLite مجانية).
// الدخول بحساب Google فقط. أدوار: admin / user.
import { classify, dupKeys, LABELS, jobScore, tenderInfo } from './classify.js';
import { PRIVACY, TERMS } from './pages.js';
import { analyzeCV, diffProfiles } from './cv.js';

const VERSION = '1.9';
const GUEST_JOBS = 10;      // الزائر يرى أحدث 10 وظائف
const GUEST_TENDERS = 2;    // وأحدث مناقصتين
const RELEASE = 'https://github.com/makanona-team/wazifati/releases/latest';
const FAIL_DAYS = 3;
const DAY = 864e5;
const KEEP_DAYS = 60;
const FIRST_DAYS = 30;
const MAX_TEXT = 3500;
const FIRST_ADMIN = 'eng.feras8585@gmail.com';

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
];

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type,X-Key,Authorization',
};
const json = (o, st = 200) => new Response(JSON.stringify(o), { status: st, headers: { 'Content-Type': 'application/json; charset=utf-8', ...CORS } });
class HttpErr extends Error { constructor(st, m) { super(m); this.st = st; } }

export default {
  async fetch(req, env) {
    if (req.method === 'OPTIONS') return new Response(null, { headers: CORS });
    const stub = env.DB.get(env.DB.idFromName('main'));
    return stub.fetch(req);
  },
};

/** مفتاح موحّد لكشف تكرار المصادر بأي صيغة كُتبت. */
export function sourceKey(kind, value) {
  let v = String(value || '').trim();
  let m;
  if (kind === 'tg' || (m = v.match(/^(?:https?:\/\/)?(?:www\.)?(?:t\.me|telegram\.me)\/(?:s\/)?([A-Za-z0-9_]{4,})/i)) || (m = v.match(/^@([A-Za-z][A-Za-z0-9_]{3,})$/))) {
    if (m) v = m[1];
    return 'tg:' + v.replace(/^@/, '').toLowerCase();
  }
  if (kind === 'reliefweb') return 'rw:' + v.toLowerCase();
  return 'url:' + v.toLowerCase().replace(/^https?:\/\//, '').replace(/^www\./, '').replace(/[?#].*$/, '').replace(/\/+$/, '');
}

export class Db {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
    this.sql = ctx.storage.sql;
    ctx.blockConcurrencyWhile(async () => this.init());
  }

  q(query, ...b) { return this.sql.exec(query, ...b).toArray(); }
  one(query, ...b) { return this.q(query, ...b)[0]; }
  run(query, ...b) { this.sql.exec(query, ...b); }
  tryRun(query) { try { this.sql.exec(query); } catch (e) { /* العمود موجود */ } }

  init() {
    this.run(`CREATE TABLE IF NOT EXISTS jobs (
      id INTEGER PRIMARY KEY AUTOINCREMENT, k1 TEXT, k2 TEXT, url TEXT, title TEXT, org TEXT,
      specs TEXT, govs TEXT, sector TEXT, deadline TEXT, posted INTEGER, created INTEGER,
      src TEXT, srcs TEXT, apply TEXT, text TEXT, shared_by TEXT)`);
    this.tryRun(`ALTER TABLE jobs ADD COLUMN updated INTEGER DEFAULT 0`);
    for (const col of ['image TEXT', 'doubt INTEGER DEFAULT 0', 'hidden INTEGER DEFAULT 0', 'flag_ok INTEGER DEFAULT 0', "kind TEXT DEFAULT 'job'", 'tcat TEXT', 'bond TEXT']) this.tryRun(`ALTER TABLE jobs ADD COLUMN ${col}`);
    this.run(`CREATE TABLE IF NOT EXISTS flags (job_id INTEGER, email TEXT, created INTEGER, PRIMARY KEY(job_id, email))`);
    this.run(`CREATE INDEX IF NOT EXISTS jobs_k1 ON jobs(k1)`);
    this.run(`CREATE INDEX IF NOT EXISTS jobs_k2 ON jobs(k2)`);
    this.run(`CREATE INDEX IF NOT EXISTS jobs_url ON jobs(url)`);
    this.run(`CREATE INDEX IF NOT EXISTS jobs_created ON jobs(created)`);
    this.run(`CREATE INDEX IF NOT EXISTS jobs_updated ON jobs(updated)`);
    this.run(`CREATE TABLE IF NOT EXISTS seen (url TEXT PRIMARY KEY, at INTEGER)`);
    this.run(`CREATE TABLE IF NOT EXISTS sources (
      id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, value TEXT UNIQUE, name TEXT, enabled INTEGER DEFAULT 1,
      added_by TEXT, last_try INTEGER, last_ok INTEGER, last_err TEXT, last_count INTEGER DEFAULT 0, total INTEGER DEFAULT 0, orig TEXT)`);
    this.tryRun(`ALTER TABLE sources ADD COLUMN nkey TEXT`);
    this.tryRun(`ALTER TABLE sources ADD COLUMN pending INTEGER DEFAULT 0`);
    this.tryRun(`ALTER TABLE sources ADD COLUMN created INTEGER DEFAULT 0`);
    this.tryRun(`ALTER TABLE sources ADD COLUMN rejected INTEGER DEFAULT 0`);
    this.tryRun(`ALTER TABLE sources ADD COLUMN fail_since INTEGER`);
    this.tryRun(`ALTER TABLE sources ADD COLUMN auto_off INTEGER DEFAULT 0`);
    this.run(`CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT)`);
    this.run(`CREATE TABLE IF NOT EXISTS accounts (
      email TEXT PRIMARY KEY, name TEXT, photo TEXT, role TEXT DEFAULT 'user', status TEXT DEFAULT 'active',
      prefs TEXT, prefs_updated INTEGER DEFAULT 0, created INTEGER, last_seen INTEGER, model TEXT, ver TEXT)`);
    for (const col of ['cv_text TEXT', 'cv_profile TEXT', 'cv_name TEXT', 'cv_updated INTEGER DEFAULT 0']) this.tryRun(`ALTER TABLE accounts ADD COLUMN ${col}`);
    this.run(`CREATE TABLE IF NOT EXISTS sessions (token TEXT PRIMARY KEY, email TEXT, created INTEGER, dev TEXT)`);
    this.run(`CREATE TABLE IF NOT EXISTS fixes (
      id INTEGER PRIMARY KEY AUTOINCREMENT, job_id INTEGER, email TEXT, specs TEXT, govs TEXT, sector TEXT, note TEXT,
      status TEXT DEFAULT 'pending', created INTEGER)`);
    this.run(`CREATE TABLE IF NOT EXISTS announcements (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT, email TEXT, created INTEGER)`);

    if (!this.one(`SELECT v FROM meta WHERE k='seeded'`)) {
      for (const [kind, value, name] of SEED_SOURCES)
        this.run(`INSERT OR IGNORE INTO sources(kind,value,name,added_by,orig) VALUES(?,?,?,?,?)`, kind, value, name, 'seed', value);
      this.run(`INSERT OR REPLACE INTO meta(k,v) VALUES('seeded','1')`);
    }
    // ترحيل 1.1: مفاتيح التكرار، وإزالة المصادر المكررة القديمة
    if (!this.one(`SELECT v FROM meta WHERE k='mig11'`)) {
      const seenKeys = new Set();
      for (const s of this.q(`SELECT id, kind, value FROM sources ORDER BY id`)) {
        const k = sourceKey(s.kind, s.value);
        if (seenKeys.has(k)) this.run(`DELETE FROM sources WHERE id=?`, s.id);
        else { seenKeys.add(k); this.run(`UPDATE sources SET nkey=?, pending=0, created=COALESCE(created,0) WHERE id=?`, k, s.id); }
      }
      this.run(`INSERT OR REPLACE INTO meta(k,v) VALUES('mig11','1')`);
    }
    // ترحيل 1.5: تنظيف الإعلانات الموجودة بفلتر «درجة الوظيفة»
    if (!this.one(`SELECT v FROM meta WHERE k='mig15'`)) {
      let removed = 0, doubt = 0;
      const now = Date.now();
      for (const j of this.q(`SELECT id, text, shared_by FROM jobs WHERE hidden=0`)) {
        const c = classify(j.text || '', { now: new Date(), force: !!j.shared_by });
        const v = jobScore(j.text || '', c);
        if (v.verdict === 'reject' && (!j.shared_by || v.score <= -10)) { this.run(`UPDATE jobs SET hidden=2, updated=? WHERE id=?`, now, j.id); removed++; }
        else if (v.verdict === 'doubt') { this.run(`UPDATE jobs SET doubt=1, updated=? WHERE id=?`, now, j.id); doubt++; }
      }
      this.run(`INSERT OR REPLACE INTO meta(k,v) VALUES('mig15',?)`, JSON.stringify({ removed, doubt, at: now }));
    }
    // ترحيل 1.6: فرز المناقصات من بين الوظائف
    if (!this.one(`SELECT v FROM meta WHERE k='mig16'`)) {
      let n = 0;
      const now = Date.now();
      for (const j of this.q(`SELECT id, title, specs, text FROM jobs WHERE hidden=0`)) {
        const c = { title: j.title || '', specs: String(j.specs || '').split(',') };
        const t = tenderInfo(j.text || '', c);
        if (t) { this.run(`UPDATE jobs SET kind='tender', tcat=?, bond=?, doubt=0, updated=? WHERE id=?`, t.tcat, t.bond, now, j.id); n++; }
      }
      this.run(`INSERT OR REPLACE INTO meta(k,v) VALUES('mig16',?)`, String(n));
    }
    this.run(`INSERT OR IGNORE INTO accounts(email,role,status,created) VALUES(?,?,?,?)`, FIRST_ADMIN, 'admin', 'active', Date.now());
    this.run(`UPDATE accounts SET role='admin', status='active' WHERE email=?`, FIRST_ADMIN);
  }

  async fetch(req) {
    const url = new URL(req.url);
    const p = url.pathname.replace(/\/+$/, '') || '/';
    try {
      const b = req.method === 'POST' ? await body(req) : {};
      // ----- عامة / للجالب -----
      if (req.method === 'GET' && p === '/') return this.home();
      if (req.method === 'GET' && p === '/privacy') return new Response(PRIVACY, { headers: { 'Content-Type': 'text/html; charset=utf-8' } });
      if (req.method === 'GET' && p === '/terms') return new Response(TERMS, { headers: { 'Content-Type': 'text/html; charset=utf-8' } });
      if (req.method === 'GET' && p === '/api') return json({ ok: true, app: 'wazifati', v: VERSION, now: Date.now(), login: !!this.env.GOOGLE_CLIENT_ID, ...this.minInfo() });
      if (req.method === 'POST' && p === '/auth') return await this.auth(b);
      if (this.keyOk(req)) {
        if (p === '/ingest') return this.ingest(b);
        if (p === '/known') return this.known(b);
        if (p === '/report') return this.report(b);
        if (p === '/sources') return json({ sources: this.q(`SELECT * FROM sources WHERE enabled=1 AND pending=0 ORDER BY id`) });
      }

      // ----- قراءة عامة (تصفّح كضيف + صفحات المشاركة) -----
      if (req.method === 'GET') {
        let m;
        if ((m = p.match(/^\/j\/(\d+)$/))) return this.jobPage(+m[1]);
        if (p === '/jobs') return this.jobs(url, req);
        if (p === '/changes') return this.changes(url);
        if (p === '/announce') {
          if (url.searchParams.get('all')) return json({ items: this.q(`SELECT id,text,created FROM announcements ORDER BY id DESC LIMIT 50`), ...this.minInfo() });
          return json({ items: this.q(`SELECT id,text,created FROM announcements WHERE id > ? ORDER BY id DESC LIMIT 20`, +url.searchParams.get('since') || 0) });
        }
        if (p === '/labels') return json(LABELS);
      }

      // ----- مستخدم مسجّل -----
      const me = this.who(req);
      if (req.method === 'GET') {
        if (p === '/me') return this.getMe(me, url.searchParams);
      }
      if (req.method === 'POST') {
        if (p === '/me') return this.saveMe(me, b);
        if (p === '/logout') { this.run(`DELETE FROM sessions WHERE token=?`, bearer(req)); return json({ ok: true }); }
        if (p === '/share') return this.share(me, b);
        if (p === '/suggest') return this.suggest(me, b);
        if (p === '/fix') return this.fix(me, b);
        if (p === '/cv') return this.saveCV(me, b);
        if (p === '/flag') return this.flag(me, b);
        if (p === '/cv/delete') { this.run(`UPDATE accounts SET cv_text=NULL, cv_profile=NULL, cv_name=NULL, cv_updated=0 WHERE email=?`, me.email); return json({ ok: true }); }
      }

      // ----- أدمن -----
      if (me.role !== 'admin') throw new HttpErr(403, 'هذه الصفحة للأدمن فقط');
      if (req.method === 'GET') {
        if (p === '/admin/sources') return json({ sources: this.q(`SELECT * FROM sources ORDER BY pending DESC, enabled DESC, id`) });
        if (p === '/admin/users') return json({ users: this.users() });
        if (p === '/admin/fixes') return json({ fixes: this.q(`SELECT f.*, j.title, j.specs AS j_specs, j.govs AS j_govs, j.sector AS j_sector FROM fixes f LEFT JOIN jobs j ON j.id=f.job_id WHERE f.status='pending' ORDER BY f.id DESC LIMIT 100`) });
        if (p === '/admin/pending') return json(this.pending(+url.searchParams.get('since') || 0));
        if (p === '/admin/stats') return json(this.stats());
        if (p === '/admin/flags') return json({ flags: this.q(`SELECT j.id, j.title, j.src, j.url, j.hidden, COUNT(f.email) AS n, GROUP_CONCAT(f.email, '، ') AS who, MAX(f.created) AS last
          FROM flags f JOIN jobs j ON j.id=f.job_id WHERE j.hidden<2 GROUP BY j.id ORDER BY last DESC LIMIT 100`) });
      }
      if (req.method === 'POST') {
        if (p === '/admin/sources/approve') return this.approve(b);
        if (p === '/admin/sources/toggle') { this.run(`UPDATE sources SET enabled=?, fail_since=NULL, auto_off=0 WHERE id=?`, b.enabled ? 1 : 0, +b.id); return json({ ok: true }); }
        if (p === '/admin/sources/delete') { this.run(`DELETE FROM sources WHERE id=?`, +b.id); return json({ ok: true }); }
        if (p === '/admin/users/role') return this.setRole(me, b);
        if (p === '/admin/users/status') return this.setStatus(me, b);
        if (p === '/admin/fixes/decide') return this.decideFix(b);
        if (p === '/admin/announce') return this.announce(me, b);
        if (p === '/admin/announce/delete') { this.run(`DELETE FROM announcements WHERE id=?`, +b.id); return json({ ok: true }); }
        if (p === '/admin/minver') return this.setMinVer(b);
        if (p === '/admin/flags/decide') return this.decideFlag(b);
      }
      return json({ error: 'not found' }, 404);
    } catch (e) {
      return json({ error: String(e && e.message || e) }, e && e.st || 500);
    }
  }

  keyOk(req) {
    const k = this.env.INGEST_KEY;
    return !!k && req.headers.get('X-Key') === k;
  }

  // ===== الدخول بحساب Google =====
  async auth(b) {
    const cid = this.env.GOOGLE_CLIENT_ID;
    if (!cid) throw new HttpErr(500, 'تسجيل الدخول غير مهيأ على الخادم');
    const tok = String(b.idToken || '');
    if (!tok) throw new HttpErr(400, 'لا يوجد رمز دخول');
    const r = await fetch('https://oauth2.googleapis.com/tokeninfo?id_token=' + encodeURIComponent(tok));
    const info = await r.json().catch(() => ({}));
    if (!r.ok || info.error) throw new HttpErr(401, 'رمز Google غير صالح');
    if (info.aud !== cid) throw new HttpErr(401, 'الرمز ليس لهذا التطبيق');
    if (!/accounts\.google\.com$/.test(info.iss || '')) throw new HttpErr(401, 'مصدر الرمز غير صحيح');
    if (String(info.email_verified) !== 'true') throw new HttpErr(401, 'إيميل غير موثّق');
    const email = String(info.email).toLowerCase();
    const now = Date.now();
    const acc = this.one(`SELECT * FROM accounts WHERE email=?`, email);
    if (acc && acc.status === 'blocked') throw new HttpErr(403, 'هذا الحساب موقوف. تواصل مع مدير التطبيق.');
    if (!acc) this.run(`INSERT INTO accounts(email,name,photo,role,status,created,last_seen) VALUES(?,?,?,?,?,?,?)`, email, info.name || '', info.picture || '', 'user', 'active', now, now);
    else this.run(`UPDATE accounts SET name=COALESCE(NULLIF(?,''),name), photo=COALESCE(NULLIF(?,''),photo), last_seen=? WHERE email=?`, info.name || '', info.picture || '', now, email);
    const token = crypto.randomUUID().replace(/-/g, '') + crypto.randomUUID().replace(/-/g, '');
    this.run(`INSERT INTO sessions(token,email,created,dev) VALUES(?,?,?,?)`, token, email, now, String(b.dev || '').slice(0, 80));
    return json({ token, me: this.meJson(this.one(`SELECT * FROM accounts WHERE email=?`, email)) });
  }

  who(req) {
    const t = bearer(req);
    if (!t) throw new HttpErr(401, 'سجّل الدخول أولاً');
    const s = this.one(`SELECT email FROM sessions WHERE token=?`, t);
    if (!s) throw new HttpErr(401, 'انتهت الجلسة، سجّل الدخول من جديد');
    const a = this.one(`SELECT * FROM accounts WHERE email=?`, s.email);
    if (!a) throw new HttpErr(401, 'الحساب غير موجود');
    if (a.status === 'blocked') throw new HttpErr(403, 'هذا الحساب موقوف. تواصل مع مدير التطبيق.');
    if (!a.last_seen || Date.now() - a.last_seen > 600e3) this.run(`UPDATE accounts SET last_seen=? WHERE email=?`, Date.now(), a.email);
    return a;
  }

  meJson(a) {
    return {
      email: a.email, name: a.name || '', photo: a.photo || '', role: a.role, prefs: a.prefs ? JSON.parse(a.prefs) : null, updated: a.prefs_updated || 0,
      cv: a.cv_profile ? { profile: JSON.parse(a.cv_profile), name: a.cv_name || '', updated: a.cv_updated || 0 } : null,
    };
  }

  getMe(me, sp) {
    if (sp.get('ver')) this.run(`UPDATE accounts SET ver=?, model=? WHERE email=?`, sp.get('ver'), sp.get('model') || '', me.email);
    return json(this.meJson(me));
  }

  saveMe(me, b) {
    const t = Date.now();
    this.run(`UPDATE accounts SET prefs=?, prefs_updated=? WHERE email=?`, JSON.stringify(b.prefs || {}).slice(0, 200000), t, me.email);
    return json({ ok: true, updated: t });
  }

  // ===== السيرة الذاتية =====
  saveCV(me, b) {
    const text = String(b.text || '').replace(/\u0000/g, '').trim();
    if (text.replace(/\s+/g, '').length < 150) throw new HttpErr(400, 'لم أستطع قراءة نص كافٍ من الملف. إن كان PDF ممسوحاً كصورة، ارفع نسخة Word أو PDF نصية.');
    const profile = analyzeCV(text);
    const old = me.cv_profile ? JSON.parse(me.cv_profile) : null;
    const t = Date.now();
    this.run(`UPDATE accounts SET cv_text=?, cv_profile=?, cv_name=?, cv_updated=? WHERE email=?`, text.slice(0, 60000), JSON.stringify(profile), String(b.name || '').slice(0, 120), t, me.email);
    return json({ profile, diff: diffProfiles(old, profile), updated: t, name: String(b.name || '') });
  }

  // ===== الوظائف =====
  jobRow(r) {
    return {
      id: r.id, t: r.title, o: r.org, s: r.specs, g: r.govs, c: r.sector, d: r.deadline,
      p: r.posted, src: r.src, n: (r.srcs || '').split('\n').filter(Boolean).length, u: r.url, a: r.apply, x: r.text, sh: r.shared_by ? 1 : 0,
      im: r.image || '', dt: r.doubt ? 1 : 0, k: r.kind || 'job', tc: r.tcat || '', bd: r.bond || '',
    };
  }

  /** الزائر (بلا جلسة صالحة): أحدث GUEST_JOBS وظيفة وGUEST_TENDERS مناقصة فقط، مع عدد المقفل. */
  guestJobs() {
    const now = Date.now();
    const okDl = new Date(now - 2 * DAY).toISOString().slice(0, 10);
    const base = `hidden=0 AND doubt=0 AND created > ? AND (deadline IS NULL OR deadline='' OR deadline >= ?)`;
    const pick = (kind, n) => this.q(`SELECT * FROM jobs WHERE ${base} AND COALESCE(kind,'job')=? ORDER BY id DESC LIMIT ?`, now - FIRST_DAYS * DAY, okDl, kind, n);
    const cnt = kind => this.one(`SELECT COUNT(*) AS n FROM jobs WHERE ${base} AND COALESCE(kind,'job')=?`, now - FIRST_DAYS * DAY, okDl, kind).n;
    const rows = [...pick('job', GUEST_JOBS), ...pick('tender', GUEST_TENDERS)].sort((a, b) => a.id - b.id);
    const mx = (this.one(`SELECT MAX(id) AS m FROM jobs`) || {}).m || 0;
    return json({
      now, last: mx, max: mx, more: false, guest: true,
      limJobs: GUEST_JOBS, limTenders: GUEST_TENDERS,
      lockedJobs: Math.max(0, cnt('job') - GUEST_JOBS), lockedTenders: Math.max(0, cnt('tender') - GUEST_TENDERS),
      jobs: rows.map(r => this.jobRow(r)),
    });
  }

  jobs(url, req) {
    const one = +url.searchParams.get('id') || 0;
    if (one) {   // فرصة واحدة بالرابط: مفتوحة للجميع
      const r = this.one(`SELECT * FROM jobs WHERE id=? AND hidden=0`, one);
      return json({ now: Date.now(), last: one, max: one, more: false, jobs: r ? [this.jobRow(r)] : [] });
    }
    const t = bearer(req);
    if (!t || !this.one(`SELECT 1 FROM sessions WHERE token=?`, t)) return this.guestJobs();
    const since = +url.searchParams.get('since') || 0;
    const limit = Math.min(+url.searchParams.get('limit') || 800, 2000);
    const rows = since > 0
      ? this.q(`SELECT * FROM jobs WHERE id > ? ORDER BY id ASC LIMIT ?`, since, limit)
      : this.q(`SELECT * FROM jobs WHERE created > ? AND hidden=0 ORDER BY id DESC LIMIT ?`, Date.now() - FIRST_DAYS * DAY, limit).reverse();
    const last = rows.length ? rows[rows.length - 1].id : since;
    const more = rows.length === limit;
    const visible = rows.filter(r => !r.hidden);
    const mx = this.one(`SELECT MAX(id) AS m FROM jobs`);
    return json({ now: Date.now(), last, max: (mx && mx.m) || 0, more, jobs: visible.map(r => this.jobRow(r)) });
  }

  // تصحيحات التصنيف التي حدثت بعد وقت معيّن
  changes(url) {
    const since = +url.searchParams.get('since') || 0;
    const rows = this.q(`SELECT id, specs, govs, sector, hidden, doubt, kind, tcat, bond FROM jobs WHERE updated > ? ORDER BY updated LIMIT 1000`, since);
    return json({ now: Date.now(), items: rows.map(r => ({ id: r.id, s: r.specs, g: r.govs, c: r.sector, h: r.hidden ? 1 : 0, dt: r.doubt ? 1 : 0, k: r.kind || 'job', tc: r.tcat || '', bd: r.bond || '' })) });
  }

  addJob(post, opts = {}) {
    let text = String(post.text || '').trim();
    const image = String(post.image || '').slice(0, 600);
    if (text.length < 20 && !image) return 'short';
    if (!text) text = '📷 الإعلان داخل الصورة';
    const url = String(post.url || '').slice(0, 500);
    if (url && this.q(`SELECT 1 FROM seen WHERE url=?`, url).length) return 'seen';
    const now = Date.now();
    const posted = +post.date || now;
    if (posted < now - FIRST_DAYS * DAY && !opts.force) { this.markSeen(url); return 'old'; }
    const c = classify(text, { now: new Date(), links: post.links || [], force: opts.force, deadline: post.deadline || '', sectorHint: post.sector || '', srcName: post.srcName || '' });
    if (post.title && post.title.length > 3) c.title = String(post.title).slice(0, 140);
    if (post.org) c.org = String(post.org).slice(0, 80);
    const tinfo = tenderInfo(text, c);
    const js = jobScore(text, c, { image: !!image });
    if (tinfo && js.verdict === 'reject' && js.score > -10 && (c.deadline || c.org)) js.verdict = 'ok';   // المناقصات بنية مختلفة عن الوظائف
    if (opts.force) { if (js.score <= -10) { this.markSeen(url); return 'notjob'; } }
    else if (js.verdict === 'reject' || (!c.isJob && !image)) { this.markSeen(url); return 'notjob'; }
    if (image && text.startsWith('📷')) c.title = '📷 إعلان وظيفة بصورة';
    if (c.deadline && Date.parse(c.deadline) < now - 2 * DAY) { this.markSeen(url); return 'expired'; }
    const { k1, k2 } = dupKeys(c, text);
    const src = String(post.srcName || post.src || '').slice(0, 60);
    const dup = this.one(`SELECT id, srcs FROM jobs WHERE k1=? OR (?<>'' AND k2=?) LIMIT 1`, k1, k2, k2);
    this.markSeen(url);
    if (dup) {
      const srcs = (dup.srcs || '').split('\n');
      if (src && !srcs.includes(src)) this.run(`UPDATE jobs SET srcs=? WHERE id=?`, [...srcs, src].filter(Boolean).join('\n'), dup.id);
      return 'dup';
    }
    this.run(
      `INSERT INTO jobs(k1,k2,url,title,org,specs,govs,sector,deadline,posted,created,src,srcs,apply,text,shared_by,updated,image,doubt,kind,tcat,bond) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,?,?,?,?)`,
      k1, k2, url, c.title, c.org, c.specs.join(','), c.govs.join(','), c.sector, c.deadline, posted, now, src, src,
      c.apply, text.slice(0, MAX_TEXT), opts.sharedBy || null, image || null, js.verdict === 'doubt' && !opts.force && !tinfo ? 1 : 0,
      tinfo ? 'tender' : 'job', tinfo ? tinfo.tcat : null, tinfo ? tinfo.bond : null);
    return 'added';
  }

  markSeen(url) { if (url) this.run(`INSERT OR REPLACE INTO seen(url,at) VALUES(?,?)`, url, Date.now()); }

  ingest(b) {
    const out = { added: 0, dup: 0, seen: 0, notjob: 0, old: 0, expired: 0, short: 0 };
    for (const post of (b.posts || []).slice(0, 60)) { const r = this.addJob(post); out[r] = (out[r] || 0) + 1; }
    if (b.sourceId && out.added) this.run(`UPDATE sources SET total = total + ? WHERE id=?`, out.added, b.sourceId);
    if (b.sourceId && out.notjob) this.run(`UPDATE sources SET rejected = COALESCE(rejected,0) + ? WHERE id=?`, out.notjob, b.sourceId);
    this.cleanup();
    return json(out);
  }

  known(b) {
    const urls = (b.urls || []).slice(0, 300);
    return json({ known: urls.filter(u => this.q(`SELECT 1 FROM seen WHERE url=?`, u).length) });
  }

  cleanup() {
    const last = +((this.one(`SELECT v FROM meta WHERE k='cleanup'`) || {}).v || 0);
    if (Date.now() - last < 6 * 3600e3) return;
    this.run(`DELETE FROM jobs WHERE created < ?`, Date.now() - KEEP_DAYS * DAY);
    this.run(`DELETE FROM seen WHERE at < ?`, Date.now() - 120 * DAY);
    this.run(`DELETE FROM fixes WHERE status<>'pending' AND created < ?`, Date.now() - 30 * DAY);
    this.run(`INSERT OR REPLACE INTO meta(k,v) VALUES('cleanup',?)`, String(Date.now()));
  }

  share(me, b) {
    let text = String(b.text || '').trim();
    const link = String(b.url || '').trim();
    if (link && !text.includes(link)) text += '\n' + link;
    if (text.length < 10) throw new HttpErr(400, 'النص قصير');
    const found = text.match(/https?:\/\/\S+/);
    const url = link || (found ? found[0] : '') || 'share:' + me.email + ':' + Date.now();
    const who = (me.name || me.email.split('@')[0]).slice(0, 40);
    return json({ result: this.addJob({ text, url, srcName: 'مشاركة من ' + who, date: Date.now() }, { force: true, sharedBy: me.email }) });
  }

  // ===== تصحيح التصنيف =====
  fix(me, b) {
    const id = +b.id;
    const job = this.one(`SELECT id FROM jobs WHERE id=?`, id);
    if (!job) throw new HttpErr(404, 'الإعلان غير موجود');
    const specs = cleanCsv(b.specs, LABELS.specs), govs = cleanCsv(b.govs, LABELS.govs);
    const sector = LABELS.sectors[b.sector] ? b.sector : '';
    if (me.role === 'admin') {
      this.applyFix(id, specs, govs, sector);
      return json({ ok: true, applied: true });
    }
    this.run(`INSERT INTO fixes(job_id,email,specs,govs,sector,note,status,created) VALUES(?,?,?,?,?,?,?,?)`, id, me.email, specs, govs, sector, String(b.note || '').slice(0, 300), 'pending', Date.now());
    return json({ ok: true, applied: false });
  }

  applyFix(id, specs, govs, sector) {
    const j = this.one(`SELECT specs, govs, sector FROM jobs WHERE id=?`, id);
    if (!j) return;
    this.run(`UPDATE jobs SET specs=?, govs=?, sector=?, updated=? WHERE id=?`, specs || j.specs, govs || j.govs, sector || j.sector, Date.now(), id);
  }

  decideFix(b) {
    const f = this.one(`SELECT * FROM fixes WHERE id=?`, +b.id);
    if (!f) throw new HttpErr(404, 'غير موجود');
    if (b.ok) {
      this.applyFix(f.job_id, b.specs != null ? cleanCsv(b.specs, LABELS.specs) : f.specs, b.govs != null ? cleanCsv(b.govs, LABELS.govs) : f.govs, f.sector);
    }
    this.run(`UPDATE fixes SET status=? WHERE id=?`, b.ok ? 'done' : 'rejected', f.id);
    return json({ ok: true });
  }

  // ===== المصادر =====
  parseSource(raw) {
    let v = String(raw || '').trim(), kind = 'site', m;
    if ((m = v.match(/^(?:https?:\/\/)?(?:www\.)?(?:t\.me|telegram\.me)\/(?:s\/)?([A-Za-z0-9_]{4,})/i)) || (m = v.match(/^@?([A-Za-z][A-Za-z0-9_]{3,})$/))) { kind = 'tg'; v = m[1]; }
    else if (/lnkd\.in|bit\.ly|tinyurl|t\.co\//i.test(v)) kind = 'link';
    else if (/facebook\.com|fb\.com|fb\.me|linkedin\.com|instagram\.com/i.test(v)) kind = 'manual';
    else if (!/^https?:\/\//i.test(v)) v = 'https://' + v;
    return { kind, value: v, nkey: sourceKey(kind, v) };
  }

  suggest(me, b) {
    const raw = String(b.value || '').trim();
    if (raw.length < 4) throw new HttpErr(400, 'أدخل رابطاً صحيحاً');
    const s = this.parseSource(raw);
    const isAdmin = me.role === 'admin';
    const ex = this.one(`SELECT id, name, value, pending, enabled FROM sources WHERE nkey=? OR lower(value)=lower(?) OR lower(orig)=lower(?)`, s.nkey, s.value, raw);
    if (ex) {
      if (isAdmin && !ex.enabled && !ex.pending) this.run(`UPDATE sources SET enabled=1 WHERE id=?`, ex.id);
      return json({ dup: true, name: isAdmin ? (ex.name || ex.value) : null, pending: !!ex.pending });
    }
    const name = String(b.name || '').trim() || s.value;
    this.run(`INSERT INTO sources(kind,value,name,added_by,orig,nkey,pending,enabled,created) VALUES(?,?,?,?,?,?,?,?,?)`,
      s.kind, s.value, name, me.email, raw, s.nkey, isAdmin ? 0 : 1, isAdmin ? 1 : 0, Date.now());
    return json({ ok: true, pending: !isAdmin, kind: s.kind });
  }

  approve(b) {
    if (b.ok) this.run(`UPDATE sources SET pending=0, enabled=1 WHERE id=?`, +b.id);
    else this.run(`DELETE FROM sources WHERE id=?`, +b.id);
    return json({ ok: true });
  }

  report(b) {
    const id = +b.id;
    if (b.resolved && b.resolved.kind) {
      const { kind, value } = b.resolved;
      const nk = sourceKey(kind, value);
      const clash = this.one(`SELECT id FROM sources WHERE (nkey=? OR lower(value)=lower(?)) AND id<>?`, nk, value, id);
      if (clash) this.run(`UPDATE sources SET enabled=0, kind=?, last_err=? WHERE id=?`, kind, 'مكرر مع مصدر آخر', id);
      else this.run(`UPDATE sources SET kind=?, value=?, nkey=? WHERE id=?`, kind, value, nk, id);
    }
    if (b.name) this.run(`UPDATE sources SET name=? WHERE id=? AND (name=value OR name=orig OR name='' OR name IS NULL)`, String(b.name).slice(0, 60), id);
    if (b.ok) this.run(`UPDATE sources SET last_try=?, last_ok=?, last_err=NULL, last_count=?, fail_since=NULL WHERE id=?`, Date.now(), Date.now(), +b.count || 0, id);
    else this.run(`UPDATE sources SET last_try=?, last_err=?, fail_since=COALESCE(fail_since, ?) WHERE id=?`, Date.now(), String(b.err || 'خطأ').slice(0, 200), Date.now(), id);
    if (b.final) {
      this.run(`INSERT OR REPLACE INTO meta(k,v) VALUES('last_run',?)`, String(Date.now()));
      this.autoDisable();
    }
    return json({ ok: true });
  }

  /** يوقف تلقائياً المصدر الذي فشل FAIL_DAYS أيام متتالية، إلا عند عطل عام (أغلب المصادر تفشل معاً). */
  autoDisable() {
    const now = Date.now();
    const base = `enabled=1 AND pending=0 AND kind<>'manual'`;
    const total = this.one(`SELECT COUNT(*) AS n FROM sources WHERE ${base}`).n;
    const failing = this.q(`SELECT id, name, last_err, fail_since FROM sources WHERE ${base} AND fail_since IS NOT NULL`);
    if (total >= 4 && failing.length / total > 0.5) {
      this.run(`INSERT OR REPLACE INTO meta(k,v) VALUES('systemic',?)`, JSON.stringify({ at: now, failing: failing.length, total }));
      return;
    }
    for (const s of failing) {
      if (now - s.fail_since >= FAIL_DAYS * DAY)
        this.run(`UPDATE sources SET enabled=0, auto_off=?, last_err=? WHERE id=?`, now, ('أُوقف تلقائياً بعد ' + FAIL_DAYS + ' أيام من الفشل: ' + (s.last_err || '')).slice(0, 200), s.id);
    }
  }

  // ===== البلاغات =====
  flag(me, b) {
    const id = +b.id;
    const j = this.one(`SELECT id, flag_ok, hidden FROM jobs WHERE id=?`, id);
    if (!j) throw new HttpErr(404, 'الإعلان غير موجود');
    this.run(`INSERT OR IGNORE INTO flags(job_id,email,created) VALUES(?,?,?)`, id, me.email, Date.now());
    const n = this.one(`SELECT COUNT(*) AS n FROM flags WHERE job_id=?`, id).n;
    let hidden = !!j.hidden;
    if (!j.hidden && !j.flag_ok && (n >= 2 || me.role === 'admin')) {
      this.run(`UPDATE jobs SET hidden=?, updated=? WHERE id=?`, me.role === 'admin' ? 2 : 1, Date.now(), id);
      hidden = true;
    }
    return json({ ok: true, n, hidden });
  }

  decideFlag(b) {
    const id = +b.id;
    if (b.action === 'delete') this.run(`UPDATE jobs SET hidden=2, updated=? WHERE id=?`, Date.now(), id);
    else this.run(`UPDATE jobs SET hidden=0, flag_ok=1, updated=? WHERE id=?`, Date.now(), id);
    this.run(`DELETE FROM flags WHERE job_id=?`, id);
    return json({ ok: true });
  }

  // ===== المستخدمون =====
  users() {
    return this.q(`SELECT a.email, a.name, a.photo, a.role, a.status, a.created, a.last_seen, a.model, a.ver,
      (SELECT COUNT(*) FROM sources s WHERE s.added_by=a.email) AS contrib,
      (SELECT COUNT(*) FROM jobs j WHERE j.shared_by=a.email) AS shares
      FROM accounts a ORDER BY a.last_seen DESC`);
  }

  setRole(me, b) {
    const email = String(b.email || '').toLowerCase();
    if (email === FIRST_ADMIN) throw new HttpErr(400, 'لا يمكن تغيير صلاحية المدير الأول');
    this.run(`UPDATE accounts SET role=? WHERE email=?`, b.role === 'admin' ? 'admin' : 'user', email);
    return json({ ok: true });
  }

  setStatus(me, b) {
    const email = String(b.email || '').toLowerCase();
    if (email === FIRST_ADMIN || email === me.email) throw new HttpErr(400, 'لا يمكن إيقاف هذا الحساب');
    const st = b.status === 'blocked' ? 'blocked' : 'active';
    this.run(`UPDATE accounts SET status=? WHERE email=?`, st, email);
    if (st === 'blocked') this.run(`DELETE FROM sessions WHERE email=?`, email);
    return json({ ok: true });
  }

  /** الحد الأدنى للإصدار (تحديث إجباري). فارغ = معطّل. */
  minInfo() {
    const g = k => (this.one(`SELECT v FROM meta WHERE k=?`, k) || {}).v || '';
    return { minVer: g('min_ver'), minMsg: g('min_msg') };
  }

  setMinVer(b) {
    const v = String(b.ver || '').trim();
    if (v && !/^\d+(\.\d+){0,3}$/.test(v)) throw new HttpErr(400, 'صيغة الإصدار غير صحيحة (مثال: 1.9)');
    this.run(`INSERT OR REPLACE INTO meta(k,v) VALUES('min_ver',?)`, v);
    this.run(`INSERT OR REPLACE INTO meta(k,v) VALUES('min_msg',?)`, String(b.msg || '').trim().slice(0, 300));
    return json({ ok: true, ...this.minInfo() });
  }

  announce(me, b) {
    const t = String(b.text || '').trim();
    if (t.length < 2) throw new HttpErr(400, 'النص فارغ');
    this.run(`INSERT INTO announcements(text,email,created) VALUES(?,?,?)`, t.slice(0, 1000), me.email, Date.now());
    return json({ ok: true });
  }

  pending(since) {
    return {
      sources: this.q(`SELECT id, name, value, added_by, created FROM sources WHERE pending=1 ORDER BY id`),
      fixes: this.one(`SELECT COUNT(*) AS n FROM fixes WHERE status='pending'`).n,
      flags: this.one(`SELECT COUNT(DISTINCT f.job_id) AS n FROM flags f JOIN jobs j ON j.id=f.job_id WHERE j.hidden<2`).n,
      newFlags: this.one(`SELECT COUNT(*) AS n FROM flags WHERE created > ?`, since).n,
      newFixes: this.one(`SELECT COUNT(*) AS n FROM fixes WHERE status='pending' AND created > ?`, since).n,
      autoOff: this.q(`SELECT name FROM sources WHERE auto_off > ? AND ? > 0 ORDER BY auto_off DESC LIMIT 10`, since, since).map(r => r.name),
      systemic: (() => { const m = JSON.parse((this.one(`SELECT v FROM meta WHERE k='systemic'`) || {}).v || 'null'); return m && m.at > since && since > 0 ? m : null; })(),
      newUsers: this.q(`SELECT email, name FROM accounts WHERE created > ? AND ? > 0 ORDER BY created DESC LIMIT 10`, since, since),
    };
  }

  stats() {
    const now = Date.now();
    const days = [];
    for (let i = 13; i >= 0; i--) {
      const start = new Date(new Date(now - i * DAY).toISOString().slice(0, 10)).getTime();
      days.push({ day: new Date(start).toISOString().slice(0, 10), n: this.one(`SELECT COUNT(*) AS n FROM jobs WHERE created >= ? AND created < ?`, start, start + DAY).n });
    }
    return {
      jobs: this.one(`SELECT COUNT(*) AS n FROM jobs`).n,
      today: this.one(`SELECT COUNT(*) AS n FROM jobs WHERE created > ?`, now - DAY).n,
      users: this.one(`SELECT COUNT(*) AS n FROM accounts`).n,
      active7: this.one(`SELECT COUNT(*) AS n FROM accounts WHERE last_seen > ?`, now - 7 * DAY).n,
      lastRun: +((this.one(`SELECT v FROM meta WHERE k='last_run'`) || {}).v || 0),
      days,
      topSources: this.q(`SELECT name, total, last_count, COALESCE(rejected,0) AS rejected FROM sources WHERE pending=0 ORDER BY total DESC LIMIT 10`),
      noisy: this.q(`SELECT name, total, rejected FROM sources WHERE pending=0 AND COALESCE(rejected,0) >= 5 ORDER BY 1.0*rejected/(total+rejected) DESC LIMIT 10`),
      cleanup: JSON.parse((this.one(`SELECT v FROM meta WHERE k='mig15'`) || {}).v || 'null'),
      broken: this.q(`SELECT name, last_err FROM sources WHERE pending=0 AND last_err IS NOT NULL AND kind<>'manual' AND (enabled=1 OR auto_off>0)`),
    };
  }

  // ===== صفحة ويب لكل وظيفة (للمشاركة على واتساب/تيليغرام) =====
  jobPage(id) {
    const esc = x => String(x == null ? '' : x).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    const safe = u => /^(https?:|mailto:)/i.test(String(u || '')) ? esc(u) : '';
    const r = this.one(`SELECT * FROM jobs WHERE id=? AND hidden=0`, id);
    const head = `<!doctype html><html lang="ar" dir="rtl"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">`;
    const css = `<style>body{font-family:system-ui,sans-serif;max-width:680px;margin:auto;padding:16px;line-height:1.8;color:#1c2333;background:#f4f6fb}.c{background:#fff;border-radius:14px;padding:16px;margin:12px 0;box-shadow:0 1px 4px #0001}h1{font-size:21px;margin:0 0 4px}.m{color:#5a6477;font-size:14px}.b{display:block;text-align:center;padding:13px;border-radius:12px;margin:8px 0;font-weight:700;text-decoration:none}.p{background:#1565C0;color:#fff}.s{background:#e8effb;color:#1565C0}.x{white-space:normal;word-break:break-word}img{max-width:100%;border-radius:10px}small{color:#8a93a5}</style>`;
    if (!r) return new Response(`${head}<title>وظيفتي</title>${css}<div class="c"><h1>هذه الفرصة لم تعد متاحة</h1><p class="m">قد تكون انتهت أو أُزيلت.</p></div><a class="b p" href="${RELEASE}">حمّل وظيفتي لتصلك فرص جديدة</a></html>`, { status: 404, headers: { 'Content-Type': 'text/html; charset=utf-8' } });
    const names = (csv, dict) => String(csv || '').split(',').filter(Boolean).map(k => dict[k]).filter(Boolean).join('، ');
    const tender = r.kind === 'tender';
    const govs = names(r.govs, LABELS.govs);
    const kind = tender ? 'مناقصة — ' + (LABELS.tcats[r.tcat] || 'أخرى') : names(r.specs, LABELS.specs);
    const sector = LABELS.sectors[r.sector] || '';
    const desc = [r.org, govs, r.deadline ? 'آخر موعد: ' + r.deadline : ''].filter(Boolean).join(' • ');
    const body = esc(String(r.text || '').slice(0, 2500)).replace(/\n/g, '<br>');
    const intent = `intent://job/${id}#Intent;scheme=wazifati;package=com.feras.wazifati;S.browser_fallback_url=${encodeURIComponent(RELEASE)};end`;
    const apply = safe(r.apply), orig = /^https?:/i.test(r.url || '') ? safe(r.url) : '';
    const img = /^https:/i.test(r.image || '') ? `<img src="${esc(r.image)}" alt="">` : '';
    const html = `${head}<title>${esc(r.title)} — وظيفتي</title>
<meta property="og:title" content="${esc(r.title)}"><meta property="og:description" content="${esc(desc)}"><meta property="og:type" content="article">${img ? `<meta property="og:image" content="${esc(r.image)}">` : ''}
<meta name="description" content="${esc(desc)}">${css}
<div class="c"><h1>${esc(r.title)}</h1><div class="m">${esc(r.org || '')}</div>
<p class="m">${govs ? '📍 ' + esc(govs) + '<br>' : ''}${kind ? '🏷 ' + esc(kind) + '<br>' : ''}${sector ? '🏢 ' + esc(sector) + '<br>' : ''}${r.deadline ? '⏳ آخر موعد: ' + esc(r.deadline) + '<br>' : ''}${r.bond ? '💰 التأمين: ' + esc(r.bond) + '<br>' : ''}${r.src ? '📡 المصدر: ' + esc(r.src) : ''}</p></div>
${apply ? `<a class="b p" href="${apply}">${tender ? '📝 وثائق المناقصة' : '📝 رابط التقديم'}</a>` : ''}${orig ? `<a class="b s" href="${orig}">📄 الإعلان الأصلي</a>` : ''}
<a class="b s" href="${intent}">📲 افتحها في تطبيق وظيفتي</a>
${img ? `<div class="c">${img}</div>` : ''}<div class="c x">${body}</div>
<div class="c"><b>💼 وظيفتي</b><p class="m">تطبيق مجاني يجمع فرص العمل والمناقصات في سوريا ويرسل لك ما يناسب اختصاصك ومحافظتك.</p><a class="b p" href="${RELEASE}">⬇ حمّل التطبيق</a></div>
<p><small>تحقّق من الجهة المعلنة قبل التقديم، ولا تدفع أي مبلغ مقابل وظيفة. <a href="/privacy">الخصوصية</a></small></p></html>`;
    return new Response(html, { headers: { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'public, max-age=300' } });
  }

  home() {
    const n = this.one(`SELECT COUNT(*) AS n FROM jobs`).n;
    const lastRun = +((this.one(`SELECT v FROM meta WHERE k='last_run'`) || {}).v || 0);
    const ago = !lastRun ? '—' : Math.round((Date.now() - lastRun) / 60e3) + ' دقيقة';
    const html = `<!doctype html><html lang="ar" dir="rtl"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>وظيفتي</title>
<style>body{font-family:system-ui,sans-serif;margin:24px;background:#f4f6fb;color:#1c2333;text-align:center}b{color:#1565C0;font-size:28px}</style>
<h1>💼 وظيفتي</h1><p>الخادم يعمل (v${VERSION})</p><p><b>${n}</b><br>فرصة محفوظة</p><p>آخر جلب منذ ${ago}</p>
<p><a href="https://github.com/makanona-team/wazifati/releases/latest">تحميل التطبيق</a></p></html>`;
    return new Response(html, { headers: { 'Content-Type': 'text/html; charset=utf-8' } });
  }
}

async function body(req) { return req.json().catch(() => ({})); }
function bearer(req) { const h = req.headers.get('Authorization') || ''; return h.startsWith('Bearer ') ? h.slice(7).trim() : ''; }
function cleanCsv(v, dict) {
  const arr = Array.isArray(v) ? v : String(v || '').split(',');
  return [...new Set(arr.map(x => String(x).trim()).filter(x => dict[x]))].join(',');
}
