package com.bigbangcraft.expeditions.audit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only JSONL audit log with size-based rotation.
 *
 * Every accepted AND refused lifecycle/authorization operation is recorded:
 * refusals are exactly the evidence an incident review needs.
 */
public final class AuditLog {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Path file;
    private final long maxBytesPerFile;
    private final int maxRotatedFiles;
    private final Object lock = new Object();

    public AuditLog(Path file, long maxBytesPerFile, int maxRotatedFiles) {
        this.file = file;
        this.maxBytesPerFile = Math.max(4096, maxBytesPerFile);
        this.maxRotatedFiles = Math.max(1, maxRotatedFiles);
    }

    public Path file() {
        return file;
    }

    public void append(AuditEvent e) throws IOException {
        String line = GSON.toJson(e);
        synchronized (lock) {
            Files.createDirectories(file.getParent());
            rotateIfNeeded();
            Files.writeString(file, line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    /** Appends, converting IO failure into an unchecked one — audit loss must be loud. */
    public void record(AuditEvent e) {
        try {
            append(e);
        } catch (IOException io) {
            throw new IllegalStateException("audit write failed — refusing to continue silently", io);
        }
    }

    private void rotateIfNeeded() throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) < maxBytesPerFile) return;
        // audit-1 = most recently rotated ... audit-N = oldest.
        // Drop entries that would exceed the cap once everything shifts +1,
        // then shift audit-(n) -> audit-(n+1) and current -> audit-1.
        List<Path> rotated = existingRotated();
        for (Path p : rotated) {
            if (rotationIndex(p) + 1 > maxRotatedFiles) {
                Files.deleteIfExists(p);
            }
        }
        for (int n = maxRotatedFiles - 1; n >= 1; n--) {
            Path src = sibling("audit-" + n + ".jsonl");
            if (Files.isRegularFile(src)) {
                Files.move(src, sibling("audit-" + (n + 1) + ".jsonl"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.move(file, sibling("audit-1.jsonl"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private List<Path> existingRotated() throws IOException {
        List<Path> out = new ArrayList<>();
        try (var stream = Files.list(file.getParent())) {
            stream.filter(p -> p.getFileName().toString().matches("audit-\\d+\\.jsonl"))
                    .sorted().forEach(out::add);
        }
        return out;
    }

    private int rotationIndex(Path p) {
        String name = p.getFileName().toString();
        return Integer.parseInt(name.replace("audit-", "").replace(".jsonl", ""));
    }

    private Path sibling(String name) {
        return file.resolveSibling(name);
    }

    /** Reads back all events (current + rotated), oldest first. Test/diagnostic use. */
    public List<AuditEvent> readAll() throws IOException {
        List<AuditEvent> out = new ArrayList<>();
        List<Path> files = new ArrayList<>(existingRotated());
        files.add(file);
        for (Path p : files) {
            if (!Files.isRegularFile(p)) continue;
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                out.add(GSON.fromJson(line, AuditEvent.class));
            }
        }
        return out;
    }
}
