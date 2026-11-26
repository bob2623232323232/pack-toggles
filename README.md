# Pack Toggles (Fabric, MC 1.21.11, client-side)

Press **J** in game: turn parts of your big resource pack on/off, then **Apply & reload**.

## How it works
The mod never edits your original zip. It copies it to `resourcepacks/<name>-toggled.zip`, leaving out
every file that belongs to a switched-off group. Select the `-toggled` pack (not the original) in
Options > Resource Packs once; after that each Apply rebuilds it and reloads. If Windows has the pack
locked, the new copy is swapped in at next launch.

Groups live in `Group.java` (path prefixes) - edit that list to add/split switches.
Turning off *textures* while keeping *models* (or the reverse) can show missing-texture squares; the
groups are paired to avoid that (blocks and items bundle states + models + textures).

## Build
Needs JDK 21 and internet:   `gradle build`   -> `build/libs/pack-toggles-1.0.0.jar`
Put the jar in `mods/` with Fabric API.

Uses plugin `net.fabricmc.fabric-loom-remap` (1.21.11 is the last obfuscated version) with Mojang mappings.

Unverified against 1.21.11: written without being able to compile. If Gradle rejects a version, update
`gradle.properties` from https://fabricmc.net/develop. If a Minecraft class name differs, the fix is
a one-line rename (Screen/Button/GuiGraphics/KeyMapping.Category are the likely spots).

## Build without installing anything
Upload this folder to a new GitHub repo (keep `.github/`). Actions > "build" > Run workflow, then download
the `pack-toggles-jar` artifact (use the jar that does not end in `-sources`).
