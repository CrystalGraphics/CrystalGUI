package com.crystalgui.ui.elements.desktop;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.easing.ProgressFunctions;
import com.crystalgui.ui.AnchoredPlacement;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIFrameTicker;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Button;

import dev.vfyjxf.taffy.style.TaffyDisplay;

import javax.annotation.Nullable;

/**
 * The hover behaviour behind {@link WindowPreview} — when it appears, where, and how it gets there.
 *
 * <h3>One panel that MOVES, not one per entry</h3>
 *
 * <p>Windows' taskbar keeps a single preview and slides it along as you run the pointer across the
 * entries, and that is not a saving — it is the behaviour. Two panels cross-fading cannot produce one
 * continuous motion, and a preview that vanished and reappeared for each neighbour would flicker its way
 * across the strip.</p>
 *
 * <h3>The delay is the whole reason it is usable</h3>
 *
 * <p>A hover is not a request. The pointer crosses the strip on its way somewhere else, and a preview on
 * every entry it passes is a panel strobing over the screen. Half a second is Windows' own wait and long
 * enough to separate "went past this" from "asked about this" — the same argument {@code Tooltip}'s delay
 * makes, for the same reason.</p>
 *
 * <p><b>The wait applies to the FIRST preview only.</b> Once one is up, moving to a neighbouring entry
 * switches immediately: you have already asked the question, and re-imposing the delay would make the
 * panel disappear and come back rather than travel.</p>
 *
 * <h3>Leaving is a question about two elements, not one</h3>
 *
 * <p>The pointer has to leave the entry <em>and</em> the panel, because the natural way to reach a
 * preview's close button is to move up off the entry and onto the panel. Hiding on the entry's own
 * {@code mouseleave} would make the panel impossible to touch — it would go the instant you set off
 * towards it.</p>
 */
final class TaskbarPreviews {

    /** Windows' own wait before the first preview. */
    private static final long HOVER_DELAY_NANOS = 500L * 1_000_000L;

    /**
     * How long the panel takes to get anywhere — rising from the bar, or sliding between entries.
     *
     * <p>One duration because it is one motion. The two were separate while the entrance was a transform
     * and a fade and the slide was a position animation; once both became "the panel travels to where it
     * belongs", keeping two names for the same number was only an invitation to make them differ for no
     * reason. GNOME's {@code SHOW_WINDOW_ANIMATION_TIME}.</p>
     */
    private static final long TRAVEL_NANOS = 150L * 1_000_000L;

    /** How far below its resting place a preview starts, in logical pixels. */
    private static final float RISE = 14f;

    /** The gap between the panel and the entry it belongs to. */
    private static final float GAP = 6f;

    /**
     * How long the pointer may be over neither the entry nor the panel before the preview goes.
     *
     * <p>Without it the panel is unreachable. It sits {@link #GAP} above the entry, so moving up to
     * press its close button means crossing pixels that belong to neither — and the entry's
     * {@code mouseleave} arrives before the panel's {@code mouseenter}, so a preview that dismissed on
     * the first frame with nothing hovered would vanish the instant you set off towards it. Windows
     * leaves the same grace, and for the same reason.</p>
     */
    private static final long LEAVE_GRACE_NANOS = 220L * 1_000_000L;

    private final Taskbar taskbar;
    private final WindowPreview preview = new WindowPreview();

    /** The entry the pointer is currently resting on, and since when. */
    @Nullable
    private WindowFrame hovered;
    @Nullable
    private Button hoveredEntry;
    private long hoveredSince;

    /** The window the panel is currently showing, or null when it is not up. */
    @Nullable
    private WindowFrame showing;

    private boolean pointerInPanel;

    /** When the pointer left both the entry and the panel, or 0 while it is on one of them. */
    private long leftAt;
    private boolean ticking;

    /**
     * A placement waiting on a measurement, and whether it was an arrival or a move.
     *
     * <p>The kind has to be carried. It was a bare "awaiting" flag whose retry always replayed the
     * ARRIVAL, so moving between entries — which always waits at least one frame, because the thumbnail
     * resizes for the new window — came back as a rise from below rather than a slide, and placed itself
     * from the arrival's starting point into the bargain.</p>
     */
    private boolean pendingPlacement;
    private boolean pendingEntering;

