# Sheep anatomy at 16x

The same veterinary model of a ewe as `../sheep_anatomy`, at the scale of the game: at one scale (4.74 cm a pixel)
inside the vanilla sheep's fleece, which is its 8x6x16 body grown 1.75 px each way, so the ewe keeps her proportions
and the wool hides what reaches past the body (the top of the back, the floor of the belly, the brisket and the
rump); cut into voxels of 16/16 of a pixel and drawn with the mod's own `anatomy.png` tiles, exactly as the game
draws them. The mod carries it too (`SheepTrunk.coarse()`), to compare detail with the 32x grain in the `resolution` demo.

| File | What | Cubes |
|---|---|---|
| `sheep_trunk_16x.bbmodel` | Sheep trunk at 16x (as the mod draws it) | 475 |
| `sheep_organs_16x.bbmodel` | Sheep organs at 16x (skeleton and diaphragm left out) | 351 |
| `sheep_liver_16x.bbmodel` | Sheep liver at 16x | 4 |
| `sheep_heart_lungs_16x.bbmodel` | Sheep heart and lungs at 16x | 17 |
| `sheep_stomachs_16x.bbmodel` | Sheep stomachs at 16x | 31 |
| `sheep_guts_16x.bbmodel` | Sheep intestines, kidneys, spleen at 16x | 33 |
| `sheep_skeleton_16x.bbmodel` | Sheep trunk skeleton at 16x | 123 |

- Blockbench units are the vanilla sheep's own model pixels: the body is x -4..4, 12..18 up, z -8..8, the head
  towards north (-Z). As in the game's model, the sheep's right is -X.
- The `vanilla_sheep_reference` group (hidden) is the vanilla body, head and legs: show it to see where the organs sit.
- Every cube is named by its tissue; groups are organs and their parts, as in the full-size files.
- At this grain the thinnest vessels and ducts may be left out, and only every fourth rib is kept, so a gap shows between them.
