# Sheep anatomy at 64x

The same veterinary model of a ewe as `../sheep_anatomy`, at the scale of the game: squashed into the vanilla sheep's
8x6x16 body, inside its fat and muscle, cut into voxels of 16/64 of a pixel and drawn with the mod's own
`anatomy.png` tiles, exactly as the game draws them. The mod carries it too (`SheepTrunk.fine()`), to compare detail with the 32x grain in the `resolution` demo.

| File | What | Cubes |
|---|---|---|
| `sheep_trunk_64x.bbmodel` | Sheep trunk at 64x (as the mod draws it) | 5083 |
| `sheep_organs_64x.bbmodel` | Sheep organs at 64x (skeleton and diaphragm left out) | 4524 |
| `sheep_liver_64x.bbmodel` | Sheep liver at 64x | 73 |
| `sheep_heart_lungs_64x.bbmodel` | Sheep heart and lungs at 64x | 363 |
| `sheep_stomachs_64x.bbmodel` | Sheep stomachs at 64x | 237 |
| `sheep_guts_64x.bbmodel` | Sheep intestines, kidneys, spleen at 64x | 203 |
| `sheep_skeleton_64x.bbmodel` | Sheep trunk skeleton at 64x | 497 |

- Blockbench units are the vanilla sheep's own model pixels: the body is x -4..4, 12..18 up, z -8..8, the head
  towards north (-Z). As in the game's model, the sheep's right is -X.
- The `vanilla_sheep_reference` group (hidden) is the vanilla body, head and legs: show it to see where the organs sit.
- Every cube is named by its tissue; groups are organs and their parts, as in the full-size files.
- At this grain the thinnest vessels and ducts may be left out, and every rib is kept.
