package com.bigbangcraft.expeditions.i18n;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Goal 04: localization infrastructure guarantees.
 */
class TranslationsTest {

    @Test
    void defaultLocaleIsPortuguese() {
        assertEquals("Você não está dentro da zona de expedição.",
                Translations.t("bbe.where.not_inside"));
    }

    @Test
    void fallbackToEnglishWhenKeyMissingInRequestedLocale() {
        Translations tr = new Translations();
        // both bundles ship full key sets, so simulate a gap by asking for an
        // unknown locale that shares nothing
        assertEquals("Expedition temporarily unavailable.",
                tr.resolve("xx_xx", "bbe.entry.blocked.unavailable"));
    }

    @Test
    void missingKeyIsLoudNotSilent() {
        Translations tr = new Translations();
        assertEquals("!bbe.does_not_exist!", tr.resolve("pt_br", "bbe.does_not_exist"));
    }

    @Test
    void positionalArgsAreReplaced() {
        Translations tr = new Translations();
        String out = tr.resolve("en_us", "bbe.closing.warn.minutes", 15);
        assertTrue(out.contains("15 MINUTES"), out);
        assertFalse(out.contains("{0}"));
    }

    @Test
    void localesHaveIdenticalKeySets() {
        Translations tr = new Translations();
        Set<String> pt = new HashSet<>(tr.keysOf("pt_br"));
        Set<String> en = new HashSet<>(tr.keysOf("en_us"));
        Set<String> onlyPt = new HashSet<>(pt);
        onlyPt.removeAll(en);
        Set<String> onlyEn = new HashSet<>(en);
        onlyEn.removeAll(pt);
        assertTrue(onlyPt.isEmpty(), "keys missing in en_us: " + onlyPt);
        assertTrue(onlyEn.isEmpty(), "keys missing in pt_br: " + onlyEn);
        assertFalse(pt.isEmpty(), "bundles must not be empty");
    }

    @Test
    void everyStateHasPlayerFacingWording() {
        Translations tr = new Translations();
        for (String s : new String[]{"open", "closing", "evacuating", "locked", "preflight",
                "backup", "reset_ready", "resetting", "booting", "validating",
                "failed", "recovery_required"}) {
            assertTrue(tr.has("pt_br", "bbe.state." + s), "missing state wording: " + s);
        }
    }

    @Test
    void technicalStatesNeverLeakRawEnumNamesToEntryDenials() {
        // the mapping for maintenance states must be player-friendly wording,
        // not enum identifiers like VALIDATING / RECOVERY_REQUIRED
        Translations tr = new Translations();
        for (String s : new String[]{"validating", "booting", "resetting", "recovery_required"}) {
            String wording = tr.resolve("pt_br", "bbe.state." + s);
            assertFalse(wording.matches("[A-Z_]+"), "raw enum leaked for " + s + ": " + wording);
        }
    }

    @Test
    void formatWithoutArgsReturnsTemplate() {
        assertEquals("plain", Translations.format("plain"));
    }

    @Test
    void localeNamesNormalize() {
        assertEquals("en_us", Translations.normalize("EN-US"));
        assertEquals("pt_br", Translations.normalize("pt-BR"));
        assertEquals("en_us", Translations.normalize(null));
        assertEquals("en_us", Translations.normalize(" "));
    }
}
