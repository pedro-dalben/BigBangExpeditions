package com.bigbangcraft.expeditions.env;

import java.nio.file.Files;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * File-backed loader for {@code config/bigbangexpeditions/environment.properties}.
 * Missing file is normal (defaults apply). Unreadable file is a hard error:
 * operators must see the failure instead of silently running defaults.
 */
public final class EnvironmentProperties {
    private EnvironmentProperties() {}

    public static Path defaultFile(Path configDir) {
        return configDir.resolve("environment.properties");
    }

    public static Path ackFile(Path configDir) {
        return configDir.resolve("production.enabled");
    }

    /** Loads properties; missing file yields empty map. */
    public static Map<String, String> load(Path file) throws IOException {
        Map<String, String> out = new HashMap<>();
        if (!Files.isRegularFile(file)) return out;
        Properties p = new Properties();
        try (var in = Files.newInputStream(file)) {
            p.load(in);
        }
        for (String name : p.stringPropertyNames()) {
            out.put(name, p.getProperty(name));
        }
        return out;
    }

    /** Acknowledgment content; null when absent. */
    public static String loadAck(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return null;
        return Files.readString(file);
    }
}
