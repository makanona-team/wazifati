#!/bin/bash
# يعيد كتابة صفحات الإصدارات السابقة بقسم «للمستخدمين» فقط
cd "$(dirname "$0")/.."
for f in changelogs/*.md; do
  v=$(basename "$f" .md)
  id=$(gh api "repos/$GITHUB_REPOSITORY/releases/tags/v$v" --jq .id 2>/dev/null) || continue
  [ -z "$id" ] && continue
  u=$(awk '/^## للمستخدمين/{f=1;next} /^## /{f=0} f' "$f" | sed '/^[[:space:]]*$/d')
  [ -z "$u" ] && u="- تحسينات في الأداء والاستقرار"
  body="$u

---
**التثبيت:** حمّل \`Wazifati-$v.apk\` من الأسفل وافتحه على الهاتف. التحديثات التالية من داخل التطبيق."
  gh api -X PATCH "repos/$GITHUB_REPOSITORY/releases/$id" -f body="$body" --jq '.tag_name' >/dev/null && echo "✓ v$v"
done
