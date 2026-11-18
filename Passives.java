package com.ascendant;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Always-on armor effects.
 * Core: Predator's Sight, Clear Mind, Phantom Stride, Emberstride.
 * Counters: Shadowveil, Meditation, Witherbane, Rooted, Glacial Stride (+ Bastion health tracking).
 * Expansion: Heartforge, Magnetic Stride, Scholar, Delver, Radiance.
 */
public final class Passives {
    private static final Map<UUID, Vec3> LAST_POS = new HashMap<>();
    private static final Map<UUID, Integer> STILL = new HashMap<>();
    private static final Map<UUID, Float> PREV_HEALTH = new HashMap<>();

    private static final Identifier HEARTFORGE_ID = Identifier.fromNamespaceAndPath(AscendantMod.MOD_ID, "heartforge");

    private Passives() {}

    public static void clear() {
        LAST_POS.clear();
        STILL.clear();
        PREV_HEALTH.clear();
    }

    private static void buff(ServerPlayer p, Holder<MobEffect> effect, int duration, int amplifier) {
        p.addEffect(new MobEffectInstance(effect, duration, amplifier, false, false, true));
    }

    /** Health at the end of the previous tick (used by Bastion to detect bursts). Null if not tracked. */
    public static Float prevHealth(ServerPlayer p) {
        return PREV_HEALTH.get(p.getUUID());
    }

    /** Called every tick. */
    public static void trackHealth(ServerPlayer p) {
        if (p.isAlive() && Ench.BASTION.levelOn(p) > 0) {
            PREV_HEALTH.put(p.getUUID(), p.getHealth());
        } else {
            PREV_HEALTH.remove(p.getUUID());
        }
    }

    /** Called every 2 ticks. */
    public static void tick(ServerPlayer p, long tick) {
        if (!p.isAlive() || p.isSpectator()) return;
        fast(p);
        if (tick % 10 == 0) medium(p);
        if (tick % 20 == 0) slow(p);
    }

    private static int stillHalfSeconds(ServerPlayer p) {
        return STILL.getOrDefault(p.getUUID(), 0);
    }

    /** Every 2 ticks: trail effects. */
    private static void fast(ServerPlayer p) {
        heartforge(p);
        magnetic(p);
        int ember = Ench.EMBERSTRIDE.levelOn(p);
        if (ember > 0 && p.isSprinting()) {
            ServerLevel l = Combat.lvl(p);
            Vec3 feet = p.position().add(0, 0.1, 0);
            Fx.particle(l, ParticleTypes.FLAME, feet, 5, 0.25, 0.02);
            Fx.particle(l, ParticleTypes.LAVA, feet, 1, 0.2, 0.0);
            for (LivingEntity e : Combat.foesAround(p, p.position(), 1.5 + ember)) {
                if (Ench.GLACIAL_STRIDE.levelOn(e) >= 2) continue; // Glacial Stride II+ is immune to ignition
                e.setRemainingFireTicks(Math.max(e.getRemainingFireTicks(), 60 + 20 * ember));
            }
        }
    }

    /** Heartforge: +4 max health (2 hearts) per level, as a transient attribute modifier. */
    private static void heartforge(ServerPlayer p) {
        AttributeInstance inst = p.getAttribute(Attributes.MAX_HEALTH);
        if (inst == null) return;
        int lvl = Ench.HEARTFORGE.levelOn(p);
        AttributeModifier cur = inst.getModifier(HEARTFORGE_ID);
        if (lvl > 0) {
            double want = 4.0 * lvl;
            if (cur == null || cur.amount() != want) {
                inst.addOrUpdateTransientModifier(new AttributeModifier(HEARTFORGE_ID, want, AttributeModifier.Operation.ADD_VALUE));
            }
        } else if (cur != null) {
            inst.removeModifier(HEARTFORGE_ID);
            if (p.getHealth() > p.getMaxHealth()) p.setHealth(p.getMaxHealth());
        }
    }

    /** Magnetic Stride: drag nearby items and XP orbs toward the wearer. Sneak to disable. */
    private static void magnetic(ServerPlayer p) {
        int lvl = Ench.MAGNETIC_STRIDE.levelOn(p);
        if (lvl <= 0 || p.isShiftKeyDown()) return;
        double r = 2.0 + 3.0 * lvl;
        ServerLevel l = Combat.lvl(p);
        AABB box = p.getBoundingBox().inflate(r);
        Vec3 target = p.position().add(0, p.getBbHeight() * 0.5, 0);
        for (ItemEntity it : l.getEntitiesOfClass(ItemEntity.class, box, i -> i.isAlive() && i.position().distanceToSqr(target) <= r * r)) {
            pull(it, target);
        }
        for (ExperienceOrb orb : l.getEntitiesOfClass(ExperienceOrb.class, box, o -> o.isAlive() && o.position().distanceToSqr(target) <= r * r)) {
            pull(orb, target);
        }
    }

    private static void pull(net.minecraft.world.entity.Entity e, Vec3 target) {
        Vec3 d = target.subtract(e.position());
        double len = d.length();
        if (len < 0.5) return;
        e.setDeltaMovement(d.scale(0.35 / len));
    }

