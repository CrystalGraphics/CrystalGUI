package com.crystalgui.ui.input;

import com.crystalgui.core.async.FrameProfile;
import com.crystalgui.core.data.CacheCell;
import com.crystalgui.core.data.ReadOnlyVec2f;
import com.crystalgui.core.signal.Signal;
import com.crystalgraphics.platform.service.CgInputService;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.platform.input.CgSystemInput.Keyboard;
import com.crystalgraphics.platform.input.CgSystemInput.Mouse;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgCursor;
import com.crystalgui.ui.elements.Dialog;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.input.keymap.KeyEventType;
import com.crystalgui.ui.input.keymap.KeyStroke;
import com.crystalgui.ui.input.keymap.KeymapResolver;
import com.crystalgui.ui.tree.UITreeTraversal;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.event.*;
import lombok.Getter;
import org.joml.Vector2f;
import org.jspecify.annotations.Nullable;
import com.crystalgraphics.platform.CgPlatform;

public final class UIInputHandler implements CgSystemInput.Keyboard, CgSystemInput.Mouse {
    public final static long multiClickInterval = ButtonState.MULTI_CLICK_INTERVAL_MS;

    private final UIWindow window;

    /**
     * Owns pending-chord state, so it sits beside the focus it depends on — a half-entered chord has to be
     * abandoned when focus moves, or a prefix begun in one panel could complete in another.
     */
    @Getter
    private final KeymapResolver keymapResolver;

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

    private final ButtonState[] mouseButtonStates = new ButtonState[CgPlatform.input().howManyMouseButtons()];

