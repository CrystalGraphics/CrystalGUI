package com.crystalgui.app.uibuilder.library;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.WeakHashMap;

import com.crystalgui.ui.dom.UIDocument;

/**
 * Builds preview cards' samples a few at a time, so a Library of forty kinds opens over several frames rather
 * than in one.
 *
 * <pre>{@code
 * PreviewBuilds.of(window).request(card);   // the card shows its kind's glyph until its turn
 * }</pre>
 *
 * <ul>
 *   <li>Each frame builds in request order until {@link #BUDGET_NANOS} is spent, and always at least one.</li>
 *   <li>A card that left the window before its turn is dropped; it asks again when it returns.</li>
 * </ul>
 */
public final class PreviewBuilds {

    /**
     * Construction time a frame may spend on samples. Their first style, layout and paint follow on the next
     * frame and cost about as much again, so this is half of what the build really adds to a frame.
     */
    static final long BUDGET_NANOS = 3_000_000L;

    private static final Map<UIDocument, PreviewBuilds> BY_WINDOW = new WeakHashMap<>();

    private final ArrayDeque<PreviewCard> queue = new ArrayDeque<>();

    private PreviewBuilds(UIDocument window) {
        // OWNED BY THE WINDOW, which never leaves itself: the queue outlives any one card or panel.
        window.animation().every(window, delta -> {
            drain();
            return true;
        });
    }

    /** The window's one queue. */
    public static PreviewBuilds of(UIDocument window) {
        return BY_WINDOW.computeIfAbsent(window, PreviewBuilds::new);
    }

    /** Queues {@code card}'s sample; a card already queued keeps its place. */
    void request(PreviewCard card) {
        if (!queue.contains(card)) queue.add(card);
    }

    /** Whether every requested sample is built. */
    public boolean isIdle() {
        return queue.isEmpty();
    }

    private void drain() {
        long started = System.nanoTime();
        while (!queue.isEmpty()) {
            PreviewCard card = queue.poll();
            if (!card.isPending() || card.document() == null) continue;
            card.buildSample();
            if (System.nanoTime() - started >= BUDGET_NANOS) return;
        }
    }
}
