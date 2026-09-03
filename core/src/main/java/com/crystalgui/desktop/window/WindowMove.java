package com.crystalgui.desktop.window;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIElement;
import org.joml.Vector2f;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.ui.dom.UIDocument;

import javax.annotation.Nullable;

/**
 * Moving a window with the POINTER — the caption drag, Alt-drag, and drag-to-edge snap.
 *
 * <h3>Why this is a class and not thirty lines in the frame</h3>
 *
 * <p>{@link WindowKeyboardMove} is the same gesture from the keyboard and has been its own class since
 * it was written. The pointer half sitting inline was the asymmetry — and the two are not related as
 * senior and junior partner: this one owns two listeners, a live drag, the snap zone that drag is
 * hovering, and the restore-under-pointer arithmetic, which is more moving state than the keyboard mode
 * has.</p>
 *
 * <p>It is also where every coordinate-space rule in the compositor collects, and they are the kind
 * that place a window neatly in the wrong spot rather than failing. <b>A mouse-DOWN listener's
 * coordinates are RAW surface pixels; a drag callback's have already been converted.</b> At the default
 * {@code uiScale} of 2 those are a factor apart, which reads as a bad constant rather than as the wrong
 * frame of reference. {@code containsScreenPoint} is the giveaway for which is which — it takes the raw
 * one.</p>
 *
 * <p><b>Converted to an offset WITHIN THE SOURCE, and this paragraph said the opposite until M6.1.</b>
 * The old {@code screenToLocal} divided out the surface scale and did NOT subtract the element's own
 * position, so its answer was an absolute layout coordinate — {@code isMouseOverElement} compared it
 * against {@code rectX}, and {@link #beginTearLoose} subtracted the bar's X to get an offset along the
 * caption. It cost a bug in each direction, because reading "local" as "relative to the source"
 * invited adding the origin back, which is what {@link #snapZoneAt} once did.</p>
 *
 * <p>{@code UIElement.toLocal} puts the box's own origin at zero, which is what the name always said and
 * what nearly every caller wants — a caret index, a slider fraction, a drag delta. The consequence
 * here is that the ARITHMETIC INVERTS: an offset along the caption needs no subtraction, and reaching
 * the work area's space needs the caption's position ADDED. Every site below was re-derived rather
 * than translated, because a comment explaining a coordinate space is describing an engine.</p>
 *
 * <h3>It reaches the frame through the frame's own API</h3>
 *
 * <p>The same seam {@link WindowKeyboardMove} has, and deliberately no wider: {@code moveTo},
 * {@code resizeTo}, {@code maximize}, {@code restore}, {@code left}/{@code top}. Two things it needs are
 * package-private rather than public — {@link WindowFrame#workArea()} and
 * {@link WindowFrame#markPlaced()} — and both are that way because a collaborator in this package
 * cannot ask for them otherwise, not because a caller outside it should be able to.</p>
 */
final class WindowMove {

    private final WindowFrame frame;

    /** The drag handle, which is also the drag SOURCE every callback coordinate is converted against. */
    private final UIElement bar;

    /** Where the window was when the current drag began. @see #beginMove */
    private float dragStartLeft, dragStartTop;

    /** The zone the live move drag is currently over. @see #snapZoneAt */
    @Nullable
    private SnapZones.Zone pendingSnap;

    /** Whether this drag tore a MAXIMISED window loose, and where along its caption. @see #beginTearLoose */
    private boolean tornLoose;
    private float grabFromLeft, grabFromRight, grabFromTop;

    /**
     * Wires both press gestures onto {@code frame}.
     *
     * <p>Nothing retains the returned object and nothing needs to: the listeners capture it and the
     * frame's own {@code EventListenerGroup} holds them, so it lives exactly as long as the window it
     * moves. A field on the frame would be one nothing ever reads.</p>
     */
    static void install(WindowFrame frame) {
        new WindowMove(frame).attach();
    }

    private WindowMove(WindowFrame frame) {
        this.frame = frame;
        this.bar = frame.titleBar();
    }

