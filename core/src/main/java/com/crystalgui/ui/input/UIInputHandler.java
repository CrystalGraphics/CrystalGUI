package com.crystalgui.ui.input;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.data.CacheCell;
import com.crystalgui.core.data.ReadOnlyVec2f;
import com.crystalgui.core.input.CgUiInputAdapter;
import com.crystalgui.core.input.SystemInput;
import com.crystalgui.core.input.SystemInput.Keyboard;
import com.crystalgui.core.input.SystemInput.Mouse;
import com.crystalgui.core.input.keyboard.CgUiKeyCodes;
import com.crystalgui.core.input.keyboard.Modifiers;
import com.crystalgui.style.property.visual.Cursor;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.tree.UITreeTraversal;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.event.*;
import lombok.Getter;
import org.joml.Vector2f;
import org.jspecify.annotations.Nullable;

public final class UIInputHandler implements SystemInput.Keyboard, SystemInput.Mouse {
    public final static long multiClickInterval = SystemInput.multiClickInterval.get();

    private final UIWindow window;

    @Getter
    private float scrollDelta = 0;
    @Getter
    private final Vector2f accumulatedMouseChange = new Vector2f();

    private final HoverFrameData hoverFrameData = new HoverFrameData();
    @Getter
    private final UIDragController dragController = new UIDragController();
    private UIElement lastPressedElement;
    private UIElement lastFrameHover;
    /** The element currently holding keyboard focus, or {@code null}. */
    @Getter
    private UIElement focusedElement;
    /** Tracks which element a Space-key hold is acting on, so releasing Space always resets that
     * element's pressed state — even if focus moved elsewhere mid-hold — and so the synthesized
     * mouse-up's wasPressTarget can tell "space held over this element, released while still
     * focused here" apart from "focus moved away mid-hold." */
    private UIElement keyboardPressTarget;

    private boolean firstFrameOver = false;

    private final ButtonState[] mouseButtonStates = new ButtonState[CrystalGuiCore.getAdapter().howManyMouseButtons()];

    public UIInputHandler(UIWindow window) {
        this.window = window;
    }

    /**
     * Forces the hover hit-test to recompute this frame, regardless of whether the mouse moved.
     * {@code UIWindow.paintFrame()} calls this AFTER that frame's layout has already been recomputed
     * — without it, the hover cache would only ever refresh on genuine mouse-position changes
     * ({@link HoverFrameData#updatePosition}), so a UI reflow under a perfectly stationary cursor
     * (an element resizing/moving via a transition, a stylesheet re-match, children added/removed)
     * would leave the hovered element stale until the next real mouse movement happened to occur.
     * Unconditional per-frame invalidation is simpler than only invalidating when layout genuinely
     * changed (which would need a new signal threaded out of {@code UIWindow.calculateLayout()}) and
     * matches this engine's existing immediate-mode philosophy of recomputing freely each frame
     * rather than optimizing for skipped work.
     *
     * <p>Hover snapshotting/diffing itself still happens entirely in {@link #endFrame()} via
     * {@link #fireAccumulatedMouseEvents()} — this method only invalidates the cache {@code endFrame()}
     * will then read; it must not also read/snapshot it here (that was the original stuck-hover bug:
     * ordinary mouse-move events already invalidate this same cache before this method runs each
     * frame, so reading it here was really an eager recompute against the *new* position mislabeled
     * as the *old* one).
     */
    public void beginFrame() {
        hoverFrameData.invalidate();
    }

    public void endFrame() {
        fireAccumulatedMouseEvents();
        if (dragController.isDragging()) {
            var pos = hoverFrameData.eventPosition();
            dragController.tick(pos.x(), pos.y());
        }
        accumulatedMouseChange.zero();
        scrollDelta = 0;
        firstFrameOver = true;
    }

    public void sendInputEvent(UIElement element, UIEvent event) {
        if (element == null) return;
        UIElement[] path = UITreeTraversal.pathToRoot(element); // root-first, path[path.length - 1] == element

        event.setPhase(PropagationPhase.CAPTURE);
        for (int i = 0; i < path.length - 1; i++) {
            path[i].events.emitToGroup(event);
        }

        event.setPhase(PropagationPhase.TARGET);
        element.events.emitToGroup(event);

        if (!event.isBubbles()) return;
        event.setPhase(PropagationPhase.BUBBLE);
        for (int i = path.length - 2; i >= 0; i--) {
            path[i].events.emitToGroup(event);
        }
    }

