package com.crystalgui.language.java;

import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.ContentProvider;
import com.crystalgui.fs.client.ContentProviders;
import com.crystalgui.language.TestWorkspace;
import com.crystalgui.text.lang.ProjectSourcesRegistry;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.lang.SymbolKind;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * A project file answers what it declares — <b>through the registry a caller actually uses</b>.
 *
 * <h3>Why this exists beside the scanner's own test</h3>
 *
 * <p>{@code ProjectSourceSymbolsTest} drives the scan directly and every one of its cases passed while
 * the feature did nothing at all on screen, because the provider was never registered: the registry
 * <b>threw</b> for {@code project://}, and the throw arrived out of a language's {@code register()}
 * where nothing was looking for it. That is the same lesson M15 recorded four times — assert across the
 * seam, not on either side of it — in its fifth costume.</p>
 */
public class ProjectSourceResourceTest {

    /** Where {@link TestWorkspace} puts a {@code .java} file — its convention, not a second one. */
    private static final String ROOT = "src/main/java";

    private TestWorkspace workspace;

    @Before
    public void openWorkspace() {
        ProjectSourcesRegistry.resetForTesting();
        ContentProviders.resetForTesting();
        workspace = new TestWorkspace(".java");
        ProjectSourcesRegistry.contribute(workspace);
        ProjectSourceSymbols.register();
    }

    @After
    public void close() {
        ProjectSourcesRegistry.resetForTesting();
        ContentProviders.resetForTesting();
    }

    private static SymbolInfo askAbout(String qualifiedName) {
        Resource resource = Resource.of(
                CgPath.parse("proj:" + ROOT + "/" + qualifiedName.replace('.', '/') + ".java"));
        ContentProvider provider = providerFor(resource);
        assertNotNull("nothing is contributed for project://, so no row can ever ask", provider);
        return provider.symbolOf(resource);
    }

    /**
     * <b>The registry accepts a provider for {@code project://} at all.</b>
     *
     * <p>Describing a scheme and serving its bytes are different questions: a project file's content
     * comes from the server, and {@code Workspace.read} checks the scheme before it checks the provider
     * table so a contribution here can never answer instead of the wire.</p>
     */
    @Test
    public void aProviderCanDescribeTheProjectScheme() {
        assertNotNull(providerFor(Resource.of(CgPath.parse("proj:src/main/java/A.java"))));
    }

    private static ContentProvider providerFor(Resource resource) {
        for (ContentProviders.Contribution contribution : ContentProviders.all()) {
            if (contribution.scheme().equals(resource.scheme())) return contribution.provider();
        }
        return null;
    }

    /** <b>...and answers what the file declares, from its unsaved text.</b> */
    @Test
    public void aProjectFileReportsWhatItDeclares() {
        workspace.edit("com.example.Greeter", "package com.example;\npublic interface Greeter { }\n");

        SymbolInfo symbol = askAbout("com.example.Greeter");

        assertNotNull("the provider said nothing about a file the workspace declares", symbol);
        assertEquals(SymbolKind.INTERFACE, symbol.kind());
        assertEquals("Greeter", symbol.name());
    }

    /**
     * <b>An edit is visible without a save</b> — the same promise M15 S5 makes for running.
     *
     * <p>The icon reads through {@code ProjectSources}, so it follows the buffer rather than the disk.
     * Turning a class into an interface changes the glyph on the next refresh.</p>
     */
    @Test
    public void anUnsavedEditChangesTheAnswer() {
        workspace.edit("com.example.Thing", "package com.example;\npublic class Thing { }\n");
        assertEquals(SymbolKind.CLASS, askAbout("com.example.Thing").kind());

        workspace.edit("com.example.Thing", "package com.example;\npublic enum Thing { A }\n");

        assertEquals(SymbolKind.ENUM, askAbout("com.example.Thing").kind());
    }

    /**
     * <b>A file the workspace does not declare answers nothing.</b>
     *
     * <p>Which is every {@code .java} outside a source root, and every file nobody has read yet — the
     * row keeps the file-type icon it had, rather than showing an unknown glyph while a read is in
     * flight.</p>
     */
    @Test
    public void aFileOutsideASourceRootSaysNothing() {
        assertNull(askAbout("com.example.NeverDeclared"));
    }
}
