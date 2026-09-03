package com.crystalgui.ui.service;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgCursor;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.platform.input.CgSystemInput.Keyboard;
import com.crystalgraphics.platform.input.CgSystemInput.Mouse;
import com.crystalgui.core.data.ReadOnlyVec2f;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UISlot;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.KeyboardEvent;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.event.PropagationPhase;
import com.crystalgui.ui.event.UIEvent;
import com.crystalgui.ui.input.ButtonState;
import com.crystalgui.ui.input.keymap.KeyEventType;
import com.crystalgui.ui.input.keymap.KeyStroke;
import com.crystalgui.ui.input.keymap.KeymapResolver;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import org.joml.Vector2f;

/**
 * The input service: the raw platform sink, the hit test, and the dispatch — over the COMPOSED tree,
 * with retargeting, DOM propagation semantics, a pointer capture, and a mode stack consulted before
 * any of it.
 *
 * <h3>What it is not</h3>
 *
 * <p>It is not the old {@code UIInputHandler}, which was the sink, the hover cache, the focus owner,
 * the tab ring, the modal trap, the keymap ladder and the drag controller in 962 lines. Focus is
 * {@link Focus}; a live interaction is a {@link InputMode}; a keymap is the {@link Chords} seam a host
 * installs. What is left here is genuinely one job: turn platform events into tree events.</p>
 *
 * <h3>The frame model, kept</h3>
 *
 * <p>Clicks and keys dispatch immediately; hover, enter/leave, move and scroll are synthesized once
 * per frame from {@link #beginFrame()}/{@link #endFrame()}. {@code beginFrame} only INVALIDATES the
 * hover — it must never read it, because a mouse-move that arrived before the frame already
 * invalidated it, so reading here is an eager recompute against the NEW position wearing the old
 * one's name. That was the original stuck-hover bug, and the baseline is a plain field.</p>
 *
 * <h3>Coordinates</h3>
 *
 * <p>Everything here is in SURFACE pixels, which is the space the box tree's world matrices are
 * composed in ({@code BoxTree.setRootTransform}) — so a hit test needs no conversion and cannot
 * disagree with what was drawn. That is the old engine's "{@code getRootTransform()} is the only
 * definition of {@code uiScale}" invariant, moved to the one place both readers already read.</p>
 */
public final class Input implements CgSystemInput.Mouse, CgSystemInput.Keyboard {

    /**
     * A keymap, as the input service sees it. The keymap itself lives outside the engine — it walks
     * scopes, holds sheets and resolves chords — and a host installs it here.
     *
     * @return whether a binding fired, in which case the keystroke is spent
     */
    public interface Chords {
        boolean resolve(@Nullable UIElement from, int key, int modifiers, boolean pressed, boolean repeat, long millis);

        /**
         * The wheel, resolved the same way and for the same reason: the widget under the pointer
         * gets first refusal on its own wheel, and only what nothing wanted becomes a shortcut —
         * which is what lets a scroller keep plain and Shift+wheel while Mod+wheel zooms, with no
         * widget hard-coding either and both remappable.
         */
        default boolean wheel(@Nullable UIElement from, float notches, int modifiers) {
            return false;
        }
    }

    /**
     * Where a RESOLVED cursor goes — never {@code auto}. Installed by a host, so nothing here has to
     * reach a platform service and a headless tree pays nothing.
     */
    public interface CursorSink {
        void present(CgCursor cursor);
    }

    /**
     * {@code detail} on a click synthesized from the keyboard — <b>zero</b>, the DOM's own signal
     * that no pointer caused it. A real press can never be 0, and a widget whose press means "the
     * pointer went down here" rather than "activate me" needs to be able to tell.
     */
    public static final int KEYBOARD_DETAIL = 0;

    private static final int BUTTONS = 8;

    private final UIDocument document;
    private final List<InputMode> modes = new ArrayList<>();
    private final ButtonState[] buttons = new ButtonState[BUTTONS];
    private final Vector2f position = new Vector2f();
    private final ReadOnlyVec2f pointer = new ReadOnlyVec2f(position);