    private void fireAccumulatedMouseEvents() {
        final var lastHover = this.lastFrameHover;
        final var currentHover = hoverFrameData.element();

        if (lastHover == currentHover) {
            emitMouseMove(lastHover);
        } else {
            final var commonAncestor = UITreeTraversal.commonAncestor(lastHover, currentHover);
            updateHoverChain(lastHover, currentHover, commonAncestor);
            emitMouseLeaveChain(lastHover, commonAncestor);
            emitMouseEnterChain(currentHover, commonAncestor);
        }

        if (scrollDelta != 0)
            emitMouseScroll(currentHover);

        updateCursor(currentHover);

        // Snapshot for next frame's diff — must happen after use above, and must be a plain field
        // write here (not a read of the live hoverFrameData cache at the top of the next frame),
        // otherwise a mouse-move event that arrives before beginFrame() next frame would invalidate
        // the cache first and silently corrupt this "old" value into the "new" one.
        this.lastFrameHover = currentHover;
    }

    /** Current pointer position in physical pixels. Read-only view of the live vector, so callers
     * must not retain it across frames. */
    public ReadOnlyVec2f pointerPosition() {
        return hoverFrameData.eventPosition();
    }

    // ── Cursor ──────────────────────────────────────────────────────────────

    /** Last cursor handed to the platform, so an unchanged one is not re-sent. */
    private Cursor lastCursor = Cursor.DEFAULT;

    /**
     * Resolves the CSS {@code cursor} for whatever the pointer is over and pushes it to the platform.
     *
     * <p>Driven from the hover diff rather than from the property's own change listener: the cursor is
     * a function of <em>where the pointer is</em>, so reacting to the property would fire for elements
     * nowhere near it and still miss the case where the pointer moves onto an element whose value
     * never changed.</p>
     *
     * <p><b>Pointer capture is handled for free.</b> While captured, hover resolves to the capturing
     * element, so a resize keeps its {@code ew-resize} cursor for the whole drag even as the pointer
     * travels across unrelated elements — which is exactly what a resize should do, and would need
     * special-casing under any other arrangement.</p>
     *
     * <p><b>Resolution runs every frame; only the platform call is skipped.</b> That is deliberate, not
     * an oversight: a stationary pointer can still need a different cursor because the element under it
     * changed — a transition finishing, a class toggling, content reflowing beneath it. It is the same
     * reasoning that makes {@link #beginFrame()} invalidate the hover cache unconditionally. The cost
     * is one cascade read and an enum compare, against a platform call that may allocate a native
     * cursor object, which is the one worth guarding.</p>
     */
    private void updateCursor(@Nullable UIElement hovered) {
        Cursor resolved = resolveCursor(hovered);
        if (resolved == lastCursor) return;
        lastCursor = resolved;
        CrystalGuiCore.getCursorService().setCursor(resolved);
    }

    /**
     * The spec's {@code auto} rule: "behaves as {@code text} over selectable text or editable
     * elements, and {@code default} otherwise".
     *
     * <p>{@code consumesTextInput()} is the engine's existing notion of "editable", already overridden
     * by {@code TextField} — so the rule lands on a signal that exists rather than needing a new one.
     * Inheritance itself needs no work here: {@code cursor} is an inheritable property, so the cascade
     * has already resolved what a nested element should show.</p>
     */
    private Cursor resolveCursor(@Nullable UIElement hovered) {
        if (hovered == null) return Cursor.DEFAULT;
        Cursor declared = hovered.getStyle().getGeneralGroup().cursor();
        if (!declared.needsResolution()) return declared;
        return hovered.consumesTextInput() ? Cursor.TEXT : Cursor.DEFAULT;
    }

    /** The cursor currently being presented. Resolved, so never {@link Cursor#AUTO}. */
    public Cursor currentCursor() {
        return lastCursor;
    }

    // ── Pointer capture ─────────────────────────────────────────────────────

    @Nullable
    private UIElement pointerCaptureTarget;

