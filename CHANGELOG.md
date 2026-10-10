# Changelog

All notable changes to Fluid Gore. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [1.9.3]

### Fixed
- Hits by other players landed on a random spot: only your own swings were aimed, so when you hit something on the
  head, a friend with the mod saw the wound somewhere else. Other players' swings are now traced along where they
  are looking, so the wound lands on the same spot on everyone's screen. Mobs and arrows still land on a guessed spot.

## [1.9.2]

### Fixed
- The click when a carcass falls over: the body hitting the ground used Minecraft's hard fall-damage crunch every
  time. That crunch now plays one fall in four; the other falls are a dull, heavy thud. The sound of a body
  heaving and dropping back, and of flesh pieces landing, is also a quarter as frequent.

## [1.9.1]

### Fixed
- The clicking when an animal is hit or killed: its cries were the animal's own hurt sound at nearly double pitch,
  chopped short, layered in pairs and restarted every 5 to 9 ticks, which stuttered like a machine gun. Cries are
  now near natural pitch, a death is at most three of them spaced further apart, only the first has a second
  layer, and repeats after a hit are capped at two.

## [1.9.0]

### Added
- Each mob has its own measure of blood (`blood/BloodAmount`): a cow or horse gushes, a chicken or rabbit barely
  bleeds. Exaggerated further on Extreme. Override per mob with `bloodAmounts` in the config.
- Death blood sounds are built like a real bleed (`blood/Gush`): the wound opens, the heart pumps it out in
  weakening jets, it drips and pools, and a heavy kill gurgles. Sounds vary from death to death.
- Blasts sound like one low wet thud and a soft patter of gore coming back down; weeping carcasses and stumps drip
  faintly now and then.

### Fixed
- The clicking: single short sounds are rare now, screams fade to silence instead of being cut off, and burning
  animals sizzle softly and rarely instead of hissing sharply many times a second.
- Flames on a dying, ragdolled animal hung in the air where it used to stand. They are now flame particles on the
  body.
- Gore sounds stacking into a wall of noise: they are quieter and rate-limited when many things die at once.

## [1.8.0]

### Added
- Nine realistic deaths, picked by where and with what the killing blow landed and by the animal itself, with
  enough chance in it that a herd killed the same way dies many different ways:
  - **Stunned**: a heavy blow to the head. It drops where it stands like a sack, lies rigid with its legs locked
    out and its head arched back, then the legs paddle on their own and slowly stop. Not a sound.
  - **Bled out**: an opened artery. It stands swaying while its blood pumps out with every heartbeat, slower each
    time, into a pool that keeps growing; its front knees go, then its hind end, and it lies on its chest before it
    rolls onto its side.
  - **The death run**: it bolts blind, screaming and bleeding, and crashes nose first.
  - **The downer**: down on its front knees, then its chest, head turned back against its flank. It keeps trying to
    heave its hind end up, groaning, and falls back each time, until it rolls over.
  - **Broken back**: the hind end drops into a dog-sit and it drags itself on its front legs, bellowing and
    smearing blood behind it. Falls make this one likely.
  - **Drowning in blood**: neck stretched out low, it coughs up frothy blood with every heave and gasps for air,
    sinks onto its chest and coughs there until it stops.
  - **Gut-shot** and **agonal thrashing after a beating**, as before.
  - **Brain hurt**: it walks in tight circles with its head cocked, crashes over, rolls onto its back kicking at the
    sky, and stiffens there, legs up.
- Every death answers a blow while it dies: a stunned cow's legs start paddling, a bleeding one gushes, a downer
  heaves to get up, a broken-backed one drags itself away from you, a choking one coughs, a circling one turns.
- New sounds built from vanilla ones: a wet cough, a groan of straining to stand, a gasp.
- Burnt bodies: animals on fire char from seared through roasted brown to black and stay that way, alive or dead.
  They smoke, sizzle and smoulder, die writhing, and leave a black scorch mark. Fire sears wounds shut, so burning
  does not bleed.
- On High and Extreme, a body that was beaten or burnt to death lies there a good while as evidence, and blood
  stays on the ground longer than the flesh.
- Settings: Death Pop Chance, Burnt Bodies. Demos: `deaths`, `burnt`, `evidence`.

### Changed
- The death that bursts the body apart at the end (Death Pop) is now rare: one death in twenty (Death Pop Chance,
  5% by default). Every other body is left lying and goes with the vanilla puff of smoke.

## [1.7.0]

### Changed
- Deaths feel like vanilla's, only harder: a dying body stays red until it is gone, keels over onto its side about as
  fast as the vanilla tip-over, and lands with a heavy thud and a jolt of blood.
- Hitting a dying body knocks it about like a struck mob (Terraria-style knockback): it flashes red, skids and hops,
  harder the harder the hit, and every blow keeps it around a little longer. Walking into a body shoves it.
- When a body finally goes it pops: a bigger puff of smoke, and what is left bursts into its head and limbs and some
  scraps of flesh that can still be kicked about for a while.
- Gore does not last as long on High and Extreme (decals, puddles, parts and spurts: High 1.5x -> 1.2x, Extreme
  2x -> 1.3x). Flesh pieces last half as long as severed parts. Bodies lie 10 s after their throes instead of 45 s
  (configs still on the old default are moved over), and the longest throes are shorter.
- Settings: Red Death, Death Pop. Demos: `death`, `floor`.

### Fixed
- Gore floating in the air after explosions or broken blocks. Flesh pieces resting on a block never noticed it was
  gone; now everything resting on or stuck to a block (flesh, parts, splats, puddles) drops the moment the block
  breaks.

