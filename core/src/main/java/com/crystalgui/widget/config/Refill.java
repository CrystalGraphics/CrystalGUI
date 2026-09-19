package com.crystalgui.widget.config;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.crystalgui.ui.dom.UIElement;

/**
 * One {@link ConfiguratorPanel#refill}: what each parent in the panel held before, what the fill claimed of it, and
 * the order the fill wrote in — React's keyed reconciliation, over a form.
 *
 * <p>A placement names a key; {@link #claim} finds the element the fill before placed under that key in the same
 * parent. {@link #finish} then takes out everything nobody claimed and puts the rest in the order written, which is
 * no work at all when the order did not change — the common case, and the reason a claim looks from where the last
 * one left off.</p>
 */
final class Refill {

    /** One parent's children as the fill found them, and as it is writing them. */
    private static final class Level {
        final List<UIElement> before;
        final boolean[] claimed;
        final List<UIElement> written = new ArrayList<>();
        int cursor;

        Level(List<UIElement> before) {
            this.before = before;
            this.claimed = new boolean[before.size()];
        }
    }

    /** The key each element was placed under, by the fill that placed it. */
    private final Map<UIElement, String> keys;

    private final Map<UIElement, Level> levels = new IdentityHashMap<>();

    /** The keys this fill placed under, which become {@link #keys} for the next. */
    private final Map<UIElement, String> nextKeys = new IdentityHashMap<>();

    Refill(Map<UIElement, String> keys) {
        this.keys = keys;
    }

    /**
     * Starts following {@code parent}: what it holds now is what this fill may claim, and what it does not claim is
     * removed. Called on first use; a reused group calls it on its content at once, so a group the fill writes
     * nothing into still empties.
     */
    void enter(UIElement parent) {
        level(parent);
    }

    /**
     * The element placed under {@code key} in {@code parent} by the fill before, if {@code compatible} accepts it —
     * or null. A test that changes the element must only do so when it answers true.
     */
    @Nullable
    <E extends UIElement> E claim(UIElement parent, String key, Class<E> type, Predicate<? super E> compatible) {
        Level level = level(parent);
        int count = level.before.size();
        for (int step = 0; step < count; step++) {
            int at = (level.cursor + step) % count;
            if (level.claimed[at]) continue;
            UIElement candidate = level.before.get(at);
            if (!key.equals(keys.get(candidate)) || !type.isInstance(candidate)) continue;
            E element = type.cast(candidate);
            if (!compatible.test(element)) continue;
            level.claimed[at] = true;
            level.cursor = at + 1;
            return element;
        }
        return null;
    }

    /** Records {@code element} as the next thing written in {@code parent}, appending it when it is new. */
    void place(UIElement parent, String key, UIElement element) {
        Level level = level(parent);
        level.written.add(element);
        nextKeys.put(element, key);
        if (element.parentElement() != parent) parent.append(element);
    }

    /** Takes out what nobody claimed, puts what was written in the order it was written, and hands back the keys. */
    Map<UIElement, String> finish() {
        for (Map.Entry<UIElement, Level> entry : levels.entrySet()) {
            UIElement parent = entry.getKey();
            Level level = entry.getValue();
            for (int i = 0; i < level.before.size(); i++) {
                UIElement left = level.before.get(i);
                if (!level.claimed[i] && left.parentElement() == parent) parent.remove(left);
            }
            // A MOVE PER ELEMENT OUT OF PLACE, and none when the fill wrote in the order it found.
            for (int i = 0; i < level.written.size(); i++) {
                UIElement wanted = level.written.get(i);
                if (parent.children().get(i) != wanted) parent.insertAt(i, wanted);
            }
        }
        return nextKeys;
    }

    private Level level(UIElement parent) {
        return levels.computeIfAbsent(parent, p -> new Level(new ArrayList<>(p.children())));
    }
}
