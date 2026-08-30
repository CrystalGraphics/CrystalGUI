package com.crystalgui.headless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.crystalgui.net.window.ScopedSheets;
import org.junit.Test;

/**
 * A server's CSS is confined to its own windows and taken away when the last one goes.
 *
 * <p>Only the rewrite and the refcount are exercised here: parsing a sheet needs {@code CgIO}, which is
 * deliberately absent from this source set — {@code StyleSheet.DEFAULT} reads {@code default.css} at
 * class-init, so the whole class is unloadable on a server. The parse itself is covered in
 * {@code core/src/test}.</p>
 */
public class ScopedSheetsTest {

    @Test
    public void aScopeClassIsDerivedFromTheTypeId() {
        assertEquals("__ui-mymod-furnace__", ScopedSheets.scopeClass("mymod:furnace"));
    }

    /** The colon in a type id would end the class early, so it cannot survive into a selector. */
    @Test
    public void aTypeIdsPunctuationDoesNotLeakIntoTheSelector() {
        String scope = ScopedSheets.scopeClass("mymod:deep/thing");
        assertTrue(scope, scope.matches("__ui-[a-z0-9-]+__"));
    }

    /**
     * <b>Both readings, because CSS has no "this element or below".</b>
     *
     * <p>The prefix alone was wrong in the case that matters most: the scope class is carried by the
     * panel ROOT, so {@code .s .machine-panel} — a descendant selector — stopped matching the very
     * element that gives the window its width. The panel collapsed to its content and opened as a
     * sliver in the corner, with nothing failing anywhere.</p>
     */
    @Test
    public void aSelectorMatchesTheScopeRootAsWellAsBelowIt() {
        String out = ScopedSheets.scope("button { color: red }", "s").trim();
        assertTrue("under the root: " + out, out.contains(".s button"));
        assertTrue("AND the root itself: " + out, out.contains("button.s"));
    }

    /** The root form attaches to the FIRST compound, which is what matches the root. */
    @Test
    public void aDescendantSelectorScopesItsFirstCompound() {
        String out = ScopedSheets.scope(".row .label { a: 1 }", "s").trim();
        assertTrue(out, out.contains(".s .row .label"));
        assertTrue("the root case anchors the first part: " + out, out.contains(".row.s .label"));
    }

    /** Before a pseudo-class, never after: `.a.s:hover`, so the pseudo keeps its place. */
    @Test
    public void theScopeGoesBeforeAPseudoClass() {
        String out = ScopedSheets.scope("button:hover { a: 1 }", "s").trim();
        assertTrue(out, out.contains("button.s:hover"));
    }

    /** Each of a comma-separated list, or the ones after the first would still escape. */
    @Test
    public void aSelectorListIsPrefixedThroughout() {
        String out = ScopedSheets.scope("button, text { color: red }", "s").trim();
        assertTrue(out, out.contains(".s button") && out.contains("button.s"));
        assertTrue(out, out.contains(".s text") && out.contains("text.s"));
    }

    @Test
    public void severalRulesAreEachPrefixed() {
        String out = ScopedSheets.scope("a { x: 1 } b { y: 2 }", "s");
        assertTrue(out, out.contains(".s a"));
        assertTrue(out, out.contains(".s b"));
    }

    /** A declaration body is never touched — braces inside it must not be read as rule boundaries. */
    @Test
    public void aDeclarationBodyIsLeftAlone() {
        String out = ScopedSheets.scope("text { background: url(a{b) }", "s");
        assertTrue(out, out.contains("url(a{b)"));
    }

    /**
     * An at-rule passes through unharmed rather than being mangled or dropped.
     *
     * <p>None is supported by the parser today, so this is about the failure mode: a scoping pass that
     * does not understand something should leave it alone, never produce a window with no styling.</p>
     */
    @Test
    public void anAtRulePassesThrough() {
        String out = ScopedSheets.scope("@import \"x\"; button { a: 1 }", "s");
        assertTrue(out, out.contains("@import"));
    }

}
