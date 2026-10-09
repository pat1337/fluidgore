# Fluid Gore

Client-side-only Fabric mod for **Minecraft Java 26.3**: blood, a blood fluid simulation, dismemberment, and
"Animal Suffering" (screams, death throes, herd panic). Mod id `fluidgore`, MIT. Current version: see
`gradle.properties` (`version=`). Released versions are git tags (`v1.1.0`, `v1.2.0`, ...) with jars in
`releases/` (git-ignored).

Easy follow-up work is listed in **TASKS.md**. Do those one at a time.

## Hard rules
1. **Client-side only.** No registries (blocks, items, entities, particle types, sounds), no packets, no
   server-side code. It must keep working on a vanilla server. Use vanilla sound events and particle types only.
2. **Cosmetic only.** Never change health, damage, drops, loot, AI or game rules.
3. **26.3 is not 1.21.** Names are Mojang's unobfuscated names and many APIs changed (render states,
   `SubmitNodeCollector`, `LevelRenderEvents`, `GuiGraphicsExtractor`, `Identifier` instead of `ResourceLocation`,
   `client.gui.setScreen`). Check the real source before guessing. See "Looking up APIs".
4. Every tunable goes in `config/GoreConfig.java`, sanitized in `sanitize()`, and exposed in the settings screen
   if it is a user setting. Add `en_us.json` keys for every new option, including a `.tooltip` key.
5. No TODOs or stubs in delivered code. Build and run the game test before calling something done.

## Environment (this PC)
- JDK 25: `C:\Users\Thele\.jdks\jdk-25.0.4.1+1`. The system `java` is 21 and will not build this.
  Bash: `export JAVA_HOME="/c/Users/Thele/.jdks/jdk-25.0.4.1+1"`.
- No Python on this machine. Use the JDK for scripts (`java SomeFile.java`).
- Port 25565 is taken by something else on this PC, so the game test's dedicated server uses 25599.
- The user's game: `%APPDATA%\.minecraft\mods` holds the installed jar plus Fabric API 0.162.0, Mod Menu 21.0.0,
  Sodium 0.9.3-alpha.1, Iris 1.11.7, Lithium, FerriteCore, Entity Culling and ImmediatelyFast. When installing a
  new version, delete the old `fluidgore-*.jar` there first (two copies of the same mod id crash the game).

## Commands
```
./gradlew build                 # jar -> build/libs/fluidgore-<version>.jar
./gradlew runClient             # dev client
./gradlew runClientGameTest     # automated in-game test; screenshots in build/run/clientGameTest/screenshots/
./gradlew runClientGameTest --no-configuration-cache -PextraMods=<dir of jars>   # same, with extra mods loaded (compat check)
java tools/GenerateTextures.java src/main/resources/assets/fluidgore             # regenerate blood.png + icon.png
```
The game test (`src/gametest/java/dev/fluidgore/test/FluidGoreClientTest.java`) must pass (exit 0, `[FluidGoreTest]`
lines in the log, no `AssertionError`). Look at the screenshots for visual changes.

## Versions
Fabric Loader 0.19.5, Fabric API 0.162.0+26.3, Loom 1.18-SNAPSHOT (resolves 1.18.3), Gradle 9.7.1, Java 25,
Mod Menu 21.0.0 (compile-only, soft dependency).

## Architecture (`src/main/java/dev/fluidgore/`)
- `FluidGoreClient`: entrypoint; registers events. `GoreSystems`: owns all state, runs it from the client tick,
  and clears it on level change, disconnect, or when a feature is set to Off.
- `config/`: `GoreConfig` (Gson JSON at `config/fluidgore.json`, read live every tick), `GoreLevel` (Off..Extreme
  multipliers), `BloodColor` (rgb + chunk block), `BloodStyle` (Redstone/Realistic/Mixed).
- `ui/`: `GoreSettingsScreen` (vanilla `OptionsSubScreen` + `OptionInstance`), `ModMenuIntegration`,
  `OptionsScreenButton` (the "Fluid Gore..." button on the Options screen).
- `trigger/`: `DamageTracker` finds damage from synced health drops plus the damage-event packet (attacker), with
  no server mod needed. `EntityFilter` decides who bleeds, the blood colour, and which items count as bladed.
