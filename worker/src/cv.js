// تحليل السيرة الذاتية بالكلمات المفتاحية (مجاني): الاختصاصات، المسميات، المهارات، الخبرة، اللغات، المحافظات.
import { SPECS, GOVS, norm, LABELS } from './classify.js';

// مهارات وأدوات ومعايير شائعة (الاسم المعروض ← كلمات البحث)
const SKILLS = [
  ['API 650', ['api 650', 'api650']], ['API 653', ['api 653']], ['API 1104', ['api 1104']], ['ASME', ['asme']], ['AWS', ['aws d1', 'american welding']],
  ['لحام', ['welding', 'لحام', 'welder', 'wps', 'pqr']], ['NDT', ['ndt', 'radiograph', 'ultrasonic test', 'فحص غير اتلافي']],
  ['اختبار هيدروستاتيكي', ['hydrotest', 'hydrostatic', 'pressure test', 'اختبار ضغط', 'اختبار هيدرو']],
  ['مضخات', ['pump', 'pumps', 'مضخه', 'مضخات']], ['خزانات', ['tank', 'tanks', 'خزان', 'خزانات']], ['أنابيب', ['pipeline', 'piping', 'pipe', 'انابيب', 'خطوط نقل']],
  ['PE / HDPE', ['hdpe', 'polyethylene', 'بولي ايثيلين', 'pe pipe']], ['HVAC', ['hvac', 'تكييف', 'تبريد']], ['طاقة شمسية', ['solar', 'photovoltaic', 'طاقه شمسيه']],
  ['مولدات', ['generator', 'genset', 'مولده', 'مولدات']], ['QA/QC', ['qa/qc', 'quality control', 'quality assurance', 'ضبط الجوده', 'مراقبه الجوده']],
  ['ISO', ['iso 9001', 'iso 45001', 'iso 14001']], ['HSE', ['hse', 'nebosh', 'iosh', 'osha', 'السلامه المهنيه']],
  ['AutoCAD', ['autocad', 'اوتوكاد']], ['Revit', ['revit']], ['SolidWorks', ['solidworks']], ['SAP2000/ETABS', ['sap2000', 'etabs', 'staad']],
  ['MS Project', ['ms project', 'microsoft project']], ['Primavera', ['primavera', 'p6']], ['PMP', ['pmp', 'project management professional']],
  ['إدارة مشاريع', ['project management', 'اداره مشاريع', 'اداره المشاريع']], ['إدارة عقود', ['contract management', 'اداره العقود', 'عقود']],
  ['مناقصات', ['tender', 'bidding', 'مناقصات', 'دفتر شروط', 'bill of quantities', 'boq', 'جدول كميات']], ['تقدير كلف', ['cost estimat', 'تقدير الكلف', 'تقدير التكاليف']],
  ['إشراف موقع', ['site supervision', 'supervision', 'اشراف', 'متابعه المقاولين', 'contractor']],
  ['MEAL', ['meal', 'm&e', 'monitoring and evaluation', 'متابعه وتقييم', 'المتابعه والتقييم']], ['Logframe', ['logframe', 'log frame', 'الاطار المنطقي']],
  ['KoboToolbox', ['kobo', 'kobotoolbox']], ['ODK', ['odk', 'open data kit']], ['CommCare', ['commcare']], ['ActivityInfo', ['activityinfo']], ['DHIS2', ['dhis2', 'dhis 2']],
  ['SPSS', ['spss']], ['Stata', ['stata']], ['Power BI', ['power bi', 'powerbi']], ['Tableau', ['tableau']], ['Excel متقدم', ['advanced excel', 'excel', 'اكسل']],
  ['GIS', ['gis', 'arcgis', 'qgis']], ['جمع وتحليل بيانات', ['data collection', 'data analysis', 'جمع البيانات', 'تحليل البيانات']], ['كتابة التقارير', ['report writing', 'reporting', 'كتابه التقارير', 'اعداد التقارير']],
  ['TPM', ['third party monitoring', 'tpm']], ['تقييم احتياجات', ['needs assessment', 'تقييم الاحتياجات']], ['إدارة شكاوى', ['cfm', 'complaint', 'feedback mechanism', 'الشكاوي']],
  ['مشتريات', ['procurement', 'purchasing', 'مشتريات']], ['لوجستيك', ['logistics', 'supply chain', 'لوجستي']], ['مستودعات', ['warehouse', 'inventory', 'مستودع']],
  ['محاسبة', ['accounting', 'محاسبه', 'quickbooks', 'ifrs']], ['رواتب', ['payroll', 'رواتب']], ['موارد بشرية', ['human resources', 'recruitment', 'موارد بشريه', 'توظيف']],
  ['Python', ['python']], ['JavaScript', ['javascript', 'js ', 'node.js', 'react']], ['Java/Android', ['java', 'android', 'kotlin']], ['SQL', ['sql', 'mysql', 'postgres']],
  ['شبكات', ['network', 'mikrotik', 'cisco', 'ccna', 'routeros', 'شبكات']], ['Photoshop/تصميم', ['photoshop', 'illustrator', 'graphic design', 'تصميم جرافيك']],
  ['تدريب وبناء قدرات', ['capacity building', 'training', 'بناء القدرات', 'تدريب']], ['قيادة فريق', ['team lead', 'team management', 'supervised a team', 'قياده فريق', 'اداره فريق']],
  ['تنسيق مع الشركاء', ['coordination', 'stakeholder', 'partners', 'التنسيق', 'الشركاء']], ['ترجمة', ['translation', 'interpreting', 'ترجمه']],
  ['WASH', ['wash', 'water supply', 'sanitation', 'مياه الشرب', 'الصرف الصحي']], ['حماية', ['protection', 'case management', 'gbv', 'حمايه']],
  ['سبل العيش', ['livelihood', 'cash for work', 'سبل العيش']], ['صحة', ['health', 'clinic', 'hospital', 'صحي', 'مشفي']],
].map(([name, kw]) => ({ name, kw: kw.map(norm) }));

