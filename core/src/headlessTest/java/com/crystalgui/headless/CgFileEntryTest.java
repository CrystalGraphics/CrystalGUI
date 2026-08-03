package com.crystalgui.headless;

import com.crystalgui.fs.CgFileCapability;
import com.crystalgui.fs.CgFileEntry;
import com.crystalgui.fs.CgFileType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** {@link CgFileEntry}, and the etag that does the work of a cache validator and a write guard. */
public class CgFileEntryTest {

    /** <b>An etag changes when either input does.</b> That is the whole contract. */
    @Test
    public void theEtagTracksBothMtimeAndSize() {
        assertEquals(CgFileEntry.file("a", 100, 5000).etag(), CgFileEntry.file("a", 100, 5000).etag());

        assertNotEquals("size moved",
                CgFileEntry.file("a", 100, 5000).etag(), CgFileEntry.file("a", 101, 5000).etag());
        assertNotEquals("mtime moved",
                CgFileEntry.file("a", 100, 5000).etag(), CgFileEntry.file("a", 100, 5001).etag());
    }

    /** The name is not part of it — a renamed file with identical bytes is still the same content. */
    @Test
    public void theEtagIgnoresTheName() {
        assertEquals(CgFileEntry.file("a.txt", 10, 1).etag(), CgFileEntry.file("b.txt", 10, 1).etag());
    }

    /**
     * <b>The two fields cannot bleed into one another.</b>
     *
     * <p>An etag is a concatenation, so the risk is that different pairs produce the same string —
     * {@code mtime=1,size=23} colliding with {@code mtime=12,size=3}. VS Code's odd bases (29 and 31) are
     * chosen so the digit ranges rarely line up, and this walks a block of adjacent pairs to check the
     * property holds where collisions would actually cluster.</p>
     */
    @Test
    public void adjacentPairsDoNotCollide() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (long mtime = 1_700_000_000_000L; mtime < 1_700_000_000_040L; mtime++) {
            for (long size = 0; size < 40; size++) {
                String etag = CgFileEntry.etag(mtime, size);
                assertTrue("collision at mtime=" + mtime + " size=" + size + " -> " + etag,
                        seen.add(etag));
            }
        }
    }

    @Test
    public void theStaticAndInstanceFormsAgree() {
        assertEquals(CgFileEntry.etag(4242, 99), CgFileEntry.file("x", 99, 4242).etag());
    }

    @Test
    public void typeHelpers() {
        CgFileEntry file = CgFileEntry.file("a.txt", 12, 1);
        CgFileEntry dir = CgFileEntry.directory("src", 1);

        assertTrue(file.isFile());
        assertFalse(file.isDirectory());
        assertTrue(dir.isDirectory());
        assertFalse(dir.isFile());
        assertEquals("a directory has no size of its own", 0L, dir.size());
        assertEquals(CgFileType.DIRECTORY, dir.type());
    }

    @Test
    public void nameAndTypeAreRequired() {
        try {
            new CgFileEntry(null, CgFileType.FILE, 0, 0);
            fail("name must be required");
        } catch (IllegalArgumentException expected) {
            // the point
        }
        try {
            new CgFileEntry("a", null, 0, 0);
            fail("type must be required");
        } catch (IllegalArgumentException expected) {
            // the point
        }
    }

    /** Capabilities are a set a provider advertises, and callers must not be able to edit it. */
    @Test
    public void capabilitySetsAreImmutable() {
        var caps = CgFileCapability.of(CgFileCapability.FILE_READ_WRITE, CgFileCapability.READONLY);
        assertTrue(caps.contains(CgFileCapability.FILE_READ_WRITE));
        assertFalse(caps.contains(CgFileCapability.FILE_READ_STREAM));
        assertTrue(CgFileCapability.NONE.isEmpty());
        try {
            caps.add(CgFileCapability.FILE_READ_STREAM);
            fail("a provider's advertised capabilities must not be editable by a caller");
        } catch (UnsupportedOperationException expected) {
            // the point
        }
    }
}
