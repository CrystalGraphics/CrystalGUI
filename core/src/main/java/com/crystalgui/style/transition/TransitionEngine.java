package com.crystalgui.style.transition;

import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.ui.UIElement;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Drives CSS-{@code transition}-style state animations: shadows a property's cascade winner with an
 * interpolated ANIMATION-origin value (see {@link com.crystalgui.style.ElementStyle}) until the
 * transition completes, then lets the real winner take back over.
 */
public final class TransitionEngine {
    private final Map<UIElement, Map<StyleProperty<?>, ActiveTransition<?>>> active = new HashMap<>();

    /**
     * Offered first refusal on every transition-eligible computed-value change (see
     * {@code ElementStyle}'s cascade-diff step). Returns {@code true} if it accepted the change and
     * will animate it in over subsequent {@link #tick} calls, {@code false} if the caller should
     * apply {@code toValue} immediately instead.
     */
    public <T> boolean tryStart(UIElement element, StyleProperty<T> property, T fromValue, T toValue) {
        // Nothing meaningful to interpolate to/from "unset" — decline and let the caller apply the
        // new value (or lack of one) immediately, same as it does for a property's first-ever
        // resolution. Most interpolators (primitive-boxed ones via auto-unboxing, object ones via
        // direct dereference) would NPE on a null argument otherwise.
        if (fromValue == null || toValue == null) return false;

        TransitionSpec spec = findApplicableSpec(element, property);
        if (spec == null || spec.durationNanos() <= 0) return false;

        long now = System.nanoTime();
        T animateFrom = fromValue;

        var byProperty = active.get(element);
        if (byProperty != null) {
            @SuppressWarnings("unchecked")
            ActiveTransition<T> inFlight = (ActiveTransition<T>) byProperty.get(property);
            if (inFlight != null) {
                // Interrupt-and-retarget: animate from wherever it currently visually is, not from
                // the old resting value — matches real CSS transition behavior.
                animateFrom = inFlight.currentValue(now);
            }
        }

        var transition = new ActiveTransition<>(property, animateFrom, toValue, now, spec.delayNanos(), spec.durationNanos(), spec.easing());
        active.computeIfAbsent(element, e -> new HashMap<>()).put(property, transition);
        element.getStyle().startAnimationSlot(property, animateFrom, 0);
        return true;
    }

    @SuppressWarnings("unchecked")
    private <T> TransitionSpec findApplicableSpec(UIElement element, StyleProperty<T> property) {
        List<TransitionSpec> specs = element.getStyle().getComputed(StylePropertyRegistry.TRANSITION);
        if (specs == null || specs.isEmpty()) return null;
        TransitionSpec fallbackAll = null;
        for (var spec : specs) {
            if (spec.propertyNameOrAll().equals(property.name)) return spec;
            if (spec.propertyNameOrAll().equals(TransitionSpec.ALL)) fallbackAll = spec;
        }
        return fallbackAll;
    }

    /** Advances every active transition. Called once per frame. */
    public void tick(float deltaSeconds) {
        if (active.isEmpty()) return;
        long now = System.nanoTime();

        var elementIterator = active.entrySet().iterator();
        while (elementIterator.hasNext()) {
            var elementEntry = elementIterator.next();
            UIElement element = elementEntry.getKey();
            var byProperty = elementEntry.getValue();

            var propertyIterator = byProperty.entrySet().iterator();
            while (propertyIterator.hasNext()) {
                var entry = propertyIterator.next();
                tickOne(element, entry.getKey(), entry.getValue(), now);
                if (entry.getValue().isFinished(now)) {
                    propertyIterator.remove();
                }
            }
            if (byProperty.isEmpty()) elementIterator.remove();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void tickOne(UIElement element, StyleProperty<?> propertyRaw, ActiveTransition<?> transitionRaw, long now) {
        StyleProperty<T> property = (StyleProperty<T>) propertyRaw;
        ActiveTransition<T> transition = (ActiveTransition<T>) transitionRaw;

        T interpolated = transition.currentValue(now);
        element.getStyle().tickAnimationSlot(property, interpolated, 0);
        // 'fromValue' as the notified old value is an approximation (the real previous tick's value
        // isn't tracked separately) — harmless, since every consumer of this notification (e.g. the
        // TaffyBridge layout listeners) applies newVal unconditionally rather than diffing oldVal.
        property.notifyListeners(element, transition.fromValue(), interpolated);

        if (transition.isFinished(now)) {
            element.getStyle().endAnimationSlot(property);
        }
    }

    /** Drops any active transitions owned by an element that just left the tree. */
    public void onElementDetached(UIElement element) {
        active.remove(element);
    }
}
