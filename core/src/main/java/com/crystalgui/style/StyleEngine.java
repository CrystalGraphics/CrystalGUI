package com.crystalgui.style;

import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StyleSlot;
import com.crystalgui.style.sheet.StyleRule;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.transition.TransitionEngine;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import lombok.Getter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One instance per {@link UIWindow}. Owns the registered stylesheets, the dirty-rematch queue
 * (selector re-matching on id/class/pseudo-class change), and — via {@link TransitionEngine} — the
 * active state-transition driver. Driven once per frame from {@link UIWindow#paintFrame()}, before
 * layout.
 */
public final class StyleEngine {
    private final UIWindow window;

    @Getter
    private final TransitionEngine transitionEngine = new TransitionEngine();

    private final List<StyleSheet> sheets = new ArrayList<>();
    private final Set<UIElement> dirtyMatch = new HashSet<>();

    /** Exactly the STYLESHEET/IMPORTANT-origin slots this engine last applied to each element — kept
     * so a re-match can remove precisely what it added, without guessing by origin (an IMPORTANT-
     * origin slot might equally have come from user Java code via StyleGroup.setImportant()). */
    private final Map<UIElement, List<StyleSlot<?>>> appliedByElement = new HashMap<>();

    public StyleEngine(UIWindow window) {
        this.window = window;
    }

    public void addStylesheet(StyleSheet sheet) {
        sheets.add(sheet);
        dirtyMatch.addAll(window.getElements());
    }

    public void removeStylesheet(StyleSheet sheet) {
        if (sheets.remove(sheet)) {
            dirtyMatch.addAll(window.getElements());
        }
    }

    /** Called from {@link UIElement#invalidateStyleMatch()} — marks an element for re-matching. */
    public void markDirty(UIElement element) {
        dirtyMatch.add(element);
    }

    /** Called when an element leaves the tree — stops tracking it for matching and animation. */
    public void onElementDetached(UIElement element) {
        dirtyMatch.remove(element);
        appliedByElement.remove(element);
        transitionEngine.onElementDetached(element);
    }

    /** Called once per frame from {@link UIWindow#paintFrame()}, before layout is recomputed. */
    public void calculateStyle(float deltaSeconds) {
        drainDirtyMatch();
        transitionEngine.tick(deltaSeconds);
    }

    private void drainDirtyMatch() {
        if (dirtyMatch.isEmpty()) return;
        // Snapshot-and-clear before iterating: a reentrant invalidateStyleMatch() call during
        // matching (plausible — style-change listeners already mutate state synchronously) must
        // land in the freshly-cleared set and get picked up next frame, not throw a
        // ConcurrentModificationException or recurse within this same pass.
        var batch = new ArrayList<>(dirtyMatch);
        dirtyMatch.clear();
        for (var element : batch) {
            rematch(element);
        }
    }

    private void rematch(UIElement element) {
        var previouslyApplied = appliedByElement.get(element);
        if (previouslyApplied != null && !previouslyApplied.isEmpty()) {
            element.getStyle().removeCandidates(previouslyApplied::contains);
        }

        List<StyleSlot<?>> newSlots = new ArrayList<>();
        for (var sheet : sheets) {
            for (var rule : sheet.candidatesFor(element)) {
                if (!rule.selector().matches(element)) continue;
                int specificity = rule.selector().specificity();
                for (var decl : rule.declarations()) {
                    var origin = decl.important() ? StyleOrigin.IMPORTANT : StyleOrigin.STYLESHEET;
                    newSlots.add(toSlot(decl, origin, specificity, rule.sourceOrder()));
                }
            }
        }

        if (newSlots.isEmpty()) {
            appliedByElement.remove(element);
        } else {
            appliedByElement.put(element, newSlots);
            element.getStyle().putCandidates(newSlots);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> StyleSlot<T> toSlot(StyleRule.Declaration decl, StyleOrigin origin, int specificity, int sourceOrder) {
        var property = (StyleProperty<T>) decl.property();
        T value = (T) decl.value().compute();
        return StyleSlot.of(property, origin, specificity, sourceOrder, value);
    }

    public List<StyleSheet> getSheets() {
        return List.copyOf(sheets);
    }
}
