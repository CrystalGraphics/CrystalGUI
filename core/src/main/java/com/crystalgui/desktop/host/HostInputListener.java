package com.crystalgui.desktop.host;

import com.crystalgraphics.platform.input.CgInput;
import com.crystalgraphics.platform.input.CgInputListener;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.window.DesktopPresentation;
import com.crystalgui.ui.input.HostPointer;

/**
 * The desktop's input: a listener on CrystalGraphics' {@code SCREEN} input stage, which every host fires for the screen
 * in front before it acts. What the desktop takes is consumed, so the screen never sees it.
 *
 * <ul>
 *   <li><b>Our own screen</b> ({@link DesktopPresentation#DESKTOP}): every event goes to the document. Pointer events
 *       are always consumed, since the desktop covers the screen; a key only when the document took it, so an Escape
 *       nothing wanted reaches the screen, which closes on it.</li>
 *   <li><b>Another screen</b> ({@link DesktopPresentation#OVERLAY}): the pinned windows take what lands on them.</li>
 * </ul>
 *
 * <p>Events arrive in the engine's vocabulary (raw pixels from the top left, {@code CgKeyCodes}, a wheel notch
 * positive rolled down), so nothing converts here.</p>
 */
final class HostInputListener implements CgInputListener {

    private final HostSession.PaintHost host;

    HostInputListener(HostSession.PaintHost host) {
        this.host = host;
    }

    @Override
    public boolean wants() {
        return presentation() != DesktopPresentation.NONE;
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
        return presentation() == DesktopPresentation.DESKTOP && HostSession.session().input().consumeComposition(text, caret);
    }

    private DesktopPresentation presentation() {
        if (!HostSession.isInstalled()) return DesktopPresentation.NONE;
        DesktopPresentation shown = HostSession.session().presentation(host);
        return shown == DesktopPresentation.DESKTOP || shown == DesktopPresentation.OVERLAY
                ? shown : DesktopPresentation.NONE;
    }

    private boolean pointer(CgSystemInput.Mouse.Event e, int button, boolean pressed, float wheel) {
        switch (presentation()) {
            case DESKTOP:
                HostSession.session().input().consumeMouseEvent(
                        HostPointer.of(e.x(), e.y(), e.dx(), e.dy(), button, pressed, wheel, e.millis()));
                return true;
            case OVERLAY:
                return HostSession.session().offerMouse(host, CgInput.pointerGrabbed(), e.x(), e.y(), button,
                        pressed, wheel);
            default:
                return false;
        }
    }

    private boolean keyboard(CgSystemInput.Keyboard.Event e) {
        switch (presentation()) {
            case DESKTOP:
                return HostSession.session().input().consumeKeyboardEvent(e);
            case OVERLAY:
                return HostSession.session().offerKey(e.key(), e.character(), e.pressed());
            default:
                return false;
        }
    }
}
