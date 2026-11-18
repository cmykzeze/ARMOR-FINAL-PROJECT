package com.ascendant;

import java.util.ArrayList;
import java.util.List;

/** Tiny tick scheduler used for echo shockwaves and expanding rings. */
public final class Scheduler {
    private record Task(long runAt, Runnable action) {}

    private static final List<Task> TASKS = new ArrayList<>();

    private Scheduler() {}

    public static void later(int delayTicks, Runnable action) {
        TASKS.add(new Task(AscendantMod.TICK + Math.max(0, delayTicks), action));
    }

    public static void tick() {
        if (TASKS.isEmpty()) return;
        List<Task> due = new ArrayList<>();
        TASKS.removeIf(t -> {
            if (AscendantMod.TICK >= t.runAt()) {
                due.add(t);
                return true;
            }
            return false;
        });
        for (Task t : due) {
            try {
                t.action().run();
            } catch (Exception e) {
                AscendantMod.LOGGER.error("Scheduled task failed", e);
            }
        }
    }

    public static void clear() {
        TASKS.clear();
    }
}
