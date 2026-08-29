package com.crystalgui.ui.elements.desktop;

import com.crystalgui.style.easing.ProgressFunctions;
import com.crystalgui.style.easing.Easing;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UITransform;
import com.crystalgui.ui.UIWindow;

import javax.annotation.Nullable;

/**
 * The open, close, minimise and maximise animations — CrystalOS's window transitions.
 *
 * <h3>Transform and opacity, never layout</h3>
 *
 * <p>Every one of these animates {@code transform} and {@code opacity} and nothing else, which is not a
 * shortcut — it is what a compositor does. DWM, Quartz and Mutter all animate a window's <em>surface</em>:
 * the window is drawn once and the result is scaled and slid about. {@link UITransform} is layout-free by
 * construction here (Taffy never sees it), so a window flying into the taskbar reflows nothing and its
 * contents are not re-measured sixty times a second. Animating {@code left}/{@code top}/{@code width}/
 * {@code height} instead would draw the same picture and re-run layout for the whole window to do it,
 * which for a maximise — the moment a window is largest and holds the most — is the worst time to pay it.
 * A geometry change is therefore <b>FLIP</b>: let layout jump to the destination, apply the transform that
 * puts the window back where it was, and ease that away to nothing.</p>
 *
 * <h3>The numbers are GNOME Shell's, because they are the ones that are published</h3>
 *
 * <p>Ported from {@code gnome-shell/js/ui/windowManager.js} — durations, easing modes, scale factors and
 * pivots. <b>Shape only, no code</b>: GNOME Shell is GPL, so this follows the repository's rule for a
 * GPL reference exactly as {@code Rope} does for Zed's {@code SumTree}.</p>
 *
 * <p>A first pass used WinUI 3's {@code ControlNormalAnimationDuration} and the Fluent easing pair, and
 * it was reported as "doesn't look like native OS window animations" — correctly. Those are <b>control</b>
 * tokens: they govern a button's hover, not a window's flight, and DWM's own window timings are not
 * published anywhere. GNOME Shell is a production window manager whose constants are readable, so it is
 * the only honest thing to port. The differences are not cosmetic:</p>
 *
 * <ul>
 *   <li>A minimise is <b>400ms</b>, not 167. It is crossing the screen; a sixth of a second is not enough
 *       time to see something travel that far, so it reads as a disappearance.</li>
 *   <li>Everything decelerates. The Fluent guidance to ACCELERATE things that are leaving is right for a
 *       control getting out of the way, and a real window manager does the opposite for windows — an exit
 *       that speeds up is gone before the eye follows it.</li>
 *   <li>A close scales to <b>0.8</b>, not 0.92. At 0.92 the change is under the threshold where it reads
 *       as motion at all, which is exactly the "does nothing significant" report.</li>
 * </ul>
 *
 * <h3>Why the numbers are here rather than in the sheet</h3>
 *
 * <p>This <b>is</b> a deviation from "no timings in Java", and a considered one. That rule exists so a
 * theme can restyle a widget, and it earns its keep for geometry and colour. It does not fit here: these
 * are a platform motion specification rather than a look, and expressing them as CSS {@code transition}
 * declarations means the cascade also <em>acts</em> on them — which is exactly the mechanism that made
 * the first version of this animate backwards, snap and flicker. {@link WindowAnimation} carries the full
 * account. The control a theme actually wants is {@link Desktop#setAnimationsEnabled}, which is the
 * accessibility switch every desktop ships.</p>
 */
final class WindowAnimator {

    // ── The motion spec: gnome-shell/js/ui/windowManager.js, then TUNED BY EYE ───────────────────
    //
    // GNOME's own constants were ported verbatim because DWM publishes none of its numbers, which makes
    // GNOME the only production window manager whose timings can honestly be cited. That argument
    // settles where to START and not where to end up: GNOME is tuning a full-screen desktop on a
    // compositor with motion blur, while these windows live INSIDE an application and are opened,
    // minimised and closed far more often than a desktop's are. Once the frame pacing was fixed they
    // read as sluggish, which is a judgement only watching them can make.
    //
    // The result is NOT a uniform scale, and the shape of what survived is the interesting part. An
    // arrival and a departure came down hardest (150 -> 125), a minimise less (400 -> 350), and a shape
    // change did not move at all -- it is the one gesture that REFLOWS rather than travelling, so its
    // duration is paying for something the others are not. A uniform 75% was tried first and was wrong
    // in exactly that place.
    //
    // The GNOME source value is kept on each line below, so the distance from the reference stays
    // legible rather than being quietly forgotten.

