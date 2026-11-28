// خادم «وظيفتي» على Cloudflare Workers + Durable Object (قاعدة SQLite مجانية).
// الدخول بحساب Google فقط. أدوار: admin / user.
import { classify, dupKeys, LABELS, jobScore, tenderInfo, learnInfo, SPECS, norm } from './classify.js';
import { PRIVACY, TERMS } from './pages.js';
import { analyzeCV, diffProfiles } from './cv.js';

const VERSION = '3.4';
const DEFAULT_FLAGS = {
  tenders: true,        // قسم المناقصات
  cv_match: true,       // السيرة الذاتية ونسبة التطابق
  suggest_source: true, // اقتراح المصادر
  share_job: true,      // مشاركة الفرص
  guest_mode: true,     // التصفح كضيف
  learn_tab: true,      // قسم التعلّم والمراجع (2.6)
};
const GUEST_JOBS = 10;      // الزائر يرى أحدث 10 وظائف
const GUEST_TENDERS = 2;    // وأحدث مناقصتين
const SITE = 'https://wazifati.eng-feras8585.workers.dev';
const RELEASE = SITE + '/download';
const GH_LATEST = 'https://api.github.com/repos/makanona-team/wazifati/releases/latest';
const DEV_NAME = 'م. فراس', DEV_PHONE = '0996265003', DEV_WA = '963996265003';
function cmpVer(a, b) {
  const x = String(a).split(/\D+/), y = String(b).split(/\D+/);
  for (let i = 0; i < Math.max(x.length, y.length); i++) { const p = +x[i] || 0, q = +y[i] || 0; if (p !== q) return p < q ? -1 : 1; }
  return 0;
}
const FAIL_DAYS = 3;
const DAY = 864e5;