## [1.6.0]

### Added
- Explosions graded like real ones, by distance and how much of the body the blast could see, using the real
  blast radius from the server's explosion packet. Point blank: the body bursts into hundreds of pieces of
  muscle, fat, bone, gut, organs, cud and its own hide, the near side seared black, in a red mist. Close: two to
  four limbs or the head torn off and thrown, the belly opened and the gut spilled, the body thrown up and out
  (out of pits too) and dead before it lands. Further off: shrapnel wounds and a hard throw.
- Flesh pieces (giblets) as small tumbling boxes that bounce by tissue, smear walls and floors, stick to walls
  and peel off, and patter down with wet slaps and clattering bone.
- New weapons: flint (ragged, shallow cuts), pickaxe (digs in and knocks out pieces), bites from animals, and
  blasts. Fists and clubs bleed far less on the outside; a long beating makes the animal bleed from the mouth.
- Settings: Blast Gore, Max Flesh Pieces.
- In-game demos: `/gore demo` lists a scene for every feature (pig pit and TNT, weapons, hacking a leg off,
  headless run, a beating in front of the herd, grief, personalities, bones, launch) and plays it in front of
  you. Needs cheats or op; client-side only.

### Fixed
- Skeletons killed by a hit whose attacker the client never learned dropped no bones.

## [1.5.0]

### Added
- Mothers and their young. A hurt calf cries for its mother and she answers, turning to it, shaking. A dying calf
  keeps calling for her, weaker each time. Afterwards she grieves for minutes: head hung, turned to where it
  happened, calling in a long, breaking call that goes on after the body is gone and fades slowly. A calf whose
  mother is killed calls for her the same way. Works for every animal in `sufferingAnimals`.
- Killing the young leaves the herd's fear raised far longer than killing a grown animal, and a small body lies
  where it fell longer.
- Setting: Grief (seconds, 0 turns it off).

## [1.4.0]

### Added
- Hit feedback by weapon and flesh: a dull wet thud for fists, a wet slice for swords, a heavy chop for axes, a crack
  when the blade reaches bone, dry tearing for husks and hard knocks for skeletons. All built from vanilla sounds.
- Blood thrown off the cut along the blow and out of the wound, more the deeper the wound.
- Severed pieces keep the wounds that were cut into them and show a raw cut face (meat around bone) where they were
  joined to the body.
- Body weight: big carcasses take blows, bounce and swing heavily; small bodies fly further and twitch faster.

### Fixed
- Babies and model variants (baby animals, warm and cold cows, pigs, chickens). Renderers swap models per entity
  while drawing, so the mod sometimes measured the wrong one: babies were hit against adult geometry, their wounds
  never showed, and parts cut off them were shrunk twice and started in the wrong place. Each entity now uses the
  model it is really drawn with.

## [1.3.0]

### Added
- The hack model. Blows land on the exact spot of the model you aimed at (leg, head, flank, neck) and open a wound
  there that worsens with each blow to the same spot: bruise, gash, open meat, then bone. Pixel-art wound layers are
  drawn on the model and follow its animation, on living animals mid-fight and on bodies after death.
- Targeted severing: a blade through a wound that cannot get any deeper takes that limb or the head off and leaves
  a raw stump on the body. Open wounds drip, so a hurt animal leaves a trail.
- Gore Level sets wound depth: Low stops at gashes, Medium at open meat, High and Extreme reach the bone.
- Weapons cut differently: fists bruise, swords cut, axes chop deep, arrows punch small, deep holes.
- Materials: husks are dried leather that splits to a sickly ooze; skeletons scuff and crack.
- Settings: Wounds, Wounds on Monsters (off by default), Wounds on Players (off by default).
- Bone bodies: skeletons and other bony mobs never bleed; hits chip them into bone shards and dust, any melee weapon
  can snap a bone off, and loose bones shatter.
- Hitting bodies after death: swing at a body that is down or still dying to spray blood, shove it and cut it.
- Death Launch: the killing blow throws a body along the direction it was struck.
- Personalities: every animal has its own voice, nerve and way of dying, fixed by its species and identity.
- Ecology: animals remember killing for the session. Survivors cower and stand over bodies, mothers and young call
  for each other, predators take interest in fresh blood, and bodies draw flies.
- Scrub Blood: wipe blood away with a sponge or brush.

## [1.2.0]

### Added
- Redstone pixel-art blood style (now the default), with Realistic and Mixed styles.
- Redstone-dust particles, gore chunks (redstone-block crumbs and bits of meat), arterial spurts.
- Log-normal random wound severity, satellite splats, colour variation and wet crunching sounds.

## [1.1.0]

### Added
- Animal Suffering: screams made from each animal's own distorted vanilla sounds, flinching, trembling, limping and
  herd panic.
- Death throes based on how the animal died (quick, normal, brutal, beheaded), and bodies that stay after the server
  removes the entity until their throes are over.
- Headless run, severed heads that twitch and moan.
- Severed parts can be punched, picked up, thrown, kicked and stomped.

## [1.0.0]

### Added
- Blood spray, splat decals on floors and walls that follow stairs and slabs, and a lightweight blood fluid
  simulation with puddles that flow, pool and drip off ledges.
- Bloody footprints, rain and water washing blood away.
- Dismemberment with bladed weapons: parts tumble away as physical objects drawn with the entity's own texture.
- Settings screen and Mod Menu integration.
