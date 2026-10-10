# Fluid Gore

**Silver-age Minecraft as a butcher's yard.** A client-side Fabric mod for Minecraft Java **26.3** that gives every
blow weight and every animal a death of its own: wounds that open where you strike and deepen with each blow, limbs
and heads hacked off, blood that sprays, pools and runs downhill, and animals that scream, panic and remember.

> **Content note:** this mod shows graphic, realistic violence against animals. That is its point: at Extreme it
> shows plainly what a kill costs the animal. The Gore Level and Suffering sliders scale it down to something mild,
> or switch it off.

![A cow's leg hacked open to the bone](docs/images/wounded-leg.png)

Everything is visual and local to your game. The mod adds no blocks, items, entities, packets or registry entries, so
it works on **vanilla servers** and nobody else needs it installed. It never changes health, damage, drops, AI or game
rules.

## Features

### Strike any part of the body
Blows are tested against the animal's real model, not its hitbox, and land exactly where you aimed: a leg, the neck,
the head, the flank. Each blow on the same spot deepens the wound through four layers: **bruise, gash, open meat,
bone**. Wounds are drawn on the model itself, so they move with every step, on living animals mid-fight and on bodies
after death. Open wounds drip, so a wounded animal leaves a trail.

![Wound layers on a leg: bruise, open meat, bone](docs/images/wound-layers.png)

- **Weapons cut differently.** Fists bruise and split skin, swords cut, axes chop deep and wide, arrows punch small,
  deep holes. Each has its own sound: a dull wet thud, a slice, a heavy chop, a crack once the blade reaches bone.
- **Hack it off.** A blade through a wound that cannot get any deeper takes that limb or the head off. The body keeps
  a raw stump; the piece keeps its gashes and shows meat around bone where it was cut.
- **Flesh by species.** Ordinary animals bleed red. Husks are dried leather that splits to a sickly, poison-coloured
  ooze. Skeletons never bleed: they scuff, crack, chip into shards and shatter.

![Severed pieces with raw cut ends](docs/images/severed.png)

### Every animal is an individual
- **Its own voice and nerve.** Each animal's personality is fixed by its species and identity: voice pitch, how loud
  and long it screams, how quickly it panics, stoic or frantic. Two cows never scream or die alike.
- **Screams** are built from each animal's own vanilla sounds, pitch-bent, layered and re-triggered into screams,
  whimpers, gurgles and panicked bellows.
- **Death throes depend on how it died**: a quick cut-off cry after one clean blow, kicking that dies away, a long,
  brutal wail with spasms after a beating, or a headless run with blood pumping from the neck. Some bolt, some
  collapse slowly, some fold over the wound. Then laboured breaths, and the last one.
- **Bodies stay.** The body outlives the server's entity: it falls, is thrown along the killing blow, and can still
  be struck, shoved and cut where it lies.

![A beheaded cow still running](docs/images/headless-run.png)

### The herd responds
No messages and no lectures; the world responds. Animals near a slaughter tremble, look around wildly and bellow, and
it gets worse with every kill. They remember for the session: survivors cower, keep their distance and stand over
bodies; mothers and young call for each other; wolves and foxes take interest in fresh blood; bodies draw flies.
All of it is pose, sound and particles; real animal behaviour is untouched.

![The aftermath](docs/images/aftermath.png)

### Blood that behaves like a fluid
Droplets fly with gravity and splat on floors and walls. Puddles flow to lower ground, pool in dips, run down stairs
and drip off ledges. Arterial spurts pump in pulses. Blood dries and darkens, rain washes it away, footprints track
through it, and a sponge or brush scrubs it off.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/) **0.19.5+** for Minecraft **26.3** and
   [Fabric API](https://modrinth.com/mod/fabric-api) (0.162.0+26.3 tested).
2. Put `fluidgore-<version>.jar` in your `mods` folder (`%APPDATA%\.minecraft\mods` on Windows).
3. Optional: [Mod Menu](https://modrinth.com/mod/modmenu) for a config button in the mod list.

Open the settings from **Options → Fluid Gore...** (top-left button) or from Mod Menu. Changes apply immediately and
are saved to `config/fluidgore.json`.

Tested alongside Sodium, Iris (without a shader pack), Lithium, FerriteCore, Entity Culling and ImmediatelyFast.

![Settings](docs/images/settings.png)

## Settings

| Setting | Default | What it does |
|---|---|---|
| Gore Level | Medium | Off / Low / Medium / High / Extreme. Scales blood, puddles, how often parts come off, how long it all stays, and how deep wounds go: Low stops at gashes, Medium at open meat, High and Extreme reach the bone. **Off** disables everything and clears existing blood and parts. |
| Affect Players / Mobs / Animals | On | Who bleeds. Mobs are hostile mobs; Animals is everything else that isn't a player. |
| Blood Color / Mob Blood | Red | Blood of players and passive creatures / of hostile mobs. Red, Dark Red, Green, Yellow, Blue, Purple, Black. |
| Style | Redstone | **Redstone**: blocky pixel-art splats like redstone dust. **Realistic**: smooth round splats. **Mixed**. |
| Gore Chunks | On | Heavy hits and kills throw crumbs and bits of meat. |
| Arterial Spurts | On | Bad wounds and stumps pump blood in pulsing jets. |
| Gore Sounds | On | Wet crunches and the weapon-specific impact sounds. |
| Persistence | 180 s | How long blood stays at Medium before fading (scaled by Gore Level). |
| Max Blood Decals | 1500 | Cap on splats and puddle cells; the oldest go first. |
| Wall Splatter | On | Blood sticks to walls and runs down ledges. |
| Bloody Footprints | On | Anything walking through a puddle leaves footprints. |
| Rain Washes Blood | On | Rain fades blood much faster. Blood in water always disperses. |
| Scrub Blood | On | Hold a sponge, wet sponge or brush and hold use on blood to wipe it away. Sneak to use the item normally. |
| Fluid Cells/Tick | 64 | Fluid simulation budget per tick. Lower it on a weak CPU. |
| Max Droplets | 600 | Cap on flying droplets. |
| Part Lifetime | 60 s | How long severed parts stay at Medium (scaled by Gore Level). |
| Max Severed Parts | 32 | Cap on loose parts; the oldest go first. |
| Play With Parts | On | Punch severed parts, pick them up and throw them, kick them by walking into them, stomp them flat. |
| Wounds | On | The hit-zone and wound system described above. |
| Wounds on Monsters | Off | Wounds on hostile mobs too. |
| Wounds on Players | Off | Wounds on other players, on your screen only. |
| Suffering | Medium | Screams, trembling, death throes and herd panic, separate from Gore Level. Off disables it. |
| Scream Volume | 100% | Also follows the Friendly Creatures volume. |
| Max Screams | 8 | Sounds playing at once; background whimpers and bellows are dropped first. |
| Herd Panic | On | Animals near a slaughter tremble and bellow. |
| Personalities | On | Every animal has its own voice, nerve and way of dying. |
| Ecology | On | Animals remember killing and react by role: prey, herds, mothers, predators, flies. |
| Death Launch | 100% | How hard the killing blow throws a body. 0 turns it off. |
| Body Lingers | 45 s | How long a body stays after its death throes. |

Only in `config/fluidgore.json`:

- `sufferingAnimals`: which animals scream and get death throes (farm animals, horses, llamas, camels, rabbits,
  wolves, foxes, cats). Deleting the entry restores the defaults.
- `boneEntities`: bony mobs that crack instead of bleeding (skeletons, strays, wither skeletons, bogged, parched,
  skeleton horses).
- `bloodlessEntities`: entities that never bleed (golems, armor stands, blazes, ...).
- `bladedItems`: what counts as a blade, as item ids (`minecraft:iron_sword`) or tags (`#minecraft:swords`). Default:
  all swords and axes.
- `cleaningItems`: what scrubs blood.
- `heavyHitDamage` (6.0): a non-lethal hit this strong can cut a part off a mob, which keeps walking without it.

## How it works

- **No server needed.** Vanilla servers already send a damage event (who hit whom) and every entity's health. The
  mod pairs the two to see each hit. When you swing, it records where your aim met the model at that moment (the
  damage packet arrives a tick later, after knockback has moved the legs).
- **Hit zones.** The ray is tested against the cubes of the entity's own model, posed exactly as it is drawn this
  frame, so it works for any mob built from model parts, modded ones included.
- **Wounds** are stored in each model part's own space and drawn right after the model, inside the same part
  transforms, so they follow the animation without any extra work.
- **Fluid.** Each puddle is a per-block cell with a volume that flows to lower neighbours, within a fixed per-tick
  budget. Decals are cut into pieces that sit on the real surface heights of stairs and slabs.
- **Bodies** are small physics systems (gravity, block collision, toppling, springy joints, weight from body size)
  that keep going after the server has removed the entity.

## Building from source

Needs **JDK 25**.

```
./gradlew build              # jar in build/libs/
./gradlew runClient          # development client
./gradlew runClientGameTest  # automated in-game test, singleplayer and dedicated server
```

The game test plays through the mod (kills, wounds, severing, babies, fluid flow, settings screens) and saves
screenshots to `build/run/clientGameTest/screenshots`. Textures are generated, not drawn:
`java tools/GenerateTextures.java src/main/resources/assets/fluidgore`.

## Known limitations

- An item held in a severed hand still renders where the hand was.
- Mobs with unusual models (slimes, fish, ghasts) bleed but have no parts to lose.
- Not yet verified with an Iris shader pack enabled.

## License

[MIT](LICENSE)
