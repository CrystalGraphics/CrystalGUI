package com.crystalgui.style.transition;

import com.crystalgui.style.StyleOrigin;
import com.crystalgui.style.property.StyleSlot;
import com.crystalgui.style.property.general.floats.FloatProperty;
import com.crystalgui.ui.UIElement;
import org.junit.Test;

import static org.junit.Assert.*;

public class TransitionEngineTest {

    @Test
    public void declinesWhenNoTransitionSpecCoversTheProperty() {
        var property = new FloatProperty("test-float-a", 0f);
        var element = new UIElement();
        var engine = new TransitionEngine();

        assertFalse(engine.tryStart(element, property, 0f, 100f));
    }

    @Test
    public void startsAndShadowsRealWinnerWhenApplicableSpecExists() {
        var property = new FloatProperty("test-float-b", 0f);
        var element = new UIElement();
        element.getStyle().getGeneralGroup().transition(property.name + " 10s"); // long, won't finish mid-test

        var engine = new TransitionEngine();
        assertTrue(engine.tryStart(element, property, 0f, 100f));

        // Immediately after starting, the shadowed value should be at (or extremely near) fromValue.
        assertEquals(0f, element.getStyle().getComputed(property), 5f);
    }

    @Test
    public void tickCompletesTinyDurationTransitionAndRestoresRealValue() throws InterruptedException {
        var property = new FloatProperty("test-float-c", 0f);
        var element = new UIElement();
        element.getStyle().putCandidate(property, StyleSlot.of(property, StyleOrigin.STYLESHEET, 0, 0, 100f));
        element.getStyle().getGeneralGroup().transition(property.name + " 1ms");

        var engine = new TransitionEngine();
        assertTrue(engine.tryStart(element, property, 0f, 100f));

        Thread.sleep(5); // comfortably longer than the 1ms transition duration
        engine.tick(0f);

        assertEquals(100f, element.getStyle().getComputed(property), 0.001f);
    }

    @Test
    public void retargetMidFlightAnimatesFromCurrentValueNotTheLiteralArgument() {
        var property = new FloatProperty("test-float-d", 0f);
        var element = new UIElement();
        element.getStyle().getGeneralGroup().transition(property.name + " 10s"); // won't finish mid-test

        var engine = new TransitionEngine();
        engine.tryStart(element, property, 0f, 100f);
        // Essentially no time has passed, so the in-flight value is still ~0. Pass an obviously-wrong
        // fromValue (999f) to prove the engine ignores it and uses the transition's live value instead.
        assertTrue(engine.tryStart(element, property, 999f, 50f));

        assertEquals(0f, element.getStyle().getComputed(property), 5f);
    }

    @Test
    public void allKeywordAppliesToAnyPropertyWithoutASpecificEntry() {
        var property = new FloatProperty("test-float-e", 0f);
        var element = new UIElement();
        element.getStyle().getGeneralGroup().transition("all 10s");

        var engine = new TransitionEngine();
        assertTrue(engine.tryStart(element, property, 0f, 100f));
    }

    @Test
    public void onElementDetachedDropsActiveTransitions() {
        var property = new FloatProperty("test-float-f", 0f);
        var element = new UIElement();
        element.getStyle().getGeneralGroup().transition(property.name + " 10s");

        var engine = new TransitionEngine();
        engine.tryStart(element, property, 0f, 100f);
        engine.onElementDetached(element);

        // Ticking after detach must not throw and must not keep animating the detached element.
        engine.tick(0f);
        assertEquals(0f, element.getStyle().getComputed(property), 5f); // unchanged since detach
    }
}
