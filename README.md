# OsmAnd Sample Plugin

A minimal Android app that extends [OsmAnd](https://osmand.net) through its official **AIDL API**,
the same mechanism used by OsmAnd's own [osmand-api-demo](https://github.com/osmandapp/osmand-api-demo).
It's the starting point for the real project.

## How OsmAnd "plugins" work

OsmAnd doesn't load third-party code. A plugin is a separate app that **binds to a service inside OsmAnd**
(`net.osmand.aidl.OsmandAidlServiceV2`) and asks it to draw layers, add buttons, widgets and menu items.
OsmAnd calls back into the app when the user taps them.

```
 this app ──bindService──▶ OsmAnd (net.osmand / net.osmand.plus)
          ◀──callbacks──── context-menu clicks, navigation updates, …
```

Two things to know:

1. **OsmAnd blocks every new app by default.** After the first connection the app shows up in
   OsmAnd → *Menu → Plugins* as "OsmAnd Sample Plugin — Third-party app". Tap it to turn it on (it goes orange).
   Until then every call returns `false`, and the sample's status line says so.
2. **Nothing you add persists.** When OsmAnd restarts, it forgets the layers and buttons. The sample stores an
   "enabled" flag and re-applies everything on every (re)connect (`SampleApp.onCreate`).

## What the sample does

| Feature | API call | Where to look in OsmAnd |
|---|---|---|
| Layer with 3 Tallinn points | `addMapLayer` | blue circles with a letter on the map |
| Context-menu buttons | `addContextMenuButtons` | tap a point → *Add marker here* / *Route here* |
| Map widget with a live value | `addMapWidget` / `updateMapWidget` | *Menu → Configure screen → widgets*, then enable "Sample plugin" |
| Main menu entry that opens this app | `setNavDrawerItems` | bottom of the main menu |
| Open a point's context menu | `showMapPoint` | |
| Move map | `setMapLocation` | |
| Map marker | `addMapMarker` | *Menu → Map markers* |
| Start / stop navigation | `navigate` / `stopNavigation` | |
| Turn-by-turn events | `registerForNavigationUpdates` | streamed into the app's log |

Code map:

- `OsmAndConnection.kt`: binding, access check, and the single `IOsmAndAidlCallback` OsmAnd calls back through.
- `SamplePlugin.kt`: one function per feature. Copy from here.
- `SampleApp.kt`: holds the connection for the whole process and reacts to context-menu clicks.
- `MainActivity.kt`: the test screen with buttons and a log.

### OsmAnd quirks found while building this (OsmAnd 5.4)

- `addContextMenuButtons` takes a left/right pair but **only draws the right one**, so register one button per call.
- OsmAnd ignores your `callbackId` unless it already knows it, and registers a new callback per call. Reuse the
  id it returns, or every click is delivered once per registration.
- Background `Toast`s are suppressed unless the app may post notifications. Respond through the API instead
  (e.g. add a marker), or post a notification.
- Icon names (`ic_action_…`, `widget_…`) refer to drawables **inside OsmAnd**. Browse
  [OsmAnd/res/drawable](https://github.com/osmandapp/OsmAnd/tree/master/OsmAnd/res/drawable) for valid names.

## Build & run

Requirements (already installed on this machine): JDK 25, Android SDK in `~/Android/Sdk`.

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

OsmAnd must be on the device. The x86_64 build from F-Droid is at
`~/Downloads/OsmAnd-5.4.4-fdroid-x86_64.apk`:

```bash
adb install ~/Downloads/OsmAnd-5.4.4-fdroid-x86_64.apk
```

Useful while testing on an emulator:

```bash
adb emu geo fix 24.7453 59.4372          # fake GPS at Tallinn Old Town (lon lat)
adb logcat -s OsmAndSamplePlugin          # this app's log
adb logcat | grep OsmandAidlService       # OsmAnd's side: shows "enabled: true/false" per call
```

## Stack

AGP 9.4 (built-in Kotlin), Gradle 9.8, compileSdk/targetSdk 36, minSdk 24,
`net.osmand:android-aidl-lib:master-snapshot` from OsmAnd's Ivy repo (`builder.osmand.net`).
