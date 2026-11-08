#!/usr/bin/env python3
"""Generates the per-wood resources for Brama's gate and bridge variants.

Eleven woods times three gate types is thirty-three blocks, and each one needs a blockstate, five
block models, five loose-block textures, an item model, a loot table, a recipe, a recipe advancement
and forty-four gate images. Writing those eight hundred-odd files by hand is not work, it is typing,
and typing is where inconsistencies come from -- so they are generated, and this script is the thing
to edit when the pattern needs to change.

It is idempotent: run it again and it rewrites the same files. Nothing it writes is edited by hand
afterwards, with one exception called out below (the oak loose-block textures, which predate this
script and are original artwork).

    python tools/generate_resources.py --gates <dir> --bridges <dir>

Both directories hold one folder per wood, as the artwork is delivered:

    <gates>/spruce/spruce_3x3.png, spruce_damage1_3x3.png, ...
    <bridges>/spruce/spruce_deck_3x3.png, spruce_deck_damage1_3x3.png, ...

Pass neither and it only regenerates the JSON, which is the usual case once the art is in place.

The wood list below must match brama.block.GateWood. It is short, it changes once per Minecraft
release at most, and keeping it in two places is cheaper than reading Java from Python.
"""

import argparse
import json
import os
import shutil
import sys

try:
    from PIL import Image
except ImportError:
    Image = None

# name, id prefix, gate texture family, deck texture family, log tag
WOODS = [
    ("oak",      "",           "wooden",     "deck",           "minecraft:oak_logs"),
    ("spruce",   "spruce_",    "spruce",     "spruce_deck",    "minecraft:spruce_logs"),
    ("birch",    "birch_",     "birch",      "birch_deck",     "minecraft:birch_logs"),
    ("jungle",   "jungle_",    "jungle",     "jungle_deck",    "minecraft:jungle_logs"),
    ("acacia",   "acacia_",    "acacia",     "acacia_deck",    "minecraft:acacia_logs"),
    ("dark_oak", "dark_oak_",  "dark_oak",   "dark_oak_deck",  "minecraft:dark_oak_logs"),
    ("mangrove", "mangrove_",  "mangrove",   "mangrove_deck",  "minecraft:mangrove_logs"),
    ("cherry",   "cherry_",    "cherry",     "cherry_deck",    "minecraft:cherry_logs"),
    ("bamboo",   "bamboo_",    "bamboo",     "bamboo_deck",    "minecraft:bamboo_blocks"),
    ("crimson",  "crimson_",   "crimson",    "crimson_deck",   "minecraft:crimson_stems"),
    ("warped",   "warped_",    "warped",     "warped_deck",    "minecraft:warped_stems"),
]

SIZES = ["2x3", "3x3", "3x4", "3x5", "4x4", "4x6", "5x3", "5x5", "5x7", "7x7", "7x9"]

# Wear stages the blockstate has slots for. The artwork ships four; the fifth falls back to the
# fourth at runtime (see GateTextures), and the loose-block texture for it is a copy.
DAMAGE_STAGES = 5
ART_STAGES = 4

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "src", "main", "resources")

ASSETS = os.path.join(RES, "assets", "brama")
DATA = os.path.join(RES, "data", "brama")

GATE_TEXTURES = os.path.join(ASSETS, "textures", "gate")
BLOCK_TEXTURES = os.path.join(ASSETS, "textures", "block")
BLOCK_MODELS = os.path.join(ASSETS, "models", "block")
ITEM_MODELS = os.path.join(ASSETS, "models", "item")
BLOCKSTATES = os.path.join(ASSETS, "blockstates")
LANG = os.path.join(ASSETS, "lang", "en_us.json")

LOOT = os.path.join(DATA, "loot_tables", "blocks")
RECIPES = os.path.join(DATA, "recipes")
ADVANCEMENTS = os.path.join(DATA, "advancements", "recipes", "brama")
AXE_TAG = os.path.join(RES, "data", "minecraft", "tags", "blocks", "mineable", "axe.json")

