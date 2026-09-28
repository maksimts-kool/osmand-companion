# Route Logger for OsmAnd

An Android companion app for [OsmAnd](https://osmand.net). When you **finish** a trip recording
(Trip recording → *Finish*, which stops and saves the GPX), it sends the track's summary to your Telegram chat:

```
🏁 Trip recorded
📅 Mon 28 Sep 2026, 17:02 – 18:15
📏 Distance: 12.34 km
⏱ Duration: 1:13:05 (moving 58:40)
🚀 Avg speed: 10.1 km/h (moving 12.6 km/h), max 31.2 km/h
⛰ Elevation: ↑ 123 m ↓ 118 m (12 m – 87 m)
📍 1234 points, 3 waypoints
📁 rec/2026-09-28_17-02_Mon.gpx
```

Pausing sends nothing. The statistics are the ones OsmAnd calculates for the saved track.

## Setup

1. Build and install (see below), open **Route Logger**, tap *Connect*.
2. In OsmAnd open *Menu → Plugins* and tap **Route Logger — Third-party app** so it turns orange.
   OsmAnd blocks third-party apps until you do this.
3. In Telegram, create a bot with [@BotFather](https://t.me/BotFather) and paste its token into the app.
4. Send `/start` to your bot, then tap *Detect* to fill in your chat id. *Send test* checks both.
5. Turn on **Send a summary when a recording is saved**. A quiet notification stays while it's on.

## How it works

OsmAnd plugins are separate apps that bind to OsmAnd's AIDL service (`net.osmand.aidl.OsmandAidlServiceV2`).
The API has no "recording finished" event, and the recording state isn't readable through it. What *is*
readable is the list of saved tracks with their statistics (`getImportedGpx`). When a recording is finished,
OsmAnd writes it to `tracks/rec/` (or `tracks/rec/yyyy-MM/`), so the app:

- runs a foreground service (`WatcherService`) that keeps the connection to OsmAnd and checks the list every 30 s,
  and right away whenever OsmAnd (re)connects;
- reports each track under `rec/` that ended after you first turned watching on and hasn't been reported yet
  (`TrackWatcher`). Tracks are recognized by start/end time and point count, so renaming the file in the
  dialog OsmAnd shows after *Finish* doesn't report it twice;
- sends the message through WorkManager (`TelegramWorker`), so a trip that ends without signal is delivered once
  the network is back.

The watcher restarts after a reboot or an app update if it was on.

### Limitation

Trip recording → *Options → Save* ("save and keep recording") writes the same kind of file to `tracks/rec/`
as *Finish*, and OsmAnd doesn't tell apps whether recording continued, so that is reported too.
Use *Finish* to end a trip.

Code map:

- `OsmAndConnection.kt`: binding and the access check.
- `TrackWatcher.kt`: finds newly saved recordings.
- `TrackSummary.kt`: the message text.
- `Telegram.kt`: Bot API client (`sendMessage`, chat id detection) and the retrying worker.
- `WatcherService.kt`, `BootReceiver.kt`: keep the watcher running.
- `MainActivity.kt`: settings screen and log.

## Build & run

Requirements: JDK 25, Android SDK in `~/Android/Sdk`.

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Useful while testing on an emulator:

```bash
adb emu geo fix 24.7453 59.4372          # fake GPS at Tallinn Old Town (lon lat)
adb logcat -s RouteLogger                # this app's log
adb logcat | grep OsmandAidlService      # OsmAnd's side: shows "enabled: true/false" per call
```

*Resend latest* in the app sends the newest recorded track again, handy for checking the message.

## Stack

AGP 9.4 (built-in Kotlin), Gradle 9.8, compileSdk/targetSdk 36, minSdk 24, WorkManager,
`net.osmand:android-aidl-lib:master-snapshot` from OsmAnd's Ivy repo (`builder.osmand.net`).
