package com.ascendant;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Damage-event hooks + target helpers.
 * Core: Phantom Stride, Earthshatter, Retribution, Vampiric Pact, Bulwark.
 * Counters: Truestrike, Tremorguard, Bastion, Rooted, Hamstring, Anathema (+ damage scaling for Sculk Ward / Witherbane / Tremorguard).
 * Expansion: Hunter's Mark, Scholar, Bloodrage, Stormcoat, Cleave, Adrenaline, Reaper's Toll.
 */
public final class Combat {
    /** True while we are dealing our own bonus damage, so enchants don't trigger each other in loops. */
    private static boolean busy = false;

    private record Mark(UUID hunter, long until) {}

    private static final class Toll {
        int stacks;
        long until;
    }

    /** Hunter's Mark: victim -> who marked it and when the mark expires. */
    private static final Map<UUID, Mark> MARKS = new HashMap<>();
    /** Reaper's Toll stacks per player. */
    private static final Map<UUID, Toll> TOLLS = new HashMap<>();

    /** Kinds of ability damage that counter enchants can reduce. */
    public enum Kind { NONE, SONIC, WITHER, SHOCK }

    private Combat() {}

    public static void clear() {
        MARKS.clear();
        TOLLS.clear();
    }

    public static ServerLevel lvl(ServerPlayer p) {
        return (ServerLevel) p.level();
    }

    /** Anything an ability should be allowed to hurt: monsters, mobs targeting you, and (if PvP is on) other players. */
    public static boolean isFoe(ServerPlayer p, LivingEntity e) {
        if (e == p || !e.isAlive()) return false;
        if (e instanceof Player other) {
            if (other.isCreative() || other.isSpectator()) return false;
            if (!lvl(p).getServer().isPvpAllowed()) return false;
            return !p.isAlliedTo(other);
        }
        if (e instanceof Enemy) return true;
        if (e instanceof Mob m) return m.getTarget() == p;
        return false;
    }

    public static List<LivingEntity> foesAround(ServerPlayer p, Vec3 c, double r) {
        AABB box = new AABB(c.x - r, c.y - r, c.z - r, c.x + r, c.y + r, c.z + r);
        return lvl(p).getEntitiesOfClass(LivingEntity.class, box, e -> isFoe(p, e) && e.distanceToSqr(c) <= r * r);
    }

    /** Damage multiplier a victim's counter enchants apply to a kind of ability damage. */
    public static float scale(LivingEntity victim, Kind kind) {
        float s = switch (kind) {
            case SONIC -> 1.0f - 0.15f * Ench.SCULK_WARD.levelOn(victim);
            case WITHER -> 1.0f - 0.20f * Ench.WITHERBANE.levelOn(victim);
            case SHOCK -> 1.0f - 0.20f * Ench.TREMORGUARD.levelOn(victim);
            default -> 1.0f;
        };
        return Math.max(0.05f, s);
    }

    /** How much life-drain survives against this victim (Anathema). */
    public static float drainScale(LivingEntity victim) {
        return Math.max(0.0f, 1.0f - 0.25f * Ench.ANATHEMA.levelOn(victim));
    }

    public static boolean hurt(ServerLevel level, LivingEntity target, DamageSource source, float amount) {
        return hurt(level, target, source, amount, Kind.NONE);
    }

    /** Deal damage that ignores invulnerability frames, respects counters, and never re-triggers our own enchants. */
    public static boolean hurt(ServerLevel level, LivingEntity target, DamageSource source, float amount, Kind kind) {
        float scaled = amount * scale(target, kind);
        boolean prev = busy;
        busy = true;
        try {
            target.invulnerableTime = 0;
            return target.hurtServer(level, source, scaled);
        } finally {
            busy = prev;
        }
    }

    /** Knock a target away from a point, with optional extra lift. Reduced by Rooted. */
    public static void knock(LivingEntity target, Vec3 from, double strength, double lift) {
        double keep = Math.max(0.0, 1.0 - 0.25 * Ench.ROOTED.levelOn(target));
        if (keep <= 0.0) return;
        target.knockback(strength * keep, from.x - target.getX(), from.z - target.getZ());
        if (lift > 0) {
            target.setDeltaMovement(target.getDeltaMovement().add(0.0, lift * keep, 0.0));
        }
        target.hurtMarked = true;
    }

