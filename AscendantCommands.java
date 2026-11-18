package com.ascendant;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * /ascendant list
 * /ascendant use <helmet|chest|legs|boots>      (the hotkeys call this)
 * /ascendant kit [core|counter|expansion]     (cheat: full netherite set; core = 11 offensive enchants, counter = 11 answers, expansion = 12 extras)
 * /ascendant book <enchant> [level]             (cheat: enchanted book)
 */
public final class AscendantCommands {
    private AscendantCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("ascendant");

        // ---- use
        LiteralArgumentBuilder<CommandSourceStack> use = Commands.literal("use");
        use.then(Commands.literal("helmet").executes(c -> use(c.getSource(), EquipmentSlot.HEAD)));
        use.then(Commands.literal("chest").executes(c -> use(c.getSource(), EquipmentSlot.CHEST)));
        use.then(Commands.literal("legs").executes(c -> use(c.getSource(), EquipmentSlot.LEGS)));
        use.then(Commands.literal("boots").executes(c -> use(c.getSource(), EquipmentSlot.FEET)));
        root.then(use);

        // ---- list
        root.then(Commands.literal("list").executes(c -> list(c.getSource())));

        // ---- kit
        root.then(Commands.literal("kit")
                .executes(c -> kit(c.getSource(), null))
                .then(Commands.literal("core").executes(c -> kit(c.getSource(), "core")))
                .then(Commands.literal("counter").executes(c -> kit(c.getSource(), "counter")))
                .then(Commands.literal("expansion").executes(c -> kit(c.getSource(), "expansion"))));

        // ---- book
        LiteralArgumentBuilder<CommandSourceStack> book = Commands.literal("book");
        for (Ench e : Ench.values()) {
            book.then(Commands.literal(e.id)
                    .executes(c -> book(c.getSource(), e, e.maxLevel()))
                    .then(Commands.argument("level", IntegerArgumentType.integer(1, e.maxLevel()))
                            .executes(c -> book(c.getSource(), e, IntegerArgumentType.getInteger(c, "level")))));
        }
        root.then(book);

        dispatcher.register(root);
    }

    private static int use(CommandSourceStack src, EquipmentSlot slot) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        Actives.use(p, slot);
        return 1;
    }

    private static int list(CommandSourceStack src) {
        src.sendSuccess(() -> Component.literal("=== Ascendant Armor ===").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
        for (Ench e : Ench.values()) {
            MutableComponent line = e.styledName();
            String tag = " [" + Character.toUpperCase(e.set.charAt(0)) + e.set.substring(1) + " " + e.tier.label + " " + e.slotName() + (e.active ? ", hotkey " + e.hotkey : ", passive") + "]";
            line.append(Component.literal(tag).withStyle(ChatFormatting.GRAY));
            src.sendSuccess(() -> line, false);
        }
        return 1;
    }

    private static boolean cheatsAllowed(CommandSourceStack src) {
        if (AscendantMod.CHEAT_COMMANDS_EVERYWHERE || src.getServer().isSingleplayer()) return true;
        src.sendFailure(Component.literal("Ascendant cheat commands only work in singleplayer. Use /enchant on servers."));
        return false;
    }

    private static Holder<Enchantment> holder(CommandSourceStack src, net.minecraft.resources.ResourceKey<Enchantment> key) {
        return src.getServer().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
    }

    private static int book(CommandSourceStack src, Ench e, int level) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!cheatsAllowed(src)) return 0;
        ServerPlayer p = src.getPlayerOrException();
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        Holder<Enchantment> h = holder(src, e.key());
        EnchantmentHelper.updateEnchantments(book, m -> m.set(h, level));
        give(p, book);
        src.sendSuccess(() -> Component.literal("Gave book: ").append(e.styledName()), false);
        return 1;
    }

    private static int kit(CommandSourceStack src, String setFilter) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!cheatsAllowed(src)) return 0;
        ServerPlayer p = src.getPlayerOrException();
        ItemStack[] pieces = {
                new ItemStack(Items.NETHERITE_HELMET), new ItemStack(Items.NETHERITE_CHESTPLATE),
                new ItemStack(Items.NETHERITE_LEGGINGS), new ItemStack(Items.NETHERITE_BOOTS)
        };
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (int i = 0; i < pieces.length; i++) {
            ItemStack stack = pieces[i];
            EquipmentSlot slot = slots[i];
            Holder<Enchantment> unbreaking = holder(src, Enchantments.UNBREAKING);
            Holder<Enchantment> mending = holder(src, Enchantments.MENDING);
            Holder<Enchantment> protection = holder(src, Enchantments.PROTECTION);
            EnchantmentHelper.updateEnchantments(stack, m -> {
                m.set(unbreaking, 3);
                m.set(mending, 1);
                m.set(protection, 4);
            });
            for (Ench e : Ench.values()) {
                if (e.slot != slot) continue;
                if (setFilter != null && !e.set.equals(setFilter)) continue;
                Holder<Enchantment> h = holder(src, e.key());
                EnchantmentHelper.updateEnchantments(stack, m -> m.set(h, e.maxLevel()));
            }
            give(p, stack);
        }
        src.sendSuccess(() -> Component.literal("Gave the full Ascendant kit. Hotkeys: Z / X / C / V").withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    private static void give(ServerPlayer p, ItemStack stack) {
        if (!p.addItem(stack)) {
            p.drop(stack, false);
        }
    }
}
