package com.crystalgui.ui;

import com.crystalgui.style.HighlightStyle;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.text.syntax.LanguageRegistry;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.editor.TextEditor;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * <b>The user-agent sheet colours what the shipped tokenizers emit.</b>
 *
 * <p>The failure this exists for is the one the bracket rule in {@code default.css} already records, and
 * which caught us a second time: a tokenizer <em>publishes</em> its ranges whether or not anything styles
 * them. So an editor given a language tokenized perfectly and looked <b>identical</b> — every keyword
 * resolved, was handed to the cascade, and rendered in the default colour. Nothing failed, nothing logged,
 * and "set the language" appeared to do nothing at all.</p>
 *
 * <p>It shipped that way because the only place syntax colours existed was {@code gallery.css}, scoped to
 * the gallery's own {@code .ed} class — so the one scene anybody looked at worked, and every other editor
 * in the engine was monochrome.</p>
 */
public class SyntaxColoursTest extends UiTestBase {

    private UIWindow window;
    private TextEditor editor;

    private void build(String fileName, String text) {
        editor = new TextEditor(text);
        editor.layout(l -> l.width(320).height(160));
        editor.generalStyle(g -> g.fontSize(8f).lineHeight(1.25f));

        LanguageRegistry.Entry entry = LanguageRegistry.forFileName(fileName);
        editor.setLanguage(entry.language());
        editor.setTokenizer(entry.newTokenizer());

        UIElement root = new UIElement().layout(l -> l.width(320).height(200));
        root.addChild(editor);
        window = new UIWindow(Ui.of(root));
        // THE USER-AGENT SHEET ONLY. No theme, because the point is that a bare editor colours its code —
        // if this needed ore.css the feature would still be missing from every embedder that ships neither.
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(640, 400);
        for (int i = 0; i < 4; i++) window.updateWithoutPainting();
    }

    /** The line elements the editor has realised — where highlight ranges actually live. */
    private List<UIText> lines() {
        List<UIText> found = new ArrayList<>();
        for (UIElement child : editor.getChildren()) collect(child, found);
        return found;
    }

    private static void collect(UIElement element, List<UIText> out) {
        // Every UIText under a __line__ row. The ranges live on the UIText the row owns, not on the row,
        // which is the same distinction gallery.css records: a HighlightRegistry belongs to a UIText.
        if (element instanceof UIText text) out.add(text);
        for (UIElement child : element.getChildren()) collect(child, out);
    }

    /** A {@code UIText} carrying at least one range under {@code name}, or null. */
    private UIText lineWithHighlight(String name) {
        for (UIText line : lines()) {
            if (!line.highlights().get(name).isEmpty()) return line;
        }
        return null;
    }

    /**
     * <b>The load-bearing assertion.</b> A published highlight resolves to a real colour.
     *
     * <p>Two halves, and the second is the one that was missing: the tokenizer must publish a
     * {@code keyword} range <em>and</em> the cascade must have a rule for it. Asserting only the first
     * passes against a completely monochrome editor.</p>
     */
    @Test
    public void aJavaKeywordIsBothPublishedAndColouredByTheUserAgentSheet() {
        build("Main.java", "public class Main {\n    int x = 1;\n}\n");

        UIText line = lineWithHighlight("keyword");
        assertNotNull("no keyword ranges published — the tokenizer did not run", line);

        HighlightStyle style = window.getStyleEngine().highlightStyle(line, "keyword");
        assertFalse("the tokenizer published 'keyword' and no rule styles it — the editor is "
                + "monochrome and nothing reports it", style.isEmpty());
    }

    /** Every name the shipped tokenizers emit, so adding a colour for five of six cannot pass. */
    @Test
    public void everyShippedTokenNameHasAColour() {
        build("Main.java",
                "// a comment\npublic class Main {\n    int x = 1;\n    String s = \"hi\";\n"
                        + "    void go() { go(); }\n}\n");

        for (String name : new String[]{"keyword", "type", "string", "number", "comment", "function"}) {
            UIText line = lineWithHighlight(name);
            assertNotNull("nothing published a '" + name + "' range", line);
            HighlightStyle style = window.getStyleEngine().highlightStyle(line, name);
            assertFalse("'" + name + "' is published but unstyled", style.isEmpty());
        }
    }

    /** GLSL too, and through the registry — the extension is what chose the tokenizer. */
    @Test
    public void aGlslFileIsColouredThroughTheRegistry() {
        build("gui_quad.frag",
                "#version 330 core\n// tint\nuniform vec4 _Color;\nvoid main() {\n    float a = 1;\n}\n");

        UIText line = lineWithHighlight("keyword");
        assertNotNull("no keyword ranges in a .frag — the registry did not resolve GLSL", line);
        assertFalse(window.getStyleEngine().highlightStyle(line, "keyword").isEmpty());
    }

    /** Distinct colours, or the highlighting is present and useless. */
    @Test
    public void keywordsAndCommentsAreDifferentColours() {
        build("Main.java", "// a comment\npublic class Main {\n}\n");

        UIText keywordLine = lineWithHighlight("keyword");
        UIText commentLine = lineWithHighlight("comment");
        assertNotNull(keywordLine);
        assertNotNull(commentLine);

        int keyword = window.getStyleEngine().highlightStyle(keywordLine, "keyword").color(0);
        int comment = window.getStyleEngine().highlightStyle(commentLine, "comment").color(0);
        assertNotEquals("keywords and comments resolve to the same colour", keyword, comment);
    }

    /** A plain file is deliberately uncoloured — PLAIN/NONE is the honest answer, not a failure. */
    @Test
    public void aPlainTextFilePublishesNothing() {
        build("notes.txt", "public class Main {\n}\n");
        assertTrue(lines().size() > 0);
        assertNotNull("expected realised lines", lines().get(0));
        for (UIText line : lines()) {
            assertTrue("a .txt file must not be tokenized", line.highlights().get("keyword").isEmpty());
        }
    }
}