    /**
     * The element every pointer event is currently routed to regardless of what is underneath, or
     * {@code null}.
     *
     * <p>Pointer Events Level 3's {@code setPointerCapture} — the primitive that pointer-based
     * dragging is built on, and the reason this engine does not implement HTML5 drag-and-drop.</p>
     */
    @Nullable
    public UIElement getPointerCaptureTarget() {
        return pointerCaptureTarget;
    }

    public boolean hasPointerCapture() {
        return pointerCaptureTarget != null;
    }

    /**
     * Routes all subsequent pointer events to {@code element} until capture is released.
     *
     * <p>Per spec: "the capturing target will substitute the normal hit testing result <b>as if the
     * pointer is always over the capturing target</b>, and they MUST always be targeted at this
     * element until capture is released."</p>
     *
     * <p><b>Boundary events fall out of that for free</b>, which is why this is one field and not a
     * second mechanism. The spec also says "when an element receives the pointer capture all the
     * following events for that pointer are considered to be inside the boundary of the capturing
     * element" — and because {@link #resolveHitTarget} makes the hover cache resolve to the capture
     * target for the whole capture, the per-frame hover diff sees no change and fires no
     * enter/leave to anything else. {@code :hover} stays pinned to the captured chain too.</p>
     *
     * <p>Before this existed, dragging a slider ran the ordinary hover diff every frame: {@code :hover}
     * flickered on and {@code mouseenter}/{@code mouseleave} fired on every element the cursor
     * crossed mid-drag.</p>
     *
     * <p><b>Fails silently when no button is down</b>, matching the spec's "only when the pointer is in
     * its active buttons state … otherwise fails silently". Capture with no button held could never be
     * released by a button release, so it would wedge input permanently.</p>
     */
    public void setPointerCapture(UIElement element) {
        if (element == null || element.getAttachedWindow() != window) return;
        if (!isAnyMouseButtonDown()) return; // spec: fails silently outside the active buttons state
        pointerCaptureTarget = element;
        hoverFrameData.invalidate();
    }

    /** Ends capture; subsequent events follow normal hit testing again. No-op if not captured. */
    public void releasePointerCapture() {
        if (pointerCaptureTarget == null) return;
        pointerCaptureTarget = null;
        // The next hit test must be a real one — without this the stale target survives until the
        // pointer happens to move.
        hoverFrameData.invalidate();
    }

    private boolean isAnyMouseButtonDown() {
        for (ButtonState state : mouseButtonStates) {
            if (state != null && state.isPressed()) return true;
        }
        return false;
    }

    /**
     * What the pointer is considered to be over: the capture target while captured, the real
     * hit-test result otherwise.
     *
     * <p>Every consumer in this class reads the pointer's target through the hover cache, so
     * substituting here is what makes capture total rather than something each call site has to
     * remember.</p>
     *
     * <p>A capture target that has left the tree is dropped rather than honoured. Holding it would
     * route every pointer event to a detached element — input would look completely dead, with no
     * error, until something happened to release it.</p>
     */
    private UIElement resolveHitTarget(float x, float y) {
        UIElement captured = pointerCaptureTarget;
        if (captured != null) {
            if (captured.getAttachedWindow() == window) return captured;
            pointerCaptureTarget = null;
        }
        return window.getHoveredElement(x, y);
    }

    private void updateHoverChain(UIElement oldHover, UIElement newHover, UIElement commonAncestor) {
        for (var e = oldHover; e != null && e != commonAncestor; e = e.getParent()) {
            e.setHovered(false);
        }
        for (var e = newHover; e != null && e != commonAncestor; e = e.getParent()) {
            e.setHovered(true);
        }
    }

    /**
     * Fires {@code mouseleave} on <b>every</b> element being left, innermost first — not just the
     * exact element the pointer was over.
     *
     * <p>This is what the DOM does, and the distinction is easy to misread: {@code mouseenter} and
     * {@code mouseleave} do not <em>bubble</em>, but the browser still fires a separate one on each
     * element in the entered/left chain. Only firing on the precise target means a container never
     * hears about the pointer once it has any children — hovering a row's own label would leave the
     * row itself with no event at all, and only the bare gaps between children would work. The
     * {@code :hover} pseudo-class was already walking this same chain (right below), so the two used
     * to disagree about what "hovered" meant.</p>
     */
    private void emitMouseLeaveChain(UIElement from, UIElement commonAncestor) {
        if (from == null) return;
        emitMouseMove(from);
        UITreeTraversal.forEachLeft(from, commonAncestor,
                e -> sendInputEvent(e, new MouseEvent.Leave(e, hoverFrameData.eventPosition())));
    }