# Which gate types a wood comes in, and how each is built.
TYPES = [
    {
        "suffix": "medieval_gate",
        "label": "Medieval Gate",
        "family": "gate",
        "oak_empty": "empty",
        "pattern": ["LIL", "LIL", "LIL"],
        "extra": ("I", {"item": "minecraft:iron_ingot"}),
        "comment": "Timber banded with iron. Yields 4 because gates are built by the dozen -- "
                   "the smallest is 6 blocks and a 7x9 is 63.",
    },
    {
        "suffix": "drawbridge",
        "label": "Drawbridge",
        "family": "deck",
        "oak_empty": "drawbridge_empty",
        "pattern": ["LCL", "LLL", "LCL"],
        "extra": ("C", {"item": "minecraft:chain"}),
        "comment": "A timber deck with the two chains that hoist it.",
    },
    {
        "suffix": "bascule_drawbridge",
        "label": "Bascule Drawbridge",
        "family": "deck",
        # The bascule shares the plain drawbridge's empty models: both are decks, and the particle
        # texture is all an empty model carries.
        "oak_empty": "drawbridge_empty",
        "pattern": ["CLC", "LLL", "CLC"],
        "extra": ("C", {"item": "minecraft:chain"}),
        "comment": "The same deck, but four chains rather than two: the bascule carries a second "
                   "pair up to its counterweighted beams.",
    },
]

# Message and command strings the Java side looks up. Kept here so one run of this script leaves
# the language file complete.
EXTRA_LANG = {
    "message.brama.gate_incomplete": "Needs a complete 3x3, 3x5, 5x5 or 5x7 of gate blocks",
    "message.brama.gate_damaged": "Gate damaged",
    "message.brama.gate_critical": "Gate critically damaged",
    "message.brama.gate_locked": "This gate belongs to %s",
    "message.brama.gate_alarm": "Your gate shut itself: %s is at the gate",
    "message.brama.drawbridge_no_room": "No room to lower the drawbridge",
    "message.brama.drawbridge_obstructed": "Something is blocking the drawbridge from raising",
    "command.brama.not_looking_at_gate": "You must be looking at an assembled gate",
    "command.brama.no_permission": "You do not have permission to manage this gate",
    "command.brama.needs_creative": "Operators only override a gate's access list in creative mode",
    "command.brama.no_change": "Nothing changed",
    "command.brama.none": "none",
    "command.brama.unowned": "nobody",
    "command.brama.trusted": "Trusted %s on this gate",
    "command.brama.untrusted": "Removed trust from %s on this gate",
    "command.brama.opped": "%s is now a co-owner of this gate",
    "command.brama.unopped": "%s is no longer a co-owner of this gate",
    "command.brama.trusted_team": "Trusted team %s on this gate",
    "command.brama.untrusted_team": "Removed trust from team %s on this gate",
    "command.brama.enemy_added": "At war with %s: your gates will shut on them",
    "command.brama.enemy_removed": "No longer at war with %s",
    "command.brama.enemy_list": "At war with: %s",
    "command.brama.info_owner": "Gate owner: %s",
    "command.brama.info_ops": "Co-owners: %s",
    "command.brama.info_trusted": "Trusted: %s",
    "command.brama.info_teams": "Trusted teams: %s",
    "command.brama.info_size": "Footprint: %s",
    "item.brama.gate_hammer": "Gate Hammer",
    "block.brama.portcullis": "Portcullis",
    "itemGroup.brama": "Brama - Medieval Gates",
}

written = {"json": 0, "png": 0, "removed": 0}


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(data, handle, indent=2, ensure_ascii=False)
        handle.write("\n")
    written["json"] += 1


def stage_name(base, damage):
    return base if damage == 0 else "%s_damage%d" % (base, damage)


def block_id(wood_prefix, suffix):
    return wood_prefix + suffix


def title(wood_name, label):
    return "%s %s" % (" ".join(part.capitalize() for part in wood_name.split("_")), label)


# --------------------------------------------------------------------------- textures

def copy_gate_textures(gates_dir, bridges_dir):
    """Installs the delivered artwork under textures/gate/, renaming oak to its legacy family."""
    for wood_name, _prefix, gate_family, deck_family, _tag in WOODS:
        if gates_dir:
            copy_family(os.path.join(gates_dir, wood_name), wood_name, gate_family)
        if bridges_dir:
            copy_family(os.path.join(bridges_dir, wood_name), wood_name + "_deck", deck_family)