    private boolean hoverValid;
    private @Nullable UIElement hover;
    private @Nullable UIElement lastFrameHover;
    /**
     * A ghost offered before its drag existed, waiting for {@code Drag.start} to claim it.
     *
     * <p><b>The one ordering a caller cannot avoid.</b> A ghost is offered from the mouse-DOWN handler
     * and the drag is started from the same handler a few lines later, so at the moment
     * {@code DragGhost.follow} runs there is no drag to hand it to — its own comment says as much
     * ("null when nothing is dragging, which is the ordinary case") and then relies on
     * {@code Drag.start} re-reading it, which nothing did. The ghost was set on nothing, every drag in
     * the application carried none, and there was no error to explain it.</p>
     *
     * <p>Dropped when a drag claims it, and dropped again when a press ends without one — a ghost
     * belongs to a single gesture, and the old engine's controller once let one outlive its drag and
     * reappear on an unrelated screen.</p>
     */
    private @Nullable UIElement pendingGhost;

    private float pendingGhostX, pendingGhostY;

    /** Offers a ghost at an explicit cursor offset, in the ghost's own space. */
    public void offerGhost(@Nullable UIElement ghost, float offsetX, float offsetY) {
        pendingGhost = ghost;
        pendingGhostX = offsetX;
        pendingGhostY = offsetY;
        pendingGhostGrab = false;
    }

    /**
     * Offers a ghost anchored where the press landed inside the SOURCE — the drag resolves the offset.
     *
     * <p><b>Not the ghost's own centre</b>, which is the mistake the old controller's javadoc warns
     * about in as many words: "anchored by where inside the source the press landed, not by the ghost's
     * own centre, so the ghost sits exactly where the source visually was relative to the pointer.
     * Grabbing a card by its corner and having it jump to centre-on-cursor is the classic tell of a
     * ghost positioned the wrong way." The port computed the centre, and computed it from a box the
     * ghost does not have while it is hidden — so the offset was zero and every ghost hung below and
     * right of the cursor.</p>
     *
     * <p>Resolved by the drag because only the drag knows it: the press within the source is exactly
     * what {@code Drag}'s own start point is.</p>
     */
    public void offerGhost(@Nullable UIElement ghost) {
        pendingGhost = ghost;
        pendingGhostX = 0f;
        pendingGhostY = 0f;
        pendingGhostGrab = true;
    }

    private boolean pendingGhostGrab;

    /** Whether the offered ghost wants the press-within-source offset. @see #offerGhost(UIElement) */
    boolean pendingGhostGrab() {
        return pendingGhostGrab;
    }

    /** Claims the offered ghost, if any, and forgets it. Called by {@code Drag.start}. */
    @Nullable
    UIElement takePendingGhost() {
        UIElement ghost = pendingGhost;
        pendingGhost = null;
        return ghost;
    }

    float pendingGhostX() {
        return pendingGhostX;
    }

    float pendingGhostY() {
        return pendingGhostY;
    }

    private @Nullable UIElement pressTarget;
    private @Nullable UIElement keyboardPressTarget;
    private @Nullable UIElement capture;
    private float scrollDelta;

    private @Nullable Chords chords;

    /**
     * The keymap every document has, built on first use — see {@link #chords()}.
     *
     * <p>Lazy so a tree nobody presses a chord in never builds a resolver, and per document because a
     * {@code CommandRegistry} is.</p>
     */
    private @Nullable Chords defaultChords;

    /**
     * The keymap in force: whatever a host installed, else the document's own.
     *
     * <p><b>A seam a host has to remember is a seam that stays empty.</b> Nothing ever called
     * {@link #setChords}, so {@code chords} was permanently null and no chord resolved anywhere — every
     * keyboard shortcut in the application was inert, and the failure was silent in the worst way,
     * because an unresolved chord falls through to ordinary dispatch and then to Tab traversal. So
     * {@code Ctrl+Tab} did not "do nothing": it cycled focus between windows, which looks like a
     * deliberate and slightly wrong feature rather than a missing one.</p>
     *
     * <p>The document already owns the {@code CommandRegistry} the keymap resolves against, so there is
     * nothing for a host to supply that is not already here. {@code setChords} stays as the override —
     * a host presenting shortcuts its own way, or a test asserting on them.</p>
     */
    private @Nullable Chords chords() {
        if (chords != null) return chords;
        if (defaultChords == null) {
            KeymapResolver resolver = new KeymapResolver(document.getCommands());
            defaultChords = new Chords() {
                @Override
                public boolean resolve(@Nullable UIElement from, int key, int modifiers,
                                       boolean pressed, boolean repeat, long millis) {
                    return resolver.resolve(from, new KeyStroke(key, modifiers),
                            pressed ? KeyEventType.PRESS : KeyEventType.RELEASE, millis, repeat);
                }

                @Override
                public boolean wheel(@Nullable UIElement from, float notches, int modifiers) {
                    return resolver.resolve(from, KeyStroke.ofWheel(notches, modifiers),
                            KeyEventType.PRESS, System.currentTimeMillis());
                }
            };
        }
        return defaultChords;
    }
    private @Nullable CursorSink cursors;
    private CgCursor lastCursor = CgCursor.DEFAULT;

