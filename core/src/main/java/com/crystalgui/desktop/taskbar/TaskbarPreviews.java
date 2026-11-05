package com.crystalgui.desktop.taskbar;

import com.crystalgui.core.window.WindowState;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.motion.WindowGeometryAnimation;
import com.crystalgui.desktop.motion.WindowMotion;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgraphics.easing.CgEasings;
import com.crystalgui.ui.service.AnchoredPlacement;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.service.Animation;
import com.crystalgui.ui.box.Box;
import com.crystalgui.style.StyleGroup;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import org.joml.Vector2f;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.control.Button;

import javax.annotation.Nullable;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import java.util.function.Supplier;

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
public final class TaskbarPreviews {

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

    /** The gap between the panel and the bar's top edge, in logical pixels. The rise itself is the sheet's. */
    private static final float GAP = 1f;

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
    /** A combined button's windows, side by side, in place of the one preview. @see WindowPreviewRow */
    private final WindowPreviewRow row = new WindowPreviewRow();
    /** The entry the row is up over, or null when it is not. */
    @Nullable
    private Button rowEntry;
    @Nullable
    private WindowMotion rowMotion;
    /** The windows of the combined entry the pointer rests on, while it has more than one. */
    @Nullable
    private List<WindowFrame> hoveredGroup;

    /** The one panel. */
    WindowPreview panel() {
        return preview;
    }

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
        previewFade = new Fade(preview);
        rowFade = new Fade(row);

        // PARKED IN THE TASKBAR so it has a tree to be promoted OUT of. Same idiom as DragGhost's: an
        // element must be somewhere before the top layer can take it.
        setPanelShown(false);
        taskbar.append(preview);

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

