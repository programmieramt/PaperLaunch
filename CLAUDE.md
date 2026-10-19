# Paper Launch

Pen-first home screen for the **Boox Palma 2 Pro**: today's Overwatcher day page *is* the home screen.
Black on white, no animations (e-ink). Successor of `../Launcher` (app list / favorites ported from there).

- **Package**: `com.programmieramt.inklauncher`
- **Stack**: Kotlin, Jetpack Compose (Material3), DataStore. No DI.
- **Toolchain**: same as `../Launcher` (JDK 17 Temurin, Gradle 9, AGP 8.11.1, Kotlin 2.2.20)

## Build & install

Always test the **release** build (Compose debug builds are slow).

```powershell
./gradlew assembleRelease
$adb = "adb"
& $adb install -r app/build/outputs/apk/release/app-release.apk
& $adb shell cmd package compile -f -m speed-profile com.programmieramt.inklauncher
```

## Device: Boox Palma 2 Pro

- 824×1648 px, 300 dpi, Android 15, Kaleido color e-ink, stylus

## Overwatcher data access

Day pages come from Overwatcher (`../overwatcher-android`, package `com.overwatcher.programmieramt`) via its
`CalendarDataProvider` (authority `com.overwatcher.programmieramt.data`):

- `openInputStream(content://…data/day/YYYY-MM-DD)` → `CalendarDay` v2 JSON (carries over open tasks if today)
- `call("putStrokes", "YYYY-MM-DD", {style, strokes})` → replaces the strokes of a page style (JSON array)
- `call("sync")` → WebDAV sync now
- `call("syncStatus")` → `{configured, started, finished, ok, error}` of the last sync (header indicator; running
  while started > finished; network errors such as an unreachable Tailscale server count as failure)
- `notifyChange` on `…/day/YYYY-MM-DD` after every save, on `…/sync` when a sync starts or ends

Protected by the signature permission `com.overwatcher.programmieramt.permission.CALENDAR_DATA`:
**both apps must be signed with the same key** (the local debug keystore; Overwatcher's CI keystore is the same key),
and Overwatcher must be installed before this app, otherwise the permission is not granted (reinstall the launcher).

## Structure

- `day/OverwatcherClient` – provider access, change observer, open day in Overwatcher
- `day/DayPage` – parsed day (org.json): start hour, lanes flag, `Default` style strokes, reading progress
- `day/DayPageRenderer` – Overwatcher's day template, ported from its `WidgetRenderer` (GPLv3). Page is 1404×1872
  (3:4); the Palma is 1:2, so `Panel` cuts it into four full-width parts (Früh, Spät, Aufgaben, Notizen), all at the
  same scale. Geometry must stay identical to Overwatcher, strokes are stored in page coordinates.
- `day/CalendarEvents` – appointments from `CalendarContract` (READ_CALENDAR)
- `day/OpenTasks` – open task rows of a day (handwriting, no checkbox mark; same rules as Overwatcher's carry-over)
- `SystemStatus` – battery (sticky broadcast) and `NotificationCounter` (needs notification access)
- `pen/PenSurfaceView` – transparent SurfaceView (z-order on top) exactly over the panel's template image: draws the
  day's strokes and records new ones. Same approach as Overwatcher's `SurfaceFragment`: the Onyx `TouchHelper` only
  renders the pen live; points come from stylus MotionEvents (fingers are ignored → home gestures). Strokes are
  committed on hover exit or 800 ms after pen up, not between the strokes of a word. Raw drawing must be disabled
  while posting the surface (`redraw`). Erasing: eraser tool, eraser tip or side button (radius 25 page units).
- `LauncherViewModel` – undo stack per day, ordered save queue (`putStrokes`); while a change is unsaved, reloads keep
  the local strokes
- `ui/HomeScreen` – date header (‹ › day navigation, tap = today), panel tabs, day panel + pen surface, pen toolbar
  (Stift, Radierer, Zurück, Overwatcher), status line, favorites dock
- `ui/DrawerScreen` – search + app list; long-press menu toggles favorite
- `apps/BooxEntries` – "Bibliothek" and "Notizen" of the Boox system launcher (`com.onyx`) as app list entries
  (they have no launcher icon; the notes overview is not exported). Library: action `com.onyx.action.LIBRARY`.
  Notes: action `com.onyx.intent.action.MAIN_ACTIVITY` + extra `json={"action":"OPEN_NOTE"}` (`TabIntentHelper`,
  other `TabAction`s: OPEN_LIBRARY, OPEN_STORAGE, OPEN_NOTE_SEARCH, ...)
- Finger gestures: swipe left/right = panel, up = app list, down = notification shade. The swipe detector must never
  consume pen pointers: Compose then sends ACTION_CANCEL to the pen surface and the stroke is lost.

## Onyx SDK build notes

- Maven repo `http://repo.boox.com/repository/maven-public/` (http only, `isAllowInsecureProtocol`)
- Pulls the old support library → `android.enableJetifier=true`; old fragment → explicit `fragment-ktx`
- R8: keep `com.onyx.**`, dontwarn optional transitive deps (see `proguard-rules.pro`); hidden API bypass in
  `MainActivity`
- Pen strokes can be simulated with `adb shell input stylus swipe x1 y1 x2 y2 ms` (no live rendering then)
