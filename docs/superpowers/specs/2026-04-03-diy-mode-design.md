# DIY Mode

## Summary

A global toggle in Advanced settings that disables all config injection for external RetroArch launches. When enabled, the launcher passes only the ROM path and core library path to RetroArch via intent extras, letting RetroArch use its own configuration entirely.

Default: **on** (temporary — will flip to off once RA config injection is rebuilt).

## Scope

- Applies only to external RetroArch launches (games/platforms configured to use the RA package).
- Does not affect embedded libretro launches, standalone app launches, or the OverrideManager/INI override system.

## Changes

### SettingsRepository

- New field: `diyMode: Boolean`, default `true`
- Persisted to `Config/settings.json`

### AppSettings / SettingsViewModel

- Expose `diyMode` in `AppSettings` data class
- Add toggle action in `SettingsViewModel`

### Settings UI (Advanced section)

- New toggle row: "DIY Mode"
- No additional UI indicators elsewhere

### LaunchManager

When `diyMode` is true and the launch path is external RetroArch:

- Skip `syncRetroArchConfig()`
- Skip `buildGameConfig()`
- Call `RetroArchLauncher.launch(romFile, coreName, configPath = null)`

When `diyMode` is false, current behavior is preserved.

## Behavior Matrix

| DIY Mode | External RA Intent Extras |
|----------|--------------------------|
| On       | `LIBRETRO` + `ROM` only  |
| Off      | `LIBRETRO` + `ROM` + `CONFIGFILE` (generated `retroarch_launch.cfg`) |
