// تصنيف إعلانات الوظائف بالكلمات المفتاحية (مجاني، بدون ذكاء اصطناعي).
// يستخرج: هل هو إعلان وظيفة، المسمى، الجهة، المحافظات، الاختصاصات، نوع الجهة، موعد الإغلاق، رابط التقديم.

export function norm(s) {
  return String(s || '')
    .toLowerCase()
    .replace(/[ً-ْـ]/g, '')   // تشكيل وتطويل
    .replace(/[أإآٱ]/g, 'ا')
    .replace(/ة/g, 'ه')
    .replace(/ى/g, 'ي')
    .replace(/ؤ/g, 'و')
    .replace(/ئ/g, 'ي')
    .replace(/[٠-٩]/g, d => '٠١٢٣٤٥٦٧٨٩'.indexOf(d))
    .replace(/[۰-۹]/g, d => '۰۱۲۳۴۵۶۷۸۹'.indexOf(d));
}

// كلمة عربية/إنكليزية تُطابق كجزء من كلمة للعربية (بسبب ال، و، ب…) وككلمة كاملة للإنكليزية القصيرة.
function rx(list) {
  const parts = list.map(w => {
    let word = norm(w).trim(), end = false;
    if (word.endsWith('$')) { end = true; word = word.slice(0, -1); }
    const n = word.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace(/\s+/g, '\\s*[-_]?\\s*');
    if (!/^[a-z0-9]/.test(word)) return n;              // عربي: يطابق داخل الكلمة (ال، و، ب…)
    return '\\b' + n + (end || word.length <= 4 ? '\\b' : '');   // إنكليزي: بداية كلمة
  });
  return new RegExp(parts.join('|'), 'i');
}

