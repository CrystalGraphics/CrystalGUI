package com.crystalgui.net;

/**
 * <b>What each side will accept from the other before it stops listening.</b>
 *
 * <p>The wire already has one ceiling — {@code FrameMultiplexer.MAX_REASSEMBLY_BYTES}, 8 MB across
 * every open stream — and it is the wrong shape for everything above it. It bounds <em>the transport</em>,
 * so a peer that stays under it can still open ten thousand windows, describe a tree of a million
 * elements, or send a stylesheet larger than the client's whole UI. Each of those is a message the
 * transport is happy to carry and the layer above cannot survive.</p>
 *
 * <h3>The posture</h3>
 *
 * <p>Chromium's rule, which this stack has taken elsewhere: <i>the browser process must be maximally
 * suspicious of its IPC inputs</i>. A Minecraft client has that relationship with a server it did not
 * write, and a server has it with every client — <b>both directions are hostile</b>, and the limits are
 * therefore symmetric rather than a server-side allowance.</p>
 *
 * <h3>Numbers over promises</h3>
 *
 * <p>Every limit here is a number rather than a heuristic, and each is generous enough that no honest
 * UI meets it: the point is to bound what a broken or malicious peer can cost, not to police design.
 * A UI that genuinely wants more than ten thousand described elements on one screen wants pagination —
 * which M7's row streaming provides — rather than a bigger cap.</p>
 *
 * <p><b>Exceeding one is refused, never truncated.</b> A description cut in half is not a smaller UI,
 * it is a wrong one, and applying it would put the two sides into a disagreement about the tree that
 * every later message inherits. The sheet cap is the one exception and is not really one: a sheet is
 * skipped whole, which is a plain window rather than a broken tree.</p>
 */
public final class UiLimits {

    private UiLimits() {
    }

    /**
     * How many windows one connection may have open at once.
     *
     * <p>Generous — a workbench with every tool window torn out is nowhere near it — and it exists so
     * that a server looping on {@code open()} costs a bounded amount rather than the client's memory.
     * Each window is a session, a mirror, an id table and a tree.</p>
     */
    public static final int MAX_WINDOWS_PER_CONNECTION = 64;

    /**
     * How many described elements one window may hold.
     *
     * <p>Counted on the way IN, before the tree is built, so an oversized description costs the reader
     * nothing but the read. Ten thousand is more than any screen can show and far more than any screen
     * should; past it the answer is streaming rows, not a bigger tree.</p>
     */
    public static final int MAX_ELEMENTS_PER_WINDOW = 10_000;

    /**
     * How large one description may be, encoded.
     *
     * <p>Beside the element cap rather than instead of it: a thousand elements each carrying a megabyte
     * of text passes an element count and is the same attack.</p>
     */
    public static final int MAX_DESCRIPTION_BYTES = 2 * 1024 * 1024;

    /** How large one stylesheet may be. A sheet past this is skipped; the window still opens. */
    public static final int MAX_SHEET_BYTES = 512 * 1024;

    /** How many sheets one window may name. */
    public static final int MAX_SHEETS_PER_WINDOW = 16;

    /**
     * How many messages one viewer may send a window per second before it is refused.
     *
     * <p>Above any real interaction by an order of magnitude — a drag reports at frame rate, so tens per
     * second — and low enough that a loop is stopped in the second it starts. Refusals are counted the
     * way any other bad message is, so a peer that keeps it up stops being listened to.</p>
     *
     * <p>Counted in whole seconds, so a burst landing either side of a boundary gets one second's
     * allowance on each and <b>the real bound is twice this</b>. Accepted rather than fixed: a sliding
     * window costs a timestamp per message to bound a peer that is, by then, already being refused and
     * counted toward losing its connection.</p>
     */
    public static final int MAX_INBOUND_PER_SECOND = 600;

    /**
     * How many descriptions a client keeps by hash.
     *
     * <p>The content-addressed cache is what makes re-opening a window free, and an unbounded one is a
     * memory leak keyed by whatever a server chose to send. Small, because the win is repetition rather
     * than history: the same window opened twice, not the last hundred distinct ones.</p>
     */
    public static final int MAX_CACHED_DESCRIPTIONS = 32;
}
