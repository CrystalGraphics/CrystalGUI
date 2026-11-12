package com.crystalgui.screen;

import com.crystalgraphics.platform.input.CgInput;
import com.crystalgraphics.platform.input.CgInputListener;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.platform.service.CgScreens;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.input.HostPointer;
import com.crystalgui.viewport.CgUiViewport;

import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * The desktop's one input router, at CrystalGraphics' {@code SCREEN} input stage: on the desktop's own screen every
 * event goes to its viewport, hot keys first; over another mod's screen a press goes to a pinned window only where one
 * is, and the keyboard follows the last press. Raw surface pixels, top-down Y, the engine's key codes: nothing here is
 * a game type, which is why it is one implementation for every version.
 *
 * <ul>
 *   <li><b>The keyboard has no position.</b> Over another screen it is ours from a press inside a pinned window until a
 *       press outside one, or until that screen closes: the rule every OS overlay follows, stable under a still
 *       pointer, where following the hover would break typing in chat the moment the pointer drifted.</li>
 *   <li>A sequenced document is never read here: presses are routed from the frame's {@code HIT_REGIONS}.</li>
 * </ul>
 */
final class CgUiScreenInput implements CgInputListener, CgSystemInput.Mouse, CgSystemInput.Keyboard {

    /** Route presses from the frame's regions inline too. */
    private static final boolean USE_REGIONS = Boolean.getBoolean("crystalgui.ui.hitRegions");
    /** Compare the two answers on every press and say where they differ. */
    private static final boolean CHECK_REGIONS = Boolean.getBoolean("crystalgui.input.regionsCheck");

    /** Who is offered a key before the desktop, while no text field has focus. */
    private final List<Predicate<CgSystemInput.Keyboard.Event>> hotKeys = new CopyOnWriteArrayList<>();
    /** Who is offered a key the desktop left, before the game is. */
    private final List<Predicate<CgSystemInput.Keyboard.Event>> keysLeft = new CopyOnWriteArrayList<>();

    /** Whether a press has landed inside a pinned window more recently than outside one, over another screen. */
    private volatile boolean keyboardIsOurs;

    CgUiScreenInput() {
    }

    /** The desktop was taken down: nothing of ours holds the keyboard. Hot keys and keys left stay with their owners. */
    void reset() {
        keyboardIsOurs = false;
    }

    // ── The SCREEN input stage ──────────────────────────────────────────────────────────────────

    @Override
    public boolean wants() {
        return CgUiScreen.mode().isInteractive();
    }

    @Override
    public boolean mouseMoved(CgSystemInput.Mouse.Event e) {
        pointer(e, HostPointer.NO_BUTTON, false, 0f);
        return false;
    }

    @Override
    public boolean mouseButton(CgSystemInput.Mouse.Event e) {
        return pointer(e, e.button(), e.state(), 0f);
    }

    @Override
    public boolean scrolled(CgSystemInput.Mouse.Event e) {
        return pointer(e, HostPointer.NO_BUTTON, false, e.wheelDelta());
    }

    @Override
    public boolean key(CgSystemInput.Keyboard.Event e) {
        return keyboard(e);
    }

    @Override
    public boolean character(CgSystemInput.Keyboard.Event e) {
        return keyboard(e);
    }

    @Override
    public boolean composition(String text, int caret) {
        CgUiViewport viewport = CgUiScreen.viewport();
        return CgUiScreen.mode() == CgUiScreen.Mode.DESKTOP && viewport != null && viewport.consumeComposition(text, caret);
    }

    private boolean pointer(CgSystemInput.Mouse.Event e, int button, boolean pressed, float wheel) {
        return switch (CgUiScreen.mode()) {
            case DESKTOP -> {
                consumeMouseEvent(HostPointer.of(e.x(), e.y(), e.dx(), e.dy(), button, pressed, wheel, e.millis()));
                yield true;
            }
            case OVERLAY -> overlayMouse(CgInput.pointerGrabbed(), e.x(), e.y(), button, pressed, wheel);
            default -> false;
        };
    }