// ===== الاختصاصات =====
export const SPECS = [
  ['admin', 'إدارة وموارد بشرية', ['موارد بشريه', 'شؤون الموظفين', 'اداري', 'اداريه', 'مدير مكتب', 'سكرتير', 'استقبال', 'human resource', 'hr', 'admin', 'administrat', 'office manager', 'receptionist', 'secretary', 'recruit', 'تعيينات', 'ارشيف', 'مدخل بيانات', 'data entry']],
  ['finance', 'مالية ومحاسبة', ['محاسب', 'المالي', 'ماليه', 'مسؤول مالي', 'مدير مالي', 'موظف مالي', 'تدقيق', 'مدقق', 'خزينه', 'صندوق', 'finance', 'financial', 'accountant', 'accounting', 'audit', 'cashier', 'treasury', 'budget', 'payroll', 'رواتب']],
  ['logistics', 'مشتريات ولوجستيك ومستودعات', ['مشتريات', 'لوجست', 'مستودع', 'امين مستودع', 'سلسله التوريد', 'توريد', 'نقل وشحن', 'procurement', 'logistic', 'supply chain', 'warehouse', 'storekeeper', 'store keeper', 'fleet', 'purchas', 'inventory']],
  ['meal', 'متابعة وتقييم MEAL', ['متابعه وتقييم', 'المتابعه والتقييم', 'رصد وتقييم', 'مساءله', 'جوده البرامج', 'meal', 'm&e', 'm & e', 'monitoring', 'evaluation', 'accountability', 'cfm', 'information management', 'ادارة المعلومات', 'جمع بيانات', 'enumerator', 'تحليل بيانات', 'data analyst', 'data collector', 'tpm', 'third party monitoring']],
  ['protection', 'حماية وإدارة حالة', ['حمايه', 'حمايه الطفل', 'العنف القايم', 'اداره الحاله', 'دعم نفسي', 'نفسي اجتماعي', 'اخصايي اجتماعي', 'protection', 'child protection', 'gbv', 'case manag', 'psychosocial', 'pss', 'mhpss', 'social worker', 'safeguarding', 'legal aid', 'gender']],
  ['health', 'صحة وتغذية', ['طبيب', 'طبيبه', 'ممرض', 'ممرضه', 'تمريض', 'قابله', 'صيدل', 'مخبر', 'اشعه', 'تخدير', 'صحه', 'صحي', 'تغذيه', 'مشفي', 'مستشفي', 'مستوصف', 'عياده', 'اسعاف', 'جراح', 'doctor', 'physician', 'nurse', 'midwi', 'pharmac', 'laborator', 'radiolog', 'health', 'medical', 'nutrition', 'clinic', 'hospital', 'anesthe', 'surgeon', 'paramedic', 'dentist', 'طب اسنان', 'epidemiolog', 'vaccin', 'لقاح']],
  ['wash', 'مياه وإصحاح WASH', ['مياه', 'اصحاح', 'صرف صحي', 'نظافه', 'wash', 'water', 'sanitation', 'hygiene']],
  ['livelihoods', 'سبل عيش وأمن غذائي وزراعة', ['سبل العيش', 'سبل عيش', 'امن غذايي', 'زراع', 'بيطر', 'ثروه حيوانيه', 'مهندس زراعي', 'livelihood', 'food security', 'fsl', 'agricultur', 'agronom', 'veterinar', 'livestock', 'cash transfer', 'cash assistance', 'cva', 'economic recovery', 'early recovery', 'small business', 'تمكين اقتصادي']],
  ['education', 'تعليم', ['تعليم', 'معلم', 'مدرس', 'مدرسه', 'تربوي', 'روضه', 'مرشد نفسي', 'teacher', 'education', 'tutor', 'school', 'learning', 'instructor', 'facilitator', 'ميسر', 'محو اميه']],
  ['engineering', 'هندسة ومأوى وإعادة تأهيل', ['مهندس', 'هندس', 'مشرف موقع', 'مراقب فني', 'اعاده تاهيل', 'ترميم', 'انشاء', 'مساح', 'كهربا', 'ميكانيك', 'مهندس مدني', 'هندسه مدنيه', 'معماري', 'engineer', 'engineering', 'shelter', 'rehabilitation', 'construction', 'site supervisor', 'surveyor', 'civil engineer', 'mechanical', 'electrical', 'architect', 'infrastructure', 'quantity', 'autocad', 'nfi']],
  ['oil', 'نفط وطاقة', ['نفط', 'بترول', 'الغاز', 'غاز طبيعي', 'الطاقه', 'طاقه شمسيه', 'حقول', 'مصفاه', 'oil', 'petroleum', 'gas', 'energy', 'solar', 'refiner', 'drilling', 'حفر ابار', 'power plant', 'محطه توليد', 'hse', 'سلامه مهنيه']],
  ['it', 'تقنية معلومات واتصالات وبرمجة', ['تقنيه معلومات', 'برمج', 'مطور', 'شبكات', 'دعم فني', 'اتصالات', 'حاسوب', 'كمبيوتر', 'it officer', 'it assistant', 'ict', 'developer', 'software', 'programmer', 'network', 'system admin', 'web', 'frontend', 'front-end', 'backend', 'back-end', 'full stack', 'flutter', 'android', 'ios', 'devops', 'database', 'cyber', 'ui/ux', 'graphic design', 'مصمم جرافيك', 'تصميم']],
  ['management', 'إدارة برامج وتنسيق', ['منسق', 'مدير مشروع', 'مدير برنامج', 'مدير برامج', 'مدير منطقه', 'مدير مكتب', 'اداره مشاريع', 'شراكات', 'منح', 'coordinator', 'project manager', 'programme manager', 'program manager', 'area manager', 'country director', 'head of', 'team leader', 'team lead', 'partnership', 'grants', 'business development', 'proposal', 'director', 'project officer', 'program officer', 'programme officer']],
  ['media', 'إعلام وتواصل وترجمة', ['اعلام', 'صحفي', 'مسؤول تواصل', 'التواصل الخارجي', 'علاقات عامه', 'تصوير', 'مونتاج', 'صانع محتوي', 'كاتب محتوي', 'ترجم', 'مترجم', 'مناصره', 'communications officer', 'communication officer', 'communications manager', 'media', 'journalist', 'photograph', 'video', 'content creator', 'content writer', 'translat', 'interpret', 'advocacy', 'public relation', 'social media', 'marketing']],
  ['legal', 'قانون', ['محامي', 'قانوني', 'قانونيه', 'حقوق', 'lawyer', 'legal', 'law$', 'compliance', 'امتثال']],
  ['drivers', 'سائقون وحراسة وخدمات', ['سايق', 'حارس', 'حراسه', 'عامل نظافه', 'عمال', 'طباخ', 'فني صيانه', 'كهربايي', 'سباك', 'driver', 'guard', 'cleaner', 'security officer', 'safety and security', 'cook', 'maintenance', 'technician', 'mechanic', 'electrician', 'plumber', 'امن وسلامه']],
  ['sales', 'مبيعات وتسويق وخدمة عملاء', ['مبيعات', 'تسويق', 'مندوب', 'خدمه عملاء', 'كاشير', 'بايع', 'sales', 'customer service', 'call center', 'مركز اتصال', 'telesales', 'retail']],
  ['training', 'تدريب ومنح وتطوع', ['تدريب', 'منحه', 'منح دراسيه', 'تطوع', 'متطوع', 'دوره', 'دورات', 'internship', 'intern$', 'scholarship', 'fellowship', 'volunteer', 'training', 'course', 'bootcamp', 'زماله']],
].map(([id, name, kw]) => ({ id, name, re: rx(kw) }));

