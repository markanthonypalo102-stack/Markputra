# Mputra Android Project — Validation Report

Date: 2026-10-29

## Tests completed in this environment

- **Original Python analyzer self-tests:** process exit code 0. Reported suite checks: 284 passed across the base engine, supplemental/regression suites, feature-function registry, pattern aggregation, confirmation gates, and V19 requested strategy engine. Individual suite counts are kept in the captured console output; these are reported check counts, not a measure of live-market accuracy.
- **Desktop vs Android-compatible visual-analysis path:** 9/9 deterministic synthetic-fixture output fields matched: candle count, green/red counts, direction, confidence, pattern count, confirmation check count, confirmation agreement, and candidate status.
- **PNG → Android Python bridge end-to-end smoke test:** passed. A synthetic lossless 900×480 chart was decoded and analyzed by the same `analyze_live_screen_frame` function. Result: 100 detected candles (58 green, 42 red), SELL directional evidence score 64.0%, signal not qualified. The bridge created a temporary local SQLite database and rejected a malformed PNG rather than returning a fabricated score.
- **Android manifest and drawable XML:** parse successfully.
- **GitHub Actions workflow YAML:** parse succeeds; the build job has the Python bridge smoke test before the Gradle APK task.
- **Python syntax:** Python compile checks pass for the bundled engine, bridge, compatibility layer, and smoke test.
- **No runtime internet permission:** the Android manifest does not declare `android.permission.INTERNET`.
- **Kotlin source syntax check:** no parser-level syntax diagnostics were found with `kotlinc`; that command was run without Android/AndroidX/Chaquopy classpaths, so it is *not* a compile test.

## Not verified here — important

The current environment has no `gradle`, `sdkmanager`, Android SDK, or `android.jar`. Therefore:

- No APK was built here.
- No real Android device/emulator installation or `MediaProjection` screen-sharing session was tested here.
- The synthetic parity fixture is not a real broker chart and does not demonstrate forecast accuracy.
- Android's NumPy/NumPy OpenCV compatibility subset matched the deterministic fixture, but unusual themes, candle widths, overlays, or pixel colors can produce different detections than desktop OpenCV.
- Price-axis OCR is disabled in this Android build. The engine's screen-frame analysis and five-minute scoring/confirmation path are retained, but price-calibrated OHLC features which need OCR/calibration may be unavailable.
- The app can't bypass Android's screen-capture consent or a broker app's secure-window restrictions. User approval is required for each new capture session.
- The hardcoded login can be found by inspecting/decompiling the app, by design. It is only a local access gate, not server-grade authentication.

## Next required validation

Upload/push this project to GitHub `main` and confirm **Actions → Build Mputra Android APK** finishes successfully. Download the resulting release APK, install it on the intended phone, allow screen-capture and overlay permissions, and test with a real broker chart before relying on it. If the workflow fails, the APK is not available yet and the first failing build step must be fixed.

The score is directional evidence, not a calibrated or guaranteed winning probability; trades remain manual.


## Android dependency correction (2026-10-29)

The first GitHub Android build failed at `:app:installDebugPythonRequirements` because Chaquopy could not find an Android-compatible SciPy wheel for the configured Python 3.11 / arm64-v8a target. The Android module no longer installs SciPy. `cv2_compat.py` now implements the required mask morphology, connected-component labeling/statistics, and Laplacian with NumPy only. The Python engine/scoring file was not changed by this dependency fix. Local checks after the change: `test_android_bridge.py` passes; `test_analysis_parity.py` passes 9/9 output parity fields for the deterministic 100-candle synthetic fixture; random-mask checks matched OpenCV component counts, component stats/centroids, and morphology opening in the local validation environment. This is not yet proof of a successful GitHub APK build or on-device live-chart accuracy.


## Fresh package preparation

- Android Gradle configuration installs only `numpy` and `Pillow`; it does not request SciPy.
- Python-only tests and XML syntax validation passed again while preparing this ZIP.
- The GitHub Actions workflow was updated to current Node 24-compatible action major versions as listed by their upstream release pages on 2026-10-29. These updates do not substitute for the real Android build.
- No Gradle/Android SDK build could be run in this environment because the Android SDK and Gradle executable are not installed here.