    /**
     * Where the panel was, and how big its picture was, before a move — what a move animates FROM.
     *
     * <p>Captured at the moment the switch is noticed, because pointing the panel at another window
     * changes both immediately: measured any later, they are already the destination and the move has
     * nothing to travel from.</p>
     */
    private float morphLeft;
    private float morphTop;
    private float morphThumbWidth;
    private float morphThumbHeight;

    /** The panel travelling, and its picture changing shape underneath it. @see #placeAndPlay */
    @Nullable
    private WindowMotion motion;
    @Nullable
    private WindowMotion thumbMotion;

    /**
     * Built with the taskbar and parked in it immediately.
     *
     * <p>The desktop is resolved when it is needed rather than taken here, because a taskbar is
     * constructed before it is attached and its desktop is its parent. Building this lazily instead —
     * on the first entry — put an {@code addInternalChild} inside {@code refresh()}, which runs from
     * {@code onWindowChanged}: a Taffy insert into a parent whose children were still being registered,
     * and the crash {@code UIElement.taffyChildIndex} is named after.</p>
     */
    TaskbarPreviews(Taskbar taskbar) {
        this.taskbar = taskbar;

        // PARKED IN THE TASKBAR so it has a tree to be promoted OUT of. Same idiom as DragGhost's: an
        // element must be somewhere before the top layer can take it.
        setPanelShown(false);
        taskbar.addInternalChild(preview);

        preview.onMouseEnter.attachListener((element, event) -> {
            pointerInPanel = true;
            wake();
        }, false, false);
        preview.onMouseLeave.attachListener((element, event) -> {
            pointerInPanel = false;
            wake();
        }, false, false);
        preview.onActivated.connect(() -> {
            WindowFrame frame = showing;
            Desktop desktop = taskbar.desktop();
            dismiss();
            // POINTER activation, so focus lands without a ring: the user pointed at this.
            if (frame != null && desktop != null) desktop.activate(frame, false);
        });
        preview.onClosed.connect(this::dismiss);
    }

    /** The window the panel is showing, or null when it is not up. */
    @Nullable
    WindowFrame showingFrame() {
        return showing;
    }

    /** Wires one taskbar entry's hover. Called as each entry is built. */
    void watch(Button entry, WindowFrame frame) {
        entry.onMouseEnter.attachListener((element, event) -> {
            hovered = frame;
            hoveredEntry = entry;
            hoveredSince = System.nanoTime();
            wake();
        }, false, false);
        // A PRESS ENDS IT. Clicking an entry activates or minimises the window under the panel, so the
        // panel is describing something that is no longer true the moment the press lands -- and after a
        // minimise it would be describing a window that has just been detached. The pointer is still on
        // the entry afterwards, so no mouseleave is coming to take it down.
        entry.onMouseDown.attachListener((element, event) -> dismiss(), false, false);
        entry.onMouseLeave.attachListener((element, event) -> {
            if (hovered == frame) {
                hovered = null;
                hoveredEntry = null;
            }
            wake();
        }, false, false);
    }

    /** Takes the panel down at once — a click, a closed window, a rebuilt strip. */
    void dismiss() {
        hovered = null;
        hoveredEntry = null;
        pointerInPanel = false;
        leftAt = 0L;
        pendingPlacement = false;
        showing = null;
        cancelMotion();
        preview.setFrame(null);
        preview.removeFromTopLayer();
        setPanelShown(false);
    }

    /**
     * Starts the per-frame check, if it is not already running.
     *
     * <p>A ticker rather than a timer because the whole decision — has the wait elapsed, is the pointer
     * still anywhere that counts — is a per-frame question anyway, and the frame delta is the clock
     * everything else here advances on. It drops itself the moment there is nothing to watch.</p>
     */
    private void wake() {
        if (ticking) return;
        UIWindow window = taskbar.getAttachedWindow();
        if (window == null) return;
        ticking = true;
        window.registerTicker(new UIFrameTicker() {
            @Override
            public boolean tickFrame(float deltaSeconds) {
                if (taskbar.getAttachedWindow() == null) {
                    ticking = false;
                    return false;
                }
                boolean busy = update();
                if (!busy) ticking = false;
                return busy;
            }
        });
    }