- `blood/`:
  - `BloodWorld` is the container.
  - `BloodEmitter` turns a hit into random wounds, droplets, redstone dust, chunks, spurts and crunch sounds.
  - `DropletSystem` handles ballistic droplets.
  - `BloodFluidSim` + `BloodCell` are the cellular puddle flow.
  - `DecalManager` + `Decal` are splats split into pieces that follow stairs and slabs.
  - `Spurts` are arterial jets. `Footprints`.
  - `SurfaceProbe` gives exact surface heights. `BloodTextures` holds the atlas tiles.
- `dismember/`:
  - `Dismemberment` decides when to cut, spawns parts, and tracks missing-part masks.
  - `GorePart` + `GorePartManager` are severed-part rigid bodies.
  - `ModelParts` clones and measures model parts.
  - `PartInteraction` lets you punch, grab, throw, kick and stomp parts.
  - `BodyPart` maps parts to model-part names.
- `suffer/`:
  - `Suffering` handles per-animal fear and pain, herd panic, corpses and severed-head moans.
  - `Corpse` keeps a body on screen after server removal for its death throes (QUICK/NORMAL/BRUTAL/HEADLESS_RUN).
  - `ScreamManager` + `ScreamSound` are pitch-bent, layered vanilla sounds. `Voice` is the entity's own sounds.
  - `SufferPose` holds animation amplitudes.
- `render/GoreRenderer`: draws decals, puddles, droplets, parts and removed corpses via
  `LevelRenderEvents.COLLECT_SUBMITS` and `submitCustomGeometry` / `submitModelPart` / the entity dispatcher.
- `mixin/` (`fluidgore.mixins.json`, all client):
  - `LivingEntityMixin`: damage-event hook and the held-back death "poof".
  - `LivingEntityRendererMixin`: copies gore state into render states, corpse overrides, whole-body motion.
  - `ModelFeatureRendererMixin`: hides severed parts and animates limbs right around `renderToBuffer`, then restores.
  - `MinecraftMixin`: routes attack/use clicks to severed parts.
  - Accessors and invokers for `ModelPart` and entity sounds.

## Textures
`assets/fluidgore/textures/misc/blood.png` is a 4x4 atlas of 64px tiles, white/grey with alpha, tinted by vertex
colour. Tiles 0-9 are smooth (splats 0-3, puddle 4, pool 5, droplet 6, footprint 7, streak 8, small splat 9) and
10-15 are pixel-art "redstone" (splats 10-13, puddle 14, droplet 15). **The atlas is full.** Adding a tile means
growing the grid (e.g. 8x4) and updating `BloodTextures.GRID` math and the generator. All PNGs come from
`tools/GenerateTextures.java` (deterministic seeds). Never hand-edit the PNG.

## Looking up APIs
The decompiled 26.3 Minecraft source is produced by `./gradlew genSources` and lives in
`.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-*/26.3/*-sources.jar`. Extract it to a scratch
folder and grep it. For Fabric API sources, download `<module>-<version>-sources.jar` from maven.fabricmc.net
(module versions are listed in the fabric-api 0.162.0+26.3 POM).

## Not verified yet
Iris with a shader pack enabled; a real multiplayer server with other players (skins, anti-cheat plugins hiding
health). Known limits: a held item still renders where a severed hand was; slimes, fish and ghasts have no
severable parts; severed heads can't blink.

## Version history
- **1.0.0**: blood spray, decals on floors and walls, fluid sim, footprints, rain and water washing, dismemberment
  with physical parts, settings screen + Mod Menu, client game test.
- **1.1.0**: Animal Suffering (screams from distorted vanilla sounds, flinch, tremble, limp, herd panic), death
  throes based on kill method, bodies outliving server removal, headless run, severed heads twitch and moan, and
  punching, grabbing, throwing, kicking and stomping parts.
- **1.2.0**: Redstone pixel-art blood style (default), redstone-dust particles, gore chunks (redstone-block crumbs
  + meat items), arterial spurts, log-normal random wound severity, satellite splats, colour variation, crunch
  sounds. Inspired by the block-crumble particles and break sounds in the user's Clay mod
  (`C:\Users\Thele\ClaySoldiersMod`).
