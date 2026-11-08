# Gate models and textures

How an **assembled** gate is drawn. Two halves to it:

- **Shape** comes from the models in this folder. Ordinary Java block models — edit them in Blockbench.
- **Surface** comes from one image per gate in `assets/brama/textures/gate/`.

Not to be confused with `assets/brama/models/block/` and `textures/block/` — those are the *loose*
block and item models, which Minecraft handles normally.

## The one thing to understand

**One image covers the whole gate.** It is not tiled per block. The renderer projects it across
whatever geometry the models describe, so a 3x3 gate shows one picture, not nine copies of one.

Two consequences, both in your favour:

- **UVs in the body models are ignored.** Move a face, change a shape, don't think about texture
  coordinates at all — the mapping is recomputed from where the geometry sits in the gate. Faces the
  projection hits edge-on, like the sides of a leaf, take their missing coordinate from depth, so an
  edge shows a real slice of timber rather than one column of pixels smeared along it.
- **There are no wear-stage models.** Damage changes the *texture*, not the shape.

## Textures — `assets/brama/textures/gate/`

| File | What it is |
|---|---|
| `wooden.png` | The swinging gate. 16x16, stretched over any size. |
| `portcullis.png` | The bar grid. |
| `deck.png` | The bridge deck, and the bascule's beams. |
| `<name>_damage1.png` … `_damage4.png` | The four wear stages. |

### Per-size textures

Any gate size can have its own image at its own resolution. Append the size to the name:

```
wooden.png               <- fallback: 16x16, stretched over every size
wooden_3x3.png           <- 48x48, used by 3x3 gates only
wooden_5x7.png           <- 80x112
wooden_damage2_5x5.png   <- wear stage 2, on 5x5 only
```

A size with no image of its own **falls back to the base image, stretched** — exactly what every gate
did before per-size textures existed. So you can add one size, ignore the rest, and nothing else
changes.

Sizes are one pixel per gate pixel, i.e. 16 px per block:

| Size | Image | Size | Image | Size | Image |
|---|---|---|---|---|---|
| 2x3 | 32x48 | 4x4 | 64x64 | 5x7 | 80x112 |
| 3x3 | 48x48 | 4x6 | 64x96 | 7x7 | 112x112 |
| 3x4 | 48x64 | 5x3 | 80x48 | 7x9 | 112x144 |
| 3x5 | 48x80 | 5x5 | 80x80 | | |

Keep both dimensions a multiple of 16 — they always are if you follow the table — or mipmapping will
reject the image.

The image maps onto the gate as you see it, **left to right, top to bottom**. On the swinging gate the
left half of the image is the left leaf and the right half is the right leaf, so if you want the two
leaves to mirror each other, paint them that way.

There is nothing to register: the whole `textures/gate/` directory is stitched by
`assets/brama/atlases/blocks.json`. Drop a PNG in and it is used; delete it and the fallback comes
back.

`wooden_3x3.png` and `wooden_7x9.png` ship as worked examples. Delete either and that size goes back
to the stretched 16x16.

Coordinates are clamped to the image, so geometry that pokes past the gate's own bounds — the very
top of a leaf, say — samples the edge pixel rather than running off onto whatever sprite the atlas
stitched next door.

## Models — this folder

### `medieval_gate/` — the wooden swinging gate

| File | Drawn |
|---|---|
| `body.json` | **Once per leaf**, stretched to the leaf's size |

No detail pieces. Handles and ironwork belong in the texture, where they can differ per size and cost
no geometry — `wooden_7x9.png` has its rings painted in.

Because the body is stretched, keep it simple — anything fussy gets smeared across the whole leaf.
Depth is the point, detail is not.

The gate swings **one fixed way**, decided when it is assembled: away from whoever assembled it. It
does not open towards you or away depending on where you stand.

Model detail as if it were on the **near** (left) leaf. The renderer turns the far leaf 180° around,
which mirrors it. An asymmetric piece will therefore face away from you on the far leaf; give it
geometry on both sides if that matters.

### `portcullis/` — the lifting bar grid

| File | Drawn |
|---|---|
| `body.json` | **Once per block**, over the whole footprint |
| `edge.json` | Once per row, on the **last column** only |

The only body that tiles rather than stretches: a grate is defined by its gaps, and they have to land
on block boundaries. Bars *and* rails sit on an 8px pitch, so the openings come out square. The
projected texture keeps the metal continuous even though the geometry repeats.

Two things in there are load-bearing and easy to undo by accident:

- **Rails sit at y 4 and 12, not 0 and 8.** Nothing then lands on the cell's bottom edge, so the foot
  of the gate ends in bar ends rather than a flat bar running across it.
- **`edge.json` exists because the cell cannot close itself.** It drops a bar every 8px from its own
  left edge, so the last one always falls short of the gate's far side and the rails would otherwise
  end in mid air.

### `drawbridge/` — the deck

| File | Drawn |
|---|---|
| `body.json` | **Once**, stretched over width × length |
| `batten.json` | Once per column, on the first and last row only |

`body.json` is the piece you **walk on**. Keep it within z = 0..4 px: the collision slab is 4px thick,
and geometry outside it looks like it floats above or below where you actually stand. x runs across
the bridge, y along it.

### `bascule_drawbridge/` — deck plus overhead beams

| File | Drawn |
|---|---|
| `body.json`, `batten.json` | Inherited from `drawbridge/`, one line each |
| `beam.json` | Tiled along each arm from the pivot outwards |
| `counterweight.json` | Once per arm, one block behind the pivot |

The arm is a whole number of blocks long, because it's tiled from a one-block beam. `beam.json` and
`counterweight.json` are placed **centred** on the pivot line, so the beam sits in the middle of the
model, not at its corner.

### Detail pieces are different

`batten`, `beam` and `counterweight` are **not** projected. They keep the UVs and the texture written
in their own JSON — that's why they can use `block/gate_detail` for ironwork. If you add detail,
author its UVs normally. (`edge` is the exception among the detail pieces: it is part of the grate, so
it is projected like the body.)

**Anything positional in a gate texture gets stretched to gate scale.** A dark column at the edge of
the image becomes one dark bar across the whole gate; a band every few rows becomes a stripe. Keep
gate textures uniform, and put structure in the model instead.

### Per-size models

Same rule as textures — append the size:

```
body.json           <- every size
body_5x5.json       <- 5x5 only
spike_7x9.json      <- longer spikes on the biggest portcullis
```

Found by scanning the pack, so nothing to register.

## Not drawn from a model

The drawbridge **chain**. Its length and angle change every frame as the deck swings, so no fixed
model can describe it.

## Editing

1. Blockbench → **New → Java Block/Item**. Not Bedrock, not Animated Java — those export files the
   game cannot read here.
2. `File → Open` the model you want.
3. Edit, then `File → Save`.
4. Run `.\gradlew processResources` and press **F3 + T** in the running game.

Step 4 matters: the dev client reads from `build/resources/main`, not from `src`, so saving alone
changes nothing on screen. The same applies to textures.

**Do not** animate in Blockbench. All movement comes from the renderer.

## Removing a piece

Empty its `elements` array. An empty model is valid and draws nothing, so you can strip the ring, the
spikes or the battens without touching any Java.
