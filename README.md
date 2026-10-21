# Camera Finder

Check a hotel room, rental or changing room for hidden cameras, using just your phone.

Camera Finder is a privacy and self-protection tool. It **only observes**: it never logs
into, controls or records any device, and nothing it finds leaves your phone.

**[Download the Android app (APK)](https://github.com/gtapraveen5-design/camera-finder/releases/latest)**

## What it checks

Hidden cameras hide in different ways, so the app has a tool for each one:

| Tool | Finds | How |
|---|---|---|
| **Network** | Cameras on the room's Wi-Fi | Lists devices on the network you joined and checks camera ports (RTSP 554, ONVIF, Hikvision and Dahua services) |
| **Hotspots** | Cameras broadcasting **their own Wi-Fi**, or hidden behind a portable **4G router** | Lists nearby Wi-Fi networks and flags camera-style names, camera/IoT-chip makers, hidden networks and very strong (in-room) signals |
| **Locate** | **Where** a flagged hotspot is | Re-measures a chosen hotspot's signal as you walk; the phone beeps faster and a radar fills as you get closer, like a metal detector (observe-only; no connection is made) |
| **Lens finder** | **Offline, sleeping or SD-card** cameras | Flashlight plus a red contrast filter. It circles small bright dots that stay put, because a camera lens reflects light straight back |
| **Infrared check** | **Night-vision** cameras | Shows a camera's infrared LEDs as glowing dots in the dark |
| **Magnet** | Powered electronics hidden inside objects, with or without a network | Uses the phone's compass sensor, which beeps faster near electronics |
| **Checklist** | Everything else | A guided physical sweep of the usual hiding spots |

A flag means "worth a closer look", not proof. No app can promise a room is clean; used
together, these tools cover the common hiding methods. See
[android/README.md](android/README.md) for a 10-minute room-sweep routine and each tool's
limits.

**If you find a camera:** don't damage it, because it's evidence. Photograph it where it
is, cover it, leave if you feel unsafe, and report it to the booking platform and the
police.

## Install (Android 9 or newer)

1. Download `camera-finder.apk` from the
   [latest release](https://github.com/gtapraveen5-design/camera-finder/releases/latest) on your phone.
2. Open it and allow "install from this source" when Android asks.
3. Open **Camera Finder**.

Permissions it asks for:
- **Location**: only on the Hotspots tab, because Android shows nearby Wi-Fi networks only
  to apps that have it. The app never reads your location.
- **Camera**: only for the lens and infrared finder. Nothing is recorded or saved.

## Repository layout

| Folder | What |
|---|---|
| [`android/`](android/) | The phone app: Java, built with the Android SDK command-line tools (no Gradle) |
| [`desktop/`](desktop/) | A Windows/Python version of the network scan, with a browser UI |

### Build the Android app

Requires JDK 17 or 21, and an Android SDK with `build-tools;34.0.0` and
`platforms;android-28` (point `ANDROID_HOME` at it).

```
powershell -ExecutionPolicy Bypass -File android\build.ps1
```

Without your own signing key, the build signs with a local debug key. For a release
build, set `CAMFINDER_KEYSTORE` and `CAMFINDER_KEYSTORE_PASS`.

### Run the desktop version

Requires Python 3 (standard library only).

```
python desktop\app.py
```

It opens `http://127.0.0.1:8765` in your browser.
