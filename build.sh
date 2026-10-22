#!/bin/bash
set -e
SDK=/opt/android-sdk
BT=$SDK/build-tools/34.0.0
PLATFORM=$SDK/platforms/android-34
APP=/root/pet-app
OUT=$APP/build

rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "1/6 编译资源"
qemu-x86_64 "$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res.zip"

echo "2/6 链接资源 + 生成 R.java"
qemu-x86_64 "$BT/aapt2" link -o "$OUT/app.unsigned.apk" \
  -I "$PLATFORM/android.jar" \
  --manifest "$APP/AndroidManifest.xml" \
  -R "$OUT/res.zip" \
  -A "$APP/assets" \
  -0 webm \
  --java "$OUT/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --version-code 1 \
  --version-name 1.0

echo "3/6 编译 Java"
javac -source 1.8 -target 1.8 \
  -encoding UTF-8 \
  -bootclasspath "$PLATFORM/android.jar" \
  -d "$OUT/classes" \
  "$APP"/java/com/dafeiyu/pet/*.java \
  "$OUT"/gen/com/dafeiyu/pet/R.java 2>&1

echo "4/6 转 dex"
"$BT/d8" --release --lib "$PLATFORM/android.jar" --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

echo "5/6 打包 + 对齐"
cp "$OUT/app.unsigned.apk" "$OUT/app.withdex.apk"
cd "$OUT"
zip -q -j app.withdex.apk dex/classes.dex
qemu-x86_64 "$BT/zipalign" -f 4 app.withdex.apk app.aligned.apk

echo "6/6 签名"
"$BT/apksigner" sign --ks "$APP/debug.keystore" --ks-pass pass:android \
  --key-pass pass:android --out "$APP/dafeiyu-pet.apk" app.aligned.apk
cp -f "$APP/dafeiyu-pet.apk" "$APP/dafeiyu-pet-v1.2.2.apk"

echo ""
echo "=== 完成 ==="
ls -la "$APP/dafeiyu-pet.apk"
