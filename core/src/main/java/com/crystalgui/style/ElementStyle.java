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
    /** The DISPLAYED winner per property — what {@link #getComputed} returns. Includes an
     * ANIMATION-origin candidate when one is active (so paint shows the interpolated value). */
    private final Map<StyleProperty<?>, StyleSlot<?>> computedSlots = new HashMap<>();
    /** The REAL underlying winner per property, ignoring any ANIMATION-origin candidate — i.e. what
     * the property should be settling toward. Diffed against on every {@link #resolveTouched} pass
     * instead of {@link #computedSlots}, because an ANIMATION candidate always wins the priority
     * comparison in {@link #computeCandidateSlot}: if the diff compared against the displayed value
     * while a transition was in flight, it would always see "no change" (the animation's own last
     * tick, unchanged), even when the STYLESHEET/INLINE/etc. target underneath had genuinely moved —
     * silently defeating both mid-flight retargeting and cleanup. */
    private final Map<StyleProperty<?>, StyleSlot<?>> realSlots = new HashMap<>();

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

    /**
     * Bulk-reclassifies every candidate currently at {@link StyleOrigin#INLINE} to
     * {@link StyleOrigin#DEFAULT}, in one atomic pass (via {@link #replaceCandidates}, so no
     * transient-null intermediate is ever visible). Meant for widget authors: write a widget's own
     * baseline styling with completely ordinary {@code .layout()}/{@code .generalStyle()} calls
     * (which default to INLINE, same as any other call site), then call this once at the end of
     * construction — e.g. {@code new UiButton().layout(l -> l.width(80)).moveInlineAsDefault()} — so
     * a stylesheet, or the widget's actual user calling {@code .layout()} again (INLINE, now
     * correctly outranking the demoted DEFAULT-origin baseline), can freely override it.
     */
    public void moveInlineAsDefault() {
        List<StyleSlot<?>> reclassified = new ArrayList<>();
        for (var slots : candidates.values()) {
            for (var slot : slots) {
                if (slot.origin() == StyleOrigin.INLINE) {
                    reclassified.add(reorigin(slot));
                }
            }
        }
        if (reclassified.isEmpty()) return;
        replaceCandidates(slot -> slot.origin() == StyleOrigin.INLINE, reclassified);
    }

    @SuppressWarnings("unchecked")
    private static <T> StyleSlot<T> reorigin(StyleSlot<?> slot) {
        return StyleSlot.of((StyleProperty<T>) slot.property(), StyleOrigin.DEFAULT, slot.specificity(), slot.sourceOrder(), (T) slot.value());
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

        var oldRealValues = new HashMap<StyleProperty<?>, Object>();
        var wasResolved = new HashSet<StyleProperty<?>>();
        for (var p : touched) {
            if (realSlots.containsKey(p)) wasResolved.add(p);
            var oldRealSlot = realSlots.get(p);
            oldRealValues.put(p, oldRealSlot == null ? null : oldRealSlot.value());

            realSlots.put(p, computeCandidateSlot(p, true));
            computedSlots.put(p, computeCandidateSlot(p, false));
        }

        for (var p : touched) {
            resolveOne(p, oldRealValues.get(p), wasResolved.contains(p));
        }
        element.onStyleChanged();
    }

    /**
     * Diffs one already-resolved property against its REAL (non-animated) prior value. If it
     * actually changed and the property allows transitions and this isn't the element's first-ever
     * resolution of it, the transition engine gets first refusal (it may shadow the value with an
     * ANIMATION-origin slot instead of applying it instantly, or retarget an in-flight one);
     * otherwise listeners are notified with the real old/new pair.
     */
    private <T> void resolveOne(StyleProperty<T> p, Object oldRealValueRaw, boolean wasResolved) {
        T oldValue = cast(oldRealValueRaw);
        var newRealSlot = realSlots.get(p);
        T newValue = newRealSlot == null ? null : cast(newRealSlot.value());
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
        return computeCandidateSlot(p, false);
    }

    /** @param excludeAnimation when {@code true}, skips ANIMATION-origin candidates — used to find
     * the "real" target a transition should be heading toward, independent of its own current shadow. */
    private <T> StyleSlot<T> computeCandidateSlot(StyleProperty<T> p, boolean excludeAnimation) {
        List<StyleSlot<?>> list = candidates.get(p);
        if (list == null || list.isEmpty()) return null;
        StyleSlot<?> best = null;
        for (var slot : list) {
            if (excludeAnimation && slot.origin() == StyleOrigin.ANIMATION) continue;
            if (best == null || StyleSlot.compare(best, slot) < 0) {
                best = slot;
            }
        }
        return cast(best);
    }
    public <T> T computeCandidate(StyleProperty<T> p) {
        var slot = computeCandidateSlot(p);
        if (slot != null) return slot.value();
        return null;
    }
    /**
     * The effective value for {@code p}: this element's own cascade winner if it has one, otherwise
     * the parent's effective value if {@code p} is {@link StyleProperty#isInheritable()} (walking up
     * recursively — a grandparent with no local candidate either falls back further still), otherwise
     * {@code null} (callers needing a guaranteed non-null value use {@code StyleGroup.getValueSave},
     * which falls back to {@code p.initialValue} beyond this).
     *
     * <p>Deliberately lazy/pull-based, not push-invalidated: an inheriting descendant is simply
     * recomputed fresh on every read rather than being proactively notified when an ancestor's real
     * value changes. That's sufficient for every currently-inheritable property (just {@code color},
     * read fresh every paint via {@code getValueSave}) but means an inherited value change does NOT
     * fire this property's {@code StyleChangeListener}s on the inheriting element, and does NOT make
     * it transition-eligible — only an element's own real candidate changing does that. A property
     * that needed push-based side effects (the way layout properties push into TaffyBridge) while
     * also being inheritable would need real invalidation propagation, which this does not provide.
     */
    @SuppressWarnings("unchecked")
    @Nullable
    public <T> T getComputed(StyleProperty<T> p) {
        var computedSlot = computedSlots.get(p);
        if (computedSlot != null) return (T) computedSlot.value();
        if (p.isInheritable()) {
            var parent = element.getParent();
            if (parent != null) return parent.getStyle().getComputed(p);
        }
        return null;
    }



    @SuppressWarnings("unchecked")
    private static <T> T cast(Object o) { return (T) o; }

    public void markTaffyStyleDirty() {
        element.markTreeDirty();
    }
}
