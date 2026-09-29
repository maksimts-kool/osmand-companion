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
| **Stop menu → Full day** | Opens the rest of today by route, soonest first, in a sheet over OsmAnd's map, in OsmAnd's colors (dark when OsmAnd's map is). Tap a time for that trip. |
| **Stop menu → Show in Companion** | Opens the stop's full timetable in this app (the opposite of *Show in OsmAnd*) |
| **Configure screen → widgets → Next departure (peatus.ee)** | Next departure from the stop you last used a button on (or the one nearest the map center), e.g. `5 · 3 min`. Tap it for that stop's full timetable in this app. |
| **Main menu → Transit timetables** | Opens this app's stop search. |
| **Configure map** | The *OsmAnd Companion* item shows or hides the stops. |

*Full day* and *Show in Companion* open this app's screens while OsmAnd is in front, which Android (10+) only
allows an app that may **display over other apps**; the *Timetables* tab asks for it. Without it, they post a
notification to tap instead.

In this app, a stop's header shows the routes serving it as big badges in their own colors (dimmed when they don't
run that day); tap one to jump to its timetable. Below are the live next departures, then every route's times for
today or any of the next 6 days, laid out by hour like the timetables at Estonian stops, the route leaving soonest
first. Each route starts at the hour of its next departure; tapping its header shows the whole day, then closes
it, then goes back to the upcoming hours. Routes done for today go last, closed. Tap a departure or a minute to see
that trip: every stop along the route with its time (the route's timetable). *Show in OsmAnd* moves OsmAnd's map
to the stop.

### Route on OsmAnd's map

A trip's route button shows it in OsmAnd the way OsmAnd shows its own transport routes: the line in the vehicle's
color, from the operator's own shape in today's feed, with a marker per stop, all of it in view, and the route's
card open at the bottom ("Bus 10 → Vana-Pääsküla", every stop with its time). Like OsmAnd's own, it's gone as soon
as the card is closed (a tap elsewhere on the map, or Back): OsmAnd's API has no event for that, so the app asks it
every half second whether its menu is still open (`isMenuOpen`). OsmAnd only takes lines as tracks, so the route is a
track while it's shown, and deleted after. If the app's process dies meanwhile, it's removed the next time a
timetable screen opens, or on the timetable feature's next update.

OsmAnd's API can't open one of its built-in routes, so this is the way to see the current one. Those built-in routes
(tap one of OsmAnd's stops, then a route) come from OpenStreetMap, and many in Estonia are out of date: Elron's R32
is still RE32 there and misses 9 stops, bus 24 still goes through Mustamäe. So before switching, the app asks OSM
(Overpass API) for the route and compares its stops with the trip's; if they differ, it says how, since that's what
tapping OsmAnd's own stops will show.

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
- A trip's last stop is also a "departure" in the feed (towards the stop itself); those are dropped, by the trip's
  last stop rather than its headsign, since some headsigns are wrong.
- **Destinations**: Elron's feed leaves the headsign out on some trips (R32 to Rakvere) and gives others the
  wrong one (R30 from Tallinn to Tapa says "Tallinn"). A headsign that's missing, or names where the trip starts
  rather than where it ends, is replaced by the trip's last stop.

## Start and stop with OsmAnd

*Settings* tab → *With OsmAnd*:

- **Start with OsmAnd**: the features start whenever OsmAnd opens, even if Companion's process was gone (swiped
  away, killed by battery savers, or not started since an update). Android tells no app when another one opens,
  and OsmAnd only talks to apps already connected to it, so this is an accessibility service (`OsmAndWatcher`),
  which the user turns on in Android's settings. It only gets the package of each window that comes to the front,
  never the window's contents, and keeps nothing. On Android 13+ a sideloaded app first needs *App info → ⋮ →
  Allow restricted settings*.
- **Stop when OsmAnd closes** (needs the above): a minute after OsmAnd was last in front, the service and its
  notification stop, and Companion lets go of OsmAnd so it can close too (while bound, Android keeps it running).
  Companion's own screens over OsmAnd and keyboards don't count as leaving; before stopping it also asks OsmAnd
  whether its map is on screen, since unlocking the phone doesn't always report which app is in front, and it
  waits while the screen is off.

`FollowOsmAnd.wantsService` decides whether `CompanionService` runs: a feature is on, and, with both settings on,
OsmAnd is in use.

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
core/                     OsmAndConnection, CompanionService (keeps the process alive), BootReceiver, AppLog,
                          OsmAndWatcher + FollowOsmAnd (start and stop with OsmAnd)
feature/timetable/        TimetableFeature, OsmAndStopUi (everything shown inside OsmAnd), PeatusClient,
                          StopActivity, TripActivity, TimetableFragment, StopIconProvider,
                          OsmAndRoute (a trip's route in OsmAnd, gone with its card), OsmRouteCheck (is OSM's route current?),
                          States (Lottie loading/empty/error)
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
`net.osmand:android-aidl-lib:master-snapshot` from OsmAnd's Ivy repo (`builder.osmand.net`), Lottie.
Timetable data: peatus.ee (Transpordiamet). Route check: OpenStreetMap contributors, via the Overpass API.

## Credits

Animations from LottieFiles, free under the [Lottie Simple License](https://lottiefiles.com/page/license),
recolored in the app to fit its theme (`feature/timetable/src/main/res/raw`):

- Loading: [Bus Loader](https://lottiefiles.com/free-animation/bus-loader-LF8V0uZBm4) by Bijay Subba Limbu
- Couldn't load: [No Internet Connection](https://lottiefiles.com/free-animation/no-internet-connection-jWCR3yXdDT)
- Nothing leaves: [Clock Time](https://lottiefiles.com/free-animation/clock-time-YX86xw76OL)
