package com.crystalgui.desktop.host;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.ui.box.Box;
import javax.annotation.Nullable;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.dom.UIDocument;

/**
 * <b>The seam a loader talks to when a window it does not own is on screen</b> — M16 §26.4.
 *
 * <h3>What is here, and what is deliberately not</h3>
 *
 * <p>Everything that could be <em>wrong</em> is here: which UI gets a click, which owns the keyboard,
 * when ownership is released. A loader's whole job is to hand over primitives and honour the answer —
 * {@code offerMouse} returns whether the desktop consumed the event, and the loader forwards what it
 * did not.</p>
 *
 * <p><b>The decision is in {@code core/} because the alternative is one implementation per Minecraft
 * version.</b> This has to work on 1.7.10, 1.12.2 and 1.20.1, and those differ enormously in how a
 * loader <em>learns</em> about input — 1.7.10 has no screen input event at all and needs a mixin, while
 * everything from 1.8 on has a cancellable one. They do not differ at all in what the answer should be.
 * Answering it in each loader would give the arbitration three implementations that drift, and the drift
 * would show up as a click that works on one version and not another: the least debuggable bug this
 * feature can produce.</p>
 *
 * <p>No parameter here is a Minecraft type. Raw surface pixels, top-down Y, our own key codes — the same
 * rule {@code CgSystemInput} follows, and the reason this is testable without a game.</p>
 */
public final class ScreenOverlay {

    private final UIDocument window;

    /**
     * Whether a press has landed inside a pinned window more recently than outside one.
     *
     * <p><b>The keyboard has no position</b>, which is what makes it the hard half. Chat has a text
     * field with a caret and a pinned window has a focused element, and both have a legitimate claim —
     * so "who is this key for" cannot be answered geometrically the way a click can. Answering it from
     * what is HOVERED would break typing in chat the moment the pointer drifted, which is the worst
     * available failure because it is intermittent.</p>
     *
     * <p>So: focus follows the click, arbitrated at the boundary between two UIs that know nothing about
     * each other. Ours from the moment a press lands inside a pinned window, theirs again from the
     * moment one lands outside. It is what every OS-level overlay does and the only rule that is stable
     * under a stationary pointer.</p>
     */
    private boolean keyboardIsOurs;

    public ScreenOverlay(UIDocument window) {
        this.window = window;
    }

    /**
     * A foreign screen opened or closed.
     *
     * <p><b>Ownership is released on the way out, and forgetting that is a real bug rather than
     * tidiness.</b> Chat closes on Enter while our window still holds the keyboard; without this the
     * next foreign screen opens with a stale owner, and the first keystroke goes to a window the player
     * has not touched. Same shape as {@code UIInputHandler} forgetting a detached element, one level
     * up.</p>
     */
    public void onForeignScreenChanged(boolean open) {
        if (!open) keyboardIsOurs = false;
    }

    /** Whether a pinned window currently owns the keyboard. @see #keyboardIsOurs */
    public boolean ownsKeyboard() {
        return keyboardIsOurs;
    }

    /**
     * Offers one pointer event.
     *
     * @param xPx      surface pixels from the left
     * @param yPx      surface pixels from the TOP — a loader converts, because window systems disagree
     * @param button   the button, or {@code -1} for a move
     * @param pressed  whether this is a press (meaningless for a move)
     * @param wheel    scroll delta, {@code 0} when this is not a wheel event
     * @return {@code true} if the desktop consumed it and the foreign screen must not see it
     */
    public boolean offerMouse(int xPx, int yPx, int button, boolean pressed, float wheel) {
        // A MOVE IS THE ONE EVENT BOTH SIDES CAN HAVE. Ours has to track the pointer or nothing ever
        // highlights; theirs has to keep working over their own widgets. So a move is always delivered
        // and never consumed.
        boolean isMove = button < 0 && wheel == 0f;

        // AN ACTIVE POINTER CAPTURE OUTRANKS THE HIT TEST, and this is the rule that will be got wrong.
        // A drag that started inside a pinned window must keep receiving moves and the button-up AFTER
        // the pointer has left it -- which is exactly what capture is for, and exactly what a per-event
        // hit test destroys. The common case, not the edge case: every window move ends outside the
        // caption it started on.
        boolean captured = window.input().pointerCaptureTarget() != null;

        if (!isMove && !captured && overlayHitTest(xPx, yPx) == null) {
            // OUTSIDE. Two things have to happen here, and the first version did neither because it
            // returned before reaching them -- its own comment said "the outside case never reaches
            // here", which was true of the code and wrong about what the code needed to do.
            if (pressed) {
                // A PRESS OUTSIDE GIVES THE KEYBOARD BACK. Without this, `keyboardIsOurs` was
                // one-way: click into a pinned window once and every keystroke for the rest of the
                // session went there. On screen that is a chat box you can click, that shows a caret,
                // and that will not accept a single character -- which reads as chat being broken.
                keyboardIsOurs = false;
                // AND THE FOCUS RING GOES WITH IT, or the editor keeps drawing itself focused while
                // somebody else has the keyboard. "Looks focused, is cold" is the exact state
                // WindowFrame.restoreFocus exists to prevent one level down. The window remembers where
                // its focus was, so clicking back in restores it.
                UINode focused = window.focus().focused();
                if (focused != null) window.focus().blurIfFocused(focused);
                // A MENU IS DISMISSED BY A PRESS ANYWHERE, including one that is not ours. Otherwise a
                // dropdown opened in a pinned window survives a click on the chat box and floats there
                // with nothing able to close it: light dismiss only ever sees presses we consumed.
                if (!window.dismiss().autoPopovers().isEmpty()) window.dismiss().lightDismiss(null);
            }
            return false;
        }

        deliver(xPx, yPx, button, pressed, wheel);

        if (isMove) return false;

        // A PRESS INSIDE TAKES THE KEYBOARD.
        if (pressed) keyboardIsOurs = true;
        return true;
    }

