# OsmAnd Companion

An Android companion app for [OsmAnd](https://osmand.net) with two features:

- **Trip summaries**: when you **finish** a trip recording, a summary goes to your Telegram chat.
- **Transit timetables (Estonia)**: OsmAnd shows public transport stops and routes but no timetables.
  This adds them right on OsmAnd's map, from [peatus.ee](https://peatus.ee).

Both run on one connection to OsmAnd and one quiet background notification, which shows while either is on.

## Setup

1. Build and install (see below), open **OsmAnd Companion**, tap *Connect*.
2. In OsmAnd open *Menu → Plugins* and tap **OsmAnd Companion — Third-party app** so it turns orange.
   OsmAnd blocks third-party apps until you do this.
3. Turn on the features you want in the *Trips* and *Timetables* tabs.

## Trip summaries

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

1. In Telegram, create a bot with [@BotFather](https://t.me/BotFather) and paste its token into the *Trips* tab.
2. Send `/start` to your bot, then tap *Detect* to fill in your chat id. *Send test* checks both.
3. Turn on **Send a summary when a recording is saved**.

### How it works

The API has no "recording finished" event, and the recording state isn't readable through it. What *is*
readable is the list of saved tracks with their statistics (`getImportedGpx`). When a recording is finished,
OsmAnd writes it to `tracks/rec/` (or `tracks/rec/yyyy-MM/`), so the feature:

- checks that list every 30 s, and right away whenever OsmAnd (re)connects;
- reports each track under `rec/` that ended after you first turned it on and hasn't been reported yet
  (`TrackWatcher`). Tracks are recognized by start/end time and point count, so renaming the file in the
  dialog OsmAnd shows after *Finish* doesn't report it twice;
- sends the message through WorkManager (`TelegramWorker`), so a trip that ends without signal is delivered once
  the network is back.

Limitation: Trip recording → *Options → Save* ("save and keep recording") writes the same kind of file to
`tracks/rec/` as *Finish*, and OsmAnd doesn't tell apps whether recording continued, so that is reported too.
Use *Finish* to end a trip.

## Transit timetables (Estonia)

Turn on **Show timetables in OsmAnd** in the *Timetables* tab. Then, in OsmAnd:

| Where in OsmAnd | What you get |
| --- | --- |
| **Map** | The stops around the map center, while it's in Estonia: colored dots from zoom 13, vehicle icons from 15. |
| **Tap a stop** | OsmAnd's own context menu: stop name, which way it goes ("Bus stop · to Pelguranna"), and the next departures as detail rows: `20:14  5 → Metsakooli · 3 min · live`. When a stop is served both ways, OsmAnd's *What's here* list tells the two sides apart by direction. |
| **Stop menu → Next departures** | Reloads them right now, 10 of them. |
| **Stop menu → Full day** | Opens the rest of today by route in a sheet over OsmAnd's map, in OsmAnd's colors (dark when OsmAnd's map is). Tap a time for that trip. |
| **Stop menu → Show in Companion** | Opens the stop's full timetable in this app (the opposite of *Show in OsmAnd*) |
| **Configure screen → widgets → Next departure (peatus.ee)** | Next departure from the stop you last used a button on (or the one nearest the map center), e.g. `5 · 3 min`. Tap it for that stop's full timetable in this app. |
| **Main menu → Transit timetables** | Opens this app's stop search. |
| **Configure map** | The *OsmAnd Companion* item shows or hides the stops. |

*Full day* and *Show in Companion* open this app's screens while OsmAnd is in front, which Android (10+) only
allows an app that may **display over other apps**; the *Timetables* tab asks for it. Without it, they post a
notification to tap instead.

In this app, a stop's timetable has the live next departures, then every route's times for today or any of the
next 6 days, laid out by hour like the timetables at Estonian stops. Tap a departure, a minute or a route to see
that trip: every stop along the route with its time (the route's timetable). *Show in OsmAnd* moves OsmAnd's map
to the stop.

### Why it lives there

Plugins can't change OsmAnd's built-in transport stops or their menus: the AIDL API only lets an app add its
own map layer, buttons in the menu of *its own* points, map widgets and main menu items. So the feature brings
its own stops (`addMapLayer`) whose tap opens OsmAnd's standard context menu. That's the spot where you already
look at a stop, and the menu's detail rows hold the departures. Buttons are added with `addContextMenuButtons`.
After a button press, `updateMapPoint(..., updateOpenedMenuAndMap = true)` redraws the open menu with the
answer. The full-day timetable doesn't fit a menu, so it's one tap away in the app, reached from the widget
or the main menu. OsmAnd starts those itself; Android doesn't let a background app open a screen.

### How it works

- **Data**: peatus.ee runs OpenTripPlanner on Estonia's national GTFS feed (all buses, trams, trains and ferries,
  with live times where the operator sends them, e.g. Tallinn). `PeatusClient` uses its GraphQL endpoint
  (`api.peatus.ee/routing/v1/routers/estonia/index/graphql`): `stopsByRadius` with departures for the map,
  `stop` for Next departures, `stoptimesForServiceDate` for a day's timetable, `trip` for a route's stops, `stops`
  for the search. No API key and no download of the whole feed.
- **Following the map**: every 4 s `TimetableFeature` asks OsmAnd where its map is (`getAppInfo`). Only while the
  map is on screen and in Estonia, it loads the stops within 1.2 km. It loads again once the map moves 400 m,
  or after a minute for fresh departures. When OsmAnd is in the background, nothing is fetched.
- **Icons**: OsmAnd takes a point's picture only as a URI, so `StopIconProvider` renders one PNG per vehicle type.
- OsmAnd keeps layers, widgets and buttons in memory only, so they're added again whenever OsmAnd (re)connects.
- A trip's last stop is also a "departure" in the feed (towards the stop itself); those are dropped.

## Code map

```
app/                      shell: home screen with a tab per feature, OsmAnd status, log
core/                     OsmAndConnection, CompanionService (keeps the process alive), BootReceiver, AppLog
feature/routelogger/      RouteLoggerFeature, TrackWatcher, TrackSummary, Telegram, RouteLoggerFragment
feature/timetable/        TimetableFeature, OsmAndStopUi (everything shown inside OsmAnd), PeatusClient,
                          StopActivity, TripActivity, TimetableFragment, StopIconProvider
```

A feature implements `BackgroundFeature` (`isEnabled`, `start()`, `stop()`) and is listed in `CompanionApp`.
`CompanionService.update()` runs the service while any feature is on. The service restarts after a reboot
or an app update.

The application id is still `dev.maksim.routelogger`, so updating from the Route Logger-only version keeps
its settings and its permission in OsmAnd → Plugins.

## Build & run

Requirements: JDK 25, Android SDK in `~/Android/Sdk`.

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Useful while testing on an emulator:

```bash
adb emu geo fix 24.7453 59.4372          # fake GPS at Tallinn Old Town (lon lat)
adb logcat -s Companion                  # this app's log
adb logcat | grep OsmandAidlService      # OsmAnd's side: shows "enabled: true/false" per call
```

*Resend latest* in the *Trips* tab sends the newest recorded track again, handy for checking the message.

## Stack

AGP 9.4 (built-in Kotlin), Gradle 9.8, compileSdk/targetSdk 36, minSdk 24, WorkManager,
`net.osmand:android-aidl-lib:master-snapshot` from OsmAnd's Ivy repo (`builder.osmand.net`).
Timetable data: peatus.ee (Transpordiamet).
