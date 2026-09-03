package com.crystalgui.net;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which rows each viewer is looking at, and therefore which the server has to describe.
 *
 * <h3>The union, not one window per viewer</h3>
 *
 * <p>Rows are <b>structure</b>, and structure goes to every viewer: a tree delta renumbers both ends,
 * so withholding one from a viewer that is scrolled elsewhere leaves it addressing elements by numbers
 * the server has moved on from — and every message after that lands somewhere plausible and wrong.
 * So the described set is the union of what viewers are looking at, plus overscan.</p>
 *
 * <p>That is the one multi-viewer cost in the mechanism and it is bounded: viewers × window. Two
 * viewers scrolled to the same place cost one window between them; two scrolled apart cost two, and
 * nothing more — which is the property {@code twoViewersScrolledApartCostTheUnionAndNothingMore}
 * pins.</p>
 *
 * <p>In {@code net} rather than beside {@code ServerScope}: it names no element and no panel, and the
 * session is what holds one per streamed list. A viewer's window is bookkeeping about the wire.</p>
 *
 * <h3>Following</h3>
 *
 * <p>A window whose end reaches the count is <b>following</b>: the server slides it as rows are
 * appended rather than waiting to be asked. That is what a log wants, and it is what makes "scroll to
 * the bottom and watch" cost nothing per line — without it, every appended row is a round trip to
 * discover that the row after it exists too.</p>
 */
public final class RowWindows {

    /**
     * Rows described on each side of what viewers asked for.
     *
     * <p>Enough that an ordinary scroll finds its rows already there and does not stutter waiting for
     * a round trip; small enough that it is not a second window. VS Code's tree and RN's
     * {@code FlatList} both keep a band of this order.</p>
     */
    public static final int OVERSCAN = 10;

    /** One viewer's window, and whether it is pinned to the end. */
    public record Window(int from, int to, boolean following) {

        Window clampedTo(int count) {
            int end = Math.min(Math.max(to, 0), count);
            int start = Math.min(Math.max(from, 0), end);
            return new Window(start, end, following);
        }
    }

    private final Map<Object, Window> byViewer = new LinkedHashMap<>();

    /**
     * Records what a viewer asked for.
     *
     * <p>A window whose {@code to} reaches the count is remembered as following, so later appends
     * slide it without the viewer asking again.</p>
     */
    public void asked(Object viewer, int from, int to, int count) {
        int end = Math.min(Math.max(to, 0), Math.max(count, 0));
        int start = Math.min(Math.max(from, 0), end);
        byViewer.put(viewer, new Window(start, end, end >= count));
    }

    /** A viewer went away. Its window goes with it, so the union shrinks. */
    public void forget(Object viewer) {
        byViewer.remove(viewer);
    }

    public int viewerCount() {
        return byViewer.size();
    }

    /** What {@code viewer} is looking at, or null if it has never asked. */
    public Window of(Object viewer) {
        return byViewer.get(viewer);
    }

    /**
     * The rows that have to exist as described elements — every viewer's window, widened by
     * {@link #OVERSCAN} and clamped to {@code count}.
     *
     * <p>A <b>span</b> rather than a set of ranges, because the described children of one element are
     * one contiguous list: two viewers at opposite ends of a long list genuinely do cost the rows
     * between them. That is the honest bound, and the alternative — a sparse child list with holes —
     * cannot be expressed as an ordered child list at all.</p>
     *
     * <p>Answers an empty window when nobody is looking, which is the state a list is in before its
     * first viewer has scrolled anywhere and is why a panel that opens costs nothing until it does.</p>
     */
    public Window required(int count) {
        if (byViewer.isEmpty() || count <= 0) return new Window(0, 0, false);
        int from = Integer.MAX_VALUE;
        int to = 0;
        boolean following = false;
        for (Window window : byViewer.values()) {
            Window live = window.clampedTo(count);
            // A FOLLOWING WINDOW IS RE-ANCHORED TO THE END rather than kept where it was asked for.
            // Its whole point is that appends arrive without being asked for, and a window left at its
            // original offsets would stop covering the tail the moment one landed.
            int start = live.following() ? Math.max(0, count - (live.to() - live.from())) : live.from();
            int end = live.following() ? count : live.to();
            from = Math.min(from, start);
            to = Math.max(to, end);
            following |= live.following();
        }
        if (from > to) return new Window(0, 0, false);
        return new Window(Math.max(0, from - OVERSCAN), Math.min(count, to + OVERSCAN), following);
    }
}
