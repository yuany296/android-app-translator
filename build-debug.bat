@echo off
if not exist gradlew.bat (
  echo gradlew.bat not found. Open project in Android Studio and sync, or generate wrapper first.
  exit /b 1
)
call gradlew.bat :app:assembleDebug
