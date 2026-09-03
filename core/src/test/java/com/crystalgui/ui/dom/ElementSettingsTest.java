package com.crystalgui.ui.dom;

import com.crystalgui.core.settings.Setting;
import com.crystalgui.core.settings.SettingsLayer;
import com.crystalgui.testsupport.UiDocumentTestBase;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * P6.1.13 — settings on <b>every element</b>, resolved up the tree.
 *
 * <h3>Why this is not in {@code headlessTest} with the rest of the gear</h3>
 * <p>{@code UIElement} holds fields of CrystalGraphics types, and a field descriptor resolves at class
 * load rather than in a method body — so the class is unloadable where CG core is deliberately absent.
 * The <em>model</em> half of settings is fully headless and tested there; this file is only the part that
 * needs a real element.</p>
 */
public class ElementSettingsTest extends UiDocumentTestBase {

    private static final Setting<Integer> INDENT = Setting.integer("editor.indent", "Indent", 4);
    private static final Setting<Boolean> WRAP = Setting.bool("editor.wrap", "Wrap", false);

    /** An element that has never been asked about settings must not be carrying a store. */
    @Test
    public void anElementWithNoSettingsAllocatesNothing() {
        UIElement element = new UIElement();
        assertNull(element.settingsOrNull());
        assertEquals("and still answers, from the declaration", Integer.valueOf(4), element.resolve(INDENT));
        assertNull("asking must not have created one", element.settingsOrNull());
    }

    /**
     * <b>The walk must not allocate a store on every ancestor it passes.</b>
     *
     * <p>The trap {@code settingsOrNull} exists for: resolving through {@code settings()} would turn a
     * read into a write, permanently attaching an empty store to every element between the reader and
     * whoever answered. {@code keymapOrNull} exists for the identical reason, which is what makes this
     * worth pinning rather than trusting.</p>
     */
    @Test
    public void resolvingDoesNotAllocateStoresAlongTheWay() {
        UIElement root = new UIElement();
        UIElement middle = new UIElement();
        UIElement leaf = new UIElement();
        root.append(middle);
        middle.append(leaf);

        root.settings().set(SettingsLayer.USER, INDENT, 2);
        assertEquals(Integer.valueOf(2), leaf.resolve(INDENT));

        assertNull("the leaf only read", leaf.settingsOrNull());
        assertNull("and nothing was hung on the element in between", middle.settingsOrNull());
    }

    /** The nearest ancestor with an answer wins — the whole of the scoping rule. */
    @Test
    public void theNearestScopeWins() {
        UIElement root = new UIElement();
        UIElement panel = new UIElement();
        UIElement field = new UIElement();
        root.append(panel);
        panel.append(field);

        root.settings().set(SettingsLayer.USER, INDENT, 2);
        assertEquals(Integer.valueOf(2), field.resolve(INDENT));

        panel.settings().set(SettingsLayer.USER, INDENT, 8);
        assertEquals("an inner scope overrides", Integer.valueOf(8), field.resolve(INDENT));
        assertSame(panel, field.scopeDefining("editor.indent"));

        assertEquals("and the outer one is untouched by it", Integer.valueOf(2), root.resolve(INDENT));
    }

    /**
     * A detached element resolves against itself alone.
     *
     * <p>Worth stating because the whole mechanism is the parent chain, and "not yet added to anything"
     * is the normal state of an element being built — it must answer rather than fail.</p>
     */
    @Test
    public void aDetachedElementStillResolves() {
        UIElement orphan = new UIElement();
        assertEquals(Integer.valueOf(4), orphan.resolve(INDENT));
        orphan.settings().set(SettingsLayer.MEMORY, INDENT, 16);
        assertEquals(Integer.valueOf(16), orphan.resolve(INDENT));
    }

    /** Reparenting changes what an element inherits, with nothing to invalidate. */
    @Test
    public void reparentingChangesWhatIsInherited() {
        UIElement left = new UIElement();
        UIElement right = new UIElement();
        UIElement child = new UIElement();
        left.settings().set(SettingsLayer.USER, WRAP, true);
        right.settings().set(SettingsLayer.USER, WRAP, false);

        left.append(child);
        assertTrue(child.resolve(WRAP));

        child.removeSelf();
        right.append(child);
        assertFalse("the walk is live, so there is no cache to go stale", child.resolve(WRAP));
    }
}