    private void attach() {
        // TARGET AND BUBBLE, with the controls filtered out by hand -- see captionPressIsAControl.
        //
        // It was target-only, which is Dialog's spelling, and the reason was sound: the two booleans are
        // ADDITIVE, so subscribing the bubble phase also hears every press on the close button, and a
        // press there would start a window drag as well as closing the window.
        //
        // What that misses is that target-only can only ever hear presses on the BAR ITSELF, which works
        // exactly as long as everything in the caption is unhittable. The frame's own title label is, so
        // it held -- until a window ADOPTED somebody else's header into its caption (WindowChrome), and a
        // panel's title is an ordinary hittable UIText. So a floating Notifications window could not be
        // dragged by the word "Notifications", only by the gap beside it, which reads as the window
        // being stuck rather than as the label being in the way.
        //
        // The frame cannot fix that by reaching into the adopted chrome and unhitting parts of it: it
        // does not own that subtree, and setHitTest applies to a whole subtree, so it would take the
        // header's own buttons out with the label.
        bar.onMouseDown.attachListener((element, event) -> {
            // THE LEFT BUTTON MOVES A WINDOW, and nothing else does.
            //
            // A drag ends when THE BUTTON THAT STARTED IT is released, and startDrag defaults to the
            // left one -- so a right-press began a move registered against a button that was never
            // going to come up, and the window then followed the cursor for ever with nothing held
            // down. There is no way out of that state short of a left-click somewhere.
            //
            // The defect is older than the right-click that exposes it: this listener never checked a
            // button. It was simply unreachable until W13a put the system menu on a caption
            // right-click, because until then nobody had a reason to press anything but the left
            // button on a title bar. The engine's own note on startDrag records the same hazard from
            // the middle-button side.
            if (event.getButtonId() != CgMouseCodes.LEFT_BUTTON) return;
            if (captionPressIsAControl(((UIElement) event.getTarget()))) return;
            // ON THE BAR, and this guard belongs HERE rather than inside beginMove.
            //
            // A synthesized activation press (Space/Enter on a focused element) carries the cursor's
            // position, which may be nowhere near the bar — honouring one teleports the window. That is a
            // statement about presses dispatched AT this element, which is what this listener receives.
            //
            // It sat in beginMove and silently disabled Alt-drag the moment that arrived: Alt-drag
            // presses the CONTENT by definition, so "is the pointer on the caption" was false every time
            // and the move was refused before anything could go wrong. Nothing failed; the gesture simply
            // did nothing at all.
            if (!bar.containsSurfacePoint(event.getPosition().x(), event.getPosition().y())) return;
            beginMove(event.getPosition().x(), event.getPosition().y(), event.getDetail());
        }, false, true);

        // ALT-DRAG: hold the desktop's move modifier and drag anywhere inside the window — W13b.
        //
        // The Linux WM staple, and the answer for a window whose title bar is tiny, covered by adopted
        // chrome, or off the top of the work area entirely.
        //
        // CAPTURE PHASE, which is the whole of what makes "anywhere" true. The press has to be taken
        // before it reaches whatever is under it — a button, an editor, a tree row — and a bubble-phase
        // listener sees only what nothing else consumed. It also has to stopPropagation, or the window
        // moves AND the thing beneath it is clicked.
        //
        // The modifier is read from the DESKTOP, never named here: Alt is contested territory in this
        // application and a widget is the wrong place to decide. @see Desktop#moveModifier()
        frame.onMouseDown.attachListener((element, event) -> {
            if (event.getButtonId() != CgMouseCodes.LEFT_BUTTON) return;
            Desktop desktop = frame.desktop();
            if (desktop == null) return;
            int mask = desktop.moveModifier();
            var input = CgPlatform.input();
            if (mask == 0 || input == null) return;
            if ((input.getCurrentModifiers() & mask) != mask) return;
            event.stopPropagation();
            // Raised first, because a press that never reaches the frame's own activation would
            // otherwise move a window without bringing it forward.
            desktop.activate(frame);
            beginMove(event.getPosition().x(), event.getPosition().y(), 1);
        }, true, false);
    }

    /**
     * Whether a press in the caption belongs to something in it rather than to the caption.
     *
     * <p><b>Focusability is the test</b>, and it is not a proxy — it is the question. A control is a
     * thing you can put the keyboard on: every button in the caption is focusable, and nothing that is
     * merely being displayed there is. A window's title, an icon, an adopted panel header's label are
     * all {@code FocusPolicy.NONE}, so they read as caption, which is what they look like.</p>
     *
     * <p>Walked up to the bar and no further, so the FRAME's own focusability — it is
     * {@code CLICK_NOT_TABBABLE}, deliberately — cannot answer yes for every press in it.</p>
     */
    private boolean captionPressIsAControl(@Nullable UIElement target) {
        for (UIElement walk = target; walk != null && walk != bar; walk = walk.parentElement()) {
            if (walk.focusPolicy().isFocusable()) return true;
        }
        return false;
    }

