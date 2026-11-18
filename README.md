# وظيفتي (Wazifati)

مساعد البحث عن الوظائف في سوريا: يجلب الفرص من قنوات تيليغرام ومواقع الوظائف كل ساعة، يصنّفها (الاختصاص، المحافظة، نوع الجهة، موعد الإغلاق)، ويرسل إشعاراً بما يطابق فلترك.

- التحميل: [آخر إصدار](https://github.com/makanona-team/wazifati/releases/latest)
- `worker/` خادم Cloudflare (Durable Object + SQLite) — يُنشر تلقائياً عند تغييره.
- `scraper/` الجالب (بايثون) — يعمل كل ساعة على GitHub Actions.
- `app/` تطبيق أندرويد — يُبنى ويُنشر عند تغيير `versionName` في `app/build.gradle`، وملاحظاته في `changelogs/<الإصدار>.md`.
- سجلات آخر تشغيل: الفروع `build-logs` و`deploy-logs` و`scrape-logs`.

الأسرار المطلوبة في المستودع: `SIGN_PASS`، `CLOUDFLARE_API_TOKEN`، `CLOUDFLARE_ACCOUNT_ID`.
