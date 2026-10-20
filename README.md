# TradeFloat Android project

Visible-chart-only floating analysis prototype. It does not connect to a broker/API, does not place trades, and cannot reliably recover true OHLC data from arbitrary screenshots. Pixel-derived metrics are visual proxies, not verified market indicators or a reliable trading signal.

## Roadmap
1. Floating panel and start/stop: included.
2. MediaProjection screen sampling in memory: included.
3. Adjustable chart region persisted locally: included.
4. Colored candle-like candidates: heuristic only, not verified OHLC extraction.
5. EMA/RSI/MACD-like calculations on pixel coordinates: explicitly labeled image proxies, not market indicators.
6. Conservative visual combination with UNCLEAR state: included.
7. Adjustable analysis horizon: tracked separately; not a forecast.
8. Manual prediction logging and outcome labeling: included; reported matches are not a guarantee of future accuracy.

## Build
1. Install current Android Studio and its supported JDK.
2. Open this folder in Android Studio and sync Gradle.
3. Build > Build APK(s), then install the debug APK.
4. Open TradeFloat. Grant Display over other apps permission.
5. Tap Start live screen session and approve Android's screen capture dialog.
6. Switch to the chart, calibrate the chart region, then use Log estimate and manually label an outcome after the chosen horizon.

Android requires fresh screen-capture consent for each session. Test on demo charts first. The app offers manual assistance only; all trading decisions remain yours.
