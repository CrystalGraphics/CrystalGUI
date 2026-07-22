package com.crystalgui.style;

import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StyleSlot;
import com.crystalgui.style.property.general.strings.StringValue;
import com.crystalgui.ui.UIElement;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class ElementStyleCascadeTest {

    private static StyleProperty<String> newProp(String defaultValue) {
        return new StyleProperty<>("test-prop", String.class, defaultValue, StringValue::new);
    }

    @Test
    public void higherOriginWinsRegardlessOfSpecificity() {
        var prop = newProp("default");
        var element = new UIElement();
        var style = element.getStyle();

        style.putCandidate(prop, StyleSlot.of(prop, StyleOrigin.STYLESHEET, 100, 0, "from-stylesheet"));
        style.putCandidate(prop, StyleSlot.of(prop, StyleOrigin.INLINE, 0, 0, "from-inline"));

        assertEquals("from-inline", style.getComputed(prop));
    }

    @Test
    public void higherSpecificityWinsWithinSameOrigin() {
        var prop = newProp("default");
        var element = new UIElement();
        var style = element.getStyle();

        style.putCandidate(prop, StyleSlot.of(prop, StyleOrigin.STYLESHEET, 10, 0, "low-specificity"));
        style.putCandidate(prop, StyleSlot.of(prop, StyleOrigin.STYLESHEET, 100, 1, "high-specificity"));

        assertEquals("high-specificity", style.getComputed(prop));
    }

    @Test
    public void laterSourceOrderWinsOnSpecificityTie() {
        var prop = newProp("default");
        var element = new UIElement();
        var style = element.getStyle();

        style.putCandidate(prop, StyleSlot.of(prop, StyleOrigin.STYLESHEET, 10, 1, "declared-first"));
        style.putCandidate(prop, StyleSlot.of(prop, StyleOrigin.STYLESHEET, 10, 2, "declared-second"));

        assertEquals("declared-second", style.getComputed(prop));
    }

    @Test
    public void putCandidateFiresListenerWithRealOldAndNewValues() {
        var prop = newProp("default");
        var element = new UIElement();

        List<String> oldSeen = new ArrayList<>();
        List<String> newSeen = new ArrayList<>();
        prop.addListener((el, p, oldVal, newVal) -> {
            oldSeen.add(oldVal);
            newSeen.add(newVal);
        });

        element.getStyle().putCandidate(prop, StyleSlot.of(prop, StyleOrigin.INLINE, 0, 0, "first"));
        element.getStyle().putCandidate(prop, StyleSlot.of(prop, StyleOrigin.IMPORTANT, 0, 0, "second"));

        assertEquals(List.of("first", "second"), newSeen);
        assertNull(oldSeen.get(0)); // never resolved before -> real "no prior value"
        assertEquals("first", oldSeen.get(1)); // second call's old value is the real prior winner
    }

    @Test
    public void removeCandidatesFallsBackAndNotifiesRealDiff() {
        var prop = newProp("default");
        var element = new UIElement();
        var style = element.getStyle();

        List<String> newSeen = new ArrayList<>();
        prop.addListener((el, p, oldVal, newVal) -> newSeen.add(newVal));

        style.putCandidate(prop, StyleSlot.of(prop, StyleOrigin.STYLESHEET, 0, 0, "sheet-value"));
        style.putCandidate(prop, StyleSlot.of(prop, StyleOrigin.INLINE, 0, 0, "inline-value"));
        assertEquals("inline-value", style.getComputed(prop));

        style.removeCandidates(slot -> slot.origin() == StyleOrigin.INLINE);

        assertEquals("sheet-value", style.getComputed(prop));
        assertEquals(List.of("sheet-value", "inline-value", "sheet-value"), newSeen);
    }

    @Test
    public void animationSlotShadowsRealWinnerUntilEnded() {
        var prop = newProp("default");
        var element = new UIElement();
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
    public void bulkPutCandidatesUpdatesComputedSlotsAndNotifies() {
        var propA = newProp("a-default");
        var propB = newProp("b-default");
        var element = new UIElement();

        List<String> aChanges = new ArrayList<>();
        List<String> bChanges = new ArrayList<>();
        propA.addListener((el, p, oldVal, newVal) -> aChanges.add(newVal));
        propB.addListener((el, p, oldVal, newVal) -> bChanges.add(newVal));

        element.getStyle().putCandidates(List.of(
                StyleSlot.of(propA, StyleOrigin.STYLESHEET, 10, 0, "a-value"),
                StyleSlot.of(propB, StyleOrigin.STYLESHEET, 10, 0, "b-value")
        ));

        assertEquals("a-value", element.getStyle().getComputed(propA));
        assertEquals("b-value", element.getStyle().getComputed(propB));
        assertEquals(List.of("a-value"), aChanges);
        assertEquals(List.of("b-value"), bChanges);
    }
}
