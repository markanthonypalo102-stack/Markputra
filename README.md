# Mputra — Native Android live candle reader

**GitHub repository name:** `Mputra`  
**Android app display name:** `PUTRA` (kept from your earlier app-name instruction).

This project is the native Android companion to the existing `main(7).py` / `putra.py` Python analyzer. It is designed to run locally on the phone: Android provides authorized screen frames through `MediaProjection`, and Chaquopy runs the Python analyzer inside the same app process. The SQLite history database is written to the app's private files directory on the phone. No market-data/prediction web API or internet permission is used by the app.

## What is connected

- `app/src/main/java/com/putra/app/MainActivity.kt`: compact login, Manila date/time, dashboard and analysis monitor.
- `FloatingControlService.kt`: draggable green touch ball, `START READING` popup and a plus/cross overlay to drag-select the candle region above the broker app.
- `CaptureService.kt`: Android foreground screen-capture service. It crops the exact user-selected phone display rectangle and forwards lossless PNG frames to Python. Qualified alerts use the engine's `signal_ready`, 65% threshold, minimum candle count, and confirmation gates, with a five-minute cooldown; unqualified scores do not trigger the alert.
- `app/src/main/python/putra.py`: full Python source based on the supplied analyzer. The scoring, pattern registry, strategies, five-minute forecast, confirmation gates and local persistence functions are preserved. Only desktop-only imports are replaced by Android shims.
- `putra_android_bridge.py`: decodes each phone frame and calls the original `analyze_live_screen_frame()` path, then returns the result to the native monitor.
- `cv2_compat.py`: NumPy-only compatibility implementation for the OpenCV operations used by the existing live frame-analysis path. Desktop drawing and OCR are display/optional-price features and are disabled on Android; they do not vote in the score.

The Android adapter sends the same cropped image through the same Python candle and scoring pipeline, rather than calculating a separate web-only BUY/SELL score. It does not invent a signal if image decoding or analysis fails. The UI reports an analysis error and clears the previous result if a frame fails.

## Login and credentials

The app icon is a simple PUTRA radar/eye mark in green on a dark background. The local login is intentionally readable in source, as requested:

- Username: `@markputra.trade`
- Password: `Putra_121204`
- Contact Us: opens the email composer to `markanthonypalo102@gmail.com`

**Important:** this is a client-side/local access gate, not strong security. Anyone who can inspect the source or decompile the APK can find these credentials and bypass a local-only gate. Real secure account authentication needs a trusted authentication server/identity provider; this project does not include one because the requested design avoids external services and APIs.

## Required Android permissions and first start

Android deliberately requires the user's approval before an app can capture the display of another app. The user must also allow PUTRA to display over other apps to show the green floating ball. These are Android system permissions, not permission bypasses.

1. Install and open PUTRA; enter the username and password.
2. On first successful login, allow **Display over other apps** for PUTRA, then accept the Android **Start recording/casting** screen-capture prompt.
3. Switch to the broker app and display the live candlestick chart.
4. Tap the green PUTRA ball → **START READING**.
5. Drag the plus/cross selection rectangle around the candles. Avoid including the PUTRA ball, menus, and price axis if possible.
6. The crop is analyzed on-device by the bundled Python engine. Tap the ball again to see the compact monitor, pause reading, reselect the chart, or open the full monitor.
7. The green floating popup is the live monitor while the broker chart remains in front. Opening the full PUTRA activity pauses reading automatically so the engine does not analyze its own monitor; return to the broker app and tap **START READING** on the ball to resume. To stop screen capture completely, use the **Stop** action in the persistent Android capture notification. Android may also stop capture if the user revokes permission or the broker app blocks capture with secure-window settings.

The native screen capture requires a supported 64-bit Android device (arm64-v8a). The app cannot read the broker's private price feed or bypass `FLAG_SECURE`; it sees only pixels Android permits it to capture. Price-axis OCR is disabled in this mobile build, so price-calibrated OHLC and price-dependent outcomes may be unavailable. The active screenshot-to-score path uses a NumPy-only compatibility subset instead of the desktop OpenCV binary; on the included deterministic fixture its principal output fields matched OpenCV, but the implementations can still differ on unusual chart themes, very thin candles, and compression artifacts. Pattern/candle image analysis runs on the selected crop.

