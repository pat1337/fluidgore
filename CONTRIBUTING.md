# Contributing to Fluid Gore

This file covers how to build and test the mod and the rules the code follows. For a map of the code, see
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Setup
- **JDK 25.** Older JDKs will not build it. Gradle 9.7 (wrapper included), Fabric Loom 1.18, Fabric Loader 0.19.5,
  Fabric API 0.162.0+26.3. Mod Menu 21.0.0 is a compile-only, optional dependency.
- Any IDE that imports Gradle works. In IntelliJ, set both the project SDK and the Gradle JVM to JDK 25.

## Commands
```
./gradlew build                 # build/libs/fluidgore-<version>.jar
./gradlew runClient             # development client, with the Cobble Tank (compiled from ../CobbleTank)
./gradlew installMod            # build, then copy the jar into %APPDATA%\.minecraft\mods (or -PmodsDir=<dir>),
                                # deleting older fluidgore-*.jar there (two copies crash the game)
./gradlew runClientGameTest     # the automated in-game test (singleplayer, then a dedicated server)
```

The Cobble Tank is its own project, `../CobbleTank` (build and install it there). Fluid Gore's dev client, showcase
runs and game test compile its sources from that folder (`-PcobbleTankDir=<dir>` to point elsewhere), so it must be
checked out beside this one.

Game test options:

| Option | Effect |
|---|---|
| `-PonlyTests=a,b` | Run only the named tests, e.g. `-PonlyTests=keptBodies,stairs`. |
| `-PonlyDemos=a,b` | With `-PonlyTests=demos`: play only these demos. |
| `-Pdedicated` | Run the picked tests against a dedicated server instead of singleplayer. |
| `-PragdollOn` | Run the picked tests with Ragdoll Physics on. |
| `-PextraMods=<dir>` | Load the jars in `<dir>` too, for compatibility checks (add `--no-configuration-cache`). |
| `-Pshaders=<zip>` | With Iris in `-PextraMods`: switch that shader pack on in `bugLook` (close-ups of reported glitches). |

A run passes when Gradle exits with 0, the log shows `[FluidGoreTest]` lines and there is no `AssertionError`.
Screenshots go to `build/run/clientGameTest/screenshots/`. Look at them after any visual change. The full suite is
long, so while you work on one feature, run just that feature's tests. Run the full suite before a release.
The test's dedicated server uses port 25599.

Generators (plain single-file Java programs, run with the JDK):
```
java tools/GenerateTextures.java src/main/resources/assets/fluidgore              # every PNG
java tools/VerifyTextures.java check src/main/resources/assets/fluidgore tools/texture-manifest.txt
java tools/SheepAnatomyModels.java art/sheep_anatomy                               # sheep art + anatomy/SheepTrunk, SheepHead, SheepLegs
java tools/CowAnatomyModels.java art/cow_anatomy                                   # cow art and piece tables, then:
powershell -File tools/ExportCowAnatomy.ps1                                        # anatomy/CowTrunk.java from them
```
The anatomy tools write Blockbench models to `art/` (open them in Blockbench to review the art) and the generated
Java tables the mod uses. Both anatomy tools pull in `tools/AnatomyKit.java` and `tools/Quadruped.java` (the
shared modelling kit and four-legged body plan); a new species extends `Quadruped` and gives its own `Body`. After a
change to the shared files, re-run both and check that `git status` shows only the changes you meant.

