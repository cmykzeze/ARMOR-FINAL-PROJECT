#!/usr/bin/env python3
"""
Ascendant Armor generator.
Single source of truth for all 34 enchantments (11 core + 11 counter + 12 expansion).
Run from the project root:

    python3 tools/generate.py

It (re)writes:
  - src/main/java/com/ascendant/Ench.java
  - src/main/resources/data/ascendant/enchantment/*.json   (stale files are deleted)
  - src/main/resources/data/minecraft/tags/enchantment/*.json
  - src/main/resources/assets/ascendant/lang/en_us.json
  - tools/readme_table.md
"""
import glob, json, os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MOD = "ascendant"

# tier: (label, json color, symbol, max level, weight, base cost, per level, anvil cost, bold)
TIERS = {
    "RARE":      ("Rare",      "aqua",         "\u25C6", 3, 5, 12, 8,  4,  False),
    "EPIC":      ("Epic",      "light_purple", "\u2726", 3, 3, 20, 9,  6,  False),
    "LEGENDARY": ("Legendary", "gold",         "\u2605", 3, 2, 28, 10, 8,  False),
    "MYTHIC":    ("Mythic",    "red",          "\u272A", 2, 1, 36, 12, 10, False),
    "GODLY":     ("Godly",     "dark_red",     "\u2620", 1, 1, 45, 15, 12, True),
}

SLOTS = {
    "HEAD":  ("head",  "head_armor",  "Helmet",     "Z"),
    "CHEST": ("chest", "chest_armor", "Chestplate", "X"),
    "LEGS":  ("legs",  "leg_armor",   "Leggings",   "C"),
    "FEET":  ("feet",  "foot_armor",  "Boots",      "V"),
}

# id, display name, tier, slot, active?, description
CORE = [
    ("warden_roar", "Warden's Roar", "MYTHIC", "HEAD", True,
     "ACTIVE. Unleash a Warden roar: sonic damage, knockback, Darkness and Slowness on every foe nearby. Level II adds two echo shockwaves."),
    ("predator_sight", "Predator's Sight", "EPIC", "HEAD", False,
     "Permanent night vision. Foes below half health glow through walls (range grows per level)."),
    ("clear_mind", "Clear Mind", "RARE", "HEAD", False,
     "Cleanses Blindness, Darkness and Nausea. II: water breathing + cleanses Slowness. III: Conduit Power + cleanses Weakness and Mining Fatigue."),

    ("wither_wrath", "Wither's Wrath", "GODLY", "CHEST", True,
     "ACTIVE. 10 seconds of Wither Aura: foes within 7 blocks wither and burn, you drain their life and gain Strength II and Resistance II."),
    ("bulwark", "Bulwark", "LEGENDARY", "CHEST", False,
     "Dropping below ~40% health triggers Resistance + Absorption (and a shockwave at III). Level III also grants a once-per-5-minutes Second Wind that cheats death."),
    ("retribution", "Retribution", "EPIC", "CHEST", False,
     "Melee attackers take 15% / 30% / 45% of the damage they deal to you (max 8), plus knockback. III also Weakens them."),

    ("dragon_dash", "Dragon's Dash", "MYTHIC", "LEGS", True,
     "ACTIVE. Fly forward like the Ender Dragon, steering with your crosshair and bowling through every foe in your path."),
    ("phantom_stride", "Phantom Stride", "EPIC", "LEGS", False,
     "Speed I-III (and Jump Boost at II+). 6% / 12% / 18% chance to phase through an attack and take no damage (1s internal cooldown)."),
    ("vampiric_pact", "Vampiric Pact", "LEGENDARY", "LEGS", False,
     "Heal 15% / 25% / 35% of all damage you deal. Overflow turns into Absorption hearts."),

    ("earthshatter", "Earthshatter", "MYTHIC", "FEET", False,
     "Negates fall damage and converts the fall into a ground-shattering shockwave that hurts and launches foes."),
    ("emberstride", "Emberstride", "RARE", "FEET", False,
     "Fire Resistance. Sprinting leaves a flame trail that sets nearby foes ablaze (wider and longer per level)."),
]


