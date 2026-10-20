# TradeFloat Android Starter (v0.1.0)

A native Android proof-of-concept for a movable floating panel and explicit user-approved screen capture.

## What this version does
- Requests Android's "Display over other apps" permission.
- Requests screen-capture consent using MediaProjection.
- Starts a foreground screen-capture service.
- Displays a movable overlay with a stop button.
- Builds a debug APK using GitHub Actions.

## What it does NOT do yet
- It does not identify chart candles or extract price values.
- It does not calculate RSI/MACD/EMA.
- It does not use an AI vision model or issue UP/DOWN predictions.
- It does not place trades or interact with the trading app.
- It is not validated for real-money trading.

## Build from Android using GitHub
1. Create a new GitHub repository named `TradeFloat`.
2. Upload all files and folders from this project, preserving the folder structure.
3. Open the repository's **Actions** tab.
4. Enable Actions if prompted, then run **Build Android APK** (or push a commit to `main`).
5. Open the completed workflow run, scroll to **Artifacts**, and download `TradeFloat-debug-apk`.
6. Extract the ZIP on your Android phone and install `app-debug.apk`. Android may ask you to allow installs from that browser/file manager.

## First run
1. Open TradeFloat.
2. Tap **Allow floating panel** and enable "Display over other apps".
3. Return to TradeFloat.
4. Tap **Start screen session** and approve Android's screen-capture prompt.
5. The app closes to let you use another app; the floating panel should appear.
6. Tap **Stop** on the panel to end the session.

## Safety and privacy
Screen capture can expose sensitive on-screen information. Start it only when intended, stop it when done, and do not share captured screens containing account details. This starter does not upload screenshots to a server.

## Next development milestone
Add chart-region selection, candle/price extraction, confidence and "unclear" thresholds, then compare predictions against historical/out-of-sample data before considering any live trading use.
