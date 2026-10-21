# Camera Finder

A small, self-contained app to help you spot **possible hidden cameras** on a
Wi-Fi network you're using — for personal safety (hotels, rentals, etc.).

It **observes only**. It lists the devices on your local network, reads the
hardware maker from each MAC address, and checks whether the network ports that
cameras commonly expose are open. It never logs into, sends commands to, or
changes anything on another device.

## Run it

Double-click **`START.bat`**, or from a terminal:

```bash
python app.py
```

Your browser opens at `http://127.0.0.1:8765`. Click **Scan my network**.
Only Python 3 is required — no extra packages, no installation.

## How to read the results

| Status | Meaning |
|---|---|
| **Likely camera** (red) | Camera-brand hardware and/or a video-stream port (RTSP 554) is open. |
| **Worth checking** (amber) | One camera signal present — look closer. |
| **No signals** (green) | Nothing camera-like detected. |
| Your router / This computer | Infrastructure, shown for completeness. |

Click any row for details and the reasons behind its status.

## What it checks

- **MAC vendor** — matched against a built-in list of camera / smart-home
  brands (Hikvision, Dahua, Reolink, Wyze, Ring, Nest, Tapo, and more).
- **Camera ports** — RTSP (554/8554), ONVIF (2020/8899), and vendor services
  (Hikvision 8000, Dahua 37777, Xiongmai 34567). **RTSP 554 is the strongest
  single signal** that a device streams video.
- **Hostname** — names containing `cam`, `ipc`, `nvr`, `doorbell`, etc.

## Important limits (please read)

- A flag means **"worth checking," not proof.** Confirm physically.
- A camera can be **missed**: it may be on a *different* network (its own
  hotspot or a guest VLAN), powered by battery and asleep, recording to a local
  SD card with no open ports, or using an unlisted MAC vendor. **A clean scan
  does not guarantee there are no cameras.**
- The MAC-vendor list is hand-curated and incomplete.

## Physical checks the network can't do

Network scanning only finds cameras that are on the *same* network and awake.
Also do a physical sweep:

- Turn off the lights and slowly scan the room with a **phone flashlight** —
  camera lenses give a small bright reflection.
- Look hard at smoke detectors, alarm clocks, chargers, vents, mirrors, and
  decor — the usual hiding spots.
- A **phone camera** can often see a camera's infrared LEDs as faint
  purple/white dots in the dark.
- If your phone has one, an **RF detector** app or a dedicated detector finds
  wireless cameras that aren't on your Wi-Fi at all.

## A note on the "make the camera blink" idea

There's no safe or reliable way for an app to send a signal that makes an
unknown camera blink or light up. That would require logging into or exploiting
a device you don't own, which isn't self-defense — and real hidden cameras
usually don't respond to anything like that anyway. Detection (what this app
does) plus a physical sweep is the approach that actually works.
