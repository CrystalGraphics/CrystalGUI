package com.crystalgui.style;

import com.crystalgui.UIElement;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StyleSlot;
import com.crystalgui.style.property.StyleValue;
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
    private final BitSet dirtyProps = new BitSet();

    @Getter
    private boolean dirty = true;

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

    public void markDirty() {
        if (!this.dirty) {
            this.dirty = true;
        }
    }

//    public void compute() {
//        if (!isDirty()) return;
//
//        var old = new HashMap<StyleProperty<?>, StyleSlot<?>>();
//
//        for (int pid = dirtyProps.nextSetBit(0); pid >= 0; pid = dirtyProps.nextSetBit(pid + 1)) {
//            var p = StylePropertyRegistry.byId(pid);
//            if (p == null) continue;
//            old.put(p, computedSlots.get(p));
//            computedSlots.put(p, computeCandidateSlot(p));
//        }
//
//        dirtyProps.clear();
//        dirty = false;
//
//
//        for (var entry : old.entrySet()) {
//            var property = entry.getKey();
//            var oldSlot = entry.getValue();
//            var newSlot = computedSlots.get(property);
//            var oldValue = oldSlot == null ? null : oldSlot.value();
//            var newValue = newSlot == null ? null : newSlot.value();
//            if (!Objects.equals(oldValue, newValue)) {
//                // apply transition while changes
//                 property.notifyListeners(element, cast(oldValue), cast(newValue));
//
//            }
//        }
//    }

    public <T> void putCandidate(StyleProperty<T> p, StyleSlot<T> slot) {
        candidates.computeIfAbsent(p, k -> new ArrayList<>()).add(slot);
        dirtyProps.set(p.id);
        markDirty();
        computedSlots.put(p, computeCandidateSlot(p));
        p.notifyListeners(element, null, slot.value());
        element.onStyleChanged();
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
    public void putCandidates(Map<StyleProperty<?>, StyleValue<?>> values,
                              StyleOrigin origin,
                              int specificity, int sourceOrder) {
        if (values.isEmpty()) return;
        for (var entry : values.entrySet()) {
            var p = entry.getKey();
            var v = entry.getValue();
            candidates.computeIfAbsent(p, k -> new ArrayList<>()).add(StyleSlot.of(
                    cast(p),
                    origin,
                    specificity,
                    sourceOrder,
                    cast(v.compute())
            ));
            dirtyProps.set(p.id);
        }
        markDirty();
        element.onStyleChanged();
    }

    public boolean containsCandidate(StyleProperty<?> property, Predicate<StyleSlot<?>> predicate) {
        var slots = candidates.get(property);
        if (slots == null || slots.isEmpty()) return false;
        return slots.stream().anyMatch(predicate);
    }

    public void removeCandidates(Predicate<StyleSlot<?>> predicate) {
        var changed = false;
        for (var entry : candidates.entrySet()) {
            var p = entry.getKey();
            List<StyleSlot<?>> list = entry.getValue();
            if (list.removeIf(predicate)) {
                dirtyProps.set(p.id);
                markDirty();
                changed = true;
            }
        }
        if (changed) {
            candidates.values().removeIf(List::isEmpty);
            element.onStyleChanged();
        }
    }

    public void removeCandidates(StyleProperty<?> property, Predicate<StyleSlot<?>> predicate) {
        var slots = candidates.get(property);
        if (slots == null || slots.isEmpty()) return;
        if (slots.removeIf(predicate)) {
            dirtyProps.set(property.id);
            markDirty();
            candidates.values().removeIf(List::isEmpty);
            element.onStyleChanged();
        }
    }

    public void clearCandidates() {
        for (var p : candidates.keySet()) {
            dirtyProps.set(p.id);
        }
        candidates.clear();
        markDirty();
        element.onStyleChanged();
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
