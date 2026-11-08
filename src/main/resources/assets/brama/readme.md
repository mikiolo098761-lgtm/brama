# Making a resource pack for Brama

Everything Brama draws can be replaced by a resource pack — no code, no coremods, nothing to register.
Match a file path and it wins.

Start a pack the normal way:

```
MyBramaPack/
  pack.mcmeta                     { "pack": { "pack_format": 15, "description": "..." } }
  assets/brama/textures/gate/wooden.png
```

Press **F3 + T** in game to reload without restarting.

## The one rule that matters

**Replace the base file and you replace the whole family.**

Brama ships a texture for all eleven gate sizes in every wear stage it draws. Those normally beat the
plain `wooden.png` — which would mean your new base texture got ignored on every gate in the game,
and you'd have to replace forty-four files to change anything.

So a sized file only counts when it comes from a pack at least as high in the stack as the base it
specialises:

| You supply | Result |
|---|---|
| `wooden.png` only | Used for **every** size and stage. Brama's sized files stand down. |
| `wooden.png` + `wooden_5x5.png` | Yours everywhere, with your own art on 5x5. |
| `wooden_5x5.png` only | Only 5x5 changes; Brama's art stays elsewhere. |

The same applies to models.

## What you can replace

### `textures/gate/` — the assembled gates

One image covers a whole gate. It is **not** tiled per block: the renderer projects it across the
gate, so a 3x3 shows one picture rather than nine copies of one.

Images are grouped into **families**, one per gate appearance:

| File | What it is |
|---|---|
| `wooden.png` | The oak swinging gate. The name predates the wood variants and is kept so older packs still fit |
| `spruce.png`, `birch.png`, … | The other ten woods' swinging gates |
| `portcullis.png` | The bar grid |
| `deck.png`, `spruce_deck.png`, … | Bridge decks, and the bascule's beams |
| `<family>_damage1..4.png` | The four wear stages |
| `<family>_<size>.png` | One size at full resolution, e.g. `wooden_3x3.png` |
| `<family>_damage2_5x5.png` | Both at once |

Sizes are 16px per block, so `2x3` is 32x48 and `7x9` is 112x144. Keep both dimensions a multiple of
16 or mipmapping rejects the image. Any size you don't supply falls back to the base, stretched.

**Anything positional gets stretched to gate scale.** A dark column at the image's edge becomes one
dark bar across the whole gate; a band every few rows becomes a stripe. Keep these textures uniform.

Textures on 3D pieces must be **fully opaque** — a silhouette with transparent gaps works on a flat
plane but leaves holes when wrapped round a bar.

### `textures/block/`, `textures/item/` — the loose blocks and the hammer

Ordinary 16x16 block and item textures, deliberately separate from the assembled-gate art above so
you can change one without the other. `gate_detail.png` is the ironwork used by the small model pieces.

### `models/gate/` — the shapes

Ordinary Java block models; open them in Blockbench. See `models/gate/readme.md` for what each piece
is, where the renderer puts it, and which ones are load-bearing.

Body models supply **shape only** — their UVs are ignored, because the gate texture is projected
across them. Change the shape freely and the mapping follows.

### `models/block/`, `blockstates/` — the loose blocks

Standard. Nothing unusual.

### `lang/en_us.json`

Block, item, creative tab and every `/gate` command message. Add `lang/xx_xx.json` to translate.

## What you cannot change from a pack

- **Sounds.** Each gate type names a vanilla sound event in code.
- **Movement.** Swing, lift and fall are all done by the renderer; animating a model does nothing.
- **Sizes.** The eleven footprints are fixed at registration. Which ones are *buildable* is a server
  config option, not a pack one.

## Full reference

- `models/gate/readme.md` — the model kit, piece by piece
- `textures/gate/readme.md` — the texture naming scheme