    /** Counterpart to {@link #emitMouseLeaveChain}, outermost first — the DOM's entry order, so an
     * ancestor learns the pointer arrived before its child does. */
    private void emitMouseEnterChain(UIElement to, UIElement commonAncestor) {
        if (to == null) return;
        emitMouseMove(to);
        UITreeTraversal.forEachEntered(to, commonAncestor,
                e -> sendInputEvent(e, new MouseEvent.Enter(e, hoverFrameData.eventPosition())));
    }

    @Override
    public boolean consumeKeyboardEvent(Keyboard.Event event) {
        if (!firstFrameOver) return false;

        // Escape aborts a drag, ahead of focus routing and before anything else can consume it.
        // A drag is a modal interaction — while one is running it is what Escape means, regardless of
        // what holds focus (which during a drag is usually not the drag source at all). Deliberately
        // ahead of the focusedElement == null branch, or a drag started from a non-focusable element
        // would be uncancellable.
        if (event.pressed() && event.key() == CgUiKeyCodes.KEY_ESCAPE && dragController.isDragging()) {
            dragController.cancelDrag();
            return true;
        }

        // Then the close watcher. Deliberately AFTER the drag branch: a drag inside a menu must eat Escape
        // before the menu does, because it is the innermost live interaction — the ordering hazard flagged
        // when Dialog was researched.
        //
        // Asks the TOPMOST watcher, which is what makes nesting work: a dropdown opened from inside a modal
        // closes first, and only a second Escape reaches the modal. Elements that establish no watcher —
        // a modeless dialog, a MANUAL popover — never see Escape at all, which is the web's behaviour too.
        if (event.pressed() && event.key() == CgUiKeyCodes.KEY_ESCAPE) {
            UIElement watcher = window.getTopCloseWatcher();
            if (watcher != null && watcher.requestClose()) return true;
        }

        CgUiInputAdapter inputAdapter = CrystalGuiCore.getAdapter();
        final int modifiers = inputAdapter.getCurrentModifiers();

        if (focusedElement == null) {
            moveTabFocus(event, modifiers);
            return false;
        }

        if (event.pressed()) {
            var propagationStopped = emitKeyboardDown(event, modifiers);
            if (!propagationStopped) {
                moveTabFocus(event, modifiers);
            }
        } else {
            emitKeyboardUp(event, modifiers);
        }
        handleActivationKey(event);
        return false;
    }

    /**
     * Web-standard keyboard activation: while an element is focused, Space/Enter act like a mouse
     * press over it. Deliberately generic (lives here, not on Button) — it synthesizes the same
     * {@link MouseEvent.Down}/{@link MouseEvent.Up} (with {@code wasPressTarget} correctly computed)
     * that a real mouse click would, so any focusable widget listening for mouse activation (Button's
     * {@code onMouseUp}-based decorator, and later Checkbox's) gets keyboard activation for free with
     * zero widget-specific keyboard code.
     *
     * <p>Enter activates immediately on key-down with no hold state, matching real browsers — Space
     * has press-and-hold semantics mirroring an actual mouse press, so it needs the held-until-release
     * tracking in {@link #keyboardPressTarget}.
     */
    private void handleActivationKey(Keyboard.Event event) {
        if (focusedElement == null) return;
        if (event.key() != CgUiKeyCodes.KEY_SPACE && event.key() != CgUiKeyCodes.KEY_RETURN) return;
        // A text-editing element gets Space as a character, not as activation — synthesizing a click
        // for it would fire the element's press handlers every time somebody typed a space, and the
        // synthesized Down carries the physical cursor position, so it would also land wherever the
        // mouse happened to be.
        if (focusedElement.consumesTextInput()) return;

        if (event.pressed() && !event.repeat()) {
            keyboardPressTarget = focusedElement;
            focusedElement.setPressed(true);
            sendInputEvent(focusedElement, new MouseEvent.Down(focusedElement, hoverFrameData.eventPosition(), 0, 1));
        } else if (!event.pressed()) {
            boolean wasPressTarget = focusedElement == keyboardPressTarget; // false if focus moved mid-hold
            if (keyboardPressTarget != null) keyboardPressTarget.setPressed(false);
            sendInputEvent(focusedElement, new MouseEvent.Up(focusedElement, hoverFrameData.eventPosition(), 0, 1, wasPressTarget));
            keyboardPressTarget = null;
        }

    }

