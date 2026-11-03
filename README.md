# TV Home

A fast, minimal Android TV launcher: Apple-TV-style floating glass dock, rotating ambient wallpapers,
screensaver. No third-party dependencies.

## What's inside
- **Dynamic Glass Dock** - real backdrop blur (pre-blurred copy of the wallpaper, drawn through a shader),
  tint, sheen, hairline borders, layered shadow, spring magnification, floating label, per-app glow,
  reflections, pin / unpin / reorder edit mode, persistent settings, reduced-effects mode.
- **Content row** above the dock: Recent apps, Suggested apps, or off.
- **All Apps** overlay: hold OK on an app to pin, unpin or hide it.
- **Ambient Gallery**: 10 categories (9 photo/abstract + Favorites), auto-rotation, favorites, disk cache,
  credits on screen. Photos are discovered at runtime from Wikimedia Commons featured pictures through the
  public MediaWiki API (nothing hard-coded). Abstract wallpapers are generated on-device (work offline).
- **Screensaver**: built into the launcher (idle timer) and also a system Dream ("TV Home Ambient").

## Remote controls
| Key | Dock | Overlays |
|---|---|---|
| Left / Right | move (magnifies) | change value / category |
| OK | launch | select |
| Hold OK (or Menu) | enter edit mode | favorite / app options |
| Up | content row, then top bar | - |
| Down | back to dock | - |
| Back | exit edit mode / return to dock | close |

Dock edit mode: OK grab / drop, Left/Right move, Up remove from dock, Back finish.

## Build
GitHub: push and run the *Build APK* workflow, or open in Android Studio. minSdk 26.

## Configure
`app/src/main/res/values/config.xml`: default dock apps (first run only) and All Apps columns.
Everything else is changed on the TV: pill **Customize** (dock + wallpapers + screensaver) and **Wallpapers**.
