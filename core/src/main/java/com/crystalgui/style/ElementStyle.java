package com.crystalgui.style;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.ui.UIElement;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StyleSlot;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Holds the styles for an element
 * Hands out the highest priority ones as values.
 */
public final class ElementStyle {
    @Getter
    public final UIElement element;

    @Getter
    public final TaffyBridge taffyBridge;

    @Getter
    public final LayoutGroup layoutGroup;
    @Getter
    public final GeneralGroup generalGroup;

    public final Map<StyleProperty<?>, List<StyleSlot<?>>> candidates = new HashMap<>();
    private final Map<StyleProperty<?>, StyleSlot<?>> computedSlots = new HashMap<>();

    public ElementStyle layout(Consumer<LayoutGroup> configurator) {
        configurator.accept(this.getLayoutGroup());
        return this;
    }

    public ElementStyle general(Consumer<LayoutGroup> configurator) {
        configurator.accept(this.getLayoutGroup());
        return this;
    }

    public ElementStyle(UIElement element) {
        this.element = element;
        this.taffyBridge = new TaffyBridge(this);
        this.layoutGroup = new LayoutGroup(this);
        this.generalGroup = new GeneralGroup(this);
    }

    /**
     * @implNote Rejects a null-valued slot rather than storing it — every known producer (Java-code
     * {@code StyleGroup.set()}, stylesheet application, the transition engine) already avoids
     * producing one, so this is a backstop against a future producer doing so by accident, not the
     * primary null-prevention mechanism. Don't remove it as "dead code."
     */
    public <T> void putCandidate(StyleProperty<T> p, StyleSlot<T> slot) {
        if (slot.value() == null) {
            CrystalGuiCore.LOGGER.warn("Refusing to add a null-valued candidate for '{}' (origin={})", p.name, slot.origin());
            return;
        }
        candidates.computeIfAbsent(p, k -> new ArrayList<>()).add(slot);
        resolveTouched(Set.of(p));
    }

    public <T> void replaceOrPutCandidate(StyleProperty<T> p, StyleSlot<T> slot) {
        var slots = candidates.get(p);
        if (slots != null) {
            var iterator = slots.iterator();
            while (iterator.hasNext()) {
                var existSlot = iterator.next();
                if (existSlot.typeEquals(slot)) {
                    if (existSlot.equals(slot)) return;
                    iterator.remove();
                    break;
                }
            }
        }
        putCandidate(p, slot);
    }
    /**
     * Bulk-applies a heterogeneous batch of slots (each may carry its own origin/specificity/
     * sourceOrder) — the path stylesheet rule matching uses, since different matched rules
     * contribute different cascade metadata for the same element in one re-match pass.
     */
    public void putCandidates(List<StyleSlot<?>> slots) {
        if (slots.isEmpty()) return;
        var touched = new LinkedHashSet<StyleProperty<?>>();
        for (var slot : slots) {
            if (slot.value() == null) {
                // Backstop — see putCandidate()'s @implNote.
                CrystalGuiCore.LOGGER.warn("Refusing to add a null-valued candidate for '{}' (origin={})",
                        slot.property().name, slot.origin());
                continue;
            }
            candidates.computeIfAbsent(slot.property(), k -> new ArrayList<>()).add(slot);
            touched.add(slot.property());
        }
        resolveTouched(touched);
    }

    /**
     * Atomically replaces every candidate matching {@code toRemove} with {@code newSlots}, in a
     * single {@link #resolveTouched} pass — critical for stylesheet re-matching, where removing the
     * stale candidates and adding the fresh ones as two separate calls (two separate
     * {@code resolveTouched} passes) would transiently resolve every touched property to {@code null}
     * in between. That spurious null intermediate defeats transitions: the transition engine sees
     * {@code fromValue == null} (the real old value having already been diffed away against the
     * transient null) and correctly declines to animate through it, so the real old→new change never
     * gets a chance to transition — it snaps instead. Doing both halves as one batch means
     * {@code resolveOne} only ever sees the real old value and the real new value.
     */
    public void replaceCandidates(Predicate<StyleSlot<?>> toRemove, List<StyleSlot<?>> newSlots) {
        var touched = new LinkedHashSet<StyleProperty<?>>();
        for (var entry : candidates.entrySet()) {
            if (entry.getValue().removeIf(toRemove)) {
                touched.add(entry.getKey());
            }
        }
        candidates.values().removeIf(List::isEmpty);

        for (var slot : newSlots) {
            if (slot.value() == null) {
                CrystalGuiCore.LOGGER.warn("Refusing to add a null-valued candidate for '{}' (origin={})",
                        slot.property().name, slot.origin());
                continue;
            }
            candidates.computeIfAbsent(slot.property(), k -> new ArrayList<>()).add(slot);
            touched.add(slot.property());
        }

        resolveTouched(touched);
    }

    public boolean containsCandidate(StyleProperty<?> property, Predicate<StyleSlot<?>> predicate) {
        var slots = candidates.get(property);
        if (slots == null || slots.isEmpty()) return false;
        return slots.stream().anyMatch(predicate);
    }

    public void removeCandidates(Predicate<StyleSlot<?>> predicate) {
        var touched = new LinkedHashSet<StyleProperty<?>>();
        for (var entry : candidates.entrySet()) {
            if (entry.getValue().removeIf(predicate)) {
                touched.add(entry.getKey());
            }
        }
        if (!touched.isEmpty()) {
            candidates.values().removeIf(List::isEmpty);
            resolveTouched(touched);
        }
    }