    /**
     * Whether the default sink has been resolved, and to what.
     *
     * <p>Three states rather than two, because "no host installed one" and "there is no platform" are
     * different: the first wants the default, the second wants silence. {@code UNRESOLVED} until the
     * pointer first needs a cursor, so a tree nobody points at never asks.</p>
     */
    private DefaultSink defaultSink = DefaultSink.UNRESOLVED;

    private enum DefaultSink { UNRESOLVED, PLATFORM, NONE }

    public Input(UIDocument document) {
        this.document = document;
    }

    // ── The mode stack ───────────────────────────────────────────────────────

    /** Pushes a live interaction above everything on the tree. The last pushed is asked first. */
    public void pushMode(InputMode mode) {
        modes.add(mode);
    }

    /** Ends a mode wherever it sits in the stack, and tells it so. A no-op if it is not there. */
    public void popMode(InputMode mode) {
        if (modes.remove(mode)) mode.ended();
    }

    /** Whether this mode is live. */
    public boolean hasMode(InputMode mode) {
        return modes.contains(mode);
    }

    /**
     * The innermost live mode of {@code type}, or null.
     *
     * <p>What "is a drag running, and which one" is asked with. {@link #hasMode} takes an INSTANCE and
     * answers a different question — whether <em>this</em> mode is still live, which is what a mode
     * asks about itself. A caller that only has a class has no instance to pass.</p>
     */
    @Nullable
    public <M extends InputMode> M mode(Class<M> type) {
        for (int i = modes.size() - 1; i >= 0; i--) {
            InputMode mode = modes.get(i);
            if (type.isInstance(mode)) return type.cast(mode);
        }
        return null;
    }

    /** The live modes, innermost first — what is asked, in the order it is asked. */
    public List<InputMode> modes() {
        List<InputMode> topFirst = new ArrayList<>(modes);
        Collections.reverse(topFirst);
        return topFirst;
    }

    // ── Seams a host installs ────────────────────────────────────────────────

    public Input setChords(@Nullable Chords chords) {
        this.chords = chords;
        return this;
    }

    /**
     * Intercepts the resolved cursor, instead of letting it reach the platform.
     *
     * <p>Optional: with no sink the cursor goes to {@code CgPlatform.cursor()}, which is where one
     * comes from anyway. A host installs one to take it somewhere else — a test that asserts on the
     * cursor, or a loader presenting it through its own screen.</p>
     */
    public Input setCursorSink(@Nullable CursorSink cursors) {
        this.cursors = cursors;
        return this;
    }

    // ── Frame ────────────────────────────────────────────────────────────────

    /**
     * Invalidates the hover, so this frame's hit test runs against this frame's layout — a reflow
     * under a stationary pointer must not leave hover stale until the next real move.
     */
    public void beginFrame() {
        hoverValid = false;
    }

    /** The hover diff, the boundary events, the move and the wheel — once, from this frame's layout. */
    public void endFrame() {
        UIElement current = hoverTarget();
        UIElement last = lastFrameHover;
        if (last == current) {
            if (current != null) send(current, new MouseEvent.Move(current, pointer));
        } else {
            UIElement common = commonComposedAncestor(last, current);
            updateHoverChain(last, current, common);
            leaveChain(last, common);
            enterChain(current, common);
        }
        if (scrollDelta != 0f) {
            MouseEvent.Scroll scroll = new MouseEvent.Scroll(current, pointer, scrollDelta);
            send(current, scroll);
            // AFTER dispatch, like a keystroke, and scoped from the POINTER rather than from focus:
            // a wheel is a pointing gesture, so zooming the editor you are hovering -- not the one
            // that happens to hold the caret -- is what every editor does.
            Chords wheelChords = chords();
            if (!scroll.isDefaultPrevented() && wheelChords != null) {
                wheelChords.wheel(scopeFor(current), scrollDelta, modifiers());
            }
            scrollDelta = 0f;
        }
        presentCursor(current);
        lastFrameHover = current;
    }