    public UIInputHandler(UIWindow window) {
        this.window = window;
        this.keymapResolver = new KeymapResolver(window.getCommands());
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

    /**
     * Drops every reference to {@code element}, which is leaving the tree.
     *
     * <p>Focus already had this treatment ({@link #blurIfFocused}); the others did not, and hover was
     * the one that bit: deleting the node under the pointer left {@code lastFrameHover} pointing into a
     * detached subtree, and the next frame's hover diff tried to find a common ancestor between two
     * elements in different trees. It threw an NPE from inside the hover diff — a place with no
     * relationship to the delete that caused it.</p>
     *
     * <p>The same reasoning as {@code UIWindow.unregisterElement} popping a detached modal off the modal
     * stack: anything holding an element has to be told when that element goes, or the reference outlives
     * the tree it made sense in.</p>
     */
    public void forgetElement(UIElement element) {
        if (element == null) return;
        if (lastFrameHover == element) lastFrameHover = null;
        if (lastPressedElement == element) lastPressedElement = null;
        if (keyboardPressTarget == element) keyboardPressTarget = null;
        if (pointerCaptureTarget == element) releasePointerCapture();
        // A drag whose source just left the tree cannot continue: every coordinate it reports is
        // converted through that element's transform, which no longer means anything.
        if (dragController.isDragging() && dragController.getSource() == element) {
            dragController.cancelDrag();
        }
        blurIfFocused(element);
    }

    /**
     * The element is about to represent something else — so it cannot still be what the pointer is over.
     *
     * <p>The narrow half of {@link #forgetElement}, for an element that is <b>not</b> leaving the tree: a
     * pooled list row is deliberately kept as a {@code display: none} child so its Taffy node and style
     * candidates survive, and it comes back bound to a different item. {@code recycle} already gives up
     * <em>focus</em> for exactly this reason — "the element must give focus up the moment it stops
     * representing anything" — and hover is the same sentence with a different word in it.</p>
     *
     * <p>Without it the flag rides the element through the pool: fold a heading with the pointer on its
     * chevron, unfold it, and the element that was the heading comes back as some row further down still
     * wearing {@code :hover}, so an untouched row lights up. The next hover diff does correct it, which is
     * why it presents as a two-or-three-frame flash rather than a stuck highlight — and why it reads as a
     * paint glitch rather than as state.</p>
     *
     * <p>{@code lastFrameHover} is dropped as well as the flag, so the next diff treats the pointer as
     * newly entering whatever is genuinely under it. Leaving it set would suppress the very Enter that
     * repairs this when the recycled element happens to still be under the cursor.</p>
     */
    public void clearHoverIfHovered(UIElement element) {
        if (element == null) return;
        if (lastFrameHover == element) lastFrameHover = null;
        element.setHovered(false);
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

    // ── CgCursor ──────────────────────────────────────────────────────────────

    /** Last cursor handed to the platform, so an unchanged one is not re-sent. */
    private CgCursor lastCursor = CgCursor.DEFAULT;

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
        CgCursor resolved = resolveCursor(hovered);
        if (resolved == lastCursor) return;
        lastCursor = resolved;
        CgPlatform.cursor().setCursor(resolved);
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
    private CgCursor resolveCursor(@Nullable UIElement hovered) {
        if (hovered == null) return CgCursor.DEFAULT;
        CgCursor declared = hovered.getStyle().getGeneralGroup().cursor();
        if (!declared.needsResolution()) return declared;
        return hovered.consumesTextInput() ? CgCursor.TEXT : CgCursor.DEFAULT;
    }

    /** The cursor currently being presented. Resolved, so never {@link CgCursor#AUTO}. */
    public CgCursor currentCursor() {
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
        if (event.pressed() && event.key() == CgKeyCodes.KEY_ESCAPE && dragController.isDragging()) {
            dragController.cancelDrag();
            return true;
        }

        // Then a live window switch, on the same rung and for the same reason: it is held open by a
        // modifier that is still down, so while it is up its own keys can mean nothing else. Escape
        // cannot be a close watcher -- that cascade asks the ACTIVE FRAME's stack first and a frame is
        // its own last watcher, so Escape would minimise the window behind the switcher rather than
        // dismiss it -- and the ARROWS cannot go through dispatch at all, because they reach the focused
        // element and a focused editor moves its caret with them. GNOME holds a modal grab for the whole
        // gesture. Tab is deliberately left alone here so repeating the chord keeps resolving through the
        // keymap and the gesture stays rebindable.
        if (event.pressed() && window.routeKeyToWindowSwitcher(event.key())) {
            return true;
        }

        // ...AND THE SAME RUNG FOR KEYBOARD MOVE/SIZE (W13c), for the identical reason: it is a mode with
        // no element, so every key it needs would otherwise reach the focused one -- and a focused editor
        // moves its caret with an arrow. Shift is the fine step, which is why the modifier is read here
        // rather than inside the mode.
        if (event.pressed()
                && window.routeKeyToKeyboardMove(event.key(),
                        CgModifiers.hasShift(CgPlatform.input().getCurrentModifiers()))) {
            return true;
        }

        // Then the close watcher. Deliberately AFTER the drag branch: a drag inside a menu must eat Escape
        // before the menu does, because it is the innermost live interaction — the ordering hazard flagged
        // when Dialog was researched.
        //
        // Asks the TOPMOST watcher, which is what makes nesting work: a dropdown opened from inside a modal
        // closes first, and only a second Escape reaches the modal. Elements that establish no watcher —
        // a modeless dialog, a MANUAL popover — never see Escape at all, which is the web's behaviour too.
        if (event.pressed() && event.key() == CgKeyCodes.KEY_ESCAPE) {
            UIElement watcher = window.getTopCloseWatcher();
            if (watcher != null && watcher.requestClose()) return true;
        }

        CgInputService inputAdapter = CgPlatform.input();
        final int modifiers = inputAdapter.getCurrentModifiers();

        if (focusedElement == null) {
            // The keymap still runs, against the ROOT. This branch used to return before ever reaching
            // it, which made every application-wide binding — the command palette, save, quit — dead
            // until the user happened to click something focusable first. Substituting the root inside
            // resolveKeymap was not enough on its own, because this early return meant it was never
            // called. A browser hands the keystroke to the document when nothing has focus; so do we.
            if (event.pressed() && resolveKeymap(event, modifiers, KeyEventType.PRESS)) return true;
            if (!event.pressed()) resolveKeymap(event, modifiers, KeyEventType.RELEASE);
            moveTabFocus(event, modifiers);
            return false;
        }

        if (event.pressed()) {
            var propagationStopped = emitKeyboardDown(event, modifiers);
            // The keymap resolves AFTER the event has bubbled, and only if nothing consumed it — exactly
            // how a browser applies its own shortcuts, with page handlers getting first refusal. Resolving
            // ahead of dispatch would let an application-wide binding steal a keystroke from a control
            // that wanted it, with no way for the control to object.
            if (!propagationStopped && resolveKeymap(event, modifiers, KeyEventType.PRESS)) {
                return true;
            }
            if (!propagationStopped) {
                moveTabFocus(event, modifiers);
            }
            // ACTIVATION IS GATED TOO, by the same rule the line above follows: one keystroke does one
            // thing. A widget that consumed Space must not also have a click synthesized on it.
            if (!propagationStopped) {
                handleActivationKey(event);
            }
            // AND THE HOST IS TOLD, which it was not.
            //
            // This returned false unconditionally, so a key the UI had fully consumed was reported to the
            // platform as untouched -- and the platform acts on what is left over. On 1.7.10 that is
            // `GuiScreen`: Escape closed the completion popup through `stopPropagation`, the answer came
            // back "nobody wanted it", and the screen shut underneath the editor. So the popup closed AND
            // the whole editor did, which reads as Escape being wired to the wrong thing rather than as a
            // return value.
            //
            // It is not only Escape. Every consumed keystroke was mis-reported; Escape is simply the one
            // key a Minecraft host also acts on itself, so it is the only one where the lie was visible.
            return propagationStopped;
        }
        emitKeyboardUp(event, modifiers);
        // A release binding is not gated on propagation: a release cannot be "handled" the way a press
        // can, because what it ends was already begun by the matching press. Suppressing it would
        // leave a space-to-pan gesture stuck on.
        resolveKeymap(event, modifiers, KeyEventType.RELEASE);
        handleActivationKey(event);
        return false;
    }

    /**
     * Runs the keymap over the focus path, outward from {@link #focusedElement}.
     *
     * @return true if a binding fired or accepted a chord prefix, in which case the caller must treat the
     *         key as consumed — falling through to Tab traversal or Space/Enter activation as well would
     *         let one keystroke do two things at once.
     */
    private boolean resolveKeymap(Keyboard.Event event, int modifiers, KeyEventType type) {
        // Falls back to the ROOT when nothing holds focus, which is what makes an application-wide
        // binding actually application-wide. Without it the resolver bailed on a null focus and every
        // root-scoped shortcut — the command palette, save, quit — was dead until the user happened to
        // click something focusable first. A browser behaves the same way: with no focused element the
        // document handles the keystroke.
        UIElement start = focusedElement != null ? focusedElement : window.ui.rootElement;
        return keymapResolver.resolve(start, new KeyStroke(event.key(), modifiers), type,
                event.millis(), event.repeat());
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
    /**
     * {@code detail} on a click this handler synthesized from the keyboard — <b>zero</b>, which is the
     * DOM's own signal for exactly this ("the click was not caused by a pointer").
     *
     * <p>It has to be distinguishable, and a real press can never be 0 because the first one is 1. The
     * activation events are otherwise deliberately identical to a mouse press, which is what lets
     * {@code Button} and {@code Checkbox} get keyboard support with no keyboard code — but a widget
     * whose press means <em>"the pointer went down at this position"</em> rather than <em>"activate
     * me"</em> needs to opt out, and until now it had nothing to opt out on.</p>
     *
     * <p>{@code GraphView} is the case that found it: it takes focus so its command keys work, so Enter
     * synthesized a press at the physical cursor and it started a rubber band — one that could not be
     * ended, because a marquee is released by the real pointer-up path and the synthesized Up never
     * reaches it.</p>
     */
    public static final int KEYBOARD_DETAIL = 0;

    private void handleActivationKey(Keyboard.Event event) {
        if (focusedElement == null) return;
        if (event.key() != CgKeyCodes.KEY_SPACE && event.key() != CgKeyCodes.KEY_RETURN) return;
        // A text-editing element gets Space as a character, not as activation — synthesizing a click
        // for it would fire the element's press handlers every time somebody typed a space, and the
        // synthesized Down carries the physical cursor position, so it would also land wherever the
        // mouse happened to be.
        if (focusedElement.consumesTextInput()) return;

        if (event.pressed() && !event.repeat()) {
            keyboardPressTarget = focusedElement;
            focusedElement.setPressed(true);
            sendInputEvent(focusedElement,
                    new MouseEvent.Down(focusedElement, hoverFrameData.eventPosition(), 0, KEYBOARD_DETAIL));
        } else if (!event.pressed()) {
            boolean wasPressTarget = focusedElement == keyboardPressTarget; // false if focus moved mid-hold
            if (keyboardPressTarget != null) keyboardPressTarget.setPressed(false);
            sendInputEvent(focusedElement, new MouseEvent.Up(
                    focusedElement, hoverFrameData.eventPosition(), 0, KEYBOARD_DETAIL, wasPressTarget));
            keyboardPressTarget = null;
        }

    }

    /** How many blocked candidates a single Tab will step over before giving up. Far past any real
     * tab ring; it exists so the loop is provably finite rather than because a number was needed. */
    private static final int TAB_SCAN_LIMIT = 512;

    private void moveTabFocus(Keyboard.Event event, int modifiers) {
        if (event.key() != CgKeyCodes.KEY_TAB) return;
        boolean reverse = CgModifiers.hasShift(modifiers);

        // The whole of focus trapping: while a modal is open the tab sequence IS the modal's subtree,
        // because everything outside it is inert. Enforced here rather than inside `tabbable()` so the
        // cached focusable-descendant answers stay free of a condition that changes for nearly every
        // element in the tree the moment a modal opens.
        //
        // WHICH MODAL, though, now that modality is per-window. A window-level one traps everything, so
        // it is asked first; failing that, the trap is whatever blocks the scope focus is currently in.
        // A modal in some OTHER window traps nothing here -- that is the entire point of scoping it --
        // which is why the walk below also has to skip what it blocks.
        UIElement modal = window.getActiveModal(null);
        if (modal == null) modal = window.getActiveModal(UIWindow.modalScopeOf(focusedElement));
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

        // AND NOTHING A MODAL BLOCKS. Scoping above traps Tab inside a modal when there IS one over the
        // focused scope; this is the other half, and it only exists because a modal in one window no
        // longer traps the whole document: tabbing out of an unblocked window would otherwise walk
        // straight into the blocked content of the window beside it, where `tabbable()` -- which sees
        // only the inert ATTRIBUTE, deliberately, so its cache stays valid -- has no objection.
        //
        // Bounded rather than "until unblocked": every candidate can be blocked, and a Tab press must
        // never be able to spin.
        int guard = 0;
        while (window.isModalBlocked(next) && guard++ < TAB_SCAN_LIMIT) {
            UIElement after = reverse
                    ? UITreeTraversal.previousTabbable(next, modal)
                    : UITreeTraversal.nextTabbable(next, modal);
            if (after == null || after == focusedElement || after == next) break;
            next = after;
        }
        if (window.isModalBlocked(next)) return;

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
            // The button that STARTED the drag, not button 0. Identical for every left-button drag in
            // the engine, and the difference between working and hanging for any other: a middle-button
            // pan would otherwise never be told its button came back up, while the implicit capture
            // release below still fired — leaving a live drag consuming every mouse move with no button
            // held at all.
            if (dragController.isDragging() && buttonOrdinal == dragController.getButton()) {
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
        // Position matters as well as time. The guard above only catches a click that lands on a
        // DIFFERENT element, so two clicks on the same large element -- two words in one text editor,
        // two spots in one text field -- counted as a double-click however far apart they were.
        buttonState.setState(event.state(), event.millis(), event.x(), event.y());
    }

    private void emitMouseDown(UIElement targetElement, int buttonId, int detail) {
        // A press that hit nothing normally blurs, matching the browser: click empty space and the active
        // element goes away. But while a modal is open, "hit nothing" does not mean the user clicked bare
        // document — it means inertness ATE the press. Treating the two the same drops the caret out of a
        // dialog's text field the moment you click its dim backdrop, which no dialog anywhere does.
        boolean absorbedByModal = targetElement == null && window.getActiveModal() != null;

        // ...AND BEING BLOCKED IS SHOWN, never silent — W13c.
        //
        // A window that quietly ignores clicks is indistinguishable from one that has hung, which is
        // window-scoped modality's whole failure mode. Windows pulses the dialog responsible and dings;
        // so does this, and it is the first real consumer of CgPlatform.sound() — an SPI wired on every
        // loader that nothing used.
        //
        // Asked at the POINTER rather than from `absorbedByModal` above, because that flag cannot tell
        // which modal is responsible: with per-window modality a press on one window must pulse THAT
        // window's dialog, not whichever is topmost somewhere else. It is also why this is not simply
        // gated on the flag — a press on bare desktop while a modal is open sets it too.
        if (absorbedByModal) {
            var position = hoverFrameData.eventPosition();
            UIElement blocking = window.modalBlockingAt(position.x(), position.y());
            if (blocking instanceof Dialog dialog) dialog.pulse();
        }

        // THE NEAREST FOCUSABLE ANCESTOR, not the exact element hit — the DOM's rule, which is why
        // clicking a <button>'s inner text focuses the button.
        //
        // Composites used to dodge this by making their parts `setHitTest(false)`, which works right up
        // until a part is itself interactive: a tree's fold chevron has to keep the pointer, and it is
        // never focusable. So a press on it blurred the focus owner here and then focused NOTHING, and
        // the fold left the whole window with `focusedElement == null` — no ring anywhere, and
        // `consumeKeyboardEvent` dispatches nothing at all in that state, so the keyboard went dead.
        // Reported as the Problems panel flickering: the ring left the editor tab on the press, and
        // `ListView.restoreFocusIfRealised` then read null as "nobody owns this" and pulled focus onto a
        // row. Two chevrons had it, `GraphNode` works around it with its own `requestFocus`, and the
        // walk is what covers every composite at once.
        UIElement focusTarget = targetElement;
        while (focusTarget != null && !focusTarget.getFocusPolicy().focusesOnClick()) {
            focusTarget = focusTarget.getParent();
        }

        // ONLY THE PRIMARY BUTTON MOVES FOCUS. A right-click opens a menu ABOUT something; it does not
        // choose it, and here that distinction is load-bearing rather than pedantic — a ListView drives
        // its selection entirely from focus, so a right-click that focused a row also selected it, and
        // the menu destroyed the selection it was opened over. Unrecoverable for a multi-selection.
        //
        // The context menu still knows its subject: it reads the row under the pointer directly, which is
        // the rule a context menu follows anyway (a menu bar resolves against focus, a context menu
        // against what was clicked).
        if (buttonId != CgMouseCodes.LEFT_BUTTON) focusTarget = focusedElement;

        if (focusTarget != focusedElement && !absorbedByModal) {
            if (focusedElement != null) {
                emitAndLoseFocus(focusedElement);
            }
            if (focusTarget != null) {
                // Deliberately no scroll: you clicked what you could already see, and scrolling
                // here would pull the content out from under the cursor. Nor a focus ring, unless
                // this is a text field — see emitAndSetFocus.
                emitAndSetFocus(focusTarget, FocusSource.POINTER);
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
        // WHAT ONE NOTCH COSTS, from the dispatch alone. A wheel event is synchronous and lands inside
        // `input` -- everything it triggers that is deferred (the window update, the reprojection, the
        // repaint) shows up in the phases after it, so the split between this and those is what says
        // whether a scroll is expensive to RECEIVE or expensive to react to.
        long timed = FrameProfile.begin();
        sendInputEvent(target, event);
        FrameProfile.step(timed, "input.scroll " + scrollDelta + " -> "
                + (target == null ? "nothing" : target.tagName()));
        // THE WHEEL RESOLVES THROUGH THE KEYMAP TOO, and after dispatch for exactly the reason a keystroke
        // does: a widget under the pointer gets first refusal on its own wheel, and only what nothing
        // wanted becomes a shortcut. That is what lets ScrollerView keep plain and Shift+wheel while
        // Mod+wheel falls through to `editor.zoomIn` -- with no widget hard-coding either, and both
        // remappable.
        //
        // Scoped from the element under the POINTER rather than from focus. A wheel is a pointing gesture:
        // zooming the editor you are hovering, not the one that happens to hold the caret, is what every
        // editor does and what the hover target already means.
        if (event.isDefaultPrevented() || scrollDelta == 0f) return;
        UIElement start = target != null ? target : window.ui.rootElement;
        keymapResolver.resolve(start, KeyStroke.ofWheel(scrollDelta, CgPlatform.input().getCurrentModifiers()),
                KeyEventType.PRESS, System.currentTimeMillis(), false);
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

    /**
     * <b>The focus owner changed.</b> Carries the new one, or {@code null} when focus was dropped.
     *
     * <h3>Why this exists as an announcement</h3>
     *
     * <p>{@code FocusEvent.Focus}/{@code Blur} are dispatched <em>at the element</em> and bubble, so they
     * answer "did I gain focus". They cannot answer "who holds focus now" for something that is not on the
     * path — which is what any observer of the focus owner needs, and there is one in every workbench: an
     * inspector, a context-sensitive toolbar, a status line.</p>
     *
     * <p>Without it those observers have two options, and both are wrong. Poll every frame — the shape
     * plan step 3 deleted five times over — or have the application hand them a subject it picked itself,
     * which caps what can ever be observed to whatever the application thought of. The Inspector had the
     * second: its subject was always the active document's view, so nothing outside a document could be
     * inspected however many sections were registered.</p>
     *
     * <p><b>Deduplicated</b>, so the blur-then-focus pair a single click produces announces the two real
     * states and never the same one twice.</p>
     */
    public final Signal.Value<UIElement> onDidChangeFocus = new Signal.Value<>();

    /** The last value {@link #onDidChangeFocus} carried, so re-stating it is silent. */
    @Nullable
    private UIElement announcedFocus;

    private void announceFocus() {
        if (announcedFocus == focusedElement) return;
        announcedFocus = focusedElement;
        onDidChangeFocus.emit(focusedElement);
    }

    private void emitAndSetFocus(UIElement target, FocusSource source) {
        // A half-entered chord belongs to the scope it was begun in. Carrying it across a focus change
        // would let `Mod+K` typed in one panel complete as `Mod+K Mod+S` in another, firing a command the
        // user never aimed at — and the resolver walks the NEW focus path, so it would not even be the
        // binding they started.
        keymapResolver.cancelPending();
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
        // AFTER the event, so an observer that reads the tree sees the state the event already applied.
        announceFocus();
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
        announceFocus();
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