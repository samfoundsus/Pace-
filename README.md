# Pace - Internet Speed Test

A small, clean internet speed test for Android. It measures download, upload, ping and jitter, and it also shows how well your connection holds up under load. Built with Kotlin and Jetpack Compose, with a Pixel-style Material 3 look.

## What it does

- Download and upload speed, with a live gauge and graph
- Ping, jitter, and loaded latency (ping while downloading and uploading)
- A bufferbloat grade, plus simple ratings for browsing, streaming, video calls and gaming
- Test history saved on your phone, with swipe to delete
- Network info: connection type, Wi-Fi band, link speed and signal, carrier and network type, public IP and ISP
- A DNS benchmark that compares Cloudflare, Google, Quad9 and your current DNS
- An extended test (30 or 60 seconds per phase) with a stability summary
- Share a result as an image, or export it as a PDF
- Material You dynamic color, light and dark themes, and a themed launcher icon

## Privacy

No accounts, no ads and no analytics. Your history stays on your device. To work, the app talks to a few public services: Cloudflare's speed test endpoints and any community or custom LibreSpeed servers you select for tests, plus ipwho.is and ipify to look up your public IP and ISP. Servers you test against, including community ones, can see your IP address and the test traffic. A public server list is fetched from librespeed.org when you open the server selector or run Auto (cached for 24 hours). The DNS benchmark sends ordinary DNS queries to the public resolvers listed above.

The app asks for a permission only when you use the feature that needs it: location, to show your Wi-Fi name, and phone state, to show whether you are on 4G or 5G. Nothing is requested when the app opens. Crash reports stay on the device and are only shared if you copy them.

## Build it

You need JDK 17 and the Android SDK. The minimum Android version is 8.0 (API 26).

Run `./gradlew assembleDebug` and the APK ends up in `app/build/outputs/apk/debug/`.

Every push to GitHub also builds a debug APK with GitHub Actions. Open the latest run under the Actions tab and download `pace-debug-apk` from the artifacts.

## Signed release builds

The workflow builds a signed release APK only when these repository secrets exist: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`. Without them it just builds the debug APK. Never commit a keystore to the repo.

## Notes

- Speeds depend on the test server and your network, so the numbers can differ from other speed test apps.
- An emulator tests the host computer's connection, so check results on a real phone.