    /**
     * Resolves the CSS {@code cursor} for whatever the pointer is over and pushes it once.
     *
     * <p>Driven from the hover rather than from the property's change listener: the cursor is a
     * function of WHERE THE POINTER IS, so reacting to the property fires for nodes nowhere near it
     * and still misses the pointer moving onto one whose value never changed. Resolution runs every
     * frame and only the sink call is skipped — a stationary pointer can still need a different
     * cursor because the node under it changed.</p>
     */
    private void presentCursor(@Nullable UIElement hovered) {
        CgCursor resolved = resolveCursor(hovered);
        if (resolved == lastCursor) return;
        lastCursor = resolved;
        if (cursors != null) {
            cursors.present(resolved);
            return;
        }
        // NO SINK INSTALLED, so ask the platform -- which is where a cursor comes from by this
        // project's own rule, not a boundary this is stepping over. Without it every resize handle
        // on this engine showed the default arrow: the cursor was resolved correctly on every frame
        // and pushed into a sink nobody had installed.
        if (defaultSink == DefaultSink.NONE) return;
        try {
            CgPlatform.cursor().setCursor(resolved);
            defaultSink = DefaultSink.PLATFORM;
        } catch (RuntimeException noPlatform) {
            // A DEDICATED SERVER REGISTERS NO PLATFORM and `CgPlatform.cursor()` throws rather than
            // answering an absent-value, so this is the probe rather than a guard: asked once, and a
            // tree with nothing behind it stops asking. Not logged -- a headless tree having no
            // cursor is the normal case, not a fault.
            defaultSink = DefaultSink.NONE;
        }
    }

    /**
     * The spec's {@code auto} rule: {@code text} over an editable node, {@code default} otherwise.
     * {@code cursor} is inheritable, so the cascade has already answered what a nested node shows.
     */
    private static CgCursor resolveCursor(@Nullable UIElement hovered) {
        if (hovered == null) return CgCursor.DEFAULT;
        CgCursor declared = hovered.computedStyle().get(StylePropertyRegistry.CURSOR);
        if (declared == null) return CgCursor.DEFAULT;
        if (!declared.needsResolution()) return declared;
        // OVER EDITABLE CONTENT, and a SLOT is not a control -- it is a placeholder for one.
        //
        // The old engine asked the hit node and nothing else, and that was right because a composite made
        // its parts unhittable, so a press in a text control landed on the CONTROL. This engine puts a
        // caller's content in a slot, and the slot is what the pointer lands on, so asking the hit node
        // answered `default` over every line of text in the application.
        //
        // Walking all the way up is the obvious repair and over-reaches: an editor's scrollbars and its
        // fold chevrons are inside it too, and the I-beam then follows the pointer onto a scrollbar,
        // which nothing does. A slot is the only node that genuinely stands for something else, so it is
        // the only one whose question is passed on -- everything else answers for itself, exactly as it
        // did before.
        //
        // The tell for the original bug was that the I-beam appeared on press and held while dragging: a
        // press takes pointer capture, and capture substitutes the hit for the capturing element, which
        // is the control. So the resolution was correct exactly when it was asked about the right node.
        UIElement at = hovered;
        while (at instanceof UISlot) at = at.composedParent();
        return at != null && at.consumesTextInput() ? CgCursor.TEXT : CgCursor.DEFAULT;
    }

    /** The cursor currently presented. Resolved, so never {@link CgCursor#AUTO}. */
    public CgCursor currentCursor() {
        return lastCursor;
    }

    // ── The pointer ──────────────────────────────────────────────────────────

    /** The pointer, in surface pixels. A live view — do not retain it across frames. */
    public ReadOnlyVec2f pointer() {
        return pointer;
    }

    /**
     * Says this frame's hit is no longer trustworthy — what {@link #beginFrame} does every frame, and
     * what anything that changes hit-testing WITHIN a frame (a modal opening) must do for itself.
     */
    public void invalidateHover() {
        hoverValid = false;
    }

