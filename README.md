<div align="center">

<img src="docs/icon.svg" width="112" alt="GUMMM icon">

# GUMMM

**Godot Universal Mod Manager Mobile**

A mobile port of [GUMM](https://github.com/KoBeWi/Godot-Universal-Mod-Manager). Add mods to Godot games on Android, with no PC.

[![Release](https://img.shields.io/github/v/release/DinoCDX/GUMMM?style=flat-square)](https://github.com/DinoCDX/GUMMM/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/DinoCDX/GUMMM/total?style=flat-square)](https://github.com/DinoCDX/GUMMM/releases)
[![Website](https://img.shields.io/badge/website-dinocdx.github.io%2FGUMMM-5B8DEF?style=flat-square)](https://dinocdx.github.io/GUMMM/)

[Download](https://github.com/DinoCDX/GUMMM/releases/latest) · [Website](https://dinocdx.github.io/GUMMM/) · [Changelog](CHANGELOG.md)

</div>

---

## What it does

GUMMM takes a Godot 3 or 4 game, injects the GUMM mod loader and your mods into its APK, signs it and installs it. The modded game installs next to the original, so your untouched copy stays.

- Add a game from your installed apps, an APK file, or a split bundle (`.apks`, `.xapk`, `.apkm`)
- Import mod zips, `.pck` files or folders, several mods per zip are fine
- Mod types: GUMM mods and plain resource packs, detected automatically with a manual override
- Switch mods on and off, reorder them, see conflicts at a glance
- Optional in-game button to switch mods while playing
- Optional external mod folder, so you can change mods without rebuilding
- Checks GitHub for new releases and updates itself

## Mod status

Every mod has a colored band on its left edge.

| Color | Meaning |
|---|---|
| Green | Active |
| Yellow | Conflicts with another mod (both replace the same file) |
| Red | Error: `mod.gd` is missing or the loader reported a failure |
| Grey | Not active |

Mods load from **bottom to top**, so the mod at the top loads last and wins a conflict. Use the arrows on each mod to reorder.

## Install

1. Download the APK from the [latest release](https://github.com/DinoCDX/GUMMM/releases/latest).
2. Allow installs from your browser or file manager when Android asks.
3. Open GUMMM.

Requires Android 9 or newer. After the first install, GUMMM updates itself from GitHub releases. You can turn that off or check by hand in App settings, About.

## Usage

1. **Add a game.** Tap add and pick an installed Godot game or an APK file.
2. **Import mods.** In the Mods tab, import a zip or folder. Each mod needs a `mod.cfg`.
3. **Arrange.** Toggle mods and set the order.
4. **Build and install.** In the Play tab, build. GUMMM injects the loader and mods, realigns and signs the APK, and installs it.

### Tabs

| Tab | Purpose |
|---|---|
| Play | Build, install, launch, uninstall, update the game APK |
| Mods | Import, switch, reorder and remove mods |
| Settings | Game info, install options, mod loading, in-game button, custom loader files |
| Log | Build log and loader log |

## Making a mod

A mod is a folder or zip with a `mod.cfg` and a `mod.gd`.

`mod.cfg`

```ini
name = "My Mod"
version = "1.0"
author = "You"
description = "What it changes"
```

`mod.gd`

```gdscript
extends Node

func initialize(mod_path, scene_tree):
    print("loaded from ", mod_path)
```

The loader follows the original GUMM contract. It calls `initialize(mod_path, scene_tree)`, or `_initialize(scene_tree)` if that is what the mod has, and stores the loaded mods in `get_tree().get_meta("GUMM_mods")`. An optional `icon.png` is shown next to the mod. Add `set_enabled(bool)` to let the in-game button switch the mod live.

## Files tab

The Files tab is a file browser for the game, for finding what to mod.

- **Three views:** Original (the APK as you added it, with splits), Modded (your last build, to compare what was added), and Game pack (the contents of the game's `.pck`, as `res://` paths).
- **Search:** by file name, or inside text files such as scripts, scenes and configs.
- **View:** text files, images, and a hex dump for anything else.
- **Export:** one file or a whole folder to `Download/GUMMM/<game>/` (needs All files access).
- **Edit:** change a text file and save it, or replace any game file with a file from your phone. GUMMM puts it in a resource pack called *My edits*, which loads last and wins over other mods. Rebuild to apply.

Encrypted packs and compiled scripts can be listed but not read.

## Mod types

GUMMM detects the type of every mod when you import it. Tap the type under a mod to change it.

| Type | What it is | How it loads |
|---|---|---|
| GUMM | A folder with `mod.cfg` and `mod.gd` | The loader runs `mod.gd` |
| Resource pack | A `.pck` or `.zip` with no scripts, files laid out like the game's `res://` | The loader mounts it with `load_resource_pack`, later packs replace earlier files |
| Godot Mod Loader | A zip with `mods-unpacked/Author-Name/manifest.json` | Packed into the APK. Godot 4 games get the bundled Mod Loader, Godot 3 games must already include it (like Brotato). Experimental |

Resource packs cannot be unloaded while the game runs, so switching one off in the in-game list says *restart to apply*. Two zip packs that contain the same file show a yellow band.

### Godot Mod Loader mods (experimental)

GUMMM bundles the [Godot Mod Loader](https://github.com/GodotModding/godot-mod-loader) for Godot 4. When you build a game with one of these mods switched on, GUMMM adds the framework, registers it as an autoload, and packs the mods into the APK as unpacked mods. If the game already ships the Mod Loader, which is the case for Brotato, only the mods are added.

- Godot 4 games get the bundled Mod Loader. Godot 3 games (like Brotato) must already include the Mod Loader, GUMMM only adds the mods. Godot 3 games without it are skipped.
- These mods are always packed into the APK, so any change needs a rebuild.
- The game's own autoloads start before the Mod Loader, so mods that extend scripts those autoloads already loaded may not take effect.
- Mods that use script hooks need a game exported with hooks, which most games are not.

## Mod loading modes

**External (default on Android 11 or newer).** Mods are copied to `/storage/emulated/0/Android/media/<modded package>/GUMM/mods`. This needs All files access for both GUMMM and the game. After one rebuild, importing, removing or switching mods only syncs that folder. The loader log is written to `GUMM/loader.log` and shown in the Log tab.

**Packed.** Mods are packed into the APK. Changing which mods are on needs a rebuild, and the app shows *Rebuild needed*. Turn off external loading in App settings, or per game in Settings. Android 9 and 10 always use this mode.

## In-game button

Off by default. A small draggable **G** button opens a list of mods and a log of load errors. It fades after 2 seconds and snaps to the nearest screen edge. A switch applies live when the mod defines `set_enabled(bool)` or has not loaded yet. Otherwise the row says *restart to apply*. Choices are stored in `user://gumm_state.txt` and reset whenever the defaults change in GUMMM.

## First build with external mods

Android asks for permission twice, once per app. This is normal and only happens once.

1. On your first build, GUMMM explains and opens a settings page. Switch on **All files access** for GUMMM and go back.
2. Build and install the game.
3. In the Mods tab, tap **Grant** under Game file access and switch on **All files access** for the modded game.

If the loader log shows error 7, the game does not have access yet.

## Notes

- One signing key lives in the app, so later builds update in place. Export it from Signing key to survive a reinstall.
- Separate app mode renames the package, authorities and custom permissions in the manifest, so the original stays installed.
- Copying save data needs root. Without root Android blocks it.
- Update game: pick the new APK of the same package. It replaces the stored APK, re-detects version and main scene, then rebuilds and installs over the modded app.
- Using your own loader files turns the in-game button off.
- Split APK games work: installed apps are copied with all their splits, and `.apks`, `.xapk` and `.apkm` bundles can be added as files. Every split is patched, signed with your key and installed together.

## Build from source

Open the project in Code on the Go (Gradle 8.14.3, AGP 8.11.0, Kotlin 2.3.0) or Android Studio and build the `app` module.

```
gradle :app:assembleDebug
```

- Dependency: `com.android.tools.build:apksig`, same version as AGP.
- `compileSdk` is 35. Lower it in `app/build.gradle.kts` if your SDK is older.
- If Gradle cannot find a plugin version, copy the AGP and Kotlin versions from a fresh project's root `build.gradle.kts`.

## Credits

Made by [DinoCDX](https://github.com/DinoCDX). Based on [GUMM](https://github.com/KoBeWi/Godot-Universal-Mod-Manager) by KoBeWi. Includes the [Godot Mod Loader](https://github.com/GodotModding/godot-mod-loader) (CC0) and its JSON Schema Validator.
