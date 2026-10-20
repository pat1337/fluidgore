# Fluid Gore

**Silver-age Minecraft as a butcher's yard.** A client-side Fabric mod for Minecraft Java **26.3** that gives every
blow weight and every animal a death of its own: wounds that open where you strike and deepen with each blow, limbs
and heads hacked off, blood that sprays, pools and runs downhill, and animals that scream, panic and remember.

> **Content note:** this mod shows graphic, realistic violence against animals. That is its point: at Extreme it
> shows plainly what a kill costs the animal. The Gore Level and Suffering sliders scale it down to something mild,
> or switch it off.

![A cow's leg hacked open to the bone](docs/images/wounded-leg.png)

The gore is visual and works on **vanilla servers** with nobody else needing the mod. It never changes health, damage,
drops, AI or game rules, with one exception: the **Cobble Tank** (2.16), a real block that does real damage. Because it
is a registered block, **a server running Fluid Gore 2.16 or later only lets in players who have the same version.**

**Server-reported hits (3.0, with Fluid Gore on the server):** the server tells every player exactly what each hit was:
the damage it did, the weapon that really struck, and the path the blow took (a player's exact view, an arrow's
flight). Wounds land where blows really landed, a shield block draws no blood, and an arrow from a dispenser wounds
where it strikes. Without the mod on the server, each game works it out from what vanilla sends, as before.

**Kept bodies (with Fluid Gore on the server too):** in singleplayer, on a LAN world you host (e4mc included) or on a
Fabric server that runs the mod, a slaughtered animal does not vanish. It dies the vanilla way, gone red and keeled
over onto its side, bleeds out where it lies, and stays there with everything you did to it until someone cleans it
up (right-click it with a sponge) or butchers it apart. Everyone with the mod sees the same chops, cuts
and plucks on it as they happen. Loose limbs and chunks move the same for everyone when someone kicks, carries or throws them, and
what was done stays as evidence: someone who walks in later or rejoins finds the zombie missing its arm and the arm
lying where it fell. This is the one thing the mod changes in the world: the body's drops and experience
still come out when it dies, as in vanilla, and a dead body cannot be milked, sheared, fed, leashed or ridden.

**Multiplayer:** everyone on a server who has the mod sees the same gore: the same wound on the same spot, the same
severed part, the same death. The clients never talk to each other; each one works the outcome out from what the server
already tells everyone (see "How it works").

## Features

### Strike any part of the body
Blows are tested against the animal's real model, not its hitbox, and land exactly where you aimed: a leg, the neck,
the head, the flank. Each blow on the same spot deepens the wound through four layers: **bruise, gash, open meat,
bone**. Wounds are drawn on the model itself, so they move with every step, on living animals mid-fight and on bodies
after death. Open wounds drip, so a wounded animal leaves a trail.

![Wound layers on a leg: bruise, open meat, bone](docs/images/wound-layers.png)

- **Weapons cut differently.** Fists and clubs bruise and crush but barely break the skin, so a beating bleeds
  little outside, and after enough of it the animal bleeds from the mouth and nose instead. A flint scrapes ragged,
  shallow cuts; swords cut; axes chop deep and wide; a pickaxe digs in and knocks bits of fat, meat and bone out
  like mining; arrows and spears punch small, deep holes; animals bite. Each has its own sound.
- **Slashes like real cuts.** Swords, axes and flint leave incised and chop wounds: long and narrow, laid along the
  swing, gaping in the middle and tapering at the ends. Deeper, they open through bruised edges, the yellow rim of fat
  under the skin, the dark split, muscle, and a line of bone. Fists, clubs, points and teeth leave round, ragged wounds.
- **Hack it off.** A blade through a wound that cannot get any deeper takes that limb or the head off. The body keeps
  a raw stump; the piece keeps its gashes and shows meat around bone where it was cut.
- **Flesh by species.** Ordinary animals bleed red. Zombies, drowned, zombified piglins, zoglins and zombie horses
  are rotting meat: the skin round a wound marbles black and green, the fat has turned to grey wax, the cut fills with
  black-brown ooze, the muscle is grey-green and putrid, there is pus, there are maggots (and they drop out), the bone
  is stained yellow, and the blood is dark, thick and slow, with no pulse to jet it. Husks are dried leather that
  splits to a sickly, poison-coloured ooze. Skeletons never bleed: they scuff, crack, chip into shards and shatter.

![Severed pieces with raw cut ends](docs/images/severed.png)

### Broken bones
Blunt blows break skulls, ribs and limbs, with visible fractures and bone shards; repeated blows smash heads or tear
limbs off. Toggle Broken Bones in settings. Horses have heavier, longer deaths and more blood. Their neck and head
sever together, including on donkeys and mules; splitting a horse carcass drops the tail separately. Try
`/gore demo horse`.

### Explosions
A blast is graded the way a real one is, by how close each body was and how much of it the blast could see (the
same measure the game uses for explosion damage, with the real radius of the TNT, creeper or bed):
- **Point blank:** the body is gone. It bursts into hundreds of pieces of muscle, fat, bone, gut, organs, the
  rumen's half-digested grass and hide cut from the animal's own skin, in the proportions a real body is made of.
  The side that took the fireball is seared black. A red mist hangs where it stood.
- **Close:** two to four limbs or the head torn off and thrown, the belly ripped open so the gut and organs spill,
  and the rest thrown up and out like a doll, over walls and out of pits. Dead before it lands.
- **Further off:** shrapnel wounds on the side facing the blast, thrown hard, and a slower death.

Wet pieces smear the walls they hit; some stick there for a while before they peel off and drop. Meat rains down
with a patter of wet slaps and the clatter of bone. Medium gore level and up; never players.

### Every animal is an individual
- **Its own voice and nerve.** Each animal's personality is fixed by its species and identity: voice pitch, how loud
  and long it screams, how quickly it panics, stoic or frantic. Two cows never scream or die alike.
- **Screams** are built from each animal's own vanilla sounds, pitch-bent, layered and re-triggered into screams,
  whimpers, gurgles and panicked bellows.
- **Dying the way animals really do.** Every animal drops the moment it is killed: the legs go and it falls onto its
  side. Then, over three to five seconds, what slaughterhouse and veterinary staff see in a dying animal: a rigid
  phase (legs locked out, head arched back, not breathing), involuntary paddling in bursts that fades and slows,
  a few agonal gasps further and further apart, fine flickers of muscle, and stillness. The heart pumps blood out
  of the worst wound in beats that weaken and slow until it stops, and the pool grows under the body. The cause of
  death shapes it: a head blow is silent and paddles hard; a cut-open animal lies bleeding and kicks late; a broken
  back leaves the hind end dead and the forelegs rigid; a beheaded body kicks hardest of all and never breathes;
  lungs full of blood gurgle and bring it up; a blast leaves it limp; a burnt one draws its limbs in; a beating ends
  in violent kicking. Every length, kick and gasp is rolled anew, so no two deaths are alike. A blow during the
  rigid phase sets the legs going. One death in twenty ends with the body bursting apart when it finally goes.
- **The vanilla death, or death throes.** By default an animal dies the vanilla way, made gory: it goes red and keels
  over onto its side in a second, its heart pumping the blood out of it as it lies still. It goes over away from the
  blow; a sword turns it with the swing and throws blood off the slash, an axe hacks into it and its legs buckle a
  moment before it keels over. Red until it is down, then just dead. Turn on Death Throes for the
  realistic deaths described above instead.
- **Bodies stay.** The body outlives the server's entity: it falls, is thrown along the killing blow, and can still
  be struck, shoved and cut where it lies. On a server with the mod it stays until it is cleaned up (see above).

### Mothers and their young
Hurt a calf and it cries for its mother, and she answers: her head comes up and turns to the cries, and she shakes.
A dying calf keeps calling for her, thinner and weaker each time, until its last breath. Then there is a moment of
silence, and she starts to call.

She grieves for minutes, not seconds: head hung, turned to where it happened, calling to it again and again in a long
low call that breaks in the middle. She goes on long after the body is gone, less often and more quietly as time
passes. Kill a mother and her calf is left calling for her. Killing the young weighs on the whole herd far longer
than killing a grown animal.

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

### Cleaning up (3.1)
A sponge or brush scrubs blood away, and it soaks up what it takes: the item goes red in three stages, in the
blood's own colour. The bloodier it gets, the less it lifts and the more it drags along the stroke, and guts on the
floor are squashed into dark streaks. Soaked through, it leaves red wipes even on a clean floor. Use it on water, a
water cauldron, or while standing in water to rinse it; a sponge washed clean comes out a wet sponge. With the mod on
the server everyone sees the stained item. Try `/gore demo sponge`.

### Butchery
Process a carcass the way it is done on a farm: right-click a dead chicken with an empty hand to pluck it, a
handful of feathers at a time, down to bare, goose-pimpled skin. Take the head, wings and legs off at the joints
with a blade. Cut through the carcass to the bone and it splits in two, and a blade on any loose piece cuts it in
two again, raw on the cut face, until it is diced. The body stays on the ground as long as you keep working on it.
On a server with the mod, the other players see each cut as you make it.

### Bodies rot (Extreme)
On the Extreme gore level a body left lying rots the way a real carcass does, one Minecraft day for a real day. Within
hours it is pale and stiff with rigor mortis and the first flies find it. Then it bloats green and tight with gas, its
legs pushed out stiff, until it splits open with a wet rip: maggots, a black pool of fluid spreading under it, the
flies and their buzzing at their worst. It sinks into itself, the bones come through, and what is left is bones and
dried hide, bleaching. The timing follows the pig carcass studies forensic science uses: in the plains a chicken is
bones in about ten days, a pig in four weeks, a cow or horse in five to eight. Heat speeds it up, water halves it,
snow stops it, and in deserts and the Nether a body dries into a leathery mummy instead. Severed pieces rot too, at
their own size's pace. Kept bodies go on rotting while nobody is near and while you sleep, and everyone with the
mod sees the same rot. Rot Speed turns it up if you would rather not wait.

### Inventory sorting
A small extra, the way 1.7.10 did it: a tiny sort button in the top right of every chest, barrel, ender chest and
shulker box and one next to your inventory, or middle-click over either part. Stacks are merged and ordered by item
id, blocks first. The hotbar is left alone. It uses ordinary inventory clicks, so it works on any server.

## Demos
Every feature has a short scene you can play in game, set up right in front of you: type `/gore demo` for the
list (click one to play it), or `/gore demo <name>`. `pit` digs a 5x5 pit of pigs and lights a TNT in it;
`weapons`, `hack`, `headless`, `beating`, `grief`, `personalities`, `bones` and `launch` show the rest.
`/gore demo stop` ends one, `/gore demo clear` removes what demos summoned.

Demos summon animals with ordinary commands, so they need cheats (singleplayer: Open to LAN, Allow Cheats) or
operator rights on a server. Nothing is needed on the server itself. The `pit` demo digs and blows up the ground,
so it asks you to click once more before it does.

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
| Scrub Blood | On | Hold a sponge, wet sponge or brush and hold use on blood to wipe it away. The tool gets bloody and smears more the bloodier it is; use it on water to wash it. Sneak to use the item normally. |
| Fluid Cells/Tick | 64 | Fluid simulation budget per tick. Lower it on a weak CPU. |
| Max Droplets | 600 | Cap on flying droplets. |
| Max Flesh Pieces | 3000 | Cap on the small pieces of flesh and bone from blasts, picks and axes. The oldest go first. |
| Part Lifetime | 60 s | How long severed parts stay at Medium (scaled by Gore Level). |
| Max Severed Parts | 32 | Cap on loose parts; the oldest go first. |
| Play With Parts | On | Punch severed parts, pick them up and throw them, kick them by walking into them, stomp them flat. |
| Blast Gore | On | Explosions shred, tear open and throw bodies by how close they were (see Explosions). |
| Wounds | On | The hit-zone and wound system described above. |
| Wounds on Monsters | On | Wounds on hostile mobs too. |
| Butchery | On | Pluck dead chickens (right-click, empty hand), split carcasses and dice pieces with a blade. |
| Inventory Sorting | On | Sort buttons on chests and your inventory, and middle-click to sort. |
| Rotten Undead | On | Zombies and their kin are rotting meat, with rotten wounds and dark, thick rotten blood. |
| Wounds on Players | On | Wounds on other players. Everyone with the mod sees the same ones. |
| Wounds Heal | On | Wounds close as health comes back and broken bones knit. |
| Wounds Clot After | 30 s | How long a living body keeps dripping after the last blow. |
| Armor on Severed Parts | On | Severed heads and limbs keep the armour they wore. |
| Brutal PvP | On | Players' blades cut other players deeper; a kill on High or Extreme takes more than one limb. |
| Blood on Screen | On | When you are hit, pixel blood is thrown across your screen from that side and runs down it. |
| Low Health Heartbeat | On | Below a third of your health the screen edge goes red and your heart pounds, faster near death. |
| Stagger | 100% | How hard a blow knocks your view aside; near death the view also sways. 0 turns both off. |
| Limping | On | A cut or broken leg makes players and humanoid mobs limp (your own view hitches too). A hurt arm hangs, and a belly wound is held. |
| Bloody Clothes | On | Blood from open wounds soaks a player's jacket, sleeves and trousers and smears their armour. |
| Suffering | Medium | Screams, trembling, death throes and herd panic, separate from Gore Level. Off disables it. |
| Scream Volume | 100% | Also follows the Friendly Creatures volume. |
| Max Screams | 8 | Sounds playing at once; background whimpers and bellows are dropped first. |
| Herd Panic | On | Animals near a slaughter tremble and bellow. |
| Personalities | On | Every animal has its own voice, nerve and way of dying. |
| Ecology | On | Animals remember killing and react by role: prey, herds, mothers, predators, flies. |
| Grief | 600 s | How long a mother grieves for her killed young (and a young animal for its mother), at Medium suffering. 0 turns it off. |
| Death Launch | 100% | How hard the killing blow throws a body. 0 turns it off. |
| Death Throes | Off | Off: the vanilla death, made gory (red, over onto its side, bleeding out, still). On: the realistic death throes. |
| Keep Bodies | On | Server side (singleplayer, LAN host, a server with the mod): bodies stay until cleaned up or butchered apart. |
| Most Kept Bodies | 64 | Server side: kept bodies per dimension; the oldest goes up in smoke. |
| Bodies Rot | On | Extreme only: kept bodies and severed pieces rot by the day, from rigor and bloat to maggots and bones. |
| Rot Speed | 1x | 1x is real time (a Minecraft day of rot per day); up to 100x. |
| Body Lingers | 10 s | How long a body stays after it dies when the server does not keep bodies. |
| Red Death | On | Dying bodies stay red until they are gone, like the vanilla death. |
| Death Pop | On | Some bodies, when they finally go, pop into their head, limbs and scraps of flesh, which you can kick about. |
| Death Pop Chance | 5% | How many deaths end in that burst; the rest die out on the ground and are left lying. |

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
  damage packet arrives a tick later, after knockback has moved the legs). Another player's swing is traced along where
  the server says they are looking, so it lands on the same spot on everyone's screen.