// ===== المحافظات =====
export const GOVS = [
  ['damascus', 'دمشق', ['damascus', 'dimashq']],
  ['rifdimashq', 'ريف دمشق', ['ريف دمشق', 'rural damascus', 'rif dimashq', 'rif damascus', 'دوما', 'جرمانا', 'صحنايا', 'قدسيا', 'الزبداني', 'يبرود', 'النبك', 'الكسوه', 'داريا', 'المعضميه', 'عربين', 'douma', 'jaramana', 'zabadani', 'darayya']],
  ['aleppo', 'حلب', ['حلب', 'aleppo', 'halab', 'منبج', 'عفرين', 'اعزاز', 'مدينه الباب', 'جرابلس', 'كوباني', 'عين العرب', 'السفيره', 'مارع', 'الاتارب', 'دارة عزة', 'manbij', 'afrin', 'azaz', 'a\'zaz', 'al-bab', 'al bab', 'jarablus', 'kobani', 'kobane', 'atareb']],
  ['idlib', 'إدلب', ['ادلب', 'idlib', 'idleb', 'سرمدا', 'الدانا', 'معره مصرين', 'اريحا', 'جسر الشغور', 'حارم', 'سلقين', 'كفر تخاريم', 'اطمه', 'معرة النعمان', 'sarmada', 'dana', 'harem', 'harim', 'salqin', 'atmeh', 'jisr']],
  ['homs', 'حمص', ['حمص', 'homs', 'تدمر', 'القصير', 'الرستن', 'تلكلخ', 'تلبيسه', 'المخرم', 'palmyra', 'tadmur', 'rastan', 'qusayr']],
  ['hama', 'حماة', ['حماه', 'hama', 'hamah', 'مدينه السلميه', 'مصياف', 'محرده', 'السقيلبيه', 'صوران', 'salamiyah', 'salamieh', 'masyaf', 'mhardeh']],
  ['latakia', 'اللاذقية', ['اللاذقيه', 'لاذقيه', 'latakia', 'lattakia', 'jableh', 'جبله', 'القرداحه', 'الحفه']],
  ['tartus', 'طرطوس', ['طرطوس', 'tartus', 'tartous', 'بانياس', 'صافيتا', 'الدريكيش', 'banias', 'baniyas', 'safita']],
  ['daraa', 'درعا', ['درعا', 'daraa', 'dara\'a', 'deraa', 'مدينه نوي', 'بصري الشام', 'مدينه ازرع', 'الصنمين', 'طفس', 'nawa', 'bosra', 'izraa']],
  ['sweida', 'السويداء', ['السويداء', 'سويداء', 'sweida', 'suwayda', 'suwaida', 'swaida', 'as-sweida', 'شهبا', 'صلخد', 'shahba']],
  ['quneitra', 'القنيطرة', ['القنيطره', 'quneitra', 'qunaitra', 'خان ارنبه']],
  ['deirezzor', 'دير الزور', ['دير الزور', 'ديرالزور', 'دير زور', 'deir ez-zor', 'deir ezzor', 'deir-ez-zor', 'deir al-zour', 'deir al zour', 'deir ez zor', 'deirezzor', 'deir-ezzor', 'der ezzor', 'dez', 'الميادين', 'البوكمال', 'هجين', 'الشحيل', 'البصيره', 'العشاره', 'ذيبان', 'الكسره', 'غرانيج', 'الجرذي', 'المريعيه', 'mayadin', 'mayadeen', 'bukamal', 'abu kamal', 'albukamal', 'hajin', 'busayrah', 'thiban', 'diban', 'shuhail', 'kasrah']],
  ['raqqa', 'الرقة', ['الرقه', 'رقه', 'raqqa', 'raqqah', 'rakka', 'الطبقه', 'عين عيسي', 'المنصوره', 'تل ابيض', 'tabqa', 'tabqah', 'ain issa', 'tal abyad', 'tell abyad']],
  ['hasakah', 'الحسكة', ['الحسكه', 'حسكه', 'hasakah', 'hassakeh', 'hasaka', 'al-hasakah', 'hassake', 'hasakeh', 'القامشلي', 'قامشلي', 'عامودا', 'المالكيه', 'ديريك', 'راس العين', 'الشدادي', 'الهول', 'تل تمر', 'الدرباسيه', 'القحطانيه', 'qamishli', 'qamishly', 'qamishlo', 'amuda', 'derik', 'malikiyah', 'ras al-ain', 'ras al ain', 'shaddadi', 'al-hol', 'al hol', 'tal tamer']],
].map(([id, name, kw]) => ({ id, name, re: rx(kw) }));

