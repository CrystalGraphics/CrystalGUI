package com.crystalgui.desktop.host;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.ui.dom.UIDocument;

import java.util.function.Supplier;

/**
 * Where every input event a host delivers enters the engine: the router of plan engine-threaded-ui §2.5.
 *
 * <pre>{@code
 * HostInput input = HostSession.session().input();
 * boolean ours = input.consumeKeyboardEvent(new CgSystemInput.Keyboard.Event(typed, key, true, false, millis));
 * if (!ours) super.keyPressed(...);   // the game acts on what the desktop left
 * }</pre>
 *
 * <p>Today it hands each event to the session's document and returns what dispatch decided. Once documents run
 * on their own sequences this is where an event is queued and the host's synchronous question answered, so a
 * host calls this and never {@code document.input()}.</p>
 *
 * <p>Every method answers false while there is no document: nothing was there to take the event.</p>
 */
public final class HostInput implements CgSystemInput.Mouse, CgSystemInput.Keyboard {

    private final Supplier<UIDocument> document;

    HostInput(Supplier<UIDocument> document) {
        this.document = document;
    }

    @Override
    public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
        UIDocument target = document.get();
        return target != null && target.input().consumeMouseEvent(event);
    }

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        UIDocument target = document.get();
        return target != null && target.input().consumeKeyboardEvent(event);
    }

    /** An input method's run in progress; an empty {@code text} ends it. */
    public boolean consumeComposition(String text, int caret) {
        UIDocument target = document.get();
        return target != null && target.input().consumeComposition(text, caret);
    }
}