    /**
     * What a press at this point would land on, or {@code null} when it belongs to the game.
     *
     * <p><b>On the compositor's side of the seam, where the old engine had it on {@code UIWindow}.</b>
     * Every question it asks is about windows — is the hit promoted into the top layer, is it inside a
     * {@code WindowFrame} — and {@code ui.dom} may not name either. Same inversion {@code Desktop.of}
     * makes for the compositor itself.</p>
     *
     * <p>Built on the box tree's own hit test, which already tests the top layer first and in reverse
     * paint order, and already answers {@code null} for a hit a modal blocks. That last part matters
     * here more than anywhere: a modal dialog over a pinned window must swallow clicks aimed past it
     * rather than let them fall through to Minecraft.</p>
     *
     * <p>The desktop's own chrome is deliberately NOT a hit. The taskbar is still attached in this
     * presentation and nobody is painting it, so a click at the bottom of the screen belongs to the
     * game.</p>
     */
    @Nullable
    public UINode overlayHitTest(float xPx, float yPx) {
        Box hitBox = window.boxes().hitTest(xPx, yPx, box -> window.focus().isInert(box.node()));
        if (hitBox == null) return null;
        UINode hit = hitBox.node();

        // Promoted into the top layer -- a dialog, a menu, a tooltip, the switcher. The promoted node
        // ITSELF is a legitimate hit, so this matches at depth zero.
        for (UINode walk = hit; walk != null; walk = walk.parent()) {
            if (window.isPromoted(walk)) return hit;
        }

        // Or INSIDE A WINDOW -- and "inside a window" is not the same as "somewhere under the window
        // layer", which is what the first version asked and is why it swallowed every click on the
        // screen. The layer is full-size (its box IS the work area) and nothing turns its hit-testing
        // off, so the hit test answers with the LAYER for any point not over a window. Matching that
        // made the whole screen ours: Minecraft's own Game Menu buttons stopped responding, because
        // every press was being consumed before it could be forwarded.
        //
        // Asking for a WindowFrame in the chain is the precise question. It excludes the bare layer, and
        // it excludes the taskbar for free -- which is desktop chrome this presentation does not paint,
        // so a click at the bottom of the screen belongs to the game.
        Desktop desktop = Desktop.ifPresent(window);
        UINode layer = desktop == null ? null : desktop.windowLayer();
        for (UINode walk = hit; walk != null; walk = walk.parent()) {
            if (walk instanceof WindowFrame) return hit;
            if (walk == layer) return null;
        }
        return null;
    }

    /**
     * Offers one key.
     *
     * @return {@code true} if the desktop consumed it and the foreign screen must not see it
     */
    public boolean offerKey(int keyCode, char typed, boolean pressed) {
        if (!keyboardIsOurs) return false;
        return window.input().consumeKeyboardEvent(
                new CgSystemInput.Keyboard.Event(typed, keyCode, pressed, false, System.currentTimeMillis()));
    }

    /**
     * Hands one event to the input handler.
     *
     * <p><b>The button is passed through, never clamped.</b> A move carries {@code -1}, and the first
     * version wrote {@code Math.max(0, button)} — which turned every single move into a left-button
     * event with {@code pressed == false}, i.e. a <b>button RELEASE</b>. So a drag ended on the first
     * pixel of movement and a resize never started: the gesture was being cancelled by the very events
     * that should have driven it. It presented as "dragging does not work in overlay mode" rather than
     * as a coordinate or capture problem, because press and click were both fine.</p>
     *
     * <p>The timestamp follows the same rule {@code CgUiInput.pumpMouse} records: a move must not carry
     * a click time, or the multi-click detail counter drifts and a slow double-click registers as a
     * triple.</p>
     */
    private void deliver(int xPx, int yPx, int button, boolean pressed, float wheel) {
        long millis = button < 0 ? -1L : System.currentTimeMillis();
        window.input().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                xPx, yPx, 0, 0, button, pressed, wheel, millis));
    }
}
