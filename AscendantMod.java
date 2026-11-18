package com.ascendant;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Ascendant Armor - 34 tiered armor enchantments (11 core + 11 counters + 12 expansion) for PvP and PvE. */
public class AscendantMod implements ModInitializer {
    public static final String MOD_ID = "ascendant";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Set to true to let anyone use /ascendant kit and /ascendant book on multiplayer servers. */
    public static final boolean CHEAT_COMMANDS_EVERYWHERE = false;

    /** Server tick counter (shared by cooldowns, scheduler and abilities). */
    public static long TICK = 0;

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                AscendantCommands.register(dispatcher));

        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                Combat.allowDamage(entity, source, amount));
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) ->
                Combat.afterDamage(entity, source, baseDamage, damageTaken, blocked));
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) ->
                Combat.allowDeath(entity, source, amount));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) ->
                Combat.onDeath(entity, source));

        ServerTickEvents.END_SERVER_TICK.register(AscendantMod::onTick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> reset());

        LOGGER.info("Ascendant Armor loaded: {} enchantments. May your fights be dramatic.", Ench.values().length);
    }

    private static void onTick(MinecraftServer server) {
        TICK++;
        Scheduler.tick();
        Actives.tick(server);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            Passives.trackHealth(p); // every tick (Bastion)
            if (TICK % 2 == 0) Passives.tick(p, TICK);
        }
    }

    private static void reset() {
        Cooldowns.clear();
        Scheduler.clear();
        Actives.clear();
        Passives.clear();
        Combat.clear();
    }
}
