# Assembled-gate textures

One image covers a whole gate — it is **not** tiled per block.

Images are grouped into **families**, one per gate appearance. Each gate block names its own:

| Family | Painted on |
|---|---|
| `wooden` | the oak swinging gate — the name predates the wood variants and is kept so old packs still fit |
| `spruce`, `birch`, `jungle`, `acacia`, `dark_oak`, `mangrove`, `cherry`, `bamboo`, `crimson`, `warped` | that wood's swinging gate |
| `deck` | the oak drawbridge and bascule deck |
| `<wood>_deck` | that wood's two bridges |
| `portcullis` | the bar grid |

Within a family:

- `<family>.png` is the fallback: one image stretched over any size.
- `<family>_damage1..4.png` are the wear stages.
- `<family>_<size>.png` overrides one gate size at full resolution, e.g. `wooden_3x3.png` at 48x48.
  Combine with wear: `wooden_damage2_5x5.png`.

Every family ships at all eleven sizes through wear stage 3, plus the unsized image. One gap is
deliberate and is filled by falling back rather than by a file:

- **No stage 4.** No gate type uses five wear stages out of the box. A server that configures one
  gets the stage-3 artwork at stage 4.

The unsized image has a second job besides being the fallback. The bascule's beams and
counterweight are **detail pieces**: their models keep their own UVs and are authored against one
16x16 tile, so that is what they are painted from. A deck family's unsized image is therefore the
timber its bridge's beams are made of — replace it and the beams change with the deck.

Sizes are 16 px per block: `2x3` is 32x48, `5x7` is 80x112, `7x9` is 112x144. Keep both dimensions a
multiple of 16.

Anything you don't provide falls back to the base image, stretched. Drop a PNG in and it's used;
delete it and the fallback returns. Nothing to register.

Loose block textures live in `../block/` and are unrelated.

Full reference: `../../models/gate/readme.md`.
