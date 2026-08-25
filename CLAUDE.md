# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew assembleRelease        # Build release APK
./gradlew installDebug           # Build and install to connected device
./gradlew test                   # Run unit tests
./gradlew connectedAndroidTest   # Run instrumentation tests (device required)
./gradlew lint                   # Run lint checks
./gradlew clean                  # Clean build outputs

# Convenience scripts
./build-debug.sh                 # One-command debug build + install
```

Single module task: `./gradlew :core-translate:test`

Mirrors are configured via Aliyun (Chinese network optimization) in `settings.gradle.kts`.

## Architecture Overview

This is a **manga/comic translation Android app** that uses screen capture + AI vision APIs to detect and translate text bubbles in real time.

### Multi-Module Structure

```
app                 # Entry point, service orchestration, permission UI
core-capture        # Screen capture via MediaProjection
core-translate      # OCR + translation via OpenAI-compatible vision API
core-overlay        # Floating button, bubble rendering, text overlay
core-store          # Encrypted settings, translation cache (2hr TTL)
```

Dependency flow: `app → {core-capture, core-translate, core-overlay, core-store}`, `core-overlay → core-translate`

### Translation Pipeline

1. User taps floating "译" button
2. `CaptureForegroundService` triggers `TranslateOrchestrator.runOnce()`
3. `ScreenCaptureController` captures bitmap via MediaProjection
4. Image compressed to max 1024px wide JPEG → base64 encoded
5. Sent to OCR endpoint (`/chat/completions`) → returns `Bubble[]` with coordinates (0–100% scale)
6. Text batch sent to translation endpoint → translated strings returned
7. `TieredEraseProcessor` removes original text using OpenCV inpaint
8. `OverlayController` renders translated bubbles as floating views

Cache: SHA-256 keyed, 2-hour TTL, max 1000 entries — cache hits skip API entirely.

### Key Classes

| Class | Module | Role |
|---|---|---|
| `CaptureForegroundService` | app | Foreground service; manages lifecycle of capture/overlay loop |
| `TranslateOrchestrator` | app | Orchestrates the full capture→OCR→translate→render pipeline |
| `ServiceLocator` | app | Service locator singleton for cross-module dependency injection |
| `ScreenCaptureController` | core-capture | MediaProjection + VirtualDisplay + ImageReader management |
| `OpenAiVisionClient` | core-translate | OkHttp-based REST client; supports OCR and translation endpoints |
| `OverlayController` | core-overlay | WindowManager-based floating UI (button + bubble layers) |
| `SettingsRepository` | core-store | AES-256-GCM encrypted SharedPreferences, profile-based config |
| `TranslationCacheRepository` | core-store | LRU cache with JSON serialization and TTL eviction |

### Supported AI Providers

- **OpenAI** – `gpt-4o-mini`, `https://api.openai.com/v1`
- **Qwen (阿里千问)** – `qwen-vl-max-latest`, `https://dashscope.aliyuncs.com/compatible-mode/v1`
- **GLM (智谱清言)** – `glm-4v-plus`, `https://open.bigmodel.cn/api/paas/v4`

All use the same OpenAI-compatible `/chat/completions` endpoint. Qwen and GLM support extended thinking mode (budget: 1024–131072 tokens). `OpenAiVisionClient` has a fallback chain: JSON format → no JSON format → no thinking.

### Overlay Coordinate System

Bubble coordinates from the OCR API are in **0–100% scale** relative to the capture image. `TranslateOrchestrator` transforms these to screen render coordinates, accounting for the virtual display dimensions vs. actual screen size.

### Configuration / Settings

`AppSettings` is profile-based: users can create multiple named profiles with different provider configs. Settings are stored encrypted via `SettingsRepository`. Legacy single-provider settings are handled for backwards compatibility.

## Key Permissions

Declared in `app/src/main/AndroidManifest.xml`:
- `SYSTEM_ALERT_WINDOW` – floating overlay
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` – capture service
- `BIND_ACCESSIBILITY_SERVICE` – page change detection via `OverlayAccessibilityService`

## Tech Stack

- Kotlin + Coroutines (`Dispatchers.IO` for API/image work, `Dispatchers.Main` for UI)
- Jetpack Compose + Material 3 (settings/launcher UI in `MainActivity`)
- OkHttp (20s connect, 60s read/write timeouts)
- OpenCV 4.10.0 (text erasure via inpaint)
- `androidx.security:security-crypto-ktx` (AES-256-GCM encrypted prefs)
- Min SDK 29 (Android 10), Target SDK 34, Java 17
