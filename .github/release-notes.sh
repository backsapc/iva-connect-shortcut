#!/usr/bin/env bash
# Prints the GitHub Release description for a version.
# Usage: release-notes.sh <version> <apk>     (APKSIGNER may point to apksigner)
set -euo pipefail

version="$1"
apk="$2"
root="$(cd "$(dirname "$0")/.." && pwd)"
name="$(basename "$apk")"

# "## [2.2] - date" ... up to the next "## [" or the link references at the bottom
notes="$(awk -v v="$version" '
  $0 ~ "^## \\[" v "\\]" { on = 1; next }
  on && (/^## \[/ || /^\[[^]]+\]: /) { exit }
  on { print }
' "$root/CHANGELOG.md")"
if [ -z "$(printf '%s' "$notes" | tr -d '[:space:]')" ]; then
  echo "CHANGELOG.md has no section for $version" >&2
  exit 1
fi

if command -v sha256sum >/dev/null; then sum="$(sha256sum "$apk" | cut -d' ' -f1)"; else sum="$(shasum -a 256 "$apk" | cut -d' ' -f1)"; fi

signer="${APKSIGNER:-}"
if [ -z "$signer" ] && [ -n "${ANDROID_HOME:-}" ]; then signer="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)/apksigner"; fi
cert="$("$signer" verify --print-certs "$apk" | grep -m1 'certificate SHA-256 digest' | awk '{print $NF}' \
  | tr '[:lower:]' '[:upper:]' | sed 's/../&:/g; s/:$//')"

cat <<EOF
$(printf '%s\n' "$notes" | sed -e '/./,$!d')

### Установка

1. Скачайте **$name** ниже и откройте его на телефоне.
2. Настройки → Приложения → IVA Автовход → ⋮ → **Разрешить ограниченные настройки**.
3. Настройки → Специальные возможности → Установленные приложения → **IVA Автовход** → включить.

Новые версии ставятся поверх старых, настройки и пароли сохраняются.

**Нужно:** Android 8.0 или новее и установленный IVA Connect.

### Проверка файла

SHA-256 файла $name:
\`\`\`
$sum
\`\`\`

Сертификат подписи (SHA-256), одинаковый у всех выпусков:
\`\`\`
$cert
\`\`\`

Все изменения: [CHANGELOG.md](https://github.com/backsapc/iva-connect-shortcut/blob/main/CHANGELOG.md)
EOF
