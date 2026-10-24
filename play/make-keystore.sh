#!/usr/bin/env bash
# Nibras Office — Google Play "upload" açarı yaradır (yalnız bir dəfə işlədilir).
# Termux: pkg install openjdk-17      Linux/macOS: JDK 17+ lazımdır
set -e
if ! command -v keytool >/dev/null 2>&1; then
  echo "keytool tapılmadı. Termux-da: pkg install openjdk-17"
  exit 1
fi
if [ -f upload.jks ]; then
  echo "upload.jks artıq var. Köhnə açarı itirməmək üçün üstünə yazılmır."
  exit 1
fi
ALIAS="upload"
PASS="$(head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 20)"
keytool -genkeypair -v -keystore upload.jks -storetype PKCS12 -alias "$ALIAS" \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "$PASS" -keypass "$PASS" \
  -dname "CN=Nibras Office, O=Nibrascode, C=AZ"
base64 upload.jks | tr -d '\n' > upload.jks.base64.txt
echo
echo "Hazırdır. GitHub -> repo -> Settings -> Secrets and variables -> Actions -> New repository secret:"
echo "  KEYSTORE_BASE64   = upload.jks.base64.txt faylının bütün məzmunu"
echo "  KEYSTORE_PASSWORD = $PASS"
echo "  KEY_ALIAS         = $ALIAS"
echo "  KEY_PASSWORD      = $PASS"
echo
echo "MÜHÜM: upload.jks və yuxarıdakı şifrələri təhlükəsiz yerdə saxla. Repo-ya YÜKLƏMƏ!"
