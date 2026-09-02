package com.crystalgui.ui.service;

import com.crystalgui.style.easing.Easing;
import com.crystalgui.ui.dom.UINode;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * The motion service: one timeline mechanism, and the per-frame hooks a tree is allowed to have.
 *
 * <h3>Why a timeline rather than the cascade</h3>
 *
 * <p>The window animations were CSS transitions first, and every one of the four ways that failed was
 * silent. A compositor animation moves several properties that must change TOGETHER and the cascade
 * resolves them independently: {@code transition} is itself a property resolved in the same pass, so
 * lifting a suppression and changing a value in one frame could resolve the value first and apply it
 * instantly; an {@code INLINE} cleanup value written to end one animation outranks the class used to
 * start the next; {@code transform-origin} is not interpolable and must be pinned for the
 * animation's whole life; and completion could only be discovered by polling. The answer is the shape
 * every real compositor uses — {@code CABasicAnimation}, {@code ValueAnimator}, {@code
 * AnimationController}: from, to, duration, curve, a per-frame tick, a completion callback.</p>
 *
 * <h3>The clock</h3>
 *
 * <p>There isn't one. The host hands {@link #tick} the frame delta, so "the clock starts on its first
 * tick, never at construction" is structural rather than remembered — the row that cost a session
 * when a window opened outside the frame loop and its whole 150ms elapsed before anything was drawn.
 * A gap longer than {@link #MAX_STEP} advances nothing, because it is time nobody saw; that is
 * bounded at {@link #MAX_HELD_FRAMES} so a host that never settles still finishes.</p>
 *
 * <p>The START value is written when the timeline is created, not on the first tick: one frame
 * between "asked for" and "showing its first value" is one frame of the END state.</p>
 */
public final class Animation {

    /** A frame gap longer than this is a stall nobody rendered, and advances the timeline by nothing. */
    public static final float MAX_STEP = 0.1f;

    /** ...but only this many in a row, so a host that never settles still finishes. */
    public static final int MAX_HELD_FRAMES = 10;

    /** What a timeline writes, given its eased progress. Transform, opacity, scroll, a layout value. */
    public interface Body {
        void apply(float easedProgress);
    }

    /** A per-frame hook owned by a node. Returns false to stop. */
    public interface Hook {
        boolean frame(float deltaSeconds);
    }

    /** One running animation. */
    public final class Timeline {
        private final float duration;
        private final Easing easing;
        private final Body body;
        private final @Nullable Runnable onDone;
        private float elapsed;
        private int held;
        private boolean running = true;

        private Timeline(float duration, Easing easing, Body body, @Nullable Runnable onDone) {
            this.duration = Math.max(0f, duration);
            this.easing = easing;
            this.body = body;
            this.onDone = onDone;
            // THROUGH THE EASING, like every other write. A raw `0f` is the same number for every
            // easing that passes through the origin and a different one for any that does not, so the
            // start value and the first re-assert of it would disagree — a jump on the first held
            // frame, under exactly the easing chosen to hold a value constant.
            body.apply(easedProgress());
        }

        public boolean isRunning() {
            return running;
        }

        public float progress() {
            return duration <= 0f ? 1f : Math.min(1f, elapsed / duration);
        }

        /** Runs to the end value and reports completion — what a caller waiting on it must go through. */
        public void finish() {
            if (!running) return;
            running = false;
            elapsed = duration;
            body.apply((float) easing.ease(1.0));
            timelines.remove(this);
            if (onDone != null) onDone.run();
        }

        /** Stops where it is: no end value, no completion callback. */
        public void cancel() {
            if (!running) return;
            running = false;
            timelines.remove(this);
        }

        private void advance(float delta) {
            if (delta > MAX_STEP && held < MAX_HELD_FRAMES) {
                held++;
                // RE-ASSERTED, NOT ASSUMED. A held frame advances the timeline by nothing, which is
                // not the same as writing nothing: what a body writes is a COMPOSITOR OVERRIDE, and
                // an override lives on the BOX rather than on the node -- so it does not survive the
                // box being destroyed and rebuilt, which is what a node leaving and rejoining the
                // tree does. The constructor's `body.apply(0f)` is therefore not a value that can be
                // relied on to still be there by the first advancing tick.
                body.apply(easedProgress());
                return;
            }
            held = 0;
            elapsed += Math.min(delta, MAX_STEP);
            if (elapsed >= duration) {
                finish();
                return;
            }
            body.apply(easedProgress());
        }

        /** The value this timeline stands at — written on an advancing frame AND re-asserted on a held one. */
        private float easedProgress() {
            return (float) easing.ease(progress());
        }
    }

    private record OwnedHook(UINode owner, Hook hook) {
    }

    private final List<Timeline> timelines = new ArrayList<>();
    private final List<OwnedHook> hooks = new ArrayList<>();
    private final List<OwnedHook> afterLayout = new ArrayList<>();

    /** Starts a timeline, writing its start value now. */
    public Timeline start(float durationSeconds, Easing easing, Body body, @Nullable Runnable onDone) {
        Timeline timeline = new Timeline(durationSeconds, easing, body, onDone);
        if (timeline.running) timelines.add(timeline);
        return timeline;
    }

    /** Whether anything is animating — the one assertion that separates "playing" from "applied instantly". */
    public boolean isAnimating() {
        return !timelines.isEmpty();
    }

    public int running() {
        return timelines.size();
    }

    /**
     * A per-frame hook OWNED by a node: dropped when the node leaves the tree, and DORMANT while it
     * is frozen.
     *
     * <p>The old {@code UIFrameTicker} was registered one-way and stopped only by returning false, so
     * the one thing that carried on running in a hidden window was a ticker — the "hidden editor that
     * keeps compiling". Ownership makes that structural: hiding is freezing, and a frozen node's hook
     * does not run.</p>
     *
     * <p><b>A hook runs BEFORE layout, so on its first frame — and on its first frame after a thaw —
     * its owner may have no box.</b> The frame is {@code animation → style → layout}, a freeze drops
     * the subtree's boxes and a thaw rebuilds them on the next pass, so a hook that reads geometry
     * gets null at both of those moments. {@code box()} is nullable and this is the commonest way to
     * meet it: the Run console's tail-follow read {@code editor.box().maxScrollTop()} and threw on the
     * first frame the panel was ever ticked. Guard at the call site, where the widget knows what the
     * right answer is for "not laid out yet" — or use {@link #afterLayout} when the hook exists to
     * measure something.</p>
     *
     * <p><b>Frozen SKIPS, it does not drop, and the difference is a whole class of dead widget.</b> A
     * freeze is temporary by construction — a frozen subtree keeps its scroll, its text and its
     * listeners precisely so it can come back — and this service cannot restore a hook it has
     * discarded. So dropping on freeze meant every widget had to notice the thaw and register again,
     * which none of the twelve that register a hook did: each guards registration with a latch set on
     * connect and never cleared, so the FIRST hide killed the hook for the life of the node. The Run
     * panel is where it was found — its transcript drain, its empty-state caption and its rail clock
     * are all that one hook, so a Run panel that had ever been hidden stopped responding to anything
     * at all while looking perfectly healthy.</p>
     */
    public void every(UINode owner, Hook hook) {
        hooks.add(new OwnedHook(owner, hook));
    }

    /**
     * As {@link #every}, but run <b>after</b> layout has settled — for a hook that has to READ
     * geometry.
     *
     * <p>The frame is {@code animation → style → layout}, so an ordinary hook sees the PREVIOUS
     * frame's boxes. For anything that moves a box that is fine; for anything that measures one it is
     * a frame late, and on the frame a node first gets a box it is measuring zero. That is the old
     * engine's documented trap — <i>"a newly added element measures zero on the same frame, because
     * advanceFrame runs style → tickers → layout"</i> — and it cost an entire feature there: an
     * animation that needed its target's natural size read 0, the "nothing to animate" guard fired,
     * and every arrival settled instantly at full size, which is exactly what no animation looks
     * like.</p>
     *
     * <p>What needs it is anything positioned FROM measured geometry: a popover and a tooltip flip and
     * clamp against their own width and height, so placed before layout on their opening frame they
     * are placed as if they were a point and jump on the next. The old engine spelled this as an
     * {@code onLayoutChanged} override on the element; a list here keeps geometry out of the node.</p>
     *
     * <p>It may not mutate the tree — a structural change would need another layout, and there is no
     * second pass. Move a box, read a box, place something; do not add one.</p>
     */
    public void afterLayout(UINode owner, Hook hook) {
        afterLayout.add(new OwnedHook(owner, hook));
    }

    public int hookCount() {
        return hooks.size();
    }

    /** Advances every timeline and runs every live hook. Called once per frame by the host. */
    public void tick(float deltaSeconds) {
        for (Timeline timeline : new ArrayList<>(timelines)) timeline.advance(deltaSeconds);
        for (OwnedHook owned : new ArrayList<>(hooks)) {
            // GONE is gone; FROZEN is coming back. @see #every
            if (!owned.owner().isConnected()) {
                hooks.remove(owned);
                continue;
            }
            if (owned.owner().isFrozen()) continue;
            if (!owned.hook().frame(deltaSeconds)) hooks.remove(owned);
        }
    }

    /** Runs the post-layout hooks. Called by the document once layout has settled. */
    /** How many post-layout hooks are live. The counterpart to {@link #hookCount}. */
    public int afterLayoutCount() {
        return afterLayout.size();
    }

    public boolean tickAfterLayout(float deltaSeconds) {
        boolean ran = false;
        for (OwnedHook owned : new ArrayList<>(afterLayout)) {
            // Same rule as `every`: gone is gone, frozen is coming back.
            if (!owned.owner().isConnected()) {
                afterLayout.remove(owned);
                continue;
            }
            if (owned.owner().isFrozen()) continue;
            ran = true;
            if (!owned.hook().frame(deltaSeconds)) afterLayout.remove(owned);
        }
        // WHETHER ANY RAN, which is the only cheap signal that the frame may need settling. What a
        // post-layout hook writes goes into the CASCADE, and the cascade does not reach Taffy until the
        // next `refreshStyles` -- which happens inside layout. So there is nothing to test afterwards:
        // "is layout dirty" answers no for a write that has not been carried across yet, and the pass
        // that would carry it is the pass being decided on. @see UIDocument#settleAfterLayout
        return ran;
    }

    /** Drops the hooks a subtree owns — the lifecycle service, freezing or destroying it. */
    public void forget(UINode node) {
        hooks.removeIf(owned -> UINode.isShadowIncludingInclusiveAncestor(node, owned.owner()));
        afterLayout.removeIf(owned -> UINode.isShadowIncludingInclusiveAncestor(node, owned.owner()));
    }
}
