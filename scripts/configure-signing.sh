#!/usr/bin/env bash
# Run once on a trusted machine with JDK 17+, gh and authenticated qlolp access.
set -euo pipefail
umask 077
repo=qlolp/TgWatch
signing_dir=${1:?Usage: ./scripts/configure-signing.sh /secure/backup-directory}
mkdir -p "$signing_dir"
store="$signing_dir/tgwatch-release.p12"
password_file="$signing_dir/tgwatch-signing-password.txt"
if [[ ! -f "$store" ]]; then
  if [[ -e "$password_file" ]]; then
    echo 'Password file exists without keystore; restore the key backup before continuing.' >&2
    exit 1
  fi
  openssl rand -base64 36 > "$password_file"
  keytool -genkeypair -keystore "$store" -storetype PKCS12 \
    -storepass:file "$password_file" -keypass:file "$password_file" \
    -alias tgwatch -keyalg RSA -keysize 3072 -validity 10000 \
    -dname 'CN=TG Watch, OU=Android, O=TgWatch'
fi
[[ -s "$password_file" ]] || { echo 'Restore the signing password file.' >&2; exit 1; }
keytool -list -keystore "$store" -storepass:file "$password_file" -alias tgwatch >/dev/null
base64 < "$store" | tr -d '\r\n' | gh secret set TGWATCH_KEYSTORE_BASE64 --repo "$repo"
gh secret set TGWATCH_KEYSTORE_PASSWORD --repo "$repo" < "$password_file"
gh secret set TGWATCH_KEY_PASSWORD --repo "$repo" < "$password_file"
printf '%s' tgwatch | gh secret set TGWATCH_KEY_ALIAS --repo "$repo"
echo 'Signing secrets configured. Keep the key and password in a secure backup. Never commit them.'
