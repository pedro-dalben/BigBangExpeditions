package com.bigbangcraft.expeditions.diagnostics;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured doctor result.
 */
public final class DoctorReport {
    public String minecraftVersion;
    public String forgeVersion;
    public String modVersion;
    public String dimension;
    public boolean lostCitiesPresent;
    public String lostCitiesVersion;
    public String lostCitiesProfile; // e.g. deceasedcraft_onlycities
    public boolean opacPresent;
    public String opacVersion;
    public boolean lootrPresent;
    public String lootrVersion;
    public String lootrEnabled; // enabled/disabled/unknown
    public boolean ftbTeamsPresent;
    public boolean hordesPresent;
    public String hordesVersion;
    public boolean createPresent;
    public boolean iePresent;
    public boolean rsPresent;
    public boolean securityCraftPresent;
    public String worldSeedStatus;
    public List<String> warnings = new ArrayList<>();

    public void warn(String w) { warnings.add(w); }
}