    private boolean keyboard(CgSystemInput.Keyboard.Event e) {
        return switch (CgUiScreen.mode()) {
            case DESKTOP -> consumeKeyboardEvent(e);
            case OVERLAY -> keyboardIsOurs && viewportKey(e);
            default -> false;
        };
    }

    // ── On the desktop's screen, and by hand ────────────────────────────────────────────────────

    @Override
    public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
        CgUiViewport viewport = CgUiScreen.viewport();
        return viewport != null && viewport.consumeMouseEvent(event);
    }

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        if (CgUiScreen.viewport() == null) return false;
        if (offerHotKey(event)) return true;
        return viewportKey(event);
    }

    private boolean viewportKey(CgSystemInput.Keyboard.Event event) {
        CgUiViewport viewport = CgUiScreen.viewport();
        return viewport != null && viewport.consumeKeyboardEvent(event);
    }

    // ── Keys around the desktop ─────────────────────────────────────────────────────────────────

    Runnable onHotKey(Predicate<CgSystemInput.Keyboard.Event> listener) {
        hotKeys.add(listener);
        return () -> hotKeys.remove(listener);
    }

    Runnable onKeyLeft(Predicate<CgSystemInput.Keyboard.Event> listener) {
        keysLeft.add(listener);
        return () -> keysLeft.remove(listener);
    }

    /** A hot key, unless the frame on screen has a text field focused. */
    private boolean offerHotKey(CgSystemInput.Keyboard.Event key) {
        if (hotKeys.isEmpty() || CgUiScreen.textInputArea() != null) return false;
        for (Predicate<CgSystemInput.Keyboard.Event> listener : hotKeys) {
            if (listener.test(key)) return true;
        }
        return false;
    }

    boolean offerLeftKey(CgSystemInput.Keyboard.Event key) {
        for (Predicate<CgSystemInput.Keyboard.Event> listener : keysLeft) {
            if (listener.test(key)) return true;
        }
        return false;
    }

    /** Every key the document dispatched and left since the last frame, to the game, in order. Render thread. */
    void returnUnhandledKeys() {
        CgUiViewport viewport = CgUiScreen.viewport();
        if (viewport != null) returnUnhandledKeys(viewport);
    }

    /** As {@link #returnUnhandledKeys()}, for what one application's own document left. */
    void returnUnhandledKeys(CgUiViewport viewport) {
        for (CgSystemInput.Keyboard.Event key; (key = viewport.pollUnhandledKey()) != null; ) {
            if (!offerLeftKey(key)) CgScreens.giveBack(key);
        }
    }

    // ── Over another screen ─────────────────────────────────────────────────────────────────────

    /** Another screen opened or closed: closing releases the keyboard, or the next one opens with a stale owner. */
    void foreignScreenChanged(boolean open) {
        if (!open) keyboardIsOurs = false;
    }

    /** Whether a pinned window owns the keyboard over another screen. */
    boolean ownsKeyboard() {
        return keyboardIsOurs;
    }

    /**
     * Offers one pointer event over another screen, refused while the pointer is the camera (a grab under a screen
     * means a mod captured it): a press there is an attack, not a click on a pinned window.
     *
     * @return whether the desktop took it, so the other screen must not see it
     */
    boolean overlayMouse(boolean grabbed, int xPx, int yPx, int button, boolean pressed, float wheel) {
        if (grabbed || !CgScreens.anyOpen()) return false;
        CgUiViewport viewport = CgUiScreen.viewport();
        UIDocument document = CgUiScreen.document();
        if (viewport == null || document == null) return false;
        if (viewport.isSequenced()) return routeOverlay(viewport, document, xPx, yPx, button, pressed, wheel);
        return viewport.ask(() -> routeOverlay(viewport, document, xPx, yPx, button, pressed, wheel));
    }

    private boolean routeOverlay(CgUiViewport viewport, UIDocument document, int xPx, int yPx, int button,
                                 boolean pressed, float wheel) {
        // A MOVE IS THE ONE EVENT BOTH SIDES CAN HAVE: ours tracks the pointer or nothing highlights, theirs keeps
        // working over its own widgets. Always delivered, never consumed.
        boolean isMove = button < 0 && wheel == 0f;
        boolean fromRegions = USE_REGIONS || viewport.isSequenced();
        HitRegions frozen = regions(viewport);
        // AN ACTIVE POINTER CAPTURE OUTRANKS THE HIT TEST: a drag started in a pinned window keeps its moves and its
        // release after the pointer leaves it, as every window move does.
        boolean captured = fromRegions ? frozen.pointerCaptured() : document.input().pointerCaptureTarget() != null;
        boolean inside = isMove || captured || takes(viewport, document, frozen, xPx, yPx);

        if (!inside) {
            if (pressed) {
                // A PRESS OUTSIDE GIVES THE KEYBOARD BACK, and the focus ring and any open menu with it.
                keyboardIsOurs = false;
                document.execute(() -> pressedOutside(document));
            }
            return false;
        }
        // THE BUTTON IS PASSED THROUGH, never clamped: a move carries -1, and a clamped move reads as a release. A
        // move carries no click time, or the multi-click count drifts.
        long millis = button < 0 ? -1L : System.currentTimeMillis();
        viewport.consumeMouseEvent(new CgSystemInput.Mouse.Event(xPx, yPx, 0, 0, button, pressed, wheel, millis));
        if (isMove) return false;
        if (pressed) keyboardIsOurs = true;
        return true;
    }

    private static HitRegions regions(CgUiViewport viewport) {
        HitRegions frozen = viewport.readout(DesktopContent.HIT_REGIONS);
        return frozen == null ? HitRegions.NONE : frozen;
    }

    /** What a press outside every pinned window does to the tree. On the document. */
    private static void pressedOutside(UIDocument document) {
        UIElement focused = document.focus().focused();
        if (focused != null) document.focus().blurIfFocused(focused);
        // A MENU IS DISMISSED BY A PRESS ANYWHERE, ours or not: light dismiss only ever sees presses we consumed.
        if (!document.dismiss().autoPopovers().isEmpty()) document.dismiss().lightDismiss(null);
    }

    private static boolean takes(CgUiViewport viewport, UIDocument document, HitRegions frozen, int xPx, int yPx) {
        if (viewport.isSequenced() || (USE_REGIONS && !CHECK_REGIONS)) return frozen.takes(xPx, yPx);
        boolean live = overlayHitTest(document, xPx, yPx) != null;
        if (CHECK_REGIONS) {
            boolean fromRegions = frozen.takes(xPx, yPx);
            if (fromRegions != live) {
                CrystalGuiCore.LOGGER.warn("[cgui] hit regions say {} at {},{} where the live test says {} ({} regions)",
                        fromRegions ? "ours" : "the game's", xPx, yPx, live ? "ours" : "the game's", frozen.size());
            }
            if (USE_REGIONS) return fromRegions;
        }
        return live;
    }

    /**
     * What a press at this point lands on over another screen, or null when it is the game's: a promoted node (a menu,
     * a dialog) or anything inside a window. On the document.
     *
     * <ul>
     *   <li>The box tree's own hit test, which tests the top layer first and answers null where a modal blocks, so a
     *       modal over a pinned window swallows clicks aimed past it.</li>
     *   <li>Inside a WINDOW, not under the window layer: the layer is full-size, so asking for it made every click ours.
     *       The taskbar, which this mode does not paint, is the game's.</li>
     *   <li>The COMPOSED parent: a window's content sits in its shadow tree's slot, where the light walk stops.</li>
     * </ul>
     */
    @Nullable
    static UIElement overlayHitTest(UIDocument document, float xPx, float yPx) {
        Box hitBox = document.boxes().hitTest(xPx, yPx, box -> document.focus().isInert(box.node()));
        if (hitBox == null) return null;
        UIElement hit = hitBox.node();
        for (UIElement walk = hit; walk != null; walk = walk.composedParent()) {
            if (document.isPromoted(walk)) return hit;
        }
        Desktop desktop = Desktop.ifPresent(document);
        UIElement layer = desktop == null ? null : desktop.windowLayer();
        for (UIElement walk = hit; walk != null; walk = walk.composedParent()) {
            if (walk instanceof WindowFrame) return hit;
            if (walk == layer) return null;
        }
        return null;
    }
}
