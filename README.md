# TradeFloat AI — minimal cloud-vision prototype

Floating overlay has exactly three controls: **30 sec**, **1 min**, **STOP**.

## What is genuinely AI here?
The Android app captures up to 8 JPEG screenshots during the selected window, then sends those images to the included Cloudflare Worker. The Worker calls Google's Gemini vision-capable API (`gemini-2.5-flash`) and asks it to return `UP`, `DOWN`, or `UNCLEAR`. This is actual image input to a cloud vision model, not a pixel-colour heuristic.

## Free-only constraints
- Gemini API currently has a free tier for eligible models/accounts, with rate limits and availability that Google can change. Check current official pricing: https://ai.google.dev/gemini-api/docs/pricing
- This app does not configure billing or switch to a paid model. If the free quota is unavailable/exhausted, it returns `UNCLEAR` with an error message; it does not fall back to paid use.
- Cloudflare Workers has a free plan subject to its current limits. Deploying the Worker requires a free Cloudflare account and setting a Gemini API key as a Worker secret.
- A Gemini API key is still required. Never put it into the APK or commit it to GitHub.

## Setup overview
1. Create a Gemini API key in Google AI Studio: https://aistudio.google.com/app/apikey
2. Create a Cloudflare account and install Wrangler or use the Cloudflare dashboard to create a Worker from `cloudflare-worker/worker.js`.
3. Set the Worker secret named `GEMINI_API_KEY` to your key, deploy the Worker, and copy its HTTPS `workers.dev` URL.
4. Open the Android project in Android Studio (JDK 17, Android SDK 35), build `app:assembleDebug`, and install the APK.
5. In TradeFloat, paste the Worker HTTPS URL and tap Save endpoint. Grant overlay permission, then start the screen session and approve Android's capture prompt.

## Honest limitations
Gemini interprets images; it is not a proven short-horizon trading predictor. It can misunderstand chart types, overlays, colors, candles, or time scales. `UP`/`DOWN` are tentative visual interpretations only, not calibrated probabilities or guarantees. Use demo charts only; do not rely on this for real-money trades. A cloud request sends sampled chart screenshots to Google through your Worker. Avoid exposing account balances, personal information, or sensitive notifications in captured screenshots.

## Build
Open this folder in Android Studio. Project uses Android Gradle Plugin 8.7.3, Kotlin 2.0.21, JDK 17 and compileSdk 35. No API key is included.
