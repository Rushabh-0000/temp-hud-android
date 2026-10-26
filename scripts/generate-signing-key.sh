#!/usr/bin/env bash
set -euo pipefail

# Create a persistent Android release key and copy/paste GitHub Actions secrets.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -d "$SCRIPT_DIR/../app" && -d "$SCRIPT_DIR/../.github" ]]; then
  ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
else
  ROOT_DIR="$SCRIPT_DIR"
fi

KEYSTORE_PATH="$ROOT_DIR/temp-hud-release.p12"
SECRETS_PATH="$ROOT_DIR/github-release-secrets.txt"

for cmd in keytool openssl base64; do
  command -v "$cmd" >/dev/null 2>&1 || {
    echo "ERROR: $cmd is required. Install a JDK and OpenSSL first." >&2
    exit 1
  }
done

# Never overwrite an existing key: all future updates must use the original key.
if [[ -e "$KEYSTORE_PATH" || -e "$SECRETS_PATH" ]]; then
  echo "ERROR: Release keystore/secrets file already exists; refusing to overwrite." >&2
  echo "Back up and keep using the existing key for all future releases." >&2
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
printf 'Created copy/paste secrets file: %s\n\n' "$SECRETS_PATH"
printf 'GitHub repository secrets page:\n'
printf 'https://github.com/Rushabh-0000/temp-hud-android/settings/secrets/actions\n'
printf 'Back up the .p12 privately. Never commit or share either generated file.\n'