def copy_family(source_dir, source_base, target_family):
    if not os.path.isdir(source_dir):
        print("  ! no artwork at %s, skipping" % source_dir)
        return

    for damage in range(ART_STAGES):
        for size in SIZES:
            name = "%s_%s.png" % (stage_name(source_base, damage), size)
            source = os.path.join(source_dir, name)
            if not os.path.isfile(source):
                continue
            target = os.path.join(GATE_TEXTURES,
                                  "%s_%s.png" % (stage_name(target_family, damage), size))
            os.makedirs(GATE_TEXTURES, exist_ok=True)
            shutil.copyfile(source, target)
            written["png"] += 1

    # The artwork stops at damage3. Oak used to ship a damage4 set in the old style, and leaving it
    # behind would show one stage of every oak gate in artwork the other ten woods do not have.
    for size in SIZES + [None]:
        stale = "%s_damage%d%s.png" % (target_family, ART_STAGES,
                                       "" if size is None else "_" + size)
        path = os.path.join(GATE_TEXTURES, stale)
        if os.path.isfile(path):
            os.remove(path)
            written["removed"] += 1


def generate_loose_textures():
    """One block of the gate, at native resolution, as the texture of a loose block.

    A loose gate block is one block's worth of gate material, so the truest 16x16 for it is the
    middle cell of that wood's 3x3 artwork -- cut out rather than scaled down, because a whole gate
    shrunk to 16 pixels turns to mush and because these blocks are looked at in bulk while a wall of
    them is being built.

    Oak is skipped: its loose-block textures are original artwork that predates the wood variants.
    """
    if Image is None:
        print("  ! Pillow is not installed, skipping loose-block textures "
              "(pip install Pillow, then run again)")
        return

    for wood_name, prefix, gate_family, deck_family, _tag in WOODS:
        if not prefix:
            continue
        for entry in TYPES:
            family = gate_family if entry["family"] == "gate" else deck_family
            target_id = block_id(prefix, entry["suffix"])

            for damage in range(DAMAGE_STAGES):
                # The fifth stage has no artwork of its own; it reuses the fourth.
                art_damage = min(damage, ART_STAGES - 1)
                source = os.path.join(GATE_TEXTURES,
                                      "%s_3x3.png" % stage_name(family, art_damage))
                if not os.path.isfile(source):
                    print("  ! missing %s, cannot cut a loose-block texture from it" % source)
                    continue

                with Image.open(source) as sheet:
                    cell = sheet.convert("RGBA").crop((16, 16, 32, 32))
                    target = os.path.join(BLOCK_TEXTURES,
                                          "%s.png" % stage_name(target_id, damage))
                    os.makedirs(BLOCK_TEXTURES, exist_ok=True)
                    cell.save(target)
                    written["png"] += 1


# ------------------------------------------------------------------------------ json

def generate_block_json():
    for wood_name, prefix, _gate_family, _deck_family, log_tag in WOODS:
        for entry in TYPES:
            target_id = block_id(prefix, entry["suffix"])
            is_oak = not prefix

            write_blockstate(target_id, entry["oak_empty"] if is_oak else None)
            if not is_oak:
                write_models(target_id)
                write_loot(target_id)
            write_recipe(target_id, entry, log_tag)
            write_advancement(target_id, entry)


def write_blockstate(target_id, oak_empty):
    """Loose blocks draw a cube per damage stage; assembled ones draw nothing at all.

    The assembled variants still need *a* model, because that is where the break particles take
    their texture from. Oak keeps pointing at the hand-written empty models it always used; the
    other woods get their own, so a spruce gate does not shower oak-coloured splinters.

    @param oak_empty the empty-model base name for oak, or None for the other woods
    """
    variants = {}
    for damage in range(DAMAGE_STAGES):
        loose = "brama:block/%s" % stage_name(target_id, damage)
        empty = "brama:block/%s" % stage_name(oak_empty or ("empty_" + target_id), damage)
        variants["assembled=false,damage=%d" % damage] = {"model": loose}
        variants["assembled=true,damage=%d" % damage] = {"model": empty}

    write_json(os.path.join(BLOCKSTATES, "%s.json" % target_id), {"variants": variants})


