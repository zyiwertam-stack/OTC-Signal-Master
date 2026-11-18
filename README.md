# OTC Scan Master

Native Android chart scanner. Screen capture and analysis run locally; the app does not need an API key, network access, or a paid AI service. Signals are heuristic visual analysis, not a trading guarantee or financial advice.

## Local analysis

- **OpenCV 4.12.0** (Apache-2.0): HSV colour segmentation, vertical morphological cleanup, connected-component candle extraction, approximate body/wick geometry, local extrema, and visual pattern heuristics.
- **Google ML Kit Text Recognition 16.0.1**, bundled Latin model: on-device OCR searches dynamically detected blue price-label regions, records readings with frame timestamps, and continues when individual frames are unreadable.
- **OpenCV Lucas–Kanade Optical Flow**: estimates vertical image movement between each adjacent pair of video samples.
- **Technical heuristics**: candle position and body direction, streaks and alternation, body-size changes, wick-based reversal evidence, price change and velocity, frame-to-frame trend, and optical flow contribute to one weighted direction score. A successful directional analysis returns CALL or PUT; weak evidence is marked `LOW DATA QUALITY`. The displayed score is an internal heuristic, not a calibrated probability.

VIDEO SCAN records for 10 seconds and samples up to 10 chronologically ordered frames across the recording. Candle and blue-price-region detection run on every frame; OCR price samples include timestamps, and movement, speed, and acceleration are evaluated across readable samples. SCREENSHOT analyzes one captured frame. GET SIGNAL reuses a recent result's frames or requests a fresh screenshot if the cached capture is missing or older than 30 seconds. Image analysis runs on a worker thread; images are not uploaded. Capture size, per-frame red/green pixel and candle counts, price OCR coverage, temporal evidence, and final status are written to Android logs (`OTCScanCapture` and `OTCScanAnalysis`).

Technical outcomes are distinguished from completed analysis: capture failures, missing chart evidence, and images without measurable directional evidence display ERROR. A completed analysis with little history or low OCR coverage still reports the stronger of its measured CALL/PUT evidence and warns `LOW DATA QUALITY`. Confidence reflects score strength, evidence agreement, recognized candles and frames, price OCR coverage, and reversal penalties; it is not a probability of profit. The project implements one on-device pipeline with shared evidence rather than separate or simulated engines.

### Models and licensing limits

No YOLOv8 or ResNet18 model is included. Common YOLOv8 COCO weights detect general objects, and standard ResNet18 ImageNet weights classify everyday objects; neither is trained to recognize financial candles or chart formations. Bundling them would not provide the requested chart analysis. Ultralytics YOLOv8 is AGPL-3.0-licensed, which has source-distribution obligations; a separately licensed commercial offering is not assumed here. A chart-specific model with verifiable training data, Android-compatible weights, and redistribution terms is not supplied by this repository. OpenCV and deterministic image heuristics are used instead of representing unrelated weights as chart models.

OpenCV's Android artifact is published for Android and distributed under Apache-2.0. ML Kit Text Recognition is Google's free bundled on-device SDK, not an open-source model; it is packaged with the app and invoked locally. These dependencies require network access only when building/downloading dependencies, not at runtime. Review upstream terms before redistributing modified dependencies.

## Build

Requirements: JDK 17, Android SDK Platform 35, and Android Gradle Plugin 8.13.2-compatible Gradle (8.13).

```sh
./gradlew :app:assembleDebug :app:lintDebug
```

The installable debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
GitHub Actions runs the same build and lint tasks and publishes the debug APK as a downloadable artifact with its size and SHA-256 in the workflow summary.

## First run

1. Install the APK and allow display over other apps.
2. Start the floating panel and grant screen-capture consent through Android's system prompt.
3. Open the chart and use **SCREENSHOT**, **VIDEO SCAN**, and **GET SIGNAL**. VIDEO SCAN takes 10 seconds; the floating panel is restored when capture completes.

Screen capture requires explicit user consent. Android may revoke consent when a capture session ends; secure/DRM-protected windows can appear blank. The app does not place trades, access trading accounts, or guarantee that visual signals are accurate or profitable. Candle color detection depends on chart colors and may provide a low-quality preliminary signal on other color schemes or cluttered charts.
