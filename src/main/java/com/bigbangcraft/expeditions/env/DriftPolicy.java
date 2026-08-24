package com.bigbangcraft.expeditions.env;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Classifies differences between the qualification-era fingerprint and the
 * current one. Every component maps to exactly one verdict; the report's
 * overall verdict is the WORST entry.
 *
 * Tiers:
 *   ALLOW               — no meaningful change
 *   WARN                — changed, but does not by itself invalidate evidence
 *   REQUIRE_REVALIDATION— prior qualification evidence no longer trusted
 *   REFUSE              — installation identity changed; plans must not execute
 */
public final class DriftPolicy {

    public enum Verdict {
        ALLOW, WARN, REQUIRE_REVALIDATION, REFUSE;

        private static int rank(Verdict v) {
            return switch (v) {
                case ALLOW -> 0;
                case WARN -> 1;
                case REQUIRE_REVALIDATION -> 2;
                case REFUSE -> 3;
            };
        }

        public Verdict worst(Verdict other) {
            return rank(this) >= rank(other) ? this : other;
        }
    }

    public static final class Entry {
        public final String component;
        public final Verdict verdict;
        public final String detail;

        public Entry(String component, Verdict verdict, String detail) {
            this.component = component;
            this.verdict = verdict;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return component + "=" + verdict + (detail.isEmpty() ? "" : " (" + detail + ")");
        }
    }

    public static final class Report {
        public final List<Entry> entries = new ArrayList<>();
        public Verdict overall = Verdict.ALLOW;

        void add(Entry e) {
            entries.add(e);
            overall = overall.worst(e.verdict);
        }

        public boolean executionBlocked() {
            return overall == Verdict.REFUSE || overall == Verdict.REQUIRE_REVALIDATION;
        }
    }

    /** Mods whose version change forces revalidation (worldgen-critical). */
    private static final Map<String, Verdict> MOD_VERDICTS = Map.of(
            "lostcities", Verdict.REQUIRE_REVALIDATION,
            "openpartiesandclaims", Verdict.WARN,
            "lootr", Verdict.WARN,
            "hordes", Verdict.WARN);

    private DriftPolicy() {}

    public static Report evaluate(InstallFingerprint baseline, InstallFingerprint current) {
        Report r = new Report();

        r.add(diff("minecraftVersion", baseline.minecraftVersion, current.minecraftVersion, Verdict.REFUSE));
        r.add(diff("forgeVersion", baseline.forgeVersion, current.forgeVersion, Verdict.REFUSE));
        r.add(diff("dimensionId", baseline.dimensionId, current.dimensionId, Verdict.REFUSE));
        r.add(diff("worldSeedHash", baseline.worldSeedHash, current.worldSeedHash, Verdict.REFUSE));
        r.add(diff("bbeVersion", baseline.bbeVersion, current.bbeVersion, Verdict.REQUIRE_REVALIDATION));
        r.add(diff("lostCitiesProfile", baseline.lostCitiesProfile, current.lostCitiesProfile, Verdict.REQUIRE_REVALIDATION));
        r.add(diff("lostCitiesProfileSha256", baseline.lostCitiesProfileSha256, current.lostCitiesProfileSha256,
                Verdict.REQUIRE_REVALIDATION));

        evalModMap(r, baseline.modVersions, current.modVersions);
        evalConfigMap(r, baseline.configSha256, current.configSha256);

        return r;
    }

    private static Entry diff(String component, String base, String cur, Verdict onDiff) {
        if (base == null || cur == null || base.isBlank() || cur.isBlank()) {
            return new Entry(component, Verdict.REFUSE, "missing value (baseline=" + base + ", current=" + cur + ")");
        }
        if (!base.equals(cur)) {
            return new Entry(component, onDiff, base + " -> " + cur);
        }
        return new Entry(component, Verdict.ALLOW, "");
    }

    private static void evalModMap(Report r, Map<String, String> base, Map<String, String> cur) {
        TreeMap<String, String> b = base == null ? new TreeMap<>() : new TreeMap<>(base);
        TreeMap<String, String> c = cur == null ? new TreeMap<>() : new TreeMap<>(cur);
        for (Map.Entry<String, String> e : b.entrySet()) {
            String now = c.get(e.getKey());
            if (now == null || now.isBlank()) {
                r.add(new Entry("mod:" + e.getKey(), Verdict.REFUSE,
                        "tracked mod missing from current install (was " + e.getValue() + ")"));
            } else if (!now.equals(e.getValue())) {
                Verdict v = MOD_VERDICTS.getOrDefault(e.getKey(), Verdict.WARN);
                r.add(new Entry("mod:" + e.getKey(), v, e.getValue() + " -> " + now));
            } else {
                r.add(new Entry("mod:" + e.getKey(), Verdict.ALLOW, ""));
            }
        }
        for (String id : c.keySet()) {
            if (!b.containsKey(id)) {
                r.add(new Entry("mod:" + id, Verdict.WARN, "new tracked mod: " + c.get(id)));
            }
        }
    }

    private static void evalConfigMap(Report r, Map<String, String> base, Map<String, String> cur) {
        TreeMap<String, String> b = base == null ? new TreeMap<>() : new TreeMap<>(base);
        TreeMap<String, String> c = cur == null ? new TreeMap<>() : new TreeMap<>(cur);
        for (Map.Entry<String, String> e : b.entrySet()) {
            String now = c.get(e.getKey());
            if (now == null) {
                r.add(new Entry("config:" + e.getKey(), Verdict.REFUSE, "tracked config missing"));
            } else if (!now.equals(e.getValue())) {
                r.add(new Entry("config:" + e.getKey(), Verdict.REQUIRE_REVALIDATION, "content changed"));
            } else {
                r.add(new Entry("config:" + e.getKey(), Verdict.ALLOW, ""));
            }
        }
        for (String k : c.keySet()) {
            if (!b.containsKey(k)) {
                r.add(new Entry("config:" + k, Verdict.WARN, "new tracked config"));
            }
        }
    }
}
