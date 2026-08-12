package com.crystalgui.style;

import com.crystalgui.style.selector.CompoundSelector;
import com.crystalgui.style.sheet.StyleSheet;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

/**
 * A {@code ::highlight()} argument may contain dots, and the user-agent sheet must survive parsing.
 *
 * <h3>What broke, and why nothing caught it</h3>
 * <p>The selector lexer spelled a pseudo-element as {@code ::[\w-]+(\([\w-]+\))?}. A highlight name is
 * not a CSS identifier — it is a tree-sitter capture name, and those are dotted by convention
 * ({@code function.builtin}, {@code punctuation.delimiter}). Every highlight name in the sheet had been a
 * bare word until the syntax vocabulary landed, so the gap had never been reachable.</p>
 *
 * <p>The failure was not a dropped rule. The argument did not match, {@code ::highlight} lexed with no
 * argument, and the {@code Part} constructor threw — <b>taking the whole sheet with it</b>. In the UA
 * sheet that means nothing has geometry, so the harness showed a black window with a perfectly healthy
 * render loop: the debugger's main thread was asleep in {@code Display.sync}, which is exactly where an
 * idle frame limiter sits, so it read as a renderer hang rather than a regex.</p>
 *
 * <p>The governance tests did not catch it because they are plain-text analysis of the css FILES and
 * never ask the engine to parse them. That is the right design for what they check and the reason this
 * test exists beside them: <b>somebody has to actually load the sheet.</b></p>
 */
public class DottedHighlightSelectorTest {

    @Test
    public void aHighlightArgumentMayContainDots() {
        assertNotNull(CompoundSelector.parse("::highlight(keyword.control)"));
        assertNotNull(CompoundSelector.parse("texteditor text::highlight(punctuation.delimiter)".split(" ")[1]));
        assertNotNull(CompoundSelector.parse("::highlight(comment.doc)"));
        // The undotted form must keep working, and a dot OUTSIDE the parentheses must still be a class.
        assertNotNull(CompoundSelector.parse("::highlight(keyword)"));
        assertNotNull(CompoundSelector.parse("text.code::highlight(string.escape)"));
    }

    /**
     * <b>The shipped user-agent sheet parses.</b> The cheapest possible smoke test for the one file whose
     * failure has no visible cause — every widget in the engine gets its geometry from it, so a sheet that
     * throws on load is not a styling bug, it is a blank application.
     */
    @Test
    public void theUserAgentSheetLoads() {
        assertFalse("StyleSheet.DEFAULT parsed to nothing", StyleSheet.DEFAULT.getRules().isEmpty());
    }
}
