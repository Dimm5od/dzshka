#!/usr/bin/env bash
# Выложить текущую сборку как обновление: копирует APK в репозиторий обновлений и пишет update.json.
# Использование: tools/publish.sh "что нового"
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO="${UPDATES_REPO:-/home/claude/dzshka-updates}"
NOTES="${1:-}"
CODE=$(grep -oP 'versionCode = \K[0-9]+' "$ROOT/app/build.gradle.kts")
NAME=$(grep -oP 'versionName = "\K[^"]+' "$ROOT/app/build.gradle.kts")
FULL="$ROOT/out/DZshka-$NAME.apk"
HELP="$ROOT/out/DZshka-Pomoshnik-$NAME.apk"
test -f "$FULL" && test -f "$HELP"
cd "$REPO"
git rm -q --ignore-unmatch 'DZshka-*.apk' >/dev/null 2>&1 || true
cp "$FULL" "$HELP" .
rel() { printf '{"code":%s,"name":"%s","apk":"%s","sha256":"%s","size":%s,"notes":%s}' \
  "$CODE" "$2" "$(basename "$1")" "$(sha256sum "$1" | cut -d' ' -f1)" "$(stat -c%s "$1")" "$(python3 -c 'import json,sys;print(json.dumps(sys.argv[1]))' "$NOTES")"; }
printf '{"full":%s,"helper":%s}\n' "$(rel "$FULL" "$NAME")" "$(rel "$HELP" "$NAME-helper")" > update.json
git add -A
git commit -qm "ДЗшка $NAME" 
git push -q origin HEAD:main
echo "Выложено: $NAME (code $CODE)"