# ---------------------------------------------------------------------------------------------
# COUNTER SET - one answer for every OP mechanic above. All passive (no extra hotkeys).
# Numbers were nerfed: lower percentages, and immunities now only come at the top levels.
# ---------------------------------------------------------------------------------------------
COUNTERS = [
    ("sculk_ward", "Sculk Ward", "EPIC", "HEAD", False,
     "COUNTER to Warden's Roar. Sonic damage and its debuff durations are cut by 15% / 30% / 45%."),
    ("shadowveil", "Shadowveil", "EPIC", "HEAD", False,
     "COUNTER to Predator's Sight. I: only seen within 8 blocks. II+: immune to Glowing; standing still for 2s while sneaking turns you invisible. III: invisible shortly after you start sneaking."),
    ("meditation", "Meditation", "RARE", "HEAD", False,
     "Stand still for 2s to meditate: Regeneration I (II at II+). III adds Resistance I. Rewards patience, punishes constant movement."),

    ("witherbane", "Witherbane", "LEGENDARY", "CHEST", False,
     "COUNTER to Wither's Wrath. Wrath damage and life-drain are cut by 20% / 40% / 60%. II+ cleanses Poison. III cleanses Wither and is immune to Wrath's wither."),
    ("anathema", "Anathema", "EPIC", "CHEST", False,
     "COUNTER to Vampiric Pact and life-drain. Attackers heal 25% / 50% / 75% less from you, and a Vampiric attacker is hurt by cursed blood."),
    ("bastion", "Bastion", "MYTHIC", "CHEST", False,
     "COUNTER to burst damage. A killing blow leaves you at 1 heart instead, but only if you were above 60% (I) / 45% (II) health. 30s / 20s cooldown."),

    ("rooted", "Rooted", "LEGENDARY", "LEGS", False,
     "COUNTER to knockback and Dragon's Dash. All knockback you take is cut by 25% / 50% / 75%. III: immune to Levitation."),
    ("truestrike", "Truestrike", "RARE", "LEGS", False,
     "COUNTER to Phantom Stride. Your attacks reduce the target's dodge chance by 5% / 10% / 15%."),
    ("hamstring", "Hamstring", "EPIC", "LEGS", False,
     "COUNTER to kiting. Melee hits briefly Slow the target. III: Slowness II and a short Weakness."),

    ("glacial_stride", "Glacial Stride", "RARE", "FEET", False,
     "COUNTER to Emberstride and fire. Extinguishes you. II: immune to ability ignition. III: Fire Resistance and a chill aura that Slows nearby foes."),
    ("tremorguard", "Tremorguard", "MYTHIC", "FEET", False,
     "COUNTER to Earthshatter and shockwaves. Shockwave and lightning damage cut by 20% / 40%, and falls up to ~6 / ~9 blocks do no damage."),
]


