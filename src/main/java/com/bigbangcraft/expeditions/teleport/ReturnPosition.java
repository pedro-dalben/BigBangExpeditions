package com.bigbangcraft.expeditions.teleport;

import java.util.Locale;
import java.util.Optional;

/**
 * Persistable return position for /expedition enter|leave.
 *
 * Serialized as a single string inside the player's PersistentData
 * (forge:PersistentData tag) so the codec is testable without bootstrapping
 * vanilla NBT classes. Format:
 *   dim|x|y|z|yaw|pitch
 * All numeric fields use plain doubles; dim is a namespace:path ResourceLocation.
 */
public final class ReturnPosition {
    public final String dimension;
    public final double x;
    public final double y;
    public final double z;
    public final float yaw;
    public final float pitch;

    public ReturnPosition(String dimension, double x, double y, double z, float yaw, float pitch) {
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public String serialize() {
        return dimension + "|" + x + "|" + y + "|" + z + "|" + yaw + "|" + pitch;
    }

    public static Optional<ReturnPosition> deserialize(String s) {
        if (s == null || s.isBlank()) return Optional.empty();
        String[] parts = s.split("\\|");
        if (parts.length != 6) return Optional.empty();
        try {
            String dim = parts[0];
            if (!dim.matches("[a-z0-9_.\\-]+:[a-z0-9/.\\-_]+")) return Optional.empty();
            // reject path traversal in dimension id
            if (dim.contains("..")) return Optional.empty();
            double x = Double.parseDouble(parts[1]);
            double y = Double.parseDouble(parts[2]);
            double z = Double.parseDouble(parts[3]);
            float yaw = Float.parseFloat(parts[4]);
            float pitch = Float.parseFloat(parts[5]);
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return Optional.empty();
            if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) return Optional.empty();
            return Optional.of(new ReturnPosition(dim, x, y, z, yaw, pitch));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public static String key() {
        return "bigbangexpeditions_return_position";
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%s [%.1f, %.1f, %.1f]", dimension, x, y, z);
    }
}