    /** Asks for a placement on a later frame, once whatever it is waiting on has been measured. */
    private void placeWhenMeasured(boolean entering) {
        pendingPlacement = true;
        pendingEntering = entering;
    }

    /** @return whether there is still anything to watch */
    private boolean update() {
        if (pendingPlacement) {
            pendingPlacement = false;
            placeAndPlay(pendingEntering);
            return true;
        }

        // A PREVIEW OF A WINDOW THAT NO LONGER EXISTS. Closing one from its own preview is the ordinary
        // route here: the entry goes, so no mouseleave will ever arrive to take the panel down with it.
        if (showing != null && showing.state() == WindowState.DESTROYED) {
            dismiss();
            return false;
        }

        UIWindow window = taskbar.getAttachedWindow();
        if (hovered != null && window != null) {
            leftAt = 0L;
            boolean elapsed = System.nanoTime() - hoveredSince >= HOVER_DELAY_NANOS;
            preview.syncThumbnail();
            if (showing == null && elapsed) {
                show(hovered);
            } else if (showing != null && showing != hovered) {
                // NO SECOND WAIT. The question has already been asked; this is the panel travelling.
                //
                // WHERE IT IS NOW, BEFORE THE CONTENT CHANGES. Pointing the panel at another window
                // resizes its thumbnail, and the panel with it, so anything measured afterwards is the
                // destination -- the move would have nothing to travel from and would only slide.
                var was = preview.getRuntimeCache();
                var rootBox = window.ui.rootElement.getRuntimeCache();
                var wasThumb = preview.thumbnailBox();
                morphLeft = was.getX() - rootBox.getX();
                morphTop = was.getY() - rootBox.getY();
                morphThumbWidth = wasThumb.getWidth();
                morphThumbHeight = wasThumb.getHeight();
                // HELD AT THE OLD SIZE until the morph takes over, or setFrame writes the destination
                // size on the spot and the picture snaps a frame before the panel starts moving.
                preview.setThumbnailSizingSuppressed(true);
                preview.setFrame(hovered);
                showing = hovered;
                placeAndPlay(false);
            }
            return true;
        }

        if (pointerInPanel) {
            leftAt = 0L;
            return true;
        }
        if (showing != null) {
            long now = System.nanoTime();
            if (leftAt == 0L) {
                leftAt = now;
                return true;
            }
            if (now - leftAt < LEAVE_GRACE_NANOS) return true;
            dismiss();
            return false;
        }
        leftAt = 0L;
        return false;
    }

    private void show(WindowFrame frame) {
        showing = frame;
        preview.setFrame(frame);
        setPanelShown(true);
        preview.addToTopLayer();
        // INVISIBLE FOR ONE FRAME, deliberately. The panel has never been laid out, so it has no size --
        // and a preview is placed RELATIVE TO ITS OWN SIZE, being anchored above the entry. Showing it
        // before it has been measured would put a full-size panel at the origin for a frame.
        StyleGroup.importantPipeline(preview.getStyle().getGeneralGroup(), g -> g.opacity(0f));
        placeWhenMeasured(true);
    }

