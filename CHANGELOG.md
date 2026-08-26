# Changelog

All notable changes to NetView are documented here.

## v3.4.7

**Compliance**
- Target Android 16 (API level 36) to meet the Google Play target-API requirement. The app already handled the Android 16 edge-to-edge behaviour (see v3.4.6).

**CM dump**
- Support the updated 4G CMExport Summary format. The cell name now comes from the `LNCEL name` column (band + sector, e.g. `..._L8_A`). Column-header matching is now case-insensitive so minor header re-casing no longer breaks loading.

## v3.4.6

**Cell Change Log**
- Each entry is now tappable to expand and show the serving cell's name from a loaded CM dump (LTE/WCDMA/GSM).
- The log is now session-only (in-memory, last 200 changes) instead of persisting across days. CSV export is unchanged.

**Dual-SIM fixes**
- Fixed one SIM's Cell Change Log showing the other SIM's cell changes on single-modem dual-SIM devices. The serving cell is now trusted only from each SIM's own registration, so genuine same-operator co-location is no longer mis-suppressed.
- Neighbour lists no longer show another SIM's serving cell bleeding through the shared cell list.

**Display**
- Brighter, higher-contrast signal-quality colours on dark theme (especially green).

**Under the hood**
- Removed window attributes deprecated in Android 15 (edge-to-edge is handled at runtime).

## v3.4.5

**Signal**
- Signal values are now colour-coded red / amber / green by quality (RSRP, RSRQ, SINR for LTE; SS-/CSI- metrics for NR; RSCP / Ec-No for WCDMA), on both the Signal and 5G NR Leg cards.

**Cell Change Log (new)**
- Optional logging (Settings toggle) that records one entry each time the serving cell changes — handover, reselection, or initial camp.
- Each entry captures the RAT transition (e.g. 4G→3G), eNB/sector, PCI, band, ARFCN, colour-coded RSRP/RSRQ/SINR, and the GPS point where it happened.
- Per-SIM timeline opened from a history icon in the top bar (shown when logging is on).
- Export the log as CSV (Excel-ready, includes latitude/longitude).
- Persisted to internal storage and reloaded on startup, so a long drive survives the app being closed or killed.

**Drive-test helpers (new)**
- **Keep Screen Awake** — keeps the screen on while NetView is open.
- **Background Status Notification** — optional silent notification showing the serving cell (CM-dump name if loaded), RSRP/RSRQ/SINR and carrier-aggregation count while the app is in the background. Best-effort; not a foreground service.

**Battery**
- Monitoring (cell + GPS polling) now runs only while the app is in the foreground.
- GPS update interval follows the configured refresh rate instead of a fixed 1-second poll.

**Fixes & cleanup**
- Shared snapshot footer showed a hardcoded old version number; it now reports the real app version.
- Debug Log screen gained a Pause/Resume control and smarter auto-scroll (scrolling up to read no longer snaps you back to the bottom).
- Removed leftover code and a build setting that triggered a Play Console "missing debug symbols" warning.

## v3.4.4
- Removed the abandoned home-screen widget and its background service, along with the related foreground-service and notification permissions.

## v3.4.3
- Fixed the "Grant permissions" button when permissions had been permanently denied — it now opens App Settings instead of doing nothing.
- Added Play Store / store listing metadata.

## v3.4.2
- Larger widget text; show download speed and latency in dual-SIM mode.

## v3.4.1
- Widget site names; skip the config screen when background monitoring is on.

## v3.4.0
- Redesigned widget layout with signal metrics as the primary focus.
