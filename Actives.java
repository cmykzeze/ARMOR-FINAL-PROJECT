package com.ascendant;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** The four hotkey abilities (Warden's Roar, Wither's Wrath, Dragon's Dash, Skybound) + shared effect routines. */
public final class Actives {
    private static final Map<UUID, Long> WRATH_END = new HashMap<>();
    private static final Map<UUID, Dash> DASHES = new HashMap<>();

    private static final class Dash {
        int left;
        final int lvl;
        final Set<UUID> hit = new HashSet<>();

        Dash(int left, int lvl) {
            this.left = left;
            this.lvl = lvl;
        }
    }

    private Actives() {}

    public static void clear() {
        WRATH_END.clear();
        DASHES.clear();
    }

    public static Ench forSlot(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> Ench.WARDEN_ROAR;
            case CHEST -> Ench.WITHER_WRATH;
            case LEGS -> Ench.DRAGON_DASH;
            case FEET -> Ench.SKYBOUND;
            default -> null;
        };
    }

    private static void msg(ServerPlayer p, Component c) {
        p.displayClientMessage(c, true);
    }

    // =====================================================================================
    //  ENTRY POINT (hotkey -> /ascendant use <slot>)
    // =====================================================================================

    public static void use(ServerPlayer p, EquipmentSlot slot) {
        Ench e = forSlot(slot);
        if (e == null) return;
        int lvl = e.levelOn(p);
        if (lvl <= 0) {
            msg(p, Component.literal("Your " + e.slotName().toLowerCase() + " has no active ability.")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        if (!Cooldowns.ready(p, e.id)) {
            msg(p, e.styledName().append(Component.literal(
                    String.format(" ready in %.1fs", Cooldowns.remainingSeconds(p, e.id))).withStyle(ChatFormatting.GRAY)));
            return;
        }

        boolean fired = false;
        int cooldownTicks = 200;
        switch (e) {
            case WARDEN_ROAR -> {
                wardenRoar(p, lvl);
                fired = true;
                cooldownTicks = lvl >= 2 ? 500 : 600;
            }
            case WITHER_WRATH -> {
                if (WRATH_END.containsKey(p.getUUID())) return;
                witherWrath(p);
                fired = true;
                cooldownTicks = 1200;
            }
            case DRAGON_DASH -> {
                dragonDash(p, lvl);
                fired = true;
                cooldownTicks = lvl >= 2 ? 140 : 200;
            }
            case SKYBOUND -> {
                fired = skybound(p, lvl);
                cooldownTicks = 220 - 20 * lvl;
            }
            default -> {
            }
        }
        if (fired) {
            Cooldowns.set(p, e.id, cooldownTicks);
            msg(p, e.styledName());
        } else {
            msg(p, Component.literal("Path blocked!").withStyle(ChatFormatting.RED));
        }
    }

    // =====================================================================================
    //  HELMET ACTIVE: WARDEN'S ROAR (Mythic)
    // =====================================================================================

    private static void wardenRoar(ServerPlayer p, int lvl) {
        ServerLevel l = Combat.lvl(p);
        double radius = 8 + 4 * lvl;
        float damage = 6.0f + 4.0f * lvl;
        Vec3 c = p.position();

        Fx.sound(l, c, SoundEvents.WARDEN_ROAR, 3.0f, 1.0f);
        Fx.sound(l, c, SoundEvents.WARDEN_SONIC_BOOM, 2.0f, 0.8f);
        Fx.sound(l, c, SoundEvents.WARDEN_HEARTBEAT, 2.0f, 0.6f);

        shockwave(p, radius, damage, lvl);

        // Expanding visual ring.
        for (int i = 1; i <= 8; i++) {
            final int k = i;
            Scheduler.later(i * 2, () -> {
                if (!p.isAlive()) return;
                Vec3 pos = p.position().add(0, 0.2, 0);
                Fx.ring(l, ParticleTypes.SCULK_SOUL, pos, radius * k / 8.0, 20 + k * 8, 0.02);
                Fx.ring(l, ParticleTypes.SCULK_CHARGE_POP, pos.add(0, 0.6, 0), radius * k / 8.0, 12 + k * 4, 0.0);
            });
        }

        // Level II: two echoes.
        if (lvl >= 2) {
            Scheduler.later(14, () -> echo(p, radius, damage * 0.6f, lvl));
            Scheduler.later(28, () -> echo(p, radius, damage * 0.6f, lvl));
        }
    }

    private static void echo(ServerPlayer p, double radius, float damage, int lvl) {
        if (!p.isAlive()) return;
        ServerLevel l = Combat.lvl(p);
        Fx.sound(l, p.position(), SoundEvents.WARDEN_SONIC_BOOM, 1.8f, 1.2f);
        Fx.sound(l, p.position(), SoundEvents.WARDEN_ATTACK_IMPACT, 1.5f, 0.7f);
        shockwave(p, radius, damage, lvl);
    }

    private static void shockwave(ServerPlayer p, double radius, float damage, int lvl) {
        ServerLevel l = Combat.lvl(p);
        Vec3 c = p.position();
        Fx.particle(l, ParticleTypes.SONIC_BOOM, c.add(0, 1.0, 0), 1, 0.0, 0.0);
        for (LivingEntity e : Combat.foesAround(p, c, radius)) {
            float s = Combat.scale(e, Combat.Kind.SONIC); // Sculk Ward shrinks damage and debuff time
            Combat.hurt(l, e, l.damageSources().sonicBoom(p), damage, Combat.Kind.SONIC);
            Combat.knock(e, c, 1.0 + 0.4 * lvl, 0.35);
            e.addEffect(new MobEffectInstance(MobEffects.DARKNESS, Math.max(20, (int) ((120 + 40 * lvl) * s)), 0));
            e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, Math.max(20, (int) ((100 + 40 * lvl) * s)), 1));
            e.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, Math.max(20, (int) ((100 + 40 * lvl) * s)), 0));
            Vec3 target = e.position().add(0, e.getBbHeight() * 0.5, 0);
            Fx.line(l, ParticleTypes.SCULK_SOUL, p.getEyePosition(), target, 0.5);
            Fx.particle(l, ParticleTypes.SONIC_BOOM, target, 1, 0.0, 0.0);
        }
    }

    // =====================================================================================
    //  CHESTPLATE ACTIVE: WITHER'S WRATH (Godly)
    // =====================================================================================

    private static void witherWrath(ServerPlayer p) {
        ServerLevel l = Combat.lvl(p);
        Vec3 c = p.position();
        WRATH_END.put(p.getUUID(), AscendantMod.TICK + 200);

        Fx.sound(l, c, SoundEvents.WITHER_SPAWN, 2.5f, 0.9f);
        Fx.sound(l, c, SoundEvents.WITHER_AMBIENT, 2.0f, 0.6f);
        Fx.particle(l, ParticleTypes.SOUL_FIRE_FLAME, c.add(0, 1.0, 0), 80, 0.8, 0.15);
        Fx.particle(l, ParticleTypes.LARGE_SMOKE, c.add(0, 1.0, 0), 60, 1.0, 0.1);
        for (int i = 1; i <= 6; i++) {
            final int k = i;
            Scheduler.later(i * 2, () -> {
                if (p.isAlive()) Fx.ring(l, ParticleTypes.SOUL, p.position().add(0, 0.3, 0), k * 1.2, 24 + k * 6, 0.03);
            });
        }

        p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 200, 1));
        p.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 200, 1));
    }

    private static void tickWrath(MinecraftServer server) {
        if (WRATH_END.isEmpty()) return;
        Iterator<Map.Entry<UUID, Long>> it = WRATH_END.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> en = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(en.getKey());
            if (p == null || !p.isAlive()) {
                it.remove();
                continue;
            }
            ServerLevel l = Combat.lvl(p);
            Vec3 c = p.position();

            if (AscendantMod.TICK >= en.getValue()) {
                Fx.sound(l, c, SoundEvents.WITHER_BREAK_BLOCK, 2.0f, 0.8f);
                Fx.particle(l, ParticleTypes.EXPLOSION_EMITTER, c.add(0, 1.0, 0), 1, 0.0, 0.0);
                Fx.ring(l, ParticleTypes.SOUL_FIRE_FLAME, c.add(0, 0.3, 0), 6.0, 60, 0.1);
                it.remove();
                continue;
            }

            long t = AscendantMod.TICK;
            // Constant swirling aura.
            if (t % 2 == 0) {
                double ang = t * 0.35;
                for (int i = 0; i < 3; i++) {
                    double a = ang + i * (Math.PI * 2.0 / 3.0);
                    double h = (t % 20) / 10.0;
                    l.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, c.x + Math.cos(a) * 3.0, c.y + h, c.z + Math.sin(a) * 3.0,
                            1, 0.0, 0.0, 0.0, 0.0);
                    l.sendParticles(ParticleTypes.LARGE_SMOKE, c.x + Math.cos(a) * 6.5, c.y + 0.2, c.z + Math.sin(a) * 6.5,
                            1, 0.1, 0.1, 0.1, 0.0);
                }
            }
            if (t % 40 == 0) {
                Fx.sound(l, c, SoundEvents.WITHER_AMBIENT, 1.5f, 0.7f);
                Fx.sound(l, c, SoundEvents.WITHER_SHOOT, 1.0f, 0.8f);
            }
            // Pulse every half second.
            if (t % 10 == 0) {
                float healed = 0.0f;
                for (LivingEntity e : Combat.foesAround(p, c, 7.0)) {
                    Combat.hurt(l, e, l.damageSources().wither(), 3.0f, Combat.Kind.WITHER);
                    if (Ench.WITHERBANE.levelOn(e) < 3) e.addEffect(new MobEffectInstance(MobEffects.WITHER, 80, 1));
                    e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 0));
                    if (Ench.GLACIAL_STRIDE.levelOn(e) < 2) {
                        e.setRemainingFireTicks(Math.max(e.getRemainingFireTicks(), 40));
                    }
                    Vec3 target = e.position().add(0, e.getBbHeight() * 0.5, 0);
                    Fx.line(l, ParticleTypes.SOUL, c.add(0, 1.2, 0), target, 0.7);
                    Fx.sound(l, target, SoundEvents.WITHER_HURT, 0.6f, 1.2f);
                    healed += 1.5f * Combat.scale(e, Combat.Kind.WITHER) * Combat.drainScale(e);
                }
                if (healed > 0.0f) p.heal(Math.min(healed, 6.0f));
            }
        }
    }

    // =====================================================================================
    //  LEGGINGS ACTIVE: DRAGON'S DASH (Mythic)
    // =====================================================================================

    private static void dragonDash(ServerPlayer p, int lvl) {
        ServerLevel l = Combat.lvl(p);
        Vec3 c = p.position();
        DASHES.put(p.getUUID(), new Dash(8 + 4 * lvl, lvl));
        p.resetFallDistance();

        Fx.sound(l, c, SoundEvents.ENDER_DRAGON_GROWL, 2.0f, 1.2f);
        Fx.sound(l, c, SoundEvents.ENDER_DRAGON_FLAP, 2.0f, 0.9f);
        Fx.particle(l, ParticleTypes.PORTAL, c.add(0, 1.0, 0), 60, 0.6, 0.8);
        Fx.ring(l, ParticleTypes.END_ROD, c.add(0, 0.2, 0), 2.0, 30, 0.1);
    }

    private static void tickDashes(MinecraftServer server) {
        if (DASHES.isEmpty()) return;
        Iterator<Map.Entry<UUID, Dash>> it = DASHES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Dash> en = it.next();
            Dash d = en.getValue();
            ServerPlayer p = server.getPlayerList().getPlayer(en.getKey());
            if (p == null || !p.isAlive()) {
                it.remove();
                continue;
            }
            ServerLevel l = Combat.lvl(p);
            Vec3 look = p.getLookAngle();
            double speed = 1.4 + 0.3 * d.lvl;
            p.setDeltaMovement(look.scale(speed));
            p.hurtMarked = true;
            p.resetFallDistance();

            Vec3 body = p.position().add(0, 1.0, 0);
            Fx.particle(l, ParticleTypes.PORTAL, body, 14, 0.4, 0.7);
            Fx.particle(l, ParticleTypes.END_ROD, body, 4, 0.3, 0.05);
            Fx.particle(l, ParticleTypes.REVERSE_PORTAL, body, 6, 0.3, 0.05);
            if (d.left % 4 == 0) Fx.sound(l, body, SoundEvents.ENDER_DRAGON_FLAP, 1.2f, 1.0f + (d.left % 8) * 0.02f);

            for (LivingEntity e : Combat.foesAround(p, body, 2.6)) {
                if (!d.hit.add(e.getUUID())) continue;
                Combat.hurt(l, e, l.damageSources().playerAttack(p), 6.0f + 3.0f * d.lvl, Combat.Kind.SHOCK);
                Combat.knock(e, p.position(), 1.2, 0.35);
                Fx.particle(l, ParticleTypes.CRIT, e.position().add(0, e.getBbHeight() * 0.5, 0), 20, 0.4, 0.4);
                Fx.sound(l, e.position(), SoundEvents.PHANTOM_BITE, 1.0f, 0.7f);
            }

            d.left--;
            if (d.left <= 0) {
                p.setDeltaMovement(p.getDeltaMovement().scale(0.3));
                p.hurtMarked = true;
                Fx.sound(l, body, SoundEvents.ENDER_DRAGON_GROWL, 1.2f, 1.5f);
                it.remove();
            }
        }
    }

    // =====================================================================================
    //  BOOTS ACTIVE: SKYBOUND (Epic)
    // =====================================================================================

    private static boolean skybound(ServerPlayer p, int lvl) {
        ServerLevel l = Combat.lvl(p);
        Vec3 c = p.position();
        Vec3 v = p.getDeltaMovement();
        Vec3 look = p.getLookAngle();
        double fx = v.x * 0.5;
        double fz = v.z * 0.5;
        if (lvl >= 3) { // thrust forward along the horizontal look direction
            Vec3 flat = new Vec3(look.x, 0.0, look.z);
            if (flat.lengthSqr() > 1.0E-4) {
                flat = flat.normalize().scale(0.9);
                fx = flat.x;
                fz = flat.z;
            }
        }
        p.setDeltaMovement(fx, 1.0 + 0.2 * lvl, fz);
        p.hurtMarked = true;
        p.resetFallDistance();
        p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 100 + 20 * lvl, 0, false, true, true));

        Fx.sound(l, c, SoundEvents.FIREWORK_ROCKET_LAUNCH, 1.5f, 0.8f);
        Fx.sound(l, c, SoundEvents.ELYTRA_FLYING, 1.0f, 1.2f);
        Fx.particle(l, ParticleTypes.CLOUD, c.add(0, 0.2, 0), 30, 0.4, 0.15);
        Fx.ring(l, ParticleTypes.CLOUD, c.add(0, 0.15, 0), 2.0, 24, 0.2);

        if (lvl >= 2) {
            for (LivingEntity e : Combat.foesAround(p, c, 3.5 + lvl)) {
                Combat.hurt(l, e, l.damageSources().indirectMagic(p, p), 2.0f * lvl, Combat.Kind.SHOCK);
                Combat.knock(e, c, 1.0, 0.4);
            }
            Fx.sound(l, c, SoundEvents.ENDER_DRAGON_FLAP, 1.5f, 0.7f);
        }
        return true;
    }

    // =====================================================================================
    //  BOOTS PASSIVE EFFECT: EARTHSHATTER (Mythic)
    // =====================================================================================

    public static void earthshatter(ServerPlayer p, int lvl, float distance) {
        ServerLevel l = Combat.lvl(p);
        Vec3 c = p.position();
        if (distance < 5.0f) {
            Fx.sound(l, c, SoundEvents.IRON_GOLEM_DAMAGE, 0.8f, 0.7f);
            Fx.particle(l, ParticleTypes.CLOUD, c.add(0, 0.2, 0), 12, 0.4, 0.05);
            return;
        }
        double radius = 4.0 + 2.0 * lvl;
        float damage = Math.min(40.0f, distance * (1.0f + 0.5f * lvl));

        Fx.sound(l, c, SoundEvents.LIGHTNING_BOLT_IMPACT, 1.5f, 0.6f);
        Fx.sound(l, c, SoundEvents.ANVIL_LAND, 1.2f, 0.5f);
        Fx.sound(l, c, SoundEvents.WARDEN_ATTACK_IMPACT, 1.5f, 0.8f);
        Fx.particle(l, lvl >= 2 ? ParticleTypes.EXPLOSION_EMITTER : ParticleTypes.EXPLOSION, c.add(0, 0.3, 0), 3, 0.5, 0.0);
        Fx.particle(l, ParticleTypes.LARGE_SMOKE, c.add(0, 0.3, 0), 30, 0.8, 0.1);
        Fx.ring(l, ParticleTypes.CLOUD, c.add(0, 0.15, 0), radius * 0.5, 30, 0.15);
        Fx.ring(l, ParticleTypes.CLOUD, c.add(0, 0.15, 0), radius, 50, 0.25);
        Fx.ring(l, ParticleTypes.CRIT, c.add(0, 0.5, 0), radius * 0.75, 40, 0.3);

        for (LivingEntity e : Combat.foesAround(p, c, radius)) {
            Combat.hurt(l, e, l.damageSources().playerAttack(p), damage, Combat.Kind.SHOCK);
            Combat.knock(e, c, 1.2 + 0.3 * lvl, 0.65);
        }
    }

    // =====================================================================================
    //  CHESTPLATE PASSIVE EFFECT: BULWARK burst (Legendary)
    // =====================================================================================

    public static void bulwarkBurst(ServerPlayer p, int lvl) {
        ServerLevel l = Combat.lvl(p);
        Vec3 c = p.position();
        p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 100 + 40 * lvl, lvl >= 2 ? 2 : 1));
        p.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 200 + 100 * lvl, lvl));
        if (lvl >= 3) p.heal(6.0f);

        Fx.sound(l, c, SoundEvents.BEACON_ACTIVATE, 1.5f, 1.2f);
        Fx.sound(l, c, SoundEvents.ANVIL_LAND, 0.8f, 1.4f);
        Fx.particle(l, ParticleTypes.TOTEM_OF_UNDYING, c.add(0, 1.0, 0), 40, 0.5, 0.4);
        Fx.ring(l, ParticleTypes.END_ROD, c.add(0, 0.2, 0), 2.0, 30, 0.1);

        if (lvl >= 3) {
            for (LivingEntity e : Combat.foesAround(p, c, 5.0)) {
                Combat.knock(e, c, 1.6, 0.4);
            }
        }
    }

    // =====================================================================================
    //  TICK
    // =====================================================================================

    public static void tick(MinecraftServer server) {
        tickWrath(server);
        tickDashes(server);
    }
}
