# OTC Scan Master

Native Android chart scanner. Screen capture and analysis run locally; the app does not need an API key, network access, or a paid AI service. Signals are heuristic visual analysis, not a trading guarantee or financial advice.

## Local analysis

- **OpenCV 4.12.0** (Apache-2.0): HSV colour segmentation, vertical morphological cleanup, connected-component candle extraction, approximate body/wick geometry, local extrema, and visual pattern heuristics.
- **Google ML Kit Text Recognition 16.0.1**, bundled Latin model: on-device OCR of visible chart text and numeric labels. The bundled model avoids a first-run model download.
- **OpenCV Lucas–Kanade Optical Flow**: estimates vertical image movement between the first and last video samples.
- **Technical heuristics**: recent candle-position trend, red/green candle vote, visible-range location, and optional optical-flow evidence are combined. CALL/PUT require agreeing evidence; weak, missing, or conflicting evidence returns WAIT. The displayed score is a heuristic evidence score, not a calibrated probability.

The app samples up to seven chronologically ordered frames from a 3.5-second screen recording. Candle detection runs on every sampled frame; the newest frame supplies candle geometry and earlier/later samples provide temporal price-position and optical-flow evidence. It also supports screenshot capture and manual image selection. Images are not uploaded. Capture size/stride, per-frame red/green pixel and candle counts, temporal evidence, and final status are written to Android logs (`OTCScanCapture` and `OTCScanAnalysis`).

Technical outcomes are distinguished from completed analysis: capture and analysis errors, missing chart colors, and missing candle components display ERROR rather than WAIT 0%; insufficient candle history is identified separately from a completed WAIT. A completed WAIT can have nonzero heuristic evidence strength; this percentage is not a calibrated probability or expected return. The project currently implements one on-device analysis pipeline rather than eight separate engines.

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
3. Open the chart and use **SCREENSHOT**, **VIDEO SCAN**, and **GET SIGNAL**.

Screen capture requires explicit user consent. Android may revoke consent when a capture session ends; secure/DRM-protected windows can appear blank. The app does not place trades, access trading accounts, or guarantee that visual signals are accurate or profitable. Red/green colour-based detection can return WAIT on charts with other colour schemes or clutter.
