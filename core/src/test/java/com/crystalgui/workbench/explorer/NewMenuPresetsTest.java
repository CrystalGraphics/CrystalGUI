package com.crystalgui.workbench.explorer;

import com.crystalgui.document.NewDocumentContext;
import com.crystalgui.document.NewDocumentKind;
import com.crystalgui.document.NewDocumentKinds;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.project.SourceRoots;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * <b>New ▸ is a registry, and which rows it offers is a property of the directory.</b>
 *
 * <p>Every row is a {@link NewDocumentKind} somebody registered — the built-ins through the same seam
 * as anyone else — and each declares for itself where it belongs. Getting that wrong is silent: a Java
 * class written into the js tree, or into a resources folder, is a perfectly good file that is in no
 * package and compiles nowhere, and nothing reports it until something tries to import it.</p>
 *
 * <p>Asserted against {@link NewDocumentKinds#offeredAt}, which needs no workbench, no tree and no
 * click — only the answer they would have produced.</p>
 */
public class NewMenuPresetsTest {

    private static final String JAVA_ROOT = "src/main/java";
    private static final String JS_ROOT = "src/main/js";

    private NewDocumentKinds kinds;

    @Before
    public void registerTheBuiltIns() {
        kinds = new NewDocumentKinds();
        BuiltInNewDocuments.registerInto(kinds);
    }

    private List<String> offeredIn(String sourceRoot) {
        List<String> ids = new ArrayList<>();
        NewDocumentContext at = new NewDocumentContext(CgPath.of("p", "some/dir"), sourceRoot);
        for (NewDocumentKind kind : kinds.offeredAt(at)) ids.add(kind.id());
        return ids;
    }

    @Test
    public void aJavaSourceRootOffersTypesAndPackages() {
        List<String> rows = offeredIn(JAVA_ROOT);
        assertTrue(rows.contains(BuiltInNewDocuments.JAVA_CLASS));
        assertTrue(rows.contains(BuiltInNewDocuments.PACKAGE));
        assertTrue(rows.contains(BuiltInNewDocuments.PACKAGE_INFO));

        // A DIRECTORY IS A PACKAGE HERE, so offering both names one thing twice and lets a reader make
        // the one that is not a package.
        assertFalse(rows.contains(BuiltInNewDocuments.FOLDER));
        assertFalse(rows.contains(BuiltInNewDocuments.JS_FILE));
    }

    /**
     * The js root is a source root too, and everything Java-shaped is wrong in it: a class would not
     * compile from there, and JavaScript has directories rather than a package namespace, so a row
     * called "Package" names something the language does not have.
     */
    @Test
    public void aJavaScriptSourceRootOffersScriptsAndDirectories() {
        List<String> rows = offeredIn(JS_ROOT);
        assertTrue(rows.contains(BuiltInNewDocuments.JS_FILE));
        assertFalse(rows.contains(BuiltInNewDocuments.JAVA_CLASS));
        assertFalse(rows.contains(BuiltInNewDocuments.PACKAGE));
        assertFalse(rows.contains(BuiltInNewDocuments.PACKAGE_INFO));
    }

    /** A root named after neither language is not guessed at — it gets the rows that are never wrong. */
    @Test
    public void anUnrecognisedSourceRootFallsBackToPlainFiles() {
        List<String> rows = offeredIn("src/main/resources");
        assertTrue(rows.contains(BuiltInNewDocuments.FILE));
        assertTrue(rows.contains(BuiltInNewDocuments.FOLDER));
        assertFalse(rows.contains(BuiltInNewDocuments.JAVA_CLASS));
        assertFalse(rows.contains(BuiltInNewDocuments.JS_FILE));
    }

    @Test
    public void everyContextOffersAPlainFile() {
        for (String root : new String[]{null, JAVA_ROOT, JS_ROOT, "src/main/resources"}) {
            assertTrue("a plain file is the one row that is never wrong, in " + root,
                    offeredIn(root).contains(BuiltInNewDocuments.FILE));
        }
    }

    /**
     * <b>A contributed kind is a first-class row.</b> This is the whole point of the seam: nothing in
     * the explorer names this document, and it still appears.
     */
    @Test
    public void aContributedKindIsOfferedLikeAnyOther() {
        kinds.register(NewDocumentKind.of("mymod:thing", "Thing")
                .suffix(".thing")
                .template(target -> "name: " + target.typeName()));

        assertTrue(offeredIn(null).contains("mymod:thing"));
        assertTrue("and in a source root too, since it asked for nowhere in particular",
                offeredIn(JAVA_ROOT).contains("mymod:thing"));
    }

    /** Engine rows sort above contributed ones, because a contributor's default order IS the reserve. */
    @Test
    public void engineRowsComeFirst() {
        kinds.register(NewDocumentKind.of("mymod:thing", "Thing"));

        List<String> rows = offeredIn(null);
        assertEquals("a plain file leads", BuiltInNewDocuments.FILE, rows.get(0));
        assertTrue("a contributed row lands after every engine one",
                rows.indexOf("mymod:thing") > rows.indexOf(BuiltInNewDocuments.FOLDER));
    }

    /** A kind's id is its command id, so two of them is a menu that fires the wrong thing. */
    @Test
    public void aDuplicateIdIsRefused() {
        try {
            kinds.register(NewDocumentKind.of(BuiltInNewDocuments.FILE, "Impostor"));
            throw new AssertionError("a duplicate id should be refused");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains(BuiltInNewDocuments.FILE));
        }
    }

    // ── Templates ───────────────────────────────────────────────────────────────────────────────

    private static NewDocumentKind.Target target(String fileName, String packageName) {
        return new NewDocumentKind.Target(CgPath.of("p", JAVA_ROOT), fileName, packageName);
    }

    private String java(String variantId, String fileName, String packageName) {
        NewDocumentKind kind = kinds.byId(BuiltInNewDocuments.JAVA_CLASS);
        for (NewDocumentKind.Variant variant : kind.variants()) {
            if (variant.id().equals(variantId)) {
                return kind.contentFor(target(fileName, packageName), variant);
            }
        }
        throw new AssertionError("no variant " + variantId);
    }

    /**
     * Each of the six declares itself correctly. {@code record} carries its parameter list and
     * {@code @interface} its at-sign — a variant producing {@code public record Foo {} } would be a file
     * that does not compile, reported as the prompt being broken rather than the template.
     */
    @Test
    public void everyVariantDeclaresItself() {
        assertEquals("public class Foo {\n}\n", java("class", "Foo.java", ""));
        assertEquals("public interface Foo {\n}\n", java("interface", "Foo.java", ""));
        assertEquals("public record Foo() {\n}\n", java("record", "Foo.java", ""));
        assertEquals("public enum Foo {\n}\n", java("enum", "Foo.java", ""));
        assertEquals("public @interface Foo {\n}\n", java("annotation", "Foo.java", ""));
        assertEquals("public class Foo extends Exception {\n}\n", java("exception", "Foo.java", ""));
    }

    @Test
    public void aTypeCarriesThePackageItLandsIn() {
        assertEquals("package a.b;\n\npublic record Point() {\n}\n", java("record", "Point.java", "a.b"));
    }

    /**
     * Directly under a source root there is no package, and the declaration is omitted rather than
     * written empty — {@code package ;} does not compile, and a file that does not compile is a worse
     * outcome than a file in the default package.
     */
    @Test
    public void theDefaultPackageGetsNoDeclaration() {
        assertEquals("public class Main {\n}\n", java("class", "Main.java", ""));
        assertEquals("", kinds.byId(BuiltInNewDocuments.PACKAGE_INFO)
                .contentFor(target("package-info.java", ""), null));
    }

    /** Typing the extension and omitting it mean the same file, so the type name is the same either way. */
    @Test
    public void theTypeNameIsTheStemWhicheverWayItWasTyped() {
        assertEquals(java("class", "Greeter.java", "a"), java("class", "Greeter", "a"));
    }

    // ── The source root a directory sits in ─────────────────────────────────────────────────────

    /**
     * A right-click on the source root itself, which is the case reached most often — you make the first
     * package from there. {@code rootOf} has to answer for the root as well as for what is under it, or
     * the root reports no root and gets the rows for somewhere outside every source tree.
     */
    @Test
    public void theSourceRootItselfKnowsWhichRootItIs() {
        List<String> roots = List.of(JAVA_ROOT, JS_ROOT);
        assertEquals(JAVA_ROOT, SourceRoots.rootOf(CgPath.of("p", JAVA_ROOT), roots));
        assertEquals(JS_ROOT, SourceRoots.rootOf(CgPath.of("p", JS_ROOT), roots));
        assertEquals(JAVA_ROOT, SourceRoots.rootOf(CgPath.of("p", JAVA_ROOT + "/com/example"), roots));
        assertEquals(null, SourceRoots.rootOf(CgPath.of("p", "src/main/resources"), roots));
    }

    /** ...and the strict question stays strict: the root is not a file sitting in a package. */
    @Test
    public void locateStillRefusesTheRootItself() {
        List<String> roots = List.of(JAVA_ROOT);
        assertEquals(null, SourceRoots.locate(CgPath.of("p", JAVA_ROOT), roots));

        SourceRoots.Located inside =
                SourceRoots.locate(CgPath.of("p", JAVA_ROOT + "/com/Greeter.java"), roots);
        assertEquals("com", inside.packageName());
        assertEquals("Greeter", inside.simpleName());
    }
}
