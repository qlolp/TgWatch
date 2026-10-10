#!/usr/bin/env bash
# Trusted machine only. restore reuses a backed-up identity; init explicitly creates one.
set -euo pipefail
umask 077
repo=qlolp/TgWatch
usage() {
  echo 'Usage: configure-signing.sh init|restore /secure/backup-directory [--repo owner/repo] [--allow-replace-existing-secrets]' >&2
  exit 1
}
[[ $# -ge 2 ]] || usage
mode=$1
signing_dir=$2
[[ "$mode" == init || "$mode" == restore ]] || usage
shift 2
allow_replace=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo) [[ $# -ge 2 ]] || usage; repo=$2; shift 2 ;;
    --allow-replace-existing-secrets) allow_replace=true; shift ;;
    *) usage ;;
  esac
done
[[ "$mode" == init || "$allow_replace" == false ]] || usage
store="$signing_dir/tgwatch-release.p12"
password_file="$signing_dir/tgwatch-signing-password.txt"
if [[ "$mode" == init ]]; then
  if [[ -e "$store" || -e "$password_file" ]]; then
    echo 'Signing backup already exists; use restore. init never overwrites a backup.' >&2
    exit 1
  fi
  # Failure to inspect Secrets is a failure, not evidence that none exist.
  existing=$(gh secret list --repo "$repo" --json name --jq '.[].name')
  if [[ "$allow_replace" == false ]] && printf '%s\n' "$existing" | grep -Eq '^TGWATCH_(KEYSTORE_BASE64|KEYSTORE_PASSWORD|KEY_ALIAS|KEY_PASSWORD)$'; then
    echo 'Signing Secrets already exist. Restore their backup; replacement requires --allow-replace-existing-secrets in init mode.' >&2
    exit 1
  fi
  mkdir -p "$signing_dir"
  # noclobber prevents replacing a password file created since the earlier check.
  set -o noclobber
  openssl rand -base64 36 > "$password_file"
  keytool -genkeypair -keystore "$store" -storetype PKCS12 \
    -storepass:file "$password_file" -keypass:file "$password_file" \
    -alias tgwatch -keyalg RSA -keysize 3072 -validity 10000 \
    -dname 'CN=TG Watch, OU=Android, O=TgWatch'
fi
[[ -s "$store" ]] || { echo 'Restore the existing keystore backup; restore never generates keys.' >&2; exit 1; }
[[ -s "$password_file" ]] || { echo 'Restore the signing password file.' >&2; exit 1; }
key_entry=$(keytool -J-Duser.language=en -J-Duser.country=US -list -v \
  -keystore "$store" -storetype PKCS12 -storepass:file "$password_file" -alias tgwatch)
if ! printf '%s\n' "$key_entry" | grep -Fq 'Entry type: PrivateKeyEntry'; then
  echo 'Backup alias is not a private signing key; Secrets were not changed.' >&2
  exit 1
fi
base64 < "$store" | tr -d '\r\n' | gh secret set TGWATCH_KEYSTORE_BASE64 --repo "$repo"
gh secret set TGWATCH_KEYSTORE_PASSWORD --repo "$repo" < "$password_file"
gh secret set TGWATCH_KEY_PASSWORD --repo "$repo" < "$password_file"
printf '%s' tgwatch | gh secret set TGWATCH_KEY_ALIAS --repo "$repo"
echo "Signing Secrets configured using $mode. Keep the key and password in a secure backup. Never commit them."
