# TradeFloat AI — Gemini + Cloudflare AI fallback

Floating overlay: exactly **30 sec**, **1 min**, **STOP**.

## AI providers
- Primary: Gemini vision API (`gemini-2.5-flash`) interprets sequential screenshot images.
- Fallback: Cloudflare Workers AI vision (`@cf/meta/llama-3.2-11b-vision-instruct`) describes selected frames; a text model compares the descriptions.
- If both fail or free quotas are exhausted, the app returns `UNCLEAR`.

Official references:
- Gemini pricing: https://ai.google.dev/gemini-api/docs/pricing
- Gemini rate limits: https://ai.google.dev/gemini-api/docs/rate-limits
- Cloudflare Workers AI free allocation: https://developers.cloudflare.com/workers-ai/platform/pricing/
- Cloudflare vision model: https://developers.cloudflare.com/workers-ai/models/llama-3.2-11b-vision-instruct/

## Free-only setup
1. Create a Gemini key at https://aistudio.google.com/app/apikey.
2. Deploy `cloudflare-worker/worker.js` as a Cloudflare Worker and add a Workers AI binding named `AI` (the included `wrangler.toml` shows the binding).
3. Add the Gemini key as the Worker secret `GEMINI_API_KEY`. With Wrangler: `npx wrangler secret put GEMINI_API_KEY`.
4. Deploy the Worker and copy its HTTPS `workers.dev` URL.
5. Build/install the Android app from Android Studio (JDK 17, Android SDK 35). Save the Worker URL in the app, allow overlay permission, start capture, and approve the Android screen-capture prompt.

Keep the Google project on the Gemini free tier and do not enable/link paid billing. Keep Cloudflare on the Workers Free plan; its published free allocation is 10,000 Neurons/day, and inference beyond the free allocation fails on Workers Free. Do not upgrade or attach billing if you require free-only use. Free quotas and model availability may change. Some Cloudflare vision models require accepting their license before use.

## Accuracy and privacy
This is genuine cloud image understanding, not pixel-color heuristics. It is not a validated short-term market forecasting system. UP/DOWN are tentative visual assessments, not calibrated probabilities or guarantees. Use demo charts only. Captured screenshots are sent to the selected provider through the Worker; keep sensitive account details and notifications out of view.

No API keys are included in the ZIP.
