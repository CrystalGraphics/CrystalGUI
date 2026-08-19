package com.crystalgui.language.engine;

import org.junit.Assume;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The build and the runtime agree about which engine versions this is.
 *
 * <h3>Two copies of a version number, and neither can see the other</h3>
 *
 * <p>{@code build.gradle.kts} resolves the jars; {@link EngineBand} names the versions the running code
 * expects. They are the same three facts written twice, in two languages, and nothing connects them —
 * so bumping one leaves the build downloading 3.46.0 while the runtime asks for 3.45.0. That failure
 * does not appear on the machine that made it, because a dev run resolves whatever the build says and
 * never consults the enum. It appears wherever the enum is used to <em>find</em> a jar: a deployment
 * laid out by band, which is the one place this cannot be tested by running it.</p>
 *
 * <p>So the build hands its pins over and this asserts they match. Cheap, and it is the only thing
 * standing between the two copies.</p>
 */
public class PinnedEngineVersionsTest {

    /** {@code 8.jdt=3.26.0,8.rhino=1.7.15.1,...} — what the build resolved, as the build spelled it. */
    private static Map<String, String> buildPins() {
        String property = System.getProperty("cgui.test.pins");
        Assume.assumeTrue("the build did not supply its pins; skipping",
                property != null && !property.trim().isEmpty());
        Map<String, String> pins = new HashMap<>();
        for (String entry : property.split(",")) {
            int equals = entry.indexOf('=');
            if (equals > 0) pins.put(entry.substring(0, equals).trim(), entry.substring(equals + 1).trim());
        }
        return pins;
    }

    @Test
    public void everyBandsPinsMatchWhatTheBuildResolves() {
        Map<String, String> pins = buildPins();
        for (EngineBand band : EngineBand.values()) {
            String prefix = band.minimumFeatureVersion() + ".";
            assertEquals(band + " JDT pin disagrees with the build",
                    pins.get(prefix + "jdt"), band.jdtVersion());
            assertEquals(band + " Rhino pin disagrees with the build",
                    pins.get(prefix + "rhino"), band.rhinoVersion());
        }
    }

    @Test
    public void theBuildDeclaresAPinForEveryBandTheRuntimeKnowsAbout() {
        // The other direction, and the one a new band would trip: adding EngineBand.JAVA_25 without
        // adding its configuration leaves the enum naming versions nothing downloads.
        Map<String, String> pins = buildPins();
        for (EngineBand band : EngineBand.values()) {
            String prefix = band.minimumFeatureVersion() + ".";
            assertTrue("the build has no JDT pin for " + band, pins.containsKey(prefix + "jdt"));
            assertTrue("the build has no Rhino pin for " + band, pins.containsKey(prefix + "rhino"));
        }
    }
}