// مهارات مرشّحة لدليل التعلّم (المرادفات للمطابقة؛ الأدمن يعدّلها ويضيف الروابط من التطبيق)
const SEED_SKILLS = [
  ['excel', 'إكسل', 'Excel', 'مكتبية', ['excel', 'اكسل', 'pivot', 'vlookup', 'microsoft excel']],
  ['english', 'اللغة الإنجليزية', 'English', 'لغات', ['english', 'انجليزي', 'اللغه الانجليزيه', 'ielts', 'toefl']],
  ['office', 'أوفيس (وورد وباوربوينت)', 'MS Office', 'مكتبية', ['microsoft office', 'ms office', 'ms word', 'microsoft word', 'powerpoint', 'اوفيس']],
  ['reports', 'كتابة التقارير', 'Report writing', 'مكتبية', ['report writing', 'كتابه التقارير', 'كتابه تقارير', 'technical writing', 'minutes of meeting']],
  ['meal', 'المتابعة والتقييم MEAL', 'MEAL', 'منظمات', ['meal', 'm&e', 'monitoring and evaluation', 'monitoring & evaluation', 'المتابعه والتقييم', 'الرصد والتقييم']],
  ['pm', 'إدارة المشاريع', 'Project management', 'إدارة', ['project management', 'pmp', 'pm4ngos', 'prince2', 'ادارة المشاريع', 'ادارة المشروع']],
  ['proposals', 'كتابة المقترحات والمنح', 'Proposal writing', 'منظمات', ['proposal writing', 'grant writing', 'concept note', 'كتابه المقترحات', 'كتابه مقترحات', 'funding proposals']],
  ['kobo', 'جمع البيانات KoBoToolbox', 'KoBoToolbox', 'منظمات', ['kobo', 'kobotoolbox', 'odk', 'commcare', 'mobile data collection', 'جمع البيانات']],
  ['protection', 'الحماية وPSEA', 'Safeguarding & PSEA', 'منظمات', ['psea', 'safeguarding', 'child protection', 'gbv', 'protection officer', 'حمايه الطفل']],
  ['procurement', 'المشتريات واللوجستيات', 'Procurement & logistics', 'منظمات', ['procurement', 'logistics', 'supply chain', 'المشتريات', 'اللوجستيات', 'لوجستيك', 'سلسله الامداد']],
  ['autocad', 'أوتوكاد', 'AutoCAD', 'هندسة', ['autocad', 'اوتوكاد', 'cad', 'civil 3d', 'civil3d']],
  ['primavera', 'بريمافيرا / MS Project', 'Primavera / MS Project', 'هندسة', ['primavera', 'ms project', 'microsoft project', 'p6', 'بريمافيرا']],
  ['revit', 'ريفيت والتصميم الإنشائي', 'Revit / structural design', 'هندسة', ['revit', 'staad', 'etabs', 'sap2000', 'sketchup', '3ds max']],
  ['maintenance', 'الصيانة والتشغيل', 'Maintenance', 'هندسة', ['maintenance', 'preventive maintenance', 'cmms', 'plc', 'scada', 'الصيانه']],
  ['hse', 'السلامة المهنية HSE', 'HSE', 'هندسة', ['hse', 'ehs', 'osha', 'nebosh', 'iosh', 'health and safety', 'السلامه المهنيه', 'الصحه والسلامه']],
  ['accounting', 'المحاسبة', 'Accounting', 'مالية', ['accounting', 'quickbooks', 'bookkeeping', 'erp', 'sap', 'odoo', 'tally', 'المحاسبه']],
  ['finance', 'الإدارة المالية والموازنات', 'Financial management', 'مالية', ['financial management', 'budgeting', 'financial reporting', 'donor regulations', 'الاداره الماليه', 'الموازنات']],
  ['data', 'تحليل البيانات (Power BI / SQL)', 'Data analysis', 'بيانات', ['power bi', 'powerbi', 'tableau', 'data analysis', 'sql', 'spss', 'stata', 'تحليل البيانات']],
  ['gis', 'نظم المعلومات الجغرافية GIS', 'GIS', 'بيانات', ['gis', 'arcgis', 'qgis', 'remote sensing', 'نظم المعلومات الجغرافيه']],
  ['design', 'التصميم الجرافيكي', 'Graphic design', 'تصميم وتسويق', ['photoshop', 'illustrator', 'canva', 'indesign', 'graphic design', 'premiere', 'after effects', 'فوتوشوب']],
  ['marketing', 'التسويق الرقمي', 'Digital marketing', 'تصميم وتسويق', ['digital marketing', 'social media', 'seo', 'content creation', 'media buying', 'التسويق الالكتروني', 'التسويق الرقمي']],
  ['hr', 'الموارد البشرية', 'Human resources', 'إدارة', ['human resources', 'hr', 'recruitment', 'payroll', 'الموارد البشريه']],
  ['programming', 'البرمجة', 'Programming', 'برمجة', ['python', 'javascript', 'java', 'php', 'react', 'web development', 'flutter', 'برمجه']],
  ['training', 'التدريب والتدريس', 'Training & teaching', 'مهارات', ['training of trainers', 'tot', 'curriculum', 'lesson planning', 'teaching', 'تدريب المدربين', 'المناهج']],
  ['soft', 'مهارات التواصل والتفاوض', 'Communication & negotiation', 'مهارات', ['communication skills', 'negotiation', 'presentation skills', 'public speaking', 'مهارات التواصل', 'التفاوض']],
];
const nrm = (s) => String(s || '').toLowerCase().replace(/[ً-ْـ]/g, '').replace(/[أإآٱ]/g, 'ا').replace(/ة/g, 'ه').replace(/ى/g, 'ي').replace(/ؤ/g, 'و').replace(/ئ/g, 'ي');
const aliasHit = (text, al) => {
  const a = nrm(al).trim();
  if (!a) return false;
  if (/^[a-z0-9 .+#&-]+$/.test(a)) return new RegExp('(^|[^a-z0-9])' + a.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '($|[^a-z0-9])').test(text);
  return text.includes(a);
};

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

// مصادر تعلّم افتراضية (2.7). الأسماء غير مجرّبة من بيئة التطوير: أي مصدر يفشل 3 أيام يُعطَّل تلقائياً.
const SEED_LEARN = [
  ['tg', 'learningforchanges', 'التعلم من أجل التغيير (شهادات Coursera/edX)'],
  ['tg', 'edlibrecom', 'التعلم الحر EDLibre'],
  ['tg', 'Programmer_of_the_Arab_world', 'مبرمجين الوطن العربي'],
  ['rss', 'https://www.freecodecamp.org/news/rss/', 'freeCodeCamp'],
  ['rss', 'https://reliefweb.int/training/rss.xml', 'ReliefWeb Training'],
];

// بوتات مكتبات تعليمية (2.8) تُفهرس بحساب القراءة الخاص
const SEED_BOTS = [
  ['tgbot', 'Binaa1alkodrat2protection_bot', 'مكتبة بناء القدرات'],
  ['tgbot', 'TakeyourpartsyBot', 'روبوت حقك تاخد دورك'],
];

// قاموس الحلول الأولي: [نمط (أجزاء يفصلها |)، العنوان، السبب، الحل، رسالة للمستخدم (اختياري)، يحتاج إصلاح كود؟]
const SEED_KB = [
  ['NoClassDefFoundError|ClassNotFoundException', 'ملف ناقص بعد تحديث التطبيق',
    'تحدّث التطبيق بينما النسخة القديمة ما زالت تعمل في الذاكرة، فبحثت عن ملف تغيّر من تحتها. يُسجَّل برقم النسخة الجديدة.',
    'لا يحتاج كوداً: يغلق المستخدم التطبيق تماماً ثم يفتحه. إن تكرر بعد إعادة الفتح فهي مشكلة حقيقية في البناء.',
    'يبدو أن التطبيق تحدّث للتو أثناء عمله. أغلقه تماماً ثم افتحه من جديد وكل شيء سيعمل.', 0],
  ['not attached to window manager', 'إغلاق نافذة بعد إعادة إنشاء الشاشة',
    'نافذة (مثل شريط تنزيل التحديث) حاولت أن تُغلق بعد تدوير الشاشة أو الخروج والعودة.',
    'أُصلح في 2.9 (safeDismiss). إن ظهر في نسخة أحدث فهناك نافذة أخرى تحتاج نفس الإصلاح.', '', 1],
  ['NoCredentialException|no credentials available|No credentials', 'لا يوجد حساب غوغل على الهاتف',
    'الهاتف ليس عليه حساب Google مضاف، أو خدمات Google معطّلة.',
    'يضيف المستخدم حساب Google من إعدادات الهاتف ← الحسابات، ثم يعيد المحاولة.',
    'لا يوجد حساب Google على هاتفك. أضفه من إعدادات الهاتف ← الحسابات ثم حاول الدخول مجدداً، أو تابع كزائر.', 0],
  ['GetCredentialCancellationException|cancelled|canceled', 'المستخدم ألغى نافذة الدخول', 'أُغلقت نافذة اختيار الحساب قبل الإكمال.', 'لا شيء؛ ليس خطأ.', '', 0],
  ['UnknownHostException|SocketTimeoutException|ConnectException|SSLHandshake|timeout', 'مشكلة اتصال بالإنترنت',
    'الإنترنت ضعيف أو مقطوع، أو حجب مؤقت للخادم.', 'لا يحتاج كوداً عادةً؛ إن تكرر عند كثيرين في نفس الوقت فافحص السيرفر.',
    'يبدو أن الاتصال بالإنترنت ضعيف. تحقق من الشبكة ثم أعد المحاولة.', 0],
  ['OutOfMemoryError', 'نفاد الذاكرة', 'الهاتف ضعيف الذاكرة أو صورة/ملف كبير جداً.', 'يحتاج تقليل حجم الصور أو القوائم في الكود.', 'أغلق بعض التطبيقات المفتوحة ثم أعد فتح وظيفتي.', 1],
  ['DeadObjectException|TransactionTooLargeException', 'خطأ من نظام أندرويد', 'خدمة في النظام توقفت أو بيانات كبيرة جداً بين الشاشات.', 'غالباً عابر؛ إن تكرر كثيراً نراجع حجم البيانات المحفوظة في الشاشة.', '', 1],
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
    for (const col of ['title_orig TEXT', 'org_orig TEXT', 'edited_by TEXT', 'edit_via TEXT', 'edited_at INTEGER DEFAULT 0', 'edit_reviewed INTEGER DEFAULT 1']) this.tryRun(`ALTER TABLE jobs ADD COLUMN ${col}`);
    for (const col of ['image TEXT', 'doubt INTEGER DEFAULT 0', 'hidden INTEGER DEFAULT 0', 'flag_ok INTEGER DEFAULT 0', "kind TEXT DEFAULT 'job'", 'tcat TEXT', 'bond TEXT']) this.tryRun(`ALTER TABLE jobs ADD COLUMN ${col}`);
    this.run(`CREATE TABLE IF NOT EXISTS flags (job_id INTEGER, email TEXT, created INTEGER, PRIMARY KEY(job_id, email))`);
    this.run(`CREATE INDEX IF NOT EXISTS jobs_k1 ON jobs(k1)`);
    this.run(`CREATE INDEX IF NOT EXISTS jobs_k2 ON jobs(k2)`);
    this.run(`CREATE INDEX IF NOT EXISTS jobs_url ON jobs(url)`);
    this.run(`CREATE INDEX IF NOT EXISTS jobs_created ON jobs(created)`);
    this.run(`CREATE INDEX IF NOT EXISTS jobs_updated ON jobs(updated)`);
    this.run(`CREATE TABLE IF NOT EXISTS seen (url TEXT PRIMARY KEY, at INTEGER)`);
    this.run(`CREATE TABLE IF NOT EXISTS learn (
      id INTEGER PRIMARY KEY AUTOINCREMENT, k1 TEXT, url TEXT, link TEXT, title TEXT, provider TEXT, lang TEXT,
      free INTEGER DEFAULT 0, cert INTEGER DEFAULT 0, deadline TEXT, skills TEXT, text TEXT, posted INTEGER,
      created INTEGER, src TEXT, hidden INTEGER DEFAULT 0)`);
    this.run(`CREATE INDEX IF NOT EXISTS learn_k1 ON learn(k1)`);
    for (const col of ['ftype TEXT', 'fsize TEXT', 'howto TEXT', 'specs TEXT', 'hay TEXT', 'reports INTEGER DEFAULT 0']) this.tryRun(`ALTER TABLE learn ADD COLUMN ${col}`);
    this.run(`CREATE TABLE IF NOT EXISTS tg_secret (k TEXT PRIMARY KEY, v TEXT, at INTEGER)`);
    this.run(`CREATE TABLE IF NOT EXISTS legacy_seen (k TEXT PRIMARY KEY, ver TEXT, last INTEGER)`);
    // الدخول عبر تيليغرام (3.1): رمز لمرة واحدة ← المستخدم يضغط «ابدأ» في البوت ← جلسة
    this.run(`CREATE TABLE IF NOT EXISTS tg_login (nonce TEXT PRIMARY KEY, created INTEGER, token TEXT, name TEXT)`);
    // قاموس الحلول (3.0.3): نمط يطابق الخطأ ← السبب والحل ورسالة للمستخدم
    this.run(`CREATE TABLE IF NOT EXISTS chan_posted (job_id INTEGER PRIMARY KEY, at INTEGER, msg_id INTEGER, skip INTEGER DEFAULT 0)`);
    this.run(`CREATE TABLE IF NOT EXISTS chan_review (job_id INTEGER PRIMARY KEY, state INTEGER DEFAULT 0, contact TEXT, at INTEGER, by TEXT)`);
    for (const col of ['hl INTEGER DEFAULT 0', 'ed INTEGER DEFAULT 0', 'views INTEGER DEFAULT 0', "kind TEXT DEFAULT 'job'", 'okey TEXT']) this.tryRun(`ALTER TABLE chan_posted ADD COLUMN ${col}`);
    this.run(`CREATE TABLE IF NOT EXISTS chan_msgs (msg_id INTEGER PRIMARY KEY, at INTEGER, kind TEXT)`);
    this.run(`CREATE TABLE IF NOT EXISTS bot_subs (tg_id TEXT PRIMARY KEY, name TEXT, uname TEXT, specs TEXT, govs TEXT, tenders INTEGER DEFAULT 0, mode TEXT DEFAULT 'instant',
      src TEXT, created INTEGER, upd INTEGER, upd_done INTEGER DEFAULT 0, active INTEGER DEFAULT 0, blocked INTEGER DEFAULT 0, last_sent INTEGER DEFAULT 0, sent INTEGER DEFAULT 0, digest_day TEXT)`);
    this.run(`CREATE TABLE IF NOT EXISTS bot_q (tg_id TEXT, job_id INTEGER, at INTEGER, PRIMARY KEY(tg_id, job_id))`);
    this.tryRun(`ALTER TABLE chan_review ADD COLUMN warned INTEGER DEFAULT 0`);
    this.run(`CREATE TABLE IF NOT EXISTS ev (day TEXT, k TEXT, v TEXT, n INTEGER, PRIMARY KEY(day,k,v))`);
    this.run(`CREATE TABLE IF NOT EXISTS job_ev (job_id INTEGER, k TEXT, n INTEGER, PRIMARY KEY(job_id,k))`);
    this.run(`CREATE TABLE IF NOT EXISTS tg_links (tg_id TEXT PRIMARY KEY, email TEXT, at INTEGER, mute INTEGER DEFAULT 0)`);
    this.run(`CREATE TABLE IF NOT EXISTS tg_link_req (nonce TEXT PRIMARY KEY, email TEXT, created INTEGER)`);
    this.run(`CREATE TABLE IF NOT EXISTS kb (id INTEGER PRIMARY KEY AUTOINCREMENT, match TEXT, title TEXT, cause TEXT, fix TEXT,
      user_msg TEXT, needs_code INTEGER DEFAULT 0, updated INTEGER)`);
    this.run(`CREATE TABLE IF NOT EXISTS sources (
      id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, value TEXT UNIQUE, name TEXT, enabled INTEGER DEFAULT 1,
      added_by TEXT, last_try INTEGER, last_ok INTEGER, last_err TEXT, last_count INTEGER DEFAULT 0, total INTEGER DEFAULT 0, orig TEXT)`);
    this.tryRun(`ALTER TABLE sources ADD COLUMN nkey TEXT`);
    this.tryRun(`ALTER TABLE sources ADD COLUMN pending INTEGER DEFAULT 0`);
    this.tryRun(`ALTER TABLE sources ADD COLUMN created INTEGER DEFAULT 0`);
    this.tryRun(`ALTER TABLE sources ADD COLUMN rejected INTEGER DEFAULT 0`);
    this.tryRun(`ALTER TABLE sources ADD COLUMN fail_since INTEGER`);
    this.tryRun(`ALTER TABLE sources ADD COLUMN auto_off INTEGER DEFAULT 0`);
    this.tryRun(`ALTER TABLE sources ADD COLUMN purpose TEXT DEFAULT 'jobs'`);
    this.tryRun(`ALTER TABLE sources ADD COLUMN learn_total INTEGER DEFAULT 0`);
    this.tryRun(`ALTER TABLE sources ADD COLUMN state TEXT`);
    this.run(`CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT)`);
    this.run(`CREATE TABLE IF NOT EXISTS accounts (
      email TEXT PRIMARY KEY, name TEXT, photo TEXT, role TEXT DEFAULT 'user', status TEXT DEFAULT 'active',
      prefs TEXT, prefs_updated INTEGER DEFAULT 0, created INTEGER, last_seen INTEGER, model TEXT, ver TEXT)`);
    for (const col of ['cv_text TEXT', 'cv_profile TEXT', 'cv_name TEXT', 'cv_updated INTEGER DEFAULT 0', 'last_sync INTEGER']) this.tryRun(`ALTER TABLE accounts ADD COLUMN ${col}`);
    this.run(`CREATE TABLE IF NOT EXISTS skills (id TEXT PRIMARY KEY, ar TEXT, en TEXT, cat TEXT, aliases TEXT, links TEXT, updated INTEGER)`);
    this.run(`CREATE TABLE IF NOT EXISTS link_reports (id INTEGER PRIMARY KEY AUTOINCREMENT, skill TEXT, url TEXT, email TEXT, created INTEGER, UNIQUE(skill, url, email))`);
    this.run(`CREATE TABLE IF NOT EXISTS sessions (token TEXT PRIMARY KEY, email TEXT, created INTEGER, dev TEXT)`);
    this.run(`CREATE TABLE IF NOT EXISTS fixes (
      id INTEGER PRIMARY KEY AUTOINCREMENT, job_id INTEGER, email TEXT, specs TEXT, govs TEXT, sector TEXT, note TEXT,
      status TEXT DEFAULT 'pending', created INTEGER)`);
    for (const col of ['title TEXT', 'org TEXT']) this.tryRun(`ALTER TABLE fixes ADD COLUMN ${col}`);
    this.run(`CREATE TABLE IF NOT EXISTS announcements (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT, email TEXT, created INTEGER)`);
    this.run(`CREATE TABLE IF NOT EXISTS feature_flags (k TEXT PRIMARY KEY, enabled INTEGER DEFAULT 1, updated INTEGER DEFAULT 0)`);
    this.run(`CREATE TABLE IF NOT EXISTS client_reports (
      id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, sig TEXT UNIQUE, app_ver TEXT, android_ver TEXT,
      model TEXT, rooted INTEGER DEFAULT 0, err_code TEXT, msg TEXT, trace TEXT,
      count INTEGER DEFAULT 1, first_seen INTEGER, last_seen INTEGER, resolved INTEGER DEFAULT 0)`);
    this.run(`CREATE TABLE IF NOT EXISTS installs (
      install_id TEXT PRIMARY KEY, model TEXT, android_ver TEXT, app_ver TEXT,
      mode TEXT DEFAULT 'guest', rooted INTEGER DEFAULT 0, first_seen INTEGER, last_seen INTEGER)`);
    this.run(`CREATE TABLE IF NOT EXISTS deletion_reasons (id INTEGER PRIMARY KEY AUTOINCREMENT, reason TEXT, note TEXT, created INTEGER)`);

    if (!this.one(`SELECT v FROM meta WHERE k='seeded'`)) {
      for (const [kind, value, name] of SEED_SOURCES)
        this.run(`INSERT OR IGNORE INTO sources(kind,value,name,added_by,orig) VALUES(?,?,?,?,?)`, kind, value, name, 'seed', value);
      this.run(`INSERT OR REPLACE INTO meta(k,v) VALUES('seeded','1')`);
    }
    if (!this.one(`SELECT v FROM meta WHERE k='seed_learn'`)) {
      for (const [kind, value, name] of SEED_LEARN)
        this.run(`INSERT OR IGNORE INTO sources(kind,value,name,added_by,orig,purpose) VALUES(?,?,?,?,?,?)`, kind, value, name, 'seed', value, 'learn');
      this.run(`INSERT OR REPLACE INTO meta(k,v) VALUES('seed_learn','1')`);
    }
    if (!this.one(`SELECT v FROM meta WHERE k='seed_bots'`)) {
      for (const [kind, value, name] of SEED_BOTS)
        this.run(`INSERT OR IGNORE INTO sources(kind,value,name,added_by,orig,purpose,nkey) VALUES(?,?,?,?,?,?,?)`, kind, value, name, 'seed', value, 'learn', sourceKey(kind, value));
      this.run(`INSERT OR REPLACE INTO meta(k,v) VALUES('seed_bots','1')`);
    }
    this.tryRun(`ALTER TABLE client_reports ADD COLUMN notified INTEGER DEFAULT 0`);
    if (!this.one(`SELECT v FROM meta WHERE k='kb_seed'`)) {
      for (const k of SEED_KB) this.run(`INSERT INTO kb(match,title,cause,fix,user_msg,needs_code,updated) VALUES(?,?,?,?,?,?,?)`, ...k, Date.now());
      this.run(`INSERT OR REPLACE INTO meta(k,v) VALUES('kb_seed','1')`);
    }
    if (!this.one(`SELECT v FROM meta WHERE k='kb_fix304'`)) {   // تصحيح سبب انهيار الدخول بعد اكتشافه الحقيقي
      this.run(`UPDATE kb SET title=?, cause=?, fix=?, user_msg=?, needs_code=1, updated=? WHERE match LIKE 'NoClassDefFoundError%'`,
        'مكتبة ناقصة: دخول Google على أندرويد 13 وأقدم',
        'مكتبة androidx.core كانت مفقودة من ملف التطبيق منذ 1.1؛ أندرويد 14+ لا يحتاجها فيعمل الدخول، والأقدم ينهار عند «الدخول بحساب Google».',
        'أُصلح في 3.0.4 (إضافة المكتبات صراحة + فحص تلقائي بعد كل بناء). إن ظهر في 3.0.4+ فافحص سجل البناء.',
        'حدّث التطبيق إلى آخر نسخة ثم جرّب الدخول بحساب Google من جديد، أو تابع كزائر الآن.', Date.now());
      this.run(`INSERT OR REPLACE INTO meta(k,v) VALUES('kb_fix304','1')`);
    }
    if (!this.one(`SELECT v FROM meta WHERE k='libcrawl2'`)) {   // إعادة فهرسة البوتات مرة واحدة لتوجيه ملفاتها إلى «مكتبة وظيفتي»
      this.run(`UPDATE sources SET state=NULL WHERE kind='tgbot'`);
      this.run(`INSERT OR REPLACE INTO meta(k,v) VALUES('libcrawl2','1')`);
    }
    if (!this.one(`SELECT v FROM meta WHERE k='retag282'`)) {   // إعادة وسم كل عناصر التعلّم بالقواعد الأدق
      this.run(`UPDATE learn SET hay=NULL`);
      this.run(`INSERT OR REPLACE INTO meta(k,v) VALUES('retag282','1')`);
    }
    // ترحيل 2.8.1ب: حساب الاختصاصات ونص البحث لعناصر التعلّم القديمة
    for (const r of this.q(`SELECT id,title,provider,text,howto FROM learn WHERE hay IS NULL LIMIT 5000`)) {
      const t = this.learnTags(r.title, r.howto, r.provider, r.text);
      this.run(`UPDATE learn SET specs=?, hay=? WHERE id=?`, t.specs, t.hay, r.id);
    }
    // ترحيل 2.8.1: إعادة فحص منشورات مصادر التعلّم التي فيها صورة + نص (كانت تُرفض خطأً)
    if (!this.one(`SELECT v FROM meta WHERE k='mig281'`)) {
      for (const src of this.q(`SELECT kind, value FROM sources WHERE purpose='learn' AND kind='tg'`))
        this.run(`DELETE FROM seen WHERE url LIKE ?`, 'https://t.me/' + src.value + '/%');
      this.run(`UPDATE sources SET rejected=0 WHERE purpose='learn'`);
      this.run(`INSERT OR REPLACE INTO meta(k,v) VALUES('mig281','1')`);
    }
    // ترحيل 2.7.1: حذف مصدرين معطّلين، وإعادة فحص ما رُفض من مصادر التعلّم
    if (!this.one(`SELECT v FROM meta WHERE k='mig271'`)) {
      this.run(`DELETE FROM sources WHERE added_by='seed' AND value IN ('https://www.classcentral.com/report/feed/','https://edlibre.com/feed')`);
      for (const src of this.q(`SELECT kind, value FROM sources WHERE purpose='learn'`)) {
        if (src.kind === 'tg') this.run(`DELETE FROM seen WHERE url LIKE ?`, 'https://t.me/' + src.value + '/%');
        else { const h = String(src.value).replace(/^https?:\/\/(www\.)?/, '').split('/')[0]; if (h) this.run(`DELETE FROM seen WHERE url LIKE ?`, '%' + h + '%'); }
      }
      this.run(`UPDATE sources SET rejected=0 WHERE purpose='learn'`);
      this.run(`INSERT OR REPLACE INTO meta(k,v) VALUES('mig271','1')`);
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
      if (req.method === 'GET' && p === '/api') return json({ ok: true, app: 'wazifati', v: VERSION, now: Date.now(), login: !!this.env.GOOGLE_CLIENT_ID, ...this.minInfo(), flags: this.flagsObj() });
      if (req.method === 'POST' && p === '/client/report') return this.clientReport(b);
      if (req.method === 'POST' && p === '/client/ev') { if (['status_share', 'bot_open'].includes(b.k)) this.evAdd(b.k); return json({ ok: true }); }
      if (req.method === 'POST' && p === '/client/ping') return this.clientPing(req, b);
      if (req.method === 'POST' && p === '/auth') return await this.auth(b);
      if (req.method === 'POST' && p === '/tg/login/start') return await this.tgLoginStart(url);
      if (req.method === 'POST' && p === '/tg/login/poll') return this.tgLoginPoll(b);
      if (req.method === 'POST' && p === '/tg/webhook') return await this.tgWebhook(req, b);
      if (this.keyOk(req)) {
        if (p === '/ingest') return this.ingest(b);
        if (p === '/known') return this.known(b);
        if (p === '/report') return this.report(b);
        if (p === '/tg/secret' && req.method === 'GET') { const r = this.one(`SELECT v FROM tg_secret WHERE k=?`, url.searchParams.get('k') || 'session'); return json({ v: r ? r.v : '' }); }
        if (p === '/tg/secret') { this.run(`INSERT OR REPLACE INTO tg_secret(k,v,at) VALUES(?,?,?)`, String(b.k || 'session'), String(b.v || ''), Date.now()); return json({ ok: true }); }
        if (p === '/tg/status') return this.tgStatus(b);
        if (p === '/tg/digest') return this.digest(!!b.force);
        if (p === '/tg/channel/tick') return await this.chanTick(url.origin, false);
        if (p === '/tg/chanviews') { for (const [id, v] of Object.entries(b.views || {})) this.run(`UPDATE chan_posted SET views=? WHERE msg_id=?`, +v || 0, +id); if (b.subs) this.evSet('chan_subs', +b.subs); return json({ ok: true }); }
        if (p === '/tg/libstats') return json({
          files: this.q(`SELECT provider, COUNT(*) n, SUM(CASE WHEN link LIKE 'https://t.me/wazifati_lib%' THEN 1 ELSE 0 END) lib FROM learn WHERE ftype IS NOT NULL GROUP BY provider`),
          sample: this.q(`SELECT title, link, url FROM learn WHERE ftype IS NOT NULL ORDER BY id DESC LIMIT 3`),
          libmap: ((this.one(`SELECT v FROM tg_secret WHERE k='libmap'`) || {}).v || '').length,
          lib: (this.one(`SELECT v FROM tg_secret WHERE k='lib'`) || {}).v || '',
        });
        if (p === '/tg/notify') { if (this.env.TG_BOT_TOKEN && this.env.TG_CHAT_ID) this.ctx.waitUntil(this.notifyTg(String(b.text || '').slice(0, 900))); return json({ ok: true }); }
        if (p === '/sources') return json({ sources: this.q(`SELECT * FROM sources WHERE enabled=1 AND pending=0 ORDER BY id`) });
        if (req.method === 'GET' && p === '/admin/backup') return this.exportBackup();
        if (req.method === 'POST' && p === '/admin/restore') return this.restoreBackup(b);
      }

      // ----- قراءة عامة (تصفّح كضيف + صفحات المشاركة) -----
      if (req.method === 'GET') {
        let m;
        if ((m = p.match(/^\/j\/(\d+)$/))) return this.jobPage(+m[1], url.searchParams.get('c') || '');
        if ((m = p.match(/^\/go\/(\d+)\/(\d+)$/))) return this.goApply(+m[1], +m[2]);
        if (p === '/download' || p === '/dl') return await this.downloadPage(url);
        if (p === '/apk') return await this.apkFile(url);
        if (p === '/jobs') { const lg = this.legacy(req); if (lg) return lg.jobs; return this.jobs(url, req); }
        if (p === '/changes') return this.changes(url);
        if (p === '/announce') {
          if (url.searchParams.get('all')) return json({ items: this.q(`SELECT id,text,created FROM announcements ORDER BY id DESC LIMIT 50`), ...this.minInfo(), flags: this.flagsObj() });
          const lg = this.legacy(req);
          if (lg) return lg.ann;
          return json({ items: this.q(`SELECT id,text,created FROM announcements WHERE id > ? ORDER BY id DESC LIMIT 20`, +url.searchParams.get('since') || 0) });
        }
        if (p === '/labels') return json(LABELS);
        if (p === '/skills') return this.skillsPublic();
        if (p === '/learn') return this.learnList(req);
      }
      if (p === '/learn/find' && req.method === 'POST') return this.learnFind(req, b);

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
        if (p === '/account/delete') return this.deleteAccount(me, b);
        if (p === '/link/report') return this.reportLink(me, b);
        if (p === '/tg/link/start') return await this.tgLinkStart(url, me);
        if (p === '/learn/report') {   // 3 بلاغات من حسابات مختلفة تقريباً ← إخفاء تلقائي حتى يراجعه الأدمن
          this.run(`UPDATE learn SET reports=COALESCE(reports,0)+1, hidden=CASE WHEN COALESCE(reports,0)+1>=3 THEN 1 ELSE hidden END WHERE id=?`, +b.id);
          return json({ ok: true });
        }
      }

      // ----- أدمن -----
      if (me.role !== 'admin') throw new HttpErr(403, 'هذه الصفحة للأدمن فقط');
      if (req.method === 'GET') {
        if (p === '/admin/sources') return json({ sources: this.q(`SELECT * FROM sources ORDER BY pending DESC, enabled DESC, id`), tg: JSON.parse((this.one(`SELECT v FROM meta WHERE k='tg_account'`) || {}).v || 'null') });
        if (p === '/admin/users') { if (me.email !== FIRST_ADMIN) throw new HttpErr(403, 'هذه الصفحة لمطوّر التطبيق فقط'); return json({ users: this.users(), subs: this.q(`SELECT tg_id, name, uname, specs, govs, tenders, mode, src, created, active, blocked, last_sent, sent FROM bot_subs ORDER BY created DESC LIMIT 2000`), guests: this.guestStats() }); }
        if (p === '/admin/fixes') return json({ fixes: this.q(`SELECT f.id, f.job_id, f.email, f.specs, f.govs, f.sector, f.note, f.created, f.title AS s_title, f.org AS s_org, j.title, j.org AS j_org, j.specs AS j_specs, j.govs AS j_govs, j.sector AS j_sector FROM fixes f LEFT JOIN jobs j ON j.id=f.job_id WHERE f.status='pending' ORDER BY f.id DESC LIMIT 100`) });
        if (p === '/admin/edits') return json({ edits: this.q(`SELECT id, title, title_orig, org, org_orig, edited_by, edit_via, edited_at, edit_reviewed, hidden FROM jobs WHERE edited_at > 0 ${url.searchParams.get('filter') === 'all' ? '' : 'AND edit_reviewed=0'} ORDER BY edited_at DESC LIMIT 200`) });
        if (p === '/admin/pending') return json(this.pending(+url.searchParams.get('since') || 0));
        if (p === '/admin/stats') return json(this.stats());
        if (p === '/admin/features') return json({ flags: this.flagsObj() });
        if (p === '/admin/skills/top') return this.skillsTop();
        if (p === '/admin/link-reports') return json({ reports: this.q(`SELECT skill, url, COUNT(*) AS n, MAX(created) AS last FROM link_reports GROUP BY skill, url ORDER BY last DESC LIMIT 100`) });
        if (p === '/admin/reports') return this.getReports(url);
        if (p === '/admin/channel') return json(this.chanInfo(url.origin));
        if (p === '/admin/channel/reviews') return json({ items: this.chanReviewList(+url.searchParams.get('state') || 0) });
        if (p === '/admin/reach') return json({ text: this.reachText(+url.searchParams.get('d') || 7), sum: this.summaryText(+url.searchParams.get('d') || 7) });
        if (p === '/admin/kb') return json({ kb: this.q(`SELECT * FROM kb ORDER BY id DESC`) });
        if (p === '/admin/flags') return json({ flags: this.q(`SELECT j.id, j.title, j.src, j.url, j.hidden, COUNT(f.email) AS n, GROUP_CONCAT(f.email, '، ') AS who, MAX(f.created) AS last
          FROM flags f JOIN jobs j ON j.id=f.job_id WHERE j.hidden<2 GROUP BY j.id ORDER BY last DESC LIMIT 100`) });
      }
      if (req.method === 'POST') {
        if (p === '/admin/sources/approve') return this.approve(b);
        if (p === '/admin/sources/toggle') { this.run(`UPDATE sources SET enabled=?, fail_since=NULL, auto_off=0 WHERE id=?`, b.enabled ? 1 : 0, +b.id); return json({ ok: true }); }
        if (p === '/admin/learn/hide') { this.run(`UPDATE learn SET hidden=? WHERE id=?`, b.hide === false ? 0 : 1, +b.id); return json({ ok: true }); }
        if (p === '/admin/learn/tojob') return this.learnToJob(+b.id);
        if (p === '/admin/channel') return json(this.chanSet(b));
        if (p === '/admin/channel/skip') { if (b.undo) this.run(`DELETE FROM chan_posted WHERE job_id=? AND skip=1`, +b.id); else this.run(`INSERT OR IGNORE INTO chan_posted(job_id,at,msg_id,skip) VALUES(?,?,0,1)`, +b.id, Date.now()); return json({ ok: true }); }
        if (p === '/admin/channel/review') return json(this.reviewSet(+b.id, b.state != null ? Math.max(0, Math.min(3, +b.state)) : (b.ok ? 1 : 2), me.email));
        if (p === '/admin/channel/post-now') return await this.chanTick(url.origin, b.reset ? 'reset' : b.tenders ? 'tenders' : b.hl ? 'hl' : 'jobs');
        if (p === '/admin/sources/purpose') { this.run(`UPDATE sources SET purpose=? WHERE id=?`, b.purpose === 'learn' ? 'learn' : 'jobs', +b.id); return json({ ok: true }); }
        if (p === '/admin/sources/delete') { this.run(`DELETE FROM sources WHERE id=?`, +b.id); return json({ ok: true }); }
        if (p === '/admin/users/role') { if (me.email !== FIRST_ADMIN) throw new HttpErr(403, 'هذا الإجراء لمطوّر التطبيق فقط'); return this.setRole(me, b); }
        if (p === '/admin/users/status') { if (me.email !== FIRST_ADMIN) throw new HttpErr(403, 'هذا الإجراء لمطوّر التطبيق فقط'); return this.setStatus(me, b); }
        if (p === '/admin/fixes/decide') return this.decideFix(me, b);
        if (p === '/admin/job/edit') return json({ ok: true, changed: this.editJob(+b.id, b.title, b.org == null ? null : b.org, me.email, null) });
        if (p === '/admin/edits/review') {
          if (b.all) this.run(`UPDATE jobs SET edit_reviewed=1 WHERE edited_at > 0`);
          else this.run(`UPDATE jobs SET edit_reviewed=1 WHERE id=?`, +b.id);
          return json({ ok: true });
        }
        if (p === '/admin/edits/restore') return this.restoreEdit(+b.id);
        if (p === '/admin/announce') return this.announce(me, b);
        if (p === '/admin/announce/delete') { this.run(`DELETE FROM announcements WHERE id=?`, +b.id); return json({ ok: true }); }
        if (p === '/admin/minver') return this.setMinVer(b);
        if (p === '/admin/kb/save') {
          const f = [String(b.match || '').trim().slice(0, 300), String(b.title || '').trim().slice(0, 120), String(b.cause || '').trim().slice(0, 600),
            String(b.fix || '').trim().slice(0, 600), String(b.user_msg || '').trim().slice(0, 300), b.needs_code ? 1 : 0];
          if (!f[0] || !f[1]) throw new HttpErr(400, 'النمط والعنوان مطلوبان');
          if (b.id) this.run(`UPDATE kb SET match=?,title=?,cause=?,fix=?,user_msg=?,needs_code=?,updated=? WHERE id=?`, ...f, Date.now(), +b.id);
          else this.run(`INSERT INTO kb(match,title,cause,fix,user_msg,needs_code,updated) VALUES(?,?,?,?,?,?,?)`, ...f, Date.now());
          return json({ ok: true });
        }
        if (p === '/admin/kb/delete') { this.run(`DELETE FROM kb WHERE id=?`, +b.id); return json({ ok: true }); }
        if (p === '/admin/legacy') {
          if (b.mode != null) this.run(`INSERT OR REPLACE INTO meta(k,v) VALUES('legacy_block',?)`, String(Math.max(0, Math.min(2, +b.mode || 0))));
          if (b.msg != null) this.run(`INSERT OR REPLACE INTO meta(k,v) VALUES('legacy_msg',?)`, String(b.msg).trim().slice(0, 300));
          return json(this.legacyStats());
        }
        if (p === '/admin/sources/recrawl') {   // دورة جديدة مع الإبقاء على القائمة الرئيسية المحفوظة (بوتات Manybot لا تعيدها)
          const r = this.one(`SELECT state FROM sources WHERE id=? AND kind='tgbot'`, +b.id);
          let root = null;
          try { root = JSON.parse((r && r.state) || '{}').root || null; } catch (e) { /* حالة تالفة */ }
          this.run(`UPDATE sources SET state=? WHERE id=? AND kind='tgbot'`, root ? JSON.stringify({ root }) : null, +b.id);
          return json({ ok: true });
        }
        if (p === '/admin/flags/decide') return this.decideFlag(b);
        if (p === '/admin/features') return this.setFeature(b);
        if (p === '/admin/skills/save') return this.saveSkill(b);
        if (p === '/admin/skills/delete') { this.run(`DELETE FROM skills WHERE id=?`, String(b.id || '')); return json({ ok: true }); }
        if (p === '/admin/link-reports/clear') { if (b.all) this.run(`DELETE FROM link_reports`); else this.run(`DELETE FROM link_reports WHERE skill=? AND url=?`, String(b.skill || ''), String(b.url || '')); return json({ ok: true }); }
        if (p === '/admin/reports/clear') return this.clearReports(b);
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

  async tgSecret() {
    const d = await crypto.subtle.digest('SHA-256', new TextEncoder().encode('wz-webhook:' + (this.env.TG_BOT_TOKEN || '')));
    return [...new Uint8Array(d)].slice(0, 20).map(x => x.toString(16).padStart(2, '0')).join('');
  }

  async tgApi(method, body) {
    const r = await fetch(`https://api.telegram.org/bot${this.env.TG_BOT_TOKEN}/${method}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) });
    return r.json().catch(() => ({}));
  }

  /** يبدأ دخولاً عبر تيليغرام: رمز لمرة واحدة صالح 5 دقائق + رابط البوت. يضبط الـwebhook أول مرة تلقائياً. */
  async tgLoginStart(url) {
    if (!this.env.TG_BOT_TOKEN) throw new HttpErr(503, 'الدخول عبر تيليغرام غير مهيأ على الخادم');
    await this.ensureHook(url.origin);
    const now = Date.now();
    this.run(`DELETE FROM tg_login WHERE created < ?`, now - 15 * 60e3);
    const nonce = crypto.randomUUID().replace(/-/g, '') + crypto.randomUUID().replace(/-/g, '').slice(0, 16);
    this.run(`INSERT INTO tg_login(nonce,created) VALUES(?,?)`, nonce, now);
    const bot = (this.one(`SELECT v FROM meta WHERE k='tg_bot_user'`) || {}).v || 'wazifati_bot';
    return json({ nonce, link: `https://t.me/${bot}?start=login_${nonce}`, expires: now + 5 * 60e3 });
  }

  tgLoginPoll(b) {
    const r = this.one(`SELECT * FROM tg_login WHERE nonce=?`, String(b.nonce || ''));
    if (!r || Date.now() - r.created > 5 * 60e3) return json({ expired: true });
    if (!r.token) return json({ pending: true });
    this.run(`DELETE FROM tg_login WHERE nonce=?`, r.nonce);   // لمرة واحدة
    const s = this.one(`SELECT email FROM sessions WHERE token=?`, r.token);
    if (!s) return json({ expired: true });
    return json({ token: r.token, me: this.meJson(this.one(`SELECT * FROM accounts WHERE email=?`, s.email)) });
  }

  async tgWebhook(req, b) {
    if (req.headers.get('X-Telegram-Bot-Api-Secret-Token') !== await this.tgSecret()) return json({ ok: false }, 403);
    try {
      if (b.callback_query) await this.botCallback(b.callback_query);
      else if (b.message) await this.botMessage(b.message);
    } catch (e) { /* لا نعيد خطأ لتيليغرام حتى لا يكرر الإرسال */ }
    return json({ ok: true });
  }

  botUserKb() {
    const chan = this.chanCfg().chat.replace(/^@/, '');
    return { inline_keyboard: [[{ text: '📲 صفحة التحميل والشرح', url: `${SITE}/download?from=bot` }], [{ text: '📣 قناة الوظائف', url: `https://t.me/${chan}` }], [{ text: '📞 تواصل مع المطوّر', url: `https://wa.me/${DEV_WA}` }]] };
  }

  async botMessage(m) {
    if (!m.from || m.from.is_bot || !m.chat || m.chat.type !== 'private') return;
    const text = String(m.text || '').trim();
    const send = (t, extra) => this.tgApi('sendMessage', { chat_id: m.chat.id, text: t, ...(extra || {}) });
    let mm;
    if ((mm = text.match(/^\/start\s+login_([A-Za-z0-9]{20,64})/))) return this.botLogin(m, mm[1], send);
    if ((mm = text.match(/^\/start\s+link_([A-Za-z0-9]{20,64})/))) {
      const r = this.one(`SELECT * FROM tg_link_req WHERE nonce=?`, mm[1]);
      if (!r || Date.now() - r.created > 15 * 60e3) return send('⌛ انتهت صلاحية رابط الربط. ارجع للتطبيق واضغط «ربط حسابي ببوت تيليغرام» مجدداً.');
      this.run(`INSERT OR REPLACE INTO tg_links(tg_id,email,at,mute) VALUES(?,?,?,COALESCE((SELECT mute FROM tg_links WHERE tg_id=?),0))`, String(m.from.id), r.email, Date.now(), String(m.from.id));
      this.run(`DELETE FROM tg_link_req WHERE nonce=?`, r.nonce);
      const ad = this.tgAdmin(m.from.id);
      await send(`✅ تم ربط حسابك (${r.email}) بالبوت.` + (ad ? '\nلوحة التحكم جاهزة 👇' : '\nستظهر لوحة التحكم حين تصبح أدمن في التطبيق.'), ad ? { reply_markup: { keyboard: [[{ text: '🛠 لوحة التحكم' }]], resize_keyboard: true, is_persistent: true } } : {});
      if (ad) await this.botPanel(m.chat.id, null, 'p', ad);
      return;
    }
    const ad = this.tgAdmin(m.from.id);
    if ((mm = text.match(/^\/start\s+(report|del)_(\d+)/))) return this.jobReport(m, +mm[2], mm[1]);
    if (/^\/start\s+apk/.test(text)) { await send('📲 هذا ملف تطبيق «وظيفتي» 👇', { reply_markup: this.botUserKb() }); const r = await this.apkSend(m.chat.id); if (r.ok) this.evAdd('bot_apk'); return; }
    const CMD = { '/stats': 's:1', '/week': 's:7', '/month': 's:30', '/funnel': 'f:7', '/channel': 'c', '/top': 'top:7', '/users': 'u', '/errors': 'e', '/sources': 'src', '/reader': 'rd', '/review': 'r:0', '/next': 'c:next', '/postnow': 'c:post', '/pause': 'c:off', '/resume': 'c:on', '/help': 'h', '/panel': 'p', '/admin': 'p' };
    if (ad && (text === '/start' || text === '🛠 لوحة التحكم' || CMD[text.split(/[\s@]/)[0]])) {
      if (text === '/start') await send('👋 أهلاً بك يا أدمن «وظيفتي».', { reply_markup: { keyboard: [[{ text: '🛠 لوحة التحكم' }]], resize_keyboard: true, is_persistent: true } });
      const v = CMD[text.split(/[\s@]/)[0]] || 'p';
      return this.botPanel(m.chat.id, null, v, ad);
    }
    const src = (text.match(/^\/start\s+([\w-]{1,20})/) || [])[1] || '';
    if (/^\/start/.test(text)) this.evAdd('bot_start', src || 'direct');
    return this.subStart(m, src);
  }

  async botLogin(m, nonce, send) {
    const row = this.one(`SELECT * FROM tg_login WHERE nonce=?`, nonce);
    if (!row || Date.now() - row.created > 5 * 60e3) return send('⌛ انتهت صلاحية رابط الدخول. ارجع للتطبيق واضغط «الدخول عبر تيليغرام» مجدداً.');
    if (row.token) return send('✅ تم الدخول مسبقاً، ارجع إلى التطبيق.');
    const email = `tg${m.from.id}@telegram.user`;   // معرّف ثابت لحساب تيليغرام (لا نحصل على رقم الهاتف)
    const name = [m.from.first_name, m.from.last_name].filter(Boolean).join(' ').slice(0, 80) || (m.from.username ? '@' + m.from.username : 'مستخدم تيليغرام');
    const now = Date.now();
    const acc = this.one(`SELECT * FROM accounts WHERE email=?`, email);
    if (acc && acc.status === 'blocked') return send('⛔ هذا الحساب موقوف. تواصل مع مدير التطبيق.');
    if (!acc) this.run(`INSERT INTO accounts(email,name,photo,role,status,created,last_seen) VALUES(?,?,?,?,?,?,?)`, email, name, '', 'user', 'active', now, now);
    else this.run(`UPDATE accounts SET name=?, last_seen=? WHERE email=?`, name, now, email);
    const token = crypto.randomUUID().replace(/-/g, '') + crypto.randomUUID().replace(/-/g, '');
    this.run(`INSERT INTO sessions(token,email,created,dev) VALUES(?,?,?,?)`, token, email, now, 'telegram');
    this.run(`UPDATE tg_login SET token=?, name=? WHERE nonce=?`, token, name, row.nonce);
    return send(`✅ تم تسجيل دخولك إلى «وظيفتي» باسم ${name}.\nارجع الآن إلى التطبيق 👈`);
  }

  async botCallback(q) {
    if (String(q.data || '').startsWith('u:') && q.message) return this.subCallback(q, String(q.data));
    const ad = this.tgAdmin(q.from.id);
    const ans = t => this.tgApi('answerCallbackQuery', { callback_query_id: q.id, ...(t ? { text: t } : {}) });
    if (!ad || !q.message) return ans('هذه الأزرار للأدمن فقط');
    const d = String(q.data || 'p'), chat = q.message.chat.id, mid = q.message.message_id;
    let mm;
    if ((mm = d.match(/^j([xk]):(\d+)$/))) {   // قرار بلاغ احتيال / طلب حذف
      const res = mm[1] === 'x' ? await this.jobKill(+mm[2]) : 'kept';
      const lbl = { deleted: '✅ أُخفيت الفرصة وحُذف منشورها', edited: '✅ أُخفيت وأُزيلت من منشور الجهة', hidden: '✅ أُخفيت الفرصة', kept: '✔ تُركت كما هي' }[res];
      await this.tgApi('editMessageText', { chat_id: chat, message_id: mid, text: String(q.message.text || '').slice(0, 3500) + `\n\n${lbl} (${ad.email.replace(/@.*/, '')})` });
      return ans(lbl);
    }
    if ((mm = d.match(/^r([aru]):(\d+)$/))) {   // قرار مراجعة على رسالة فرصة
      const st = { a: 1, r: 2, u: 0 }[mm[1]];
      this.reviewSet(+mm[2], st, ad.email);
      const who = ad.email.replace(/@.*/, '');
      const lbl = { 1: '✅ تمت الموافقة — تُنشر في الدفعة القادمة', 2: '❌ رُفضت — تبقى في التطبيق فقط', 0: '↩ أُعيدت إلى «بانتظار المراجعة»' }[st];
      await this.tgApi('editMessageText', { chat_id: chat, message_id: mid, text: String(q.message.text || '').slice(0, 3500) + `\n\n${lbl} (${who})` });
      return ans(lbl.split(' — ')[0]);
    }
    await ans();
    return this.botPanel(chat, mid, d, ad, q.from.id);
  }

  /** لوحة التحكم: كل زر يعرض نتيجته بنفس الرسالة مع أزرار فرعية */
  async botPanel(chat, mid, v, ad, tgId) {
    const back = [{ text: '⬅ القائمة', callback_data: 'p' }];
    const per = (pre, d) => [[1, 'اليوم'], [7, '7 أيام'], [30, '30 يوماً']].map(([n, t]) => ({ text: (n === d ? '• ' : '') + t, callback_data: `${pre}:${n}` }));
    const n = (sql, ...a) => (this.one(sql, ...a) || {}).n || 0;
    const cfg = this.chanCfg();
    let text = '', kb = [];
    const [k, arg] = v.split(':');
    const days = Math.max(1, Math.min(90, +arg || 1));
    if (k === 's') { text = this.summaryText(days); kb = [per('s', days), [{ text: '🔄 تحديث', callback_data: v }], back]; }
    else if (k === 'f') { text = this.reachText(days); kb = [per('f', days), [{ text: '🔄 تحديث', callback_data: v }], back]; }
    else if (k === 'top') {
      const since = Date.now() - days * DAY;
      const tv = this.q(`SELECT e.job_id, e.n, j.title, j.text FROM job_ev e JOIN jobs j ON j.id=e.job_id WHERE e.k='view' AND j.created>? ORDER BY e.n DESC LIMIT 5`, since - 14 * DAY);
      const ta = this.q(`SELECT e.job_id, e.n, j.title, j.text FROM job_ev e JOIN jobs j ON j.id=e.job_id WHERE e.k='apply' AND j.created>? ORDER BY e.n DESC LIMIT 5`, since - 14 * DAY);
      const tp = this.q(`SELECT p.views n, j.title, j.text FROM chan_posted p JOIN jobs j ON j.id=p.job_id WHERE p.at>? AND p.views>0 ORDER BY p.views DESC LIMIT 5`, since);
      const L = (a) => a.length ? a.map((x, i) => `${i + 1}. ${this.unNorm(x.text, x.title).slice(0, 45)} (${x.n})`).join('\n') : '—';
      text = `🔥 الأكثر اهتماماً\n\n👁 مشاهدات في القناة:\n${L(tp)}\n\n🧲 زيارات صفحة الفرصة:\n${L(tv)}\n\n✋ ضغطات التقديم:\n${L(ta)}`;
      kb = [per('top', days), back];
    } else if (k === 'u') {
      const since = Date.now() - 7 * DAY;
      const vers = this.q(`SELECT app_ver v, COUNT(*) n FROM installs WHERE last_seen>? AND app_ver<>'' GROUP BY app_ver ORDER BY n DESC LIMIT 6`, since);
      const nb = {};
      for (const r of this.q(`SELECT model FROM installs WHERE last_seen>?`, since)) { let x = (String(r.model).trim().split(/\s+/)[0] || '?').toLowerCase(); if (/^sm-/.test(x)) x = 'samsung'; nb[x] = (nb[x] || 0) + 1; }
      text = `👥 المستخدمون\n\nالحسابات: ${n(`SELECT COUNT(*) n FROM accounts`)} (جديدة اليوم ${n(`SELECT COUNT(*) n FROM accounts WHERE created>?`, Date.now() - DAY)}، هذا الأسبوع ${n(`SELECT COUNT(*) n FROM accounts WHERE created>?`, since)})\nنشطون اليوم: ${n(`SELECT COUNT(*) n FROM accounts WHERE last_seen>?`, Date.now() - DAY)}\nأجهزة نشطة (7 أيام): ${n(`SELECT COUNT(*) n FROM installs WHERE last_seen>?`, since)}\n\n🔢 النسخ: ${vers.map(x => `${x.v} (${x.n})`).join('، ') || '—'}\n📱 الأجهزة: ${Object.entries(nb).sort((a, b) => b[1] - a[1]).slice(0, 6).map(([a, b]) => `${a} ${b}`).join('، ') || '—'}`;
      kb = [[{ text: '🔄 تحديث', callback_data: 'u' }], back];
    } else if (k === 'e') {
      const rs = this.q(`SELECT kind, err_code, count, model, app_ver, last_seen FROM client_reports WHERE resolved=0 ORDER BY last_seen DESC LIMIT 8`);
      text = rs.length ? '🐞 الأخطاء غير المحلولة\n\n' + rs.map(r => `${r.kind === 'auth_fail' ? '🔑' : '💥'} ${r.err_code} ×${r.count}\n   📱 ${r.model} • v${r.app_ver}`).join('\n') + '\n\nالتفاصيل والحلول من التطبيق: الإدارة ← الأخطاء' : '✅ لا أخطاء غير محلولة';
      kb = [[{ text: '🔄 تحديث', callback_data: 'e' }], back];
    } else if (k === 'src') {
      const fail = this.q(`SELECT name,value,last_err FROM sources WHERE enabled=1 AND pending=0 AND kind<>'manual' AND fail_since>0 LIMIT 8`);
      const off = this.q(`SELECT name,value FROM sources WHERE auto_off=1 AND enabled=0 LIMIT 8`);
      const pend = this.q(`SELECT name,value,added_by FROM sources WHERE pending=1 LIMIT 8`);
      text = `📡 المصادر\n\nفعّالة: ${n(`SELECT COUNT(*) n FROM sources WHERE enabled=1`)}\n\n❌ تفشل: ${fail.length ? '\n' + fail.map(f => `• ${f.name || f.value}: ${String(f.last_err || '').slice(0, 60)}`).join('\n') : 'لا شيء'}\n\n⏸ أُوقفت تلقائياً: ${off.length ? off.map(f => f.name || f.value).join('، ') : 'لا شيء'}\n\n📨 مقترحة بانتظار موافقتك: ${pend.length ? '\n' + pend.map(f => `• ${f.name || f.value} (${f.added_by})`).join('\n') : 'لا شيء'}`;
      kb = [[{ text: '🔄 تحديث', callback_data: 'src' }], back];
    } else if (k === 'rd') {
      const tg = JSON.parse((this.one(`SELECT v FROM meta WHERE k='tg_account'`) || {}).v || 'null');
      text = !tg ? '👤 حساب القراءة غير مفعّل' : `👤 حساب القراءة: ${tg.ok ? '✅ متصل' : '⚠️ متوقف'}${tg.name ? ' (' + tg.name + ')' : ''}\nآخر تحديث: ${new Date(tg.at + 3 * 3600e3).toISOString().slice(0, 16).replace('T', ' ')}${tg.ok ? '' : '\nالسبب: ' + tg.err}`;
      kb = [[{ text: '🔄 تحديث', callback_data: 'rd' }], back];
    } else if (k === 'c') {
      if (arg === 'on' || arg === 'off') this.chanSet({ on: arg === 'on' });
      if (arg === 'mode') this.chanSet({ mode: cfg.mode === 'all' ? 'best' : 'all' });
      const c2 = this.chanCfg(), info = this.chanInfo(SITE);
      if (arg === 'reset') {
        text = `🧹 مسح القناة وإعادة النشر؟\n\nيحذف البوت كل منشوراته في ${c2.chat} (منشوراتك اليدوية لا تُمس)، ثم يعيد تثبيت ملف التطبيق ويعيد نشر ${info.fillCount} فرصة ومناقصة من آخر 7 أيام بالترتيب الزمني وبصمت (20 منشوراً بالدقيقة تقريباً). المشتركون لا يتأثرون.`;
        kb = [[{ text: '✅ نعم، امسح وأعد النشر', callback_data: 'c:reset!' }, { text: 'إلغاء', callback_data: 'c' }]];
        return this.botShow(chat, mid, text, kb);
      }
      if (arg === 'reset!') {
        const r = await this.chanReset(SITE);
        text = `🧹 حُذف ${r.deleted} منشور من ${r.total}.\n📌 ملف التطبيق: ${r.pin}\n📥 بدأت التعبئة: نُشر ${r.first && r.first.n || 0} حتى الآن، والباقي يُنشر تلقائياً كل دقيقة. سيصلك تنبيه عند الانتهاء.`;
        kb = [[{ text: '📣 القناة', callback_data: 'c' }], back];
        return this.botShow(chat, mid, text, kb);
      }
      if (arg === 'post!' || arg === 'hl!') {
        const r = await (await this.chanTick(SITE, arg === 'hl!' ? 'hl' : 'jobs')).json();
        text = r.ok ? (r.empty ? 'لا توجد فرص جديدة للنشر الآن.' : `✅ نُشرت ${r.n} ${arg === 'hl!' ? 'في منشور «أبرز الفرص»' : 'فرصة'}`) : '❌ ' + r.err;
        kb = [[{ text: '📣 القناة', callback_data: 'c' }], back];
      } else if (arg === 'post' || arg === 'hl') {
        text = arg === 'hl' ? `نشر «⭐ أبرز الفرص» الآن (بإشعار للمشتركين)؟` : `نشر ${Math.min(info.waiting, c2.mode === 'all' ? 15 : c2.n)} فرصة الآن في ${c2.chat}؟`;
        kb = [[{ text: '✅ نعم، انشر', callback_data: `c:${arg}!` }, { text: 'إلغاء', callback_data: 'c' }]];
      } else if (arg === 'next') {
        text = `👀 الدفعة القادمة (${info.next.length} من ${info.waiting} بانتظار النشر):\n\n` + (info.next.map((x, i) => `${i + 1}. ${x.title}${x.org ? ' — ' + x.org : ''}${x.contact ? ' 📞' : ''}`).join('\n') || 'لا شيء جديد');
        kb = [[{ text: '🚀 انشر الآن', callback_data: 'c:post' }], [{ text: '📣 القناة', callback_data: 'c' }], back];
      } else {
        const subs = (this.one(`SELECT n FROM ev WHERE k='chan_subs' ORDER BY day DESC LIMIT 1`) || {}).n || 0;
        text = `📣 قناة الوظائف ${c2.chat}\n\nالنشر التلقائي: ${c2.on ? '▶ يعمل' : '⏸ متوقف'}${info.fill && info.fill.on ? `\n📥 التعبئة جارية: نُشر ${info.fill.n}` : ''}\nالطريقة: ${c2.mode === 'all' ? 'كل فرصة ومناقصة فور التقاطها بصمت + «أبرز الفرص» بإشعار' : 'أبرز الفرص فقط'}\nالمواعيد: ${c2.times.join(' و ')} • حد يومي ${c2.cap}\n👥 المشتركون: ${subs}\n📤 نُشر خلال 24 ساعة: ${info.today}\n🕓 بانتظار النشر: ${info.waiting}\n⏳ بانتظار المراجعة: ${info.counts.pending}${info.err ? '\n⚠️ آخر خطأ: ' + info.err.err : ''}`;
        kb = [[{ text: '👀 الدفعة القادمة', callback_data: 'c:next' }, { text: '🚀 انشر الآن', callback_data: 'c:post' }], [{ text: '⭐ أبرز الفرص الآن', callback_data: 'c:hl' }],
          [{ text: c2.on ? '⏸ إيقاف النشر' : '▶ تشغيل النشر', callback_data: c2.on ? 'c:off' : 'c:on' }, { text: c2.mode === 'all' ? '🔁 أبرز الفرص فقط' : '🔁 كل الفرص فوراً', callback_data: 'c:mode' }],
          [{ text: '🧹 مسح القناة وإعادة النشر', callback_data: 'c:reset' }], back];
      }
    } else if (k === 'r') {
      const st = [0, 2, 3].includes(+arg) ? +arg : 0;
      const items = this.chanReviewList(st);
      const cnt = s => n(`SELECT COUNT(*) n FROM chan_review r JOIN jobs j ON j.id=r.job_id WHERE r.state=? AND j.hidden=0`, s);
      const name = { 0: '⏳ بانتظار المراجعة', 2: '🚫 المرفوضة', 3: '🗄 المؤرشفة' }[st];
      text = `${name}: ${items.length}${items.length > 8 ? ' (أعرض أحدث 8)' : ''}` + (st === 0 ? '\nما لا تراجعه خلال 5 أيام يُؤرشف تلقائياً.' : '');
      kb = [[{ text: `⏳ بانتظار (${cnt(0)})`, callback_data: 'r:0' }, { text: `🚫 مرفوضة (${cnt(2)})`, callback_data: 'r:2' }, { text: `🗄 مؤرشفة (${cnt(3)})`, callback_data: 'r:3' }], back];
      await this.botShow(chat, mid, text, kb);
      for (const x of items.slice(0, 8)) {
        const t = `${x.title}${x.org ? '\n🏢 ' + x.org : ''}\n📞 ${x.contact}${x.expired ? '\n⏳ انتهى موعدها' : ''}${st ? `\n${st === 2 ? 'رُفضت' : 'أُرشفت'}: ${new Date(x.at + 3 * 3600e3).toISOString().slice(0, 10)} — ${String(x.by || '').replace(/@.*/, '')}` : ''}\n\n${String(x.text || '').slice(0, 900)}`;
        const bk = st === 0 ? [[{ text: '✅ موافق', callback_data: `ra:${x.id}` }, { text: '❌ رفض', callback_data: `rr:${x.id}` }]]
          : [[{ text: '↩ تراجع', callback_data: `ru:${x.id}` }, ...(x.expired ? [] : [{ text: '✅ موافق وانشر', callback_data: `ra:${x.id}` }])]];
        await this.tgApi('sendMessage', { chat_id: chat, text: t.slice(0, 4000), disable_web_page_preview: true, reply_markup: { inline_keyboard: bk } });
      }
      return;
    } else if (k === 'bs') {
      const st = this.subStats(7);
      const bySrc = this.evBy('bot_sub', 30).map(x => `${x.v || 'مباشر'} ${x.n}`).join('، ');
      const recent = this.q(`SELECT name, uname, specs, govs, created, mode FROM bot_subs WHERE active=1 ORDER BY created DESC LIMIT 8`);
      text = `🤖 مشتركو البوت\n\n✅ فعّالون: ${st.total} • بدؤوا البوت: ${st.started}\n🆕 جدد خلال 7 أيام: ${st.fresh}\n🎯 وصلتهم فرصة هذا الأسبوع: ${st.reached}\n📨 رسائل فرص خلال 7 أيام: ${st.delivered}\n🚪 أوقفوا أو حظروا البوت: ${st.left}${bySrc ? '\n📍 مصدر الاشتراك (30 يوماً): ' + bySrc : ''}\n\nآخر المشتركين:\n` +
        (recent.map(r => `• ${r.name || ''}${r.uname ? ' @' + r.uname : ''} — ${r.specs === 'all' ? 'كل الاختصاصات' : String(r.specs || '').split(',').map(x => LABELS.specs[x]).filter(Boolean).slice(0, 2).join('، ')} / ${r.govs === 'all' ? 'كل المحافظات' : String(r.govs || '').split(',').map(x => LABELS.govs[x]).filter(Boolean).slice(0, 2).join('، ')}`).join('\n') || '—');
      kb = [[{ text: '🔄 تحديث', callback_data: 'bs' }], back];
    } else if (k === 'apk') {
      const r = await this.apkSend(chat);
      text = r.ok ? '📲 أُرسل ملف التطبيق أعلاه — يمكنك إعادة توجيهه لأي شخص.' : '❌ تعذّر إرسال الملف: ' + (r.description || '');
      kb = [back];
    } else if (k === 'mute') {
      const id = String(tgId || chat);
      const cur = this.one(`SELECT mute FROM tg_links WHERE tg_id=?`, id);
      if (cur) this.run(`UPDATE tg_links SET mute=? WHERE tg_id=?`, cur.mute ? 0 : 1, id);
      else this.run(`INSERT INTO tg_links(tg_id,email,at,mute) VALUES(?,?,?,1)`, id, ad.email, Date.now());
      return this.botPanel(chat, mid, 'p', ad, tgId);
    } else if (k === 'h') {
      text = '❓ لوحة التحكم\n\n📊 الإحصائيات: الفرص والحسابات والتثبيتات والنسخ.\n📈 الوصول: صفحة التحميل ← التحميل ← التثبيت ← الحساب، والقناة وضغطات التقديم.\n📣 القناة: الحالة، الدفعة القادمة، النشر الآن، الإيقاف والتشغيل.\n⏳ المراجعة: الفرص التي فيها واتساب/هاتف/معرّف — موافقة أو رفض أو تراجع.\n🐞 الأخطاء • 📡 المصادر • 👤 حساب القراءة.\n🔕 التنبيهات: إيقاف/تشغيل تنبيهات البوت لك وحدك.\n\nالأوامر النصية الاحتياطية: /stats /week /month /funnel /channel /review /top /users /errors /sources /reader';
      kb = [back];
    } else {
      const pend = n(`SELECT COUNT(*) n FROM chan_review WHERE state=0`), errs = n(`SELECT COUNT(*) n FROM client_reports WHERE resolved=0`);
      const muted = (this.one(`SELECT mute FROM tg_links WHERE tg_id=?`, String(tgId || chat)) || {}).mute;
      const bs = this.subStats(1);
      text = `🛠 لوحة تحكم وظيفتي\n\n🎯 مشتركو البوت: ${bs.total} (جدد اليوم ${bs.fresh}) • وصلتهم فرصة هذا الأسبوع: ${bs.reached}\n📲 تثبيتات اليوم: ${n(`SELECT COUNT(*) n FROM installs WHERE first_seen>?`, Date.now() - DAY)} • 👤 حسابات اليوم: ${n(`SELECT COUNT(*) n FROM accounts WHERE created>?`, Date.now() - DAY)}\n⬇ زيارات صفحة التحميل اليوم: ${this.evSum('dl_page', 1)}\n⏳ بانتظار المراجعة: ${pend}${errs ? `\n🐞 أخطاء غير محلولة: ${errs}` : ''}`;
      kb = [[{ text: '📊 الإحصائيات', callback_data: 's:1' }, { text: '📈 التحميل والوصول', callback_data: 'f:1' }],
        [{ text: '📣 القناة', callback_data: 'c' }, { text: `⏳ المراجعة${pend ? ' (' + pend + ')' : ''}`, callback_data: 'r:0' }],
        [{ text: '🔥 الأكثر اهتماماً', callback_data: 'top:7' }, { text: '👥 المستخدمون', callback_data: 'u' }],
        [{ text: `🐞 الأخطاء${errs ? ' (' + errs + ')' : ''}`, callback_data: 'e' }, { text: '📡 المصادر', callback_data: 'src' }],
        [{ text: '👤 حساب القراءة', callback_data: 'rd' }, { text: '📲 ملف التطبيق', callback_data: 'apk' }],
        [{ text: '🔔 اشتراكي بالفرص', callback_data: 'u:edit' }, { text: '🤖 مشتركو البوت', callback_data: 'bs' }],
        [{ text: muted ? '🔔 تشغيل التنبيهات' : '🔕 إيقاف التنبيهات', callback_data: 'mute' }, { text: '❓ مساعدة', callback_data: 'h' }]];
    }
    return this.botShow(chat, mid, text, kb);
  }

  async botShow(chat, mid, text, kb) {
    const body = { chat_id: chat, text: String(text).slice(0, 4000), disable_web_page_preview: true, reply_markup: { inline_keyboard: kb } };
    if (mid) { const r = await this.tgApi('editMessageText', { ...body, message_id: mid }); if (r.ok || /not modified/.test(r.description || '')) return r; }
    return this.tgApi('sendMessage', body);
  }

  who(req) {
    const t = bearer(req);
    if (!t) throw new HttpErr(401, 'سجّل الدخول أولاً');
    const s = this.one(`SELECT email FROM sessions WHERE token=?`, t);
    if (!s) throw new HttpErr(401, 'انتهت الجلسة، سجّل الدخول من جديد');
    const a = this.one(`SELECT * FROM accounts WHERE email=?`, s.email);
    if (!a) throw new HttpErr(401, 'الحساب غير موجود');
    if (a.status === 'blocked') throw new HttpErr(403, 'هذا الحساب موقوف. تواصل مع مدير التطبيق.');
    // «آخر فتح» لا يتحدّث من مزامنة الخلفية (ترسل X-Bg)، و«آخر مزامنة» يتحدّث من أي طلب
    const nowMs = Date.now(), bg = req.headers.get('X-Bg') === '1';
    if (!a.last_sync || nowMs - a.last_sync > 600e3) this.run(`UPDATE accounts SET last_sync=? WHERE email=?`, nowMs, a.email);
    if (!bg && (!a.last_seen || nowMs - a.last_seen > 600e3)) this.run(`UPDATE accounts SET last_seen=? WHERE email=?`, nowMs, a.email);
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
  /** فرصة من تيليغرام نُشرت في قناتنا ← رابط منشورنا ومصدرها «قناة وظيفتي» بدل القناة الأخرى.
   *  المرفوض/المؤرشف/غير المنشور يبقى بمصدره الأصلي. المواقع الحقيقية تبقى كما هي دائماً. */
  pubUrl(r) { return this.pubInfo(r).u; }
  pubInfo(r) {
    const u = String(r.url || '');
    if (!/^https?:\/\/(t\.me|telegram\.me)\//i.test(u)) return { u, src: r.src };
    if (!this._cmap || Date.now() - this._cmap.at > 120e3) {
      const m = new Map(); for (const x of this.q(`SELECT job_id, msg_id FROM chan_posted WHERE skip=0 AND msg_id>0`)) m.set(x.job_id, x.msg_id);
      this._cmap = { at: Date.now(), m, cfg: this.chanCfg() };
    }
    const mid = this._cmap.m.get(r.id), link = mid && this.chanLink(this._cmap.cfg, mid);
    return link ? { u: link, src: '📣 قناة وظيفتي' } : { u, src: r.src };
  }

  jobRow(r) {
    return {
      id: r.id, t: r.title, o: r.org, s: r.specs, g: r.govs, c: r.sector, d: r.deadline,
      p: r.posted, src: this.pubInfo(r).src, n: (r.srcs || '').split('\n').filter(Boolean).length, u: this.pubUrl(r), a: r.apply, x: r.text, sh: r.shared_by ? 1 : 0,
      im: r.image || '', dt: r.doubt ? 1 : 0, k: r.kind || 'job', tc: r.tcat || '', bd: r.bond || '',
    };
  }

  /** الزائر (بلا جلسة صالحة): أحدث GUEST_JOBS وظيفة وGUEST_TENDERS مناقصة فقط، مع عدد المقفل. */
  guestJobs(url) {
    const now = Date.now();
    // 3.4: الزائر يرى أحدث الفرص المطابقة لاهتماماته التي اختارها عند أول فتح (sp/gv) إن وُجدت
    const sp = new Set(String((url && url.searchParams.get('sp')) || '').split(',').filter(Boolean));
    const gv = new Set(String((url && url.searchParams.get('gv')) || '').split(',').filter(Boolean));
    const fits = r => (!sp.size || String(r.specs || '').split(',').some(x => sp.has(x))) && (!gv.size || String(r.govs || '').split(',').some(x => gv.has(x) || x === 'all'));
    const okDl = new Date(now - 2 * DAY).toISOString().slice(0, 10);
    const base = `hidden=0 AND doubt=0 AND created > ? AND (deadline IS NULL OR deadline='' OR deadline >= ?)`;
    const pick = (kind, n) => {
      const rows = this.q(`SELECT * FROM jobs WHERE ${base} AND COALESCE(kind,'job')=? ORDER BY id DESC LIMIT 600`, now - FIRST_DAYS * DAY, okDl, kind);
      const m = kind === 'job' && (sp.size || gv.size) ? rows.filter(fits) : [];
      return (m.length >= Math.min(n, 3) ? m : rows).slice(0, n);
    };
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

  /**
   * النسخ القديمة (بلا ترويسة X-App-Ver، أي أقدم من 3.0): تُسجَّل للعدّاد، وحسب مفتاح الأدمن legacy_block:
   * 0 لا شيء • 1 إيقاف الحسابات المعروف أن نسختها أقدم من 1.9 (لا تملك التحديث الإجباري) • 2 إيقاف كل نسخة بلا بصمة.
   * الموقوفة لا تصلها الفرص، بل بطاقة واحدة مثبّتة وإعلان يدعوان لتحميل النسخة الجديدة.
   */
  legacy(req) {
    if (req.headers.get('X-App-Ver')) return null;
    const t = bearer(req);
    const ses = t && this.one(`SELECT email FROM sessions WHERE token=?`, t);
    const acc = ses && this.one(`SELECT ver FROM accounts WHERE email=?`, ses.email);
    const ver = (acc && acc.ver) || '';
    const ip = req.headers.get('CF-Connecting-IP') || '';
    const key = ses ? 'u:' + ses.email : 'g:' + ip + '|' + (req.headers.get('User-Agent') || '').slice(0, 40);
    const now = Date.now();
    const prev = this.one(`SELECT last FROM legacy_seen WHERE k=?`, key);
    if (!prev || now - prev.last > 3600e3) this.run(`INSERT OR REPLACE INTO legacy_seen(k,ver,last) VALUES(?,?,?)`, key, ver, now);
    const mode = +((this.one(`SELECT v FROM meta WHERE k='legacy_block'`) || {}).v || 0);
    const veryOld = ver && /^\d/.test(ver) && cmpVer(ver, '1.9') < 0;
    if (!(mode === 2 || (mode === 1 && veryOld))) return null;
    const msg = (this.one(`SELECT v FROM meta WHERE k='legacy_msg'`) || {}).v
      || 'نسختك من «وظيفتي» قديمة ولم تعد تستقبل الفرص الجديدة. حمّل النسخة الجديدة مجاناً من الرابط ثم ثبّتها فوق القديمة (بياناتك تبقى كما هي).';
    const ID = 2000000000;
    const row = { id: ID, title: '⚠️ حدّث تطبيق وظيفتي لتستمر الفرص بالوصول', org: 'وظيفتي', specs: '', govs: 'all', sector: '', deadline: '',
      posted: now, src: 'وظيفتي', srcs: '', url: RELEASE, apply: RELEASE, text: msg + '\n\n' + RELEASE, image: '', doubt: 0, kind: 'job' };
    return {
      jobs: json({ now, last: ID, max: ID, more: false, total: 1, locked: 0, jobs: [this.jobRow(row)] }),
      ann: json({ items: [{ id: ID, text: '⚠️ ' + msg + ' ' + RELEASE, created: now }] }),
    };
  }

  installBrands() {
    const by = {};
    for (const r of this.q(`SELECT model, COUNT(*) AS n FROM installs WHERE model<>'' GROUP BY model`)) {
      const parts = String(r.model).trim().split(/\s+/);
      let brand = (parts[0] || '?').toLowerCase();
      if (/^(sm-|galaxy)/i.test(brand)) brand = 'samsung';
      if (/^(redmi|poco|mi)$/i.test(brand)) brand = 'xiaomi';
      brand = brand.charAt(0).toUpperCase() + brand.slice(1);
      const model = parts.length > 1 ? parts.slice(1).join(' ') : r.model;
      (by[brand] = by[brand] || { brand, n: 0, models: [] }).n += r.n;
      by[brand].models.push({ model, n: r.n });
    }
    return Object.values(by).sort((a, b) => b.n - a.n).map(x => ({ ...x, models: x.models.sort((a, b) => b.n - a.n) }));
  }

  legacyStats() {
    const since = Date.now() - 7 * DAY;
    const rows = this.q(`SELECT k, ver FROM legacy_seen WHERE last > ?`, since);
    const byVer = {};
    let users = 0, guests = 0;
    for (const r of rows) {
      if (r.k.startsWith('u:')) users++; else guests++;
      const v = r.ver || 'غير معروف';
      byVer[v] = (byVer[v] || 0) + 1;
    }
    return { mode: +((this.one(`SELECT v FROM meta WHERE k='legacy_block'`) || {}).v || 0), total: rows.length, users, guests, byVer,
      msg: (this.one(`SELECT v FROM meta WHERE k='legacy_msg'`) || {}).v || '' };
  }

  jobs(url, req) {
    const one = +url.searchParams.get('id') || 0;
    if (one) {   // فرصة واحدة بالرابط: مفتوحة للجميع
      const r = this.one(`SELECT * FROM jobs WHERE id=? AND hidden=0`, one);
      return json({ now: Date.now(), last: one, max: one, more: false, jobs: r ? [this.jobRow(r)] : [] });
    }
    const t = bearer(req);
    if (!t || !this.one(`SELECT 1 FROM sessions WHERE token=?`, t)) return this.guestJobs(url);
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
    const rows = this.q(`SELECT id, title, org, specs, govs, sector, hidden, doubt, kind, tcat, bond FROM jobs WHERE updated > ? ORDER BY updated LIMIT 1000`, since);
    return json({ now: Date.now(), items: rows.map(r => ({ id: r.id, t: r.title, o: r.org || '', s: r.specs, g: r.govs, c: r.sector, h: r.hidden ? 1 : 0, dt: r.doubt ? 1 : 0, k: r.kind || 'job', tc: r.tcat || '', bd: r.bond || '' })) });
  }

  addJob(post, opts = {}) {
    let text = String(post.text || '').trim();
    const image = String(post.image || '').slice(0, 600);
    if (text.length < 20 && !image) return 'short';
    const capt = text.length >= 30;   // صورة مع نص حقيقي (منشورات الدورات غالباً صورة + وصف)
    if (!text) text = '📷 الإعلان داخل الصورة';
    const url = String(post.url || '').slice(0, 500);
    if (url && this.q(`SELECT 1 FROM seen WHERE url=?`, url).length) {
      // ملف مفهرس سابقاً وصار له رابط مباشر في «مكتبة وظيفتي» ← حدّث رابطه
      if (post.file && post.open && /^https:\/\/t\.me\/wazifati_lib/.test(post.open)) this.run(`UPDATE learn SET link=? WHERE url=? AND link<>?`, post.open, url, post.open);
      return 'seen';
    }
    const now = Date.now();
    const posted = +post.date || now;
    if (opts.purpose === 'learn') {   // مصدر تعلّم: يُقبل كل منشور تعليمي/فيه رابط ما لم يكن إعلان وظيفة واضحاً
      if (posted < now - 90 * DAY) { this.markSeen(url); return 'old'; }
      const cj = classify(text, { now: new Date(), links: post.links || [] });
      const jj = jobScore(text, cj, {});
      const looksJob = cj.isJob && jj.verdict === 'ok' && !learnInfo(text, post.links || []);
      if (post.file && this.addLearn(post, text, url, posted, now, true)) return 'learn';
      if (!looksJob && (!image || capt) && this.addLearn(post, text, url, posted, now, true)) return 'learn';
      if (!looksJob) { this.markSeen(url); return 'notjob'; }
    }
    if (posted < now - FIRST_DAYS * DAY && !opts.force) { this.markSeen(url); return 'old'; }
    // منح دراسية/ممولة ليست وظائف حتى لو جاءت من مصدر وظائف
    if (!opts.force && !post.file) {
      const tn = nrm(text);
      if (/(منحه دراسيه|منح دراسيه|منحه لدراسه|منحه ماجستير|منحه دكتوراه|منح ماجستير|ممول بالكامل|ممولة بالكامل|scholarship|fully funded|fellowship)/i.test(tn)
        && !/(مطلوب|شاغر|نعلن عن حاجه|vacanc|job title|position:)/i.test(tn) && this.addLearn(post, text, url, posted, now, true)) return 'learn';
    }
    const c = classify(text, { now: new Date(), links: post.links || [], force: opts.force, deadline: post.deadline || '', sectorHint: post.sector || '', srcName: post.srcName || '' });
    if (post.title && post.title.length > 3) c.title = String(post.title).slice(0, 140);
    if (post.org) c.org = String(post.org).slice(0, 80);
    const tinfo = tenderInfo(text, c);
    const js = jobScore(text, c, { image: !!image });
    if (tinfo && js.verdict === 'reject' && js.score > -10 && (c.deadline || c.org)) js.verdict = 'ok';   // المناقصات بنية مختلفة عن الوظائف
    if (!opts.force && (js.verdict === 'reject' || (!c.isJob && !image)) && !tinfo) {
      if ((!image || capt) && this.addLearn(post, text, url, posted, now)) return 'learn';
    }
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

  learnTags(title, howto, prov, text) {
    // ملفات المكتبات: القسم + العنوان أدق من النص؛ وتنظيف كلمات تُربك تصنيف الوظائف («المساحات الآمنة» ليست مساحة/هندسة)
    const src = howto ? [title, howto] : [title, text && String(text).slice(0, 800)];
    const nt = norm(src.join(' ')).replace(/(ال)?مساح(ات|ه)( (ال)?(صديقه|امنه|امن|تعلم|تعليميه))?/g, ' ').replace(/(^|\s)(ل|و|ب)?(ال)?انشاء(\s|$)/g, ' ');
    const specs = SPECS.filter(s => s.id !== 'training' && s.re.test(nt)).map(s => s.id).join(',');
    return { specs: specs ? ',' + specs + ',' : '', hay: nrm([title, howto, prov, String(text || '').slice(0, 700)].join(' ')) };
  }

  /** تعلّم مخصّص: فقط ما يطابق اهتمامات الشخص (اختصاصات + كلمات من سيرته) أو بحثه. */
  learnFind(req, b) {
    const t = bearer(req);
    const ses = t && this.one(`SELECT email FROM sessions WHERE token=?`, t);
    const acc = ses && this.one(`SELECT role FROM accounts WHERE email=?`, ses.email);
    const user = !!acc, admin = !!acc && acc.role === 'admin';
    const specs = new Set((Array.isArray(b.specs) ? b.specs : []).map(String).slice(0, 40));
    const terms = [...new Set((Array.isArray(b.terms) ? b.terms : []).map(x => String(x).trim()).filter(x => nrm(x).length >= 2))].slice(0, 60);
    const qw = nrm(String(b.q || '')).split(/\s+/).filter(w => w.length >= 2).slice(0, 6);
    const all = admin && !!b.all;
    const now = Date.now(), okDl = new Date(now - DAY).toISOString().slice(0, 10);
    const rows = this.q(`SELECT id,title,provider,lang,free,cert,deadline,link,url,text,posted,ftype,fsize,howto,specs,hay,reports,hidden FROM learn
      WHERE ${all ? '' : 'hidden=0 AND '}created > ? AND (deadline IS NULL OR deadline='' OR deadline >= ?)`, now - 400 * DAY, okDl);
    const names = LABELS.specs || {};
    const out = [];
    for (const r of rows) {
      const hay = r.hay || '';
      let score = 0;
      const why = [];
      if (qw.length) {
        if (!qw.every(w => hay.includes(w))) continue;
        score = 10;
      } else if (!all) {
        for (const sp of String(r.specs || '').split(',')) if (sp && specs.has(sp)) { score += 3; if (why.length < 2) why.push(names[sp] || sp); }
        for (const tm of terms) if (aliasHit(hay, tm)) { score += 2; if (why.length < 2 && !why.includes(tm)) why.push(tm); if (score > 12) break; }
        if (!score) continue;
      }
      if (r.ftype) score += 0.5;
      out.push([score, r, why]);
    }
    out.sort((a, b2) => b2[0] - a[0] || (b2[1].posted || 0) - (a[1].posted || 0));
    const cap = all ? 3000 : !user ? 10 : qw.length ? 150 : 120;
    return json({
      now, guest: !user, admin, total: out.length, locked: user ? 0 : Math.max(0, out.length - 10),
      items: out.slice(0, cap).map(([sc, r, why]) => ({
        id: r.id, t: r.title, p: r.provider, l: r.lang, f: r.free, c: r.cert, d: r.deadline || '', a: r.link || r.url, u: r.url,
        x: String(r.text || '').slice(0, 220), d0: r.posted, ft: r.ftype || '', fs: r.fsize || '',
        h: String(r.link || '').startsWith('https://t.me/wazifati_lib') ? '' : (r.howto || ''),   // ملف صار في القناة: لا حاجة لطريق البوت
        w: why.join('، '), hid: r.hidden ? 1 : 0, rp: r.reports || 0,
      })),
    });
  }

  learnToJob(id) {
    const r = this.one(`SELECT * FROM learn WHERE id=?`, id);
    if (!r) throw new HttpErr(404, 'غير موجود');
    this.run(`DELETE FROM seen WHERE url=?`, r.url);
    const res = this.addJob({ url: r.url, text: r.text, date: r.posted, links: r.link ? [r.link] : [], srcName: r.provider }, { force: true });
    this.run(`UPDATE learn SET hidden=1 WHERE id=?`, id);
    return json({ ok: true, res });
  }

  addLearn(post, text, url, posted, now, lenient = false) {
    const f = post.file && post.file.name ? post.file : null;
    let li = learnInfo(text, post.links || []);
    if (f && lenient) li = li || { free: 1, lang: /[\u0600-\u06FF]/.test(f.name + text) ? 'ar' : 'en', cert: 0, link: '' };
    if (!li && lenient) {
      const links = (post.links || []).concat(String(text).match(/https?:\/\/[^\s<>"'()\]]+/g) || []);
      if (!links.length || text.length < 25) return false;
      const ar = (text.match(/[\u0600-\u06FF]/g) || []).length, la = (text.match(/[A-Za-z]/g) || []).length;
      li = { free: /(مجان|free\b|بدون رسوم)/i.test(text) ? 1 : 0, lang: ar >= la ? 'ar' : 'en', cert: /(شهاد|certificate)/i.test(text) ? 1 : 0, link: links[0] };
      if (/freecodecamp\.org|edlibre\.com/i.test(li.link)) li.free = 1;
    }
    if (!li) return false;
    let title = String(post.title || (f ? f.name.replace(/\.[a-z0-9]{2,4}$/i, '').replace(/[_]+/g, ' ') : '')).trim();
    if (!title) title = (text.split('\n').map(x => x.trim()).find(x => x.length > 6) || text).replace(/^[^\p{L}\p{N}]+/u, '').slice(0, 120);
    const prov = String(post.srcName || post.src || '').slice(0, 60);
    const k1 = (f ? 'F:' + String(f.size || '') : 'L:') + nrm(title).replace(/[^\p{L}\p{N}]+/gu, '').slice(0, 60);
    this.markSeen(url);
    if (this.one(`SELECT 1 FROM learn WHERE k1=? OR (? AND ?<>'' AND link=?)`, k1, f ? 0 : 1, li.link, li.link)) return true;
    const t = nrm(title + ' ' + text);
    const skills = SEED_SKILLS.filter(([, , , , al]) => al.some(a => aliasHit(t, a))).map(x => x[0]).join(',');
    const dl = classify(text, { now: new Date() }).deadline || '';
    const tg = this.learnTags(title, post.howto, prov, text);
    this.run(`INSERT INTO learn(k1,url,link,title,provider,lang,free,cert,deadline,skills,text,posted,created,src,ftype,fsize,howto,specs,hay) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
      k1, url, f ? (post.open || url) : li.link, title, prov, li.lang, li.free, li.cert, dl, skills, text.slice(0, 1500), posted, now, prov,
      f ? (String(f.name).split('.').pop() || 'file').toLowerCase().slice(0, 8) : null, f ? String(f.size || '').slice(0, 20) : null, post.howto ? String(post.howto).slice(0, 200) : null, tg.specs, tg.hay);
    return true;
  }

  learnList(req) {
    const t = bearer(req);
    const user = !!(t && this.one(`SELECT 1 FROM sessions WHERE token=?`, t));
    const now = Date.now();
    const okDl = new Date(now - DAY).toISOString().slice(0, 10);
    const base = `hidden=0 AND created > ? AND (deadline IS NULL OR deadline='' OR deadline >= ?)`;
    const total = this.one(`SELECT COUNT(*) AS n FROM learn WHERE ${base}`, now - 400 * DAY, okDl).n;
    const rows = this.q(`SELECT id,title,provider,lang,free,cert,deadline,skills,link,url,text,posted,ftype,fsize,howto FROM learn WHERE ${base} ORDER BY posted DESC LIMIT ?`, now - 400 * DAY, okDl, user ? 3000 : 10);
    return json({ now, guest: !user, locked: user ? 0 : Math.max(0, total - 10), items: rows.map(r => ({ id: r.id, t: r.title, p: r.provider, l: r.lang, f: r.free, c: r.cert, d: r.deadline || '', s: r.skills || '', a: r.link || r.url, u: r.url, x: (r.text || '').slice(0, 600), d0: r.posted, ft: r.ftype || '', fs: r.fsize || '', h: r.howto || '' })) });
  }

  markSeen(url) { if (url) this.run(`INSERT OR REPLACE INTO seen(url,at) VALUES(?,?)`, url, Date.now()); }

  ingest(b) {
    const out = { added: 0, dup: 0, seen: 0, notjob: 0, old: 0, expired: 0, short: 0, learn: 0 };
    const sp = b.sourceId ? (this.one(`SELECT purpose FROM sources WHERE id=?`, b.sourceId) || {}).purpose : '';
    for (const post of (b.posts || []).slice(0, 60)) { const r = this.addJob(post, sp === 'learn' ? { purpose: 'learn' } : {}); out[r] = (out[r] || 0) + 1; }
    if (b.sourceId && out.added) this.run(`UPDATE sources SET total = total + ? WHERE id=?`, out.added, b.sourceId);
    if (b.sourceId && out.learn) this.run(`UPDATE sources SET learn_total = COALESCE(learn_total,0) + ? WHERE id=?`, out.learn, b.sourceId);
    if (b.sourceId && out.notjob) this.run(`UPDATE sources SET rejected = COALESCE(rejected,0) + ? WHERE id=?`, out.notjob, b.sourceId);
    this.cleanup();
    if (out.added) this.ctx.waitUntil(this.chanFlush());   // النشر الفوري في القناة
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
    this.run(`DELETE FROM learn WHERE created < ? AND ftype IS NULL`, Date.now() - 150 * DAY);
    this.run(`DELETE FROM learn WHERE created < ? AND ftype IS NOT NULL`, Date.now() - 420 * DAY);
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
    const title = String(b.title || '').trim(), org = b.org == null ? null : String(b.org).trim();
    if (title && (title.length < 3 || title.length > 140)) throw new HttpErr(400, 'العنوان من 3 إلى 140 حرفاً');
    if (org && org.length > 80) throw new HttpErr(400, 'اسم الجهة طويل (80 حرفاً كحد أقصى)');
    if (me.role === 'admin') {
      this.applyFix(id, specs, govs, sector);
      if (title || org != null) this.editJob(id, title, org, me.email, null);
      return json({ ok: true, applied: true });
    }
    if (title || org != null) {
      const cur = this.one(`SELECT title, org FROM jobs WHERE id=?`, id);
      if ((!title || title === cur.title) && (org == null || org === (cur.org || ''))) throw new HttpErr(400, 'لم تغيّر شيئاً');
    }
    this.run(`INSERT INTO fixes(job_id,email,specs,govs,sector,note,status,created,title,org) VALUES(?,?,?,?,?,?,?,?,?,?)`, id, me.email, specs, govs, sector, String(b.note || '').slice(0, 300), 'pending', Date.now(), title || null, org);
    return json({ ok: true, applied: false });
  }

  applyFix(id, specs, govs, sector) {
    const j = this.one(`SELECT specs, govs, sector FROM jobs WHERE id=?`, id);
    if (!j) return;
    this.run(`UPDATE jobs SET specs=?, govs=?, sector=?, updated=? WHERE id=?`, specs || j.specs, govs || j.govs, sector || j.sector, Date.now(), id);
  }

  /** تعديل عنوان/جهة الإعلان مع حفظ الأصل. org=null يعني «بلا تغيير». */
  editJob(id, title, org, by, via) {
    const j = this.one(`SELECT title, org FROM jobs WHERE id=?`, id);
    if (!j) throw new HttpErr(404, 'الإعلان غير موجود');
    title = String(title || '').trim();
    if (title && (title.length < 3 || title.length > 140)) throw new HttpErr(400, 'العنوان من 3 إلى 140 حرفاً');
    if (org != null) { org = String(org).trim(); if (org.length > 80) throw new HttpErr(400, 'اسم الجهة طويل (80 حرفاً كحد أقصى)'); }
    const nt = title || j.title, no = org == null ? (j.org || '') : org;
    if (nt === j.title && no === (j.org || '')) return false;
    const now = Date.now();
    this.run(`UPDATE jobs SET title=?, org=?, title_orig=COALESCE(title_orig, ?), org_orig=COALESCE(org_orig, ?), edited_by=?, edit_via=?, edited_at=?, edit_reviewed=?, updated=? WHERE id=?`,
      nt, no, j.title, j.org || '', by, via, now, by === FIRST_ADMIN ? 1 : 0, now, id);
    return true;
  }

  restoreEdit(id) {
    const j = this.one(`SELECT title_orig, org_orig, edited_at FROM jobs WHERE id=?`, id);
    if (!j || !j.edited_at) throw new HttpErr(400, 'لا يوجد تعديل لاسترجاعه');
    this.run(`UPDATE jobs SET title=COALESCE(title_orig, title), org=COALESCE(org_orig, org), title_orig=NULL, org_orig=NULL, edited_by=NULL, edit_via=NULL, edited_at=0, edit_reviewed=1, updated=? WHERE id=?`, Date.now(), id);
    return json({ ok: true });
  }

  decideFix(me, b) {
    const f = this.one(`SELECT * FROM fixes WHERE id=?`, +b.id);
    if (!f) throw new HttpErr(404, 'غير موجود');
    if (b.ok) {
      this.applyFix(f.job_id, b.specs != null ? cleanCsv(b.specs, LABELS.specs) : f.specs, b.govs != null ? cleanCsv(b.govs, LABELS.govs) : f.govs, f.sector);
      if (f.title || f.org != null) this.editJob(f.job_id, f.title || '', f.org, me.email, f.email);
    }
    this.run(`UPDATE fixes SET status=? WHERE id=?`, b.ok ? 'done' : 'rejected', f.id);
    return json({ ok: true });
  }

  // ===== المصادر =====
  parseSource(raw) {
    let v = String(raw || '').trim(), kind = 'site', m;
    if ((m = v.match(/^(?:https?:\/\/)?(?:www\.)?t\.me\/c\/(\d{5,})/i))) return { kind: 'tgc', value: m[1], nkey: 'tgc:' + m[1] };
    if ((m = v.match(/^(?:https?:\/\/)?(?:www\.)?(?:t\.me|telegram\.me)\/(?:\+|joinchat\/)([\w-]{6,})/i))) return { kind: 'tgp', value: 'https://t.me/+' + m[1], nkey: 'tgp:' + m[1] };
    if ((m = v.match(/^(?:https?:\/\/)?(?:www\.)?(?:t\.me|telegram\.me)\/(?:s\/)?([A-Za-z0-9_]{4,})/i)) || (m = v.match(/^@?([A-Za-z][A-Za-z0-9_]{3,})$/))) { kind = 'tg'; v = m[1]; }
    if (kind === 'tg' && /bot$/i.test(v)) kind = 'tgbot';
    if (kind.startsWith('tg')) { }
    else if (/lnkd\.in|bit\.ly|tinyurl|t\.co\//i.test(v)) kind = 'link';
    else if (/facebook\.com|fb\.com|fb\.me|linkedin\.com|instagram\.com/i.test(v)) kind = 'manual';
    else if (/(\/feed\/?$|\/rss|\.rss$|rss\.xml|atom\.xml|\/feeds?\/)/i.test(v)) kind = 'rss';
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
    const purpose = b.purpose === 'learn' ? 'learn' : 'jobs';
    this.run(`INSERT INTO sources(kind,value,name,added_by,orig,nkey,pending,enabled,created,purpose) VALUES(?,?,?,?,?,?,?,?,?,?)`,
      s.kind, s.value, name, me.email, raw, s.nkey, isAdmin ? 0 : 1, isAdmin ? 1 : 0, Date.now(), purpose);
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
    if (b.state != null) this.run(`UPDATE sources SET state=? WHERE id=?`, String(b.state).slice(0, 20000), id);
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
    const base = `enabled=1 AND pending=0 AND kind<>'manual' AND COALESCE(last_err,'') NOT LIKE '⏸%'`;
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
    return this.q(`SELECT a.email, a.name, a.photo, a.role, a.status, a.created, a.last_seen, a.last_sync, a.model, a.ver, a.cv_updated, a.prefs,
      (SELECT COUNT(*) FROM sources s WHERE s.added_by=a.email) AS contrib,
      (SELECT COUNT(*) FROM jobs j WHERE j.shared_by=a.email) AS shares
      FROM accounts a ORDER BY a.created DESC`).map(a => {
      let apps = 0; try { apps = Object.keys((JSON.parse(a.prefs || '{}').apps) || {}).length; } catch (_) { /* */ }
      const { prefs, ...o } = a;
      return { ...o, apps, via: /@telegram\.user$/.test(a.email) ? 'tg' : 'google', cv: a.cv_updated ? 1 : 0 };
    });
  }

  guestStats() {
    const n = (sql, ...a) => (this.one(sql, ...a) || {}).n || 0, wk = Date.now() - 7 * DAY;
    return {
      total: n(`SELECT COUNT(*) n FROM installs WHERE mode='guest'`), active7: n(`SELECT COUNT(*) n FROM installs WHERE mode='guest' AND last_seen>?`, wk),
      new7: n(`SELECT COUNT(*) n FROM installs WHERE mode='guest' AND first_seen>?`, wk),
      vers: this.q(`SELECT app_ver v, COUNT(*) n FROM installs WHERE mode='guest' GROUP BY app_ver ORDER BY n DESC LIMIT 8`),
      models: this.q(`SELECT model v, COUNT(*) n FROM installs WHERE mode='guest' GROUP BY model ORDER BY n DESC LIMIT 8`),
    };
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
      edits: this.one(`SELECT COUNT(*) AS n FROM jobs WHERE edited_at > 0 AND edit_reviewed=0`).n,
      reports: this.one(`SELECT COUNT(*) AS n FROM client_reports WHERE resolved=0`).n,
      chanReview: (this.one(`SELECT COUNT(*) n FROM chan_review WHERE state=0`) || {}).n || 0,
      newReports: this.one(`SELECT COUNT(*) AS n FROM client_reports WHERE resolved=0 AND last_seen > ?`, since).n,
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
      installs: {
        total: this.one(`SELECT COUNT(*) AS n FROM installs`).n,
        registered: this.one(`SELECT COUNT(*) AS n FROM installs WHERE mode='registered'`).n,
        guest: this.one(`SELECT COUNT(*) AS n FROM installs WHERE mode='guest'`).n,
        active7: this.one(`SELECT COUNT(*) AS n FROM installs WHERE last_seen > ?`, now - 7 * DAY).n,
        rooted: this.one(`SELECT COUNT(*) AS n FROM installs WHERE rooted=1`).n,
        topModels: this.q(`SELECT model, COUNT(*) AS n FROM installs WHERE model<>'' GROUP BY model ORDER BY n DESC LIMIT 10`),
        brands: this.installBrands(),
        appVers: this.q(`SELECT app_ver AS v, COUNT(*) AS n FROM installs WHERE app_ver<>'' GROUP BY app_ver ORDER BY n DESC LIMIT 15`),
        new1: this.one(`SELECT COUNT(*) AS n FROM installs WHERE first_seen > ?`, now - DAY).n,
        new7: this.one(`SELECT COUNT(*) AS n FROM installs WHERE first_seen > ?`, now - 7 * DAY).n,
        daily: this.q(`SELECT CAST((first_seen + 3*3600000) / 86400000 AS INTEGER) AS d, COUNT(*) AS n FROM installs WHERE first_seen > ? GROUP BY d ORDER BY d`, now - 30 * DAY),
        legacy30: this.one(`SELECT COUNT(*) AS n FROM legacy_seen WHERE last > ?`, now - 30 * DAY).n,
        androidVers: this.q(`SELECT android_ver AS v, COUNT(*) AS n FROM installs WHERE android_ver<>'' GROUP BY android_ver ORDER BY n DESC LIMIT 8`),
      },
      deletions: {
        total: this.one(`SELECT COUNT(*) AS n FROM deletion_reasons`).n,
        byReason: this.q(`SELECT reason, COUNT(*) AS n FROM deletion_reasons GROUP BY reason ORDER BY n DESC`),
      },
    };
  }

  // ===== مفاتيح التشغيل عن بُعد (2.4) =====
  flagsObj() {
    const out = { ...DEFAULT_FLAGS };
    for (const r of this.q(`SELECT k, enabled FROM feature_flags`)) {
      if (Object.prototype.hasOwnProperty.call(DEFAULT_FLAGS, r.k)) out[r.k] = !!r.enabled;
    }
    return out;
  }

  setFeature(b) {
    const k = String(b.key || '').trim();
    if (!Object.prototype.hasOwnProperty.call(DEFAULT_FLAGS, k)) throw new HttpErr(400, 'مفتاح غير معروف');
    this.run(`INSERT OR REPLACE INTO feature_flags(k,enabled,updated) VALUES(?,?,?)`, k, b.enabled ? 1 : 0, Date.now());
    return json({ ok: true, flags: this.flagsObj() });
  }

  // ===== تقارير الأخطاء وفشل الدخول (2.4) =====
  clientReport(b) {
    const kind = b.kind === 'auth_fail' ? 'auth_fail' : 'crash';
    const ver = String(b.ver || '').slice(0, 24);
    const android = String(b.android || '').slice(0, 24);
    const model = String(b.model || '').slice(0, 80);
    const rooted = b.rooted ? 1 : 0;
    const code = String(b.code || '').slice(0, 60);
    const clean = s => String(s || '').replace(/[\w.+-]+@[\w-]+\.[\w.-]+/g, '[email]').replace(/Bearer\s+\S+/gi, 'Bearer [redacted]');
    const msg = clean(b.msg).slice(0, 400);
    const trace = clean(b.trace).slice(0, 2500);
    const first = (msg || trace.split('\n')[0] || '').slice(0, 100);
    const sig = `${kind}|${ver}|${code}|${first}`;
    const now = Date.now();
    const ex = this.one(`SELECT id, notified FROM client_reports WHERE sig=?`, sig);
    if (ex) {
      this.run(`UPDATE client_reports SET count=count+1, last_seen=?, model=?, android_ver=?, rooted=?, resolved=0 WHERE id=?`, now, model, android, rooted, ex.id);
    } else {
      this.run(`INSERT INTO client_reports(kind,sig,app_ver,android_ver,model,rooted,err_code,msg,trace,count,first_seen,last_seen,resolved) VALUES(?,?,?,?,?,?,?,?,?,1,?,?,0)`,
        kind, sig, ver, android, model, rooted, code, msg, trace, now, now);
    }
    const kb = this.kbFor(`${kind} ${code} ${msg} ${trace}`);
    // 3.0.3: الانهيارات تصل للبوت أيضاً — أول ظهور، ثم مرة كل 12 ساعة لنفس الخطأ
    const row = this.one(`SELECT id, notified, count FROM client_reports WHERE sig=?`, sig);
    if (kind === 'crash' && row && now - (row.notified || 0) > 12 * 3600e3 && this.env.TG_BOT_TOKEN && this.env.TG_CHAT_ID) {
      this.run(`UPDATE client_reports SET notified=? WHERE id=?`, now, row.id);
      const top = trace.split('\n').filter(x => x.trim()).slice(0, 4).join('\n');
      const txt = `💥 ${row.count > 1 ? 'تكرر' : 'انهيار جديد'} في وظيفتي (v${ver})${row.count > 1 ? ` ×${row.count}` : ''}\n📱 ${model || '?'} (Android ${android || '?'})${rooted ? ' 🔓 روت' : ''}\n🔎 ${code}: ${msg.slice(0, 200)}\n${top.slice(0, 600)}`
        + (kb ? `\n\n💡 معروف: ${kb.title}\n${kb.fix}` : '\n\n❓ خطأ غير معروف بعد — أضف حله من التطبيق: المزيد ← الأخطاء');
      this.ctx.waitUntil(this.notifyTg(txt));
    }
    if (kind === 'auth_fail' && this.env.TG_BOT_TOKEN && this.env.TG_CHAT_ID) {
      const txt = `⚠️ فشل تسجيل دخول في وظيفتي (v${ver})\n📱 ${model || 'غير معروف'} (Android ${android || '?'})${rooted ? '\n🔓 الجهاز عليه روت (Root)' : ''}\n❌ ${code}: ${msg}`;
      this.ctx.waitUntil(this.notifyTg(txt + (kb ? `\n💡 ${kb.title}: ${kb.fix}` : '')));
    }
    return json({ ok: true, tip: kb && kb.user_msg ? kb.user_msg : '' });
  }

  kbFor(text) {
    const t = String(text || '').toLowerCase();
    for (const k of this.q(`SELECT * FROM kb ORDER BY id`)) {
      const parts = String(k.match || '').split('|').map(x => x.trim().toLowerCase()).filter(Boolean);
      if (parts.some(x => t.includes(x))) return k;
    }
    return null;
  }

  /** حالة حساب القراءة الخاص في تيليغرام؛ تنبيه المطوّر عند التعطل (مرة كل 12 ساعة). */
  tgStatus(b) {
    const prev = JSON.parse((this.one(`SELECT v FROM meta WHERE k='tg_account'`) || {}).v || '{}');
    const now = Date.now();
    const st = { ok: !!b.ok, err: String(b.err || '').slice(0, 200), at: now, name: String(b.name || prev.name || '').slice(0, 60), alerted: prev.alerted || 0 };
    if (!st.ok && b.alert && this.env.TG_BOT_TOKEN && this.env.TG_CHAT_ID && now - (prev.alerted || 0) > 12 * 3600e3) {
      st.alerted = now;
      this.ctx.waitUntil(this.notifyTg('⚠️ حساب القراءة في وظيفتي متوقف\n' + st.err + '\nأعد تسجيل الدخول من GitHub: Actions ← «تسجيل دخول حساب تيليغرام»'));
    }
    if (st.ok) st.alerted = 0;
    this.run(`INSERT OR REPLACE INTO meta(k,v) VALUES('tg_account',?)`, JSON.stringify(st));
    return json({ ok: true });
  }

  /** الملخص اليومي للمطوّر على تيليغرام (مرة في اليوم بتوقيت دمشق). */
  digest(force) {
    const day = this.dayDm();
    const done = (this.one(`SELECT v FROM meta WHERE k='digest_day'`) || {}).v;
    if (done === day && !force) return json({ skip: true });
    const n = (sql, ...a) => (this.one(sql, ...a) || {}).n || 0;
    const pendingSrc = n(`SELECT COUNT(*) n FROM sources WHERE pending=1`);
    const failing = this.q(`SELECT name,value FROM sources WHERE enabled=1 AND pending=0 AND kind<>'manual' AND fail_since IS NOT NULL AND fail_since>0 AND COALESCE(last_err,'') NOT LIKE '⏸%' LIMIT 5`);
    const autoOff = this.q(`SELECT name,value FROM sources WHERE auto_off=1 AND enabled=0 LIMIT 5`);
    const tg = JSON.parse((this.one(`SELECT v FROM meta WHERE k='tg_account'`) || {}).v || 'null');
    const pend = n(`SELECT COUNT(*) n FROM chan_review WHERE state=0`);
    const L = [`📊 ملخص وظيفتي — ${day}`, '', this.summaryText(1), '', this.reachText(1), '',
      pend ? `⏳ بانتظار مراجعتك للقناة: ${pend}` : '',
      pendingSrc ? `📨 مصادر مقترحة بانتظار موافقتك: ${pendingSrc}` : '',
      tg ? (tg.ok ? '👤 حساب القراءة: متصل' : `⚠️ حساب القراءة متوقف: ${tg.err}`) : '',
      failing.length ? '❌ مصادر تفشل: ' + failing.map(f => f.name || f.value).join('، ') : '',
      autoOff.length ? '⏸ أُوقفت تلقائياً: ' + autoOff.map(f => f.name || f.value).join('، ') : '',
    ].filter(x => x !== '');
    this.run(`INSERT OR REPLACE INTO meta(k,v) VALUES('digest_day',?)`, day);
    const text = L.join('\n').replace(/\n{3,}/g, '\n\n');
    if (this.env.TG_BOT_TOKEN && this.env.TG_CHAT_ID) this.ctx.waitUntil(this.tgApi('sendMessage', { chat_id: this.env.TG_CHAT_ID, text: text.slice(0, 4000), reply_markup: { inline_keyboard: [[{ text: '🛠 لوحة التحكم', callback_data: 'p' }]] } }));
    return json({ ok: true, text });
  }

  // ===== قناة الوظائف على تيليغرام (3.1.2) =====
  chanCfg() {
    const d = { on: 1, chat: '@wazifati_jobs', n: 8, times: [9, 18], tenders: 1, mode: 'all', cap: 150 };
    try { return { ...d, ...JSON.parse((this.one(`SELECT v FROM meta WHERE k='chan'`) || {}).v || '{}') }; } catch (_) { return d; }
  }

  chanSet(b) {
    const c = this.chanCfg();
    if (b.on != null) c.on = b.on ? 1 : 0;
    if (b.tenders != null) c.tenders = b.tenders ? 1 : 0;
    if (b.n != null) c.n = Math.max(1, Math.min(15, +b.n || 8));
    if (b.cap != null) c.cap = Math.max(5, Math.min(400, +b.cap || 150));
    if (b.mode != null) c.mode = b.mode === 'best' ? 'best' : 'all';
    if (b.times != null) {
      const t = [...new Set(String(b.times).split(/[^\d]+/).map(Number).filter(x => x >= 0 && x <= 23))].sort((a, b) => a - b).slice(0, 6);
      if (t.length) c.times = t;
    }
    if (b.chat) c.chat = String(b.chat).trim().slice(0, 64);
    this.run(`INSERT OR REPLACE INTO meta(k,v) VALUES('chan',?)`, JSON.stringify(c));
    return { ok: true, cfg: c };
  }

  /** هل الفرصة لجمهور سوريا؟ (محافظة سورية أو «كل سوريا»؛ «عن بعد» فقط إن ذُكرت سوريا؛ لا دول أخرى) */
  chanSyria(r, lenient) {
    const g = String(r.govs || '').split(',').filter(Boolean);
    const t = norm(`${r.title || ''} ${r.org || ''} ${r.text || ''}`);
    if (/(الامارات|دبي|ابوظبي|ابو ظبي|السعوديه|الرياض|جده|قطر|الدوحه|الكويت|البحرين|سلطنه عمان|مسقط|تركيا|اسطنبول|غازي عنتاب|عنتاب|انطاكيا|العراق|بغداد|اربيل|كردستان العراق|لبنان|بيروت|الاردن|عمان الاردن|مصر|القاهره|ليبيا|المانيا|اوروبا|خارج القطر|خارج سوريا|\buae\b|dubai|saudi|qatar|kuwait|turkey|istanbul|gaziantep|iraq|erbil|lebanon|beirut|jordan|amman|egypt|germany)/.test(t)) return false;
    if (g.some(x => !['abroad', 'unknown', 'remote', 'all'].includes(x))) return true;
    if (g.includes('all')) return true;
    if (g.includes('remote')) return /(سوريا|سوريه|syria)/.test(t);
    return !!lenient && !g.includes('abroad');   // المناقصات: غالباً بلا محافظة
  }

  /** وسيلة تواصل شخصية (واتساب/هاتف/معرّف تيليغرام في سطر التقديم) ← تحتاج مراجعة قبل القناة */
  chanContact(text) {
    const T = String(text || '');
    const phones = T.match(/(?:\+|00)?963[\s-]?9\d(?:[\s-]?\d){7}|\b09\d(?:[\s-]?\d){7}\b|wa\.me\/\d+|(?:\+|00)\d{9,14}/g) || [];
    const handles = [];
    for (const line of T.split('\n')) if (/(تقديم|للتقديم|ارسال|إرسال|أرسل|ارسل|راسل|مراسل|تواصل|للاستفسار|واتس|whats|cv|سيره|سيرة|apply|contact)/i.test(line))
      for (const m of line.match(/(?:^|[^\w.])@[A-Za-z]\w{3,}\b|t\.me\/[A-Za-z]\w{3,}/g) || []) handles.push(m.replace(/^[^@t]+/, ''));
    const hasWa = /(واتس|whats ?app|wa\.me)/i.test(T);
    return (phones.length || handles.length) ? [...new Set([...phones, ...handles])].slice(0, 4).join(' ، ') + (hasWa ? ' (واتساب)' : '') : '';
  }

  /** مناقصة من «مصدر نظامي»: جهة واضحة + عنصر رسمي (رقم/تأمين/موعد/دفتر شروط/موقع أو إيميل رسمي) */
  /** جهة المناقصة: من التصنيف، أو من صيغة «تعلن/يعلن <الجهة> عن…» */
  tenderOrg(r) {
    const o = this.cleanText(this.unNorm(r.text, r.org)).trim();
    if (o) return o;
    const m = String(r.text || '').match(/(?:تعلن|يعلن|أعلنت|اعلنت)\s+([^\n،,:]{4,70}?)\s+(?:عن|عزمها|رغبتها)/);
    return m ? m[1].trim() : '';
  }

  tenderFormal(r) {
    const org = this.tenderOrg(r);
    if (org.length < 3) return false;
    const T = String(r.text || '');
    return !!(r.bond || r.deadline || /(مناقص[ةه] رقم|اعلان رقم|إعلان رقم|رقم المناقص|رقم الإعلان|رقم الاعلان|دفتر (ال)?شروط|استلام (ال)?(وثائق|دفاتر|دفتر)|تأمين|التأمين|https?:\/\/(?!t\.me)|[\w.+-]+@[\w-]+\.[\w.]+|tender no|rfq|rfp|itb)/i.test(T));
  }

  /** مفتاح الجهة لتجميع مناقصاتها (بعد توحيد الكتابة وحذف الكلمات العامة) */
  orgKey(org) {
    const stop = new Set(['ال', 'مؤسسه', 'الموسسه', 'موسسه', 'المؤسسه', 'شركه', 'الشركه', 'مديريه', 'المديريه', 'هييه', 'الهييه', 'مجلس', 'العامه', 'العام', 'السوريه', 'سوريه', 'في', 'لل', 'ل', 'و']);
    return norm(String(org || '')).replace(/[^\p{L}\p{N}\s]+/gu, ' ').split(/\s+/).filter(w => w && !stop.has(w)).join('').slice(0, 24);
  }

  /** المرشّحون للنشر (وظائف ومناقصات). freshDays: نافذة الجديد (3 عادةً، 7 في التعبئة).
   *  الحالات: 0 بانتظار، 1 موافق، 2 مرفوض، 3 مؤرشف (5 أيام بلا قرار). الموافق عليه يُنشر حتى 14 يوماً ما دام موعده قائماً. */
  chanCands(freshDays = 3) {
    const now = Date.now(), today = new Date(now + 3 * 3600e3).toISOString().slice(0, 10);
    const rows = this.q(`SELECT id,title,org,specs,govs,deadline,apply,src,url,text,created,posted,kind,bond,tcat FROM jobs
      WHERE hidden=0 AND (doubt=0 OR kind='tender') AND created>? AND (deadline IS NULL OR deadline='' OR deadline>=?)
      AND id NOT IN (SELECT job_id FROM chan_posted) ORDER BY id DESC LIMIT 800`, now - Math.max(14, freshDays) * DAY, today);
    const rev = {}; for (const x of this.q(`SELECT job_id, state FROM chan_review`)) rev[x.job_id] = x.state;
    const out = []; let added = 0;
    for (const r of rows) {
      const fresh = r.created > now - freshDays * DAY, tender = r.kind === 'tender';
      if (!this.chanSyria(r, tender ? 1 : 0)) continue;
      const fr = !tender && this.fraudy(r.text);
      const c = fr ? '⚠️ اشتباه احتيال: يطلب مالاً' + (this.chanContact(r.text) ? ' • ' + this.chanContact(r.text) : '') : this.chanContact(r.text);
      const needs = tender ? !this.tenderFormal(r) : !!c;
      if (needs) {
        if (rev[r.id] == null) { if (!fresh) continue; this.run(`INSERT OR IGNORE INTO chan_review(job_id,state,contact,at) VALUES(?,0,?,?)`, r.id, c || (tender ? 'مناقصة بلا جهة أو عنصر رسمي واضح' : ''), now); rev[r.id] = 0; added++; }
        if (rev[r.id] !== 1) continue;
        r.keepContact = 1;
      } else if (rev[r.id] === 2 || rev[r.id] === 3 || !fresh) continue;
      out.push(r);
    }
    return { out, added };
  }

  chanScore(r) { return (r.keepContact ? 4 : 0) + (r.apply ? 3 : 0) + (r.deadline ? 2 : 0) + (r.org ? 1 : 0) + (/other/.test(r.specs || 'other') ? 0 : 1) - (Date.now() - r.created) / DAY; }

  /** n=0 ← كل المرشّحين (مرتبين حسب الجودة) بلا حدود توازن */
  chanPick(n, rows) {
    rows = rows || this.chanCands().out.filter(r => r.kind !== 'tender');
    const seenT = new Set();
    const cand = rows.filter(r => { const k = norm(String(r.title || '')).slice(0, 40) + '|' + norm(String(r.org || '')).slice(0, 20); if (seenT.has(k)) return false; seenT.add(k); return true; })
      .sort((a, b) => this.chanScore(b) - this.chanScore(a));
    if (!n) return cand;
    const out = [], ps = {}, pg = {};
    for (const pass of [1, 2]) for (const r of cand) {
      if (out.length >= n) break;
      if (out.includes(r)) continue;
      const s = String(r.specs || 'other').split(',')[0], g = String(r.govs || 'unknown').split(',')[0];
      if (pass === 1 && ((ps[s] || 0) >= 2 || (pg[g] || 0) >= 3)) continue;
      ps[s] = (ps[s] || 0) + 1; pg[g] = (pg[g] || 0) + 1; out.push(r);
    }
    return out;
  }

  /** اسم المصدر يُذكر فقط إن كان موقعاً حقيقياً (لا قنوات/مجموعات/بوتات تيليغرام) */
  srcSite(r) {
    const u = String(r.url || '');
    if (!/^https?:\/\//i.test(u)) return '';
    try {
      const h = new URL(u).hostname.toLowerCase().replace(/^www\./, '');
      if (/(^|\.)(t\.me|telegram\.me|telegram\.org|telegram\.dog|facebook\.com|fb\.com|fb\.me|instagram\.com|whatsapp\.com|wa\.me|x\.com|twitter\.com)$/.test(h)) return '';
      return h;
    } catch (_) { return ''; }
  }

  /** يستعيد الكتابة الأصلية (ة، ئ، أ…) لعنوان/جهة استُخرجت من نص مُطبّع */
  unNorm(raw, x) {
    raw = String(raw || ''); x = String(x || '');
    if (!x || raw.includes(x)) return x;
    let n = ''; const idx = [];
    for (let i = 0; i < raw.length; i++) { const c = norm(raw[i]); if (c.length === 1) { n += c; idx.push(i); } }
    const k = norm(x), pos = n.indexOf(k);
    return pos < 0 || !k ? x : raw.slice(idx[pos], idx[pos + k.length - 1] + 1);
  }

  /** ينظّف النص من الترويج لقنوات أخرى؛ keep=1 يُبقي معرّفات سطر التقديم (بعد موافقة المراجع) */
  cleanText(text, keep) {
    return String(text || '').split('\n').map(line => {
      if (/(تابعونا|انضموا|اشتركوا|للمزيد من|قناتنا|رابط القناة|join us|follow us|subscribe)/i.test(line)) return '';
      const contactLine = keep && /(تقديم|ارسال|إرسال|أرسل|ارسل|راسل|مراسل|تواصل|للاستفسار|واتس|whats|cv|سيره|سيرة|apply|contact)/i.test(line);
      let x = line.replace(/https?:\/\/(t\.me|telegram\.me)\/\S+/gi, '').replace(/(^|\s)t\.me\/\S+/gi, '$1');
      if (!contactLine) x = x.replace(/(^|[^\w.])@[A-Za-z]\w{3,}\b/g, '$1');
      return x.replace(/[ \t]{2,}/g, ' ').trim();
    }).filter((l, i, a) => l || (a[i - 1] && a[i - 1] !== '')).join('\n').replace(/\n{3,}/g, '\n\n').trim();
  }

  chanTenders() {
    return this.chanCands().out.filter(r => r.kind === 'tender').slice(0, 12);
  }

  chanFmt() {
    const esc = x => String(x == null ? '' : x).replace(/[&<>]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]));
    const tag = x => '#' + String(x).replace(/[^\p{L}\p{N}]+/gu, '_').replace(/^_|_$/g, '');
    const stag = x => tag(String(x).split(/\s+و|،|\s+/)[0]);
    const lab = (map, v, skip) => String(v || '').split(',').filter(k => k && !skip.includes(k)).map(k => map[k]).filter(Boolean);
    return { esc, tag, stag, lab };
  }

  /** منشور مستقل لوظيفة واحدة */
  chanJobText(r) {
    const { esc, tag, stag, lab } = this.chanFmt();
    const clean = this.cleanText(r.text, r.keepContact);
    const title = esc(this.cleanText(this.unNorm(r.text, r.title)).slice(0, 120)) || 'فرصة عمل';
    const org = esc(this.cleanText(this.unNorm(r.text, r.org)).slice(0, 60));
    const govs = lab(LABELS.govs, r.govs, ['unknown']), specs = lab(LABELS.specs, r.specs, ['other']);
    const e = [`💼 <b>${title}</b>`];
    if (org) e.push(`🏢 ${org}`);
    const meta = [govs.length ? '📍 ' + govs.join('، ') : '', specs.length ? '🎓 ' + specs.slice(0, 2).join('، ') : ''].filter(Boolean).join('  ');
    if (meta) e.push(esc(meta));
    if (r.deadline) e.push(`⏳ آخر موعد: ${esc(r.deadline)}`);
    if (r.keepContact) { const c = this.chanContact(r.text); if (c) e.push(`📞 للتقديم: ${esc(c)}`); }
    // مقتطف قصير (3 أسطر تقريباً) من الوصف بلا تكرار العنوان
    const tn = norm(String(r.title || ''));
    const ex = clean.split('\n').map(x => x.trim()).filter(x => x.length > 3 && norm(x) !== tn).slice(0, 3).join('\n');
    if (ex) e.push('', esc(ex.length > 280 ? ex.slice(0, 277) + '…' : ex));
    const site = this.srcSite(r);
    if (site) e.push('', `🌐 المصدر: ${esc(site)}`);
    const tags = [...new Set([...lab(LABELS.govs, r.govs, ['unknown', 'all']).slice(0, 2).map(tag), ...specs.slice(0, 2).map(stag)])];
    if (tags.length) e.push('', tags.join(' '));
    e.push('', `⚠️ لا تدفع أي مبلغ مقابل وظيفة • <a href="${this.botLink('report_' + r.id)}">بلّغ عن احتيال</a>`);
    return e.join('\n').slice(0, 3800);
  }

  /** منشور مناقصة منفردة */
  chanTenderOne(r) {
    const { esc, tag, lab } = this.chanFmt();
    const title = esc(this.cleanText(this.unNorm(r.text, r.title)).slice(0, 140)) || 'مناقصة';
    const org = esc(this.tenderOrg(r).slice(0, 80));
    const govs = lab(LABELS.govs, r.govs, ['unknown']);
    const e = [`📑 <b>${title}</b>`];
    if (org) e.push(`🏢 ${org}`);
    if (r.tcat && LABELS.tcats[r.tcat]) e.push(`🗂 ${esc(LABELS.tcats[r.tcat])}`);
    if (r.bond) e.push(`💰 التأمين: ${esc(r.bond)}`);
    if (govs.length) e.push(`📍 ${esc(govs.join('، '))}`);
    if (r.deadline) e.push(`⏳ آخر موعد لتقديم العروض: ${esc(r.deadline)}`);
    const tn = norm(String(r.title || ''));
    const ex = this.cleanText(r.text, r.keepContact).split('\n').map(x => x.trim()).filter(x => x.length > 3 && norm(x) !== tn).slice(0, 4).join('\n');
    if (ex) e.push('', esc(ex.length > 360 ? ex.slice(0, 357) + '…' : ex));
    const site = this.srcSite(r);
    if (site) e.push('', `🌐 المصدر: ${esc(site)}`);
    e.push('', ['#مناقصات', ...lab(LABELS.govs, r.govs, ['unknown', 'all']).slice(0, 2).map(tag)].join(' '));
    e.push('', `<a href="${this.botLink('report_' + r.id)}">⚠️ بلّغ عن إعلان مشبوه</a>`);
    return e.join('\n').slice(0, 3800);
  }

  /** منشور مجمّع لعدة مناقصات من نفس الجهة خلال 24 ساعة */
  chanTenderGroup(rows, origin) {
    const { esc, tag, lab } = this.chanFmt();
    const org = esc(this.tenderOrg(rows[0]).slice(0, 80)) || 'جهة واحدة';
    const L = [`📑 <b>مناقصات ${org} (${rows.length})</b>`, ''];
    rows.forEach((r, i) => {
      const t = esc(this.cleanText(this.unNorm(r.text, r.title)).slice(0, 120)) || 'مناقصة';
      const bits = [r.deadline ? `⏳ ${esc(r.deadline)}` : '', r.bond ? `💰 ${esc(r.bond)}` : ''].filter(Boolean).join(' • ');
      L.push(`${i + 1}. <b>${t}</b>${bits ? '\n   ' + bits : ''}\n   📄 <a href="${origin}/j/${r.id}?c=ch">التفاصيل كاملة</a>`);
    });
    const govs = [...new Set(rows.flatMap(r => lab(LABELS.govs, r.govs, ['unknown', 'all'])))].slice(0, 2);
    L.push('', ['#مناقصات', ...govs.map(tag)].join(' '));
    return L.join('\n').slice(0, 4000);
  }

  /** منشور «مناقصات اليوم» (ملخص بروابط منشوراتنا) */
  chanTenderText(rows, origin) {
    const { esc, lab } = this.chanFmt();
    const cfg = this.chanCfg();
    const L = ['📑 <b>مناقصات اليوم</b>', ''];
    rows.forEach((r, i) => {
      const link = (r.msg_id && this.chanLink(cfg, r.msg_id)) || `${origin}/j/${r.id}?c=ch`;
      const org = esc(this.cleanText(this.unNorm(r.text, r.org)).slice(0, 50));
      const g = lab(LABELS.govs, r.govs, ['unknown']).slice(0, 1).join('');
      L.push(`${i + 1}. <a href="${link}">${esc(this.cleanText(this.unNorm(r.text, r.title)).slice(0, 90)) || 'مناقصة'}</a>${org ? ' — ' + org : ''}${r.deadline ? ' • ⏳' + esc(r.deadline) : ''}${g ? ' • 📍' + esc(g) : ''}`);
    });
    L.push('', `📲 كل المناقصات والفرص في تطبيق «وظيفتي» المجاني:\n${SITE}/download?from=ch`);
    return L.join('\n').slice(0, 4000);
  }

  // نص للمعاينة في التطبيق (بلا وسوم HTML)
  chanText(rows) {
    return rows.map(r => this.chanJobText(r)).join('\n\n— — —\n\n').replace(/<[^>]+>/g, '').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
  }

  async chanFail(cfg, r) {
    const err = String((r && r.description) || 'فشل الإرسال').slice(0, 200);
    this.run(`INSERT OR REPLACE INTO meta(k,v) VALUES('chan_err',?)`, JSON.stringify({ err, at: Date.now() }));
    this.ctx.waitUntil(this.notifyAdmins('⚠️ تعذّر النشر في قناة الوظائف ' + cfg.chat + ': ' + err));
    return { ok: false, err };
  }

  chanDone(cfg, n) {
    const at = Date.now();
    this.run(`DELETE FROM meta WHERE k='chan_err'`);
    if (!this.one(`SELECT v FROM meta WHERE k='chan_first'`)) {
      this.run(`INSERT OR REPLACE INTO meta(k,v) VALUES('chan_first',?)`, String(at));
      if (this.env.TG_CHAT_ID) this.ctx.waitUntil(this.notifyTg(`✅ أول نشر في قناة الوظائف ${cfg.chat} تم بنجاح (${n}).`));
    }
    this.run(`DELETE FROM chan_posted WHERE at < ?`, at - 60 * DAY);
    this.run(`DELETE FROM chan_review WHERE state IN (2,3) AND at < ?`, at - 60 * DAY);
  }

  chanKb(r, origin, last) {
    const kb = [[{ text: '📄 التفاصيل كاملة وطريقة التقديم', url: `${origin}/j/${r.id}?c=ch` }], [{ text: '🔔 تريد فرص اختصاصك فقط؟ تصلك في الخاص', url: this.botLink('ch') }]];
    if (last) kb.push([{ text: '📲 حمّل تطبيق وظيفتي — فرص حسب اختصاصك', url: `${origin}/download?from=ch` }]);
    return { inline_keyboard: kb };
  }

  /** كل وظيفة بمنشور مستقل. silent=كلها صامتة (وضع «كل الفرص»)، وإلا الأول بإشعار والباقي صامت */
  async chanSendJobs(cfg, rows, origin, silent) {
    if (!this.env.TG_BOT_TOKEN) return { ok: false, err: 'TG_BOT_TOKEN غير مهيأ' };
    if (!rows.length) return { ok: true, empty: true };
    let n = 0;
    for (let i = 0; i < rows.length; i++) {
      const r = rows[i];
      const res = await this.tgApi('sendMessage', { chat_id: cfg.chat, text: this.chanJobText(r), parse_mode: 'HTML', disable_web_page_preview: true,
        disable_notification: silent || i > 0, reply_markup: this.chanKb(r, origin, !silent && i === rows.length - 1) });
      if (!res.ok) { if (!n) return this.chanFail(cfg, res); break; }
      this.run(`INSERT OR REPLACE INTO chan_posted(job_id,at,msg_id,skip,kind) VALUES(?,?,?,0,'job')`, r.id, Date.now(), res.result.message_id);
      this.chanMsg(res.result.message_id, 'job');
      this.evAdd('chan_post');
      n++;
    }
    this._cmap = null;
    this.chanDone(cfg, n + ' فرصة');
    return { ok: true, n };
  }

  chanLink(cfg, msgId) { return /^@/.test(cfg.chat) ? `https://t.me/${cfg.chat.slice(1)}/${msgId}` : ''; }

  /** «⭐ أبرز فرص اليوم»: أفضل n مما نُشر خلال 24 ساعة ولم يُبرز بعد — بإشعار */
  async chanHighlights(cfg, origin) {
    const ps = this.q(`SELECT p.job_id, p.msg_id, j.title, j.org, j.govs, j.specs, j.apply, j.deadline, j.created, j.text FROM chan_posted p JOIN jobs j ON j.id=p.job_id
      WHERE p.skip=0 AND p.msg_id>0 AND COALESCE(p.hl,0)=0 AND p.at>? AND COALESCE(j.kind,'job')='job' AND j.hidden=0`, Date.now() - DAY);
    if (!ps.length) return { ok: true, empty: true };
    const top = this.chanPick(cfg.n, ps.map(x => ({ ...x, id: x.job_id })));
    const { esc, lab } = this.chanFmt();
    const h = new Date(Date.now() + 3 * 3600e3).getUTCHours();
    const L = [`⭐ <b>أبرز فرص ${h < 14 ? 'الصباح' : 'المساء'}</b> — اختيار «وظيفتي»`, ''];
    top.forEach((r, i) => {
      const title = esc(this.cleanText(this.unNorm(r.text, r.title)).slice(0, 90)) || 'فرصة';
      const org = esc(this.cleanText(this.unNorm(r.text, r.org)).slice(0, 50));
      const g = lab(LABELS.govs, r.govs, ['unknown']).slice(0, 2).join('، ');
      const link = this.chanLink(cfg, r.msg_id) || `${origin}/j/${r.id}?c=ch`;
      L.push(`${i + 1}. <a href="${link}">${title}</a>${org ? ' — ' + org : ''}${g ? ' • 📍' + esc(g) : ''}`);
    });
    L.push('', `📌 كل الفرص الجديدة تُنشر هنا أولاً بأول. ابحث بوسم محافظتك مثل #دمشق أو اختصاصك مثل #هندسة.`);
    const res = await this.tgApi('sendMessage', { chat_id: cfg.chat, text: L.join('\n').slice(0, 4000), parse_mode: 'HTML', disable_web_page_preview: true,
      reply_markup: { inline_keyboard: [[{ text: '🔔 فرص اختصاصك ومحافظتك تصلك في الخاص', url: this.botLink('ch') }], [{ text: '📲 حمّل تطبيق وظيفتي', url: `${origin}/download?from=ch` }]] } });
    if (!res.ok) return this.chanFail(cfg, res);
    this.chanMsg(res.result.message_id, 'hl');
    for (const r of top) this.run(`UPDATE chan_posted SET hl=? WHERE job_id=?`, Date.now(), r.id);
    return { ok: true, n: top.length };
  }

  /** تحديث المنشور بدل تكراره: إن تغيّرت الفرصة (موعد/رابط/دمج مصدر جديد) بعد نشرها */
  async chanEdits(cfg, origin) {
    const rows = this.q(`SELECT j.*, p.msg_id, p.at pat FROM chan_posted p JOIN jobs j ON j.id=p.job_id
      WHERE p.skip=0 AND p.msg_id>0 AND COALESCE(j.kind,'job')='job' AND j.updated > MAX(p.at, COALESCE(p.ed,0)) + 60000 AND p.at > ? LIMIT 5`, Date.now() - 7 * DAY);
    let n = 0;
    for (const r of rows) {
      const rv = this.one(`SELECT state FROM chan_review WHERE job_id=?`, r.id);
      r.keepContact = rv && rv.state === 1 ? 1 : 0;
      const res = await this.tgApi('editMessageText', { chat_id: cfg.chat, message_id: r.msg_id, text: this.chanJobText(r), parse_mode: 'HTML', disable_web_page_preview: true, reply_markup: this.chanKb(r, origin, false) });
      this.run(`UPDATE chan_posted SET ed=? WHERE job_id=?`, Date.now(), r.id);
      if (res.ok) n++;
    }
    return n;
  }

  async chanSendTenders(cfg, rows, origin) {
    if (!this.env.TG_BOT_TOKEN) return { ok: false, err: 'TG_BOT_TOKEN غير مهيأ' };
    if (cfg.mode !== 'best') rows = this.q(`SELECT p.job_id id, MIN(p.msg_id) msg_id, j.title, j.org, j.text, j.deadline, j.govs FROM chan_posted p JOIN jobs j ON j.id=p.job_id
      WHERE p.kind='tender' AND p.skip=0 AND p.msg_id>0 AND p.at>? GROUP BY p.job_id ORDER BY p.at LIMIT 25`, Date.now() - DAY);
    if (!rows.length) return { ok: true, empty: true };
    const res = await this.tgApi('sendMessage', { chat_id: cfg.chat, text: this.chanTenderText(rows, origin), parse_mode: 'HTML', disable_web_page_preview: true });
    if (!res.ok) return this.chanFail(cfg, res);
    this.chanMsg(res.result.message_id, 'tsum');
    if (cfg.mode === 'best') for (const x of rows) this.run(`INSERT OR REPLACE INTO chan_posted(job_id,at,msg_id,skip,kind) VALUES(?,?,?,0,'tender')`, x.id, Date.now(), res.result.message_id);
    this.chanDone(cfg, rows.length + ' مناقصة');
    return { ok: true, n: rows.length };
  }

  chanMsg(id, kind) { try { this.run(`INSERT OR REPLACE INTO chan_msgs(msg_id,at,kind) VALUES(?,?,?)`, id, Date.now(), kind); } catch (_) { /* */ } }

  /** النشر الفوري: يفرغ طابور الفرص والمناقصات الجاهزة (أقدمها أولاً) ضمن حدود تيليغرام (18/دقيقة)؛
   *  إن بقي شيء يجدول نفسه بعد دقيقة (منبّه الـDurable Object). في وضع «التعبئة» نافذة 7 أيام وبلا حد يومي. */
  async chanFlush() {
    if (this._fl) return { busy: true };
    this._fl = 1;
    try {
      const cfg = this.chanCfg();
      if (!this.env.TG_BOT_TOKEN) return { off: true };
      if (!cfg.on || cfg.mode === 'best') return { off: true, subs: await this.subDrain(30) };
      let fill = null; try { fill = JSON.parse((this.one(`SELECT v FROM meta WHERE k='chan_fill'`) || {}).v || 'null'); } catch (_) { /* */ }
      if (fill && !fill.on) fill = null;
      const now = Date.now();
      const lastMin = (this.one(`SELECT COUNT(*) n FROM chan_msgs WHERE at>?`, now - 60e3) || {}).n || 0;
      let room = Math.max(0, Math.min(12, 18 - lastMin));   // ≤12 منشوراً لكل تشغيل لترك طلبات لإرسال المشتركين
      if (!fill) {
        const day = (this.one(`SELECT COUNT(*) n FROM chan_msgs WHERE at>? AND kind IN ('job','tender')`, now - DAY) || {}).n || 0;
        room = Math.min(room, Math.max(0, cfg.cap - day));
      }
      const all = this.chanCands(fill ? 7 : 3).out;
      // بلا تكرار للعنوان+الجهة، الأقدم أولاً
      const seen = new Set(), q = [];
      for (const r of all.sort((a, b) => a.created - b.created)) {
        const k = r.kind + '|' + norm(String(r.title || '')).slice(0, 40) + '|' + norm(String(r.org || '')).slice(0, 20);
        if (seen.has(k)) { this.run(`INSERT OR IGNORE INTO chan_posted(job_id,at,msg_id,skip,kind) VALUES(?,?,0,1,?)`, r.id, now, r.kind || 'job'); continue; }
        seen.add(k); q.push(r);
      }
      let n = 0, err = null;
      this._filling = !!fill;   // منشورات التعبئة القديمة لا تُرسل للمشتركين (فقط الجديد)
      for (const r of q.slice(0, room)) {
        const res = r.kind === 'tender' ? await this.chanPostTender(cfg, r, SITE) : await this.chanPostJob(cfg, r, SITE);
        if (!res.ok) { err = res; break; }
        n++;
        if (!fill && r.posted && r.posted > now - 2 * DAY && r.posted < now) { this.evAdd('speed', '', Math.round((now - r.posted) / 60e3)); this.evAdd('speed_n'); }
      }
      this._filling = false;
      if (err && !n) { await this.chanFail(cfg, err); return { ok: false, err: err.description }; }
      if (n) { this._cmap = null; this.chanDone(cfg, n + ' منشور'); }
      const left = q.length - n;
      if (fill) {
        fill.n = (fill.n || 0) + n;
        if (left <= 0) {
          fill.on = 0;
          this.ctx.waitUntil(this.notifyAdmins(`✅ امتلأت القناة: ${fill.n} منشور خلال ${Math.max(1, Math.round((Date.now() - fill.at) / 60e3))} دقيقة.`));
        }
        this.run(`INSERT OR REPLACE INTO meta(k,v) VALUES('chan_fill',?)`, JSON.stringify(fill));
      }
      const sd = await this.subDrain(Math.max(5, 32 - n));
      if ((left > 0 && (fill || err || lastMin + n >= 12)) || sd.left > 0) { try { await this.ctx.storage.setAlarm(Date.now() + (left > 0 ? 62e3 : 5e3)); } catch (_) { /* */ } }
      return { ok: true, n, left, fill: fill ? fill.n : undefined, subs: sd };
    } finally { this._fl = 0; }
  }

  async alarm() { try { await this.chanFlush(); } catch (_) { /* */ } }

  async chanPostJob(cfg, r, origin) {
    const res = await this.tgApi('sendMessage', { chat_id: cfg.chat, text: this.chanJobText(r), parse_mode: 'HTML', disable_web_page_preview: true,
      disable_notification: true, reply_markup: this.chanKb(r, origin, false) });
    if (!res.ok) return res;
    this.run(`INSERT OR REPLACE INTO chan_posted(job_id,at,msg_id,skip,kind) VALUES(?,?,?,0,'job')`, r.id, Date.now(), res.result.message_id);
    this.chanMsg(res.result.message_id, 'job');
    this.evAdd('chan_post');
    if (!this._filling) this.subEnqueue(r);
    return res;
  }

  /** مناقصة: منفردة، أو تُضاف لمنشور نفس الجهة خلال 24 ساعة (تعديل بدل منشور جديد) */
  async chanPostTender(cfg, r, origin) {
    const okey = this.orgKey(this.tenderOrg(r));
    const grp = okey ? this.one(`SELECT msg_id FROM chan_posted WHERE kind='tender' AND okey=? AND skip=0 AND msg_id>0 AND at>? ORDER BY at DESC LIMIT 1`, okey, Date.now() - DAY) : null;
    if (grp) {
      const rows = this.q(`SELECT j.* FROM chan_posted p JOIN jobs j ON j.id=p.job_id WHERE p.msg_id=? ORDER BY p.at`, grp.msg_id);
      rows.push(r);
      const ed = await this.tgApi('editMessageText', { chat_id: cfg.chat, message_id: grp.msg_id, text: this.chanTenderGroup(rows, origin), parse_mode: 'HTML', disable_web_page_preview: true,
        reply_markup: { inline_keyboard: [[{ text: '📲 حمّل تطبيق وظيفتي', url: `${origin}/download?from=ch` }]] } });
      if (ed.ok) {
        this.run(`INSERT OR REPLACE INTO chan_posted(job_id,at,msg_id,skip,kind,okey) VALUES(?,?,?,0,'tender',?)`, r.id, Date.now(), grp.msg_id, okey);
        this.evAdd('chan_post');
        if (!this._filling) this.subEnqueue(r);
        return { ok: true };
      }
    }
    const res = await this.tgApi('sendMessage', { chat_id: cfg.chat, text: this.chanTenderOne(r), parse_mode: 'HTML', disable_web_page_preview: true, disable_notification: true,
      reply_markup: { inline_keyboard: [[{ text: '📄 التفاصيل كاملة والوثائق', url: `${origin}/j/${r.id}?c=ch` }], [{ text: '🔔 تصلك المناقصات في الخاص', url: this.botLink('ch') }]] } });
    if (!res.ok) return res;
    this.run(`INSERT OR REPLACE INTO chan_posted(job_id,at,msg_id,skip,kind,okey) VALUES(?,?,?,0,'tender',?)`, r.id, Date.now(), res.result.message_id, okey);
    this.chanMsg(res.result.message_id, 'tender');
    this.evAdd('chan_post');
    if (!this._filling) this.subEnqueue(r);
    return res;
  }

  /** 🧹 مسح القناة وإعادة النشر: يحذف منشورات البوت المسجّلة فقط، يصفّر السجل، يعيد تثبيت الملف ويبدأ التعبئة */
  async chanReset(origin) {
    const cfg = this.chanCfg();
    const ids = new Set();
    for (const x of this.q(`SELECT msg_id FROM chan_msgs`)) ids.add(x.msg_id);
    for (const x of this.q(`SELECT DISTINCT msg_id FROM chan_posted WHERE msg_id>0`)) ids.add(x.msg_id);
    try { const p = JSON.parse((this.one(`SELECT v FROM meta WHERE k='apk_pin'`) || {}).v || '{}'); if (p.mid) ids.add(p.mid); } catch (_) { /* */ }
    const arr = [...ids].filter(Boolean).sort((a, b) => a - b);
    let del = 0;
    for (let i = 0; i < arr.length; i += 100) {
      const r = await this.tgApi('deleteMessages', { chat_id: cfg.chat, message_ids: arr.slice(i, i + 100) });
      if (r.ok) del += arr.slice(i, i + 100).length;
      else for (const id of arr.slice(i, i + 100)) { const x = await this.tgApi('deleteMessage', { chat_id: cfg.chat, message_id: id }); if (x.ok) del++; }
    }
    this.run(`DELETE FROM chan_posted`);
    this.run(`DELETE FROM chan_msgs`);
    this.run(`DELETE FROM meta WHERE k IN ('apk_pin','chan_err')`);
    this._cmap = null;
    this.run(`INSERT OR REPLACE INTO meta(k,v) VALUES('chan_fill',?)`, JSON.stringify({ on: 1, at: Date.now(), n: 0 }));
    let pin = '';
    try { pin = await this.apkPin(cfg); } catch (e) { pin = String(e); }
    const fl = await this.chanFlush();
    return { ok: true, deleted: del, total: arr.length, pin, first: fl };
  }

  /** يُستدعى كل ساعة من الجالب، أو يدوياً من الأدمن («انشر الآن»: force='jobs'|'tenders'|'hl'). */
  async chanTick(origin, force) {
    const cfg = this.chanCfg();
    if (force === 'jobs') return json(cfg.mode === 'all' ? await this.chanFlush() : await this.chanSendJobs(cfg, this.chanPick(cfg.n), origin, false));
    if (force === 'reset') return json(await this.chanReset(origin));
    if (force === 'tenders') return json(await this.chanSendTenders(cfg, this.chanTenders(), origin));
    if (force === 'hl') return json(await this.chanHighlights(cfg, origin));
    const out = {};
    try { await this.ensureHook(origin); } catch (_) { /* */ }
    try { out.apk = await this.apkPin(cfg, origin); } catch (e) { out.apk = String(e).slice(0, 100); }
    try { await this.subsDaily(cfg); } catch (_) { /* */ }
    out.review = await this.reviewTick();
    out.daily = this.subDaily();
    if (out.daily) { try { await this.ctx.storage.setAlarm(Date.now() + 5e3); } catch (_) { /* */ } }
    if (!cfg.on) return json({ ...out, off: true });
    try { out.edits = await this.chanEdits(cfg, origin); } catch (_) { /* */ }
    const dm = new Date(Date.now() + 3 * 3600e3), day = dm.toISOString().slice(0, 10), h = dm.getUTCHours();
    if (cfg.mode === 'all') out.flush = await this.chanFlush();
    let st = {}; try { st = JSON.parse((this.one(`SELECT v FROM meta WHERE k='chan_slots'`) || {}).v || '{}'); } catch (_) { /* */ }
    if (st.day !== day) st = { day, done: [] };
    const due = cfg.times.filter(t => h >= t && !st.done.includes(t));
    if (!due.length) return json(out);
    st.done.push(...due);
    this.run(`INSERT OR REPLACE INTO meta(k,v) VALUES('chan_slots',?)`, JSON.stringify(st));
    if (cfg.mode === 'all') out.hl = await this.chanHighlights(cfg, origin);
    else out.best = await this.chanSendJobs(cfg, this.chanPick(cfg.n), origin, false);
    if (cfg.tenders && due.includes(Math.max(...cfg.times))) out.tenders = await this.chanSendTenders(cfg, this.chanTenders(), origin);
    return json(out);
  }

  /** المراجعة كل ساعة: كشف الجديد + تنبيه، أرشفة بعد 5 أيام، تذكير قبلها بيوم */
  async reviewTick() {
    const now = Date.now();
    const { added } = this.chanCands();
    const pend = () => (this.one(`SELECT COUNT(*) n FROM chan_review WHERE state=0`) || {}).n || 0;
    if (added) this.ctx.waitUntil(this.notifyAdmins(`⏳ ${added} فرصة جديدة فيها واتساب/هاتف/معرّف تيليغرام بانتظار المراجعة قبل النشر في القناة (المجموع ${pend()}).`, [[{ text: '⏳ راجعها الآن', callback_data: 'r:0' }]]));
    const soon = this.q(`SELECT job_id FROM chan_review WHERE state=0 AND COALESCE(warned,0)=0 AND at < ?`, now - 4 * DAY);
    if (soon.length) {
      for (const x of soon) this.run(`UPDATE chan_review SET warned=1 WHERE job_id=?`, x.job_id);
      this.ctx.waitUntil(this.notifyAdmins(`⚠️ ${soon.length} فرصة ستؤرشف غداً إن لم تراجعها.`, [[{ text: '⏳ راجعها الآن', callback_data: 'r:0' }]]));
    }
    const arch = (this.one(`SELECT COUNT(*) n FROM chan_review WHERE state=0 AND at < ?`, now - 5 * DAY) || {}).n || 0;
    if (arch) { this.run(`UPDATE chan_review SET state=3, at=?, by='تلقائي' WHERE state=0 AND at < ?`, now, now - 5 * DAY); this.evAdd('review', 'arch', arch); }
    return { added, archived: arch };
  }

  chanReviewList(state = 0) {
    const today = new Date(Date.now() + 3 * 3600e3).toISOString().slice(0, 10);
    return this.q(`SELECT r.job_id id, r.state, r.contact, r.at, r.by, j.title, j.org, j.text, j.deadline FROM chan_review r JOIN jobs j ON j.id=r.job_id
      WHERE r.state=? AND j.hidden=0 AND r.job_id NOT IN (SELECT job_id FROM chan_posted) ORDER BY r.at DESC LIMIT 60`, state)
      .map(x => ({ ...x, title: this.unNorm(x.text, x.title), org: this.unNorm(x.text, x.org), text: String(x.text || '').slice(0, 1500), expired: x.deadline && x.deadline < today ? 1 : 0 }));
  }

  reviewSet(id, state, by) {
    this.run(`INSERT INTO chan_review(job_id,state,contact,at,by,warned) VALUES(?,?,?,?,?,0) ON CONFLICT(job_id) DO UPDATE SET state=excluded.state, at=excluded.at, by=excluded.by, warned=0`,
      +id, state, this.chanContact((this.one(`SELECT text FROM jobs WHERE id=?`, +id) || {}).text), Date.now(), by);
    this.evAdd('review', ['', 'ok', 'no', 'arch'][state] || 'undo');
    if (state === 1) this.ctx.waitUntil(this.chanFlush());
    return { ok: true };
  }

  chanInfo(origin) {
    const cfg = this.chanCfg(), since = Date.now() - DAY;
    const cands = this.chanCands().out;
    const all = this.chanPick(0, cands.filter(r => r.kind !== 'tender'));
    const next = cfg.mode === 'all' ? all.slice(0, 15) : this.chanPick(cfg.n), tn = cands.filter(r => r.kind === 'tender').slice(0, 12);
    let fill = null; try { fill = JSON.parse((this.one(`SELECT v FROM meta WHERE k='chan_fill'`) || {}).v || 'null'); } catch (_) { /* */ }
    const cnt = st => (this.one(`SELECT COUNT(*) n FROM chan_review r JOIN jobs j ON j.id=r.job_id WHERE r.state=? AND j.hidden=0`, st) || {}).n || 0;
    return {
      cfg, err: JSON.parse((this.one(`SELECT v FROM meta WHERE k='chan_err'`) || {}).v || 'null'),
      today: (this.one(`SELECT COUNT(*) n FROM chan_posted WHERE skip=0 AND at>?`, since) || {}).n || 0,
      total: (this.one(`SELECT COUNT(*) n FROM chan_posted WHERE skip=0`) || {}).n || 0,
      waiting: all.length + tn.length, fill,
      fillCount: this.chanCands(7).out.length,
      slots: JSON.parse((this.one(`SELECT v FROM meta WHERE k='chan_slots'`) || {}).v || '{}'),
      review: this.chanReviewList(0), counts: { pending: cnt(0), rejected: cnt(2), archived: cnt(3) },
      next: next.map(r => ({ id: r.id, title: this.unNorm(r.text, r.title), org: this.unNorm(r.text, r.org), src: this.srcSite(r), deadline: r.deadline, contact: r.keepContact ? 1 : 0 })),
      tenders: tn.map(r => ({ id: r.id, title: this.unNorm(r.text, r.title), org: this.unNorm(r.text, r.org) })),
      preview: this.chanText(next.slice(0, 3)),
    };
  }

  async notifyTg(text) {
    try {
      await fetch(`https://api.telegram.org/bot${this.env.TG_BOT_TOKEN}/sendMessage`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ chat_id: this.env.TG_CHAT_ID, text: String(text).slice(0, 3500) }),
      });
    } catch (_) { /* تجاهل */ }
  }

  // ===== 3.3: اشتراك البوت — المنتج الأساسي: فرص اختصاصك ومحافظتك في الخاص بلا تثبيت ولا حساب =====
  botUser() { return (this.one(`SELECT v FROM meta WHERE k='tg_bot_user'`) || {}).v || 'wazifati_bot'; }
  botLink(p) { return `https://t.me/${this.botUser()}?start=${p}`; }
  subGet(id) { return this.one(`SELECT * FROM bot_subs WHERE tg_id=?`, String(id)); }

  subSummary(s) {
    const sp = s.specs === 'all' ? 'كل الاختصاصات' : String(s.specs || '').split(',').filter(Boolean).map(k => LABELS.specs[k]).filter(Boolean).join('، ') || '—';
    const gv = s.govs === 'all' ? 'كل المحافظات' : String(s.govs || '').split(',').filter(Boolean).map(k => LABELS.govs[k]).filter(Boolean).join('، ') || '—';
    return `🎓 ${sp}${s.tenders ? ' + 📑 المناقصات' : ''}\n📍 ${gv}\n🔔 ${s.mode === 'daily' ? 'ملخص يومي مساءً' : 'فوراً عند نشر كل فرصة'}`;
  }

  /** شاشات الاشتراك (تعديل الرسالة نفسها في كل خطوة) */
  subScreen(s, step) {
    const has = (csv, k) => String(csv || '').split(',').includes(k);
    if (step === 'specs') {
      const all = s.specs === 'all';
      const rows = [];
      for (let i = 0; i < SPECS.length; i += 2) rows.push(SPECS.slice(i, i + 2).map(x => ({ text: (all || has(s.specs, x.id) ? '✅ ' : '') + x.name, callback_data: 'u:sp:' + x.id })));
      rows.push([{ text: (s.tenders ? '✅ ' : '') + '📑 المناقصات وطلبات العروض', callback_data: 'u:tn' }]);
      rows.push([{ text: (all ? '✅ ' : '') + '🌐 كل الاختصاصات', callback_data: 'u:sp:all' }]);
      rows.push([{ text: 'التالي: المحافظة ⬅', callback_data: 'u:next1' }]);
      return { text: '1️⃣ اختر اختصاصك (يمكنك اختيار أكثر من واحد) ثم اضغط «التالي»:', kb: rows };
    }
    if (step === 'govs') {
      const all = s.govs === 'all';
      const G = Object.entries(LABELS.govs).filter(([k]) => !['all', 'remote', 'abroad', 'unknown'].includes(k));
      const rows = [];
      for (let i = 0; i < G.length; i += 2) rows.push(G.slice(i, i + 2).map(([k, n]) => ({ text: (all || has(s.govs, k) ? '✅ ' : '') + n, callback_data: 'u:gv:' + k })));
      rows.push([{ text: (has(s.govs, 'remote') ? '✅ ' : '') + '💻 عمل عن بُعد', callback_data: 'u:gv:remote' }, { text: (all ? '✅ ' : '') + '🇸🇾 كل المحافظات', callback_data: 'u:gv:all' }]);
      rows.push([{ text: '➡ رجوع', callback_data: 'u:edit' }, { text: 'التالي ⬅', callback_data: 'u:next2' }]);
      return { text: '2️⃣ اختر محافظتك (أو أكثر) ثم اضغط «التالي»:', kb: rows };
    }
    if (step === 'mode') return { text: '3️⃣ متى تصلك الفرص؟', kb: [[{ text: '⚡ فوراً — كل فرصة لحظة نشرها', callback_data: 'u:md:instant' }], [{ text: '🌙 ملخص واحد يومياً مساءً', callback_data: 'u:md:daily' }], [{ text: '➡ رجوع', callback_data: 'u:next1' }]] };
    // done / status
    const chan = this.chanCfg().chat.replace(/^@/, '');
    return {
      text: (s.active ? '✅ اشتراكك فعّال — ستصلك فرص اختصاصك هنا:\n\n' : '⏸ اشتراكك متوقف.\n\n') + this.subSummary(s) + '\n\n⚠️ لا تدفع أي مبلغ مقابل وظيفة، وبلّغنا عن أي إعلان مشبوه.',
      kb: [[{ text: '⚙️ تعديل اشتراكي', callback_data: 'u:edit' }, { text: s.active ? '⏸ إيقاف مؤقت' : '▶ تشغيل', callback_data: s.active ? 'u:stop' : 'u:resume' }],
        [{ text: '📲 تطبيق وظيفتي (تتبّع طلباتك، سيرتك، تذكيرات)', callback_data: 'u:apk' }],
        [{ text: '📣 قناة كل الفرص', url: `https://t.me/${chan}` }, { text: '↗ شارك البوت', url: `https://t.me/share/url?url=${encodeURIComponent(this.botLink('share'))}&text=${encodeURIComponent('فرص عمل سوريا حسب اختصاصك ومحافظتك تصلك على تيليغرام مجاناً 👇')}` }]],
    };
  }

  async subStart(m, src) {
    const id = String(m.from.id), now = Date.now();
    const name = [m.from.first_name, m.from.last_name].filter(Boolean).join(' ').slice(0, 80);
    let s = this.subGet(id);
    if (!s) {
      this.run(`INSERT INTO bot_subs(tg_id,name,uname,specs,govs,tenders,mode,src,created,upd,active) VALUES(?,?,?,?,?,0,'instant',?,?,?,0)`, id, name, m.from.username || '', '', '', src || 'direct', now, now);
      this.evAdd('bot_new', src || 'direct');
      s = this.subGet(id);
      await this.tgApi('sendMessage', { chat_id: m.chat.id, text: '👋 أهلاً بك في «وظيفتي» — فرص العمل والمناقصات في سوريا.\nاختر اختصاصك ومحافظتك، وتصلك الفرص المناسبة لك هنا مباشرة، مجاناً وبلا تسجيل.', reply_markup: { remove_keyboard: true } });
    } else this.run(`UPDATE bot_subs SET name=?, uname=?, blocked=0 WHERE tg_id=?`, name, m.from.username || '', id);
    const done = s.specs && s.govs;
    const v = this.subScreen(s, done ? 'done' : 'specs');
    return this.tgApi('sendMessage', { chat_id: m.chat.id, text: v.text, reply_markup: { inline_keyboard: v.kb } });
  }

  async subCallback(q, d) {
    const id = String(q.from.id), chat = q.message.chat.id, mid = q.message.message_id;
    let s = this.subGet(id);
    if (!s) { this.run(`INSERT INTO bot_subs(tg_id,name,uname,specs,govs,tenders,mode,src,created,upd,active) VALUES(?,?,?,'','',0,'instant','direct',?,?,0)`, id, q.from.first_name || '', q.from.username || '', Date.now(), Date.now()); this.evAdd('bot_new', 'direct'); s = this.subGet(id); }
    const tog = (csv, k) => { const a = String(csv || '').split(',').filter(x => x && x !== 'all'); const i = a.indexOf(k); if (i >= 0) a.splice(i, 1); else a.push(k); return a.join(','); };
    const [, act, arg] = d.split(':');
    let step = 'done';
    if (act === 'sp') { this.run(`UPDATE bot_subs SET specs=? WHERE tg_id=?`, arg === 'all' ? (s.specs === 'all' ? '' : 'all') : tog(s.specs, arg), id); step = 'specs'; }
    else if (act === 'tn') { this.run(`UPDATE bot_subs SET tenders=? WHERE tg_id=?`, s.tenders ? 0 : 1, id); step = 'specs'; }
    else if (act === 'gv') { this.run(`UPDATE bot_subs SET govs=? WHERE tg_id=?`, arg === 'all' ? (s.govs === 'all' ? '' : 'all') : tog(s.govs, arg), id); step = 'govs'; }
    else if (act === 'edit') step = 'specs';
    else if (act === 'next1') {
      if (!s.specs && !s.tenders) { await this.tgApi('answerCallbackQuery', { callback_query_id: q.id, text: 'اختر اختصاصاً واحداً على الأقل', show_alert: true }); return; }
      step = 'govs';
    } else if (act === 'next2') {
      if (!s.govs) { await this.tgApi('answerCallbackQuery', { callback_query_id: q.id, text: 'اختر محافظة واحدة على الأقل', show_alert: true }); return; }
      step = 'mode';
    } else if (act === 'md') {
      const first = !s.active && !s.upd_done;
      this.run(`UPDATE bot_subs SET mode=?, active=1, upd=?, upd_done=1 WHERE tg_id=?`, arg === 'daily' ? 'daily' : 'instant', Date.now(), id);
      if (first) this.evAdd('bot_sub', s.src || 'direct');
      step = 'done';
      s = this.subGet(id);
      await this.tgApi('answerCallbackQuery', { callback_query_id: q.id, text: '✅ تم! ستصلك فرص اختصاصك هنا' });
      await this.subWelcomeJobs(s, chat);
    } else if (act === 'stop') { this.run(`UPDATE bot_subs SET active=0 WHERE tg_id=?`, id); this.evAdd('bot_stop'); }
    else if (act === 'resume') this.run(`UPDATE bot_subs SET active=1 WHERE tg_id=?`, id);
    else if (act === 'apk') { await this.tgApi('answerCallbackQuery', { callback_query_id: q.id }); const r = await this.apkSend(chat); if (r.ok) this.evAdd('bot_apk'); return; }
    if (act !== 'md') await this.tgApi('answerCallbackQuery', { callback_query_id: q.id });
    const v = this.subScreen(this.subGet(id), step);
    return this.botShow(chat, mid, v.text, v.kb);
  }

  /** هل الفرصة تناسب المشترك؟ */
  subMatch(s, j) {
    const has = (csv, k) => String(csv || '').split(',').includes(k);
    const jg = String(j.govs || '').split(',').filter(Boolean);
    const govOk = s.govs === 'all' ? !jg.includes('abroad') : (jg.includes('all') || jg.some(g => has(s.govs, g)));
    if (j.kind === 'tender') return !!s.tenders && (govOk || jg.length === 0 || jg.includes('unknown'));
    if (!s.specs) return false;
    const js = String(j.specs || '').split(',').filter(x => x && x !== 'other');
    const specOk = s.specs === 'all' || js.some(x => has(s.specs, x));
    return specOk && govOk;
  }

  /** بعد نشر فرصة في القناة: تُضاف لطابور المشتركين المطابقين (الفوري) */
  subEnqueue(j) {
    const now = Date.now();
    for (const s of this.q(`SELECT * FROM bot_subs WHERE active=1 AND blocked=0 AND mode='instant'`)) if (this.subMatch(s, j)) this.run(`INSERT OR IGNORE INTO bot_q(tg_id,job_id,at) VALUES(?,?,?)`, s.tg_id, j.id, now);
  }

  /** أول اشتراك: آخر 3 فرص مطابقة من 48 ساعة كترحيب حتى يرى القيمة فوراً */
  async subWelcomeJobs(s, chat) {
    const rows = this.q(`SELECT j.* FROM chan_posted p JOIN jobs j ON j.id=p.job_id WHERE p.skip=0 AND p.at>? AND j.hidden=0 ORDER BY p.at DESC LIMIT 200`, Date.now() - 2 * DAY).filter(j => this.subMatch(s, j)).slice(0, 3);
    if (!rows.length) return this.tgApi('sendMessage', { chat_id: chat, text: '🔎 لا توجد فرص مطابقة خلال آخر يومين — ستصلك هنا فور نشرها.' });
    await this.tgApi('sendMessage', { chat_id: chat, text: `🔎 آخر ${rows.length} فرص تناسبك:` });
    for (const j of rows) await this.subSendJob(s, j);
  }

  subKb(j) {
    return { inline_keyboard: [[{ text: '📄 التفاصيل كاملة وطريقة التقديم', url: `${SITE}/j/${j.id}?c=bot` }]] };
  }

  async subSendJob(s, j) {
    const rv = this.one(`SELECT state FROM chan_review WHERE job_id=?`, j.id);
    j.keepContact = rv && rv.state === 1 ? 1 : 0;
    const text = j.kind === 'tender' ? this.chanTenderOne(j) : this.chanJobText(j);
    const r = await this.tgApi('sendMessage', { chat_id: s.tg_id, text, parse_mode: 'HTML', disable_web_page_preview: true, reply_markup: this.subKb(j) });
    return this.subAfterSend(s, r);
  }

  subAfterSend(s, r) {
    if (r.ok) { this.run(`UPDATE bot_subs SET last_sent=?, sent=sent+1 WHERE tg_id=?`, Date.now(), s.tg_id); this.evAdd('bot_deliver'); return 'ok'; }
    if (r.error_code === 403 || /blocked|deactivated|not found/i.test(r.description || '')) {
      this.run(`UPDATE bot_subs SET blocked=1, active=0 WHERE tg_id=?`, s.tg_id);
      this.run(`DELETE FROM bot_q WHERE tg_id=?`, s.tg_id);
      this.evAdd('bot_blocked');
      return 'gone';
    }
    if (r.error_code === 429) return 'wait';
    return 'err';
  }

  /** يرسل من الطابور ضمن ميزانية الطلبات (حد Cloudflare 50 طلباً فرعياً لكل تشغيل) */
  async subDrain(budget) {
    const rows = this.q(`SELECT q.tg_id, q.job_id FROM bot_q q ORDER BY q.at LIMIT ?`, budget);
    let n = 0;
    for (const x of rows) {
      const s = this.subGet(x.tg_id);
      if (!s || !s.active || s.blocked) { this.run(`DELETE FROM bot_q WHERE tg_id=?`, x.tg_id); continue; }
      let res;
      if (x.job_id === 0) {   // الملخص اليومي
        const js = this.q(`SELECT j.* FROM chan_posted p JOIN jobs j ON j.id=p.job_id WHERE p.skip=0 AND p.at>? AND j.hidden=0 ORDER BY p.at`, Date.now() - DAY).filter(j => this.subMatch(s, j)).slice(0, 15);
        if (js.length) {
          const { esc } = this.chanFmt();
          const t = [`🌙 <b>فرص اليوم المناسبة لك (${js.length})</b>`, '', ...js.map((j, i) => `${i + 1}. <a href="${SITE}/j/${j.id}?c=bot">${esc(this.cleanText(this.unNorm(j.text, j.title)).slice(0, 80))}</a>${j.org ? ' — ' + esc(this.cleanText(this.unNorm(j.text, j.org)).slice(0, 40)) : ''}`), '', '⚠️ لا تدفع أي مبلغ مقابل وظيفة.'].join('\n');
          res = this.subAfterSend(s, await this.tgApi('sendMessage', { chat_id: s.tg_id, text: t.slice(0, 4000), parse_mode: 'HTML', disable_web_page_preview: true }));
        } else res = 'ok';
      } else {
        const j = this.one(`SELECT * FROM jobs WHERE id=? AND hidden=0`, x.job_id);
        res = j ? await this.subSendJob(s, j) : 'ok';
      }
      if (res === 'wait') break;
      this.run(`DELETE FROM bot_q WHERE tg_id=? AND job_id=?`, x.tg_id, x.job_id);
      n++;
    }
    const left = (this.one(`SELECT COUNT(*) n FROM bot_q`) || {}).n || 0;
    return { n, left };
  }

  /** الساعة 8 مساءً بتوقيت دمشق: طابور الملخص اليومي لمشتركي «ملخص يومي» */
  subDaily() {
    const dm = new Date(Date.now() + 3 * 3600e3), day = dm.toISOString().slice(0, 10);
    if (dm.getUTCHours() < 20) return 0;
    const rows = this.q(`SELECT tg_id FROM bot_subs WHERE active=1 AND blocked=0 AND mode='daily' AND COALESCE(digest_day,'')<>?`, day);
    for (const r of rows) { this.run(`INSERT OR IGNORE INTO bot_q(tg_id,job_id,at) VALUES(?,0,?)`, r.tg_id, Date.now()); this.run(`UPDATE bot_subs SET digest_day=? WHERE tg_id=?`, day, r.tg_id); }
    return rows.length;
  }

  subStats(days) {
    const since = Date.now() - days * DAY, n = (sql, ...a) => (this.one(sql, ...a) || {}).n || 0;
    return {
      total: n(`SELECT COUNT(*) n FROM bot_subs WHERE active=1 AND blocked=0`),
      started: n(`SELECT COUNT(*) n FROM bot_subs`),
      fresh: n(`SELECT COUNT(*) n FROM bot_subs WHERE created>? AND active=1`, since),
      reached: n(`SELECT COUNT(*) n FROM bot_subs WHERE last_sent>?`, Date.now() - 7 * DAY),
      left: n(`SELECT COUNT(*) n FROM bot_subs WHERE blocked=1 OR (active=0 AND upd_done=1)`),
      delivered: this.evSum('bot_deliver', days),
    };
  }

  // ===== 3.3: الحماية — بلاغ احتيال، «احذف منشوري»، كشف طلب المال =====
  fraudy(text) { return /(رسوم (ال)?(تسجيل|اشتراك|قبول|ملف|تقديم|استمار[ةه])|ادفع|دفع مبلغ|تحويل مبلغ|مبلغ رمزي|ثمن (ال)?استمار[ةه]|سعر (ال)?استمار[ةه]|(ارسل|أرسل|تحويل|شحن) رصيد|كرت (شحن|رصيد)|حوال[ةه] (مالي[ةه]|بقيم[ةه]))/i.test(String(text || '')); }

  async jobReport(m, jobId, kind) {
    const j = this.one(`SELECT id,title,org,text,url FROM jobs WHERE id=?`, jobId);
    const send = t => this.tgApi('sendMessage', { chat_id: m.chat.id, text: t });
    if (!j) return send('هذا الإعلان لم يعد موجوداً.');
    const who = [m.from.first_name, m.from.last_name].filter(Boolean).join(' ') + (m.from.username ? ' @' + m.from.username : '');
    const title = this.unNorm(j.text, j.title);
    if (kind === 'report') {
      const dup = this.one(`SELECT 1 FROM flags WHERE job_id=? AND email=?`, jobId, 'tg:' + m.from.id);
      if (!dup) this.run(`INSERT OR IGNORE INTO flags(job_id,email,created) VALUES(?,?,?)`, jobId, 'tg:' + m.from.id, Date.now());
      this.evAdd('fraud_report');
      await send('🙏 شكراً لك — وصل بلاغك للإدارة وسنراجع الإعلان بسرعة.\nتذكير: لا تدفع أي مبلغ مقابل وظيفة.');
      if (!dup) await this.notifyAdmins(`🚨 بلاغ احتيال على فرصة:\n${title}\n${j.org || ''}\nمن: ${who}\n${SITE}/j/${jobId}`, [[{ text: '🚫 إخفاء وحذف المنشور', callback_data: `jx:${jobId}` }, { text: '✔ سليم', callback_data: `jk:${jobId}` }]]);
    } else {
      this.evAdd('del_request');
      await send('📨 وصل طلب حذف إعلانك للإدارة، وسيُحذف بعد التحقق. شكراً لك.');
      await this.notifyAdmins(`🗑 طلب حذف من صاحب الإعلان:\n${title}\n${j.org || ''}\nمن: ${who}\n${SITE}/j/${jobId}`, [[{ text: '🗑 احذفه', callback_data: `jx:${jobId}` }, { text: 'تجاهل', callback_data: `jk:${jobId}` }]]);
    }
  }

  /** إخفاء فرصة وحذف منشورها من القناة (أو إزالتها من منشور مناقصات الجهة) */
  async jobKill(jobId) {
    this.run(`UPDATE jobs SET hidden=1, updated=? WHERE id=?`, Date.now(), jobId);
    this.run(`DELETE FROM bot_q WHERE job_id=?`, jobId);
    const p = this.one(`SELECT msg_id FROM chan_posted WHERE job_id=? AND msg_id>0`, jobId);
    if (!p) return 'hidden';
    const cfg = this.chanCfg();
    const others = this.q(`SELECT j.* FROM chan_posted c JOIN jobs j ON j.id=c.job_id WHERE c.msg_id=? AND c.job_id<>? AND j.hidden=0`, p.msg_id, jobId);
    this.run(`UPDATE chan_posted SET skip=1 WHERE job_id=?`, jobId);
    this._cmap = null;
    if (others.length) {
      const r = await this.tgApi('editMessageText', { chat_id: cfg.chat, message_id: p.msg_id, text: others.length > 1 ? this.chanTenderGroup(others, SITE) : this.chanTenderOne(others[0]), parse_mode: 'HTML', disable_web_page_preview: true });
      return r.ok ? 'edited' : 'hidden';
    }
    const r = await this.tgApi('deleteMessage', { chat_id: cfg.chat, message_id: p.msg_id });
    return r.ok ? 'deleted' : 'hidden';
  }

  // ===== 3.2: إحصائيات الأحداث (أعداد فقط بلا هوية) =====
  dayDm(off = 0) { return new Date(Date.now() + 3 * 3600e3 - off * DAY).toISOString().slice(0, 10); }
  evAdd(k, v = '', n = 1) {
    try { this.run(`INSERT INTO ev(day,k,v,n) VALUES(?,?,?,?) ON CONFLICT(day,k,v) DO UPDATE SET n=n+excluded.n`, this.dayDm(), k, String(v || '').slice(0, 30), n); } catch (_) { /* */ }
  }
  evSet(k, n, v = '') { this.run(`INSERT OR REPLACE INTO ev(day,k,v,n) VALUES(?,?,?,?)`, this.dayDm(), k, v, n); }
  evSum(k, days) { return (this.one(`SELECT SUM(n) n FROM ev WHERE k=? AND day>?`, k, this.dayDm(days)) || {}).n || 0; }
  evBy(k, days) { return this.q(`SELECT v, SUM(n) n FROM ev WHERE k=? AND day>? GROUP BY v ORDER BY n DESC`, k, this.dayDm(days)); }
  jobEv(id, k) { try { this.run(`INSERT INTO job_ev(job_id,k,n) VALUES(?,?,1) ON CONFLICT(job_id,k) DO UPDATE SET n=n+1`, id, k); } catch (_) { /* */ } }

  // ===== 3.2: صفحة التحميل + رابط APK المباشر + ملف التطبيق في تيليغرام =====
  async relLatest(force) {
    let c = null; try { c = JSON.parse((this.one(`SELECT v FROM meta WHERE k='rel_cache'`) || {}).v || 'null'); } catch (_) { /* */ }
    if (c && !force && Date.now() - c.at < 20 * 60e3) return c;
    try {
      const r = await fetch(GH_LATEST, { headers: { 'User-Agent': 'wazifati-worker', Accept: 'application/vnd.github+json' } });
      if (!r.ok) return c;
      const j = await r.json();
      const a = (j.assets || []).find(x => /\.apk$/i.test(x.name));
      if (!a) return c;
      const notes = String(j.body || '').split('\n').map(x => x.replace(/^[\s*\-•]+/, '').trim())
        .filter(x => x && !/^(#|التثبيت|\*\*التثبيت)/.test(x) && !/apk|github/i.test(x)).slice(0, 6);
      c = { at: Date.now(), tag: String(j.tag_name || '').replace(/^v/, ''), url: a.browser_download_url, name: a.name, size: a.size, date: String(j.published_at || '').slice(0, 10), notes };
      this.run(`INSERT OR REPLACE INTO meta(k,v) VALUES('rel_cache',?)`, JSON.stringify(c));
    } catch (_) { /* */ }
    return c;
  }

  async downloadPage(url) {
    const from = String(url.searchParams.get('from') || 'direct').replace(/[^\w-]/g, '').slice(0, 20) || 'direct';
    this.evAdd('dl_page', from);
    const rel = await this.relLatest() || {};
    const esc = x => String(x == null ? '' : x).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    const mb = rel.size ? (rel.size / 1048576).toFixed(1) + ' ميغا' : '';
    const chan = this.chanCfg().chat.replace(/^@/, '');
    const bot = (this.one(`SELECT v FROM meta WHERE k='tg_bot_user'`) || {}).v || 'wazifati_bot';
    const qr = `https://api.qrserver.com/v1/create-qr-code/?size=180x180&data=${encodeURIComponent(SITE + '/download?from=qr')}`;
    const html = `<!doctype html><html lang="ar" dir="rtl"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>تحميل تطبيق وظيفتي</title><meta property="og:title" content="تحميل تطبيق وظيفتي — فرص العمل في سوريا"><meta property="og:description" content="تطبيق مجاني يجمع فرص العمل والمناقصات في سوريا حسب اختصاصك ومحافظتك.">
<style>body{font-family:system-ui,sans-serif;max-width:560px;margin:auto;padding:16px;line-height:1.8;color:#1c2333;background:#f4f6fb}.c{background:#fff;border-radius:16px;padding:18px;margin:12px 0;box-shadow:0 1px 4px #0001}
h1{font-size:24px;margin:4px 0}.m{color:#5a6477;font-size:14px}.b{display:block;text-align:center;padding:16px;border-radius:14px;margin:10px 0;font-weight:800;font-size:18px;text-decoration:none}.p{background:#1565C0;color:#fff}.s{background:#e8effb;color:#1565C0;font-size:15px;padding:12px}
ol{padding-right:20px;margin:6px 0}li{margin:6px 0}.w{background:#fff6e5;border-right:4px solid #e65100;padding:10px 12px;border-radius:10px}details{margin:8px 0}summary{font-weight:700;cursor:pointer}.q{text-align:center}</style>
<div class="c" style="text-align:center"><div style="font-size:46px">💼</div><h1>وظيفتي</h1><div class="m">فرص العمل والمناقصات في سوريا — حسب اختصاصك ومحافظتك، مجاناً</div>
<a class="b p" href="/apk?from=${esc(from)}">⬇ تحميل التطبيق${mb ? ' (' + mb + ')' : ''}</a>
<div class="m">${rel.tag ? 'الإصدار ' + esc(rel.tag) + (rel.date ? ' • ' + esc(rel.date) : '') : ''} • أندرويد 8 فما فوق</div></div>
<div class="c"><b>📲 طريقة التثبيت</b><ol><li>اضغط زر التحميل وانتظر انتهاء التنزيل.</li><li>افتح الملف من الإشعار أو من «التنزيلات».</li><li>إن طُلب منك: اسمح بـ<b>«التثبيت من هذا المصدر»</b> ثم ارجع واضغط «تثبيت».</li><li>افتح التطبيق واختر اختصاصك ومحافظتك ✅</li></ol>
<div class="w"><b>🛡 إن ظهر تحذير Google Play Protect:</b><br>اضغط <b>«مزيد من التفاصيل»</b> ثم <b>«التثبيت على أي حال»</b>. التحذير يظهر لأن التطبيق غير منشور على متجر Google Play، وهذا طبيعي.</div>
<details><summary>❓ ظهرت رسالة «التطبيق لم يُثبَّت»</summary><ul><li>إن كانت عندك نسخة قديمة: احذفها أولاً ثم ثبّت الجديدة.</li><li>تأكد من وجود مساحة فارغة (50 ميغا على الأقل).</li><li>أعد تنزيل الملف إن انقطع التحميل.</li></ul></details>
<details><summary>❓ لا أجد الملف بعد التحميل</summary><p class="m">افتح تطبيق «الملفات» ← «التنزيلات» وابحث عن ${esc(rel.name || 'Wazifati.apk')}.</p></details>
<details><summary>❓ أفضّل التحميل من تيليغرام</summary><p class="m">افتح البوت واضغط «ابدأ»، فيصلك ملف التطبيق مباشرة.</p><a class="b s" href="https://t.me/${esc(bot)}?start=apk">🤖 استلم الملف من البوت</a></details></div>
${(rel.notes || []).length ? `<div class="c"><b>✨ الجديد في هذه النسخة</b><ul>${rel.notes.map(x => `<li>${esc(x)}</li>`).join('')}</ul></div>` : ''}
<div class="c"><a class="b s" href="https://t.me/${esc(chan)}">📣 تابع قناة الوظائف على تيليغرام</a><div class="q"><img src="${qr}" width="150" height="150" alt="QR"><div class="m">امسح الرمز لفتح هذه الصفحة على هاتف آخر</div></div></div>
<div class="c m" style="text-align:center">تطوير: ${DEV_NAME} • <a href="tel:${DEV_PHONE}">📞 ${DEV_PHONE}</a> • <a href="https://wa.me/${DEV_WA}">💬 واتساب</a><br><a href="/privacy">الخصوصية</a></div></html>`;
    return new Response(html, { headers: { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' } });
  }

  /** ينزّل ملف الـAPK عبر خادمنا (اسم ملف واضح، ويعمل حتى لو كان GitHub بطيئاً في المتصفح) */
  async apkFile(url) {
    const from = String(url.searchParams.get('from') || 'direct').replace(/[^\w-]/g, '').slice(0, 20) || 'direct';
    const rel = await this.relLatest();
    if (!rel || !rel.url) return Response.redirect('https://github.com/makanona-team/wazifati/releases/latest', 302);
    this.evAdd('apk', from);
    try {
      const r = await fetch(rel.url, { headers: { 'User-Agent': 'wazifati-worker' } });
      if (!r.ok || !r.body) return Response.redirect(rel.url, 302);
      const h = { 'Content-Type': 'application/vnd.android.package-archive', 'Content-Disposition': `attachment; filename="Wazifati-${rel.tag || 'latest'}.apk"`, 'Cache-Control': 'no-store' };
      if (r.headers.get('content-length')) h['Content-Length'] = r.headers.get('content-length');
      return new Response(r.body, { headers: h });
    } catch (_) { return Response.redirect(rel.url, 302); }
  }

  /** يرسل ملف التطبيق عبر البوت (file_id محفوظ لكل نسخة لتسريع الإرسال) */
  async apkSend(chatId, extra) {
    const rel = await this.relLatest();
    if (!rel || !rel.url) return { ok: false };
    let fid = null; try { const x = JSON.parse((this.one(`SELECT v FROM meta WHERE k='apk_tg'`) || {}).v || 'null'); if (x && x.tag === rel.tag) fid = x.fid; } catch (_) { /* */ }
    const cap = `📲 <b>تطبيق وظيفتي ${rel.tag}</b>\n\nطريقة التثبيت:\n1️⃣ اضغط الملف لتنزيله ثم افتحه.\n2️⃣ اسمح بـ«التثبيت من هذا المصدر» إن طُلب.\n3️⃣ إن ظهر تحذير Play Protect: «مزيد من التفاصيل» ← «التثبيت على أي حال».\n\nشرح مفصّل وحلول المشاكل: ${SITE}/download?from=bot`;
    const res = await this.tgApi('sendDocument', { chat_id: chatId, document: fid || rel.url, caption: cap, parse_mode: 'HTML', ...(extra || {}) });
    if (res.ok && res.result && res.result.document && !fid) this.run(`INSERT OR REPLACE INTO meta(k,v) VALUES('apk_tg',?)`, JSON.stringify({ tag: rel.tag, fid: res.result.document.file_id }));
    return res;
  }

  /** ملف التطبيق مثبّت أعلى القناة ويُستبدل عند كل نسخة منشورة */
  async apkPin(cfg) {
    const rel = await this.relLatest();
    if (!rel || !rel.tag) return 'no-rel';
    let st = {}; try { st = JSON.parse((this.one(`SELECT v FROM meta WHERE k='apk_pin'`) || {}).v || '{}'); } catch (_) { /* */ }
    if (st.tag === rel.tag || (st.fail && Date.now() - st.fail < 6 * 3600e3 && st.ftag === rel.tag)) return 'same';
    const res = await this.apkSend(cfg.chat, { disable_notification: true });
    if (!res.ok) { this.run(`INSERT OR REPLACE INTO meta(k,v) VALUES('apk_pin',?)`, JSON.stringify({ ...st, fail: Date.now(), ftag: rel.tag })); return 'send-fail: ' + (res.description || ''); }
    const mid = res.result.message_id;
    this.chanMsg(mid, 'apk');
    const pin = await this.tgApi('pinChatMessage', { chat_id: cfg.chat, message_id: mid, disable_notification: true });
    if (st.mid) this.ctx.waitUntil(this.tgApi('unpinChatMessage', { chat_id: cfg.chat, message_id: st.mid }));
    this.run(`INSERT OR REPLACE INTO meta(k,v) VALUES('apk_pin',?)`, JSON.stringify({ tag: rel.tag, mid }));
    if (!pin.ok) this.ctx.waitUntil(this.notifyAdmins(`📌 أُرسل ملف التطبيق ${rel.tag} إلى القناة لكن تعذّر تثبيته: ${pin.description || ''}\nأعطِ البوت صلاحية «تثبيت الرسائل» في القناة.`));
    return 'pinned ' + rel.tag;
  }

  async subsDaily(cfg) {
    const today = this.dayDm();
    if ((this.one(`SELECT n FROM ev WHERE day=? AND k='chan_subs'`, today) || {}).n) return;
    const r = await this.tgApi('getChatMemberCount', { chat_id: cfg.chat });
    if (r.ok) this.evSet('chan_subs', r.result);
  }

  /** يضبط الـwebhook (مرة) مع callback_query لأزرار لوحة التحكم */
  async ensureHook(origin) {
    if (!this.env.TG_BOT_TOKEN) return;
    const hook = origin + '/tg/webhook';
    if ((this.one(`SELECT v FROM meta WHERE k='tg_hook'`) || {}).v === hook && (this.one(`SELECT v FROM meta WHERE k='tg_hook_v'`) || {}).v === '2') return;
    const r = await this.tgApi('setWebhook', { url: hook, secret_token: await this.tgSecret(), allowed_updates: ['message', 'callback_query'] });
    if (!r.ok) return;
    const me = await this.tgApi('getMe');
    if (me.ok) this.run(`INSERT OR REPLACE INTO meta(k,v) VALUES('tg_bot_user',?)`, me.result.username);
    this.run(`INSERT OR REPLACE INTO meta(k,v) VALUES('tg_hook',?)`, hook);
    this.run(`INSERT OR REPLACE INTO meta(k,v) VALUES('tg_hook_v','2')`);
    await this.tgApi('setMyCommands', { commands: [{ command: 'start', description: 'ابدأ / تحميل التطبيق' }] });
  }

  // ===== 3.2: أزرار التقديم في صفحة الفرصة (تُعدّ الضغطات عبر /go) =====
  jobButtons(r) {
    const T = String(r.text || ''), out = [];
    const tgLink = u => /(^|\/\/)(t\.me|telegram\.me)\//i.test(String(u || ''));
    if (r.apply && /^https?:/i.test(r.apply) && !tgLink(r.apply)) out.push({ k: 'link', label: r.kind === 'tender' ? '📝 وثائق المناقصة' : '📝 رابط التقديم', href: r.apply, cls: 'p' });
    const emails = [...new Set([...(T.match(/[\w.+-]+@[\w-]+(?:\.[\w-]+)+/g) || []), ...(/^mailto:/i.test(r.apply || '') ? [r.apply.slice(7).split('?')[0]] : [])])].slice(0, 2);
    for (const m of emails) out.push({ k: 'mail', label: `📧 إرسال إيميل إلى ${m}`, href: `mailto:${m}?subject=${encodeURIComponent('طلب توظيف: ' + (r.title || ''))}`, cls: 'p' });
    const isWa = /(واتس|whats ?app)/i.test(T);
    const phones = [...new Set((T.match(/(?:\+|00)?963[\s-]?9\d(?:[\s-]?\d){7}|\b09\d(?:[\s-]?\d){7}\b/g) || []).map(x => x.replace(/[\s-]/g, '')))].slice(0, 2);
    for (const ph of phones) {
      const intl = ph.replace(/^(\+|00)/, '').replace(/^0/, '963');
      if (isWa) out.push({ k: 'wa', label: `💬 واتساب ${ph}`, href: `https://wa.me/${intl}`, cls: 'p' });
      out.push({ k: 'call', label: `📞 اتصال ${ph}`, href: `tel:+${intl}`, cls: 's' });
    }
    for (const w of [...new Set(T.match(/wa\.me\/\d+/g) || [])].slice(0, 1)) out.push({ k: 'wa', label: '💬 مراسلة واتساب', href: 'https://' + w, cls: 'p' });
    for (const u of [...new Set(T.match(/https?:\/\/[^\s<>"'()]+/g) || [])]) {
      const x = u.replace(/[.,،؛;:!?)]+$/, '');
      if (/(docs\.google\.com\/forms|forms\.gle|kobotoolbox|forms\.office\.com|jotform|typeform)/i.test(x) && !out.some(o => o.href === x)) { out.push({ k: 'form', label: '📋 نموذج التقديم', href: x, cls: 'p' }); break; }
    }
    return out;
  }

  goApply(id, i) {
    const r = this.one(`SELECT * FROM jobs WHERE id=?`, id);
    const b = r ? this.jobButtons(r)[i] : null;
    if (!b) return Response.redirect(`${SITE}/j/${id}`, 302);
    this.evAdd('apply_click', b.k); this.jobEv(id, 'apply');
    return new Response(null, { status: 302, headers: { Location: b.href, 'Cache-Control': 'no-store' } });
  }

  // ===== 3.2: تنبيهات لكل الأدمنية المربوطين بالبوت =====
  adminChats() {
    const ids = new Set();
    if (this.env.TG_CHAT_ID) ids.add(String(this.env.TG_CHAT_ID));
    for (const r of this.q(`SELECT l.tg_id FROM tg_links l JOIN accounts a ON a.email=l.email WHERE a.role='admin' AND a.status='active'`)) ids.add(String(r.tg_id));
    for (const r of this.q(`SELECT email FROM accounts WHERE role='admin' AND status='active' AND email LIKE 'tg%@telegram.user'`)) ids.add(r.email.replace(/^tg|@telegram\.user$/g, ''));
    const muted = new Set(this.q(`SELECT tg_id FROM tg_links WHERE mute=1`).map(x => String(x.tg_id)));
    return [...ids].filter(x => !muted.has(x));
  }
  async notifyAdmins(text, kb) {
    if (!this.env.TG_BOT_TOKEN) return;
    for (const id of this.adminChats()) {
      try { await this.tgApi('sendMessage', { chat_id: id, text: String(text).slice(0, 3500), ...(kb ? { reply_markup: { inline_keyboard: kb } } : {}) }); } catch (_) { /* */ }
    }
  }
  tgAdmin(tgId) {
    tgId = String(tgId);
    if (this.env.TG_CHAT_ID && tgId === String(this.env.TG_CHAT_ID)) return { email: FIRST_ADMIN, owner: 1 };
    const l = this.one(`SELECT email FROM tg_links WHERE tg_id=?`, tgId);
    for (const email of [l && l.email, `tg${tgId}@telegram.user`].filter(Boolean)) {
      const a = this.one(`SELECT email, role, status FROM accounts WHERE email=?`, email);
      if (a && a.role === 'admin' && a.status === 'active') return { email: a.email, owner: a.email === FIRST_ADMIN ? 1 : 0 };
    }
    return null;
  }

  async tgLinkStart(url, me) {
    if (!this.env.TG_BOT_TOKEN) throw new HttpErr(503, 'البوت غير مهيأ على الخادم');
    await this.ensureHook(url.origin);
    const nonce = crypto.randomUUID().replace(/-/g, '');
    this.run(`DELETE FROM tg_link_req WHERE created < ?`, Date.now() - 15 * 60e3);
    this.run(`INSERT INTO tg_link_req(nonce,email,created) VALUES(?,?,?)`, nonce, me.email, Date.now());
    const bot = (this.one(`SELECT v FROM meta WHERE k='tg_bot_user'`) || {}).v || 'wazifati_bot';
    const linked = this.one(`SELECT tg_id FROM tg_links WHERE email=?`, me.email);
    return json({ link: `https://t.me/${bot}?start=link_${nonce}`, linked: !!linked });
  }

  // ===== 3.2: ملخصات وإحصائيات نصية (للبوت والملخص اليومي والتطبيق) =====
  summaryText(days) {
    const since = Date.now() - days * DAY, n = (sql, ...a) => (this.one(sql, ...a) || {}).n || 0;
    const jobs = n(`SELECT COUNT(*) n FROM jobs WHERE created>? AND COALESCE(kind,'job')='job' AND COALESCE(hidden,0)=0`, since);
    const tenders = n(`SELECT COUNT(*) n FROM jobs WHERE created>? AND kind='tender'`, since);
    const learn = n(`SELECT COUNT(*) n FROM learn WHERE created>?`, since);
    const newAcc = n(`SELECT COUNT(*) n FROM accounts WHERE created>?`, since), active = n(`SELECT COUNT(*) n FROM accounts WHERE last_seen>?`, since), totalAcc = n(`SELECT COUNT(*) n FROM accounts`);
    const newInst = n(`SELECT COUNT(*) n FROM installs WHERE first_seen>?`, since), activeInst = n(`SELECT COUNT(*) n FROM installs WHERE last_seen>?`, since);
    const crashes = n(`SELECT COUNT(*) n FROM client_reports WHERE last_seen>? AND resolved=0`, since);
    const top = this.q(`SELECT src, COUNT(*) n FROM jobs WHERE created>? GROUP BY src ORDER BY n DESC LIMIT 3`, since);
    const vers = this.q(`SELECT app_ver v, COUNT(*) n FROM installs WHERE last_seen>? AND app_ver<>'' GROUP BY app_ver ORDER BY n DESC LIMIT 4`, since);
    const per = days === 1 ? 'اليوم' : days <= 10 ? `آخر ${days} أيام` : `آخر ${days} يوماً`;
    return [`📊 ملخص ${per}`,
      `💼 فرص جديدة: ${jobs} • 📑 مناقصات: ${tenders} • 🎓 تعلّم: ${learn}`,
      `👥 حسابات جديدة: ${newAcc} • نشطون: ${active} • المجموع: ${totalAcc}`,
      `📲 تثبيتات جديدة: ${newInst} • أجهزة نشطة: ${activeInst}`,
      vers.length ? '🔢 النسخ: ' + vers.map(v => `${v.v} (${v.n})`).join('، ') : '',
      crashes ? `💥 أخطاء غير محلولة: ${crashes}` : '✅ لا أخطاء جديدة',
      top.length ? '🏆 الأكثر إضافة: ' + top.map(t => `${t.src} (${t.n})`).join('، ') : '',
    ].filter(Boolean).join('\n');
  }

  reachText(days) {
    const since = Date.now() - days * DAY, n = (sql, ...a) => (this.one(sql, ...a) || {}).n || 0;
    const FROM = { ch: 'القناة', bot: 'البوت', direct: 'مباشر', job: 'صفحات الفرص', qr: 'QR', app: 'التطبيق', wa: 'واتساب', share: 'مشاركة' };
    const by = k => this.evBy(k, days).map(x => `${FROM[x.v] || x.v || 'مباشر'} ${x.n}`).join('، ');
    const page = this.evSum('dl_page', days), apk = this.evSum('apk', days), botf = this.evSum('bot_apk', days);
    const inst = n(`SELECT COUNT(*) n FROM installs WHERE first_seen>?`, since), acc = n(`SELECT COUNT(*) n FROM accounts WHERE created>?`, since);
    const subsNow = (this.one(`SELECT n FROM ev WHERE k='chan_subs' ORDER BY day DESC LIMIT 1`) || {}).n || 0;
    const subsThen = (this.one(`SELECT n FROM ev WHERE k='chan_subs' AND day<=? ORDER BY day DESC LIMIT 1`, this.dayDm(days)) || {}).n;
    const posts = this.evSum('chan_post', days);
    const spN = this.evSum('speed_n', days), spAvg = spN ? Math.round(this.evSum('speed', days) / spN) : 0;
    const views = this.evSum('job_view', days), chView = (this.one(`SELECT SUM(n) n FROM ev WHERE k='job_view' AND v='ch' AND day>?`, this.dayDm(days)) || {}).n || 0;
    const clicks = this.evSum('apply_click', days);
    const KN = { mail: '📧', call: '📞', wa: '💬', link: '🔗', form: '📋' };
    const ck = this.evBy('apply_click', days).map(x => `${KN[x.v] || x.v} ${x.n}`).join(' ');
    const topPost = this.one(`SELECT p.views, j.title, j.text FROM chan_posted p JOIN jobs j ON j.id=p.job_id WHERE p.at>? AND p.views>0 ORDER BY p.views DESC LIMIT 1`, since);
    const topJobs = this.q(`SELECT e.job_id, e.n, j.title, j.text FROM job_ev e JOIN jobs j ON j.id=e.job_id WHERE e.k='view' AND j.created>? ORDER BY e.n DESC LIMIT 3`, since - 14 * DAY);
    // طلبات المستخدمين (من «طلباتي» — أعداد فقط)
    const ST = { applied: 0, contact: 0, interview: 0, accepted: 0, rejected: 0 };
    for (const r of this.q(`SELECT prefs FROM accounts WHERE prefs LIKE '%"apps"%'`)) {
      try { for (const a of Object.values(JSON.parse(r.prefs).apps || {})) if ((a.u || 0) > since && ST[a.s] != null) ST[a.s]++; } catch (_) { /* */ }
    }
    const rv = this.evBy('review', days).reduce((o, x) => (o[x.v] = x.n, o), {});
    const per = days === 1 ? 'اليوم' : days <= 10 ? `آخر ${days} أيام` : `آخر ${days} يوماً`;
    const bs = this.subStats(days);
    return [`📈 الوصول — ${per}`,
      `🎯 مشتركو البوت: ${bs.total} (جدد ${bs.fresh}) • وصلتهم فرصة مطابقة هذا الأسبوع: ${bs.reached} • رسائل فرص: ${bs.delivered}`,
      `⬇ صفحة التحميل: ${page} زيارة${page ? ' (' + by('dl_page') + ')' : ''}`,
      `📦 تحميلات: ${apk}${apk ? ' (' + by('apk') + ')' : ''} • 🤖 ملفات من البوت: ${botf}`,
      `📲 تثبيتات جديدة: ${inst} • 👤 حسابات جديدة: ${acc}`,
      `🔻 التحويل: ${page} زيارة ← ${apk + botf} تحميل ← ${inst} تثبيت ← ${acc} حساب`,
      `📣 القناة: ${subsNow} مشترك${subsThen != null ? ` (${subsNow - subsThen >= 0 ? '+' : ''}${subsNow - subsThen})` : ''} • منشورات: ${posts}`,
      spN ? `⚡ سرعة النشر: متوسط ${spAvg < 60 ? spAvg + ' دقيقة' : (spAvg / 60).toFixed(1) + ' ساعة'} من نشر المصدر حتى قناتنا (${spN} منشور)` : '',
      topPost ? `👁 أكثر منشور مشاهدة: ${this.unNorm(topPost.text, topPost.title).slice(0, 50)} (${topPost.views})` : '',
      `🧲 صفحات الفرص: ${views} زيارة (من القناة ${chView})`,
      `✋ ضغطات التقديم: ${clicks}${ck ? ' — ' + ck : ''}`,
      topJobs.length ? '🔥 الأكثر زيارة: ' + topJobs.map(t => `${this.unNorm(t.text, t.title).slice(0, 35)} (${t.n})`).join('، ') : '',
      `📋 طلبات المستخدمين: قدّموا ${ST.applied + ST.contact + ST.interview + ST.accepted + ST.rejected} • مقابلات ${ST.interview} • ✅ وجدوا عملاً ${ST.accepted}`,
      (rv.ok || rv.no || rv.arch) ? `⏳ المراجعة: قُبلت ${rv.ok || 0} • رُفضت ${rv.no || 0} • أُرشفت ${rv.arch || 0}` : '',
    ].filter(Boolean).join('\n');
  }

  getReports(url) {
    const all = url.searchParams.get('all') === '1';
    const rows = this.q(`SELECT * FROM client_reports ${all ? '' : 'WHERE resolved=0'} ORDER BY last_seen DESC LIMIT 100`);
    for (const r of rows) r.kb = this.kbFor(`${r.kind} ${r.err_code} ${r.msg} ${r.trace}`);
    return json({ reports: rows });
  }

  clearReports(b) {
    if (b.all) this.run(`UPDATE client_reports SET resolved=1 WHERE resolved=0`);
    else if (b.deleteId) this.run(`DELETE FROM client_reports WHERE id=?`, +b.deleteId);
    else this.run(`UPDATE client_reports SET resolved=1 WHERE id=?`, +b.id);
    return json({ ok: true });
  }

  // ===== إحصائيات التثبيت والأجهزة والزوار (2.4) =====
  clientPing(req, b) {
    const id = String(b.installId || '').trim().slice(0, 64);
    if (id.length < 8) return json({ ok: false }, 400);
    const t = bearer(req);
    const isReg = (t && !!this.one(`SELECT 1 FROM sessions WHERE token=?`, t)) || b.mode === 'registered';
    const mode = isReg ? 'registered' : 'guest';
    const model = String(b.model || '').trim().slice(0, 80);
    const android = String(b.android || '').trim().slice(0, 24);
    const ver = String(b.ver || '').trim().slice(0, 24);
    const rooted = b.rooted ? 1 : 0;
    const now = Date.now();
    this.run(`INSERT INTO installs(install_id,model,android_ver,app_ver,mode,rooted,first_seen,last_seen) VALUES(?,?,?,?,?,?,?,?)
      ON CONFLICT(install_id) DO UPDATE SET model=excluded.model, android_ver=excluded.android_ver, app_ver=excluded.app_ver, mode=excluded.mode, rooted=excluded.rooted, last_seen=excluded.last_seen`,
      id, model, android, ver, mode, rooted, now, now);
    return json({ ok: true });
  }

  // ===== حذف الحساب نهائياً (2.4) =====
  deleteAccount(me, b) {
    if (me.email === FIRST_ADMIN) throw new HttpErr(400, 'لا يمكن حذف حساب المدير الأول من داخل التطبيق');
    if (String(b.confirm || '').trim().toLowerCase() !== 'delete') throw new HttpErr(400, 'يجب كتابة كلمة delete للتأكيد');
    const reason = String(b.reason || 'other').trim().slice(0, 40);
    const note = String(b.note || '').trim().slice(0, 300);
    this.run(`INSERT INTO deletion_reasons(reason,note,created) VALUES(?,?,?)`, reason, note, Date.now());
    this.run(`DELETE FROM sessions WHERE email=?`, me.email);
    this.run(`DELETE FROM flags WHERE email=?`, me.email);
    this.run(`DELETE FROM fixes WHERE email=?`, me.email);
    this.run(`DELETE FROM link_reports WHERE email=?`, me.email);
    this.run(`UPDATE jobs SET shared_by='deleted' WHERE shared_by=?`, me.email);
    this.run(`UPDATE jobs SET edited_by='deleted' WHERE edited_by=?`, me.email);
    this.run(`UPDATE sources SET added_by='deleted' WHERE added_by=?`, me.email);
    this.run(`DELETE FROM accounts WHERE email=?`, me.email);
    return json({ ok: true });
  }


  // ===== دليل المهارات والتعلّم (2.6) =====
  jarr(s) { try { const a = JSON.parse(s || '[]'); return Array.isArray(a) ? a : []; } catch { return []; } }

  skillsPublic() {
    const out = [];
    for (const r of this.q(`SELECT id, ar, en, cat, aliases, links FROM skills ORDER BY cat, ar`)) {
      const links = this.jarr(r.links);
      if (!links.length) continue;
      out.push({ id: r.id, ar: r.ar, en: r.en, cat: r.cat, al: this.jarr(r.aliases), links });
    }
    const upd = this.one(`SELECT MAX(updated) AS u FROM skills`);
    return json({ skills: out, updated: (upd && upd.u) || 0 });
  }

  skillsTop() {
    const jobs = this.q(`SELECT title, text FROM jobs WHERE hidden=0 AND COALESCE(kind,'job')<>'tender' ORDER BY id DESC LIMIT 1500`);
    const texts = jobs.map(j => nrm((j.title || '') + ' ' + (j.text || '')));
    const saved = new Map(this.q(`SELECT id, ar, en, cat, aliases, links FROM skills`).map(r => [r.id, r]));
    const list = [];
    const seen = new Set();
    const add = (id, ar, en, cat, aliases) => {
      const sv = saved.get(id);
      const al = sv ? this.jarr(sv.aliases) : aliases;
      let n = 0;
      for (const t of texts) if (al.some(a => aliasHit(t, a))) n++;
      list.push({ id, ar: sv ? sv.ar : ar, en: sv ? sv.en : en, cat: sv ? sv.cat : cat, aliases: al, n, links: sv ? this.jarr(sv.links) : [], saved: !!sv });
      seen.add(id);
    };
    for (const [id, ar, en, cat, al] of SEED_SKILLS) add(id, ar, en, cat, al);
    for (const [id, r] of saved) if (!seen.has(id)) add(id, r.ar, r.en, r.cat, this.jarr(r.aliases));
    list.sort((a, b) => b.n - a.n);
    return json({ scanned: jobs.length, skills: list });
  }

  saveSkill(b) {
    let id = String(b.id || '').trim().toLowerCase();
    if (!/^[a-z0-9_-]{2,40}$/.test(id)) id = 's' + Date.now().toString(36);
    const ar = String(b.ar || '').trim().slice(0, 80), en = String(b.en || '').trim().slice(0, 80);
    if (!ar && !en) throw new HttpErr(400, 'اكتب اسم المهارة');
    const cat = String(b.cat || 'أخرى').trim().slice(0, 30) || 'أخرى';
    const aliases = (Array.isArray(b.aliases) ? b.aliases : []).map(x => String(x).trim().slice(0, 40)).filter(Boolean).slice(0, 30);
    const now = Date.now();
    const types = ['course', 'video', 'playlist', 'channel', 'telegram', 'doc', 'site'];
    const links = [];
    for (const l of (Array.isArray(b.links) ? b.links : []).slice(0, 20)) {
      const u = String(l.u || '').trim();
      if (!/^https?:\/\//i.test(u)) continue;
      links.push({ t: String(l.t || '').trim().slice(0, 100) || u.slice(0, 60), u: u.slice(0, 500), l: l.l === 'en' ? 'en' : 'ar', ty: types.includes(l.ty) ? l.ty : 'site', c: +l.c || now });
    }
    this.run(`INSERT OR REPLACE INTO skills(id, ar, en, cat, aliases, links, updated) VALUES(?,?,?,?,?,?,?)`, id, ar, en, cat, JSON.stringify(aliases), JSON.stringify(links), now);
    return json({ ok: true, id });
  }

  reportLink(me, b) {
    const skill = String(b.skill || '').slice(0, 60), url = String(b.url || '').slice(0, 500);
    if (!skill || !url) throw new HttpErr(400, 'بيانات ناقصة');
    this.run(`INSERT OR IGNORE INTO link_reports(skill, url, email, created) VALUES(?,?,?,?)`, skill, url, me.email, Date.now());
    return json({ ok: true });
  }

  // ===== النسخ الاحتياطي والاسترجاع (2.4) =====
  exportBackup() {
    const names = ['jobs', 'sources', 'accounts', 'fixes', 'announcements', 'flags', 'meta', 'feature_flags', 'client_reports', 'installs', 'deletion_reasons', 'skills', 'learn'];
    const tables = {};
    for (const t of names) tables[t] = this.q(`SELECT * FROM ${t}`);
    return json({ exported_at: Date.now(), version: VERSION, tables });
  }

  restoreBackup(b) {
    const tables = b && b.tables;
    if (!tables || typeof tables !== 'object') throw new HttpErr(400, 'ملف نسخة احتياطية غير صالح');
    const allowed = ['jobs', 'sources', 'accounts', 'sessions', 'fixes', 'announcements', 'flags', 'meta', 'feature_flags', 'client_reports', 'installs', 'deletion_reasons', 'skills'];
    const counts = {};
    for (const t of allowed) {
      const rows = tables[t];
      if (!Array.isArray(rows)) continue;
      this.run(`DELETE FROM ${t}`);
      let n = 0;
      for (const r of rows) {
        const cols = Object.keys(r);
        if (!cols.length) continue;
        const qs = cols.map(() => '?').join(',');
        this.run(`INSERT OR REPLACE INTO ${t}(${cols.join(',')}) VALUES(${qs})`, ...cols.map(c => r[c]));
        n++;
      }
      counts[t] = n;
    }
    return json({ ok: true, restored: counts });
  }

  // ===== صفحة ويب لكل وظيفة (للمشاركة على واتساب/تيليغرام) =====
  jobPage(id, c) {
    const esc = x => String(x == null ? '' : x).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    const safe = u => /^(https?:|mailto:)/i.test(String(u || '')) ? esc(u) : '';
    const r = this.one(`SELECT * FROM jobs WHERE id=? AND hidden=0`, id);
    const from = String(c || '').replace(/[^\w]/g, '').slice(0, 10) || 'direct';
    this.evAdd('job_view', from);
    if (r) { this.jobEv(id, 'view'); if (from === 'ch') this.jobEv(id, 'ch'); r.title = this.unNorm(r.text, r.title); r.org = this.unNorm(r.text, r.org); }
    const chan = this.chanCfg().chat.replace(/^@/, '');
    const head = `<!doctype html><html lang="ar" dir="rtl"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">`;
    const css = `<style>body{font-family:system-ui,sans-serif;max-width:680px;margin:auto;padding:16px;line-height:1.8;color:#1c2333;background:#f4f6fb}.c{background:#fff;border-radius:14px;padding:16px;margin:12px 0;box-shadow:0 1px 4px #0001}h1{font-size:21px;margin:0 0 4px}.m{color:#5a6477;font-size:14px}.b{display:block;text-align:center;padding:13px;border-radius:12px;margin:8px 0;font-weight:700;text-decoration:none}.p{background:#1565C0;color:#fff}.s{background:#e8effb;color:#1565C0}.x{white-space:normal;word-break:break-word}img{max-width:100%;border-radius:10px}small{color:#8a93a5}</style>`;
    const dl = `${SITE}/download?from=job`;
    if (!r) return new Response(`${head}<title>وظيفتي</title>${css}<div class="c"><h1>هذه الفرصة لم تعد متاحة</h1><p class="m">قد تكون انتهت أو أُزيلت.</p></div><a class="b p" href="${dl}">حمّل وظيفتي لتصلك فرص جديدة</a><a class="b s" href="https://t.me/${esc(chan)}">📣 تابع قناة وظيفتي</a></html>`, { status: 404, headers: { 'Content-Type': 'text/html; charset=utf-8' } });
    const names = (csv, dict) => String(csv || '').split(',').filter(Boolean).map(k => dict[k]).filter(Boolean).join('، ');
    const tender = r.kind === 'tender';
    const govs = names(r.govs, LABELS.govs);
    const kind = tender ? 'مناقصة — ' + (LABELS.tcats[r.tcat] || 'أخرى') : names(r.specs, LABELS.specs);
    const sector = LABELS.sectors[r.sector] || '';
    const desc = [r.org, govs, r.deadline ? 'آخر موعد: ' + r.deadline : ''].filter(Boolean).join(' • ');
    const body = esc(this.cleanText(r.text, 1).slice(0, 4000)).replace(/\n/g, '<br>');
    const btns = this.jobButtons(r).map((x, i) => `<a class="b ${x.cls}" href="/go/${id}/${i}">${esc(x.label)}</a>`).join('');
    const site = this.srcSite(r);
    const intent = `intent://job/${id}#Intent;scheme=wazifati;package=com.feras.wazifati;S.browser_fallback_url=${encodeURIComponent(dl)};end`;
    const orig = site ? safe(r.url) : '';
    const img = /^https:/i.test(r.image || '') ? `<img src="${esc(r.image)}" alt="">` : '';
    const html = `${head}<title>${esc(r.title)} — وظيفتي</title>
<meta property="og:title" content="${esc(r.title)}"><meta property="og:description" content="${esc(desc)}"><meta property="og:type" content="article">${img ? `<meta property="og:image" content="${esc(r.image)}">` : ''}
<meta name="description" content="${esc(desc)}">${css}
<div class="c"><h1>${esc(r.title)}</h1><div class="m">${esc(r.org || '')}</div>
<p class="m">${govs ? '📍 ' + esc(govs) + '<br>' : ''}${kind ? '🏷 ' + esc(kind) + '<br>' : ''}${sector ? '🏢 ' + esc(sector) + '<br>' : ''}${r.deadline ? '⏳ آخر موعد: ' + esc(r.deadline) + '<br>' : ''}${r.bond ? '💰 التأمين: ' + esc(r.bond) + '<br>' : ''}${site ? '🌐 المصدر: ' + esc(site) : ''}</p></div>
${btns ? `<div class="c"><b>✋ طريقة التقديم</b>${btns}</div>` : ''}
<div class="c x"><b>📋 الوصف الوظيفي والتفاصيل</b><br>${body}</div>${img ? `<div class="c">${img}</div>` : ''}
${orig ? `<a class="b s" href="${orig}">📄 الإعلان على ${esc(site)}</a>` : ''}<a class="b p" href="${this.botLink(from === 'ch' ? 'job_ch' : 'job')}">🔔 فرص اختصاصك تصلك على تيليغرام — مجاناً</a><a class="b s" href="${intent}">📲 افتحها في تطبيق وظيفتي</a><a class="b s" href="https://t.me/${esc(chan)}">📣 تابع قناة وظيفتي — كل الفرص أولاً بأول</a>
<div class="c"><b>💼 وظيفتي</b><p class="m">تطبيق مجاني يجمع فرص العمل والمناقصات في سوريا ويرسل لك ما يناسب اختصاصك ومحافظتك.</p><a class="b p" href="${dl}">⬇ حمّل التطبيق</a></div>
<p><small>⚠️ تحقّق من الجهة المعلنة قبل التقديم، ولا تدفع أي مبلغ مقابل وظيفة. <a href="${this.botLink('report_' + id)}">بلّغ عن احتيال</a> • <a href="${this.botLink('del_' + id)}">هذا إعلانك وتريد حذفه؟</a> • <a href="/privacy">الخصوصية</a></small></p></html>`;
    return new Response(html, { headers: { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'private, max-age=60' } });
  }

  home() {
    const n = this.one(`SELECT COUNT(*) AS n FROM jobs`).n;
    const lastRun = +((this.one(`SELECT v FROM meta WHERE k='last_run'`) || {}).v || 0);
    const ago = !lastRun ? '—' : Math.round((Date.now() - lastRun) / 60e3) + ' دقيقة';
    const html = `<!doctype html><html lang="ar" dir="rtl"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>وظيفتي</title>
<style>body{font-family:system-ui,sans-serif;margin:24px;background:#f4f6fb;color:#1c2333;text-align:center}b{color:#1565C0;font-size:28px}</style>
<h1>💼 وظيفتي</h1><p>الخادم يعمل (v${VERSION})</p><p><b>${n}</b><br>فرصة محفوظة</p><p>آخر جلب منذ ${ago}</p>
<p><a href="/download?from=home">تحميل التطبيق</a></p></html>`;
    return new Response(html, { headers: { 'Content-Type': 'text/html; charset=utf-8' } });
  }
}

async function body(req) { return req.json().catch(() => ({})); }
function bearer(req) { const h = req.headers.get('Authorization') || ''; return h.startsWith('Bearer ') ? h.slice(7).trim() : ''; }
function cleanCsv(v, dict) {
  const arr = Array.isArray(v) ? v : String(v || '').split(',');
  return [...new Set(arr.map(x => String(x).trim()).filter(x => dict[x]))].join(',');
}
