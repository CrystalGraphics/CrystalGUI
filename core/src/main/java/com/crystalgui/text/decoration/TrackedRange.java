package com.crystalgui.text.decoration;

import com.crystalgui.text.ChangeSet;

import javax.annotation.Nullable;

/**
 * A span of a document that stays correct while the document is edited.
 *
 * <h3>Mutable, and identified by reference — deliberately</h3>
 *
 * <p>The whole point is that a consumer holds one of these and reads it later. A value type would mean the
 * holder has to look its range up again after every edit, by some key, against some map — which is the
 * bookkeeping this exists to remove, and which is wrong in exactly the way that is invisible: the lookup
 * still returns <em>something</em>, just the wrong span. Monaco's {@code IntervalNode} is mutable for the
 * same reason, and its identity is the object.</p>
 *
 * <p>The consequence to keep in mind: <b>{@link #from()} and {@link #to()} are only meaningful against the
 * document as it is now.</b> Storing them and comparing later compares two different documents.</p>
 *
 * <h3>{@link #lane} and {@link #payload} — the designed-for-later half</h3>
 *
 * <p>§17.1's rule was to design for the future consumers and build for the present one. {@code lane} is a
 * string kind — {@code "diagnostic"}, later {@code "bracket-pair"}, {@code "ruler"}, {@code "git"} — so a
 * consumer can replace its own ranges wholesale without touching anybody else's, which is the same
 * owner-keyed shape {@code DiagnosticSet} already needed and got wrong once by not having. {@code payload}
 * is whatever the lane's owner wants back. Neither is interpreted here.</p>
 *
 * <h3>Collapse is recorded, not hidden</h3>
 *
 * <p>When an edit deletes everything a range covered, the range collapses to a point rather than being
 * dropped. Dropping is a guess: a diagnostic whose text was just deleted is stale, but so is the whole
 * analysis, and the recompile 300ms behind will replace it — removing it eagerly makes the squiggle vanish
 * and reappear, which reads as flicker rather than as correctness.</p>
 *
 * <p>But a collapsed range must not be <em>drawn</em> like a born-empty one. A zero-width diagnostic is a
 * real thing — "expected ';'" points between two characters and is widened to one character so it can be
 * seen — whereas a range that collapsed because its text was deleted would be widened into a mark over
 * whatever innocent text moved into its place. {@link #collapsedByEdit()} is what tells them apart, and it
 * is the reason this is not simply {@code from == to}.</p>
 */
public final class TrackedRange {

    private final Stickiness stickiness;
    private final String lane;
    private final Object payload;

    private int from;
    private int to;
    private boolean collapsedByEdit;
    private boolean removed;

    TrackedRange(int from, int to, Stickiness stickiness, String lane, @Nullable Object payload) {
        this.from = Math.min(from, to);
        this.to = Math.max(from, to);
        this.stickiness = stickiness == null ? Stickiness.ALWAYS_GROWS_WHEN_TYPING_AT_EDGES : stickiness;
        this.lane = lane == null ? DecorationSet.DEFAULT_LANE : lane;
        this.payload = payload;
    }

    public int from() {
        return from;
    }

    public int to() {
        return to;
    }

    public int length() {
        return to - from;
    }

    public boolean isEmpty() {
        return to == from;
    }

    public Stickiness stickiness() {
        return stickiness;
    }

    public String lane() {
        return lane;
    }

    @Nullable
    public Object payload() {
        return payload;
    }

    /** {@link #payload()} when it is of the given type, else null — so a lane's owner reads its own. */
    @Nullable
    public <T> T payload(Class<T> type) {
        return type.isInstance(payload) ? type.cast(payload) : null;
    }

    /**
     * Whether an edit deleted everything this covered.
     *
     * <p>Distinct from {@link #isEmpty()}, which is also true of a range that was <em>created</em> empty.
     * See the class note — one of those should be drawn and the other should not.</p>
     */
    public boolean collapsedByEdit() {
        return collapsedByEdit;
    }

    /** Whether this has been taken out of its set. A stale reference reads its last offsets and is inert. */
    public boolean isRemoved() {
        return removed;
    }

    public boolean contains(int offset) {
        return offset >= from && offset < to;
    }

    /** Whether this and {@code [otherFrom, otherTo)} share any character. Empty ranges touch nothing. */
    public boolean overlaps(int otherFrom, int otherTo) {
        return from < otherTo && otherFrom < to;
    }

    /**
     * Moves this through an edit.
     *
     * <p>Both ends go through {@link ChangeSet#mapPos} with the {@code assoc} the stickiness names, which is
     * the entire implementation — there is no boundary arithmetic here, because doing it twice is how the
     * two copies come to disagree.</p>
     *
     * <p>{@link ChangeSet.MapMode#TRACK_DELETION} is what detects collapse: it reports a position strictly
     * inside removed text as gone, and a range with either end gone is one whose span no longer exists.
     * Where it collapses <em>to</em> is then asked for again in {@code SIMPLE} mode, which is the edit
     * point.</p>
     */
    void adjust(ChangeSet change) {
        // Read BEFORE anything moves. The "did this collapse" test below is about the span this range had,
        // and asking length() after assigning the new ends compares the new span with itself -- a guard that
        // can never fire, which is worse than no guard because it looks like one.
        int lengthBefore = to - from;
        int mappedFrom = change.mapPos(from, stickiness.startAssoc(), ChangeSet.MapMode.TRACK_DELETION);
        int mappedTo = change.mapPos(to, stickiness.endAssoc(), ChangeSet.MapMode.TRACK_DELETION);

        if (mappedFrom == ChangeSet.DELETED || mappedTo == ChangeSet.DELETED) {
            int point = change.mapPos(from, stickiness.startAssoc(), ChangeSet.MapMode.SIMPLE);
            from = point;
            to = point;
            collapsedByEdit = true;
            return;
        }

        from = mappedFrom;
        // An edit can leave the ends crossed -- NEVER_GROWS on an insertion inside an empty range moves the
        // start forward and the end back. Clamping rather than swapping, because the start is the anchor a
        // reader thinks in and a silently reversed range would still look plausible everywhere it is used.
        to = Math.max(mappedFrom, mappedTo);
        if (to == from && lengthBefore != 0) collapsedByEdit = true;
    }

    void markRemoved() {
        removed = true;
    }

    @Override
    public String toString() {
        return "TrackedRange[" + lane + " " + from + ".." + to
                + (collapsedByEdit ? " collapsed" : "") + (removed ? " removed" : "") + "]";
    }
}
