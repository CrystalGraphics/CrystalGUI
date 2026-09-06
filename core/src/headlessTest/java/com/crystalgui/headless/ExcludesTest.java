package com.crystalgui.headless;

import com.crystalgui.fs.provider.CgFileEntry;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.project.Excludes;
import com.crystalgui.fs.provider.InMemoryFileSystem;
import com.crystalgui.fs.project.ProjectRegistry;
import com.crystalgui.fs.project.ProjectInfo;
import com.crystalgui.fs.server.WorkspaceActor;
import com.crystalgui.fs.server.WorkspacePermission;
import com.crystalgui.fs.project.WorkspaceProject;
import com.crystalgui.fs.server.WorkspaceService;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@code plan/fs-rewrite.md} F0.5, N23 — one exclusion rule, honoured by everything that excludes.
 *
 * <p>There were three matchers with three different semantics. The listing matched {@code *} and
 * {@code ?} anywhere; the watcher matched a <b>leading star only</b> while its javadoc claimed "the same
 * rule"; and the icon theme had a third. So a project excluding {@code build/*.class} filtered its
 * listings and watched every one of those files anyway.</p>
 */
public class ExcludesTest {

    @Test
    public void aBareNameMatchesItselfAndNothingElse() {
        Excludes excludes = Excludes.of(List.of(".git"));
        assertTrue(excludes.excludes(".git"));
        assertFalse(excludes.excludes(".gitignore"));
        assertFalse(excludes.excludes("git"));
    }

    @Test
    public void aTrailingStarMatchesAPrefix() {
        Excludes excludes = Excludes.of(List.of("tmp*"));
        assertTrue(excludes.excludes("tmp"));
        assertTrue(excludes.excludes("tmpfile"));
        assertFalse(excludes.excludes("atmp"));
    }

    @Test
    public void aLeadingStarMatchesASuffix() {
        Excludes excludes = Excludes.of(List.of("*.class"));
        assertTrue(excludes.excludes("Main.class"));
        assertTrue(excludes.excludes(".class"));
        assertFalse(excludes.excludes("Main.java"));
    }

    /**
     * <b>The divergence, in one assertion.</b> The watcher's matcher took the first character as the only
     * place a star could appear, so this pattern — a star in the middle, which the listing has always
     * honoured — matched nothing at all there.
     */
    @Test
    public void aStarInTheMiddleMatches() {
        Excludes excludes = Excludes.of(List.of("build*.class"));
        assertTrue(excludes.excludes("build.class"));
        assertTrue(excludes.excludes("buildOutputMain.class"));
        assertFalse(excludes.excludes("srcMain.class"));
    }

    @Test
    public void aQuestionMarkIsExactlyOneCharacter() {
        Excludes excludes = Excludes.of(List.of("?.tmp"));
        assertTrue(excludes.excludes("a.tmp"));
        assertFalse(excludes.excludes("ab.tmp"));
        assertFalse(excludes.excludes(".tmp"));
    }

    @Test
    public void nothingIsExcludedByAnEmptyRule() {
        assertSame(Excludes.NONE, Excludes.of(List.of()));
        assertSame(Excludes.NONE, Excludes.of(null));
        assertTrue(Excludes.NONE.isEmpty());
        assertFalse(Excludes.NONE.excludes("anything"));
    }

    /**
     * <b>The acceptance: the manifest and the watcher agree on every pattern.</b>
     *
     * <p>Asserted by running the two consumers' own questions over one pattern set. The listing goes
     * through {@code WorkspaceService.manifest}; the watcher's answer is {@code Excludes} directly,
     * because that is now the only thing {@code NioFileEventSource} asks — which is the point.
     */
    @Test
    public void theWatcherAndTheManifestAgreeOnEveryPattern() {
        List<String> patterns = List.of(".git", "build", "*.class", "tmp*", "a*b");
        String[] names = {".git", ".gitignore", "build", "rebuild", "Main.class", "Main.java",
                "tmp", "tmpfile", "atmp", "ab", "axxb", "ba"};

        InMemoryFileSystem files = new InMemoryFileSystem();
        for (String name : names) files.seed("p:" + name, "x");

        WorkspaceProject project =
                new WorkspaceProject(new ProjectInfo("p", "P"), Paths.get("/srv/p"), patterns);
        ProjectRegistry registry = new ProjectRegistry().register(() -> List.of(project));
        WorkspaceService service =
                new WorkspaceService(registry, files, WorkspacePermission.ALLOW_ALL);

        List<String> listed = new ArrayList<>();
        for (CgFileEntry entry : service.manifest(WorkspaceActor.LOCAL, CgPath.parse("p:"))) {
            listed.add(entry.name());
        }

        Excludes watched = Excludes.of(project.excludes());
        for (String name : names) {
            assertEquals("the two must agree about '" + name + "'",
                    !listed.contains(name), watched.excludes(name));
        }
        assertTrue("and the fixture must actually exclude something", listed.size() < names.length);
        assertTrue("and must actually keep something", listed.contains("Main.java"));
    }
}