    /**
     * Puts the panel where it belongs, and animates it getting there.
     *
     * @param entering whether this is a first appearance, which starts {@link #RISE} below where it
     *                 belongs, rather than a move between entries, which starts wherever it already is
     */
    private void placeAndPlay(boolean entering) {
        UIWindow window = taskbar.getAttachedWindow();
        Button entry = hoveredEntry;
        if (window == null || entry == null || showing == null) {
            dismiss();
            return;
        }
        // THE THUMBNAIL SETTLES BEFORE THE PANEL IS PLACED. It takes its width from the window's shape
        // and it can only do that once it has been laid out at the height the sheet gives it, so the
        // first frame of a preview is always one where it has just changed. Placing against the
        // measurement taken before that change puts a panel of one width at the centre computed for
        // another -- and the panel is invisible until this succeeds, so waiting costs nothing.
        if (entering && preview.syncThumbnail()) {
            placeWhenMeasured(true);
            return;
        }
        var self = preview.getRuntimeCache();
        if (self.getWidth() <= 0f || self.getHeight() <= 0f) {
            // Still unmeasured; try again next frame rather than placing against nothing.
            placeWhenMeasured(entering);
            return;
        }

        // WHERE THE PANEL ENDS UP, which on a move is not where it is now: the picture is still held at
        // the old window's size, and the panel is only ever the picture plus its padding. Asking the
        // thumbnail where it is GOING and adding that same padding gives the destination without having
        // to jump there first -- and the placement must be computed against it, or a panel that grows
        // during the move ends up centred on the width it started at.
        float[] fit = entering ? null : preview.fittedThumbnailSize();
        if (!entering && fit == null) {
            placeWhenMeasured(false);
            return;
        }
        float endWidth = self.getWidth();
        float endHeight = self.getHeight();
        if (fit != null) {
            var thumb = preview.thumbnailBox();
            endWidth += fit[0] - thumb.getWidth();
            endHeight += fit[1] - thumb.getHeight();
        }

        var root = window.ui.rootElement.getRuntimeCache();
        AnchoredPlacement.Rect anchor = AnchoredPlacement.anchorRectInRoot(entry, window);
        var target = AnchoredPlacement.resolve(anchor, endWidth, endHeight,
                root.getWidth(), root.getHeight(), AnchoredPlacement.Side.TOP, GAP);

        // CENTRED OVER THE ENTRY, which resolve does not do and should not: it LEFT-ALIGNS on the cross
        // axis, which is what a dropdown wants -- a menu hangs from its button's left edge. A taskbar
        // preview is a label for the thing beneath it, so it sits over the middle of it, as Windows'
        // does. Re-clamped afterwards, or an entry near either end of a centred strip would push the
        // panel off the screen.
        float centred = anchor.x() + (anchor.width() - endWidth) / 2f;
        float widest = Math.max(0f, root.getWidth() - endWidth);
        target.x = Math.max(0f, Math.min(centred, widest));

        cancelMotion();

        // BOTH ARE POSITION ANIMATIONS, and the entrance only stopped being one after the fact.
        //
        // The rise was a TRANSFORM plus a fade, on the reasoning that a rise is the panel arriving at a
        // position it already has and so must leave AnchoredPlacement's placement unfought. That reason
        // does not hold: nothing else writes this panel's position, which is exactly why the slide is
        // allowed to animate it directly. And the two behaved differently -- a slide onto a minimised
        // window's entry was smooth while the rise onto the same entry snapped partway, with the picture
        // being drawn by identical code either way. Whatever the transform-and-fade path was doing to a
        // texture, the position path does not do, so there is no reason to keep two mechanisms for one
        // kind of motion.
        //
        // The panel therefore starts RISE below where it belongs and travels there, exactly as it
        // travels between entries. Opacity is left alone: at anything under 1 an element goes through a
        // screen-sized layer FBO, which for a panel containing a re-rendered window subtree is the most
        // expensive frame in the system, and a rise reads as an arrival without it.
        StyleGroup.importantPipeline(preview.getStyle().getGeneralGroup(), g -> g.opacity(1f));

        // THE PICTURE IS WHAT CHANGES SIZE, and the panel is never told to. Two windows are rarely the
        // same shape, so a move that only slid would change size once, abruptly, at the start of an
        // otherwise smooth journey -- and animating the PANEL's size instead is the version that was
        // tried and is worse in two ways. A width written every frame is a width PINNED at INLINE
        // origin: the panel was frozen at the first size it ever had, every later thumbnail changed
        // underneath it, and every stylesheet rule aimed at the problem landed beneath the inline write.
        // And even before it settled it was wrong, because the picture inside snapped to the new size on
        // the first frame while the box around it was still travelling -- so for the length of a move a
        // wide window's thumbnail hung out past the panel holding it.
        //
        // Animating the thumbnail fixes both at once and needs no third mechanism: the panel is sized by
        // its content, so it follows the picture frame by frame, and the header follows it too through
        // matchHeaderToThumbnail. Nothing writes a size onto the panel, so nothing can pin one.
        float fromLeft = entering ? target.x : morphLeft;
        float fromTop = entering ? target.y + RISE : morphTop;
        play(new WindowGeometryAnimation(preview, this::panelIsLive,
                fromLeft, fromTop, 0f, 0f, target.x, target.y, 0f, 0f,
                true, false, TRAVEL_NANOS, ProgressFunctions.Premade.OUT_QUAD, this::motionFinished));

        if (fit != null) morphThumbnail(window, fit[0], fit[1]);
    }

