package com.crystalgui.language.java;

import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.SymbolModifier;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * What a project {@code .java} file declares, read off the text — the tree's icon, and the tab's.
 *
 * <h3>Every test here is a token read out of context</h3>
 *
 * <p>Which is the only way a scan of this kind goes wrong. A comment, a string literal and an annotation
 * each put one of the words being looked for somewhere it does not mean what it says, and all three
 * failures look identical from outside: a row with an icon, rather than a row with the WRONG icon.</p>
 */
public class ProjectSourceSymbolsTest {

    private static SymbolInfo declared(String source) {
        return ProjectSourceSymbols.declaredIn(source, "com.example.Thing");
    }

    private static SymbolKind kindOf(String source) {
        SymbolInfo found = declared(source);
        return found == null ? null : found.kind();
    }

    // ── The kinds ───────────────────────────────────────────────────────────────────────────────

    @Test
    public void everyTopLevelKindIsRecognised() {
        assertEquals(SymbolKind.CLASS, kindOf("package com.example;\npublic class Thing { }\n"));
        assertEquals(SymbolKind.INTERFACE, kindOf("package com.example;\npublic interface Thing { }\n"));
        assertEquals(SymbolKind.ENUM, kindOf("package com.example;\npublic enum Thing { A, B }\n"));
        assertEquals(SymbolKind.RECORD, kindOf("package com.example;\npublic record Thing(int x) { }\n"));
        assertEquals(SymbolKind.ANNOTATION,
                kindOf("package com.example;\npublic @interface Thing { }\n"));
    }

    /** <b>Modifiers ride along</b> — they are what {@code SymbolIcon} stacks its mark layers from. */
    @Test
    public void modifiersAreCarried() {
        assertTrue(declared("public abstract class Thing { }\n")
                .modifiers().contains(SymbolModifier.ABSTRACT));
        assertTrue(declared("public final class Thing { }\n")
                .modifiers().contains(SymbolModifier.FINAL));
        assertTrue("`final enum` is what a Java enum compiles to, and what the tab shows",
                declared("final enum Thing { A }\n").modifiers().contains(SymbolModifier.FINAL));
    }

    // ── The three ways a scan reads a keyword that is not one ───────────────────────────────────

    /**
     * <b>A comment does not declare anything.</b>
     *
     * <p>The commonest shape in this codebase: every file opens with a javadoc block, and the words
     * {@code class}, {@code interface} and {@code final} are all ordinary English inside one.</p>
     */
    @Test
    public void aKeywordInsideACommentIsProse() {
        assertEquals(SymbolKind.INTERFACE, kindOf(""
                + "/** A final class of thing -- see the enum below. */\n"
                + "// class Thing was renamed\n"
                + "public interface Thing { }\n"));
    }

    /** <b>...nor does one inside a string.</b> */
    @Test
    public void aKeywordInsideALiteralIsText() {
        assertEquals(SymbolKind.ENUM, kindOf(""
                + "public enum Thing {\n"
                + "    A;\n"
                + "    static final String HINT = \"public final class Other\";\n"
                + "}\n"));
    }

    /**
     * <b>{@code @Override} is not {@code @interface}.</b>
     *
     * <p>One character apart in the same position. Skipping a bare {@code @} and letting the word through
     * makes every annotated class in the project report as an interface.</p>
     */
    @Test
    public void anAnnotationIsNotAnAnnotationType() {
        assertEquals(SymbolKind.CLASS, kindOf(""
                + "@Deprecated\n"
                + "@SuppressWarnings(\"unchecked\")\n"
                + "public class Thing { }\n"));
    }

    /**
     * <b>{@code record} is contextual, and the other two are not.</b>
     *
     * <p>{@code SourceHeaders} documents the same trap: {@code record} is a declaration only when a NAME
     * follows it. Believed unconditionally, a parameter called {@code record} reports the file as one.</p>
     */
    @Test
    public void recordIsOnlyADeclarationWhenANameFollows() {
        assertEquals(SymbolKind.CLASS, kindOf(""
                + "public class Thing {\n"
                + "    void write(Object record) { }\n"
                + "}\n"));
    }

    /** <b>An import's own modifier does not reach the type.</b> {@code import static} is not a final class. */
    @Test
    public void anImportsModifierDoesNotLeak() {
        SymbolInfo found = declared(""
                + "package com.example;\n"
                + "import static java.util.List.of;\n"
                + "public class Thing { }\n");

        assertEquals(SymbolKind.CLASS, found.kind());
        assertTrue("`import static` made the class static: " + found.modifiers(),
                found.modifiers().isEmpty());
    }

    // ── Nothing to say ──────────────────────────────────────────────────────────────────────────

    /**
     * <b>A file that declares no type answers null</b>, and keeps its file-type icon.
     *
     * <p>{@code package-info.java} is the real one, and a file being typed is the common one — a tree
     * that drew an unknown glyph while you were halfway through a word would flicker on every keystroke.</p>
     */
    @Test
    public void aFileWithNoTypeSaysNothing() {
        assertNull(kindOf("package com.example;\n"));
        assertNull(kindOf("/** Nothing here yet. */\n"));
        assertNull(kindOf(""));
    }

    /** <b>The name comes from the qualified name</b>, never left blank. @see ProjectSourceSymbols */
    @Test
    public void theSymbolCarriesItsName() {
        SymbolInfo found = declared("public class Thing { }\n");

        assertEquals("Thing", found.name());
        assertEquals("com.example", found.container());
    }
}