def write_models(target_id):
    for damage in range(DAMAGE_STAGES):
        name = stage_name(target_id, damage)
        write_json(os.path.join(BLOCK_MODELS, "%s.json" % name), {
            "parent": "minecraft:block/cube_all",
            "textures": {"all": "brama:block/%s" % name},
        })
        write_json(os.path.join(BLOCK_MODELS, "%s.json" % stage_name("empty_" + target_id, damage)), {
            "textures": {"particle": "brama:block/%s" % name},
        })

    write_json(os.path.join(ITEM_MODELS, "%s.json" % target_id),
               {"parent": "brama:block/%s" % target_id})


def write_loot(target_id):
    write_json(os.path.join(LOOT, "%s.json" % target_id), {
        "type": "minecraft:block",
        "pools": [{
            "rolls": 1,
            "bonus_rolls": 0,
            "entries": [{"type": "minecraft:item", "name": "brama:%s" % target_id}],
            "conditions": [{"condition": "minecraft:survives_explosion"}],
        }],
    })


def write_recipe(target_id, entry, log_tag):
    key_letter, key_value = entry["extra"]
    write_json(os.path.join(RECIPES, "%s.json" % target_id), {
        "__comment": entry["comment"],
        "type": "minecraft:crafting_shaped",
        "pattern": entry["pattern"],
        "key": {"L": {"tag": log_tag}, key_letter: key_value},
        "result": {"item": "brama:%s" % target_id, "count": 4},
    })


def write_advancement(target_id, entry):
    _letter, ingredient = entry["extra"]
    write_json(os.path.join(ADVANCEMENTS, "%s.json" % target_id), {
        "parent": "minecraft:recipes/root",
        "rewards": {"recipes": ["brama:%s" % target_id]},
        "criteria": {
            "has_ingredient": {
                "trigger": "minecraft:inventory_changed",
                "conditions": {"items": [{"items": [ingredient["item"]]}]},
            },
            "has_the_recipe": {
                "trigger": "minecraft:recipe_unlocked",
                "conditions": {"recipe": "brama:%s" % target_id},
            },
        },
        "requirements": [["has_ingredient", "has_the_recipe"]],
        "sends_telemetry_event": False,
    })


def generate_axe_tag():
    """Every wooden gate and bridge, in the order the woods are declared."""
    values = []
    for wood_name, prefix, _g, _d, _tag in WOODS:
        for entry in TYPES:
            values.append("brama:%s" % block_id(prefix, entry["suffix"]))
    write_json(AXE_TAG, {"replace": False, "values": values})


def generate_lang():
    entries = dict(EXTRA_LANG)
    for wood_name, prefix, _g, _d, _tag in WOODS:
        for entry in TYPES:
            entries["block.brama.%s" % block_id(prefix, entry["suffix"])] = \
                title(wood_name, entry["label"])

    ordered = dict(sorted(entries.items()))
    write_json(LANG, ordered)


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--gates", help="folder of per-wood swinging-gate artwork")
    parser.add_argument("--bridges", help="folder of per-wood bridge-deck artwork")
    parser.add_argument("--no-textures", action="store_true",
                        help="only regenerate JSON, leaving every image alone")
    args = parser.parse_args()

    if not os.path.isdir(RES):
        print("cannot find %s - run this from inside the mod's repository" % RES)
        return 1

    if not args.no_textures and (args.gates or args.bridges):
        print("installing gate artwork")
        copy_gate_textures(args.gates, args.bridges)
    if not args.no_textures:
        print("cutting loose-block textures")
        generate_loose_textures()

    print("writing blockstates, models, loot tables, recipes and advancements")
    generate_block_json()
    print("writing the mineable/axe tag")
    generate_axe_tag()
    print("writing en_us.json")
    generate_lang()

    print("done: %d json, %d png written, %d stale png removed"
          % (written["json"], written["png"], written["removed"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