    private void beginMove(float pointerX, float pointerY, int detail) {
        UIDocument window = frame.document();
        if (window == null) return;

        // DOUBLE-CLICK TOGGLES, and starts no drag. Windows' gesture. Returning here matters: the second
        // press would otherwise begin a move as well, so the smallest tremor after a double-click would
        // drag the window it had just restored.
        //
        // EVERY SECOND CLICK OF A RUN, not every click after the first. `detail` counts UP for as long as
        // presses keep landing in the same place inside the system's double-click interval -- 1, 2, 3,
        // 4 -- so `>= 2` toggled on the third press, and the fourth, and the fifth. Clicking about on a
        // caption at a leisurely pace maximised and restored the window over and over, which reads as the
        // double-click interval being far too long when it is really every click after the first counting
        // as a double.
        //
        // Even counts only, which is what a browser's `dblclick` does with the same counter: a third
        // press is a single click again (and falls through to starting a move, as it does on Windows),
        // and a fourth completes a second pair.
        if (detail >= 2 && detail % 2 == 0) {
            frame.toggleMaximized();
            return;
        }

        // MOVING LEAVES THE TILED GROUP. A window carried away from its cell is no longer in the layout,
        // so it must stop being a partner in any joint resize -- otherwise dragging a neighbour's divider
        // would reach out and re-tile a window sitting somewhere else entirely. Resizing deliberately does
        // NOT clear it, which is the whole of joint resize: the cell stays, its edge moves.
        frame.clearSnappedZone();

        // FROM WHERE THE WINDOW IS, not from what was last asked for. A window currently held at the
        // edge by the clamp has a wanted position further out; starting a drag from that would spend the
        // difference before anything moved.
        dragStartLeft = frame.left();
        dragStartTop = frame.top();

        // Positional drag, zero threshold: a window must track the very first pixel, and a title bar has
        // no competing click interpretation to protect.
        frame.setMoving(true);
        Drag.start(bar, pointerX, pointerY, new Drag.Listener() {
            @Override
            public void onDragUpdate(float mouseX, float mouseY, float startX, float startY,
                                     float deltaX, float deltaY) {
                frame.markPlaced();
                // A MAXIMISED WINDOW RESTORES ON THE FIRST MOVEMENT, never on the press.
                //
                // Windows' restore-drag, and the ordering is what makes it work rather than a
                // detail: restoring on the press means the FIRST press of a double-click restores
                // and the second re-maximises, so double-clicking a maximised caption appears to do
                // nothing at all. Click-and-hold on a maximised title bar does nothing there too --
                // it is the movement that tears the window loose.
                if (frame.isMaximized()) {
                    if (deltaX == 0f && deltaY == 0f) return;
                    beginTearLoose(mouseX, mouseY);
                } else if (tornLoose) {
                    // RE-ANCHORED EVERY FRAME, against the width the window has RIGHT NOW.
                    //
                    // The shrink animates the size while this owns the position, so a position worked
                    // out once from the FINAL width holds the left edge still and lets the right edge
                    // converge onto it -- the window collapses leftwards instead of closing in around
                    // the cursor, and only arrives under the hand when the animation ends.
                    //
                    // Asking the current width each frame keeps the grabbed point under the pointer for
                    // every frame of the shrink, from either edge or the middle. It costs nothing once
                    // the size settles: a constant width gives a constant offset, which is the same
                    // thing the delta-based move below computes.
                    anchorUnderPointer(mouseX, mouseY);
                } else {
                    frame.moveTo(dragStartLeft + deltaX, dragStartTop + deltaY);
                }
                // SNAP IS DECIDED FROM THE POINTER, never from the window's own edge. Dragging a
                // wide window leftwards puts its edge at the boundary long before the hand gets
                // there, so an edge test snaps windows nobody was aiming at an edge with -- and a
                // narrow one could never reach a band at all. Every desktop reads the pointer.
                pendingSnap = snapZoneAt(mouseX, mouseY);
                Desktop desktop = frame.desktop();
                if (desktop != null) {
                    // The FRAME goes with it: the preview morphs out of the window being dragged and
                    // back into it, so it needs to know which one. @see Desktop#showSnapPreview
                    if (pendingSnap != null) desktop.showSnapPreview(pendingSnap, frame);
                    else desktop.hideSnapPreview();
                }
            }

            @Override
            public void onDragEnd(float mouseX, float mouseY) {
                commitSnap();
                tornLoose = false;
                frame.setMoving(false);
            }

            @Override
            public void onDragCancel() {
                // ESCAPE DURING A MOVE ABANDONS THE SNAP TOO. The preview is the only part of a
                // cancelled drag that would otherwise stay on screen, with nothing left to take it down.
                pendingSnap = null;
                tornLoose = false;
                frame.setMoving(false);
                Desktop desktop = frame.desktop();
                if (desktop != null) desktop.hideSnapPreview();
            }
        });
    }