    /** What the pointer is over: the capture target while captured, the real hit otherwise. */
    @Nullable
    public UIElement hoverTarget() {
        if (!hoverValid) {
            hover = resolveHit(position.x, position.y);
            hoverValid = true;
        }
        return hover;
    }

    /**
     * Routes every pointer event to {@code node} regardless of what is underneath — Pointer Events
     * L3's {@code setPointerCapture}, and the primitive a drag is built on.
     *
     * <p>Boundary events fall out for free: the hover resolves to the capture target for the whole
     * capture, so the per-frame diff sees no change and nothing enters or leaves. Fails silently
     * with no button down, per spec — a capture nothing can release would wedge input.</p>
     */
    public void setPointerCapture(UIElement node) {
        if (node == null || node.document() != document || !anyButtonDown()) return;
        capture = node;
        hoverValid = false;
    }

    public void releasePointerCapture() {
        if (capture == null) return;
        capture = null;
        hoverValid = false;
    }

    @Nullable
    public UIElement pointerCaptureTarget() {
        return capture;
    }

    private @Nullable UIElement resolveHit(float x, float y) {
        UIElement captured = capture;
        if (captured != null) {
            if (captured.document() == document) return captured;
            capture = null;
        }
        Focus focus = document.focus();
        Box box = document.boxes().hitTest(x, y, b -> focus.isInert(b.node()));
        return box == null ? null : box.node();
    }

    /**
     * The modal that a click here would land under, or null.
     *
     * <p><b>Inertness makes a hit FALL THROUGH, and for modality that is the wrong answer.</b> Skipping
     * an inert subtree is right for {@code pointer-events: none} -- the pointer passes over a node, it
     * does not punch a hole in the document -- so a press on a blocked window resolved to whatever sat
     * behind it, which on a desktop is the window LAYER, whose own listener treats a press as "the user
     * clicked bare background" and deactivates. So clicking a blocked window's caption did not merely
     * fail to raise it: it took the active window away entirely, leaving a desktop reporting nothing
     * active with a dialog plainly on screen. The only way back in was to click the dialog.</p>
     *
     * <p>A modal has to SWALLOW the press instead. It is asked of the RAW hit -- the geometry, with no
     * inertness filter -- because the question is "what did the user aim at", and then of that node's
     * own scope, never the globally topmost modal: with per-window modality that would flash a window
     * the user is not looking at while the one they clicked stayed silent.</p>
     *
     * <p>Null while a pointer is captured: a drag routes every event to its capture target by
     * definition, and a drag cannot have started inside something inert.</p>
     */
    private @Nullable UIElement modalAbsorbing() {
        if (capture != null) return null;
        Box under = document.boxes().hitTest(position.x, position.y);
        return document.focus().blockingModal(under == null ? null : under.node());
    }

    private boolean anyButtonDown() {
        for (ButtonState state : buttons) {
            if (state != null && state.isPressed()) return true;
        }
        return false;
    }

    // ── Dispatch ─────────────────────────────────────────────────────────────

    /**
     * The three phases over the COMPOSED path, with the event's target RETARGETED per listener:
     * a listener outside a shadow tree is told the host, never the part — the spec's algorithm, and
     * the reason a composite can be encapsulated without every consumer knowing it is one.
     *
     * <p>Propagation is the DOM's: {@code stopPropagation} ends the WALK and the remaining
     * listeners on the same node still run; only {@code stopImmediatePropagation} ends those. The
     * old engine conflated them, which is why a listener attached to a widget's own group after its
     * constructor could never run.</p>
     */
    public void send(@Nullable UIElement target, UIEvent event) {
        if (target == null) return;
        List<UIElement> path = composedPath(target);   // root first, path.get(last) == target

        event.setPhase(PropagationPhase.CAPTURE);
        for (int i = 0; i < path.size() - 1 && !event.isPropagationStopped(); i++) {
            emit(path.get(i), target, event);
        }
        if (event.isPropagationStopped()) return;

        event.setPhase(PropagationPhase.TARGET);
        emit(target, target, event);
        if (!event.isBubbles() || event.isPropagationStopped()) return;

        event.setPhase(PropagationPhase.BUBBLE);
        for (int i = path.size() - 2; i >= 0 && !event.isPropagationStopped(); i--) {
            emit(path.get(i), target, event);
        }
    }

