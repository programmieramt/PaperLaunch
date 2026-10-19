# Paper Launch

A pen-first home screen for the **Boox Palma 2 Pro**: the home screen *is* today's planner page, and you write on it
directly with the stylus. No app to open, no page to find: pick the device up, see today, write.

The planner pages come from [Overwatcher](https://github.com/programmieramt/toolsboox-android), a fork of
Tools for Boox. Paper Launch reads and writes them through Overwatcher's data provider, so everything you write shows
up in Overwatcher and syncs to other devices via its WebDAV sync.

## Features

- **Today's page as home screen**, split into four full-width parts for the tall 1:2 screen: morning, afternoon,
  tasks, notes. The schedule opens at the half with the current time.
- **Pen writes, fingers navigate**: swipe sideways between the parts, up for the app list, down for notifications.
- **Live ink** with the Onyx pen SDK; eraser (tool, eraser tip or side button) and undo.
- **Header** with day navigation and an indicator for Overwatcher's last WebDAV sync (tap to sync now).
- **Status line**: battery, open tasks of the day, notifications. Tapping the open tasks jumps to the oldest day of
  the last 30 with an unfinished task.
- **App list** with search and favorites. It also opens the Boox Library and Notes, which otherwise only open from
  the Boox launcher's widgets.

## Requirements

- A Boox device with stylus (built and tested on the Palma 2 Pro, Android 15)
- [Overwatcher](https://github.com/programmieramt/toolsboox-android) installed **first**, signed with the **same key**
  as Paper Launch: its data provider is protected by a signature permission

## Build

```sh
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

The release build is signed with your local debug keystore; build Overwatcher with the same key. Then choose
Paper Launch as the default home app in the Android settings.

## How it was made

Paper Launch is vibe-coded: it was written with Claude (Anthropic's AI) from my descriptions, as was much of this
README. Expect rough edges. It's a personal project, shared for the idea.

## Credits and license

The day page template is ported from Overwatcher's widget renderer, which builds on
[Tools for Boox](https://github.com/gaborauth/toolsboox-android) by Gábor Auth and the Ledger fork by Michael Joel Hall.
Like them, Paper Launch is licensed under the [GNU GPL v3](LICENSE).