    private void moveTabFocus(Keyboard.Event event, int modifiers) {
        if (event.key() != CgUiKeyCodes.KEY_TAB) return;
        boolean reverse = Modifiers.hasShift(modifiers);

        // The whole of focus trapping: while a modal is open the tab sequence IS the modal's subtree,
        // because everything outside it is inert. Enforced here rather than inside `tabbable()` so the
        // cached focusable-descendant answers stay free of a condition that changes for nearly every
        // element in the tree the moment a modal opens.
        UIElement modal = window.getActiveModal();
        UIElement scope = modal != null ? modal : window.ui.rootElement;

        UIElement next;
        if (focusedElement == null) {
            next = reverse
                    ? UITreeTraversal.lastTabbableIn(scope)
                    : UITreeTraversal.firstTabbableIn(scope);
        } else {
            next = reverse
                    ? UITreeTraversal.previousTabbable(focusedElement, modal)
                    : UITreeTraversal.nextTabbable(focusedElement, modal);
            if (next == null) { // fell off the end — wrap around, inside the scope
                next = reverse
                        ? UITreeTraversal.lastTabbableIn(scope)
                        : UITreeTraversal.firstTabbableIn(scope);
            }
        }
        if (next == null) return; // nothing tabbable at all

        if (focusedElement != null) emitAndLoseFocus(focusedElement);
        focusedElement = next;
        // Tab traversal scrolls the new target into view — it is keyboard-driven, not a click, and
        // tabbing to something below the fold must reveal it, exactly as a browser does. Keyboard focus
        // is also the case `:focus-visible` exists for: this is the path that rings.
        emitAndSetFocus(focusedElement, FocusSource.KEYBOARD);
    }

    private boolean emitKeyboardDown(Keyboard.Event event, int modifiers) {
        KeyboardEvent.Down newEvent = new KeyboardEvent.Down(focusedElement, event.key(), event.character(), event.repeat(), modifiers, event.millis());
        sendInputEvent(focusedElement, newEvent);
        return newEvent.isPropagationStopped() || newEvent.isPhasePropagationStopped() || newEvent.isDefaultPrevented();
    }

    private void emitKeyboardUp(Keyboard.Event event, int modifiers) {
        KeyboardEvent.Up newEvent = new KeyboardEvent.Up(focusedElement, event.key(), event.character(), event.repeat(), modifiers, event.millis());
        sendInputEvent(focusedElement, newEvent);
    }

    @Override
    public boolean consumeMouseEvent(Mouse.Event event) {
        if (!firstFrameOver) return false;
        hoverFrameData.updatePosition(event.x(), event.y());
        accumulatedMouseChange.add(event.dx(), event.dy());
        scrollDelta += event.wheelDelta();
        if (event.button() != -1) processMouseButtons(event);
        return false;
    }

    private void processMouseButtons(Mouse.Event event) {
        final UIElement target = hoverFrameData.element();
        updateButtonState(event, target);

        final int buttonOrdinal = event.button();
        final ButtonState buttonState = getMouseButtonState(buttonOrdinal);
        final int detail = buttonState == null ? 1 : buttonState.getDetail();

        if (event.state()) {
            this.lastPressedElement = target;
            if (this.lastPressedElement != null && buttonOrdinal == 0) this.lastPressedElement.setPressed(true);
            emitMouseDown(target, buttonOrdinal, detail);
        } else {
            // Capture before lastPressedElement's pressed flag resets below.
            boolean wasPressTarget = target == lastPressedElement;
            if (this.lastPressedElement != null && buttonOrdinal == 0) this.lastPressedElement.setPressed(false);
            emitMouseUp(target, buttonOrdinal, detail, wasPressTarget);
            if (buttonOrdinal == 0 && dragController.isDragging()) {
                var pos = hoverFrameData.eventPosition();
                dragController.endDrag(pos.x(), pos.y());
            }
            // Implicit release, after the up event has been delivered — the spec's ordering, and it
            // matters: the up must still reach the capturing element, which is the whole reason a
            // drag can end anywhere on screen rather than only over its source.
            if (!isAnyMouseButtonDown()) releasePointerCapture();
        }
    }