    /**
     * The zone a drag is over, in the WORK AREA's space.
     *
     * <h3>A drag callback's coordinates are relative to the SOURCE — one addition, not a subtraction</h3>
     *
     * <p><b>This method's own javadoc used to say the opposite, and it was right about the old
     * engine.</b> {@code UIDragController.tick} ran {@code screenToLocal} against the drag source,
     * which converted out of surface pixels into the source's layout space and did NOT subtract the
     * source's own origin — so the coordinate was effectively absolute, and the work area's origin had
     * to come off it. The version before that added the bar's origin back and counted it twice, which
     * shipped: the zone a drag reported was displaced by however far along the desktop the window
     * happened to be.</p>
     *
     * <p>M6.1 moved that origin. {@code Drag} converts with {@code UIElement.toLocal}, which puts the
     * source's own origin at zero — so the pointer is now an offset within the CAPTION, and reaching
     * the work area's space means adding the caption's position rather than subtracting the area's.
     * Keeping the old arithmetic reintroduces the original bug from the other side. A comment that
     * explains a coordinate space is describing an ENGINE, and the port changes engines.</p>
     *
     * <p>Through {@link Box#originIn}, which carries every transform and scroll between the two boxes —
     * a subtraction of world origins would cancel the root's translation and leave its SCALE, the
     * {@code uiScale} error {@code AnchoredPlacement} records.</p>
     */
    @Nullable
    private SnapZones.Zone snapZoneAt(float mouseX, float mouseY) {
        Desktop desktop = frame.desktop();
        if (desktop == null || frame.isToolWindow()) return null;
        Box area = desktop.windowLayer().box();
        Box barBox = bar.box();
        if (area == null || barBox == null) return null;
        Vector2f barOrigin = Box.originIn(barBox, area);
        // THE CAPTION'S HEIGHT IS THE TOP BAND -- see SnapZones. The pointer rides inside the caption
        // for the whole drag, so anything smaller is unreachable for all but the shallowest grab.
        return SnapZones.forPoint(barOrigin.x + mouseX, barOrigin.y + mouseY,
                area.width(), area.height());
    }

    /** Applies whatever the drag was hovering when it ended. @see #snapZoneAt */
    private void commitSnap() {
        SnapZones.Zone zone = pendingSnap;
        pendingSnap = null;
        Desktop desktop = frame.desktop();
        // AT ONCE, not the contracting hide. The window is about to take the very rect the preview is
        // occupying, so animating the preview back to where the window used to be would play the
        // gesture backwards beside the thing playing it forwards.
        if (desktop != null) desktop.hideSnapPreviewNow();
        if (zone == null || desktop == null) return;

        if (zone == SnapZones.Zone.MAXIMIZE) {
            // THROUGH maximize(), so the restore rect, the class, the glyph and the animation are all
            // the ones a maximise already has. A snap that wrote the rect itself would be a second
            // maximise that the restore button knew nothing about.
            frame.maximize();
            return;
        }
        // THROUGH THE DESKTOP, which owns where the group's dividers currently are -- computing the rect
        // here would tile against halves and silently undo a layout somebody had dragged to 3:1.
        desktop.snapFrameTo(frame, zone);
    }

