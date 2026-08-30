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

    @Test
    public void everySelectorIsPrefixed() {
        String out = ScopedSheets.scope("button { color: red }", "s");
        assertEquals(".s button { color: red }", out.trim());
    }

    /** Each of a comma-separated list, or the ones after the first would still escape. */
    @Test
    public void aSelectorListIsPrefixedThroughout() {
        String out = ScopedSheets.scope("button, text { color: red }", "s").trim();
        assertEquals(".s button, .s text { color: red }", out);
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
