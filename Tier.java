package com.ascendant;

import net.minecraft.ChatFormatting;

/** Power tiers. Higher tier = rarer, stronger, fewer levels. */
public enum Tier {
    RARE("Rare", ChatFormatting.AQUA, "\u25C6", 3),
    EPIC("Epic", ChatFormatting.LIGHT_PURPLE, "\u2726", 3),
    LEGENDARY("Legendary", ChatFormatting.GOLD, "\u2605", 3),
    MYTHIC("Mythic", ChatFormatting.RED, "\u272A", 2),
    GODLY("Godly", ChatFormatting.DARK_RED, "\u2620", 1);

    public final String label;
    public final ChatFormatting color;
    public final String symbol;
    public final int maxLevel;

    Tier(String label, ChatFormatting color, String symbol, int maxLevel) {
        this.label = label;
        this.color = color;
        this.symbol = symbol;
        this.maxLevel = maxLevel;
    }
}
