# NetView 📡

A modern Android app that shows detailed serving-cell parameters, carrier aggregation, and GPS — for every SIM on your phone.

## Features

- 📶 **Network type** — 2G / 3G / 4G / 5G NSA / 5G SA
- 🗼 **Serving cell** — PCI, EARFCN, eNB ID, Sector ID, TAC, Band
- 📊 **Signal** — RSRP, RSRQ, SINR, RSSI, CQI, TA
- 🔀 **Carrier Aggregation** — PCell / SCell, per-CC band, bandwidth, PCI, frequency (Android 12+)
- 📱 **Multi-SIM** — Separate tab per SIM
- 📞 **Voice tech** — VoLTE / VoNR / CS, IMS registration
- 🌍 **GPS** — Latitude / longitude / accuracy / altitude
- 🎨 **Material You** — Adaptive colors from your wallpaper
- ⚙️ **Tunable refresh** — 1 to 60 seconds in Settings

## Requirements

- Android 8.0+ (API 26)
- An active SIM
- Location permission (required by Android to expose cell IDs)

## Building the APK (no Android Studio needed!)

This project builds in GitHub Actions automatically. To set it up:

### 1. Create a free GitHub account

Sign up at [github.com](https://github.com) if you don't have one.

### 2. Create a new repository

- Click **+ → New repository**
- Name it `NetView` (or anything you like)
- Set to **Public** (required for free GitHub Actions)
- **Don't** initialize with a README

### 3. Upload this project

**Option A — Via web upload:**
- On your new empty repo page, click **uploading an existing file**
- Drag the entire NetView folder contents (not the outer folder)
- Commit changes

**Option B — Via git (if installed):**
```bash
cd NetView
git init
git add .
git commit -m "Initial commit"
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/NetView.git
git push -u origin main
```

### 4. Wait for the build

- Go to your repo → **Actions** tab
- You'll see "Build NetView APK" running
- Takes ~3–5 minutes the first time

### 5. Download the APK

- Click the completed build
- Scroll to **Artifacts** → click `NetView-APK` to download
- Unzip → you have `NetView-debug.apk`

### 6. Install on your phone

- Transfer the APK to your phone
- Open it — Android may ask you to allow "Install unknown apps" for your file manager
- Tap **Install**

## Publishing a release

Push a git tag like `v1.0.0` to automatically create a GitHub Release with the APK attached:

```bash
git tag v1.0.0
git push origin v1.0.0
```

## Roadmap (planned)

- **Pro / Tier C features**
  - Samsung Service Mode quick-launcher (SIB params via *#0011#*)
  - Signal history graph
  - CSV export / drive-test logging
  - Map view with cell tower lookup (OpenCelliD)
  - Neighbor cell display
- Play Store release with signed AAB

## Notes & Limitations

- **SIB info (RS Power, P-Max, Q-RxLevMin, etc.)** is not accessible via public Android APIs — Samsung's hidden Service Mode (`*#0011#`) is needed for those.
- **Carrier aggregation** requires Android 12+. On older devices, that section shows "Not available".
- **VoLTE/VoNR detection** is heuristic — depends on what the OEM exposes.
- Some Samsung/Xiaomi devices may restrict `TelephonyManager` queries even with granted permissions.

## License

MIT
