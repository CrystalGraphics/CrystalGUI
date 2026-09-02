package com.crystalgui.widget.text;

import com.crystalgui.style.selector.Selector;
import com.crystalgui.ui.dom.UINodeRegistry;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UINode;
import org.junit.Test;

import static org.junit.Assert.*;

public class SelectorTest extends UiDocumentTestBase {

    @Test
    public void universalMatchesAnything() {
        assertTrue(Selector.parse("*").matches(new UINode()));
    }

    @Test
    public void idSelectorMatchesOnlyExactId() {
        UINode el = new UINode().setId("main");
        assertTrue(Selector.parse("#main").matches(el));
        assertFalse(Selector.parse("#other").matches(el));
    }

    @Test
    public void classSelectorRequiresAllClassesPresent() {
        UINode el = new UINode();
        el.addClass("foo").addClass("bar");

        assertTrue(Selector.parse(".foo").matches(el));
        assertTrue(Selector.parse(".foo.bar").matches(el));
        assertFalse(Selector.parse(".foo.baz").matches(el));
    }

    /**
     * The plain div's type selector is {@code element}, from {@link com.crystalgui.ui.UINodeRegistry}.
     *
     * <p>It used to be {@code uielement} — the lowercased Java class name — because {@code tagName()}
     * derived from the class rather than the registry. That leaked an implementation detail into the
     * CSS surface, and it was the same mechanism that made {@code UIText} report {@code uitext} while
     * registering as {@code text}, so a {@code text { }} rule never matched anything.</p>
     */
    @Test
    public void typeSelectorMatchesTheRegisteredTagName() {
        UINode el = new UINode();
        assertTrue(Selector.parse("element").matches(el));
        assertFalse("the Java class name must no longer be a selectable tag",
                Selector.parse("uielement").matches(el));
        assertFalse(Selector.parse("button").matches(el));
    }

    @Test
    public void pseudoClassDelegatesToRealElementState() {
        UINode el = new UINode();
        assertFalse(Selector.parse(":hover").matches(el));

        el.setHovered(true);
        assertTrue(Selector.parse(":hover").matches(el));

        el.setHovered(false);
        assertFalse(Selector.parse(":hover").matches(el));
    }

    @Test
    public void unknownPseudoClassFailsAtParseTime() {
        assertThrows(IllegalArgumentException.class, () -> Selector.parse(":not-a-real-pseudo-class"));
    }

    @Test
    public void compoundSelectorRequiresAllPartsToMatch() {
        UINode el = new UINode().setId("submit");
        el.addClass("primary");
        el.setEnabled(false);

        assertTrue(Selector.parse("#submit.primary:disabled").matches(el));
        assertFalse(Selector.parse("#submit.primary:enabled").matches(el));
        assertFalse(Selector.parse("#wrong.primary:disabled").matches(el));
    }

    @Test
    public void descendantCombinatorMatchesAnyAncestorDepth() {
        UINode root = new UINode();
        root.addClass("panel");
        UINode mid = new UINode();
        UINode leaf = new UINode();
        leaf.addClass("button");

        root.append(mid);
        mid.append(leaf);

        assertTrue(Selector.parse(".panel .button").matches(leaf));
    }

    @Test
    public void childCombinatorRequiresImmediateParent() {
        UINode root = new UINode();
        root.addClass("panel");
        UINode mid = new UINode();
        UINode grandchildButton = new UINode();
        grandchildButton.addClass("button");
        UINode directChildButton = new UINode();
        directChildButton.addClass("button");

        root.append(mid);
        mid.append(grandchildButton);
        root.append(directChildButton);

        var selector = Selector.parse(".panel > .button");
        assertFalse("grandchild should not match a direct-child combinator", selector.matches(grandchildButton));
        assertTrue("direct child should match", selector.matches(directChildButton));
    }

    @Test
    public void specificityFollowsCssWeights() {
        assertEquals(0, Selector.parse("*").specificity());
        assertEquals(1, Selector.parse("uielement").specificity());
        assertEquals(10, Selector.parse(".foo").specificity());
        assertEquals(10, Selector.parse(":hover").specificity());
        assertEquals(100, Selector.parse("#id").specificity());
        assertEquals(111, Selector.parse("uielement#id.foo").specificity());
    }
}