- **Shared dice.** Every random choice a hit makes (how deep the wound goes, whether a part comes off, which death an
  animal dies, how its body thrashes) is seeded from facts every client gets the same way: the victim's UUID, the
  attacker, and the victim's exact health after the blow. Every player with the mod rolls the same numbers, so two
  players watching the same fight see the same thing. Severed parts and bodies still run their physics on each
  client, so they can come to rest a little apart.
- **Hit zones.** The ray is tested against the cubes of the entity's own model, posed exactly as it is drawn this
  frame, so it works for any mob built from model parts, modded ones included.
- **Wounds** are stored in each model part's own space and drawn right after the model, inside the same part
  transforms, so they follow the animation without any extra work.
- **Fluid.** Each puddle is a per-block cell with a volume that flows to lower neighbours, within a fixed per-tick
  budget. Decals are cut into pieces that sit on the real surface heights of stairs and slabs.
- **Bodies** are small physics systems (gravity, block collision, toppling, springy joints, weight from body size)
  that keep going after the server has removed the entity.
- **Kept bodies and shared butchery** are the one part that needs the mod on the server. The server stops vanilla
  from removing a dead animal (up to Most Kept Bodies per dimension; the oldest goes up in smoke) and refuses
  interactions with it. A blow, cut, pluck or clean-up on a body is sent to the server as what the player did (where
  they stood and looked, what they held), passed on to the players nearby, and played on each screen with dice seeded
  from that action and the body's UUID, so it comes out the same everywhere. Loose pieces are named by the dice of
  the hit that made them, so a cut on a piece finds the same piece on every screen.

## Building from source

Needs **JDK 25**.

```
./gradlew build              # jar in build/libs/
./gradlew runClient          # development client
./gradlew runClientGameTest  # automated in-game test, singleplayer and dedicated server
```

The game test plays through the mod (kills, wounds, severing, babies, grief, fluid flow, settings screens) and saves
screenshots to `build/run/clientGameTest/screenshots`. Textures are generated, not drawn:
`java tools/GenerateTextures.java src/main/resources/assets/fluidgore`.

## Known limitations

- An item held in a severed hand still renders where the hand was.
- Evidence covers what a player with the mod did; gore from hits no one with the mod made (a skeleton's arrows) is only
  seen by those watching. Blood splats and puddles are not kept. Loose pieces are kept in the server's memory: they
  are gone after the world is closed, while what was done to bodies is saved with them.
- Mobs with unusual models (slimes, fish, ghasts) bleed but have no parts to lose.
- Not yet verified with an Iris shader pack enabled.

## License

[MIT](LICENSE)
