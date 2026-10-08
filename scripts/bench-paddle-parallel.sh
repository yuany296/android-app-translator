#!/bin/bash
# PaddleOCR 识别阶段「串行 vs 并行」A/B 基准（交错 2 轮，降低模拟器噪声）。
# 关键坑：connectedAndroidTest 跑完会卸载 APK，external files 目录被清空，
#        因此每次跑之前必须重新 push 测试图。
export PATH="/c/Users/yuanying/.workbuddy/binaries/PortableGit/versions/1.2.0/usr/bin:/c/Users/yuanying/.workbuddy/binaries/PortableGit/versions/1.2.0/mingw64/bin:$PATH"
cd /c/homework/AI_work/android-app-translator || exit 1

ADB=/c/Users/yuanying/AppData/Local/Android/Sdk/platform-tools/adb
SERIAL=emulator-5554
DEST=/sdcard/Android/data/com.manga.translator.app/files

FILE=core-translate/src/main/java/com/manga/translator/translate/PaddleOcrLocalTextDetector.kt
BAK=/tmp/PaddleOcrLocalTextDetector.new.kt
CLASS=com.manga.translator.app.PaddleOcrPerfBenchmarkTest

cp "$FILE" "$BAK" || exit 1
echo "backup_md5=$(md5sum "$BAK" | cut -d' ' -f1)"

push_images () {
  "$ADB" -s $SERIAL shell "mkdir -p $DEST"
  "$ADB" -s $SERIAL push bg1_screen.png $DEST/bench_page1.png > /dev/null
  "$ADB" -s $SERIAL push verify_screen.png $DEST/bench_page2.png > /dev/null
  "$ADB" -s $SERIAL push verify_glossary_overview.png $DEST/bench_page3.png > /dev/null
  echo "images_pushed=$("$ADB" -s $SERIAL shell "ls $DEST" | wc -l)"
}

run_case () {
  local label=$1
  echo "########## CASE $label START $(date '+%H:%M:%S') ##########"
  push_images
  rm -rf app/build/outputs/androidTest-results/connected/debug
  ./gradlew.bat :app:connectedDebugAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.class=$CLASS \
      --console=plain > "logs/bench-$label-gradle.log" 2>&1
  echo "gradle_exit=$?"
  mkdir -p "logs/bench-$label-artifacts"
  rm -rf "logs/bench-$label-artifacts"/*
  cp -r app/build/outputs/androidTest-results/connected/debug/. "logs/bench-$label-artifacts/" 2>/dev/null
  echo "bench_lines=$(find logs/bench-$label-artifacts -name 'logcat-*.txt' -exec grep -h 'BENCH ' {} \; | grep -c 'ms=')"
  echo "########## CASE $label END $(date '+%H:%M:%S') ##########"
}

# 第 1 轮
run_case parallel
git checkout -- "$FILE"
run_case serial

# 第 2 轮（反序，抵消随时间漂移的噪声）
cp "$BAK" "$FILE"
run_case parallel2
git checkout -- "$FILE"
run_case serial2

cp "$BAK" "$FILE"
echo "restored_md5=$(md5sum "$FILE" | cut -d' ' -f1)"
echo "ALL_DONE"
