// صفحات عامة: سياسة الخصوصية وشروط الاستخدام (مطلوبة لنشر الدخول بحساب Google).
const wrap = (title, body) => `<!doctype html><html lang="ar" dir="rtl"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${title} — وظيفتي</title>
<style>body{font-family:system-ui,sans-serif;max-width:760px;margin:auto;padding:20px;line-height:1.8;color:#1c2333;background:#f4f6fb}h1{color:#1565C0}h2{margin-top:24px}.en{direction:ltr;text-align:left;border-top:1px solid #ccd;margin-top:30px;padding-top:10px}</style>
<body>${body}<p><a href="/">وظيفتي — Wazifati</a></p></body></html>`;

export const PRIVACY = wrap('سياسة الخصوصية', `<h1>سياسة الخصوصية — تطبيق وظيفتي</h1>
<p>وظيفتي تطبيق مجاني يجمع إعلانات الوظائف العامة في سوريا ويرسل للمستخدم إشعارات بما يناسبه.</p>
<h2>ما نجمعه</h2><ul>
<li>عند الدخول بحساب Google: الاسم والبريد الإلكتروني وصورة الحساب فقط، لتعريفك داخل التطبيق.</li>
<li>إعداداتك داخل التطبيق: الاختصاصات والمحافظات المختارة، الفرص المحفوظة، وإعدادات الإشعارات، لتعود إليك على أي هاتف.</li>
<li>ما تشاركه أو تقترحه بنفسك (إعلانات وظائف، مصادر، تصحيحات).</li>
<li>نوع الهاتف وإصدار التطبيق لأغراض الدعم الفني.</li></ul>
<h2>ما لا نفعله</h2><ul><li>لا نبيع بياناتك ولا نشاركها مع أي جهة.</li><li>لا نصل إلى بريدك أو جهات اتصالك أو ملفاتك.</li><li>لا إعلانات تجارية داخل التطبيق.</li></ul>
<h2>التخزين والحذف</h2><p>تُحفظ البيانات على خوادم Cloudflare. يمكنك طلب حذف حسابك وبياناتك بالتواصل مع المطوّر: eng.feras8585@gmail.com</p>
<div class="en"><h2>Privacy Policy — Wazifati</h2><p>Wazifati is a free app that collects public job announcements in Syria and notifies users about matching jobs. On Google sign-in we receive only your name, email address and profile photo to identify you in the app. We store your in-app settings (chosen specialties and regions, saved jobs, notification settings) and content you choose to share. We do not sell or share your data, and we do not access your email, contacts or files. Data is stored on Cloudflare. To delete your account and data, contact eng.feras8585@gmail.com.</p></div>`);

export const TERMS = wrap('شروط الاستخدام', `<h1>شروط الاستخدام — تطبيق وظيفتي</h1>
<ul><li>التطبيق مجاني، ويعرض إعلانات وظائف منشورة علناً في مصادرها الأصلية مع رابط المصدر.</li>
<li>لا نضمن صحة الإعلانات أو استمرارها؛ تحقّق دائماً من الجهة المعلنة قبل التقديم ولا تدفع أي مبلغ مقابل وظيفة.</li>
<li>يُمنع نشر إعلانات وهمية أو مسيئة عبر المشاركة؛ ويحق للإدارة إيقاف أي حساب مخالف.</li>
<li>للتواصل: eng.feras8585@gmail.com</li></ul>
<div class="en"><h2>Terms of Service — Wazifati</h2><p>Wazifati is free and shows job announcements publicly posted by their original sources, with links to them. We do not guarantee their accuracy; always verify the employer and never pay for a job. Posting fake or abusive content is prohibited and may lead to account suspension. Contact: eng.feras8585@gmail.com</p></div>`);
