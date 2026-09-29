# OsmAnd Companion

An Android companion app for [OsmAnd](https://osmand.net):

- **Transit timetables (Estonia)**: OsmAnd shows public transport stops and routes but no timetables.
  This adds them right on OsmAnd's map, from [peatus.ee](https://peatus.ee).

It keeps one connection to OsmAnd and one quiet background notification, which shows while a feature is on.
Trip summaries to Telegram live on the `feature/trips` branch for now.

## Setup

1. Install the APK from the [latest release](https://github.com/maksimts-kool/osmand-maksimts/releases/latest)
   (or build it, see below), open **OsmAnd Companion**, tap *Connect*.
2. In OsmAnd open *Menu → Plugins* and tap **OsmAnd Companion — Third-party app** so it turns orange.
   OsmAnd blocks third-party apps until you do this.
3. Turn on the features you want in the *Timetables* tab.

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

## Updates

The app updates itself from this repo's [GitHub Releases](https://github.com/maksimts-kool/osmand-maksimts/releases):

- It asks GitHub for the latest release when it opens (at most every 10 minutes) and once a day in the
  background (WorkManager). The daily check posts a notification once per new version.
- A newer version shows an *Update to x.y.z* button in the header and, once per version, a dialog with the
  release notes. *Log* tab → *Check for updates* checks right away.
- *Update* downloads the APK straight into an Android install session and checks it against the SHA-256
  GitHub reports. The first time, Android asks you to allow *Install unknown apps* for this app, then to
  confirm the update. On Android 12+ later updates usually install without asking.

Android installs an update only if it's signed with the same key as the installed app. Builds made before
2.0.0 was released on GitHub are signed with a local debug key, so uninstall that once and install the
release APK. That clears the app's settings.

## Releases

Bump `appVersion` in `gradle.properties` if you like, then tag and push:

```bash
git tag v2.1.0
git push origin v2.1.0
```

The [Release workflow](.github/workflows/release.yml) builds the APK with the tag's version, signs it and
publishes a GitHub release with notes generated from the merged PRs. Edit the notes on GitHub if needed: the
app shows them in its update dialog. `versionCode` comes from the version (2.1.0 → 20100), so tags must go up
and look like `vMAJOR.MINOR.PATCH` (each part 0–99).

### Signing key

Releases are signed with a key kept out of git. On the maintainer's machine:

- `~/.android/osmand-companion-release.jks`: the key. **Back it up.** Without it, no installed copy can
  be updated, and everyone would have to uninstall and lose their settings.
- `keystore.properties` in the repo root (gitignored): its path and passwords. When it's there, debug builds
  are signed with the same key too, so `adb install -r` and GitHub updates replace each other.

CI gets the same key from the repository secrets `SIGNING_KEYSTORE_BASE64` (the .jks, base64),
`SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`.

## Code map

```
app/                      shell: home screen with a tab per feature, OsmAnd status, log;
                          update/: Updater, GitHubReleases, UpdateWorker, InstallResultReceiver
core/                     OsmAndConnection, CompanionService (keeps the process alive), BootReceiver, AppLog
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

## Stack

AGP 9.4 (built-in Kotlin), Gradle 9.8, compileSdk/targetSdk 36, minSdk 24, WorkManager,
`net.osmand:android-aidl-lib:master-snapshot` from OsmAnd's Ivy repo (`builder.osmand.net`).
Timetable data: peatus.ee (Transpordiamet).