const LANGS = [
  ['العربية', ['arabic', 'عربي', 'العربيه']], ['الإنكليزية', ['english', 'انكليزي', 'انجليزي', 'الانكليزيه', 'الانجليزيه', 'ielts', 'toefl']],
  ['التركية', ['turkish', 'تركي', 'التركيه']], ['الكردية', ['kurdish', 'كردي', 'الكرديه']], ['الفرنسية', ['french', 'فرنسي', 'الفرنسيه']], ['الألمانية', ['german', 'الماني', 'الالمانيه']],
].map(([name, kw]) => ({ name, kw: kw.map(norm) }));

const TITLE_RX = /\b(senior |junior |assistant |deputy |lead |field |site |project |program(?:me)? )?(engineer|officer|coordinator|manager|specialist|assistant|supervisor|advisor|consultant|analyst|director|technician|accountant|developer|teacher|trainer|enumerator|associate|lead|head of [a-z ]+)\b/i;
const TITLE_AR_RX = /(مهندس|مهندسه|منسق|منسقه|مدير|مديره|مسؤول|مسؤوله|مشرف|مشرفه|اخصايي|مستشار|محاسب|مساعد|مدرب|فني|باحث|معلم|موظف)\s+[^\n،,.:()|\-–]{2,40}/;

function count(text, kw) {
  let n = 0;
  for (const k of kw) {
    if (!k) continue;
    const short = /^[a-z0-9&\/ .]+$/.test(k) && k.length <= 4;
    if (short) {
      const re = new RegExp('(^|[^a-z0-9])' + k.replace(/[.*+?^${}()|[\]\\\/]/g, '\\$&') + '($|[^a-z0-9])', 'g');
      n += (text.match(re) || []).length;
    } else {
      let i = 0, f;
      while ((f = text.indexOf(k, i)) >= 0) { n++; i = f + k.length; }
    }
  }
  return n;
}

// سنوات الخبرة: من نطاقات التواريخ (2017 - 2025، 2018 – حتى الآن) أو عبارة صريحة
function experienceYears(raw) {
  const t = norm(raw);
  const now = new Date().getUTCFullYear();
  let m = t.match(/(\d{1,2})\s*\+?\s*(?:years|year|yrs|سنوات|سنه|عاما|عام)\s*(?:of\s*)?(?:experience|خبره|من الخبره)/);
  const explicit = m ? +m[1] : 0;
  const ranges = [];
  const re = /((?:19|20)\d\d)\s*(?:[\/\-–—]|to|الي|حتي)\s*((?:19|20)\d\d|present|current|now|today|الان|حاليا|حتي الان|till now)/g;
  while ((m = re.exec(t))) {
    const a = +m[1], b = /^\d/.test(m[2]) ? +m[2] : now;
    if (a >= 1980 && b >= a && b <= now + 1 && b - a <= 40) ranges.push([a, b]);
  }
  ranges.sort((x, y) => x[0] - y[0]);
  let total = 0, cur = null;
  for (const r of ranges) {
    if (!cur) cur = [...r];
    else if (r[0] <= cur[1]) cur[1] = Math.max(cur[1], r[1]);
    else { total += cur[1] - cur[0]; cur = [...r]; }
  }
  if (cur) total += cur[1] - cur[0];
  // نطاق التعليم الجامعي غالباً ضمن النطاقات؛ نأخذ الأكبر بين الصريح والمحسوب مع حد أعلى معقول
  return Math.min(40, Math.max(explicit, total));
}

