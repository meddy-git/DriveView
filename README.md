# DriveView (Android app)

Fullscreen split-screen viewer for the ESP32-CAM car, made for a JioDive-style headset.
It receives the video over UDP (no waiting for confirmations, so a slow phone Wi-Fi can't cause lag)
and holds a Wi-Fi lock so the phone's Wi-Fi never goes into power saving while the app is open.

Works with the `DriveViewCar.ino` sketch (UDP port 8888).

## Build it
1. Install Android Studio. Open this folder (`DriveViewApp`) as a project and let Gradle sync
   (if it offers to upgrade the Android Gradle Plugin / Gradle, accept).
2. On your phone: Settings > About phone > tap "Build number" 7 times, then enable Developer options > USB debugging.
3. Plug the phone in and press Run, or use Build > Build APK(s) and copy the .apk to the phone.

## Use it
1. Flash `DriveViewCar.ino`. On the phone join the car's Wi-Fi (`MyWiFiCar`).
   Choose "stay connected" if Android warns there is no internet.
2. Open the DriveView app. It connects to the car automatically and keeps the screen on.
3. Tap the black gap between the two views = light on/off.
4. Long-press anywhere = settings (car address, lens correction, zoom, flips, light level).

The car address defaults to 192.168.4.1 (the ESP32's own Wi-Fi address).