    private @Nullable ButtonState getMouseButtonState(int button) {
        if (button >= mouseButtonStates.length) return null;
        if (button < 0) return null;
        if (mouseButtonStates[button] == null) {
            mouseButtonStates[button] = new ButtonState();
        }
        return mouseButtonStates[button];
    }

    private void updateButtonState(Mouse.Event event, UIElement target) {
        final int buttonOrdinal = event.button();
        final ButtonState buttonState = getMouseButtonState(buttonOrdinal);
        if (buttonState == null) return;
        if (event.state() && target != lastPressedElement) buttonState.resetDetail();
        buttonState.setState(event.state(), event.millis());
    }

    private void emitMouseDown(UIElement targetElement, int buttonId, int detail) {
        // A press that hit nothing normally blurs, matching the browser: click empty space and the active
        // element goes away. But while a modal is open, "hit nothing" does not mean the user clicked bare
        // document — it means inertness ATE the press. Treating the two the same drops the caret out of a
        // dialog's text field the moment you click its dim backdrop, which no dialog anywhere does.
        boolean absorbedByModal = targetElement == null && window.getActiveModal() != null;

        if (targetElement != focusedElement && !absorbedByModal) {
            if (focusedElement != null) {
                emitAndLoseFocus(focusedElement);
            }
            if (targetElement != null && targetElement.getFocusPolicy().focusesOnClick()) {
                // Deliberately no scroll: you clicked what you could already see, and scrolling
                // here would pull the content out from under the cursor. Nor a focus ring, unless
                // this is a text field — see emitAndSetFocus.
                emitAndSetFocus(targetElement, FocusSource.POINTER);
            }
        }

        // Read BEFORE dispatch, and that ordering is the whole point: a handler is free to open — or re-open
        // at a new position — a context menu from this very press, and anything shown during the dispatch must
        // not then be dismissed by it. See UIWindow.lightDismiss(UIElement, int).
        int shownBeforePress = window.popoverShowSeq();

        var event = new MouseEvent.Down(targetElement, hoverFrameData.eventPosition(), buttonId, detail);
        sendInputEvent(targetElement, event);

        // Light dismiss LAST, so the press still reaches whatever it landed on. Clicking a button outside
        // an open menu both presses that button and closes the menu, which is what browsers do — dismissing
        // first would tear down the tree under an event that has not been delivered yet.
        //
        // On press rather than release: the spec pairs pointerdown with pointerup so a text-selection drag
        // that starts inside and ends outside does not dismiss, which is a concern for selectable document
        // content and not for menus. Recorded as a divergence rather than an oversight.
        window.lightDismiss(targetElement, shownBeforePress);
    }

    private void emitMouseUp(UIElement target, int buttonId, int detail, boolean wasPressTarget) {
        MouseEvent.Up event = new MouseEvent.Up(target, hoverFrameData.eventPosition(), buttonId, detail, wasPressTarget);
        sendInputEvent(target, event);
    }

    /**
     * Dispatches the wheel and stops there.
     *
     * <p>Deliberately no built-in "scroll the nearest container" behaviour. A bare {@link UIElement}
     * is scrollable only <em>programmatically</em> — via {@code scrollTop}/{@code scrollLeft} — and
     * never by the wheel, however its {@code overflow} is set. Wheel handling belongs to a widget
     * that opts into it ({@code ScrollerView} does), which keeps a stray clipped element from
     * silently swallowing scroll input.</p>
     */
    private void emitMouseScroll(UIElement target) {
        MouseEvent.Scroll event = new MouseEvent.Scroll(target, hoverFrameData.eventPosition(), scrollDelta);
        sendInputEvent(target, event);
    }

    private void emitMouseMove(UIElement target) {
        MouseEvent.Move event = new MouseEvent.Move(target, hoverFrameData.eventPosition());
        sendInputEvent(target, event);
    }

