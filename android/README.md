# Camera Finder (Android app) — v1.1

A phone app for checking a hotel room, rental or changing room for hidden
cameras. It has five tools, and each one catches a different kind of camera:

| Tab | Catches | How |
|---|---|---|
| **1 Network** | Cameras on the room's Wi-Fi | Lists devices on the network you joined; checks camera ports (RTSP 554 etc.) |
| **2 Hotspots** | Cameras on **their own Wi-Fi hotspot**, or behind a portable **4G router** | Lists nearby Wi-Fi networks; flags camera-style names (HDWiFiCam, V380 "MV…", Yoosee "GW_AP…", IPCAM…), camera/IoT-chip makers, hidden networks and very strong (in-room) signals |
| **3 Locate** | **Where** a flagged hotspot is | Pick a hotspot (from the Hotspots tab) and walk; the phone re-measures its signal, beeping faster and filling a radar as you close in. Works only on a device broadcasting its own Wi-Fi; it never connects to it |
| **3 Lens finder** | **Offline / asleep / SD-card** cameras | Flashlight + red contrast filter; circles small bright dots that stay put (a lens reflects light straight back) |
| **3 Infrared check** | **Night-vision** cameras | Camera view in the dark shows IR LEDs as purple/white dots (with a TV-remote test) |
| **4 Magnet** | **Any powered electronics** hidden in objects, even with no network | Phone's compass sensor; beeps faster near electronics |
| **5 Checklist** | Everything else | Guided physical sweep of the usual hiding spots |

It only **observes**. It never logs into, controls or records any device, and
nothing it finds is uploaded anywhere.

## Install / update

1. Copy `camera-finder.apk` to the phone and tap it. Allow "install from this
   source" if asked. It installs over v1.0 as an update (same signing key).
2. Open **Camera Finder**.

Permissions it asks for, and why:
- **Location**: only on the Hotspots tab. Android shows nearby Wi-Fi networks
  only to apps that have it. The app never reads or stores your location.
  Location must also be switched **on** in quick settings while you scan.
- **Camera**: only for the Lens / Infrared finder. Nothing is recorded or saved.

## Sweeping a room (about 10 minutes)

1. **Network**: join the room's Wi-Fi, tap Scan.
2. **Hotspots**: tap Scan. If anything is flagged, open it and tap **Locate this**.
3. **Locate**: walk slowly; follow the beeps and the radar to where the signal peaks.
4. **Lens finder**: lights off, sweep each wall slowly. Inspect every red circle.
5. **Infrared**: still in the dark. Do the TV-remote test first.
6. **Magnet**: pass the top of the phone over smoke detectors, clocks, chargers,
   frames, plants, tissue boxes and hooks.
7. **Checklist**: tick off the physical checks.

**If you find a camera:** don't damage it, because it's evidence. Photograph it
where it is, cover it, leave if you feel unsafe, and report it to the booking
platform and the police.

## Honest limits

These tools together cover the common hiding methods, but no app can promise a
room is clean:
- **Hotspots**: Android allows about 4 fresh Wi-Fi scans every 2 minutes, so
  hunting by signal strength is slow. Turning off "Wi-Fi scan throttling" in
  Developer options makes it faster. A camera on cellular (4G) only, with no Wi-Fi,
  won't show up here; use the Lens and Magnet tabs instead.
- **Lens finder**: works best in a dark room. Glass, jewellery and screws can
  glint too. The app only circles what's worth a closer look.
- **Infrared**: only finds cameras with IR night vision switched on. Some phone
  cameras block IR, which is what the remote test checks.
- **Magnet**: speakers, magnets, metal and in-wall wiring also move the reading.
  Compare similar objects.
- **Network**: modern Android hides other devices' MAC addresses, so brand names
  often show "Unknown". Open camera ports are the main signal.

## Build

Requires JDK 17 or 21, and an Android SDK with `build-tools;34.0.0` and
`platforms;android-28` (set `ANDROID_HOME`, or keep a `tools\sdk` folder in a
parent directory).

```
powershell -ExecutionPolicy Bypass -File android\build.ps1
```

The pipeline is `aapt2 → javac --release 8 → d8 → zipalign → apksigner` (no
Gradle). For a release build, set `CAMFINDER_KEYSTORE` and
`CAMFINDER_KEYSTORE_PASS`, or put the password in a `keystore.pass` file next to
`build.ps1`. Both are git-ignored, so they're never committed. Without a release
key, the build signs with a local debug key.

Source:
- `src/net/camerafinder/`: `Scanner` (network), `HotspotScanner`,
  `LensActivity` (lens + IR + glint detection), `Magnet`, `MainActivity` (bridge)
- `assets/index.html`: the UI
