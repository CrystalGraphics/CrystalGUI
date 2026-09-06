package com.crystalgui.headless;

import com.crystalgui.fs.CgFileError;
import com.crystalgui.fs.CgFileSystemException;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.project.ProjectProvider;
import com.crystalgui.fs.project.ProjectRegistry;
import com.crystalgui.fs.project.WorkspaceProject;
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

    /**
     * A project created at runtime appears as soon as its provider says its set moved.
     *
     * <p>This used to assert enumeration was live <em>unconditionally</em>, which is what
     * {@code all()} rebuilding on every call bought — and what it cost is in
     * {@code plan/fs-rewrite.md} N20: one file read rebuilt the registry three times, and the watcher's
     * poll twice per file per peer per half second. The bargain is now explicit
     * ({@link ProjectProvider#revision()}), and both halves of it are asserted: this, and
     * {@link #aProviderThatDoesNotReportAChangeIsNotAskedAgain} below.</p>
     */
    @Test
    public void aProviderThatReportsAChangeIsRebuilt() {
        List<WorkspaceProject> backing = new java.util.ArrayList<>();
        long[] revision = {0};
        ProjectRegistry registry = new ProjectRegistry().register(new ProjectProvider() {
            @Override public List<WorkspaceProject> projects() { return backing; }
            @Override public long revision() { return revision[0]; }
        });

        assertTrue(registry.all().isEmpty());
        backing.add(project("late.arrival", "/tmp/late"));
        revision[0]++;

        assertEquals(1, registry.all().size());
        assertTrue(registry.find("late.arrival").isPresent());
    }

    /**
     * <b>The counter-control, and the point of the whole step.</b> A provider with a fixed set is asked
     * for its projects <em>once</em>, however many times the registry is read.
     */
    @Test
    public void aProviderThatDoesNotReportAChangeIsNotAskedAgain() {
        int[] asked = {0};
        ProjectRegistry registry = new ProjectRegistry().register(() -> {
            asked[0]++;
            return List.of(project("fixed", "/tmp/fixed"));
        });

        registry.all();
        assertEquals(1, asked[0]);

        // What one file read costs today: authorise, resolve, and the listing's excludes lookup.
        registry.all();
        registry.find("fixed");
        registry.require(CgPath.parse("fixed:some/file.txt"));
        registry.infos();

        assertEquals("a fixed set is read once per process, not once per question", 1, asked[0]);
    }

    /** {@code invalidate()} is the host's escape hatch, for a provider that cannot report its own moves. */
    @Test
    public void invalidateForcesARebuild() {
        int[] asked = {0};
        ProjectRegistry registry = new ProjectRegistry().register(() -> {
            asked[0]++;
            return List.of(project("fixed", "/tmp/fixed"));
        });

        registry.all();
        registry.invalidate();
        registry.all();

        assertEquals(2, asked[0]);
    }

    /** Registering a second provider is itself a change; nothing has to report it. */
    @Test
    public void registeringAProviderRebuildsWithoutAnyRevisionBeingBumped() {
        ProjectRegistry registry = new ProjectRegistry().register(() -> List.of(project("a", "/tmp/a")));
        assertEquals(1, registry.all().size());

        registry.register(() -> List.of(project("b", "/tmp/b")));

        assertEquals(2, registry.all().size());
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
        com.crystalgui.fs.project.ProjectProvider provider = () -> List.of(project("x.y", "/tmp/x"));
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