## Local data and offline behavior

- SQLite: `filesDir/putra_trading_memory_btc_otc.db` inside the app's private storage.
- Capture crops are processed in memory as lossless PNG so JPEG compression does not alter red/green candle-mask pixels; the adapter does not upload frames.
- The app manifest intentionally has no `INTERNET` permission.
- Market news, public price feeds and external research remain disabled.
- The score is directional evidence, not a calibrated or guaranteed win probability. Qualified alerts sound and notify only if the engine gates pass; while the five-minute alert cooldown is active, the score display is hidden as in the desktop flow. Trading/orders remain manual.

## Build an installable APK from GitHub (free workflow)

This repository is named `Mputra` and has `.github/workflows/build-android.yml`. Pushing the project to the `main` branch starts a GitHub Actions build, compiles the Android APK, and publishes a downloadable GitHub Release. No personal API key or paid Android hosting service is required. GitHub Actions uses its built-in `GITHUB_TOKEN` only to attach the build to the repository's release.

1. Download and extract the project ZIP on a computer.
2. On [GitHub](https://github.com/), create a **public** repository named `Mputra`. For easiest setup, leave “Add a README” unchecked so the repository starts empty.
3. Install [GitHub Desktop](https://desktop.github.com/) and sign in with the same GitHub account. Choose **File → Clone repository**, select the new `Mputra` repository and a local folder.
4. Copy the **contents** of the extracted `Mputra` folder into that cloned `Mputra` folder. The `.github` folder must be copied too; do not put all files inside an extra nested folder and do not upload only the ZIP.
5. In GitHub Desktop, enter a summary such as `Add PUTRA Android app`, click **Commit to main**, then click **Push origin** (or **Publish repository** if prompted).
6. Open the repository on GitHub and click **Actions → Build Mputra Android APK**. A push to `main` starts the workflow automatically. Wait for the workflow run to finish and confirm it is green/successful. If it fails, open the run and inspect the first red step; do not treat a failed build as an installable app.
7. After a successful run on `main`, open **Releases**. The workflow publishes `PUTRA.apk` and `SHA256SUMS.txt`.
8. The direct download URL is `https://github.com/YOUR-GITHUB-USERNAME/Mputra/releases/latest/download/Mputra.apk`. Replace `YOUR-GITHUB-USERNAME` with the account that owns the repository. Clicking this direct asset URL on Android downloads the APK file after the release has been published.
9. Tap the downloaded APK to install. Android may ask you to permit installation from the browser you used, and you must approve the install prompt manually. Android will not silently install an app just because its link was clicked.

The workflow creates a debug-signed APK, which is installable for personal testing. GitHub-hosted builds use a new CI debug signing identity on separate runs; to install a later build signed differently, uninstall the previous build first. Do not distribute a debug-signed build as a public production app. Publishing a GitHub Release with `GITHUB_TOKEN` requires the repository to permit Actions to create releases; if the upload succeeds but release publication is denied, check **Settings → Actions → General → Workflow permissions** and allow the workflow's repository write permission.

## Validation and honest limitations

The Python engine module imports on a desktop test environment with the Android-only import shims, and its live frame analyzer was tested against OpenCV on a deterministic synthetic 100-candle fixture. The regression script is `tests/test_analysis_parity.py`. The included GitHub workflow installs Python smoke-test dependencies, runs source syntax checks and the end-to-end PNG-to-engine bridge test, then attempts a real Gradle/Android SDK APK build. See `VALIDATION_REPORT.md` for the checks run here and the explicit checks which still require GitHub's Android build runner and a real phone.

I cannot claim a phone-tested APK until the GitHub Actions build succeeds and the resulting `Mputra.apk` is installed and exercised on the target phone. The current environment does not include the Android SDK/Gradle installation or network access needed to produce a locally built APK here. Real broker screenshots, device-specific screen-capture behavior, thermal/performance limits, and long-running live sessions must be validated on the actual phone. If a future build log reveals a dependency or device-specific error, fix that error before using the app for trading.

## Run the deterministic analysis parity test locally

Install the desktop dependencies required by the original code, then run:

```bash
python tests/test_analysis_parity.py
```

This verifies matching direction/score, candle counts, pattern count and confirmation metrics for the synthetic fixture. It is a regression test, not proof of live-trading accuracy.