    private static void emit(UIElement listener, UIElement target, UIEvent event) {
        event.retarget(UIElement.retarget(target, listener));
        listener.events.emitToGroupDom(event);
    }

    /** The composed path from the document down to {@code target}, inclusive. */
    private static List<UIElement> composedPath(UIElement target) {
        List<UIElement> path = new ArrayList<>();
        for (UIElement at = target; at != null; at = at.composedParent()) path.add(at);
        Collections.reverse(path);
        return path;
    }

    @Nullable
    private static UIElement commonComposedAncestor(@Nullable UIElement a, @Nullable UIElement b) {
        if (a == null || b == null) return null;
        for (UIElement up = a; up != null; up = up.composedParent()) {
            for (UIElement down = b; down != null; down = down.composedParent()) {
                if (up == down) return up;
            }
        }
        return null;
    }

    private static void updateHoverChain(@Nullable UIElement from, @Nullable UIElement to, @Nullable UIElement common) {
        for (UIElement at = from; at != null && at != common; at = at.composedParent()) at.setHovered(false);
        for (UIElement at = to; at != null && at != common; at = at.composedParent()) at.setHovered(true);
    }

    /**
     * Fires {@code Leave} on EVERY node being left, innermost first — the pair does not bubble, and
     * the DOM still fires one per node in the chain. Firing only on the exact target means a
     * container with children never hears about the pointer at all.
     */
    private void leaveChain(@Nullable UIElement from, @Nullable UIElement common) {
        if (from == null) return;
        send(from, new MouseEvent.Move(from, pointer));
        for (UIElement at = from; at != null && at != common; at = at.composedParent()) {
            send(at, new MouseEvent.Leave(at, pointer));
        }
    }

    /** The counterpart, outermost first: an ancestor learns the pointer arrived before its child. */
    private void enterChain(@Nullable UIElement to, @Nullable UIElement common) {
        if (to == null) return;
        send(to, new MouseEvent.Move(to, pointer));
        List<UIElement> entered = new ArrayList<>();
        for (UIElement at = to; at != null && at != common; at = at.composedParent()) entered.add(at);
        for (int i = entered.size() - 1; i >= 0; i--) {
            UIElement at = entered.get(i);
            send(at, new MouseEvent.Enter(at, pointer));
        }
    }

    // ── The platform sink ────────────────────────────────────────────────────

    @Override
    public boolean consumeMouseEvent(Mouse.Event event) {
        if (event.x() != position.x || event.y() != position.y) hoverValid = false;
        position.set(event.x(), event.y());
        scrollDelta += event.wheelDelta();

        for (InputMode mode : modes()) {
            if (mode.pointerMoved(position.x, position.y)) break;
        }
        if (event.button() == -1) return false;

        for (InputMode mode : modes()) {
            if (mode.pointerButton(event.button(), event.state(), position.x, position.y)) return true;
        }
        return button(event);
    }

    private boolean button(Mouse.Event event) {
        UIElement absorbedBy = modalAbsorbing();
        if (absorbedBy != null) {
            // SWALLOWED, and only a press is worth reporting -- a release has no gesture in it.
            if (event.state()) document.focus().blockedScopeOf(absorbedBy).pressBlocked(absorbedBy);
            return true;
        }
        UIElement target = hoverTarget();
        int ordinal = event.button();
        ButtonState state = buttonState(ordinal);
        if (state != null) {
            if (event.state() && target != pressTarget) state.resetDetail();
            state.setState(event.state(), event.millis(), event.x(), event.y());
        }
        int detail = state == null ? 1 : state.getDetail();

        if (event.state()) {
            pressTarget = target;
            // THE POPOVER STACK AS IT STOOD BEFORE DISPATCH, read now and acted on after.
            //
            // A popover opened FROM a mouse-down handler must not be closed by the very press that
            // opened it -- it would appear never to open at all -- so light dismiss judges against the
            // stack as it was when the press landed, not as it is once the press has been delivered.
            int shownBefore = document.dismiss().showSeq();
            if (target != null && ordinal == 0) target.setPressed(true);
            document.focus().pressed(target, ordinal, false);
            send(target, new MouseEvent.Down(target, pointer, ordinal, detail));
            // AFTER the dispatch, which is the spec's order and browsers': dismissing first tears down
            // the tree under an undelivered event, so the press would never reach what it landed on.
            // On press rather than the spec's press/release pair -- that pairing exists for
            // text-selection drags, which this engine has no equivalent of.
            document.dismiss().lightDismiss(target, shownBefore);
        } else {
            boolean wasPressTarget = target == pressTarget;
            if (pressTarget != null && ordinal == 0) pressTarget.setPressed(false);
            send(target, new MouseEvent.Up(target, pointer, ordinal, detail, wasPressTarget));
            // Implicit release AFTER the up is delivered -- the spec's ordering, and what lets a drag
            // end anywhere on screen rather than only over its source.
            if (!anyButtonDown()) releasePointerCapture();
            // AND A GHOST NOBODY CLAIMED GOES WITH THE PRESS. Offered on the way down, and if the
            // gesture turned out to be an ordinary click there is no drag to have taken it -- leaving
            // it would hand it to the next drag, which is how the old engine's controller once let a
            // ghost outlive its drag and turn up on an unrelated screen.
            if (!anyButtonDown()) pendingGhost = null;
        }
        return false;
    }