const DAMASCUS_ONLY = /(?<!ريف\s{0,3})دمشق|(?<!rural\s{0,3})(?<!rif\s{0,3})\bdamascus/i;
const NES = rx(['شمال شرق سوريا', 'شمال شرقي سوريا', 'شمال وشرق سوريا', 'north east syria', 'northeast syria', 'north-east syria', 'nes$', 'ne syria', 'الجزيره السوريه']);
const NWS = rx(['شمال غرب سوريا', 'شمال غربي سوريا', 'north west syria', 'northwest syria', 'north-west syria', 'nws$', 'nw syria']);
const ALL_SYRIA = rx(['جميع المحافظات', 'كافه المحافظات', 'كل المحافظات', 'all governorates', 'various locations', 'multiple locations', 'جميع المناطق السوريه']);
const REMOTE = rx(['عن بعد', 'من المنزل', 'اونلاين', 'remote', 'work from home', 'online', 'freelance', 'فري لانس', 'عمل حر']);
const ABROAD = rx(['غازي عنتاب', 'عنتاب', 'gaziantep', 'antep', 'تركيا', 'turkey', 'türkiye', 'turkiye', 'istanbul', 'اسطنبول', 'هاتاي', 'hatay', 'اورفا', 'urfa', 'اربيل', 'erbil', 'العراق', 'iraq', 'amman', 'الاردن', 'jordan', 'لبنان', 'lebanon', 'بيروت', 'beirut', 'السعوديه', 'saudi', 'الامارات', 'دبي', 'dubai', 'uae', 'قطر', 'qatar', 'الكويت', 'kuwait', 'المانيا', 'germany', 'europe', 'اوروبا', 'مصر', 'egypt']);

// ===== نوع الجهة =====
const UN = rx(['الامم المتحده', 'unicef', 'يونيسف', 'اليونيسف', 'unhcr', 'المفوضيه الساميه', 'wfp', 'برنامج الاغذيه', 'undp', 'unfpa', 'who', 'world health organization', 'منظمه الصحه العالميه', 'iom', 'المنظمه الدوليه للهجره', 'ocha', 'unops', 'fao', 'الفاو', 'unrwa', 'الاونروا', 'un-habitat', 'unhabitat', 'unesco', 'unmas', 'united nations', 'ohchr', 'undss', 'un women', 'ilo', 'منظمه العمل الدوليه']);
const NGO = rx(['منظمه', 'جمعيه', 'مؤسسه خيريه', 'هيئه الاغاثه', 'الهلال الاحمر', 'الصليب الاحمر', 'ngo', 'ingo', 'foundation', 'relief', 'humanitarian', 'انساني', 'انسانيه', 'charity', 'international rescue', 'irc', 'nrc', 'norwegian refugee', 'drc', 'danish refugee', 'mercy corps', 'save the children', 'care international', 'world vision', 'oxfam', 'concern worldwide', 'medair', 'msf', 'medecins', 'imc', 'international medical corps', 'people in need', 'pin', 'blumont', 'sams', 'syria relief', 'violet', 'بنفسج', 'shafak', 'شفق', 'bahar', 'بهار', 'ihh', 'goal', 'acted', 'reach initiative', 'impact', 'solidarites', 'premiere urgence', 'acf', 'action against hunger', 'handicap international', 'humanity & inclusion', 'icrc', 'sarc', 'sarc', 'الامانه السوريه للتنميه', 'syria trust', 'creative associates', 'chemonics', 'proximity', 'giz', 'tdh', 'terre des hommes', 'ghiras', 'غراس', 'hihfad', 'حراس', 'hurras', 'qatar charity', 'قطر الخيريه', 'إحسان', 'ihsan', 'mercy-usa', 'uossm', 'syrian forum', 'المنتدي السوري', 'kesk', 'women now', 'ثمار', 'سند', 'non-governmental', 'غير حكوميه', 'nonprofit', 'non-profit']);
const GOV = rx(['وزاره', 'مديريه', 'مكتب المحافظ', 'مسابقه', 'الجهات العامه', 'الجهات الحكوميه', 'الموسسه العامه', 'المؤسسه العامه', 'الشركه العامه', 'الشركه السوريه للنفط', 'مجلس مدينه', 'مجلس محافظه', 'الاداره الذاتيه', 'الاداره المدنيه', 'جامعه دمشق', 'جامعه حلب', 'جامعه الفرات', 'جامعه تشرين', 'جامعه البعث', 'جامعه حمص', 'جامعه حماه', 'مرسوم', 'الحكومه', 'جهه حكوميه', 'ministry', 'directorate', 'municipality', 'government', 'public sector', 'القطاع العام', 'وظيفه حكوميه', 'دوايير الدوله', 'دوائر الدوله', 'مكتب التشغيل']);
const PRIVATE = rx(['شركه', 'مؤسسه', 'مطعم', 'مكتب', 'معمل', 'مصنع', 'متجر', 'صالون', 'مركز', 'فندق', 'مدرسه خاصه', 'مشفي خاص', 'company', 'co.', 'ltd', 'llc', 'group', 'agency', 'startup', 'factory', 'restaurant', 'hotel', 'store', 'shop', 'bank', 'بنك', 'مصرف']);
export const SECTORS = [['un', 'أمم متحدة'], ['ngo', 'منظمات'], ['gov', 'حكومي'], ['private', 'قطاع خاص'], ['unknown', 'غير محدد']];