## What the mod is for
Fluid Gore shows farm animals the way they are: the work of a farm and a butcher's yard (slaughter, jointing, the
organs of a ewe, a ram and a lamb, blood and how it pools) inside the vanilla world. Its subjects are agriculture,
husbandry and livestock biology. The rules below follow from that.
- **True to the animal.** Anatomy, wounds and deaths follow how the real animal is built and how it really dies,
  from veterinary and meat-science references, as far as a block model allows. When the model forces a compromise
  (a lamb's skull drawn with the adult shape, say), it goes in the README's known limitations. It is not hidden.
- **Unlabelled.** Nothing in the world is captioned. A body is read by looking at it; Identify Remains is the
  opt-in way to name a piece.
- **Shown, not scored.** The core changes how things look and sound, never how the game plays (rule 2 below).
  Gameplay realism (injuries that slow an animal, bleeding that drains health, husbandry effects) belongs in a separate
  opt-in add-on with its own mod id, the way the Cobble Tank is.
- **Say only what is drawn.** Docs, changelog entries and demo text describe what a player can see in the game today,
  checked against a screenshot. Leave out anything that is planned or only true in the art files.
- **Small servers, modest machines.** It is built for a few players together (2 to 10) on a GTX 1050 Ti class
  machine with 16 GB of RAM, and it needs no shader pack.

## Project rules

1. **Client first.** Everything visual must work on a vanilla server, and when the server lacks the mod. Use vanilla
   sound events and particle types only, and do not register blocks, items, entities, particles or sounds. The
   one deliberate exception is the server half (`server/`, `net/`), which keeps bodies, reports hits and relays player
   actions. Anything that needs registered content is an add-on with its own mod id and jar, like the Cobble Tank
   (its own project, `../CobbleTank`, mod id `cobbletank`). An add-on may use Fluid Gore's public hooks
   (`net/VesselBurstPayload`, `net/VesselBursts`, `Demos.add`, `GoreSettingsScreen.addSection`, damage-type tags such
   as `fluidgore:gas_jet`), and should run without Fluid Gore, as the Cobble Tank does. Fluid Gore never refers to an
   add-on's classes.

   Every client feature must still work when `GoreSync.serverKeepsBodies()` and `GoreSync.serverReportsHits()` are
   false.
2. **Visual by default.** Never change health, damage, drops, loot, AI or game rules. The exceptions are listed in the
   README under "Where it runs": kept bodies, stained cleaning tools and fire from burning bodies, and the
   Cobble Tank's own damage. A new exception has to be a deliberate decision, added to that list.
3. **The server half stays small.** The server says only what it knows (damage, weapon, ray, seed, what a player did)
   and never works out gore. Its work must be cheap and capped. Treat every packet as hostile: check that numbers are
   finite, check distances and ownership, and rate-limit per player (`server/Throttle`).
4. **Every screen must agree.**
   - Any random choice that changes what a hit looks like must use a `Fate.stream(name)`, never
     `RandomSource.create()`.
   - What a body does over time uses `Corpse.random`, because per-tick draws differ between clients.
   - A new way of acting on a body goes through `GoreSync.act`, and a piece is always found by its `GorePart.key`.
5. **26.3 is not 1.21.** Names are Mojang's unobfuscated ones, and many APIs changed: render states,
   `SubmitNodeCollector`, `LevelRenderEvents`, `GuiGraphicsExtractor`, `Identifier`. Check the real source before
   guessing (see below).
6. **Every tunable lives in `config/GoreConfig.java`** (an add-on's in its own config, such as `TankConfig`) and is
   sanitised in `sanitize()`. A user-facing setting also goes in the settings screen, with an `en_us.json` name and a
   `.tooltip` key.
7. **No TODOs or stubs in delivered code.** Build and run the relevant game tests before calling something done.
8. **Anything you can see gets a demo.** Add a demo to `Demos.OWN` (newest first, tagged with its version); an add-on
   registers its own with `Demos.add`. Base it on the feature's game-test scene, placed with `Stage` relative to the
   player. Use only tag-scoped commands:
   never `kill @e` without `tag=fluidgore_demo`, and never `item replace` (use `Script.hold`). A demo that changes
   terrain sets `destructive = true`. The `demos` game test plays every demo.
9. **Generated files are never edited by hand.** This covers every PNG in `assets/fluidgore/textures`,
   `anatomy/SheepTrunk.java`, `anatomy/SheepHead.java`, `anatomy/SheepLegs.java` and `anatomy/CowTrunk.java`, and the READMEs in `art/`. Change the generator and re-run
   it. After a texture generator change, run `VerifyTextures check`: existing PNGs and anatomy tiles must not change
   unless that is the point (then re-record the manifest with `write`).

## Looking up Minecraft and Fabric APIs
Run `./gradlew genSources`. The decompiled 26.3 source is then in
`.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-*/26.3/*-sources.jar`. Extract it to a scratch folder
and search it. For Fabric API, download `<module>-<version>-sources.jar` from maven.fabricmc.net. The module versions
are listed in the fabric-api 0.162.0+26.3 POM.

## Docs and releases
- Update `README.md` for anything a player sees, `CHANGELOG.md` for every release (Keep a Changelog headings:
  Added, Changed, Fixed, Removed, Security), and `docs/ARCHITECTURE.md` when you add or move a system.
- The version is in `gradle.properties`. A release is a git tag `vX.Y.Z`. Built jars go in `releases/` (git-ignored).
- `notes/` is git-ignored and holds private working notes; nothing in the public docs should depend on it.
