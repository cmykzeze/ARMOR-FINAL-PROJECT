package com.ascendant;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/** Particle + sound helpers for dramatic fights. */
public final class Fx {
    private Fx() {}

    public static void sound(ServerLevel level, Vec3 pos, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, pos.x, pos.y, pos.z, sound, SoundSource.PLAYERS, volume, pitch);
    }

    public static void particle(ServerLevel level, ParticleOptions type, Vec3 pos, int count, double spread, double speed) {
        level.sendParticles(type, pos.x, pos.y, pos.z, count, spread, spread, spread, speed);
    }

    /** Flat ring of particles around a centre. */
    public static void ring(ServerLevel level, ParticleOptions type, Vec3 center, double radius, int points, double speed) {
        for (int i = 0; i < points; i++) {
            double a = (Math.PI * 2.0 * i) / points;
            level.sendParticles(type, center.x + Math.cos(a) * radius, center.y, center.z + Math.sin(a) * radius,
                    1, 0.0, 0.05, 0.0, speed);
        }
    }

    /** Line of particles between two points. */
    public static void line(ServerLevel level, ParticleOptions type, Vec3 from, Vec3 to, double step) {
        Vec3 delta = to.subtract(from);
        double len = delta.length();
        if (len < 0.01) return;
        Vec3 dir = delta.scale(1.0 / len);
        for (double d = 0; d < len; d += step) {
            Vec3 p = from.add(dir.scale(d));
            level.sendParticles(type, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }
}
