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
    public void realCascadeChangeIsDetectedEvenWhileAnAnimationShadowIsActive() {
        // The masking bug this guards against: computeCandidateSlot always prefers an ANIMATION
        // candidate (highest priority) once one exists. If diffing compared against the DISPLAYED
        // value (which includes that shadow), a real STYLESHEET/INLINE change happening while a
        // transition is in flight would always look like "no change" — silently defeating both
        // mid-flight retargeting and any listener (e.g. TaffyBridge) ever finding out. Diffing must
        // use the REAL (non-animated) winner instead, so this is detected regardless.
        var prop = newProp("default");
        var element = new UIElement();
        var style = element.getStyle();

        var oldSlot = StyleSlot.of(prop, StyleOrigin.STYLESHEET, 10, 0, "real-old");
        style.putCandidate(prop, oldSlot);
        assertEquals("real-old", style.getComputed(prop));

        // Shadow it with an active "animation" (as TransitionEngine.startAnimationSlot would).
        style.startAnimationSlot(prop, "mid-animation", 0);
        assertEquals("mid-animation", style.getComputed(prop)); // display shows the shadow

        List<String> oldSeen = new ArrayList<>();
        List<String> newSeen = new ArrayList<>();
        prop.addListener((el, p, oldVal, newVal) -> {
            oldSeen.add(oldVal);
            newSeen.add(newVal);
        });

        // The REAL underlying candidate changes while the animation is still shadowing it.
        var newSlot = StyleSlot.of(prop, StyleOrigin.STYLESHEET, 10, 1, "real-new");
        style.replaceCandidates(slot -> slot == oldSlot, List.of(newSlot));

        // Display still shows the (unrelated, still-ticking) animation shadow — unaffected.
        assertEquals("mid-animation", style.getComputed(prop));
        // But the diff must have seen the REAL change, not "no change" (the old masking bug).
        assertEquals(List.of("real-old"), oldSeen);
        assertEquals(List.of("real-new"), newSeen);
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
    public void replaceCandidatesDiffsDirectlyWithoutANullIntermediate() {
        // This is the exact shape StyleEngine.rematch() needs: swapping out one origin's candidates
        // for a fresh set (e.g. re-matching stylesheet rules on a class/pseudo-class change) must
        // read as ONE old->new diff, not remove-then-null-then-add. If it went through as two
        // separate mutations (removeCandidates() then putCandidates()), a transition-eligible
        // property would see (real -> null) then (null -> real) instead of (real -> real), and the
        // transition engine would correctly-but-wrongly decline to animate through the spurious null.
        var prop = newProp("default");
        var element = new UIElement();
        var style = element.getStyle();

        var oldSlot = StyleSlot.of(prop, StyleOrigin.STYLESHEET, 10, 0, "old-value");
        style.putCandidate(prop, oldSlot);
        assertEquals("old-value", style.getComputed(prop));

        List<String> oldSeen = new ArrayList<>();
        List<String> newSeen = new ArrayList<>();
        prop.addListener((el, p, oldVal, newVal) -> {
            oldSeen.add(oldVal);
            newSeen.add(newVal);
        });

        var newSlot = StyleSlot.of(prop, StyleOrigin.STYLESHEET, 20, 0, "new-value");
        style.replaceCandidates(slot -> slot == oldSlot, List.of(newSlot));

        assertEquals("new-value", style.getComputed(prop));
        assertEquals("exactly one notification, not a remove-then-add pair", List.of("new-value"), newSeen);
        assertEquals("the diff must see the real prior value, never an intermediate null",
                List.of("old-value"), oldSeen);
    }

    @Test
    public void putCandidateRejectsNullValuedSlot() {
        var prop = newProp("default");
        var element = new UIElement();
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
        var element = new UIElement();

        element.getStyle().putCandidates(List.of(
                StyleSlot.of(propA, StyleOrigin.STYLESHEET, 10, 0, "a-value"),
                StyleSlot.of(propB, StyleOrigin.STYLESHEET, 10, 0, null)
        ));

        assertEquals("a-value", element.getStyle().getComputed(propA));
        assertNull(element.getStyle().getComputed(propB));
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
