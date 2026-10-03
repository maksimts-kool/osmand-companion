# OsmAnd Companion

An Android companion app for [OsmAnd](https://osmand.net):

- **Transit timetables (Estonia)**: OsmAnd shows public transport stops and routes but no timetables.
  This adds them right on OsmAnd's map, from [peatus.ee](https://peatus.ee).
- **Trip planner on live times (Estonia)**: Google Maps and peatus.ee plan on the timetable. This one plans on where
  Tallinn's buses, trolleybuses and trams and Harjumaa's county buses actually are, and shows the way on OsmAnd's map.

It keeps one connection to OsmAnd and one quiet background notification, which shows while a feature is on.
Trip summaries to Telegram live on the `feature/trips` branch for now.

## Setup

1. Install the APK from the [latest release](https://github.com/maksimts-kool/osmand-maksimts/releases/latest)
   (or build it, see below), open **OsmAnd Companion**, tap *Connect*.
2. In OsmAnd open *Menu → Plugins* and tap **OsmAnd Companion — Third-party app** so it turns orange.
   OsmAnd blocks third-party apps until you do this.
3. Turn on the features you want in the *Timetables* tab. The *Trips* tab needs nothing turned on.

## Transit timetables (Estonia)

Turn on **Show timetables in OsmAnd** in the *Timetables* tab. Then, in OsmAnd:

| Where in OsmAnd | What you get |
| --- | --- |
| **Map** | The stops around the map center, while it's in Estonia: colored dots from zoom 13, vehicle icons from 15. |
| **Tap a stop** | OsmAnd's own context menu: stop name, which way it goes ("Bus stop · to Pelguranna"), and when it was loaded (`Updated 20:08 · peatus.ee`). When a stop is served both ways, OsmAnd's *What's here* list tells the two sides apart by direction. |
| **Stop menu → Next departures** | Opens the next departures like a departure board, up to 12 and none more than an hour away (big minutes to go, then route, destination, time, and live delay where available: those minutes turn green, with a animated live mark) in a sheet over OsmAnd's map, in OsmAnd's colors (dark when OsmAnd's map is). Tap one for that trip. |
| **Stop menu → Full timetable** | Opens the stop's full timetable in this app (the opposite of *Show in OsmAnd*). Back goes back to OsmAnd. |
| **Configure screen → widgets → Next departure (peatus.ee)** | Next departure from the stop you last used a button on (or the one nearest the map center), e.g. `5 · 3 min`. Tap it for that stop's full timetable in this app. |
| **Stop menu → Trip to here** | Plans a trip from where you are to the stop, on live times ([Trip planner](#trip-planner-estonia)). |
| **Main menu → Transit timetables** | Opens this app's stop search. |
| **Main menu → Plan a trip (live)** | Opens the trip planner, to OsmAnd's navigation destination if it has one. |
| **Configure screen → widgets → Trip (live)** | While a trip is being taken: its next step, e.g. `23 · 4 min` over the stop ([trip mode](#trip-mode)). |
| **Configure map** | The *OsmAnd Companion* item shows or hides the stops. |

*Next departures* and *Full timetable* open this app's screens while OsmAnd is in front, which Android (10+) only
allows an app that may **display over other apps**; the *Timetables* tab asks for it. Without it, they post a
notification to tap instead.

In this app, a stop's header shows the routes serving it as big badges in their own colors (dimmed when they don't
run that day); tap one to jump to its timetable. Below is a card per route for today or any of the next 6 days,
the route leaving soonest first. A card starts folded to one line of its next departures, so all the routes fit on
screen: how soon the next one leaves, then the times after it, live where available (in green, with an animated
live mark; Tallinn's city buses, trolleybuses and trams, and Harjumaa's county buses). The hours below show live times too, so a late bus is
under the minute it actually leaves, in green; today's live times refresh every 30 s. Tapping
it unfolds the times from the next departure's hour on, laid out by hour like the timetables at Estonian stops,
then the whole day, then folds it again. Routes done for today go last. Until 6 in the morning, *Today* also has
the night's last runs of the day before, timetabled past 24:00 (25:04), so at 00:40 the
next bus isn't the first one of the morning. Tap a departure or a minute to see
that trip: every stop along the route with its time (the route's timetable), live in green where the vehicle
gives its times, with *Live* once in its header and how late or early (`+2`, `−1`) as the end part of each stop's ETA chip,
with the vehicle drawn where the live times put it, gliding along as time goes by, and over to where fresh
live times put it. *Show in OsmAnd* moves OsmAnd's map
to the stop. Opened from OsmAnd (its stop menu, its widget, or the notification standing in for them), a stop's
timetable is in a task of its own, like the sheet, so Back from it goes back to OsmAnd rather than to this app's home
screen.

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
look at a stop. Buttons are added with `addContextMenuButtons`. The menu's detail rows are only plain text, so the
departures are one button away in sheets of this app's over OsmAnd's map, and the full timetable is in the app,
reached from the stop menu, the widget or the main menu. OsmAnd starts those itself; Android doesn't let a background app open a screen.

### How it works

- **Data**: peatus.ee runs OpenTripPlanner on Estonia's national GTFS feed (all buses, trams, trains and ferries,
  with live times where the operator sends them). `PeatusClient` uses its GraphQL endpoint
  (`api.peatus.ee/routing/v1/routers/estonia/index/graphql`): `stopsByRadius` with departures for the map,
  `stop` for Next departures, `stoptimesForServiceDate` for a day's timetable, `trip` for a route's stops, `stops`
  for the search. No API key and no download of the whole feed.
- **Live times in Tallinn**: peatus.ee has none for Tallinn's city lines, so `TallinnLive` gets them from the
  city's own feed, the one transport.tallinn.ee shows (`transport.tallinn.ee/siri-stop-departures.php?stopid=…`).
  Its stop ids come from the city's stop list (`transport.tallinn.ee/data/stops.txt`, fetched once a day), by the
  code on the stop sign: for older stops the id is peatus.ee's without `estonia:`, but not for newer ones
  (Haabersti is `estonia:141510` on peatus.ee and 5877 in the feed). It's asked for a stop's next departures
  (the stop screen and both sheets) when a Tallinn city bus, trolleybus or tram serves the stop. The feed is per
  stop, so a trip's live times take one request per stop from 20 minutes behind its timetable to 90 minutes
  ahead (the feed predicts about an hour ahead), 6 at a time, every 30 s while the trip is open; the stops after those (also those past the feed's hour, and the last stop, which it never lists) get the last known delay, and those
  before the first one the feed still lists the trip at are behind the vehicle. Its departures are matched to
  peatus.ee's by route, timetabled time (the feed's is to the second, peatus.ee's rounded down to the minute) and
  destination, as at the end of a line the vehicle arriving and the one leaving back can be due the same minute.
  A bus running late has already dropped out of peatus.ee's next departures, so those of the last 20 minutes are
  asked for too. If the feed doesn't answer, the timetable is shown as before.
  The feed forgets a stop as soon as the vehicle has left it, so `LiveMemory` keeps the live time each trip was last
  given at each stop (for the app's lifetime, pruned after 6 hours): a stop behind the vehicle that was seen live, on
  the trip's screen or on a stop's departures (the stop screen, the Next departures sheet), keeps that time, in green,
  about when it left. Ridango has those stops itself.
- **Live times of Harjumaa's county buses**: peatus.ee has none for those either, so `RidangoLive` gets them from
  Ridango's OpenTripPlanner, the journey planner behind [iil.pilet.ee](https://iil.pilet.ee)
  (`wmb-otp-peutk.eu-prod.ridango.cloud/otp/routers/1/index/graphql`). It runs on the same national feed and answers
  the same GraphQL, with live times from the buses that send them (SEBE's and Hansabuss's). It has each of
  peatus.ee's trips twice: `1:` for `estonia:` with only the timetable, and the copy the live times are on, with a
  number in front (`1:74_ATL_…` for `estonia:ATL_…`), which is the one its stops list; so that number is left out to
  match them, and a trip is asked for both ways at once. Its stops are by the code on the sign for some
  (`1:21207-1`) and by peatus.ee's number for others, so a stop is asked for both ways at once, and the one with the
  stop's code wins. It's asked about a stop's
  county bus departures (`REGIONAL`) when it has any, and about a trip's stops all at once when it's a county bus's.
- **Following the map**: every 4 s `TimetableFeature` asks OsmAnd where its map is (`getAppInfo`). Only while the
  map is on screen and in Estonia, it loads the stops within 1.2 km. It loads again once the map moves 400 m,
  or after a minute for fresh departures. When OsmAnd is in the background, nothing is fetched.
- **Icons**: OsmAnd takes a point's picture only as a URI, so `StopIconProvider` renders one PNG per vehicle type.
- OsmAnd keeps layers, widgets and buttons in memory only, so they're added again whenever OsmAnd (re)connects.
- A trip's last stop is also a "departure" in the feed (towards the stop itself); those are dropped, by the trip's
  last stop rather than its headsign, since some headsigns are wrong.
- **Night buses**: peatus.ee has Tallinn's night buses (91–96) a day late. The 96 leaving Vana-Pääsküla at 01:36 in
  the night to Saturday is timetabled on Saturday at 25:36, which would be Sunday's 01:36; the city's live feed has
  it in the night to Saturday, to the second. So their times are moved a day earlier everywhere (their trip and
  service day stay, so they're still found by them), and at night a stop's departures are also asked for a day
  ahead, where peatus.ee has tonight's. In the city's feed they're `nightbus` rather than `bus`.
- **Destinations**: Elron's feed leaves the headsign out on some trips (R32 to Rakvere) and gives others the
  wrong one (R30 from Tallinn to Tapa says "Tallinn"). A headsign that's missing, or names where the trip starts
  rather than where it ends, is replaced by the trip's last stop.

## Trip planner (Estonia)

The *Trips* tab plans a trip by public transport on live times. A bus that left 10 minutes ago by the timetable but is
running 12 minutes late is still offered, if you can walk to it in time; a connection that's gone because the first
bus is late isn't. Planners on the timetable (Google Maps, peatus.ee, transport.tallinn.ee) can't do either.

- **From**: where OsmAnd has you (its last GPS fix, asked again on every plan, so this app needs no location
  permission), else OsmAnd's map center. **To**: OsmAnd's navigation destination if it has one, so anything found
  with OsmAnd's own search works. Either can be searched for instead (addresses, places and stops, via peatus.ee's
  search), or picked from the last 8 used.
- **When**: leave now, depart at, or arrive by.
- **Results**: as Citymapper lists them, walking on its own, then the ways, the one that gets there first on top. Ways
  on and off at the same stops are one row ("walk › 24 / 24A › walk · 27 min → 19:33 · in 11, 22, 33 min from
  Lehola"), in green when the first ride's time is its vehicle's. Changes under 2 minutes, a missed connection, or a
  ride swapped for the next of its line are pointed out. The list is planned again every 30 s while it's on screen.
- **A way's screen**: when to leave and when you're there, then a card per step on a track: each walk, each ride from
  where it's got on to where it's got off (the stops in between a tap away) with live times and how late, and the
  time to change. The first ride's card has the way's other departures to choose from, those that can't be made in
  time dimmed. A ride opens its trip. The header's map button draws it on OsmAnd's map like a trip's route (a track per
  walk and ride, in its color, with the stops on it, and a card with the steps; gone once the card is closed). *Walk
  there with OsmAnd* starts OsmAnd's walking navigation to the first stop. *GO* follows it ([trip mode](#trip-mode)).

In OsmAnd: *Main menu → Plan a trip (live)* opens the tab, to OsmAnd's destination if there is one; a stop's
*Trip to here* button plans from where you are to that stop.

### How it works

One planner finds the ways (`TripPlanner`): **peatus.ee's OpenTripPlanner** (`OtpPlanner`), the national one, for
all of Estonia, trains and intercity buses included.

Leaving now, it's also asked from 15 minutes ago, so a late bus is among what it finds. Its
itineraries then get their live times (`LiveRetimer`): Tallinn city lines from the city's feed at the stop where a ride
is got on and the one where it's got off (the delay carries on where the feed says nothing), county buses from
Ridango's trip, the rest as the planner had them. Each ride is checked against the one before (or the walk to the
first): one that can't be caught any more is swapped for the next of its line from that stop (from the city's feed,
else peatus.ee's departures), and if there's none, the way is dropped. The ways go in one list (`Ranking`): the
same way found twice is shown once, the more live one; a way that leaves no later and gets there no
sooner than another (a change counted as 4 minutes) is left out. The feeds' answers are shared for 20 s (`LiveFeeds`).
Ways on and off at the same stops are shown as one (`Way`), as Citymapper does: "24 / 24A · in 11, 22, 33 min from
Lehola", and on the way's screen its departures are there to choose from.

### Trip mode

*GO* on a way's screen follows that way until you're there, with the screen off and OsmAnd closed too
(`TripFeature`, in the background service like the timetables, but kept running without OsmAnd):

- **The next step**, in a notification that counts down to it: when to leave ("Leave in 4 min · Walk 3 min to
  Tõnismägi for Bus 67 at 15:41"), when the ride comes (with how late, live), where to get off and how many stops to
  go, then the last walk. Its buttons: *New way* plans again from where OsmAnd has you, *Stop trip* ends it.
- **The same in OsmAnd**: a *Trip (live)* widget ("23 · 4 min" over the stop), which OsmAnd lists under
  *Configure screen → widgets* once a trip has started; tap it for the trip's screen. And at the top of the Trips tab.
- **Alerts**, each once: *Leave now* (90 s before), the next ride's delay each time it changes by 2 minutes or more
  ("Bus 67 is now 5 min late · leave at 15:39"), and *Get off at the next stop* (2 minutes before).
- **A connection that's gone**: every 20 s the rides get their live times. If the next ride can't be caught any more
  (the city's feed has it gone already, or the ride being taken gets in too late for it, the walk between counted), it
  plans again (at most every 90 s): from where that ride gets off, at its time, or from where OsmAnd has you if it was
  the first. The new way takes the old one's place, with an alert saying what to take now; if there's none, an alert
  says so, once.
- **The live screen** (`LiveTripActivity`), opened by *GO*, the notification, the widget or the Trips tab: Citymapper's
  GO without its map. Time left and when you're there, how far along, and what to do now, one step at a time (walk,
  board, ride, walk, there; `TripSteps`), with *Prev* and *Next* to look at the others. Before the first ride leaves,
  its other departures can be switched to; on a ride, its stops and a *Get off alert* switch.
- It ends 3 minutes after you're there, or with *Stop trip*. The trip is kept in the app's files (`TripStore`), so it
  carries on if Android kills the app on the way, or it's updated.

Nothing tells it where you are on the way: a ride whose time has come is taken as being ridden. So it can't tell you
missed the first bus unless its feed says it left early; *New way* is for that.

## Start and stop with OsmAnd

*Settings* tab → *With OsmAnd*:

- **Start with OsmAnd**: the features start whenever OsmAnd opens, even if Companion's process was gone (swiped
  away, killed by battery savers, or not started since an update). Android tells no app when another one opens,
  and OsmAnd only talks to apps already connected to it, so this is an accessibility service (`OsmAndWatcher`),
  which the user turns on in Android's settings. It only gets the package of each window that comes to the front,
  never the window's contents, and keeps nothing. On Android 13+ a sideloaded app first needs *App info → ⋮ →
  Allow restricted settings*. Android turns the service off when the app is force-stopped (App info → Force stop),
  so it has to be turned on again after that; being swiped away, killed or updated doesn't.
- **Stop when OsmAnd closes** (needs the above): a minute after OsmAnd was last in front, the service and its
  notification stop, and Companion lets go of OsmAnd so it can close too (while bound, Android keeps it running).
  Companion's own screens over OsmAnd and keyboards don't count as leaving; before stopping it also asks OsmAnd
  whether its map is on screen, since unlocking the phone doesn't always report which app is in front, and it
  waits while the screen is off. A trip being taken ([trip mode](#trip-mode)) keeps it all running until it ends.

`FollowOsmAnd.wantsService` decides whether `CompanionService` runs: a trip is being taken, or a feature is on and,
with both settings on, OsmAnd is in use.

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

## Crash reports and usage stats

Off until the user opts in. The home screen asks once (*Share* / *No thanks*), and *Settings* → *Privacy* has
the switch. A build without Sentry's DSN (below) has none of it, and never asks.

All of it goes to [Sentry](https://sentry.io) (`sentry-android-core`, without NDK or session replay):

- **Crashes**: crashes, freezes (ANRs) and crash-free sessions per release, with the app's log lines as
  breadcrumbs. No IP address, user or device name.
- **Usage counts**: Sentry metrics (*Explore → Metrics*), counted by `Analytics.signal(name, params)`, which can
  be grouped by their parameters. No location, stops or searches.
- **Traces** (*Explore → Traces*): app start, screen loads with slow and frozen frames, and requests to
  peatus.ee (by query field, e.g. `POST api.peatus.ee stopsByRadius`), transport.tallinn.ee, Ridango and Overpass, timed
  by `Analytics.timed(...)`. Inside the screen loading at the time, else on their own. All in debug builds, 20% in
  releases. The screens OsmAnd opens are timed from the tap until they show what they're for, by
  `Analytics.screenLoad(...)`: the Next departures sheet (`Next departures sheet`)
  and a stop's timetable (`Stop timetable`, from wherever it's opened; from OsmAnd's widget, from when it starts).
  Sentry's own timing of them starts at whenever one of this app's screens last paused, which for a screen opened
  from OsmAnd means nothing.

All of it carries the release, Android version, phone model and Sentry's random id for the install (`user.id`, so a
metric can count unique users). Opting out deletes that id and anything not yet sent.

| Metric | When | Parameters |
| --- | --- | --- |
| `App.opened` | Home screen opens (3 s later) | `osmand` (missing / disconnected / noAccess / ready), `osmandApp`, `timetables`, `displayOverApps`, `startWithOsmAnd` |
| `Timetables.turnedOn` / `turnedOff` | The *Show timetables in OsmAnd* switch | |
| `Timetables.activeOnMap` | Stops loaded onto OsmAnd's map, once a day | |
| `Timetables.button` | A stop menu button in OsmAnd | `button` (nextDepartures / showInApp, the Full timetable button / tripTo) |
| `Timetables.openBlocked` | Android didn't let a screen open over OsmAnd | `screen`, `notified` |
| `Timetables.stopOpened` | A stop tapped in this app's list | `from` (nearMap / search) |
| `Peatus.failed` | Loading stops failed (once per streak of failures) | `error` |
| `Planner.planned` | A trip planned in the Trips tab (not its refreshes) | `results`, `live` (whether any way is live), `best` (which planner found the first: PEATUS) |
| `Planner.shownInOsmAnd` | A way's *Show in OsmAnd* | |
| `Planner.walkInOsmAnd` | A way's *Walk there with OsmAnd* | |
| `Planner.choseDeparture` | Another departure picked on a way's screen | |
| `Trip.started` | *GO* | `rides`, `live` |
| `Trip.liveOpened` | The live trip screen opened | |
| `Trip.choseDeparture` | Another departure picked on the live screen | |
| `Trip.replanned` | Trip mode planned again | `reason` (connection / gone / asked), `found` |
| `Trip.ended` | The trip ended | `arrived` (false: stopped) |

Add more with `Analytics.signal(name, params)`, or `Analytics.daily(name)` for at most once a day per device.
Never put anything in them that could identify someone.

The DSN goes in `local.properties` (gitignored); CI reads the secret `SENTRY_DSN`:

```properties
sentryDsn=https://…@o….ingest.de.sentry.io/…
```

Debug builds report to Sentry's `debug` environment, so filter by `release` for real users. In the Sentry
project, turn on *Settings → Security & Privacy → Prevent Storing of IP Addresses*.

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
`SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`, and Sentry's DSN from
`SENTRY_DSN` (optional: without it the release has no crash reports or usage stats).

## Code map

```
app/                      shell: home screen with a tab per feature, OsmAnd status, log;
                          update/: Updater, GitHubReleases, UpdateWorker, InstallResultReceiver
core/                     OsmAndConnection, CompanionService (keeps the process alive), BootReceiver, AppLog,
                          OsmAndWatcher + FollowOsmAnd (start and stop with OsmAnd),
                          Analytics (opt-in crash reports and usage stats, in Sentry)
feature/timetable/        TimetableFeature, OsmAndStopUi (everything shown inside OsmAnd), PeatusClient, TallinnLive,
                          RidangoLive, LiveMemory (live times seen at stops the vehicle has left),
                          StopActivity, TripActivity, TimetableFragment, StopIconProvider,
                          OsmAndRoute (a trip's route in OsmAnd, gone with its card), OsmRouteCheck (is OSM's route current?),
                          States (Lottie loading/empty/error)
feature/planner/          TripPlanner, OtpPlanner (peatus.ee's), LiveRetimer + LiveFeeds (live times into planned ways),
                          Ranking, Way (the same way at other times, as one), Geocoder (peatus.ee's search), PlannerFragment (the Trips tab), PlaceSearchActivity,
                          ItineraryActivity, OsmAndTrip (a way on OsmAnd's map, walking navigation, OsmAnd's places);
                          trip mode: TripFeature, TripStore (the trip being taken), TripProgress (where it is, what's
                          missed, which alerts), TripSteps + LiveTripActivity (the live screen), TripNotifications,
                          TripActionReceiver
```

The planner itself only works while its screens are open; trip mode is a `BackgroundFeature` (`TripFeature`), on
while a trip is being taken, and the one feature that runs without OsmAnd (`runsWithoutOsmAnd`). The unit tests
(`./gradlew :feature:planner:testDebugUnitTest`) cover decoding the city's timetables, the search, live times, the
ranking, and trip mode's steps and alerts.

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
`net.osmand:android-aidl-lib:master-snapshot` from OsmAnd's Ivy repo (`builder.osmand.net`), Lottie,
Sentry (opt-in).
Timetable data: peatus.ee (Transpordiamet); live times in Tallinn: the City of Tallinn, via transport.tallinn.ee. Route check: OpenStreetMap contributors, via the Overpass API.
Trip planning: Ridango's and peatus.ee's OpenTripPlanner, the City of Tallinn's timetables (transport.tallinn.ee),
peatus.ee's search (Pelias). RAPTOR: Delling, Pajor and Werneck, "Round-Based Public Transit Routing" (2012).

## Credits

Animations from LottieFiles, free under the [Lottie Simple License](https://lottiefiles.com/page/license),
recolored in the app to fit its theme (`feature/timetable/src/main/res/raw`):

- Loading: [Bus Loader](https://lottiefiles.com/free-animation/bus-loader-LF8V0uZBm4) by Bijay Subba Limbu
- Couldn't load: [No Internet Connection](https://lottiefiles.com/free-animation/no-internet-connection-jWCR3yXdDT)
- Nothing leaves: [Clock Time](https://lottiefiles.com/free-animation/clock-time-YX86xw76OL)
- Live times: [Go Live](https://lottiefiles.com/free-animation/go-live-FkhFJ5HQm5) by Games Hub, with its waves on
  one side only and cropped to them

The update popup's download animation (`app/src/main/res/raw/update_download.json`) is made for this app.
