package com.crystalgui.render.texture.svg;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link SvgDocument#preload} does the loading a first frame would otherwise do, off the render thread.
 *
 * <p><b>Nothing here clears the document cache.</b> It is global and static, and other tests hold live
 * {@code SvgDocument} instances from it — clearing it mid-suite hands them a freshly parsed object and
 * turns their identity assertions into order-dependent failures. It cost {@code ProjectTreeIconsTest}
 * exactly that. Every assertion below holds against a warm cache anyway.</p>
 *
 * <p>The interesting assertion is not that it completes — it is that a preloaded path is afterwards served
 * from the cache as the <em>same instance</em>, since that is the whole mechanism. A preload that parsed
 * correctly into a map nobody consults would be indistinguishable from working, right up until it made no
 * difference to a frame time.</p>
 */
public class SvgPreloadTest {

    @Test
    public void preloadedIconsAreServedFromTheCacheAfterwards() throws Exception {
        List<String> paths = shippedIconPaths();
        assertTrue("expected a shipped icon set, found " + paths.size(), paths.size() > 5);

        SvgDocument.preload(paths).get(30, TimeUnit.SECONDS);

        for (String path : paths) {
            SvgDocument first = SvgDocument.of(path);
            assertNotNull(path + " did not load", first);
            // Same instance: proves the worker populated the cache the render thread reads, rather than
            // parsing into something of its own that of() then quietly redid.
            assertSame(path + " was re-parsed rather than served from cache", first, SvgDocument.of(path));
        }
    }

    /** A path that cannot be read must not fail the batch, exactly as it would not on the render thread. */
    @Test
    public void anUnreadablePathDoesNotFailTheBatch() throws Exception {
        List<String> paths = new ArrayList<>(shippedIconPaths());
        paths.add("crystalgui:ui/icons/definitely-not-here.svg");
        SvgDocument.preload(paths).get(30, TimeUnit.SECONDS);
        assertEquals(null, SvgDocument.of("crystalgui:ui/icons/definitely-not-here.svg"));
    }

    /** Preloading twice is a no-op, so a caller may warm the same set on every screen open. */
    @Test
    public void preloadingAnAlreadyCachedSetIsHarmless() throws Exception {
        List<String> paths = shippedIconPaths();
        SvgDocument.preload(paths).get(30, TimeUnit.SECONDS);
        SvgDocument first = SvgDocument.of(paths.get(0));
        SvgDocument.preload(paths).get(30, TimeUnit.SECONDS);
        assertSame("a second preload replaced a cached document", first, SvgDocument.of(paths.get(0)));
    }

    /** Documents are shared across threads, so the lazy mesh must survive concurrent first use. */
    @Test
    public void concurrentFirstUseOfTheLazyMeshIsConsistent() throws Exception {
        List<String> paths = shippedIconPaths();
        SvgDocument.preload(paths).get(30, TimeUnit.SECONDS);

        SvgDocument document = SvgDocument.of(paths.get(0));
        assertNotNull(document);
        List<Thread> racers = new ArrayList<>();
        List<List<SvgDocument.DrawOp>> seen = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            seen.add(null);
            int slot = i;
            Thread thread = new Thread(() -> seen.set(slot, document.ops()));
            racers.add(thread);
            thread.start();
        }
        for (Thread thread : racers) thread.join(30_000);
        for (List<SvgDocument.DrawOp> ops : seen) {
            assertSame("ops() handed out two different meshes", seen.get(0), ops);
        }
    }

    private static List<String> shippedIconPaths() throws IOException {
        Path root = Path.of("src/main/resources/assets/crystalgui/ui/icons");
        if (!Files.isDirectory(root)) root = Path.of("core").resolve(root);
        assertTrue("icon root is missing: " + root.toAbsolutePath(), Files.isDirectory(root));
        Path base = root;
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.getFileName().toString().endsWith(".svg"))
                    .map(p -> "crystalgui:ui/icons/"
                            + base.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }
}
