# TradeFloat AI — Android project

Minimal floating chart reader with three overlay buttons: **30 sec**, **1 min**, and **STOP**.

## Build on GitHub Actions
1. Upload the entire contents of this folder to the root of your GitHub repository (not inside an extra nested folder).
2. Open **Actions → Build TradeFloat APK** and run the workflow, or push a commit.
3. When successful, open the workflow run, scroll to **Artifacts**, and download `tradefloat-debug-apk`.

## Configure
1. Deploy `cloudflare-worker/worker.js` as a Cloudflare Worker.
2. Add a Worker secret named `GEMINI_API_KEY`.
3. Add a Workers AI binding named `AI` if using the fallback.
4. In the app, enter the deployed `https://…workers.dev` URL and save it.
5. Grant **Display over other apps** permission, then start live chart reader and approve Android's screen-capture prompt.

The project is not claiming a verified local build in this environment. The GitHub Actions workflow is included to compile it in CI. UP/DOWN outputs are uncertain visual interpretations, not reliable forecasts; use demo charts only. Keep provider usage on free tiers and do not enable billing if you cannot afford charges.
