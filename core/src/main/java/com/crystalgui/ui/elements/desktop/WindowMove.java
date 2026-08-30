package com.crystalgui.ui.elements.desktop;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.input.UIDragController;

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
 * <p><b>Converted to ABSOLUTE layout coordinates, not to an offset within the source</b>, and the
 * distinction has now cost a bug in each direction. {@code screenToLocal} divides out the surface scale
 * and leaves the result comparable to {@code getRuntimeCache().getX()} — which is why
 * {@code isMouseOverElement} tests its argument against {@code rectX}, and why
 * {@link #beginTearLoose} can subtract the bar's X to get an offset along the caption. Reading
 * "local" as "relative to the source" is what made {@link #snapZoneAt} add that origin back, and the
 * snap zone a drag reported was then displaced by however far along the desktop the window sat.</p>
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
            if (!bar.containsScreenPoint(event.getPosition().x(), event.getPosition().y())) return;
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
        for (UIElement walk = target; walk != null && walk != bar; walk = walk.getParent()) {
            if (walk.focusable()) return true;
        }
        return false;
    }

    private void beginMove(float pointerX, float pointerY, int detail) {
        UIWindow window = frame.getAttachedWindow();
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

        UIDragController drag = window.getInputHandler().getDragController();
        // Positional drag, zero threshold: a window must track the very first pixel, and a title bar has
        // no competing click interpretation to protect.
        frame.setMoving(true);
        drag.startDrag(bar, pointerX, pointerY, new UIDragController.DragListener() {
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
     * <h3>A drag callback's coordinates are ALREADY ABSOLUTE — one subtraction, not two</h3>
     *
     * <p>{@code UIDragController.tick} runs {@code screenToLocal} against the drag source, and that
     * converts out of <em>surface</em> pixels into the source's local layout space — it does not
     * subtract the source's own origin. {@code isMouseOverElement} is the proof: it compares the
     * coordinate it is handed against {@code runtimeCache.getX()}, so the two are in one space. So is
     * {@link #beginTearLoose}, which takes {@code pointerX - bar.getX()} to get the offset along
     * the caption — which only means anything if the pointer is absolute.</p>
     *
     * <p><b>Adding the bar's origin back was therefore counting it twice</b>, and it shipped: the zone a
     * drag reported was displaced by however far along the desktop the window happened to be, so a
     * window near the middle reported the RIGHT edge at about half the distance across and a window at
     * the left could not reach LEFT at all. It reads as "snap triggers too early" rather than as a
     * conversion, because it is wrong by a different amount every time.</p>
     *
     * <p>Through the layout boxes and never the transform chain: {@code localToWorld} is in surface
     * pixels with the root transform baked in, which is the documented way to place something neatly in
     * the wrong spot.</p>
     */
    @Nullable
    private SnapZones.Zone snapZoneAt(float mouseX, float mouseY) {
        Desktop desktop = frame.desktop();
        if (desktop == null || frame.isToolWindow()) return null;
        var area = desktop.windowLayer().getRuntimeCache();
        // THE CAPTION'S HEIGHT IS THE TOP BAND -- see SnapZones. The pointer rides inside the caption
        // for the whole drag, so anything smaller is unreachable for all but the shallowest grab.
        return SnapZones.forPoint(mouseX - area.getX(), mouseY - area.getY(),
                area.getWidth(), area.getHeight());
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
        float barLeft = bar.getRuntimeCache().getX();
        float barWidth = bar.getRuntimeCache().getWidth();
        grabFromLeft = pointerX - barLeft;
        grabFromRight = barLeft + barWidth - pointerX;
        // The caption's height does not change, so the frame's own top keeps the pointer at the same
        // place DOWN the bar for the whole gesture.
        grabFromTop = pointerY - frame.getRuntimeCache().getY();

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
        float areaX = area == null ? 0f : area.getRuntimeCache().getX();
        float areaY = area == null ? 0f : area.getRuntimeCache().getY();

        float width = frame.getRuntimeCache().getWidth();
        if (width <= 0f) return;

        float reach = width / 2f;
        float along = grabFromLeft < reach ? grabFromLeft
                : grabFromRight < reach ? width - grabFromRight
                : reach;
        frame.moveTo(pointerX - areaX - along, pointerY - areaY - grabFromTop);
    }

}