# ---------------------------------------------------------------------------------------------
# EXPANSION SET - 12 more enchants, 3 per piece: offense, utility and PvE.
# ---------------------------------------------------------------------------------------------
EXPANSION = [
    ("hunters_mark", "Hunter's Mark", "EPIC", "HEAD", False,
     "Your first hit marks a foe with Glowing. For 5s after each hit, your hits on it deal +10% / 15% / 20% extra damage."),
    ("scholar", "Scholar", "RARE", "HEAD", False,
     "Mob kills drop bonus experience (+3 / +6 / +9 points). III: Luck I."),
    ("radiance", "Radiance", "LEGENDARY", "HEAD", False,
     "A holy aura (6 / 8 / 10 blocks) burns undead and deals radiant damage to them every second. III: Regeneration I."),

    ("heartforge", "Heartforge", "LEGENDARY", "CHEST", False,
     "Forges extra life: +2 / +4 / +6 hearts of maximum health."),
    ("bloodrage", "Bloodrage", "LEGENDARY", "CHEST", False,
     "Every hit you take stacks Strength (up to I / II / III) for 5s. Pain is power."),
    ("stormcoat", "Stormcoat", "EPIC", "CHEST", False,
     "When struck in melee, 20% / 30% / 40% chance to call lightning on the attacker that chains to nearby foes (3s cooldown). Immune to lightning damage."),

    ("cleave", "Cleave", "EPIC", "LEGS", False,
     "Melee hits splash 30% / 50% / 70% of their damage onto other foes around the target."),
    ("adrenaline", "Adrenaline", "EPIC", "LEGS", False,
     "Every kill grants Speed II (longer per level) and heals 2 / 3 / 4 health."),
    ("reapers_toll", "Reaper's Toll", "MYTHIC", "LEGS", False,
     "Kills stack Toll (max 5, lasts 8s). Each stack adds +4% / +8% damage to your hits, and kills heal you."),

    ("skybound", "Skybound", "EPIC", "FEET", True,
     "ACTIVE. Launch skyward and drift down on Slow Falling. II+: the launch blasts nearby foes away. III: also thrusts you forward."),
    ("delver", "Delver", "RARE", "FEET", False,
     "Haste I (II at II+), plus one extra level while underground. III cleanses Mining Fatigue."),
    ("magnetic_stride", "Magnetic Stride", "RARE", "FEET", False,
     "Pulls nearby items and experience orbs to you (5 / 8 / 11 blocks). Sneak to switch it off."),
]

SETS = {}
for _e in CORE:
    SETS[_e[0]] = "core"
for _e in COUNTERS:
    SETS[_e[0]] = "counter"
for _e in EXPANSION:
    SETS[_e[0]] = "expansion"

COUNTER_IDS = {c[0] for c in COUNTERS}
ALL = CORE + COUNTERS + EXPANSION