    /** {@code SHOW_WINDOW_ANIMATION_TIME} (150ms) — a window arriving. */
    private static final long SHOW_NANOS = 125L * 1_000_000L;

    /** {@code DESTROY_WINDOW_ANIMATION_TIME} (150ms) — a window closing. */
    private static final long DESTROY_NANOS = 125L * 1_000_000L;

    /** {@code MINIMIZE_WINDOW_ANIMATION_TIME} (400ms) — a window crossing the screen to the taskbar, and back. */
    private static final long MINIMIZE_NANOS = 350L * 1_000_000L;

    /** {@code WINDOW_ANIMATION_TIME} (250ms — unchanged) — a window changing SHAPE: maximise, restore-down. */
    private static final long SIZE_NANOS = 250L * 1_000_000L;

    /**
     * {@code EASE_OUT_EXPO} — GNOME's mode for a window arriving, and used here for that alone.
     *
     * <p>Its virtue is the snap: a window unfolding from a sliver wants to be most of the way there
     * before you have registered it starting.</p>
     */
    /**
     * An opening window's curve — {@link #MOVING}'s twin, and deliberately NOT expo.
     *
     * <p>It was OUT_EXPO, on the standing rule that expo is kept "only for an opening window, whose
     * whole journey is a scale within its own box". That exemption assumed an open is a SMALL scale
     * change. This one runs from {@link #SHOW_SCALE_X} — one percent — to full size, which is a
     * hundredfold growth: it travels further than the minimise the rule was written to protect.</p>
     *
     * <p>Expo's velocity at t=0 is 6.93x its average, so over {@link #SHOW_NANOS} at 120Hz the window
     * reached 33% of its growth in the FIRST frame, 55% by the second and 97% by the halfway point,
     * leaving nine frames to cover the last three percent. Reported exactly as it behaves: "it feels
     * like the animation is 2 frames, one intermediate and the other the final state".</p>
     */
    private static final Easing ARRIVING = ProgressFunctions.Premade.OUT_QUAD;

    /**
     * {@code EASE_OUT_QUAD} — a close, a size change, and (deviating from GNOME) a minimise.
     *
     * <h4>Why the minimise does not use {@code OUT_EXPO}, which is what GNOME gives it</h4>
     *
     * <p>Because peak velocity is what makes individual frames visible, and expo's is <b>6.93×</b> its
     * average against quad's 2×. Over 400ms at 60Hz that puts 25% of the journey in the first frame and
     * 44% by the second — and a minimise is the one animation crossing a large part of the screen, so
     * those fractions are ~100px and ~75px of displacement between consecutive frames. Far enough apart
     * that the eye resolves them as separate positions instead of motion, which is exactly how it was
     * reported: "still choppy, I can see its individual frames", while the close and the maximise
     * beside it — both on quad — read as smooth.</p>
     *
     * <p>The curve is mathematically smooth either way; what the eye samples is the STEP SIZE. Expo
     * survives on a compositor with motion blur and vsync-aligned presentation, and over the short
     * scale-only distance an opening window travels, which is why it is kept there.</p>
     */
    private static final Easing MOVING = ProgressFunctions.Premade.OUT_QUAD;

    /**
     * What a window grows FROM: {@code scale (0.01, 0.05)} about a {@code (0.5, 1.0)} pivot.
     *
     * <p>GNOME's, and it is drastic on purpose — a sliver at the bottom-centre that unfolds. The
     * temptation is to soften it, and softening is what produced an open animation nobody could see:
     * below about 0.85 the eye reads a scale as motion, and above it as a window that flickered.</p>
     */
    private static final float SHOW_SCALE_X = 0.01f;
    private static final float SHOW_SCALE_Y = 0.05f;

    /** What a window shrinks INTO when it closes — {@code scale (0.8, 0.8)} about its own centre. */
    private static final float DESTROY_SCALE = 0.8f;