    /**
     * Runs the picture from the shape of the window the panel was showing to the shape of the new one.
     *
     * <p>{@code syncSize} is held off for the duration, because it and this write the same slot: it would
     * put the destination straight back over each intermediate frame and the morph would be invisible.
     * Handed back at the end with the final size applied, so the two agree about what is on screen.</p>
     */
    private void morphThumbnail(UIWindow window, float toWidth, float toHeight) {
        UIElement picture = preview.thumbnailElement();
        preview.setThumbnailSizingSuppressed(true);
        preview.applyThumbnailSize(morphThumbWidth, morphThumbHeight);
        WindowMotion started = new WindowGeometryAnimation(picture, this::panelIsLive,
                0f, 0f, morphThumbWidth, morphThumbHeight,
                0f, 0f, toWidth, toHeight,
                false, true, TRAVEL_NANOS, ProgressFunctions.Premade.OUT_QUAD, () -> {
                    thumbMotion = null;
                    preview.setThumbnailSizingSuppressed(false);
                    preview.applyThumbnailSize(toWidth, toHeight);
                });
        thumbMotion = started;
        window.registerTicker(started);
    }

    private void play(WindowMotion started) {
        motion = started;
        // UNHITTABLE WHILE IT MOVES, and this is a correctness fix rather than a nicety.
        //
        // The panel rises from RISE below its resting place while the gap to the entry is only GAP, so
        // for the first part of every entrance it physically COVERS the entry it belongs to -- and it is
        // promoted to the top layer, so it wins the hit test. The pointer therefore stops being over the
        // entry, becomes over the panel, and stops again as the panel rises clear: the entry's :hover
        // highlight blinks off and back on, the panel's own hover rules churn with it, and the whole
        // thing settles the instant the motion ends. Which is exactly how it was reported -- flickering
        // while it animates up, fine afterwards.
        //
        // It also removes a second, quieter fault: that churn ran the leave logic, so an entrance could
        // start its own grace timer against a pointer that had never actually gone anywhere.
        //
        // setHitTest covers the whole subtree, so the close button is unreachable for the 150ms this
        // lasts. That is the right trade: a control you cannot aim at yet is not a control.
        preview.setHitTest(false);
        UIWindow window = taskbar.getAttachedWindow();
        if (window != null) window.registerTicker(started);
    }

    /** Settled: the panel stops moving, so it can be aimed at again. @see #play */
    private void motionFinished() {
        motion = null;
        preview.setHitTest(true);
    }

    private void cancelMotion() {
        if (thumbMotion != null) {
            thumbMotion.cancel();
            thumbMotion = null;
            // NEVER LEFT SUPPRESSED. A cancelled morph that did not hand sizing back would leave the
            // picture stuck at whatever it had reached, for every window the panel showed afterwards.
            preview.setThumbnailSizingSuppressed(false);
        }
        if (motion == null) return;
        motion.cancel();
        motion = null;
        // A CANCELLED MOTION IS ONE ANOTHER IS REPLACING, and that one turns it off again immediately --
        // but a cancel with nothing following it (a dismiss) must not leave the panel permanently deaf.
        preview.setHitTest(true);
    }

    /**
     * Shows or hides the panel outright.
     *
     * <p>{@code display} rather than opacity: a hidden preview must take no space and, more to the
     * point, must not be HITTABLE — a full-size transparent panel parked over the strip would eat every
     * click meant for the entries underneath it.</p>
     */
    private void setPanelShown(boolean shown) {
        StyleGroup.importantPipeline(preview.getStyle().getLayoutGroup(),
                l -> l.display(shown ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
    }

    /** The panel stops being worth writing to once it has left the tree. @see WindowAnimation */
    private boolean panelIsLive() {
        return preview.getAttachedWindow() != null;
    }
}
