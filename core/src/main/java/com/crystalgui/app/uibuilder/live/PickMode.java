package com.crystalgui.app.uibuilder.live;

import javax.annotation.Nullable;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.service.InputMode;

/**
 * Turns the next click anywhere in a window into a selection — DevTools' element picker.
 *
 * <pre>{@code
 * PickMode.start(document);        // Ctrl+Shift+C
 * }</pre>
 *
 * <p>A mode rather than a listener, because it has to come <b>before the tree</b>: every widget under the
 * pointer is live, so a click meant to select a button would otherwise press it, and a click on a text
 * field would focus it. An {@link InputMode} is asked first and says it consumed, which is the whole
 * reason the engine has a mode stack.</p>
 *
 * <p><b>It honours {@code hit-test: false}</b>, as DevTools' picker honours {@code pointer-events: none}.
 * Reaching through it sounds more useful and is not: an application's unhittable layers are mostly
 * full-window pictures that paint nothing — {@code RegionDropOverlay} covers the whole workbench — so a
 * picker that reaches through them picks the same invisible sheet wherever you click, and the one thing
 * you can inspect is the thing you never wanted. What such a layer hides is reachable from the tree.</p>
 */
public final class PickMode implements InputMode {

    /** Fires with each element picked. The subject is updated first. */
    public final Signal.Value<UIElement> onPicked = new Signal.Value<>();

    /** Fires when the mode ends, however it ended. */
    public final Signal.Action onEnded = new Signal.Action();

    private final UIDocument document;

    private final LiveSubject subject;

    @Nullable
    private UIElement hovered;

    private PickMode(UIDocument document) {
        this.document = document;
        this.subject = LiveSubject.on(document);
    }

    /**
     * Pushes a picker over {@code document}. Returns the mode, or null when there is no window to pick in.
     */
    @Nullable
    public static PickMode start(@Nullable UIDocument document) {
        if (document == null) return null;
        PickMode mode = new PickMode(document);
        document.input().pushMode(mode);
        return mode;
    }

    /** What the pointer is over, or null. What an overlay would outline. */
    @Nullable
    public UIElement hovered() {
        return hovered;
    }

    @Override
    public String name() {
        return "uibuilder.pick";
    }

    @Override
    public boolean pointerMoved(float x, float y) {
        hovered = elementAt(x, y);
        // Tracking only. Consuming the move would freeze every :hover in the window, and a picker that
        // makes the screen it is inspecting behave differently is inspecting something else.
        return false;
    }

    @Override
    public boolean pointerButton(int button, boolean pressed, float x, float y) {
        if (button != CgMouseCodes.LEFT_BUTTON) return false;
        // BOTH HALVES CONSUMED. Taking the press and leaving the release would deliver a release to a
        // widget that never saw its press -- which is how a button ends up latched down.
        if (!pressed) return true;

        UIElement picked = elementAt(x, y);
        if (picked != null) {
            subject.pick(picked);
            onPicked.emit(picked);
        }
        end();
        return true;
    }

    @Override
    public boolean keyPressed(int key, int modifiers, boolean repeat) {
        if (key != CgKeyCodes.KEY_ESCAPE) return false;
        // ENDS THE MODE AND CONSUMES, but only while it is up. An Escape that arrives once the picker has
        // gone is not the picker's to eat -- and a mode that answered "consumed" regardless would quietly
        // disable every dialog's own Escape in the window for as long as anything held a reference to it.
        if (!document.input().hasMode(this)) return false;
        end();
        return true;
    }

    /** Takes the picker off the stack. Safe to call twice. */
    public void end() {
        if (!document.input().hasMode(this)) return;
        document.input().popMode(this);
        hovered = null;
        onEnded.emit();
    }

    @Nullable
    private UIElement elementAt(float x, float y) {
        // Nothing skipped: inertness is the predicate's business, and a picker must reach INTO a
        // modal-blocked region -- looking at what a dialog has disabled is half of why you are looking.
        Box box = document.boxes().hitTest(x, y, ignored -> false);
        return box == null ? null : box.node();
    }
}