    /**
     * Restores a maximised window <b>around the pointer</b>, so a drag that begins on its caption
     * carries on from where the hand already is.
     *
     * <p>The pointer keeps its fraction across the caption: grab a maximised window three-quarters of
     * the way along its title bar and the restored window appears with the cursor three-quarters along
     * <em>its</em> title bar. Keeping the left edge instead — the obvious alternative — makes a window
     * grabbed on its right-hand side leap out from under the cursor, which is why no window manager
     * does it that way. The vertical offset inside the caption is simply preserved, since the caption's
     * height does not change.</p>
     *
     * <p>Measured before restoring and applied after, using the <em>recorded</em> restore width rather
     * than a fresh measurement: layout has not run yet at this point, so the box still reports the
     * maximised size.</p>
     */
    /**
     * Tears a maximised window loose: captures where along the caption it was grabbed, starts the
     * shrink, and places it under the pointer for this frame.
     *
     * <p>The offsets are taken from the MAXIMISED caption and kept for the rest of the drag, because
     * they are what the grab meant. {@link #anchorUnderPointer} re-reads the window's current width
     * every frame and re-derives the placement from them, so the window closes in around the cursor
     * instead of collapsing toward one edge.</p>
     *
     * <p><b>The coordinates are already in layout units.</b> {@code UIDragController.tick} runs
     * {@code screenToLocal} against the drag SOURCE before calling the listener -- that conversion is
     * most of why the callback exists -- so a second one here halves them and the window comes back at
     * about half the distance across the caption. A mouse-DOWN listener's position is the other way
     * round: that one is raw, which is why the caption guard uses {@code containsScreenPoint}.</p>
     */
    private void beginTearLoose(float pointerX, float pointerY) {
        Box barBox = bar.box();
        if (barBox == null) return;
        // NO SUBTRACTION. The pointer is already an offset within the BAR, which is exactly what
        // "along the caption" means -- the old engine handed over an absolute coordinate and had to
        // have the bar's left taken off it.
        grabFromLeft = pointerX;
        grabFromRight = barBox.width() - pointerX;
        // The caption's height does not change, so the frame's own top keeps the pointer at the same
        // place DOWN the bar for the whole gesture. The bar sits at its own offset inside the frame,
        // so that offset is ADDED rather than the frame's own top subtracted.
        grabFromTop = barBox.y() + pointerY;

        tornLoose = true;
        frame.restoreShrinkingUnderDrag();
        anchorUnderPointer(pointerX, pointerY);
    }

    /**
     * Places the window so the grabbed point sits under the pointer, <b>at the width it has right
     * now</b>.
     *
     * <h3>Anchored to an edge where the caption's content is, and centred where it is not</h3>
     *
     * <p>A caption's content does not rescale with the window: a menu bar adopted into it runs from the
     * LEFT, the window controls sit at the RIGHT, and each keeps its distance from its own edge. So the
     * cursor stays on what was grabbed only if it is measured from the same edge that thing is measured
     * from -- the left offset near the left, the right offset near the right. A FRACTION of the caption
     * preserves neither, and was only ever right at the corners, where the two agree.</p>
     *
     * <p>The MIDDLE of a maximised caption is empty, so there is nothing to preserve there -- and the two
     * edge rules cannot both be honoured anyway, since neither offset fits inside a window a fraction of
     * the width. Clamping into the window instead put the pointer on whichever edge won and left the
     * whole window hanging off one side of it.</p>
     *
     * <p>Half the current width is each edge's reach, which makes the three cases one CONTINUOUS
     * function: where an edge rule stops applying it already answers the window's centre, which is
     * exactly what the middle case answers.</p>
     */
    private void anchorUnderPointer(float pointerX, float pointerY) {
        UIElement area = frame.workArea();
        Box areaBox = area == null ? null : area.box();
        Box barBox = bar.box();
        Box self = frame.box();
        if (areaBox == null || barBox == null || self == null) return;

        float width = self.width();
        if (width <= 0f) return;

        // THE POINTER IN THE WORK AREA'S SPACE, by adding the caption's position rather than by
        // subtracting the area's. The old engine was handed an absolute coordinate and took the area's
        // origin off it; here the coordinate starts inside the bar, so the whole chain runs the other
        // way. @see #snapZoneAt
        Vector2f barOrigin = Box.originIn(barBox, areaBox);
        float inAreaX = barOrigin.x + pointerX;
        float inAreaY = barOrigin.y + pointerY;

        float reach = width / 2f;
        float along = grabFromLeft < reach ? grabFromLeft
                : grabFromRight < reach ? width - grabFromRight
                : reach;
        frame.moveTo(inAreaX - along, inAreaY - grabFromTop);
    }

}