        row.setDisplayed(false);
        taskbar.append(row);
        row.onMouseEnter.attachListener((element, event) -> {
            pointerInPanel = true;
            wake();
        }, false, false);
        row.onMouseLeave.attachListener((element, event) -> {
            pointerInPanel = false;
            wake();
        }, false, false);
        row.onPicked.connect(frame -> {
            Desktop desktop = taskbar.desktop();
            dismiss();
            if (desktop != null) desktop.activate(frame, false);
        });
    }

    /** The window the panel is showing, or null when it is not up. */
    @Nullable
    WindowFrame showingFrame() {
        return showing;
    }

    /**
     * Wires one taskbar entry's hover. Called as each entry is built; {@code frame} is asked on every hover,
     * since a pin's entry has no window until its application runs and none again once it closes.
     */
    void watch(Button entry, Supplier<List<WindowFrame>> windows) {
        entry.onMouseEnter.attachListener((element, event) -> {
            List<WindowFrame> shown = windows.get();
            if (isSuppressed() || shown.isEmpty()) return;
            hovered = shown.size() == 1 ? shown.get(0) : null;
            hoveredGroup = shown.size() > 1 ? shown : null;
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
            if (hoveredEntry == entry) {
                hovered = null;
                hoveredGroup = null;
                hoveredEntry = null;
            }
            wake();
        }, false, false);
    }

    /**
     * Stops previews appearing at all — set while a jump list is open on an entry.
     *
     * <h3>A preview and a menu are alternatives, never both</h3>
     *
     * <p>Windows shows one or the other, and opening the menu cancels the preview. Both at once is not
     * merely busy: they occupy the same space above the same entry, so the panel sits over the menu it
     * is supposed to have been replaced by.</p>
     *
     * <p>Dismissing once is not enough, which is why this is held rather than a call. The pointer does
     * not leave the entry when the menu opens, so nothing takes the panel down again — the hover is
     * still live, the delay elapses under the open menu, and it comes straight back.</p>
     *
     * <p>HELD PER REASON: each jump list and a drag hold their own, and previews come back when the last lets go.
     * One flag let the list a right-click light-dismissed lift the hold of the list that same press opened.</p>
     */
    void setSuppressed(Object by, boolean held) {
        boolean was = isSuppressed();
        if (held) suppressedBy.add(by);
        else suppressedBy.remove(by);
        if (!was && isSuppressed()) dismiss();
    }

    boolean isSuppressed() {
        return !suppressedBy.isEmpty();
    }

    private final Set<Object> suppressedBy = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Takes the panel down at once — a click, a closed window, a rebuilt strip. */
    void dismiss() {
        hideRow(true);
        boolean visible = showing != null && !preview.hasClass(PLACING_CLASS);
        hovered = null;
        hoveredGroup = null;
        hoveredEntry = null;
        pointerInPanel = false;
        leftAt = 0L;
        pendingPlacement = false;
        showing = null;
        cancelMotion();
        if (visible) previewFade.start(this::previewGone);
        else previewGone();
    }

    /** The single preview off the screen, once any fade has landed. */
    private void previewGone() {
        preview.setFrame(null);
        UIDocument window = taskbar.document();
        if (window != null) window.demote(preview);
        setPanelShown(false);
        preview.setHitTest(true);
    }

    /**
     * On a panel while it fades out: the sheet's fade, after which it leaves.
     *
     * <p>WinUI closes a menu flyout with an 83ms fade. The length is the sheet's alone: closing writes
     * {@code display: none}, which the sheet's {@code display} transition holds laid out for the fade, and the
     * panel goes once it has no box. Its computed opacity cannot say when: a compositor runs the fade.</p>
     */
    public static final String CLOSING_CLASS = "__closing__";

    /** One panel's fade out, cancelled by showing it again. */
    private final class Fade {
        private final UIElement panel;
        /** Bumped by every start and cancel, so a superseded watch drops itself. */
        private int generation;

        private Fade(UIElement panel) {
            this.panel = panel;
        }

        void start(Runnable gone) {
            int mine = ++generation;
            panel.setHitTest(false);
            panel.addClass(CLOSING_CLASS);
            setDisplay(false);
            UIDocument window = taskbar.document();
            if (window == null) {
                finish(gone);
                return;
            }
            window.animation().afterLayout(panel, delta -> {
                if (generation != mine) return false;
                if (panel.box() != null) return true;
                finish(gone);
                return false;
            });
        }

        void cancel() {
            generation++;
            panel.removeClass(CLOSING_CLASS);
            setDisplay(true);
        }

        private void finish(Runnable gone) {
            generation++;
            panel.removeClass(CLOSING_CLASS);
            gone.run();
            setDisplay(true);
        }

        /** Inline, as a popover's: the one value the sheet's {@code display} transition holds for the fade. */
        private void setDisplay(boolean shown) {
            StyleGroup.inlinePipeline(panel.getStyle().getLayoutGroup(),
                    l -> l.display(shown ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
        }
    }

    private final Fade previewFade;
    private final Fade rowFade;

    /**
     * Starts the per-frame check, if it is not already running.
     *
     * <p>A ticker rather than a timer because the whole decision — has the wait elapsed, is the pointer
     * still anywhere that counts — is a per-frame question anyway, and the frame delta is the clock
     * everything else here advances on. It drops itself the moment there is nothing to watch.</p>
     */
    private void wake() {
        UIDocument window = taskbar.document();
        if (window == null) return;
        window.animation().everyIfAbsent(taskbar, watch);
    }

    /** Held, so the service can tell it is already live. @see Animation#everyIfAbsent */
    private final Animation.Hook watch = this::watchFrame;

    private boolean watchFrame(float deltaSeconds) {
        return taskbar.document() != null && update();
    }

    /** Asks for a placement on a later frame, once whatever it is waiting on has been measured. */
    private void placeWhenMeasured(boolean entering) {
        pendingPlacement = true;
        pendingEntering = entering;
    }

    /** @return whether there is still anything to watch */
    private boolean update() {
        // NOTHING NEW WHILE HELD, whatever hover was already counting down
        if (isSuppressed()) return false;
        if (pendingPlacement) {
            pendingPlacement = false;
            placeAndPlay(pendingEntering);
            return true;
        }
        if (rowEntry != null) {
            // PLACED ONCE THE PICTURES HAVE SETTLED, as the single preview is: against a box measured
            // before they took their windows' shapes it sat a picture's height too high
            boolean changed = row.sync();
            if (row.windows().isEmpty()) {
                dismiss();
                return false;
            }
            if (!changed && (row.hasClass(PLACING_CLASS) || rowPlacedHeight != heightOf(row))) placeRow();
        }
        UIDocument here = taskbar.document();
        if (hoveredGroup != null && here != null) {
            leftAt = 0L;
            boolean asked = System.nanoTime() - hoveredSince >= HOVER_DELAY_NANOS
                    || showing != null || rowEntry != null;
            // ALREADY UP somewhere: no second wait, as between single previews
            if (asked && rowEntry != hoveredEntry) showRow(hoveredEntry, hoveredGroup);
            return true;
        }

        // A PREVIEW OF A WINDOW THAT NO LONGER EXISTS. Closing one from its own preview is the ordinary
        // route here: the entry goes, so no mouseleave will ever arrive to take the panel down with it.
        if (showing != null && showing.state() == WindowState.DESTROYED) {
            dismiss();
            return false;
        }

        UIDocument window = taskbar.document();
        if (hovered != null && window != null) {
            leftAt = 0L;
            boolean elapsed = System.nanoTime() - hoveredSince >= HOVER_DELAY_NANOS;
            preview.syncThumbnail();
            // FROM A ROW TO ONE WINDOW: the row goes and the preview comes up without a second wait
            if (rowEntry != null) {
                hideRow(false);
                elapsed = true;
            }
            if (showing == null && elapsed) {
                show(hovered);
            } else if (showing != null && showing != hovered) {
                // NO SECOND WAIT. The question has already been asked; this is the panel travelling.
                //
                // WHERE IT IS NOW, BEFORE THE CONTENT CHANGES. Pointing the panel at another window
                // resizes its thumbnail, and the panel with it, so anything measured afterwards is the
                // destination -- the move would have nothing to travel from and would only slide.
                Box was = preview.box();
                Box rootBox = window.box();
                Box wasThumb = preview.thumbnailBox();
                if (was == null || rootBox == null || wasThumb == null) return true;
                // THROUGH THE ROOT'S INVERSE, never by subtracting its origin. A promoted panel is
                // hosted by the top layer rather than by the taskbar, so its `Box.x()` is an offset
                // within THAT -- and a subtraction of two world origins cancels the root's translation
                // and leaves its SCALE, which is the uiScale error AnchoredPlacement records.
                Vector2f origin = Box.originIn(was, rootBox);
                morphLeft = origin.x;
                morphTop = origin.y;
                morphThumbWidth = wasThumb.width();
                morphThumbHeight = wasThumb.height();
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
        if (showing != null || rowEntry != null) {
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

    /** Shows a combined entry's row now, without the hover's wait: what clicking it does. */
    void showNow(Button entry) {
        List<WindowFrame> windows = taskbar.windowsOf(entry);
        if (windows.size() < 2) return;
        hoveredEntry = entry;
        hoveredGroup = windows;
        hovered = null;
        showRow(entry, windows);
        wake();
    }

    private void showRow(Button entry, List<WindowFrame> windows) {
        // ONE PANEL AT A TIME: the single preview gives way
        if (showing != null) {
            showing = null;
            cancelMotion();
            preview.setFrame(null);
            taskbar.document().demote(preview);
            setPanelShown(false);
        }
        rowFade.cancel();
        rowEntry = entry;
        row.setWindows(windows);
        row.setDisplayed(true);
        taskbar.document().promote(row);
        // Invisible until it has a size to be placed by, and unhittable until it has risen, as the single preview is
        row.addClass(PLACING_CLASS);
        row.setHitTest(false);
    }

    /** {@code entry}'s extent along the bar and the bar's across it: a panel sits on the bar's edge, as Windows' do. */
    @Nullable
    private AnchoredPlacement.Rect onTheBar(Button entry, UIDocument window) {
        AnchoredPlacement.Rect own = AnchoredPlacement.anchorRectInRoot(entry, window);
        AnchoredPlacement.Rect bar = AnchoredPlacement.anchorRectInRoot(taskbar, window);
        if (own == null || bar == null) return own;
        return new AnchoredPlacement.Rect(own.x(), bar.y(), own.width(), bar.height());
    }

    /** Centred over its entry and rising into place, as the single preview does. */
    private void placeRow() {
        UIDocument window = taskbar.document();
        Button entry = rowEntry;
        Box self = row.box();
        if (window == null || entry == null || self == null || self.width() <= 0f || self.height() <= 0f) return;
        Box root = window.box();
        AnchoredPlacement.Rect anchor = onTheBar(entry, window);
        if (root == null || anchor == null) return;
        var target = AnchoredPlacement.resolve(anchor, self.width(), self.height(),
                root.width(), root.height(), AnchoredPlacement.Side.TOP, GAP);
        float centred = anchor.x() + (anchor.width() - self.width()) / 2f;
        target.x = Math.max(0f, Math.min(centred, Math.max(0f, root.width() - self.width())));
        boolean entering = row.hasClass(PLACING_CLASS);
        row.removeClass(PLACING_CLASS);
        rowPlacedHeight = self.height();
        if (rowMotion != null) rowMotion.cancel();
        // PLACED WHERE IT RESTS: the rise is the sheet's, started by dropping the placing class above. Held for
        // the length of a move so the row takes no pointer until it has arrived.
        rowMotion = new WindowGeometryAnimation(row, this::rowIsLive,
                target.x, target.y, 0f, 0f, target.x, target.y, 0f, 0f,
                true, false, entering ? TRAVEL_NANOS : 0L, CgEasings.OUT_QUAD, () -> {
                    rowMotion = null;
                    row.setHitTest(true);
                });
        window.animation().every(row, rowMotion);
    }

    /** The row's height when it was last placed: a different one means it has to be placed again. */
    private float rowPlacedHeight = -1f;

    private static float heightOf(UIElement element) {
        Box box = element.box();
        return box == null ? -1f : box.height();
    }

    private boolean rowIsLive() {
        return row.parent() != null && rowEntry != null;
    }

    /** @param fade whether it fades out, as a dismissal does; giving way to the single preview is at once */
    private void hideRow(boolean fade) {
        if (rowEntry == null) return;
        rowEntry = null;
        if (rowMotion != null) {
            rowMotion.cancel();
            rowMotion = null;
        }
        if (fade && !row.hasClass(PLACING_CLASS)) rowFade.start(this::rowGone);
        else rowGone();
    }

    private void rowGone() {
        row.setHitTest(true);
        row.clear();
        UIDocument window = taskbar.document();
        if (window != null) window.demote(row);
        row.setDisplayed(false);
    }

    private void show(WindowFrame frame) {
        previewFade.cancel();
        showing = frame;
        // A FRESH SHOW HAS NO MORPH IN FLIGHT, so nothing may be holding the picture's size. Belt and
        // braces beside cancelMotion's hand-back: an entrance that inherited a suppressed thumbnail
        // placed a panel around a picture of the previous window's shape.
        preview.setThumbnailSizingSuppressed(false);
        preview.setFrame(frame);
        setPanelShown(true);
        taskbar.document().promote(preview);
        // INVISIBLE FOR ONE FRAME, deliberately. The panel has never been laid out, so it has no size --
        // and a preview is placed RELATIVE TO ITS OWN SIZE, being anchored above the entry. Showing it
        // before it has been measured would put a full-size panel at the origin for a frame.
        //
        // A CLASS, not an opacity written from Java: the same idiom Popover and InputDialog reached for,
        // and for the reason the standing row gives -- a resting value written from here outranks every
        // rule that would ever want to restyle it, so the hiding belongs in the sheet and this only says
        // WHEN. Dropping the class hands the property back exactly as withdrawing a candidate did.
        preview.addClass(PLACING_CLASS);
        // UNHITTABLE UNTIL IT HAS SETTLED: an invisible panel under the pointer takes the entry's hover, and
        // the placement then dismisses it for having no entry -- a loop at the hover delay. @see #play
        preview.setHitTest(false);
        placeWhenMeasured(true);
    }

    /**
     * Puts the panel where it belongs, and animates it getting there.
     *
     * @param entering whether this is a first appearance, which rises in from the sheet's placing state where it
     *                 belongs, rather than a move between entries, which starts wherever it already is
     */
    private void placeAndPlay(boolean entering) {
        UIDocument window = taskbar.document();
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
        Box self = preview.box();
        if (self == null || self.width() <= 0f || self.height() <= 0f) {
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
        float endWidth = self.width();
        float endHeight = self.height();
        if (fit != null) {
            Box thumb = preview.thumbnailBox();
            if (thumb == null) {
                placeWhenMeasured(entering);
                return;
            }
            endWidth += fit[0] - thumb.width();
            endHeight += fit[1] - thumb.height();
        }

        Box root = window.box();
        AnchoredPlacement.Rect anchor = onTheBar(entry, window);
        if (root == null || anchor == null) {
            placeWhenMeasured(entering);
            return;
        }
        var target = AnchoredPlacement.resolve(anchor, endWidth, endHeight,
                root.width(), root.height(), AnchoredPlacement.Side.TOP, GAP);

        // CENTRED OVER THE ENTRY, which resolve does not do and should not: it LEFT-ALIGNS on the cross
        // axis, which is what a dropdown wants -- a menu hangs from its button's left edge. A taskbar
        // preview is a label for the thing beneath it, so it sits over the middle of it, as Windows'
        // does. Re-clamped afterwards, or an entry near either end of a centred strip would push the
        // panel off the screen.
        float centred = anchor.x() + (anchor.width() - endWidth) / 2f;
        float widest = Math.max(0f, root.width() - endWidth);
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
        // A FIRST APPEARANCE IS PLACED WHERE IT RESTS, and rises in from the sheet's placing state as the class
        // drops: WinUI's flyout slide, a transform and a clip with no fade. Opacity is left alone: at anything
        // under 1 an element goes through a layer, which for a panel holding a window's picture is the most
        // expensive frame in the system. The move between entries stays a position animation.
        preview.removeClass(PLACING_CLASS);

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
        float fromTop = entering ? target.y : morphTop;
        play(new WindowGeometryAnimation(preview, this::panelIsLive,
                fromLeft, fromTop, 0f, 0f, target.x, target.y, 0f, 0f,
                true, false, TRAVEL_NANOS, CgEasings.OUT_QUAD, this::motionFinished));

        if (fit != null) morphThumbnail(window, fit[0], fit[1]);
    }

    /**
     * Runs the picture from the shape of the window the panel was showing to the shape of the new one.
     *
     * <p>{@code syncSize} is held off for the duration, because it and this write the same slot: it would
     * put the destination straight back over each intermediate frame and the morph would be invisible.
     * Handed back at the end with the final size applied, so the two agree about what is on screen.</p>
     */
    private void morphThumbnail(UIDocument window, float toWidth, float toHeight) {
        UIElement picture = preview.thumbnailElement();
        preview.setThumbnailSizingSuppressed(true);
        preview.applyThumbnailSize(morphThumbWidth, morphThumbHeight);
        WindowMotion started = new WindowGeometryAnimation(picture, this::panelIsLive,
                0f, 0f, morphThumbWidth, morphThumbHeight,
                0f, 0f, toWidth, toHeight,
                false, true, TRAVEL_NANOS, CgEasings.OUT_QUAD, () -> {
                    thumbMotion = null;
                    preview.setThumbnailSizingSuppressed(false);
                    preview.applyThumbnailSize(toWidth, toHeight);
                });
        thumbMotion = started;
        window.animation().every(picture, started);
    }

    private void play(WindowMotion started) {
        motion = started;
        // UNHITTABLE WHILE IT MOVES: a panel travelling under a resting pointer churns its hover rules and
        // the leave logic, which can start the grace timer against a pointer that never moved. The close
        // button is out of reach for the 150ms; a control you cannot aim at yet is not a control.
        preview.setHitTest(false);
        UIDocument window = taskbar.document();
        if (window != null) window.animation().every(preview, started);
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
            // THE BOX IS MID-MORPH AND THE RECORD SAYS OTHERWISE. The animation writes the picture's
            // INLINE size without going through the thumbnail, so after a cancel the box holds an
            // intermediate size while the thumbnail's own "last applied" still says the morph's start.
            // Forgetting it makes the next sync write unconditionally.
            preview.forgetThumbnailSize();
        }
        // NEVER LEFT SUPPRESSED -- and UNCONDITIONALLY, which is the half that was missing. Sizing is
        // switched off the moment a move begins (update, before setFrame) and only ever switched back on
        // by a morph COMPLETING, or here when one is cancelled. A move abandoned before its morph was
        // created -- the pointer left, a click dismissed the panel, the destination was still being
        // measured -- had no thumbMotion to cancel and so left sizing off for good: every later preview
        // kept whatever box it had, and a wide window was drawn letterboxed in a tall one's. Reported as
        // "you messed up the sizes of the previews", and it was the first pictureless window in the
        // strip that made the abandoned move routine.
        preview.setThumbnailSizingSuppressed(false);
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
    /** On the panel while it is being placed and has no size to be placed against. @see #placeAndPlay */
    public static final String PLACING_CLASS = "__placing__";

    private void setPanelShown(boolean shown) {
        // `setDisplayed`, the engine's own attribute, where the old engine wrote `display` at IMPORTANT.
        // The M6.0 rule: this selector engine has no attribute selectors, so HTML's `[hidden] { display:
        // none }` cannot be written -- the box tree gives a hidden node no box instead, exactly as it
        // gives none to `display: none`, which is what the IMPORTANT write effectively did anyway.
        preview.setDisplayed(shown);
    }

    /** The panel stops being worth writing to once it has left the tree. @see WindowAnimation */
    private boolean panelIsLive() {
        return preview.document() != null;
    }
}
