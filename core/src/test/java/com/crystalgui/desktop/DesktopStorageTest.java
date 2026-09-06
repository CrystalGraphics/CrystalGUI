package com.crystalgui.desktop;

import com.crystalgui.core.storage.StorageLayout;
import com.crystalgui.ui.dom.UIDocument;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@code plan/crystalgui/fs-rewrite/fs-storage-layout.md} S0 — where a desktop writes.
 *
 * <p>The one thing a caller sees from outside and can get wrong: a host says {@code useStorage(dir)}
 * and everything under it has to land in {@code crystalgui/}. It was once reported as landing beside
 * that tree instead, which turned out to be a build predating the change — so the path is pinned here
 * rather than left to a harness run to reveal.</p>
 */
public class DesktopStorageTest {

    private static Path installation(String name) {
        return Paths.get("build", "tmp", "desktop-storage-test", name).toAbsolutePath();
    }

    @Test
    public void storageLandsUnderTheCrystalguiRoot() {
        Path installation = installation("configured");
        Desktop desktop = Desktop.of(new UIDocument()).useStorage(installation);

        assertEquals("the store goes under crystalgui/workspace-config, never beside it",
                StorageLayout.configIn(installation), desktop.config().directory());
        assertEquals(StorageLayout.cacheIn(installation), desktop.cacheRoot());
    }

    @Test
    public void theCacheDirectoryIsNamedButNotCreated() {
        Path installation = installation("lazy-cache");
        Desktop desktop = Desktop.of(new UIDocument()).useStorage(installation);

        assertTrue("the config store makes its directory eagerly",
                desktop.config().directory().toFile().isDirectory());
        // cache/ is a Path and not a store on purpose: an empty cache/ standing there before anything
        // derived exists is a directory nobody can explain.
        assertFalse("cache/ is not created merely by being named",
                desktop.cacheRoot().toFile().exists());
    }

    /**
     * <b>No default, and that is the point.</b> A desktop nobody has told has nowhere to write and says
     * so; inventing the working directory would be an answer chosen for a host that never saw the
     * question, which is the same reason {@code HostServices} declares no defaults either.
     */
    @Test
    public void anUnconfiguredDesktopHasNowhereToWrite() {
        Desktop desktop = Desktop.of(new UIDocument());

        assertNull(desktop.config());
        assertNull(desktop.cacheRoot());
        // And it refuses rather than writing somewhere nobody chose. Recording the arrangement is a
        // no-op for the same reason; neither throws, because a host with no storage is a supported
        // state and not an error.
        desktop.persistAs("nowhere");
        assertNull(desktop.config());
    }
}