    /** How small a minimise ends up when there is no taskbar button to aim at. @see #towardTaskbar */
    private static final float MINIMIZE_FALLBACK_SCALE = 0.02f;

    /**
     * Identity written as ONE SCALE, and identity written as a TRANSLATE then a SCALE.
     *
     * <p><b>Never {@link UITransform#IDENTITY}, which is an EMPTY function list.</b> CSS interpolates two
     * transforms component-wise only when their function lists line up, and this engine implements that
     * rule exactly — {@code TransformProperty.interpolate} opens with {@code if (a.size() != b.size())
     * return snap(...)}, and {@code snap} is the binary rule: {@code t < 0.5 ? from : to}. An empty list
     * lines up with nothing, so every animation that used {@code IDENTITY} as its resting end did not
     * interpolate at all. It sat at its start value until the halfway point and jumped.</p>
     *
     * <p>Which is what "chopped, I can see the individual frames" was, and "the maximise does nothing
     * significant" (one jump), and — worst — "the minimise just fades in place": the travel happened at
     * {@code t = 0.5}, by which point {@code OUT_EXPO} had already taken opacity to about 0.03, so the
     * window was invisible before it moved. Independent of window size and content, and invisible to a
     * drag, which writes {@code left}/{@code top} rather than a transform. Every symptom, one cause.</p>
     *
     * <p>{@code TransformProperty}'s own javadoc says it: a mismatch "is one an author can always avoid
     * by writing both ends with the same functions, which is the standard advice for CSS transform
     * animations anyway". These are that, and every {@code play*} below pairs its endpoints.</p>
     */
    private static final UITransform NEUTRAL_SCALE = UITransform.scale(1f, 1f);

    private static final UITransform NEUTRAL_MAPPING = UITransform.of(
            UITransform.Op.translate(LengthPercent.px(0f), LengthPercent.px(0f)),
            UITransform.Op.scale(1f, 1f));

    private static final LengthPercent CORNER = LengthPercent.px(0f);
    private static final LengthPercent CENTRE = LengthPercent.percent(0.5f);
    private static final LengthPercent BOTTOM = LengthPercent.percent(1f);

    // ── The switch ──────────────────────────────────────────────────────────────────────────────

    /**
     * Whether window animations play at all. @see Desktop#setAnimationsEnabled
     *
     * <p>When off, every {@code play*} below writes nothing and runs its continuation
     * <b>synchronously</b> — which is what makes the switch a real feature rather than a test hook. An
     * animation that merely finished instantly would still defer the hide or the destroy by a frame, so
     * every caller of {@code requestClose} would have to learn to wait for a window that, as far as the
     * user is concerned, is not animating. Off means off, including the waiting.</p>
     */
    private static boolean enabled = true;

    static void setEnabled(boolean value) {
        enabled = value;
    }

    static boolean isEnabled() {
        return enabled;
    }

    private final WindowFrame frame;

    /**
     * The animation currently running on this window, if any.
     *
     * <p>Cancelled before a new one starts. Maximise then immediately restore is the ordinary case, and
     * two drivers writing the same slot would trade frames with each other for as long as both ran.</p>
     */
    @Nullable
    private WindowMotion current;

    WindowAnimator(WindowFrame frame) {
        this.frame = frame;
    }

    /** Whether an animation is playing on this window right now. */
    boolean isPlaying() {
        return current != null;
    }

    /**
     * Where the running animation is headed, or null if none is.
     *
     * <p>Exists because a minimise's whole meaning is its destination, and nothing else about the frame
     * says what that is: the animation STARTS at identity and only reaches the taskbar 400ms later, so
     * neither the computed transform nor any frame of it can be asked "does this travel". It regressed
     * silently once — the fallback for a missing taskbar button was a plain fade — and this is the
     * observable that catches it coming back.</p>
     */
    @Nullable
    UITransform currentTarget() {
        return current instanceof WindowAnimation move ? move.target() : null;
    }

    /** Where the running animation started. @see WindowAnimation#startValue */
    @Nullable
    UITransform currentStart() {
        return current instanceof WindowAnimation move ? move.startValue() : null;
    }

    // ── Open and close ──────────────────────────────────────────────────────────────────────────