export function analyzeCV(raw) {
  const text = String(raw || '').slice(0, 60000);
  const n = ' ' + norm(text).replace(/\s+/g, ' ') + ' ';

  // الاختصاصات: عدد الكلمات المطابقة لكل اختصاص
  const scores = [];
  for (const s of SPECS) {
    const re = new RegExp(s.re.source, 'gi');
    const hits = n.match(re) || [];
    const distinct = new Set(hits.map(h => h.trim().toLowerCase())).size;
    const score = distinct * 3 + Math.min(hits.length, 30);
    if (score > 0) scores.push({ id: s.id, score });
  }
  // أسطر المسميات (الخبرات) لها وزن أكبر: «مهندس مشاريع – شركة نفط» تعني هندسة + نفط
  for (const line of text.split('\n')) {
    const ln = ' ' + norm(line) + ' ';
    if (line.length > 140 || !(TITLE_RX.test(line) || TITLE_AR_RX.test(norm(line)))) continue;
    for (const s of SPECS) if (s.re.test(ln)) {
      const e = scores.find(x => x.id === s.id);
      if (e) e.score += 8; else scores.push({ id: s.id, score: 8 });
    }
  }
  // «صيانة/فني» تُحسب للهندسة إن وُجدت خبرة هندسية
  const eng = scores.find(x => x.id === 'engineering');
  const drv = scores.find(x => x.id === 'drivers');
  if (eng && drv && eng.score > drv.score) drv.score = Math.round(drv.score / 3);
  scores.sort((a, b) => b.score - a.score);
  const top = scores.length ? scores[0].score : 1;
  const specs = scores.map(s => ({ id: s.id, name: LABELS.specs[s.id], pct: Math.round(100 * s.score / top) }))
    .filter(s => s.pct >= 20).slice(0, 7);

  // المهارات
  const skills = SKILLS.map(s => ({ name: s.name, n: count(n, s.kw) })).filter(s => s.n > 0)
    .sort((a, b) => b.n - a.n).slice(0, 25).map(s => s.name);

  // المسميات (أسطر قصيرة تحتوي مسمى وظيفي)
  const titles = [];
  const seen = new Set();
  for (const line of text.split(/\n|•|\|/)) {
    const l = line.replace(/\s+/g, ' ').trim();
    if (l.length < 5 || l.length > 70) continue;
    let m = l.match(TITLE_RX);
    let t = '';
    if (m) t = l.slice(Math.max(0, m.index - 25), m.index + m[0].length + 25).replace(/^[^A-Za-z]*\b/, '').replace(/[,;:(\-–].*$/, '').trim();
    else if ((m = norm(l).match(TITLE_AR_RX))) t = l.replace(/[,;:(\-–|].*$/, '').trim();
    if (t && t.length >= 5 && t.length <= 60) {
      const k = norm(t);
      if (!seen.has(k)) { seen.add(k); titles.push(t); }
    }
    if (titles.length >= 8) break;
  }

  const langs = LANGS.filter(l => count(n, l.kw) > 0).map(l => l.name);
  const govs = GOVS.filter(g => g.re.test(n)).map(g => g.id).slice(0, 6);
  const years = experienceYears(text);

  // كلمات للمطابقة مع الإعلانات (مطبّعة)
  const words = new Set();
  for (const s of SKILLS) if (skills.includes(s.name)) for (const k of s.kw) if (k.length >= 3) words.add(k);
  for (const t of titles) for (const w of norm(t).split(' ')) if (w.length >= 4 && !/^(senior|junior|field|project|program|programme|assistant|officer|manager)$/.test(w)) words.add(w);

  return {
    specs, skills, titles, langs, govs, years,
    words: [...words].slice(0, 80),
    chars: text.length,
    v: 1,
  };
}

/** ما الذي تغيّر بين تحليلين. */
export function diffProfiles(oldP, newP) {
  if (!oldP) return null;
  const add = (a, b) => (b || []).filter(x => !(a || []).includes(x));
  const os = (oldP.specs || []).map(s => s.id), ns = (newP.specs || []).map(s => s.id);
  return {
    newSkills: add(oldP.skills, newP.skills),
    lostSkills: add(newP.skills, oldP.skills),
    newSpecs: add(os, ns).map(id => LABELS.specs[id]),
    newTitles: add(oldP.titles, newP.titles),
    years: [oldP.years || 0, newP.years || 0],
  };
}
