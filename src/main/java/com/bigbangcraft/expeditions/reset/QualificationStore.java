package com.bigbangcraft.expeditions.reset;

import com.bigbangcraft.expeditions.env.InstallFingerprint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persists the qualification-era {@link InstallFingerprint} used as the drift
 * baseline, and the CURRENT fingerprint export consumed by the offline CLI.
 * Stored under config/bigbangexpeditions/ so it is visible in backups of the
 * configuration, not the world.
 */
public final class QualificationStore {

    public static final String FILE_QUALIFICATION = "qualification-fingerprint.json";
    public static final String FILE_CURRENT = "current-fingerprint.json";

    private QualificationStore() {}

    public static InstallFingerprint loadQualification(Path configDir) throws IOException {
        Path f = configDir.resolve(FILE_QUALIFICATION);
        if (!Files.isRegularFile(f)) return null;
        return InstallFingerprint.fromJson(Files.readString(f));
    }

    public static void saveQualification(Path configDir, InstallFingerprint f) throws IOException {
        save(configDir, FILE_QUALIFICATION, f);
    }

    public static void exportCurrent(Path configDir, InstallFingerprint f) throws IOException {
        // include sha256 at top level for shell consumption by scripts
        Path p = configDir.resolve(FILE_CURRENT);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "{\n  \"fingerprint\": " + f.toJson()
                + ",\n  \"sha256\": \"" + f.sha256() + "\"\n}");
    }

    public static InstallFingerprint loadCurrentExported(Path configDir) throws IOException {
        Path f = configDir.resolve(FILE_CURRENT);
        if (!Files.isRegularFile(f)) return null;
        String json = Files.readString(f);
        // extract embedded fingerprint object
        int i = json.indexOf("\"fingerprint\": ") + "\"fingerprint\": ".length();
        int j = json.lastIndexOf(",\"sha256\"");
        return InstallFingerprint.fromJson(json.substring(i, j).trim());
    }

    private static void save(Path configDir, String name, InstallFingerprint f) throws IOException {
        Path p = configDir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, f.toJson());
    }
}
