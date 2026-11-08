# Brama — Medieval Gates

Castle gates that are actually gate-sized, for **Minecraft 1.20.1 / Forge 47**.

Build a wall of gate blocks, strike it with a Gate Hammer, and they lock together into one working
gate — up to seven blocks wide and nine tall — that swings, lifts or falls as a single piece. Gates
wear down in stages under attack instead of popping out of existence a block at a time, which is what
makes a siege last longer than one creeper.

---

## What is in it

**Four kinds of gate, eleven timbers.**

| Gate | Opens by | Woods |
|---|---|---|
| Medieval Gate | two leaves swinging apart | all 11 |
| Portcullis | winching straight up, clearing two thirds of its height | iron |
| Drawbridge | a deck falling forward into a walkable bridge | all 11 |
| Bascule Drawbridge | the same deck, on counterweighted beams | all 11 |

Oak, spruce, birch, jungle, acacia, dark oak, mangrove, cherry, bamboo, crimson and warped, each
with its own artwork at every gate size. Every wood is crafted from its own logs and is exactly as
tough as every other — pick the one that suits the castle, not the one that survives longest.

**Eleven footprints:** 2x3, 3x3, 3x4, 3x5, 4x4, 4x6, 5x3, 5x5, 5x7, 7x7 and 7x9, each a server
config option so big gates can be restricted.

**Sieges.** An assembled gate wears down in up to five stages, every block of it degrading together
so defenders can see how close it is to falling. Explosions cost a stage rather than blowing a hole,
and so does anything else that removes part of an assembled gate — battering rams, WorldEdit,
machinery from mods this one has never heard of. Creepers can be made harmless to gates while TNT
still works.

**Ownership.** Whoever assembles a gate owns it. `/gate trust` lets other people through, `/gate op`
appoints co-owners, and operators override the lot — in creative mode.

**Gates that watch their own gateway.** An enemy walks up to an open gate and it shuts in their face.
Who counts as an enemy comes from the owner's own `/gate enemy` list, or from whatever claim mod the
server runs.

**Mobs know the difference.** A lowered drawbridge is a walkway as far as pathfinding is concerned,
so villagers and animals cross it instead of walking round. Raise it and they route away again —
including mobs from other mods, with no patching on either side.

---

## Commands

Every command except `/gate enemy` applies to **the gate you are looking at**. No coordinates, no
selection.

| Command | Effect |
|---|---|
| `/gate trust <player>` | Let someone open this gate |
| `/gate untrust <player>` | Take that back |
| `/gate trust team <team>` | Trust a whole scoreboard team |
| `/gate op <player>` | Co-owner: can edit the trust list and disassemble |
| `/gate unop <player>` | Remove a co-owner |
| `/gate info` | Owner, co-owners, trusted players and teams, footprint |
| `/gate enemy add <player>` | Declare war. **Every gate you own** shuts on them |
| `/gate enemy remove <player>` | Call it off |
| `/gate enemy list` | Who you are at war with |

Enmity is per player rather than per gate on purpose: trust is a property of a doorway — you let the
blacksmith through the postern and nowhere else — but war is not, and nobody wants to name the same
besieger on all nine gates of a castle.

---

## Claim mods

The siege watch asks three questions in order, and stops at the first answer:

1. **Can they open the gate?** Owner, co-owners, trusted players and trusted teams never set it off.
2. **Are they on the owner's `/gate enemy` list?** Then they always do.
3. **What does the claim mod say?** Standing inside the gate owner's claim without being a member or
   an ally of it is enough.

With no claim mod and nobody listed, nothing happens — a gate in the wilderness cannot tell an enemy
from a passer-by. Turn on `siege.closeOnStrangers` if you would rather it treated everyone who cannot
open it as hostile.

| Claim mod | How |
|---|---|
| FTB Chunks + FTB Teams | Its own API: claims for the land, teams and declared allies for the diplomacy |
| Open Parties and Claims | Its own API: claims, parties and ally parties |
| Anything else that protects blocks — Flan, GriefPrevention ports, server plugins | The generic probe |

The **generic probe** works by asking the question every protection mod already answers: it offers
the server a cancellable interaction on the gate in the player's name and sees whether anything
refuses it. That makes it work with mods this one has never heard of, at the price of knowing nothing
about alliances. It is throttled to one question per player per gate every five seconds.

Nothing is a hard dependency. Set `siege.claimProvider` to pick one explicitly, or leave it on `auto`.

---

## Configuration

Server-scoped and per-world, at `saves/<world>/serverconfig/brama-server.toml`, so it syncs to
clients and travels with the save. Seed a global default from `defaultconfigs/brama-server.toml`.

- **Per gate type** — damage stages, mining hardness assembled and loose, blast resistance both while
  protected and while vulnerable, and how long it takes to open.
- **general** — which footprints can be built, hammer durability, whether outside forces damage or
  dismantle gates, ownership rules, admin permission level, and whether an operator has to be in
  creative mode to override a gate.
- **explosions** — whether blasts cost a stage, and whether creepers are harmless to gates.
- **siege** — the auto-closing watch: radius, sweep interval, which claim mod to ask, whether allies
  are trusted automatically, and whether defenders are warned.

Every option is commented in the file itself, including why the defaults are what they are.

---

## Resource packs

Built to be reskinned. Every texture and model is replaceable by an ordinary resource pack — no code
and nothing to register.

One image covers a whole gate rather than tiling per block, and a pack can supply art **per gate
size** at full resolution: 48x48 for a 3x3, 112x144 for a 7x9. Replace a single base texture and it
applies to every size; supply sized files and those take over. Models are ordinary Blockbench-editable
JSON, and body models carry shape only — their UVs are ignored and the mapping follows whatever you
build.

Full documentation ships inside the jar at `assets/brama/models/gate/readme.md`.

---

## Building

```bash
./gradlew build
```

The jar lands in `build/libs/`. Java 17, and ForgeGradle fetches everything else on the first run.

```bash
./gradlew runClient    # dev client
./gradlew runServer    # dev server
```

### Regenerating resources

Thirty-three of the thirty-four gate blocks are one of eleven woods, and their blockstates, models,
loot tables, recipes, advancements and language entries are generated rather than written:

```bash
python tools/generate_resources.py                              # JSON and loose-block textures
python tools/generate_resources.py --gates <dir> --bridges <dir>  # also install new artwork
```

Edit the script, not its output. The wood list at the top of it must match `brama.block.GateWood`.

---

## Contributing

Issues and pull requests are welcome.

The code is commented with the *reasoning* rather than the mechanics — why the blast resistance is
zero, why the footprint lives on the block entity, why an assembled gate is invisible. If you change
one of those decisions, change the comment that explains it; if you find a comment that is no longer
true, that is a bug worth reporting on its own.

---

## Licence

MIT. See [LICENSE](LICENSE).