    private @Nullable ButtonState buttonState(int button) {
        if (button < 0 || button >= BUTTONS) return null;
        if (buttons[button] == null) buttons[button] = new ButtonState();
        return buttons[button];
    }

    @Override
    public boolean consumeKeyboardEvent(Keyboard.Event event) {
        int modifiers = modifiers();
        for (InputMode mode : modes()) {
            boolean taken = event.pressed()
                    ? mode.keyPressed(event.key(), modifiers, event.repeat())
                    : mode.keyReleased(event.key(), modifiers);
            if (taken) return true;
        }

        UIElement focused = document.focus().focused();
        // I6: A MODIFIED CHORD GOES TO THE KEYMAP FIRST unless the target claims it.
        //
        // The old order was the reverse -- dispatch, then the keymap on whatever nothing consumed --
        // which is right for an unmodified key (a control owns its own typing) and is what forced
        // every text widget to keep a YIELD LIST: `TextEditor` had to know that Tab must be given up
        // when a modifier is held, `SearchReplaceBar` had the same gap, and a `return true` on a chord
        // a widget did not want was indistinguishable from that widget handling it. Asking the widget
        // to CLAIM what it wants inverts that: the list is of what a widget takes, which it knows.
        boolean chorded = event.pressed() && isChord(modifiers)
                && (focused == null || !focused.claimsChord(event.key(), modifiers));
        Chords keymap = chords();
        if (chorded && keymap != null
                && keymap.resolve(scopeFor(focused), event.key(), modifiers, true, event.repeat(), event.millis())) {
            return true;
        }

        if (event.pressed()) {
            KeyboardEvent.Down down = new KeyboardEvent.Down(focused, event.key(), event.character(),
                    event.repeat(), modifiers, event.millis());
            send(focused, down);
            boolean consumed = down.isPropagationStopped() || down.isDefaultPrevented();
            // THE ACCESSOR, NOT THE FIELD. `chords` is null until a host installs one, and `chords()`
            // is what falls back to the document's own command registry -- so reading the field here
            // skipped the keymap entirely for every UNMODIFIED key on every host that never called
            // `setChords`, while the modified-chord path above (which uses the accessor) worked.
            //
            // What that looked like: `Space` is bound to `graph.createNode`, and with the keymap
            // skipped it fell through to `activation` instead, which synthesized a press on the focused
            // node. The graph is focusable so its commands can resolve, so Space started a canvas PAN
            // and the create menu never opened -- two symptoms that read as a broken binding and a
            // broken gesture rather than as one lookup asking the wrong thing.
            if (!consumed && keymap != null
                    && keymap.resolve(scopeFor(focused), event.key(), modifiers, true, event.repeat(), event.millis())) {
                return true;
            }
            // ONE KEYSTROKE DOES ONE THING: traversal and activation are gated on the same answer.
            if (!consumed) consumed = document.focus().moveTabFocus(event.key(), modifiers);
            if (!consumed) activation(event, focused);
            // ESCAPE IS A CASCADE, and this is its last rung.
            //
            // AFTER dispatch and only on a key nothing consumed, which is the same rule the keymap
            // follows and for the same reason: a control gets first refusal on its own keystrokes, so
            // a search box clears its query before the dialog around it closes. The mode stack above
            // has already had it -- a live drag eats Escape before any of this -- and `Dismiss` walks
            // the close-watcher stack from the top, so a dropdown opened inside a modal closes first
            // and only a second Escape reaches the modal.
            if (!consumed && event.key() == CgKeyCodes.KEY_ESCAPE) {
                consumed = document.dismiss().escape(scopeFor(focused));
            }
            // AND THE HOST IS TOLD. The platform acts on what is left over -- a GuiScreen closes on an
            // Escape nobody wanted -- so reporting a consumed key as untouched closes the screen under
            // whatever just handled it.
            return consumed;
        }
        KeyboardEvent.Up up = new KeyboardEvent.Up(focused, event.key(), event.character(),
                event.repeat(), modifiers, event.millis());
        send(focused, up);
        // The accessor here too -- a release must reach the same keymap the press did, or a
        // hold-to-open gesture never hears that its key came up. @see #consumeKeyboardEvent
        if (keymap != null) keymap.resolve(scopeFor(focused), event.key(), modifiers, false, false, event.millis());
        activation(event, focused);
        return false;
    }