// ===== هل هو إعلان وظيفة؟ =====
const JOBWORDS = rx(['وظيف', 'شاغر', 'توظيف', 'فرصه عمل', 'فرص عمل', 'فرصه', 'مطلوب', 'تعلن', 'يعلن', 'نبحث عن', 'بحاجه الي', 'عن حاجتها', 'للتقديم', 'التقديم', 'قدم الان', 'السيره الذاتيه', 'سيره ذاتيه', 'الراتب', 'دوام', 'تدريب', 'منحه', 'تطوع', 'مسابقه', 'تعيين', 'vacanc', 'job', 'jobs', 'position', 'hiring', 'we are looking', 'looking for', 'apply', 'deadline', 'closing date', 'cv', 'resume', 'recruit', 'consultan', 'internship', 'scholarship', 'fellowship', 'volunteer', 'role', 'opportunit', 'tender', 'مناقصه', 'عرض سعر', 'rfq', 'rfp']);
const TENDER = rx(['مناقصه', 'عروض اسعار', 'طلب عروض', 'tender', 'rfq', 'rfp', 'request for quotation', 'request for proposal', 'invitation to bid', 'استدراج عروض']);

// ===== التواريخ =====
const AR_MONTHS = [
  ['كانون الثاني', 'يناير', 'جانفي'], ['شباط', 'فبراير', 'فيفري'], ['اذار', 'مارس'], ['نيسان', 'ابريل', 'افريل'], ['ايار', 'مايو', 'ماي'], ['حزيران', 'يونيو', 'جوان'],
  ['تموز', 'يوليو', 'جويليه'], ['اب', 'اغسطس', 'اوت'], ['ايلول', 'سبتمبر'], ['تشرين الاول', 'اكتوبر'], ['تشرين الثاني', 'نوفمبر'], ['كانون الاول', 'ديسمبر'],
];
const EN_MONTHS = ['jan', 'feb', 'mar', 'apr', 'may', 'jun', 'jul', 'aug', 'sep', 'oct', 'nov', 'dec'];
function monthOf(w) {
  w = norm(w).trim();
  const en = EN_MONTHS.findIndex(m => w.startsWith(m));
  if (en >= 0 && /^[a-z]/.test(w)) return en + 1;
  for (let i = 0; i < 12; i++) for (const n of AR_MONTHS[i]) if (w === norm(n) || w === 'ال' + norm(n)) return i + 1;
  return 0;
}
const AR_MONTH_RX = AR_MONTHS.flat().map(norm).sort((a, b) => b.length - a.length).join('|');

function mk(y, m, d) {
  if (y < 100) y += 2000;
  if (m < 1 || m > 12 || d < 1 || d > 31) return null;
  const dt = new Date(Date.UTC(y, m - 1, d));
  if (dt.getUTCMonth() !== m - 1) return null;
  return dt;
}
function iso(dt) { return dt.toISOString().slice(0, 10); }

/** يقرأ أول تاريخ في نص قصير. now: Date */
export function parseDate(s, now) {
  const t = norm(s);
  let m;
  // 2026-11-30 / 2026/11/30
  if ((m = t.match(/(20\d\d)[\/\-.](\d{1,2})[\/\-.](\d{1,2})/))) return mk(+m[1], +m[2], +m[3]);
  // 30/11/2026  30-11-26  30.11.2026
  if ((m = t.match(/(\d{1,2})\s*[\/\-.]\s*(\d{1,2})\s*[\/\-.]\s*(20\d\d|\d\d)(?!\d)/))) {
    let a = +m[1], b = +m[2];
    if (b > 12 && a <= 12) [a, b] = [b, a];   // صيغة أمريكية
    return mk(+m[3], b, a);
  }
  // 30 November 2026 / 30 تشرين الثاني 2026 / 30 Nov
  const reDMY = new RegExp(`(\\d{1,2})\\s*(?:st|nd|rd|th)?\\s*(?:of\\s+)?(?:من\\s+)?(?:شهر\\s+)?([a-z]{3,9}|${AR_MONTH_RX})\\.?\\s*,?\\s*(20\\d\\d)?`);
  if ((m = t.match(reDMY))) {
    const mo = monthOf(m[2]);
    if (mo) return withYear(m[3], mo, +m[1], now);
  }
  // November 30, 2026
  if ((m = t.match(/\b([a-z]{3,9})\.?\s+(\d{1,2})(?:st|nd|rd|th)?\s*,?\s*(20\d\d)?/))) {
    const mo = monthOf(m[1]);
    if (mo) return withYear(m[3], mo, +m[2], now);
  }
  // 30/11 بدون سنة
  if ((m = t.match(/(?<![\d\/])(\d{1,2})\s*\/\s*(\d{1,2})(?![\d\/])/))) {
    let a = +m[1], b = +m[2];
    if (b > 12 && a <= 12) [a, b] = [b, a];
    return withYear(null, b, a, now);
  }
  return null;
}
function withYear(y, mo, d, now) {
  if (y) return mk(+y, mo, d);
  let dt = mk(now.getUTCFullYear(), mo, d);
  if (dt && dt.getTime() < now.getTime() - 60 * 864e5) dt = mk(now.getUTCFullYear() + 1, mo, d);
  return dt;
}

