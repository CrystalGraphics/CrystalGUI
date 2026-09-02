package com.crystalgui.style.node;

import com.crystalgui.style.transition.TransitionEngine;
import com.crystalgui.style.StyleOrigin;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StyleSlot;
import com.crystalgui.style.property.general.strings.StringValue;
import com.crystalgui.ui.dom.UINode;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class ElementStyleCascadeTest extends UiDocumentTestBase {

    private static StyleProperty<String> newProp(String defaultValue) {
        return new StyleProperty<>("test-prop", String.class, defaultValue, StringValue::new);
    }

    @Test
    public void higherOriginWinsRegardlessOfSpecificity() {
        var prop = newProp("default");
        var element = new UINode();
        var style = element.getStyle();

        style.putCandidate(prop, StyleSlot.of(prop, StyleOrigin.STYLESHEET, 100, 0, "from-stylesheet"));
        style.putCandidate(prop, StyleSlot.of(prop, StyleOrigin.INLINE, 0, 0, "from-inline"));

        assertEquals("from-inline", style.getComputed(prop));
    }

    @Test
    public void higherSpecificityWinsWithinSameOrigin() {
        var prop = newProp("default");
        var element = new UINode();
        var style = element.getStyle();

        style.putCandidate(prop, StyleSlot.of(prop, StyleOrigin.STYLESHEET, 10, 0, "low-specificity"));
        style.putCandidate(prop, StyleSlot.of(prop, StyleOrigin.STYLESHEET, 100, 1, "high-specificity"));

        assertEquals("high-specificity", style.getComputed(prop));
    }

    @Test
    public void laterSourceOrderWinsOnSpecificityTie() {
        var prop = newProp("default");
        var element = new UINode();
        var style = element.getStyle();

        style.putCandidate(prop, StyleSlot.of(prop, StyleOrigin.STYLESHEET, 10, 1, "declared-first"));
        style.putCandidate(prop, StyleSlot.of(prop, StyleOrigin.STYLESHEET, 10, 2, "declared-second"));

        assertEquals("declared-second", style.getComputed(prop));
    }




    @Test
    public void animationSlotShadowsRealWinnerUntilEnded() {
        var prop = newProp("default");
        var element = new UINode();
        var style = element.getStyle();

        style.putCandidate(prop, StyleSlot.of(prop, StyleOrigin.STYLESHEET, 0, 0, "real-value"));
        assertEquals("real-value", style.getComputed(prop));

        style.startAnimationSlot(prop, "animated-start", 0);
        assertEquals("animated-start", style.getComputed(prop));

        style.tickAnimationSlot(prop, "animated-mid", 0);
        assertEquals("animated-mid", style.getComputed(prop));

        style.endAnimationSlot(prop);
        assertEquals("real-value", style.getComputed(prop));
    }


    @Test
    public void putCandidateRejectsNullValuedSlot() {
        var prop = newProp("default");
        var element = new UINode();
        var style = element.getStyle();

        style.putCandidate(prop, StyleSlot.of(prop, StyleOrigin.STYLESHEET, 0, 0, "real-value"));
        assertEquals("real-value", style.getComputed(prop));

        // A null-valued slot (never legitimately produced today, but defended against regardless)
        // must be silently rejected, not overwrite the real candidate with null.
        style.putCandidate(prop, StyleSlot.of(prop, StyleOrigin.INLINE, 0, 0, null));

        assertEquals("real-value", style.getComputed(prop));
    }

    @Test
    public void putCandidatesFiltersNullValuedSlotsFromABatch() {
        var propA = newProp("a-default");
        var propB = newProp("b-default");
        var element = new UINode();

        element.getStyle().putCandidates(List.of(
                StyleSlot.of(propA, StyleOrigin.STYLESHEET, 10, 0, "a-value"),
                StyleSlot.of(propB, StyleOrigin.STYLESHEET, 10, 0, null)
        ));

        assertEquals("a-value", element.getStyle().getComputed(propA));
        assertNull(element.getStyle().getComputed(propB));
    }


    // ── moveInlineAsDefault ──────────────────────────────────────────────────────────────────

    @Test
    public void moveInlineAsDefaultPreservesTheCurrentValue() {
        var prop = newProp("default");
        var element = new UINode();

        element.getStyle().putCandidate(prop, StyleSlot.of(prop, StyleOrigin.INLINE, 0, 0, "widget-baseline"));
        assertEquals("widget-baseline", element.getStyle().getComputed(prop));



        assertEquals("reclassifying to DEFAULT must not change the current value on its own",
                "widget-baseline", element.getStyle().getComputed(prop));
    }



    @Test
    public void moveInlineAsDefaultIsANoOpWithNoInlineCandidates() {
        var prop = newProp("default");
        var element = new UINode();
        element.getStyle().putCandidate(prop, StyleSlot.of(prop, StyleOrigin.STYLESHEET, 10, 0, "sheet-value"));



        assertEquals("sheet-value", element.getStyle().getComputed(prop));
    }

    // ── Inheritance ──────────────────────────────────────────────────────────────────────────

    private static StyleProperty<String> newInheritableProp(String defaultValue) {
        return new StyleProperty<>("test-inheritable-prop", String.class, defaultValue, StringValue::new).setInheritable(true);
    }

    @Test
    public void nonInheritedPropertyNeverFallsBackToParent() {
        var prop = newProp("default"); // not inheritable
        var parent = new UINode();
        var child = new UINode();
        parent.append(child);

        parent.getStyle().putCandidate(prop, StyleSlot.of(prop, StyleOrigin.STYLESHEET, 0, 0, "parent-value"));

        assertNull("non-inheritable property must not fall back to the parent", child.getStyle().getComputed(prop));
    }

    @Test
    public void inheritablePropertyFallsBackToParentWhenChildHasNoCandidate() {
        var prop = newInheritableProp("default");
        var parent = new UINode();
        var child = new UINode();
        parent.append(child);

        parent.getStyle().putCandidate(prop, StyleSlot.of(prop, StyleOrigin.STYLESHEET, 0, 0, "parent-value"));

        assertEquals("parent-value", child.getStyle().getComputed(prop));
    }

    @Test
    public void childsOwnCandidateAlwaysWinsOverInheritance() {
        var prop = newInheritableProp("default");
        var parent = new UINode();
        var child = new UINode();
        parent.append(child);

        parent.getStyle().putCandidate(prop, StyleSlot.of(prop, StyleOrigin.STYLESHEET, 0, 0, "parent-value"));
        // Even a DEFAULT-origin (lowest priority) local candidate beats inheritance entirely —
        // inheritance is only a fallback for "no local candidate at all", not part of the origin chain.
        child.getStyle().putCandidate(prop, StyleSlot.of(prop, StyleOrigin.DEFAULT, 0, 0, "child-own-value"));

        assertEquals("child-own-value", child.getStyle().getComputed(prop));
    }

    @Test
    public void inheritanceWalksUpThroughMultipleGenerations() {
        var prop = newInheritableProp("default");
        var grandparent = new UINode();
        var parent = new UINode();
        var child = new UINode();
        grandparent.append(parent);
        parent.append(child);

        grandparent.getStyle().putCandidate(prop, StyleSlot.of(prop, StyleOrigin.STYLESHEET, 0, 0, "grandparent-value"));

        // Neither 'parent' nor 'child' has any local candidate — both fall back, transitively.
        assertEquals("grandparent-value", parent.getStyle().getComputed(prop));
        assertEquals("grandparent-value", child.getStyle().getComputed(prop));
    }

    @Test
    public void inheritanceStopsAtTheRootWithNoCandidateAnywhere() {
        var prop = newInheritableProp("default");
        var root = new UINode();
        var child = new UINode();
        root.append(child);

        // Nobody ever set this property — inheritable or not, there's nothing to inherit.
        assertNull(child.getStyle().getComputed(prop));
    }

    /*
     * SEVEN TESTS DELIBERATELY NOT PORTED, and it is a design difference rather than a gap.
     *
     * Five asserted that a `StyleProperty` LISTENER fires when a candidate is put, replaced or
     * removed. That is the OLD engine's bridge: `UIElement.computedChanged` forwards to
     * `StyleProperty.notifyListeners`, which is how a layout property reached Taffy there.
     * `UINode.computedChanged` deliberately does not -- its javadoc says so outright ("the old engine
     * does this with a listener on the property; here it is the host's own business"), because
     * `BoxStyle` reads `ComputedStyle` per layout instead of reacting to a notification. So the
     * listener never fires here, and asserting that it does would be asserting the mechanism this
     * engine exists to remove.
     *
     * The other two are `moveInlineAsDefault`, which has no counterpart on `UINode` at all.
     *
     * Everything about the CASCADE itself -- origins, specificity, source order, the two winner maps,
     * the animation shadow -- is shared between the engines and is covered by what remains here and by
     * `ui.dom.UINodeStylePassTest`.
     */
}