    /**
     * Where the keymap starts walking: the focused node, or the DOCUMENT when nothing holds focus.
     *
     * <p>Not a detail. The old handler returned before ever reaching the keymap on a null focus,
     * which made every application-wide binding — the command palette, save, quit — dead until the
     * user happened to click something focusable first. A browser hands the keystroke to the
     * document; so do we.</p>
     */
    private UIElement scopeFor(@Nullable UIElement from) {
        return from != null ? from : document;
    }

    /** A chord is a MODIFIED keystroke; Shift alone is a character, not a chord. */
    private static boolean isChord(int modifiers) {
        return CgModifiers.hasCtrl(modifiers)
                || CgModifiers.hasAlt(modifiers);
    }

    /**
     * The live modifier mask. A raw {@code Keyboard.Event} does not carry one, so it comes from the
     * platform — and a tree with no platform registered (every headless test) answers none rather
     * than failing, which is the honest reading of "this host has no modifier state".
     */
    private int modifiers() {
        try {
            return CgPlatform.input().getCurrentModifiers();
        } catch (IllegalStateException noPlatform) {
            return 0;
        }
    }

    /**
     * Space/Enter over a focused node synthesize the same press a mouse would — which is the whole
     * of keyboard activation, and why {@code Button} contains no keyboard code.
     */
    private void activation(Keyboard.Event event, @Nullable UIElement focused) {
        if (focused == null) return;
        if (event.key() != CgKeyCodes.KEY_SPACE
                && event.key() != CgKeyCodes.KEY_RETURN) return;
        // A text-editing node takes Space as a character; synthesizing a press would fire its
        // handlers every time somebody typed a space, at wherever the pointer happens to be.
        if (focused.consumesTextInput()) return;

        if (event.pressed() && !event.repeat()) {
            keyboardPressTarget = focused;
            focused.setPressed(true);
            send(focused, new MouseEvent.Down(focused, pointer, 0, KEYBOARD_DETAIL));
        } else if (!event.pressed()) {
            boolean wasPressTarget = focused == keyboardPressTarget;
            if (keyboardPressTarget != null) keyboardPressTarget.setPressed(false);
            send(focused, new MouseEvent.Up(focused, pointer, 0, KEYBOARD_DETAIL, wasPressTarget));
            keyboardPressTarget = null;
        }
    }

    // ── Forgetting ───────────────────────────────────────────────────────────

    /**
     * Drops every reference to a node that has left the tree or been frozen — hover, the press
     * target, the capture. Focus is {@link Focus}'s to drop.
     *
     * <p>The old engine forgot focus and not hover, so deleting the node under the pointer left the
     * baseline in a detached subtree and the next diff asked for a common ancestor across two trees:
     * a walk that never converges.</p>
     */
    public void forget(UIElement node) {
        for (UIElement at : node.composedSubtree()) {
            if (hover == at) {
                hover = null;
                hoverValid = false;
            }
            if (lastFrameHover == at) lastFrameHover = null;
            if (pressTarget == at) pressTarget = null;
            if (keyboardPressTarget == at) keyboardPressTarget = null;
            if (capture == at) capture = null;
            at.setHovered(false);
            at.setPressed(false);
        }
    }
}
