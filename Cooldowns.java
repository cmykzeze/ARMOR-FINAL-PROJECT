package com.ascendant;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/** Per-player cooldowns, measured in server ticks. */
public final class Cooldowns {
    private static final Map<UUID, Map<String, Long>> MAP = new HashMap<>();

    private Cooldowns() {}

    private static Map<String, Long> row(ServerPlayer p) {
        return MAP.computeIfAbsent(p.getUUID(), k -> new HashMap<>());
    }

    public static boolean ready(ServerPlayer p, String key) {
        Long t = row(p).get(key);
        return t == null || AscendantMod.TICK >= t;
    }

    public static void set(ServerPlayer p, String key, int ticks) {
        row(p).put(key, AscendantMod.TICK + ticks);
    }

    public static double remainingSeconds(ServerPlayer p, String key) {
        Long t = row(p).get(key);
        if (t == null) return 0;
        return Math.max(0, (t - AscendantMod.TICK) / 20.0);
    }

    public static void clear() {
        MAP.clear();
    }
}