def w(path, text):
    full = os.path.join(ROOT, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def java_str(s):
    return s.replace("\\", "\\\\").replace('"', '\\"')


def gen_java():
    lines = []
    for (eid, name, tier, slot, active, desc) in ALL:
        const = eid.upper()
        hotkey = SLOTS[slot][3] if active else ""
        lines.append(
            f'    {const}("{eid}", "{java_str(name)}", Tier.{tier}, EquipmentSlot.{slot}, {str(active).lower()}, '
            f'"{hotkey}", "{java_str(desc)}", "{SETS[eid]}")'
        )
    body = ",\n".join(lines)
    src = f'''package com.ascendant;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * AUTO-GENERATED by tools/generate.py - edit the generator, not this file.
 * All 34 Ascendant enchantments (11 core + 11 counter + 12 expansion).
 */
public enum Ench {{
{body};

    public final String id;
    public final String displayName;
    public final Tier tier;
    public final EquipmentSlot slot;
    public final boolean active;
    public final String hotkey;
    public final String blurb;
    public final String set;
    public final boolean counter;
    private ResourceKey<Enchantment> key;

    Ench(String id, String displayName, Tier tier, EquipmentSlot slot, boolean active, String hotkey, String blurb, String set) {{
        this.id = id;
        this.displayName = displayName;
        this.tier = tier;
        this.slot = slot;
        this.active = active;
        this.hotkey = hotkey;
        this.blurb = blurb;
        this.set = set;
        this.counter = set.equals("counter");
    }}

    public ResourceKey<Enchantment> key() {{
        if (key == null) {{
            key = ResourceKey.create(Registries.ENCHANTMENT, Identifier.fromNamespaceAndPath(AscendantMod.MOD_ID, id));
        }}
        return key;
    }}

    public int maxLevel() {{
        return tier.maxLevel;
    }}

    public String slotName() {{
        return switch (slot) {{
            case HEAD -> "Helmet";
            case CHEST -> "Chestplate";
            case LEGS -> "Leggings";
            case FEET -> "Boots";
            default -> slot.getName();
        }};
    }}

    public MutableComponent styledName() {{
        MutableComponent c = Component.literal(tier.symbol + " " + displayName).withStyle(tier.color);
        if (tier == Tier.GODLY) c = c.withStyle(ChatFormatting.BOLD);
        return c;
    }}

    /** Level of this enchantment on the matching armor slot of the entity (0 if none). */
    public int levelOn(LivingEntity entity) {{
        ItemStack stack = entity.getItemBySlot(slot);
        if (stack.isEmpty()) return 0;
        ItemEnchantments enchants = stack.getEnchantments();
        if (enchants.isEmpty()) return 0;
        for (Holder<Enchantment> holder : enchants.keySet()) {{
            if (holder.is(key())) return enchants.getLevel(holder);
        }}
        return 0;
    }}
}}
'''
    w("src/main/java/com/ascendant/Ench.java", src)


def gen_enchant_json():
    keep = {f"{e[0]}.json" for e in ALL}
    for old in glob.glob(os.path.join(ROOT, f"src/main/resources/data/{MOD}/enchantment/*.json")):
        if os.path.basename(old) not in keep:
            os.remove(old)
            print("Removed stale", os.path.basename(old))
    for (eid, name, tier, slot, active, desc) in ALL:
        label, color, sym, maxlvl, weight, base, per, anvil, bold = TIERS[tier]
        slot_json, item_tag, _, _ = SLOTS[slot]
        description = {"translate": f"enchantment.{MOD}.{eid}", "color": color}
        if bold:
            description["bold"] = True
        data = {
            "description": description,
            "supported_items": f"#minecraft:enchantable/{item_tag}",
            "primary_items": f"#minecraft:enchantable/{item_tag}",
            "weight": weight,
            "max_level": maxlvl,
            "min_cost": {"base": base, "per_level_above_first": per},
            "max_cost": {"base": base + 30, "per_level_above_first": per},
            "anvil_cost": anvil,
            "slots": [slot_json],
        }
        w(f"src/main/resources/data/{MOD}/enchantment/{eid}.json", json.dumps(data, indent=2) + "\n")


def gen_tags():
    def ids(pred):
        return [f"{MOD}:{e[0]}" for e in ALL if pred(e)]

    tags = {
        "in_enchanting_table": ids(lambda e: e[2] in ("RARE", "EPIC")),
        "tradeable": ids(lambda e: e[2] in ("RARE", "EPIC")),
        "non_treasure": ids(lambda e: e[2] in ("RARE", "EPIC")),
        "treasure": ids(lambda e: e[2] in ("LEGENDARY", "MYTHIC", "GODLY")),
        "on_random_loot": ids(lambda e: True),
    }
    for name, values in tags.items():
        w(f"src/main/resources/data/minecraft/tags/enchantment/{name}.json",
          json.dumps({"values": values}, indent=2) + "\n")


def gen_lang():
    lang = {}
    for (eid, name, tier, slot, active, desc) in ALL:
        sym = TIERS[tier][2]
        lang[f"enchantment.{MOD}.{eid}"] = f"{sym} {name}"
    lang[f"key.category.{MOD}.keys"] = "Ascendant Armor"
    for slot, (_, _, label, key) in SLOTS.items():
        lang[f"key.{MOD}.{slot.lower()}"] = f"{label} Ability"
    w(f"src/main/resources/assets/{MOD}/lang/en_us.json", json.dumps(lang, indent=2, ensure_ascii=False) + "\n")


def gen_readme_table():
    rows = ["| Piece | Set | Enchantment | Tier | Type | Max Lvl | What it does |", "|---|---|---|---|---|---|---|"]
    for (eid, name, tier, slot, active, desc) in ALL:
        label, _, sym, maxlvl, *_ = TIERS[tier]
        kind = f"Active ({SLOTS[slot][3]})" if active else "Passive"
        rows.append(f"| {SLOTS[slot][2]} | {SETS[eid].capitalize()} | {sym} **{name}** | {label} | {kind} | {maxlvl} | {desc.replace('ACTIVE. ', '')} |")
    w("tools/readme_table.md", "\n".join(rows) + "\n")


if __name__ == "__main__":
    gen_java()
    gen_enchant_json()
    gen_tags()
    gen_lang()
    gen_readme_table()
    print("Generated", len(ALL), "enchantments.")