    /**
     * Moves focus to {@code element} from code, scrolling it into view if it's off-screen — the DOM's
     * {@code element.focus()}.
     *
     * <p>Scrolling is the whole point of having this separate from the click path: focus that lands
     * somewhere invisible is focus the user can't see, so anything that isn't a click reveals its
     * target. A click can't need it (you clicked what you could see) and scrolling on click would
     * yank the page under the cursor.</p>
     *
     * <p>No-op for an element that can't take focus, so callers don't have to check.</p>
     */
    public void requestFocus(UIElement element) {
        if (element == null || !element.focusable()) return;
        // focusable() sees only the inert *attribute* — the modal half is checked here, where it costs one
        // ancestor walk per (rare) programmatic focus call instead of poisoning a per-frame cache. The web
        // ignores focus() on an inert element in exactly this way.
        if (element.isInert()) return;
        if (focusedElement == element) {
            element.scrollIntoView(); // already focused, but may have been scrolled away since
            return;
        }
        if (focusedElement != null) emitAndLoseFocus(focusedElement);
        emitAndSetFocus(element, FocusSource.PROGRAMMATIC);
    }

    /**
     * Moves focus because <b>the pointer went there</b> — no ring and no scroll, exactly like the click path.
     *
     * <p>Separate from {@link #requestFocus} because that one is {@code PROGRAMMATIC}, which rings: a menu
     * doing focus-follows-hover through it would draw a focus ring on whatever the mouse passed over, which
     * is precisely the noise {@code :focus-visible} exists to avoid. Same carve-out as a click, for the same
     * reason — you already know where your pointer is.</p>
     */
    public void requestPointerFocus(UIElement element) {
        if (element == null || !element.focusable() || element.isInert()) return;
        if (focusedElement == element) return;
        if (focusedElement != null) emitAndLoseFocus(focusedElement);
        emitAndSetFocus(element, FocusSource.POINTER);
    }

    private void emitAndSetFocus(UIElement target, FocusSource source) {
        this.focusedElement = target;
        if (target != null) {
            // The text-input carve-out: browsers ring a focused text field however it was focused,
            // because a caret alone is a weak affordance and the field is where typing goes. Every
            // other widget stays unringed after a click — you already know what you clicked.
            boolean ring = source.ringsByDefault() || target.consumesTextInput();
            target.setFocused(true, ring);
            // Instant, never eased — see UIElement.scrollIntoView.
            if (source.scrollsIntoView()) target.scrollIntoView();
        }
        FocusEvent.Focus event = new FocusEvent.Focus(target);
        sendInputEvent(target, event);
    }

    /** Drops focus if — and only if — {@code element} currently holds it. Called when an element
     * stops being a legitimate focus target (disabled, or detached from the tree) so focus can't
     * linger on something that no longer accepts input. No-op otherwise, so callers don't need to
     * check first. */
    public void blurIfFocused(UIElement element) {
        if (element != null && focusedElement == element) {
            emitAndLoseFocus(element);
        }
    }

    private void emitAndLoseFocus(UIElement target) {
        this.focusedElement = null;
        if (target == null) return;
        target.setFocused(false);
        target.setPressed(false);
        FocusEvent.Blur event = new FocusEvent.Blur(target);
        sendInputEvent(target, event);
    }

    private class HoverFrameData {
        private final Vector2f position = new Vector2f();
        private final ReadOnlyVec2f sealedVec2f = new ReadOnlyVec2f(position);

        private final CacheCell<UIElement> hoveredElement = new CacheCell<UIElement>()
                .setCalculator(ignored -> UIInputHandler.this.resolveHitTarget(position.x(), position.y()))
                .set(null);

        boolean positionChanged(int x, int y) {
            return x != position.x() || y != position.y();
        }

        void updatePosition(int x, int y) {
            if (positionChanged(x, y)) hoveredElement.invalidate();
            position.set(x, y);
        }

        UIElement element() {
            return hoveredElement.get();
        }

        void invalidate() {
            hoveredElement.invalidate();
        }

        ReadOnlyVec2f eventPosition() {
            return sealedVec2f;
        }
    }

    public void resetHandler() {
        firstFrameOver = false;
    }
}