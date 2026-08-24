package com.bigbangcraft.expeditions.integration.lostcities;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Isolated adapter for Lost Cities (mcjty). Reflective to avoid hard compile
 * dependency — pack may not have Lost Cities on dev env.
 *
 * Fail-closed: any reflection failure yields "unavailable" / empty results;
 * callers must treat unknown profile state as unsafe for reset operations.
 */
public final class LostCitiesAdapter {
    public static final String MODID = "lostcities";
    /** Expected profile for the expedition dimension; verified at runtime, not assumed safe. */
    public static final String EXPECTED_EXPEDITION_PROFILE = "deceasedcraft_onlycities";

    private static final String CONFIG_CLASS = "mcjty.lostcities.setup.Config";
    private static volatile boolean configLookupFailed;
    private static Method getProfileForDimensionMethod;

    private LostCitiesAdapter() {}

    public static ResourceLocation expeditionDimensionId() {
        return new ResourceLocation("bigbangexpeditions", "expedition");
    }

    /**
     * Runtime-only: builds the dimension ResourceKey. Must not be called from
     * plain-JUnit contexts — vanilla registry classes are unbootstrapped there.
     */
    public static ResourceKey<Level> expeditionDimensionKey() {
        return ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, expeditionDimensionId());
    }

    /** True if the Lost Cities mod classes are loadable. Never throws. */
    public static boolean isAvailable() {
        try {
            Class.forName(CONFIG_CLASS);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Resolves the LC-configured profile via
     * mcjty.lostcities.setup.Config#getProfileForDimension(ResourceKey).
     * Empty = unavailable OR not configured OR lookup failed (fail-closed).
     */
    public static Optional<String> getProfile(ServerLevel level) {
        if (level == null || !isAvailable()) return Optional.empty();
        try {
            Method m = resolveGetMethod();
            // level.dimension() is constructed by live MC; safe to pass reflectively
            Object result = m.invoke(null, level.dimension());
            if (result instanceof String s && !s.isBlank()) {
                return Optional.of(s);
            }
            return Optional.empty();
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /**
     * String-based variant usable wherever only the dimension id is known.
     * Builds a ResourceKey lazily; fails closed if MC classes are unavailable.
     */
    public static Optional<String> getProfileById(ResourceLocation dimensionId) {
        if (!isAvailable()) return Optional.empty();
        try {
            Method m = resolveGetMethod();
            ResourceKey<Level> key = ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION, dimensionId);
            Object result = m.invoke(null, key);
            if (result instanceof String s && !s.isBlank()) {
                return Optional.of(s);
            }
            return Optional.empty();
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /** A world counts as an LC world only when a non-blank profile resolves. */
    public static boolean isLostCitiesWorld(ServerLevel level) {
        return getProfile(level).isPresent();
    }

    /**
     * Fingerprint of the resolved profile definition file
     * (config/lostcities/profiles/&lt;name&gt;.json), sha-256 hex.
     * Empty when the profile file cannot be read — never a guessed value.
     */
    public static Optional<String> getProfileFingerprint(Path lostCitiesConfigDir, String profileName) {
        if (profileName == null || profileName.isBlank() || lostCitiesConfigDir == null) return Optional.empty();
        // sanitize: profile name must be simple path segment
        if (!profileName.matches("[A-Za-z0-9_\\-.]+")) return Optional.empty();
        Path f = lostCitiesConfigDir.resolve("profiles").resolve(profileName + ".json");
        if (!Files.isRegularFile(f)) return Optional.empty();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(Files.readAllBytes(f));
            return Optional.of(HexFormat.of().formatHex(hash));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Validates that the dimension's resolved profile matches expectation.
     * Returns empty list on success, else human-readable failure reasons (fail-closed).
     */
    public static java.util.List<String> validateExpectedProfile(ServerLevel level, String expectedProfile) {
        java.util.List<String> failures = new java.util.ArrayList<>();
        if (!isAvailable()) {
            failures.add("Lost Cities not available");
            return failures;
        }
        if (level == null) {
            failures.add("level unavailable — profile cannot be resolved (fail-closed)");
            return failures;
        }
        Optional<String> actual = getProfile(level);
        if (actual.isEmpty()) {
            failures.add("no Lost Cities profile configured for " + level.dimension().location());
        } else if (!actual.get().equals(expectedProfile)) {
            failures.add("profile mismatch: expected '" + expectedProfile + "' got '" + actual.get() + "'");
        }
        return failures;
    }

    private static Method resolveGetMethod() throws Exception {
        if (configLookupFailed) throw new IllegalStateException("LC Config lookup previously failed");
        if (getProfileForDimensionMethod == null) {
            synchronized (LostCitiesAdapter.class) {
                if (getProfileForDimensionMethod == null) {
                    Class<?> c = Class.forName(CONFIG_CLASS);
                    Method found = null;
                    for (Method m : c.getMethods()) {
                        if (m.getName().equals("getProfileForDimension") && m.getParameterCount() == 1
                                && m.getParameterTypes()[0] == ResourceKey.class) {
                            found = m;
                            break;
                        }
                    }
                    if (found == null) {
                        configLookupFailed = true;
                        throw new IllegalStateException("getProfileForDimension(ResourceKey) not found");
                    }
                    found.setAccessible(true);
                    getProfileForDimensionMethod = found;
                }
            }
        }
        return getProfileForDimensionMethod;
    }
}
