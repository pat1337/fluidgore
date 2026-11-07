# Sheep anatomy at 32x

The same veterinary model of a ewe as `../sheep_anatomy`, at the scale of the game: squashed into the vanilla sheep's
8x6x16 body, inside its fat and muscle, cut into voxels of 16/32 of a pixel and drawn with the mod's own
`anatomy.png` tiles, exactly as the game draws them. The mod's ruminant trunk (`src/main/java/dev/fluidgore/anatomy/SheepTrunk.java`) is generated from `sheep_trunk_32x`.

| File | What | Cubes |
|---|---|---|
| `sheep_trunk_32x.bbmodel` | Sheep trunk at 32x (as the mod draws it) | 1211 |
| `sheep_organs_32x.bbmodel` | Sheep organs at 32x (skeleton and diaphragm left out) | 1059 |
| `sheep_liver_32x.bbmodel` | Sheep liver at 32x | 16 |
| `sheep_heart_lungs_32x.bbmodel` | Sheep heart and lungs at 32x | 66 |
| `sheep_stomachs_32x.bbmodel` | Sheep stomachs at 32x | 79 |
| `sheep_guts_32x.bbmodel` | Sheep intestines, kidneys, spleen at 32x | 47 |
| `sheep_skeleton_32x.bbmodel` | Sheep trunk skeleton at 32x | 130 |

- Blockbench units are the vanilla sheep's own model pixels: the body is x -4..4, 12..18 up, z -8..8, the head
  towards north (-Z). As in the game's model, the sheep's right is -X.
- The `vanilla_sheep_reference` group (hidden) is the vanilla body, head and legs: show it to see where the organs sit.
- Every cube is named by its tissue; groups are organs and their parts, as in the full-size files.
- At this grain the thinnest vessels and ducts may be left out, and only every second rib is kept, so a gap shows between them.
