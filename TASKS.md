# Easy tasks (good for a Sonnet session)

Read CLAUDE.md first. Do **one task at a time**: implement it, run `./gradlew build` and
`./gradlew runClientGameTest`, then commit. Each task lists which files to touch. If a task turns out to need a
new mixin or rendering changes, stop and leave it for a bigger session.

## 1. CHANGELOG.md (done in 1.4.0; keep it updated with every release)
Create `CHANGELOG.md` from the "Version history" section of CLAUDE.md (1.0.0, 1.1.0, 1.2.0), newest first,
using Keep a Changelog style headings (Added/Changed).
Files: new `CHANGELOG.md`.

## 2. "Reset to Defaults" button in the settings screen
Add a button next to "Done" in `GoreSettingsScreen` that replaces the config with `new GoreConfig()` (add a
static `GoreConfig.resetToDefaults()` that sets `instance = new GoreConfig()` and saves), then rebuilds the
screen with `this.rebuildWidgets()`. Look at `OptionsSubScreen.addFooter()` (layout footer) for how the Done
button is added. Override `addFooter()` and use a horizontal `LinearLayout` with two 150px buttons.
Files: `ui/GoreSettingsScreen.java`, `config/GoreConfig.java`, `en_us.json` (`fluidgore.settings.reset`).

## 3. Per-mob blood colours in the config
Add `Map<String, BloodColor> entityBloodColors` to `GoreConfig`, defaulting to e.g. `minecraft:spider` and
`minecraft:cave_spider` = GREEN, `minecraft:slime` = GREEN, `minecraft:enderman` = PURPLE,
`minecraft:magma_cube` = YELLOW. In `EntityFilter.bloodType()`, check this map first (by entity type id), then
fall back to the current Enemy/other rule. Null-check it in `sanitize()`. Config file only; no GUI needed.
Files: `config/GoreConfig.java`, `trigger/EntityFilter.java`, README settings note.

## 4. More suffering animals
Add these ids to the default `sufferingAnimals` list in `GoreConfig`: `minecraft:wolf`, `minecraft:cat`,
`minecraft:fox`, `minecraft:turtle`, `minecraft:frog`, `minecraft:panda`, `minecraft:polar_bear`,
`minecraft:villager`, `minecraft:wandering_trader`. Existing users keep their saved list (Gson keeps file
values), so mention in README that deleting the list entry from `fluidgore.json` restores the defaults.
Files: `config/GoreConfig.java`, `README.md`.

## 5. Option to hide the Options-screen button
Add `boolean optionsButton = true` to `GoreConfig`, a toggle in the settings screen, and an early return in
`OptionsScreenButton` when it is false. (Mod Menu remains a way in; without Mod Menu, say in the tooltip that
the config file is the only way back.)
Files: `config/GoreConfig.java`, `ui/OptionsScreenButton.java`, `ui/GoreSettingsScreen.java`, `en_us.json`.

## 6. Quick toggle key
Register a key mapping with Fabric's `KeyMappingHelper` (module `fabric-key-mapping-api-v1`; check its sources
for the 26.3 class and method names) with no default key, in a "Fluid Gore" category. When pressed, cycle
`goreLevel` Off -> Low -> Medium -> High -> Extreme -> Off, save the config, and show the new level with
`player.displayClientMessage(Component, true)` (action bar). Poll it in `ClientTickEvents.END_CLIENT_TICK` with
`consumeClick()`.
Files: new `ui/GoreKeys.java`, `FluidGoreClient.java`, `en_us.json`.

## 7. Game test coverage for settings
Extend `FluidGoreClientTest`:
- Set `sufferingLevel = OFF` and assert `GoreSystems.suffering().corpses()` is empty after 2 ticks.
- Set `bloodStyle = REALISTIC`, kill a husk, and take a screenshot named `style_realistic`.
Files: `src/gametest/java/dev/fluidgore/test/FluidGoreClientTest.java`.

## 8. Other languages
Copy `en_us.json` to `de_de.json`, `es_es.json` and `fr_fr.json` with translated values (keep every key and the
`%s` / `%%` placeholders exactly).
Files: `src/main/resources/assets/fluidgore/lang/`.

## Release checklist (after any task)
1. Bump `version` in `gradle.properties` (patch for fixes, minor for features).
2. `./gradlew build runClientGameTest`; check the log and screenshots.
3. Copy the jar into `releases/`, commit, `git tag vX.Y.Z`.
4. To install for the user: delete the old `fluidgore-*.jar` in `%APPDATA%\.minecraft\mods`, then copy the new one.

## Not for an easy session
Anything touching `mixin/`, `render/GoreRenderer`, `ModelFeatureRendererMixin` animation, the fluid sim maths,
corpse/throes timing, or growing the texture atlas.
