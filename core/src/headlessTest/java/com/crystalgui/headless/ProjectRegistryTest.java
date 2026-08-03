package com.crystalgui.headless;

import com.crystalgui.fs.CgFileError;
import com.crystalgui.fs.CgFileSystemException;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.ProjectRegistry;
import com.crystalgui.fs.WorkspaceProject;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** {@link ProjectRegistry} — where an id becomes a directory, and where it refuses to. */
public class ProjectRegistryTest {

    private static WorkspaceProject project(String id, String where) {
        return new WorkspaceProject(id, id, Paths.get(where));
    }

    @Test
    public void anEmptyRegistryOffersNothing() {
        ProjectRegistry registry = new ProjectRegistry();
        assertTrue("a server with no workspace mod exposes no workspace", registry.all().isEmpty());
        assertTrue(registry.find("anything").isEmpty());
    }

    @Test
    public void providersAreEnumeratedInRegistrationOrder() {
        ProjectRegistry registry = new ProjectRegistry()
                .register(() -> List.of(project("a.one", "/tmp/one")))
                .register(() -> List.of(project("b.two", "/tmp/two"), project("b.three", "/tmp/three")));

        assertEquals(List.of("a.one", "b.two", "b.three"),
                registry.all().stream().map(WorkspaceProject::id).toList());
    }

    /**
     * <b>A duplicate id is refused, not resolved by ordering.</b>
     *
     * <p>Letting the first registration win would make a {@link CgPath} saved in a document resolve to a
     * different project depending on mod load order — which is not reproducible and would present as the
     * file being fine on one launch and missing on the next.</p>
     */
    @Test
    public void aDuplicateIdIsRefused() {
        ProjectRegistry registry = new ProjectRegistry()
                .register(() -> List.of(project("scripts", "/tmp/a")))
                .register(() -> List.of(project("scripts", "/tmp/b")));
        try {
            registry.all();
            fail("two providers offering the same id must not silently resolve");
        } catch (IllegalStateException e) {
            assertTrue("the message should suggest namespacing, since that is the fix",
                    e.getMessage().contains("mymod."));
        }
    }

    /** Enumeration is live, so a project created at runtime appears without an invalidation call. */
    @Test
    public void enumerationIsLive() {
        List<WorkspaceProject> backing = new java.util.ArrayList<>();
        ProjectRegistry registry = new ProjectRegistry().register(() -> backing);

        assertTrue(registry.all().isEmpty());
        backing.add(project("late.arrival", "/tmp/late"));
        assertEquals(1, registry.all().size());
    }

    /**
     * <b>An unknown project reports FILE_NOT_FOUND, exactly as a missing file does.</b>
     *
     * <p>A distinct "no such project" code would let an unauthorised client map which projects exist by
     * comparing errors.</p>
     */
    @Test
    public void anUnknownProjectIsNotFound() {
        ProjectRegistry registry = new ProjectRegistry();
        try {
            registry.require(CgPath.parse("ghost:some/file.txt"));
            fail("expected a refusal");
        } catch (CgFileSystemException e) {
            assertEquals(CgFileError.FILE_NOT_FOUND, e.getError());
        }
    }

    @Test
    public void requireResolvesAPathToItsProject() {
        ProjectRegistry registry = new ProjectRegistry()
                .register(() -> List.of(project("mymod.scripts", "/srv/scripts")));

        WorkspaceProject found = registry.require(CgPath.parse("mymod.scripts:src/Main.java"));
        assertEquals("mymod.scripts", found.id());
        assertEquals(Paths.get("/srv/scripts"), found.root());
        assertEquals("mymod", found.namespace());
    }

    /** An id with no dot is its own namespace, so a single-project mod need not invent one. */
    @Test
    public void anIdWithoutADotIsItsOwnNamespace() {
        assertEquals("scripts", project("scripts", "/tmp/x").namespace());
        assertEquals("a", project("a.b.c", "/tmp/x").namespace());
    }

    @Test
    public void defaultRootForNeedsABaseAndThenComposesIt() {
        ProjectRegistry registry = new ProjectRegistry();
        try {
            registry.defaultRootFor("mymod", "scripts");
            fail("without a base there is no sensible default");
        } catch (IllegalStateException expected) {
            // the point — the host decides where writable data lives
        }
        registry.defaultBase(Paths.get("/srv/projects"));
        assertEquals(Paths.get("/srv/projects", "mymod", "scripts"),
                registry.defaultRootFor("mymod", "scripts"));
    }

    @Test
    public void unregisterRemovesAProvider() {
        com.crystalgui.fs.ProjectProvider provider = () -> List.of(project("x.y", "/tmp/x"));
        ProjectRegistry registry = new ProjectRegistry().register(provider);
        assertEquals(1, registry.all().size());
        assertTrue(registry.unregister(provider));
        assertTrue(registry.all().isEmpty());
        assertFalse("removing twice is not an error but reports nothing was there",
                registry.unregister(provider));
    }

    /** The wire-safe half never carries a directory. */
    @Test
    public void infosCarryNoServerPath() {
        ProjectRegistry registry = new ProjectRegistry()
                .register(() -> List.of(project("mymod.scripts", "/home/mc/secret/layout")));

        var infos = registry.infos();
        assertEquals(1, infos.size());
        assertEquals("mymod.scripts", infos.get(0).id());
        assertEquals("a project's root path must never reach a client",
                "mymod.scripts:", infos.get(0).root().toString());

        Path onDisk = registry.require(CgPath.parse("mymod.scripts:")).root();
        assertFalse("the info type has no field that could leak it",
                infos.get(0).toString().contains(onDisk.toString()));
    }
}