const DEADLINE_KW = /(اخر موعد|اخر يوم|اخر تاريخ|موعد الاغلاق|تاريخ الاغلاق|موعد اغلاق|تاريخ انتهاء|ينتهي التقديم|يغلق التقديم|اغلاق التقديم|التقديم متاح حتي|التقديم حتي|لغايه|حتي تاريخ|حتي يوم|المهله|deadline|closing date|close date|closing on|closes on|apply before|apply by|last date|application deadline|applications close|end of application|end of applications|due date|end date|expiry date|expires|valid until|open until)/g;

export function findDeadline(text, now) {
  const t = norm(text);
  let best = null, m;
  DEADLINE_KW.lastIndex = 0;
  while ((m = DEADLINE_KW.exec(t))) {
    const seg = t.slice(m.index + m[0].length, m.index + m[0].length + 70);
    const d = parseDate(seg, now);
    if (d && plausible(d, now)) { best = d; break; }
  }
  return best ? iso(best) : '';
}
function plausible(d, now) {
  const diff = (d.getTime() - now.getTime()) / 864e5;
  return diff > -45 && diff < 240;
}

// ===== الروابط =====
const SOCIAL = /(t\.me|telegram\.(me|org)|facebook\.com|fb\.com|fb\.me|instagram\.com|whatsapp\.com|wa\.me|chat\.whatsapp|twitter\.com|x\.com|tiktok\.com|youtube\.com|youtu\.be|linkedin\.com\/(?!jobs))/i;
const APPLYISH = /(forms\.gle|docs\.google\.com\/forms|kobo|ee\.kobotoolbox|jotform|typeform|forms\.office|forms\.microsoft|apply|career|recruit|jobs?\b|vacanc|hr\.|bamboohr|workable|lever\.co|greenhouse|smartrecruiters|zoho|oracle|successfactors|taleo|inspira|reliefweb|untalent|impactpool|devex|bayt\.com|linkedin\.com\/jobs|wuzzuf)/i;