    /** Every 0.5s: effect refresh. */
    private static void medium(ServerPlayer p) {
        // --- stillness tracking (Shadowveil, Meditation)
        Vec3 pos = p.position();
        Vec3 last = LAST_POS.put(p.getUUID(), pos);
        if (last != null && last.distanceToSqr(pos) < 0.0025) {
            STILL.merge(p.getUUID(), 1, Integer::sum);
        } else {
            STILL.put(p.getUUID(), 0);
        }
        int still = stillHalfSeconds(p);

        // --- core
        int sight = Ench.PREDATOR_SIGHT.levelOn(p);
        if (sight > 0) buff(p, MobEffects.NIGHT_VISION, 240, 0);

        int clear = Ench.CLEAR_MIND.levelOn(p);
        if (clear > 0) {
            p.removeEffect(MobEffects.BLINDNESS);
            p.removeEffect(MobEffects.DARKNESS);
            p.removeEffect(MobEffects.NAUSEA);
            if (clear >= 2) {
                buff(p, MobEffects.WATER_BREATHING, 60, 0);
                p.removeEffect(MobEffects.SLOWNESS);
            }
            if (clear >= 3) {
                buff(p, MobEffects.CONDUIT_POWER, 60, 0);
                p.removeEffect(MobEffects.WEAKNESS);
                p.removeEffect(MobEffects.MINING_FATIGUE);
            }
        }

        int stride = Ench.PHANTOM_STRIDE.levelOn(p);
        if (stride > 0) {
            buff(p, MobEffects.SPEED, 40, stride - 1);
            if (stride >= 2) buff(p, MobEffects.JUMP_BOOST, 40, stride - 2);
        }

        int ember = Ench.EMBERSTRIDE.levelOn(p);
        if (ember > 0) buff(p, MobEffects.FIRE_RESISTANCE, 60, 0);

        // --- expansion
        int scholar = Ench.SCHOLAR.levelOn(p);
        if (scholar >= 3) buff(p, MobEffects.LUCK, 60, 0);

        int delver = Ench.DELVER.levelOn(p);
        if (delver > 0) {
            boolean underground = !Combat.lvl(p).canSeeSky(p.blockPosition());
            int amp = (delver >= 2 ? 1 : 0) + (underground ? 1 : 0);
            buff(p, MobEffects.HASTE, 60, Math.min(amp, 2));
            if (delver >= 3) p.removeEffect(MobEffects.MINING_FATIGUE);
        }

        // --- counters
        int veil = Ench.SHADOWVEIL.levelOn(p);
        if (veil > 0) {
            if (veil >= 2) p.removeEffect(MobEffects.GLOWING);
            boolean sneaking = p.isShiftKeyDown();
            if ((veil >= 3 && sneaking && still >= 1) || (veil >= 2 && sneaking && still >= 4)) {
                buff(p, MobEffects.INVISIBILITY, 40, 0);
            }
        }

        int meditation = Ench.MEDITATION.levelOn(p);
        if (meditation > 0 && still >= 4) {
            buff(p, MobEffects.REGENERATION, 40, meditation >= 2 ? 1 : 0);
            if (meditation >= 3) buff(p, MobEffects.RESISTANCE, 40, 0);
            Fx.particle(Combat.lvl(p), ParticleTypes.HAPPY_VILLAGER, p.position().add(0, 1.0, 0), 3, 0.4, 0.0);
        }

        int bane = Ench.WITHERBANE.levelOn(p);
        if (bane > 0) {
            if (bane >= 2) p.removeEffect(MobEffects.POISON);
            if (bane >= 3) p.removeEffect(MobEffects.WITHER);
        }

        int rooted = Ench.ROOTED.levelOn(p);
        if (rooted >= 3) p.removeEffect(MobEffects.LEVITATION);

        int glacial = Ench.GLACIAL_STRIDE.levelOn(p);
        if (glacial > 0) {
            if (p.isOnFire()) p.clearFire();
            if (glacial >= 3) buff(p, MobEffects.FIRE_RESISTANCE, 60, 0);
        }
    }

    /** Every 1s: heavier scans. */
    private static void slow(ServerPlayer p) {
        int sight = Ench.PREDATOR_SIGHT.levelOn(p);
        if (sight > 0) {
            double range = 12.0 + 6.0 * sight;
            for (LivingEntity e : Combat.foesAround(p, p.position(), range)) {
                int veil = Ench.SHADOWVEIL.levelOn(e);
                if (veil >= 2) continue; // Shadowveil II+ hides from Predator's Sight
                if (veil == 1 && e.distanceToSqr(p) > 64.0) continue; // Shadowveil I: only seen within 8 blocks
                if (e.getHealth() < e.getMaxHealth() * 0.5f) {
                    e.addEffect(new MobEffectInstance(MobEffects.GLOWING, 40, 0, false, false, false));
                }
            }
        }

        int radiance = Ench.RADIANCE.levelOn(p);
        if (radiance > 0) {
            ServerLevel l = Combat.lvl(p);
            double r = 4.0 + 2.0 * radiance;
            Fx.ring(l, ParticleTypes.END_ROD, p.position().add(0, 0.3, 0), r, 28, 0.01);
            for (LivingEntity e : Combat.foesAround(p, p.position(), r)) {
                if (!e.getType().is(EntityTypeTags.UNDEAD)) continue;
                e.setRemainingFireTicks(Math.max(e.getRemainingFireTicks(), 60));
                Combat.hurt(l, e, l.damageSources().indirectMagic(p, p), 1.5f * radiance);
                Fx.particle(l, ParticleTypes.END_ROD, e.position().add(0, e.getBbHeight() * 0.5, 0), 6, 0.3, 0.05);
            }
            if (radiance >= 3) buff(p, MobEffects.REGENERATION, 40, 0);
        }

        int glacial = Ench.GLACIAL_STRIDE.levelOn(p);
        if (glacial >= 3) {
            ServerLevel l = Combat.lvl(p);
            Fx.ring(l, ParticleTypes.SNOWFLAKE, p.position().add(0, 0.3, 0), 3.0, 24, 0.02);
            for (LivingEntity e : Combat.foesAround(p, p.position(), 3.0)) {
                e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 0));
            }
        }
    }
}
