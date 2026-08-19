package com.crystalgui.language.engine;

/**
 * Which engine versions this host can actually run — one read of {@code java.specification.version}.
 *
 * <h3>The ceiling argument, which makes banding correct rather than a compromise</h3>
 *
 * <p>A Java 8 JVM cannot load a class file newer than major 52. So on a Java 8 host, scripts are
 * Java-8-language scripts <b>no matter what compiler we ship</b> — a compiler that accepted records
 * would emit bytecode the host refuses. The same holds at 11 and at 17. So the oldest band's engines
 * are not a degraded experience: they are exactly the ceiling the host already imposes, and each band
 * is simultaneously "the newest engine that runs here" and "the newest language this host can execute".</p>
 *
 * <h3>The pins are measured, and two of them contradict what the release notes imply</h3>
 *
 * <p>Every version below is the newest whose <em>base</em> class files are within the band's ceiling,
 * found by reading the class-file major out of the published jar rather than by trusting a
 * compatibility statement. Two results were better than the plan assumed and one was worse:</p>
 *
 * <ul>
 *   <li><b>JDT 3.26.0 runs on Java 8</b>, not the 3.22.0 the plan expected — five more releases of
 *       fixes on the oldest band, and it accepts a far newer source level than the Java 14 that was
 *       budgeted for (not that a Java 8 host can execute the result; see the ceiling argument).</li>
 *   <li><b>JDT 3.33.0 runs on Java 11.</b> The plan had "≥ 4.28 requires Java 17"; 4.28 <em>is</em>
 *       3.33.0 and it is fine. 3.34.0 is where 17 becomes mandatory.</li>
 *   <li><b>Rhino 1.9.1 needs only Java 11</b>, so bands 11 and 17 share it. Three bands, two Rhinos.</li>
 * </ul>
 *
 * <h3>What this enum does not decide</h3>
 *
 * <p>It names versions; it does not find jars. Where a band's jars come from — bundled in the mod jar,
 * a {@code libraries/} directory, a dev-time Gradle configuration — is {@link EngineSource}'s question,
 * because the answer differs per platform and none of them changes which version is correct.</p>
 */
public enum EngineBand {

    /** Java 8, 9, 10. */
    JAVA_8(8, "3.26.0", "1.7.15.1"),

    /** Java 11 through 16. */
    JAVA_11(11, "3.33.0", "1.9.1"),

    /** Java 17 and everything after — the band that takes whatever is newest. */
    JAVA_17(17, "3.46.0", "1.9.1");

    private final int minimumFeatureVersion;
    private final String jdtVersion;
    private final String rhinoVersion;

    EngineBand(int minimumFeatureVersion, String jdtVersion, String rhinoVersion) {
        this.minimumFeatureVersion = minimumFeatureVersion;
        this.jdtVersion = jdtVersion;
        this.rhinoVersion = rhinoVersion;
    }

    /** The lowest JVM feature version in this band. */
    public int minimumFeatureVersion() {
        return minimumFeatureVersion;
    }

    /** `org.eclipse.jdt:org.eclipse.jdt.core` — the DOM and bindings, not the batch compiler alone. */
    public String jdtVersion() {
        return jdtVersion;
    }

    /** `org.mozilla:rhino`. */
    public String rhinoVersion() {
        return rhinoVersion;
    }

    /** The class-file major this band's JVM can load: 52, 55, 61. */
    public int classFileCeiling() {
        return minimumFeatureVersion + 44;
    }

    /**
     * The band for the JVM this is running on.
     *
     * <p>One property read, at startup, and deliberately not cached in a static: a test needs to ask
     * about a version it is not running on, and a static would make that impossible to express without
     * a reset hook nobody remembers to call.</p>
     */
    public static EngineBand detect() {
        return forFeatureVersion(hostFeatureVersion());
    }

    /** The band a host of this feature version belongs to. */
    public static EngineBand forFeatureVersion(int featureVersion) {
        EngineBand best = JAVA_8;
        for (EngineBand band : values()) {
            if (featureVersion >= band.minimumFeatureVersion) best = band;
        }
        return best;
    }

    /**
     * This JVM's feature version, from {@code java.specification.version}.
     *
     * <p><b>Two formats, and the old one is the trap.</b> Java 8 reports {@code "1.8"} and everything
     * from 9 onward reports {@code "11"}, {@code "17"}, {@code "25"}. Parsing the first number of
     * {@code "1.8"} gives <b>1</b>, which is below every band's minimum — so a naive parse selects the
     * oldest band by accident and looks correct, because the oldest band is also what Java 8 should get.
     * It stops looking correct on Java 9, which reports {@code "9"} and would then be read as 9. The
     * legacy prefix has to be handled deliberately rather than fallen into.</p>
     *
     * <p>An unreadable or absent property answers 8: the conservative direction, since the oldest band
     * runs everywhere and a newer one does not.</p>
     */
    public static int hostFeatureVersion() {
        return parseFeatureVersion(System.getProperty("java.specification.version"));
    }

    /** Visible for testing — the parse, without reading a system property. */
    static int parseFeatureVersion(String specificationVersion) {
        if (specificationVersion == null) return 8;
        String text = specificationVersion.trim();
        if (text.isEmpty()) return 8;
        if (text.startsWith("1.")) text = text.substring(2);

        int end = 0;
        while (end < text.length() && Character.isDigit(text.charAt(end))) end++;
        if (end == 0) return 8;
        try {
            int parsed = Integer.parseInt(text.substring(0, end));
            // 1.0 through 1.7 are older than anything here can run on, and 0 would select nothing.
            return Math.max(parsed, 8);
        } catch (NumberFormatException unreadable) {
            return 8;
        }
    }
}
