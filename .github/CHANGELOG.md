# v1.0.5

- Can multitask away from Cannoli in-game and return to gameplay. Resolves #26
- Quitting Cannoli while in game saves to auto on exit. Game will auto resume on next Cannoli launch. Resolves #26
- Attempt to fix framerate on 120 Hz devices (don't own such a nice device) #17
- Fix caching regression in launcher that impacted art display and resume game legend. Closes #22. Resolves #24
- Files / folders prefixed with a dot are now hidden. Resolves #23
- Resume appears / works with any save slot, not just auto. Resolves #28
- By popular request, the OG font from MinUI was bundled. It is not the default. Resolves #15
- You can load additional custom fonts into `Cannoli Root/Config/Fonts`. Go nuts. Resolves #15