    /** The window arrives: unfolds from a sliver at its own bottom edge, fading in. */
    void playOpen() {
        if (!enabled) return;
        start(UITransform.scale(SHOW_SCALE_X, SHOW_SCALE_Y), NEUTRAL_SCALE,
                0f, 1f, CENTRE, BOTTOM, SHOW_NANOS, ARRIVING, null);
    }

    /**
     * The window leaves: down and out, then {@code then}.
     *
     * @param then what to actually do once it has finished — the real hide or destroy. Hiding is
     *             DETACHING here and a detached subtree paints nothing, so a window that tore itself
     *             down on the press would animate to an empty screen: perfectly correct, entirely
     *             invisible.
     */
    void playClose(Runnable then) {
        if (!enabled) {
            then.run();
            return;
        }
        start(NEUTRAL_SCALE, UITransform.scale(DESTROY_SCALE), 1f, 0f,
                CENTRE, CENTRE, DESTROY_NANOS, MOVING, then);
    }

    // ── Minimise and restore ────────────────────────────────────────────────────────────────────

    /**
     * The window shrinks into its taskbar button and disappears, then {@code then}.
     *
     * <p>Every desktop's, and the reason is not decoration: a minimise that merely fades says the window
     * has gone, while one that flies into the taskbar says <em>where</em> it went, which is the one thing
     * you need in order to get it back.</p>
     *
     * <p><b>Something to aim at is found in three steps</b>, because a minimise that does not travel is
     * not a minimise: this window's own button, then its <em>owner's</em> (a tool window has no entry by
     * design and goes where its owner goes — see {@link #taskbarEntry()}), then the bottom centre of the
     * work area, which is what GNOME does when there is no button at all. The plain close animation is
     * the last resort and means the geometry could not be measured — a detached window, or a desktop
     * mid-layout — rather than "there is no taskbar".</p>
     */
    void playMinimize(Runnable then) {
        if (!enabled) {
            then.run();
            return;
        }
        // PHOTOGRAPHED BEFORE IT GOES. The flight ends by detaching the window, and a detached window
        // has nothing left to draw -- so a taskbar preview of a minimised one has to be a picture taken
        // while it was still whole. Requested here, taken on the next paint, which is still frame one of
        // the animation and therefore still an untransformed, fully opaque window.
        UITransform into = towardTaskbar();
        if (into == null) {
            playClose(then);
            return;
        }
        frame.requestSnapshot();
        start(NEUTRAL_MAPPING, into, 1f, 0f, CORNER, CORNER, MINIMIZE_NANOS, MOVING, then);
    }

    /** The window grows back out of its taskbar button — {@link #playMinimize} reversed. */
    void playRestore() {
        if (!enabled) return;
        UITransform from = towardTaskbar();
        if (from == null) {
            playOpen();
            return;
        }
        start(from, NEUTRAL_MAPPING, 0f, 1f, CORNER, CORNER, MINIMIZE_NANOS, MOVING, null);
    }

    /**
     * Where a minimise flies to — the taskbar button if there is one, the strip itself if there is not.
     *
     * <p><b>A minimise that does not travel is not a minimise.</b> This used to fall through to the close
     * animation whenever there was no button to aim at, and the report was exactly that: "it doesn't move
     * towards the taskbar, it just fades out". GNOME hits the same case — a window with no icon geometry —
     * and does not give up on the movement either: it aims at a corner of the monitor at zero scale, so
     * the window still goes SOMEWHERE. The whole information content of the animation is where it went.</p>
     *
     * <p>Ours aims at the middle of the taskbar's bottom edge, because the strip is centred rather than
     * cornered. Null only when there is no desktop to measure at all, at which point a plain fade really
     * is all that is left.</p>
     */
    @Nullable