export function findApply(text, links = []) {
  const all = [...links, ...(String(text).match(/https?:\/\/[^\s<>"'()\]]+/g) || [])]
    .map(u => u.replace(/[.,،؛;:!?)]+$/, ''))
    .filter(u => /^https?:\/\//i.test(u));
  const nonSocial = all.filter(u => !SOCIAL.test(u));
  const best = nonSocial.find(u => APPLYISH.test(u)) || nonSocial[0];
  if (best) return best;
  const mail = String(text).match(/[\w.+-]+@[\w-]+\.[\w.-]+/);
  return mail ? 'mailto:' + mail[0] : '';
}

// ===== المسمى والجهة =====
const GENERIC_LINE = rx(['اعلان وظيفه', 'اعلان عن', 'اعلان توظيف', 'فرصه عمل', 'فرص عمل', 'شاغر وظيفي', 'وظيفه شاغره', 'وظايف شاغره', 'مطلوب للعمل', 'vacancy announcement', 'job announcement', 'job opportunity', 'job vacancy', 'we are hiring', 'hiring', 'new job', 'وظيفه جديده', 'عاجل', 'urgent', 'فرصه', 'رابط التقديم', 'للتقديم']);
const TITLE_LABEL = /(?:^|\n)\s*[^\n\w؀-ۿ]{0,4}\s*(?:المسمي الوظيفي|المسمي|عنوان الوظيفه|اسم الوظيفه|الوظيفه|الشاغر|المنصب|position title|position|job title|title|vacancy title|vacancy|role|post)\s*[:：\-–—]\s*([^\n]{3,140})/i;
const ORG_LABEL = /(?:^|\n)\s*[^\n\w؀-ۿ]{0,4}\s*(?:اسم المنظمه|المنظمه|الجهه|الجهه المعلنه|اسم الشركه|الشركه|المؤسسه|صاحب العمل|organization|organisation|company|employer|agency|hiring organization)\s*[:：\-–—]\s*([^\n]{2,80})/i;
const ORG_INLINE = /(?:ال)?(منظمه|جمعيه|مؤسسه|شركه|هيئه|مديريه|وزاره)\s+([^\n،,.:؛()\-–]{2,40})/;
const ANNOUNCE = /(?:تعلن|يعلن|ترغب|تبحث|يبحث)[^\n]{0,60}?(?:عن حاجتها|عن حاجته|عن توفر|عن وجود|عن فتح باب التقديم|عن|بحاجه)\s*(?:الي|ل|لـ|لشغل)?\s*(?:شاغر|وظيفه|منصب)?\s*([^\n]{3,90}?)(?=\s+(?:في|ب|لدي|ضمن|لمشروع|للعمل)\s|[.،,\n]|$)/;

function cleanLine(s) {
  return String(s || '')
    .replace(/[#*_~`|•●▪️◾◽▫️■□►▶➖➡️⬅️👇👈👉🔹🔸🔶🔷✅☑️✔️📌📍📢📣🔴🟢🔵⭕❗❕‼️💼🗓️📅⏰⏳🌐🔗✨⭐🌟]+/gu, ' ')
    .replace(/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/gu, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .replace(/^[\-–—:.\s]+|[\-–—:.\s]+$/g, '');
}

export function findTitle(text) {
  const raw = String(text);
  const n = norm(raw);
  let m = n.match(TITLE_LABEL);
  if (m) return pickRaw(raw, n, m.index + m[0].indexOf(m[1]), m[1].length);
  const lines = raw.split('\n');
  for (const ln of lines.slice(0, 6)) {
    const c = cleanLine(ln);
    if (c.length < 4 || c.length > 120) continue;
    if (/^https?:/i.test(c)) continue;
    const cn = norm(c);
    if (GENERIC_LINE.test(cn) && c.length < 45 && !SPECS.some(s => s.re.test(cn))) continue;
    if (/^(السلام|مرحبا|اهلا|صباح|مساء|hello|hi|dear)/i.test(cn)) continue;
    const an = cn.match(ANNOUNCE);
    if (an && an[1].length >= 3) return shortTitle(cleanLine(an[1]));
    return shortTitle(c);
  }
  m = n.match(ANNOUNCE);
  if (m) return pickRaw(raw, n, m.index + m[0].lastIndexOf(m[1]), m[1].length);
  return cleanLine(lines.find(l => cleanLine(l).length > 3) || '').slice(0, 100);
}
// norm لا يغيّر طول النص (استبدال حرف بحرف) عدا حذف التشكيل؛ لذلك نعيد القصّ على النص المطبّع ونرجع للأصل إن تطابق الطول.
function shortTitle(t) {
  if (t.length <= 80) return t;
  const cut = t.search(/\s(closing|deadline|apply|https?:|اخر موعد|آخر موعد|للتقديم)|\.\s/i);
  if (cut > 8) t = t.slice(0, cut);
  return t.length > 100 ? t.slice(0, 97).replace(/\s+\S*$/, '') + '…' : t;
}
function pickRaw(raw, n, start, len) {
  if (raw.length === n.length) return cleanLine(raw.substr(start, len));
  return cleanLine(n.substr(start, len));
}

export function findOrg(text, srcName) {
  const raw = String(text), n = norm(raw);
  let m = n.match(ORG_LABEL);
  if (m) return pickRaw(raw, n, m.index + m[0].indexOf(m[1]), m[1].length).slice(0, 70);
  m = n.match(ORG_INLINE);
  if (m) {
    const s = pickRaw(raw, n, m.index, m[0].length).split(/\s(?:عن|في|تعلن|يعلن|تبحث|بحاجه|بحاجة|لديها|ترغب|تطلب)\s/)[0];
    if (!/^(منظمه|جمعيه|مؤسسه|شركه|هيئه)\s+(دوليه|محليه|انسانيه|غير حكوميه|رايده|كبري|خاصه)$/.test(norm(s))) return s.slice(0, 60);
  }
  const en = raw.match(/\b(UNICEF|UNHCR|WFP|UNDP|UNFPA|WHO|IOM|OCHA|UNOPS|FAO|UNRWA|IRC|NRC|DRC|Mercy Corps|Save the Children|World Vision|Oxfam|CARE|Medair|MSF|IMC|People in Need|PIN|Blumont|SAMS|Syria Relief|Violet|Shafak|GOAL|ACTED|REACH|Concern|Ghiras|Hurras|IHH|Qatar Charity|SARC|ICRC|Syria Trust|Chemonics|Creative Associates|Proximity|GIZ|Tdh|Handicap International|Humanity & Inclusion|Action Against Hunger|Solidarites International|Premiere Urgence|UOSSM|SEMA|SAMS|Bahar|Ihsan|Takaful Al Sham|Hand in Hand for Aid and Development|HIHFAD|Syrian Forum|Women Now|Sened|Shafak)\b/);
  if (en) return en[1];
  return '';
}

// ===== التصنيف الكامل =====
export function classify(text, opts = {}) {
  const now = opts.now || new Date();
  const raw = String(text || '').slice(0, 6000).replace(/\s*\/\s*(ة|ه|ت|ين|ات)(?=[\s\-–,،.]|$)/g, '');
  const n = ' ' + norm(raw).replace(/\s+/g, ' ') + ' ';
  const head = ' ' + norm(raw.slice(0, 700)).replace(/\s+/g, ' ') + ' ';

  const isJob = opts.force || (raw.length >= 40 && JOBWORDS.test(n));
  const title = findTitle(raw).slice(0, 140);
  const titleN = ' ' + norm(title) + ' ';

  // الاختصاص: من المسمى أولاً، ثم من أول النص
  let specs = SPECS.filter(s => s.re.test(titleN)).map(s => s.id);
  if (!specs.length) specs = SPECS.filter(s => s.re.test(head)).map(s => s.id).slice(0, 3);
  else if (!specs.includes('oil') && SPECS.find(s => s.id === 'oil').re.test(head)) specs.push('oil');
  if (TENDER.test(head)) specs = ['tender'];
  if (!specs.length) specs = ['other'];

  // المحافظات
  let govs = GOVS.filter(g => g.id !== 'damascus' && g.re.test(n)).map(g => g.id);
  if (DAMASCUS_ONLY.test(n)) govs.unshift('damascus');
  if (NES.test(n)) for (const g of ['deirezzor', 'raqqa', 'hasakah']) if (!govs.includes(g)) govs.push(g);
  if (NWS.test(n)) for (const g of ['idlib', 'aleppo']) if (!govs.includes(g)) govs.push(g);
  if (ALL_SYRIA.test(n)) govs.push('all');
  if (REMOTE.test(n)) govs.push('remote');
  if (!govs.length && ABROAD.test(n)) govs.push('abroad');
  govs = [...new Set(govs)];
  if (!govs.length) govs = ['unknown'];

  // نوع الجهة
  const org = findOrg(raw, opts.srcName || '');
  const orgN = ' ' + norm(org) + ' ';
  let sector = 'unknown';
  const probe = orgN + head;
  if (/غير\s*(ال)?حكومي|non-governmental|nongovernmental/.test(probe) && !UN.test(probe)) sector = 'ngo';
  else if (UN.test(probe)) sector = 'un';
  else if (GOV.test(probe)) sector = 'gov';
  else if (NGO.test(probe)) sector = 'ngo';
  else if (PRIVATE.test(probe)) sector = 'private';
  if (opts.sectorHint && sector === 'unknown') sector = opts.sectorHint;

  return {
    isJob: !!isJob,
    title: title || 'فرصة عمل',
    org,
    specs,
    govs,
    sector,
    deadline: opts.deadline || findDeadline(raw, now),
    apply: findApply(raw, opts.links || []),
  };
}

// مفتاح كشف التكرار
export function dupKeys(c, text) {
  const t = norm(c.title).replace(/[^a-z0-9ء-ي]+/g, '');
  const o = norm(c.org).replace(/[^a-z0-9ء-ي]+/g, '');
  const body = norm(text).replace(/https?:\/\/\S+/g, '').replace(/[^a-z0-9ء-ي]+/g, '').slice(0, 260);
  const k1 = t && o ? 'to:' + t.slice(0, 60) + '|' + o.slice(0, 30) + '|' + (c.deadline || '') : 'b:' + body;
  const k2 = c.apply && !/^mailto:/.test(c.apply) ? 'a:' + c.apply.replace(/^https?:\/\/(www\.)?/, '').replace(/[?#].*$/, '').replace(/\/$/, '').toLowerCase() + '|' + t.slice(0, 30) : '';
  return { k1, k2 };
}

export const LABELS = {
  specs: Object.fromEntries([...SPECS.map(s => [s.id, s.name]), ['tender', 'مناقصات وعروض أسعار'], ['other', 'غير مصنف']]),
  govs: Object.fromEntries([...GOVS.map(g => [g.id, g.name]), ['all', 'كل سوريا'], ['remote', 'عن بعد'], ['abroad', 'خارج سوريا'], ['unknown', 'غير محدد']]),
  sectors: Object.fromEntries(SECTORS),
};
