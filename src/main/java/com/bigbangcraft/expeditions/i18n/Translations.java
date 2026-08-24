package com.bigbangcraft.expeditions.i18n;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side translation core (Goal 04).
 *
 * Decision: text is resolved SERVER-side from lang JSONs embedded in this mod
 * and emitted as literal components. This keeps output deterministic for every
 * client regardless of whether the client installed the mod, and keeps the
 * whole pipeline unit-testable without bootstrapping Minecraft registries.
 * The active locale is a server-level setting (default pt_br — BigBangCraft
 * player base); en_us is always loaded as fallback.
 *
 * Missing-key behavior is loud: the raw key wrapped in '!…!' is returned so
 * gaps surface in gameplay instead of hiding. Parity between locales is
 * enforced by tests, not by runtime failure.
 */
public final class Translations {
    public static final String DEFAULT_LOCALE = "pt_br";
    public static final String FALLBACK_LOCALE = "en_us";

    private static final Gson GSON = new Gson();
    private static volatile Translations instance;

    private final Map<String, Map<String, String>> bundles = new ConcurrentHashMap<>();

    public Translations() {
        bundles.put(FALLBACK_LOCALE, loadBundle(FALLBACK_LOCALE));
        bundles.put(DEFAULT_LOCALE, loadBundle(DEFAULT_LOCALE));
    }

    public static Translations get() {
        if (instance == null) {
            synchronized (Translations.class) {
                if (instance == null) instance = new Translations();
            }
        }
        return instance;
    }

    /** Test hook. */
    public static void reset() {
        instance = null;
    }

    /** Resolve with the configured default locale. */
    public static String t(String key, Object... args) {
        return get().resolve(DEFAULT_LOCALE, key, args);
    }

    /**
     * Resolution order: locale -> en_us -> '!key!'. Positional args replace
     * {0}, {1}, ... Arguments are plain strings/numbers supplied by callers.
     */
    public String resolve(String locale, String key, Object... args) {
        String value = lookup(locale, key);
        if (value == null && !FALLBACK_LOCALE.equals(locale)) {
            value = lookup(FALLBACK_LOCALE, key);
        }
        if (value == null) return "!" + key + "!";
        return format(value, args);
    }

    private String lookup(String locale, String key) {
        Map<String, String> bundle = bundles.get(normalize(locale));
        return bundle == null ? null : bundle.get(key);
    }

    public boolean has(String locale, String key) {
        return lookup(locale, key) != null || lookup(FALLBACK_LOCALE, key) != null;
    }

    /** Keys present in the given locale bundle itself (no fallback). */
    public Set<String> keysOf(String locale) {
        Map<String, String> bundle = bundles.get(normalize(locale));
        return bundle == null ? Collections.emptySet() : bundle.keySet();
    }

    static String format(String template, Object... args) {
        if (args == null || args.length == 0) return template;
        String out = template;
        for (int i = 0; i < args.length; i++) {
            out = out.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return out;
    }

    static String normalize(String locale) {
        if (locale == null || locale.isBlank()) return FALLBACK_LOCALE;
        return locale.toLowerCase().replace('-', '_');
    }

    private Map<String, String> loadBundle(String locale) {
        String path = "/assets/bigbangexpeditions/lang/" + normalize(locale) + ".json";
        try (InputStream in = Translations.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("lang resource missing: " + path);
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> map = GSON.fromJson(json, new TypeToken<Map<String, String>>() {}.getType());
            if (map == null) throw new IOException("lang resource empty: " + path);
            map.values().removeIf(v -> v == null || v.isBlank());
            return map;
        } catch (IOException | RuntimeException e) {
            // A missing en_us bundle would leave every message as raw keys —
            // that must never happen silently in production.
            throw new IllegalStateException("translation bundle unavailable: " + path, e);
        }
    }
}
