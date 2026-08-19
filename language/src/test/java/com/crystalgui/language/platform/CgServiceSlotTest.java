package com.crystalgui.language.platform;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * <b>The slot's contract, from the side that consumes it.</b>
 *
 * <p>{@link CgService} lives in CrystalGraphics and knows nothing about scripting; this is where the two
 * meet, so this is where the meeting is asserted. What matters is not that a getter returns what a setter
 * put there — it is the three properties the slot exists to guarantee, each of which fails silently.</p>
 *
 * <p>Deliberately not a test of the announcement text. That goes to stderr on purpose and reads as
 * documentation for whoever is looking at a log; pinning the wording would make it a thing that breaks
 * when someone improves it, which is the edge-testing this project refuses.</p>
 */
public class CgServiceSlotTest {

    @Before
    @After
    public void forget() {
        CgPlatform.provide(ScriptPlatforms.SERVICE, null);
    }

    /** A fake that is identifiable by reference. */
    private static ScriptPlatform fake() {
        return new ScriptPlatform() {
            @Override
            public com.crystalgui.language.map.ReadableView.ByteSource liveBytes() {
                return name -> null;
            }

            @Override
            public java.nio.file.Path cacheRoot() {
                return java.nio.file.Paths.get("build", "slot-test").toAbsolutePath();
            }

            @Override
            public MappingCoordinates mappings() {
                return MappingCoordinates.NONE;
            }

            @Override
            public NamespaceProbe namespaceProbe() {
                return NamespaceProbe.NONE;
            }

            @Override
            public String runtimeClassName(String onDiskInternalName) {
                return onDiskInternalName;
            }
        };
    }

    /**
     * <b>The absent-value is never null</b>, which is the whole reason it is stated on the slot.
     *
     * <p>A lookup returning an {@code Optional} puts this decision at every call site, and consumers that
     * each supply their own fallback are consumers that can disagree about what absence means. Here it is
     * declared once beside the contract, so there is one answer.</p>
     */
    @Test
    public void anUnprovidedSlotAnswersItsAbsentValueRatherThanNull() {
        assertFalse(CgPlatform.isProvided(ScriptPlatforms.SERVICE));
        assertNotNull(CgPlatform.get(ScriptPlatforms.SERVICE));
        assertSame(ScriptPlatform.NONE, CgPlatform.get(ScriptPlatforms.SERVICE));
        // And through the convenience wrapper, which is what nearly every caller actually uses.
        assertSame(ScriptPlatform.NONE, CgPlatform.get(ScriptPlatforms.SERVICE));
    }

    /** Providing installs it, and {@code isProvided} distinguishes it from the fallback. */
    @Test
    public void providingInstallsIt() {
        ScriptPlatform installed = fake();
        CgPlatform.provide(ScriptPlatforms.SERVICE, installed);

        assertTrue(CgPlatform.isProvided(ScriptPlatforms.SERVICE));
        assertSame(installed, CgPlatform.get(ScriptPlatforms.SERVICE));
        assertSame(installed, CgPlatform.get(ScriptPlatforms.SERVICE));
    }

    /**
     * <b>Last write wins, and null clears</b> — both because initialisation order is not ours to fix.
     *
     * <p>Two mods initialise in whatever order the loader picks, so a slot that refused a second write
     * would make correctness depend on that order. And {@code register(null)} has always meant "back to
     * none", so the two spellings have to agree or a caller has to know which one it is holding.</p>
     */
    @Test
    public void lastWriteWinsAndNullClears() {
        ScriptPlatform first = fake();
        ScriptPlatform second = fake();

        CgPlatform.provide(ScriptPlatforms.SERVICE, first);
        CgPlatform.provide(ScriptPlatforms.SERVICE, second);
        assertSame(second, CgPlatform.get(ScriptPlatforms.SERVICE));

        CgPlatform.provide(ScriptPlatforms.SERVICE, null);
        assertFalse(CgPlatform.isProvided(ScriptPlatforms.SERVICE));
        assertSame(ScriptPlatform.NONE, CgPlatform.get(ScriptPlatforms.SERVICE));
    }

    /**
     * <b>The slot appears in the platform stack</b> — the question nothing could answer before.
     *
     * <p>This is what makes the registry one registry rather than a convention: a loader, or anyone
     * reading a log, can enumerate what the platform is carrying instead of knowing in advance which
     * static holders exist. Read through {@code CgPlatform.services()}, which is the only public way in
     * — a slot's own read/write surface is package-private on purpose.</p>
     */
    @Test
    public void theSlotIsVisibleInTheDeclaredStack() {
        // Touch the class so its slot is certainly declared -- the one property `declared()` cannot
        // give on its own, and the reason absence is reported from get() rather than from a sweep.
        assertNotNull(ScriptPlatforms.SERVICE);

        List<CgService<?>> declared = CgPlatform.services();
        for (CgService<?> slot : declared) {
            if ("crystalgui:script-platform".equals(slot.name())) return;
        }
        fail("the script-platform slot is not in the declared stack: " + declared);
    }

    /**
     * A slot refuses a null absent-value at declaration.
     *
     * <p>Not pedantry: a slot whose fallback is null is a slot every consumer must null-check, which is
     * precisely the call-site branching the design removes. A contract with no sensible do-nothing value
     * belongs in {@code CgPlatformService}, where the compiler insists on a real one.</p>
     */
    @Test
    public void aSlotRefusesANullAbsentValue() {
        try {
            CgService.of("crystalgui:test-null-absent", null);
            fail("a null absent-value was accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("absent-value"));
        }
    }

    /** And a slot needs a name, because the name is what a log line and the stack dump say. */
    @Test
    public void aSlotRefusesAnEmptyName() {
        try {
            CgService.of("", ScriptPlatform.NONE);
            fail("an unnamed slot was accepted");
        } catch (IllegalArgumentException expected) {
            assertEquals(true, expected.getMessage().contains("name"));
        }
    }
}