    private UITransform towardTaskbar() {
        UIElement entry = taskbarEntry();
        UITransform viaEntry = entry == null ? null : toward(entry);
        // FALLS THROUGH, rather than returning whatever `toward` answered. An entry can EXIST and still
        // have no box -- the strip hidden, or the button laid out on a later frame than the press -- and
        // returning its null went all the way back to the plain close animation. Reported as "it just
        // fades in place", which is exactly what a close looks like when you were expecting a minimise.
        if (viaEntry != null) return viaEntry;

        Desktop desktop = frame.desktop();
        if (desktop == null) return null;
        var area = desktop.windowLayer().getRuntimeCache();
        if (area.getWidth() <= 0f || area.getHeight() <= 0f) return null;
        // THE REMEMBERED BOX, not the live one -- a restore asks this a frame before its reattached
        // node has been laid out. @see WindowFrame#boxX()
        if (frame.boxWidth() <= 0f || frame.boxHeight() <= 0f) return null;
        return UITransform.of(
                UITransform.Op.translate(
                        LengthPercent.px(area.getX() + area.getWidth() / 2f - frame.boxX()),
                        LengthPercent.px(area.getY() + area.getHeight() - frame.boxY())),
                UITransform.Op.scale(MINIMIZE_FALLBACK_SCALE, MINIMIZE_FALLBACK_SCALE));
    }

    /**
     * The transform that lays this window over {@code target}'s box — a translate and a scale.
     *
     * <p>Both boxes are measured in the layout space a frame's own {@code left}/{@code top} live in, so
     * the two are directly comparable. Composed translate-then-scale, read against the top-left corner,
     * which {@link WindowAnimation} pins for the animation's whole life.</p>
     */
    @Nullable
    private UITransform toward(@Nullable UIElement target) {
        if (target == null) return null;
        var to = target.getRuntimeCache();
        // THE REMEMBERED BOX. @see WindowFrame#boxX()
        float selfW = frame.boxWidth();
        float selfH = frame.boxHeight();
        if (selfW <= 0f || selfH <= 0f) return null;
        if (to.getWidth() <= 0f || to.getHeight() <= 0f) return null;
        return UITransform.of(
                UITransform.Op.translate(
                        LengthPercent.px(to.getX() - frame.boxX()),
                        LengthPercent.px(to.getY() - frame.boxY())),
                UITransform.Op.scale(to.getWidth() / selfW, to.getHeight() / selfH));
    }

    /**
     * The button this window flies to, or null when there is none to fly to.
     *
     * <h3>A window with no button of its own aims at its OWNER's</h3>
     *
     * <p>Which is the tool-window case, and it is not a fallback so much as the truthful answer. A tool
     * window has no taskbar entry by design ({@code WS_EX_TOOLWINDOW}), and it is minimised only as part
     * of its owner going away — so the place it went is exactly the place the owner went. Flying there
     * says so; the plain shrink-and-fade {@link #playMinimize} would otherwise fall back to says only
     * that it stopped existing.</p>
     *
     * <p>It fixes the return trip in the same stroke, because {@link #playRestore} reads this too: the
     * panels grow back out of the same button the window does.</p>
     *
     * <p>Still null for a window that has no entry and no owner with one — a policy-hidden window, or a
     * desktop with the strip off. Flying at a button that is not there would send it into a corner for
     * no reason.</p>
     */
    @Nullable
    private UIElement taskbarEntry() {
        Desktop desktop = frame.desktop();
        if (desktop == null) return null;
        Taskbar taskbar = desktop.taskbar();
        if (taskbar == null) return null;
        for (WindowFrame walk = frame; walk != null; walk = walk.ownerWindow()) {
            UIElement entry = taskbar.entryFor(walk);
            if (entry != null) return entry;
        }
        return null;
    }

    // ── Maximise and restore-down ───────────────────────────────────────────────────────────────

