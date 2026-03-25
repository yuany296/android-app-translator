#!/usr/bin/env sh
set -eu
if [ ! -f ./gradlew ]; then
  echo "gradlew not found. Open in Android Studio and sync project, or generate wrapper with: gradle wrapper"
  exit 1
fi
./gradlew :app:assembleDebug
