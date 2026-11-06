package com.crystalgui.desktop.host;

import com.crystalgraphics.platform.input.CgInputListener;
import com.crystalgraphics.platform.input.CgInputStage;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.window.DesktopPresentation;
import com.crystalgui.ui.input.HostPointer;

/**
 * The pinned windows' input: a listener on CrystalGraphics' {@code SCREEN} input stage, which every host fires for
 * the screen in front before it acts. What the desktop takes is consumed, so the screen never sees it.
 *
 * <p>Events arrive in the engine's vocabulary (raw pixels from the top left, {@code CgKeyCodes}, a wheel notch
 * positive rolled down), so nothing converts here.</p>
 */
final class HostInputListener implements CgInputListener {

    private final HostSession.PaintHost host;

    HostInputListener(HostSession.PaintHost host) {
        this.host = host;
    }

    /** Only in overlay mode: a host that drains a foreign screen's input does it for nothing else. */
    @Override
    public boolean wants() {
        return HostSession.isInstalled()
                && HostSession.session().presentation(host) == DesktopPresentation.OVERLAY;
    }

    @Override
    public boolean mouseMoved(CgSystemInput.Mouse.Event e) {
        offer(e, HostPointer.NO_BUTTON, false, 0f);
        return false;
    }

    @Override
    public boolean mouseButton(CgSystemInput.Mouse.Event e) {
        return offer(e, e.button(), e.state(), 0f);
    }

    @Override
    public boolean scrolled(CgSystemInput.Mouse.Event e) {
        return offer(e, HostPointer.NO_BUTTON, false, e.wheelDelta());
    }

    @Override
    public boolean key(CgSystemInput.Keyboard.Event e) {
        return HostSession.isInstalled() && HostSession.session().offerKey(e.key(), (char) 0, e.pressed());
    }

    @Override
    public boolean character(CgSystemInput.Keyboard.Event e) {
        return HostSession.isInstalled() && HostSession.session().offerKey(0, e.character(), true);
    }

    private boolean offer(CgSystemInput.Mouse.Event e, int button, boolean pressed, float wheel) {
        return HostSession.isInstalled() && HostSession.session().offerMouse(host, CgInputStage.pointerGrabbed(),
                e.x(), e.y(), button, pressed, wheel);
    }
}