    public void removeCandidates(StyleProperty<?> property, Predicate<StyleSlot<?>> predicate) {
        var slots = candidates.get(property);
        if (slots == null || slots.isEmpty()) return;
        if (slots.removeIf(predicate)) {
            candidates.values().removeIf(List::isEmpty);
            resolveTouched(Set.of(property));
        }
    }

    public void clearCandidates() {
        if (candidates.isEmpty()) return;
        var touched = new LinkedHashSet<StyleProperty<?>>(candidates.keySet());
        candidates.clear();
        resolveTouched(touched);
    }

    /**
     * The single write path for every cascade mutation above. Phase 1 recomputes {@link #computedSlots}
     * for every touched property before anything is diffed, so a batch that sets both {@code transition}
     * and a transitionable property in the same call (e.g. one stylesheet rule) sees a fully-resolved
     * {@code transition} value regardless of {@code Map} iteration order. Phase 2 diffs old vs. new and
     * either notifies listeners directly or hands the change to the transition engine.
     */
    private void resolveTouched(Set<StyleProperty<?>> touched) {
        if (touched.isEmpty()) return;

        var oldValues = new HashMap<StyleProperty<?>, Object>();
        var wasResolved = new HashSet<StyleProperty<?>>();
        for (var p : touched) {
            if (computedSlots.containsKey(p)) wasResolved.add(p);
            oldValues.put(p, getComputed(p));
            computedSlots.put(p, computeCandidateSlot(p));
        }

        for (var p : touched) {
            resolveOne(p, oldValues.get(p), wasResolved.contains(p));
        }
        element.onStyleChanged();
    }

    /**
     * Diffs one already-resolved property. If the value actually changed and the property allows
     * transitions and this isn't the element's first-ever resolution of it, the transition engine
     * gets first refusal (it may shadow the value with an ANIMATION-origin slot instead of applying
     * it instantly); otherwise listeners are notified with the real old/new pair.
     */
    private <T> void resolveOne(StyleProperty<T> p, Object oldValueRaw, boolean wasResolved) {
        T oldValue = cast(oldValueRaw);
        T newValue = getComputed(p);
        if (Objects.equals(oldValue, newValue)) return;

        var window = element.getAttachedWindow();
        if (wasResolved && p.isAllowTransition() && window != null
                && window.getStyleEngine().getTransitionEngine().tryStart(element, p, oldValue, newValue)) {
            return;
        }
        p.notifyListeners(element, oldValue, newValue);
    }

    // ── Transition-engine-internal write path (style/transition/) ─────────────────────────────
    // Bypasses putCandidate()/resolveTouched() entirely: per-frame interpolation writes must not
    // report fake diffs or re-enter transition-eligibility checks on their own writes.

    public <T> void startAnimationSlot(StyleProperty<T> p, T startValue, int sourceOrder) {
        replaceAnimationSlot(p, startValue, sourceOrder);
        computedSlots.put(p, StyleSlot.of(p, StyleOrigin.ANIMATION, 0, sourceOrder, startValue));
    }

    public <T> void tickAnimationSlot(StyleProperty<T> p, T interpolatedValue, int sourceOrder) {
        replaceAnimationSlot(p, interpolatedValue, sourceOrder);
        computedSlots.put(p, StyleSlot.of(p, StyleOrigin.ANIMATION, 0, sourceOrder, interpolatedValue));
    }

    /** Removes the ANIMATION shadow and lets the real (non-animated) winner take back over. */
    public <T> void endAnimationSlot(StyleProperty<T> p) {
        var slots = candidates.get(p);
        if (slots != null) {
            slots.removeIf(s -> s.origin() == StyleOrigin.ANIMATION);
            if (slots.isEmpty()) candidates.remove(p);
        }
        computedSlots.put(p, computeCandidateSlot(p));
        element.onStyleChanged();
    }

    private <T> void replaceAnimationSlot(StyleProperty<T> p, T value, int sourceOrder) {
        var slots = candidates.computeIfAbsent(p, k -> new ArrayList<>());
        slots.removeIf(s -> s.origin() == StyleOrigin.ANIMATION);
        slots.add(StyleSlot.of(p, StyleOrigin.ANIMATION, 0, sourceOrder, value));
    }
    public <T> StyleSlot<T> computeCandidateSlot(StyleProperty<T> p) {
        List<StyleSlot<?>> list = candidates.get(p);
        if (list != null && !list.isEmpty()) {
            var best = list.getFirst();
            for (int i = 1; i < list.size(); i++) {
                StyleSlot<?> cur = list.get(i);
                if (StyleSlot.compare(best, cur) < 0) {
                    best = cur;
                }
            }
            return cast(best);
        }
        return null;
    }
    public <T> T computeCandidate(StyleProperty<T> p) {
        var slot = computeCandidateSlot(p);
        if (slot != null) return slot.value();
        return null;
    }
    @SuppressWarnings("unchecked")
    @Nullable
    public <T> T getComputed(StyleProperty<T> p) {
        var computedSlot = computedSlots.get(p);
        return computedSlot == null ? null : (T) computedSlot.value();
    }



    @SuppressWarnings("unchecked")
    private static <T> T cast(Object o) { return (T) o; }

    public void markTaffyStyleDirty() {
        element.markTreeDirty();
    }
}
