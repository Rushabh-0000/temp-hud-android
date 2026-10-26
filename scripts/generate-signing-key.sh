#!/usr/bin/env bash
set -euo pipefail

# Generates a persistent Android release keystore and the copy/paste values for GitHub Actions.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -d "$SCRIPT_DIR/../app" && -d "$SCRIPT_DIR/../.github" ]]; then
  ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
else
  ROOT_DIR="$SCRIPT_DIR"
fi
KEYSTORE_PATH="$ROOT_DIR/temp-hud-release.p12"
SECRETS_PATH="$ROOT_DIR/github-release-secrets.txt"

for cmd in keytool openssl base64; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "ERROR: '$cmd' is required. Use a Linux/macOS/JDK workspace with OpenSSL installed." >&2
    exit 1
  fi
done

if [[ -e "$KEYSTORE_PATH" || -e "$SECRETS_PATH" ]]; then
  echo "ERROR: A keystore or secrets file already exists. Refusing to overwrite a release key." >&2
  echo "Keep using the existing key for every future Temp HUD update." >&2
  exit 1
fi

umask 077
STORE_PASSWORD="$(openssl rand -hex 32)"
KEY_ALIAS="temp-hud-release"

keytool -genkeypair -noprompt \
  -keystore "$KEYSTORE_PATH" \
  -storetype PKCS12 \
  -storepass "$STORE_PASSWORD" \
  -keypass "$STORE_PASSWORD" \
  -alias "$KEY_ALIAS" \
  -keyalg RSA -keysize 3072 -validity 10000 \
  -dname "CN=Temp HUD Release, OU=Android, O=Temp HUD, C=IN"

chmod 600 "$KEYSTORE_PATH"
KEYSTORE_BASE64="$(base64 < "$KEYSTORE_PATH" | tr -d '\r\n')"

cat > "$SECRETS_PATH" <<SECRETS
TEMP HUD RELEASE SIGNING SECRETS
Do not commit or share this file. Copy each value into the matching GitHub Actions repository secret.
Keep temp-hud-release.p12 backed up securely. Every future release must use the same key.

Secret: ANDROID_KEYSTORE_BASE64
Value:
$KEYSTORE_BASE64

Secret: ANDROID_KEYSTORE_PASSWORD
Value:
$STORE_PASSWORD

Secret: ANDROID_KEY_ALIAS
Value:
$KEY_ALIAS

Secret: ANDROID_KEY_PASSWORD
Value:
$STORE_PASSWORD
SECRETS
chmod 600 "$SECRETS_PATH"

printf '\nCreated release keystore: %s\n' "$KEYSTORE_PATH"
printf 'Created GitHub secrets text: %s\n\n' "$SECRETS_PATH"
printf 'Copy each Value into the matching repository secret here:\n'
printf 'https://github.com/Rushabh-0000/temp-hud-android/settings/secrets/actions\n'
printf 'Back up the .p12 privately. Never commit or publicly share the .p12 or generated .txt file.\n'
