TradeFloat AI audit notes

Changes in this replacement pack:
- Corrected GitHub Actions YAML indentation and placed workflow at .github/workflows/build-apk.yml.
- App module Gradle config explicitly targets Java/Kotlin 17 and Android SDK 35.
- CaptureOverlayService creates the overlay before capture setup and keeps a visible startup error instead of immediately calling stopSelf() in the startup catch.

Not verified in this environment:
- Android APK build (no connected Android SDK/Gradle build execution was performed here).
- Cloudflare deployment, Workers AI model availability/license, Gemini API key, or free-tier quotas.
- Device-specific MediaProjection behavior. Test with a demo chart only.

Repository placement matters: upload the files preserving their paths. Do not put app/build.gradle.kts at the repository root. The workflow must be .github/workflows/build-apk.yml.
Keep GEMINI_API_KEY as a Cloudflare Worker secret; never put it in Android source or GitHub files.