    // ------------------------------------------------------------------ ALLOW_DAMAGE

    public static boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
        if (busy) return true;
        if (!(entity instanceof ServerPlayer p)) return true;

        ServerLevel l = lvl(p);
        Vec3 at = p.position().add(0, 1.0, 0);

        if (source.is(DamageTypeTags.IS_FALL)) {
            // Earthshatter: cancel fall damage, turn the fall into a shockwave.
            int shatter = Ench.EARTHSHATTER.levelOn(p);
            if (shatter > 0) {
                Actives.earthshatter(p, shatter, amount + 3.0f);
                return false;
            }
            // Tremorguard: small falls are harmless.
            int guard = Ench.TREMORGUARD.levelOn(p);
            if (guard > 0 && amount <= 3.0f * guard) {
                Fx.particle(l, ParticleTypes.CLOUD, p.position().add(0, 0.2, 0), 8, 0.3, 0.03);
                return false;
            }
            return true;
        }

        // Stormcoat: immune to lightning.
        if (source.is(DamageTypeTags.IS_LIGHTNING) && Ench.STORMCOAT.levelOn(p) > 0) {
            return false;
        }

        // Phantom Stride: chance to phase through attacks from entities (1s internal cooldown). Truestrike lowers it.
        int stride = Ench.PHANTOM_STRIDE.levelOn(p);
        if (stride > 0 && source.getEntity() != null && Cooldowns.ready(p, "phantom")) {
            float chance = 0.06f * stride;
            if (source.getEntity() instanceof LivingEntity attacker) {
                chance -= 0.05f * Ench.TRUESTRIKE.levelOn(attacker);
            }
            if (chance > 0.0f && p.getRandom().nextFloat() < chance) {
                Cooldowns.set(p, "phantom", 20);
                Fx.sound(l, at, SoundEvents.PHANTOM_SWOOP, 1.0f, 1.5f);
                Fx.particle(l, ParticleTypes.SOUL, at, 14, 0.4, 0.05);
                Fx.particle(l, ParticleTypes.SMOKE, at, 10, 0.3, 0.02);
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ ALLOW_DEATH (Bastion, then Bulwark III: Second Wind)

    public static boolean allowDeath(LivingEntity entity, DamageSource source, float amount) {
        if (!(entity instanceof ServerPlayer p)) return true;
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return true; // /kill, void

        ServerLevel l = lvl(p);
        Vec3 at = p.position().add(0, 1.0, 0);

        // Bastion: can't be burst down from above the threshold.
        int bastion = Ench.BASTION.levelOn(p);
        if (bastion > 0 && Cooldowns.ready(p, "bastion")) {
            Float prev = Passives.prevHealth(p);
            float cap = p.getMaxHealth() * (bastion >= 2 ? 0.45f : 0.60f);
            if (prev != null && prev > cap) {
                Cooldowns.set(p, "bastion", bastion >= 2 ? 400 : 600);
                p.setHealth(Math.min(p.getMaxHealth(), 2.0f)); // 1 heart
                p.clearFire();
                Fx.sound(l, at, SoundEvents.SHIELD_BLOCK, 1.5f, 0.6f);
                Fx.sound(l, at, SoundEvents.ANVIL_LAND, 1.0f, 0.8f);
                Fx.particle(l, ParticleTypes.CRIT, at, 40, 0.6, 0.4);
                Fx.ring(l, ParticleTypes.END_ROD, p.position().add(0, 0.2, 0), 1.8, 24, 0.08);
                p.displayClientMessage(Ench.BASTION.styledName(), true);
                return false;
            }
        }

        // Bulwark III: Second Wind.
        int bulwark = Ench.BULWARK.levelOn(p);
        if (bulwark >= 3 && Cooldowns.ready(p, "bulwark_revive")) {
            Cooldowns.set(p, "bulwark_revive", 6000); // 5 minutes
            p.setHealth(p.getMaxHealth() * 0.35f);
            p.clearFire();
            Fx.sound(l, at, SoundEvents.TOTEM_USE, 1.5f, 0.8f);
            Fx.particle(l, ParticleTypes.TOTEM_OF_UNDYING, at, 80, 0.6, 0.5);
            Actives.bulwarkBurst(p, bulwark);
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ AFTER_DAMAGE

    public static void afterDamage(LivingEntity entity, DamageSource source, float baseDamage, float damageTaken, boolean blocked) {
        if (busy || damageTaken <= 0.0f) return;

        if (entity instanceof ServerPlayer victim && victim.isAlive()) {
            bulwark(victim);
            retribution(victim, source, damageTaken);
            rooted(victim);
            bloodrage(victim, source);
            stormcoat(victim, source);
        }

        if (source.getEntity() instanceof ServerPlayer attacker && entity != attacker) {
            vampiric(attacker, entity, damageTaken);
            hamstring(attacker, entity, source);
            huntersMark(attacker, entity, damageTaken);
            reapersToll(attacker, entity, damageTaken);
            cleave(attacker, entity, source, damageTaken);
        }
    }

    private static void bulwark(ServerPlayer p) {
        int lvl = Ench.BULWARK.levelOn(p);
        if (lvl <= 0) return;
        if (p.getHealth() > p.getMaxHealth() * (0.30f + 0.05f * lvl)) return;
        if (!Cooldowns.ready(p, "bulwark")) return;
        Cooldowns.set(p, "bulwark", 900 - 150 * lvl);
        Actives.bulwarkBurst(p, lvl);
    }

    private static void retribution(ServerPlayer victim, DamageSource source, float taken) {
        int lvl = Ench.RETRIBUTION.levelOn(victim);
        if (lvl <= 0) return;
        if (!(source.getDirectEntity() instanceof LivingEntity attacker) || attacker == victim || !attacker.isAlive()) return;
        ServerLevel l = lvl(victim);
        float reflected = Math.min(taken * 0.15f * lvl, 8.0f);
        hurt(l, attacker, l.damageSources().thorns(victim), reflected);
        knock(attacker, victim.position(), 0.6 + 0.2 * lvl, 0.1);
        if (lvl >= 3) attacker.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60, 0));
        Vec3 at = attacker.position().add(0, attacker.getBbHeight() * 0.5, 0);
        Fx.sound(l, at, SoundEvents.THORNS_HIT, 1.0f, 0.8f);
        Fx.particle(l, ParticleTypes.ENCHANTED_HIT, at, 12, 0.3, 0.2);
    }

    /** Rooted also trims ordinary (vanilla) knockback by scaling the velocity the hit just gave us. */
    private static void rooted(ServerPlayer p) {
        int lvl = Ench.ROOTED.levelOn(p);
        if (lvl <= 0) return;
        double keep = Math.max(0.0, 1.0 - 0.25 * lvl);
        Vec3 v = p.getDeltaMovement();
        p.setDeltaMovement(v.x * keep, v.y, v.z * keep);
        p.hurtMarked = true;
    }

    private static void vampiric(ServerPlayer attacker, LivingEntity victim, float damage) {
        int lvl = Ench.VAMPIRIC_PACT.levelOn(attacker);
        if (lvl <= 0) return;
        ServerLevel l = lvl(attacker);

        // Anathema: cursed blood.
        int curse = Ench.ANATHEMA.levelOn(victim);
        if (curse > 0) {
            hurt(l, attacker, l.damageSources().magic(), 1.0f * curse);
            Fx.particle(l, ParticleTypes.WITCH, attacker.position().add(0, 1.2, 0), 8, 0.3, 0.05);
        }

        float heal = damage * (0.05f + 0.10f * lvl) * drainScale(victim);
        if (heal <= 0.0f) return;
        if (attacker.getHealth() >= attacker.getMaxHealth()) {
            float cap = 4.0f * lvl;
            attacker.setAbsorptionAmount(Math.min(cap, attacker.getAbsorptionAmount() + heal));
        } else {
            attacker.heal(heal);
        }
        Vec3 from = victim.position().add(0, victim.getBbHeight() * 0.6, 0);
        Vec3 to = attacker.position().add(0, 1.2, 0);
        Fx.line(l, ParticleTypes.DAMAGE_INDICATOR, from, to, 0.6);
        Fx.particle(l, ParticleTypes.HEART, to, 2, 0.3, 0.0);
        Fx.sound(l, to, SoundEvents.PHANTOM_BITE, 0.5f, 1.6f);
    }

    private static void hamstring(ServerPlayer attacker, LivingEntity victim, DamageSource source) {
        int lvl = Ench.HAMSTRING.levelOn(attacker);
        if (lvl <= 0 || source.getDirectEntity() != attacker) return;
        victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 30 + 10 * lvl, lvl >= 3 ? 1 : 0));
        if (lvl >= 3) victim.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 40, 0));
        ServerLevel l = lvl(attacker);
        Vec3 at = victim.position().add(0, 0.3, 0);
        Fx.particle(l, ParticleTypes.CRIT, at, 6, 0.25, 0.05);
        Fx.sound(l, at, SoundEvents.PHANTOM_BITE, 0.4f, 0.6f);
    }

    // ------------------------------------------------------------------ EXPANSION: hit effects

    private static void huntersMark(ServerPlayer attacker, LivingEntity victim, float damage) {
        int lvl = Ench.HUNTERS_MARK.levelOn(attacker);
        if (lvl <= 0) return;
        ServerLevel l = lvl(attacker);
        Mark m = MARKS.get(victim.getUUID());
        boolean marked = m != null && m.hunter().equals(attacker.getUUID()) && AscendantMod.TICK < m.until();
        MARKS.put(victim.getUUID(), new Mark(attacker.getUUID(), AscendantMod.TICK + 100));
        Vec3 at = victim.position().add(0, victim.getBbHeight() + 0.3, 0);
        if (!marked) {
            if (Ench.SHADOWVEIL.levelOn(victim) < 2) {
                victim.addEffect(new MobEffectInstance(MobEffects.GLOWING, 100, 0, false, false, false));
            }
            Fx.particle(l, ParticleTypes.CRIT, at, 8, 0.25, 0.05);
            Fx.sound(l, at, SoundEvents.ARROW_HIT_PLAYER, 0.6f, 1.4f);
            return;
        }
        float bonus = damage * (0.05f + 0.05f * lvl);
        hurt(l, victim, l.damageSources().playerAttack(attacker), bonus);
        Fx.particle(l, ParticleTypes.DAMAGE_INDICATOR, at, 3, 0.2, 0.0);
    }

    private static void reapersToll(ServerPlayer attacker, LivingEntity victim, float damage) {
        int lvl = Ench.REAPERS_TOLL.levelOn(attacker);
        if (lvl <= 0) return;
        Toll t = TOLLS.get(attacker.getUUID());
        if (t == null || t.stacks <= 0 || AscendantMod.TICK >= t.until) return;
        float bonus = damage * 0.04f * lvl * t.stacks;
        if (bonus <= 0.0f) return;
        ServerLevel l = lvl(attacker);
        hurt(l, victim, l.damageSources().playerAttack(attacker), bonus);
        Fx.particle(l, ParticleTypes.SOUL, victim.position().add(0, victim.getBbHeight() * 0.5, 0), 2 + t.stacks, 0.25, 0.03);
    }

    private static void cleave(ServerPlayer attacker, LivingEntity victim, DamageSource source, float damage) {
        int lvl = Ench.CLEAVE.levelOn(attacker);
        if (lvl <= 0 || source.getDirectEntity() != attacker) return;
        ServerLevel l = lvl(attacker);
        float splash = Math.min(damage * (0.10f + 0.20f * lvl), 10.0f);
        Vec3 c = victim.position().add(0, victim.getBbHeight() * 0.5, 0);
        boolean any = false;
        for (LivingEntity e : foesAround(attacker, c, 2.0 + 0.5 * lvl)) {
            if (e == victim) continue;
            hurt(l, e, l.damageSources().playerAttack(attacker), splash);
            Fx.particle(l, ParticleTypes.SWEEP_ATTACK, e.position().add(0, e.getBbHeight() * 0.5, 0), 1, 0.0, 0.0);
            any = true;
        }
        if (any) Fx.sound(l, c, SoundEvents.PLAYER_ATTACK_SWEEP, 0.8f, 0.9f);
    }

    // ------------------------------------------------------------------ EXPANSION: when you are hit

    private static void bloodrage(ServerPlayer victim, DamageSource source) {
        int lvl = Ench.BLOODRAGE.levelOn(victim);
        if (lvl <= 0 || source.getEntity() == null) return;
        int cap = lvl - 1; // amplifier cap: Strength I / II / III
        MobEffectInstance cur = victim.getEffect(MobEffects.STRENGTH);
        int next = 0;
        if (cur != null) {
            if (cur.getAmplifier() >= cap) {
                if (cur.getAmplifier() > cap) return; // a stronger Strength (e.g. Wither's Wrath) is active
                next = cap;
            } else {
                next = cur.getAmplifier() + 1;
            }
        }
        victim.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 100, next));
        ServerLevel l = lvl(victim);
        Fx.particle(l, ParticleTypes.ANGRY_VILLAGER, victim.position().add(0, 1.8, 0), 3, 0.25, 0.0);
        Fx.sound(l, victim.position(), SoundEvents.RAVAGER_ROAR, 0.3f, 1.4f);
    }

    private static void stormcoat(ServerPlayer victim, DamageSource source) {
        int lvl = Ench.STORMCOAT.levelOn(victim);
        if (lvl <= 0) return;
        if (!(source.getDirectEntity() instanceof LivingEntity attacker) || attacker == victim || !attacker.isAlive()) return;
        if (!Cooldowns.ready(victim, "stormcoat")) return;
        if (victim.getRandom().nextFloat() >= 0.10f + 0.10f * lvl) return;
        Cooldowns.set(victim, "stormcoat", 60);

        ServerLevel l = lvl(victim);
        Vec3 from = victim.position().add(0, 1.2, 0);
        Vec3 target = attacker.position().add(0, attacker.getBbHeight() * 0.5, 0);
        Fx.sound(l, target, SoundEvents.LIGHTNING_BOLT_THUNDER, 1.2f, 1.3f);
        Fx.line(l, ParticleTypes.ELECTRIC_SPARK, from, target, 0.4);
        Fx.particle(l, ParticleTypes.ELECTRIC_SPARK, target, 30, 0.4, 0.3);
        hurt(l, attacker, l.damageSources().lightningBolt(), 4.0f + 2.0f * lvl, Kind.SHOCK);
        for (LivingEntity e : foesAround(victim, attacker.position(), 3.0 + lvl)) {
            if (e == attacker) continue;
            Vec3 t2 = e.position().add(0, e.getBbHeight() * 0.5, 0);
            Fx.line(l, ParticleTypes.ELECTRIC_SPARK, target, t2, 0.4);
            hurt(l, e, l.damageSources().lightningBolt(), 2.0f + lvl, Kind.SHOCK);
        }
    }

    // ------------------------------------------------------------------ EXPANSION: kills

    /** Called for every death (Fabric AFTER_DEATH). Handles Scholar, Adrenaline, Reaper's Toll and mark cleanup. */
    public static void onDeath(LivingEntity victim, DamageSource source) {
        MARKS.remove(victim.getUUID());
        if (!(source.getEntity() instanceof ServerPlayer killer) || killer == victim) return;
        ServerLevel l = lvl(killer);

        int scholar = Ench.SCHOLAR.levelOn(killer);
        if (scholar > 0 && !(victim instanceof Player)) {
            killer.giveExperiencePoints(3 * scholar);
            Fx.particle(l, ParticleTypes.ENCHANT, victim.position().add(0, 1.0, 0), 12, 0.4, 0.3);
        }

        int adren = Ench.ADRENALINE.levelOn(killer);
        if (adren > 0) {
            killer.addEffect(new MobEffectInstance(MobEffects.SPEED, 60 + 40 * adren, 1));
            killer.heal(1.0f + adren);
            Fx.particle(l, ParticleTypes.CRIT, killer.position().add(0, 1.0, 0), 10, 0.4, 0.2);
        }

        int reaper = Ench.REAPERS_TOLL.levelOn(killer);
        if (reaper > 0) {
            Toll t = TOLLS.computeIfAbsent(killer.getUUID(), k -> new Toll());
            if (AscendantMod.TICK >= t.until) t.stacks = 0;
            t.stacks = Math.min(5, t.stacks + 1);
            t.until = AscendantMod.TICK + 160;
            killer.heal(0.5f * reaper * t.stacks);
            Fx.particle(l, ParticleTypes.SOUL, killer.position().add(0, 1.0, 0), 6 + 2 * t.stacks, 0.4, 0.05);
            Fx.sound(l, killer.position(), SoundEvents.WITHER_HURT, 0.8f, 0.8f + 0.1f * t.stacks);
            killer.displayClientMessage(Ench.REAPERS_TOLL.styledName().append(
                    net.minecraft.network.chat.Component.literal(" x" + t.stacks)), true);
        }
    }
}
