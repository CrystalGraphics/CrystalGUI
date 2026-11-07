package com.crystalgui.language.platform;

import com.crystalgui.language.map.PlatformMappings;
import com.crystalgui.language.map.ReadableView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** What {@link ScriptService} works out from the running game: the mapping per version and namespace, and no game. */
public class ScriptServiceTest {

    private static final byte[] CLASS = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Before
    @After
    public void noGame() {
        FakeGame.clear();
        PlatformMappings.resetForTesting();
    }

    private Path game(String version, String... classes) {
        Set<String> present = Set.of(classes);
        Path directory = folder.getRoot().toPath();
        FakeGame.install(version, name -> present.contains(name) ? CLASS : null, directory);
        return directory;
    }

    @Test
    public void withNoGameNothingIsLive() throws Exception {
        assertFalse(ScriptService.live());
        assertSame(ReadableView.ByteSource.NONE, ScriptService.liveBytes());
        assertTrue(ScriptService.mappings().isNone());
        assertTrue(ScriptService.namespaceProbe().isNone());
        assertEquals("net/minecraft/world/World", ScriptService.runtimeClassName("net/minecraft/world/World"));
        assertTrue(ScriptService.cacheRoot().endsWith(Path.of("build", "crystalgui-cache")));
        assertNull(ScriptService.runningNodeClass());
    }

    @Test
    public void aGameCachesUnderItsDirectory() {
        Path directory = game("1.20.1");
        assertTrue(ScriptService.live());
        assertTrue(ScriptService.cacheRoot().startsWith(directory));
    }

    @Test
    public void minecraft1710TakesMcpStable12() {
        game("1.7.10");
        assertEquals("stable-12", ScriptService.mappings().cacheKey());
        assertEquals("getBlock", ScriptService.namespaceProbe().readableMember());
    }

    @Test
    public void legacyForgeTakesTheNewestMcpAtOrBelowItsVersion() {
        game("1.12.2");
        assertEquals("stable-39-1.12", ScriptService.mappings().cacheKey());
        game("1.8.9");
        assertEquals("stable-22-1.8.9", ScriptService.mappings().cacheKey());
        assertEquals("net/minecraft/world/World", ScriptService.namespaceProbe().internalName());
    }

    @Test
    public void fabricIsToldByItsIntermediaryLevel() {
        game("1.20.1", "net/minecraft/class_1937");
        MappingCoordinates mappings = ScriptService.mappings();
        assertEquals("intermediary-1.20.1", mappings.cacheKey());
        assertEquals("net/minecraft/class_1937", ScriptService.namespaceProbe().internalName());
    }

    @Test
    public void forgeFrom117KeepsOfficialClassNames() {
        game("1.20.1", "net/minecraft/world/level/Level");
        assertTrue(ScriptService.mappings().keepsReadableClassNames());
        assertEquals("net/minecraft/world/level/Level", ScriptService.namespaceProbe().internalName());
    }

    @Test
    public void forgeBefore117RunsMcpClassNames() {
        game("1.16.5", "net/minecraft/world/World");
        assertFalse(ScriptService.mappings().keepsReadableClassNames());
        assertEquals("net/minecraft/world/World", ScriptService.namespaceProbe().internalName());
    }

    @Test
    public void forgeBefore1144TakesMcpStable() {
        game("1.13.2", "net/minecraft/world/World");
        assertEquals("stable-47-1.13.2", ScriptService.mappings().cacheKey());
    }

    @Test
    public void versionsCompareByNumber() {
        assertTrue(ScriptService.compareVersions("1.12.2", "1.13") < 0);
        assertTrue(ScriptService.compareVersions("26.2", "1.21.11") > 0);
        assertEquals(0, ScriptService.compareVersions("1.21.6-pre1", "1.21.6"));
    }
}
