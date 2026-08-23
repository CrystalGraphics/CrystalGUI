package com.crystalgui.language;

import com.crystalgui.text.lang.ProjectSources;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An unsaved workspace: qualified name to text, edited in place, writing no file.
 *
 * <h3>Nothing here is ever saved, and that is the point rather than a convenience</h3>
 *
 * <p>Project files are read through {@link ProjectSources}, which answers from an open editor's buffer
 * before it answers from disk — so what a test runs is what an author would be able to see. A fixture
 * backed by real files would pass against an implementation that had quietly gone back to reading disk.</p>
 *
 * <h3>The extension is not decoration</h3>
 *
 * <p>{@code SourceRoots.CONVENTION} names any file under a declared root whatever its extension, and both
 * {@code src/main/java} and {@code src/main/js} are declared — so <b>one index holds both languages'
 * names</b>, with nothing in the NAME to say which language wrote it. {@link #pathOf} is the only thing
 * that can, which is why both engines guard on it: handing a {@code .js} file to a Java compiler produces
 * a page of syntax errors about the wrong file instead of the one true thing, that there is no such type.</p>
 *
 * <p>A stand-in answering {@code null} from {@code pathOf} is <em>trusted</em> by both engines — that is
 * the documented behaviour for a provider with no paths to offer — so a fixture without this cannot test
 * either guard, and would pass against no guard at all.</p>
 *
 * <p>Shared because all three copies of it had become identical, down to the root-mapping rule above.
 * Three transcriptions of one convention is three chances for it to drift from {@code SourceRoots}.</p>
 */
public final class TestWorkspace implements ProjectSources {

    private final Map<String, String> files = new LinkedHashMap<>();
    private final Map<String, String> extensions = new LinkedHashMap<>();
    private final String defaultExtension;

    /** @param defaultExtension what a two-argument {@link #edit} means — the language under test */
    public TestWorkspace(String defaultExtension) {
        this.defaultExtension = defaultExtension;
    }

    public TestWorkspace edit(String qualifiedName, String source) {
        return edit(qualifiedName, source, defaultExtension);
    }

    public TestWorkspace edit(String qualifiedName, String source, String extension) {
        files.put(qualifiedName, source);
        extensions.put(qualifiedName, extension);
        return this;
    }

    @Override
    public String sourceOf(String qualifiedName) {
        return files.get(qualifiedName);
    }

    /** Where the file would live — the only thing that says which LANGUAGE wrote it. */
    @Override
    public String pathOf(String qualifiedName) {
        String extension = extensions.get(qualifiedName);
        if (extension == null) return null;
        String root = ".js".equals(extension) ? "src/main/js" : "src/main/java";
        return "proj:" + root + "/" + qualifiedName.replace('.', '/') + extension;
    }

    @Override
    public boolean declaresPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        String prefix = packageName + ".";
        for (String name : files.keySet()) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    @Override
    public List<String> declaredTypes() {
        return List.copyOf(files.keySet());
    }
}