    /**
     * Animates a window between two rects by driving its LAYOUT, one frame at a time.
     *
     * <p>The one animation here that is not a transform, and {@link WindowGeometryAnimation} carries the
     * whole argument: a size change reflows the window's content, so a transform would show the
     * destination's layout at the source's geometry — a restore opening on a frame of 3x-magnified text.</p>
     *
     * @return whether an animation started. {@code false} means the caller must apply the final rect
     *         itself, which is what keeps the animations-off path synchronous.
     */
    /**
     * Plays a window SHRINKING while something else owns its position — a drag tearing a maximised
     * window loose.
     *
     * <p>The position is deliberately not animated and {@code geometryAnimating} is deliberately not
     * set, because the pointer owns where the window is for the whole of a drag. An ordinary restore
     * animates all four and blocks {@code applyPosition} for its duration, which is right when it is
     * travelling to a known rect and fatal here: the window flew to its stored position and ignored the
     * cursor, so the gesture had to snap instantly instead. Animating the size alone gives the shrink
     * back without taking the position away.</p>
     */
    boolean playShrink(float fromWidth, float fromHeight, float toWidth, float toHeight, Runnable settle) {
        if (!enabled) return false;
        if (fromWidth <= 0f || fromHeight <= 0f || toWidth <= 0f || toHeight <= 0f) return false;
        UIWindow window = frame.getAttachedWindow();
        if (window == null) return false;

        cancelCurrent();
        WindowGeometryAnimation animation = new WindowGeometryAnimation(frame, this::frameIsLive,
                0f, 0f, fromWidth, fromHeight, 0f, 0f, toWidth, toHeight,
                false, true, SIZE_NANOS, MOVING, () -> {
                    current = null;
                    settle.run();
                });
        current = animation;
        window.registerTicker(animation);
        return true;
    }

    boolean playResize(float fromLeft, float fromTop, float fromWidth, float fromHeight,
                       float toLeft, float toTop, float toWidth, float toHeight, Runnable settle) {
        if (!enabled) return false;
        if (fromWidth <= 0f || fromHeight <= 0f || toWidth <= 0f || toHeight <= 0f) return false;
        UIWindow window = frame.getAttachedWindow();
        if (window == null) return false;

        cancelCurrent();
        // NOT a surface animation: a size change REFLOWS the content, so a photograph of the old layout
        // stretched to the new box is exactly the artefact this driver exists to avoid.
        WindowGeometryAnimation animation = new WindowGeometryAnimation(frame, this::frameIsLive,
                fromLeft, fromTop, fromWidth, fromHeight, toLeft, toTop, toWidth, toHeight,
                true, true, SIZE_NANOS, MOVING, () -> {
                    current = null;
                    settle.run();
                });
        current = animation;
        window.registerTicker(animation);
        return true;
    }

    // ── Driving ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Replaces whatever was running with a new animation, and registers it.
     *
     * <p>Runs {@code then} immediately when the window is not in a tree: there is nothing to draw and
     * nothing to tick, and a continuation that never fired would strand a close forever.</p>
     */
    private void start(UITransform from, UITransform to, float fromOpacity, float toOpacity,
                       LengthPercent originX, LengthPercent originY,
                       long durationNanos, Easing easing, @Nullable Runnable then) {
        cancelCurrent();
        UIWindow window = frame.getAttachedWindow();
        if (window == null) {
            if (then != null) then.run();
            return;
        }
        // A WINDOW ANIMATES ITS LIVE CONTENTS, NOT A PHOTOGRAPH OF THEM -- measured, not assumed.
        //
        // Drawing a snapshot for the animation's duration is what a real compositor does, and it was
        // tried here to stop a fade re-rendering the whole window every frame. It bought NOTHING: the
        // same minimise measures 49 ticks at a mean 8ms gap either way, because the choppiness it was
        // meant to cure was never the layer FBO -- it was the clock, stamped at construction, advanced
        // on wall time and charged for stalled frames nobody ever saw. With that fixed the live path is
        // exactly as smooth, and the photograph was pure cost: it drew a visibly different window for
        // the length of every gesture, having produced a whitened one, a blank one and an invisible one
        // on the way there.
        //
        // WindowSnapshot itself stays -- a MINIMISED window is detached and has nothing left to draw, so
        // its taskbar preview genuinely needs a picture taken while it was still whole. @see #playMinimize
        WindowAnimation animation = new WindowAnimation(frame, this::frameIsLive,
                from, to, fromOpacity, toOpacity,
                originX, originY, durationNanos, easing, () -> {
                    current = null;
                    if (then != null) then.run();
                });
        current = animation;
        window.registerTicker(animation);
    }

    /** A window stops being worth writing to once it is destroyed. @see WindowAnimation */
    private boolean frameIsLive() {
        return frame.state() != WindowState.DESTROYED;
    }

    /** Ends whatever was running, so two drivers never write the same thing on alternate frames. */
    private void cancelCurrent() {
        if (current == null) return;
        current.cancel();
        current = null;
    }
}
