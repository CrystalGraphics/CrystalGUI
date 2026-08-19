package com.crystalgui.language.engine;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Band selection — one property read, and every way it can be spelled.
 *
 * <p>No jars and no network: this is the half of {@code plan_syntax.md} §6 that is pure arithmetic, and
 * it is worth separating from the half that needs 44MB of engines because it is the half that runs on
 * every machine.</p>
 */
public class EngineBandTest {

    @Test
    public void theLegacyOneDotEightSpellingIsHandledDeliberately() {
        // THE TRAP. Java 8 reports "1.8" and everything from 9 on reports a bare number. Reading the
        // first integer of "1.8" gives 1 -- which is below every band's minimum, so forFeatureVersion
        // still answers JAVA_8 and the whole thing LOOKS correct. It stops looking correct on Java 9,
        // where "9" parses as 9 and the two spellings have silently been read on different scales.
        assertEquals(8, EngineBand.parseFeatureVersion("1.8"));
        assertEquals(8, EngineBand.parseFeatureVersion("1.7"));
        assertEquals(9, EngineBand.parseFeatureVersion("9"));
    }

    @Test
    public void modernSpellingsParse() {
        assertEquals(11, EngineBand.parseFeatureVersion("11"));
        assertEquals(17, EngineBand.parseFeatureVersion("17"));
        assertEquals(21, EngineBand.parseFeatureVersion("21"));
        assertEquals(25, EngineBand.parseFeatureVersion("25"));
        // An early-access or vendor suffix must not defeat the parse.
        assertEquals(26, EngineBand.parseFeatureVersion("26-ea"));
        assertEquals(17, EngineBand.parseFeatureVersion(" 17 "));
    }

    @Test
    public void anUnreadableVersionFallsBackToTheOldestBand() {
        // The conservative direction, and the choice is not obvious: guessing NEWEST would give a better
        // experience when the guess is right and an unloadable engine when it is wrong. The oldest band
        // runs everywhere.
        assertEquals(8, EngineBand.parseFeatureVersion(null));
        assertEquals(8, EngineBand.parseFeatureVersion(""));
        assertEquals(8, EngineBand.parseFeatureVersion("nonsense"));
        assertEquals(8, EngineBand.parseFeatureVersion("0"));
    }

    @Test
    public void eachHostVersionSelectsTheNewestBandItCanRun() {
        assertSame(EngineBand.JAVA_8, EngineBand.forFeatureVersion(8));
        assertSame(EngineBand.JAVA_8, EngineBand.forFeatureVersion(9));
        assertSame(EngineBand.JAVA_8, EngineBand.forFeatureVersion(10));
        assertSame(EngineBand.JAVA_11, EngineBand.forFeatureVersion(11));
        assertSame(EngineBand.JAVA_11, EngineBand.forFeatureVersion(16));
        assertSame(EngineBand.JAVA_17, EngineBand.forFeatureVersion(17));
        assertSame(EngineBand.JAVA_17, EngineBand.forFeatureVersion(21));
        // A version newer than any band we know about takes the newest band rather than none.
        assertSame(EngineBand.JAVA_17, EngineBand.forFeatureVersion(99));
    }

    @Test
    public void aVersionBelowEveryBandStillSelectsOne() {
        // Java 7 cannot run any of this, and answering null would push a null check into every caller for
        // a case that ends in "no engines" anyway. The band is selected; finding its jars is what fails.
        assertSame(EngineBand.JAVA_8, EngineBand.forFeatureVersion(7));
        assertSame(EngineBand.JAVA_8, EngineBand.forFeatureVersion(0));
    }

    @Test
    public void theClassFileCeilingMatchesTheBandsJvm() {
        // 52 = Java 8, 55 = Java 11, 61 = Java 17. This is the number `checkEngineBands` holds the
        // resolved jars to, so it has to be right here as well as in the build.
        assertEquals(52, EngineBand.JAVA_8.classFileCeiling());
        assertEquals(55, EngineBand.JAVA_11.classFileCeiling());
        assertEquals(61, EngineBand.JAVA_17.classFileCeiling());
    }

    @Test
    public void bandsElevenAndSeventeenShareOneRhino() {
        // Measured, and it collapses a row of the plan's table: Rhino 1.9.1's class files are Java 11,
        // not 17, so there are three bands and only two Rhinos.
        assertEquals(EngineBand.JAVA_11.rhinoVersion(), EngineBand.JAVA_17.rhinoVersion());
        assertNotSame(EngineBand.JAVA_8.rhinoVersion(), EngineBand.JAVA_11.rhinoVersion());
    }

    @Test
    public void everyBandPinsBothEngines() {
        for (EngineBand band : EngineBand.values()) {
            assertTrue(band + " has no JDT pin", band.jdtVersion().matches("\\d+\\.\\d+\\.\\d+"));
            assertTrue(band + " has no Rhino pin", band.rhinoVersion().matches("\\d+(\\.\\d+)+"));
        }
    }

    @Test
    public void detectAnswersForTheJvmRunningTheTest() {
        // Not asserting WHICH band -- that changes with the toolchain. Asserting that detection agrees
        // with the property it reads, which is the part that could be wrong.
        assertSame(EngineBand.forFeatureVersion(EngineBand.hostFeatureVersion()), EngineBand.detect());
    }
}
