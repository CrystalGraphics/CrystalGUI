package com.crystalgui.desktop.host;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.ui.dom.DocumentDriver;
import com.crystalgui.ui.dom.UIDocument;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * Where every input event a host delivers enters the engine, for a document that may not exist yet.
 *
 * <pre>{@code
 * HostInput input = HostSession.session().input();
 * boolean ours = input.consumeKeyboardEvent(new CgSystemInput.Keyboard.Event(typed, key, true, false, millis));
 * if (!ours) super.keyPressed(...);   // the game acts on what the desktop left
 *
 * // once a host frame, on the render thread: what the document dispatched and left
 * for (CgSystemInput.Keyboard.Event key; (key = input.pollUnhandledKey()) != null; ) reinject(key);
 * }</pre>
 *
 * <p>A document's {@link DocumentDriver} decides how an event reaches it: dispatched here, in lockstep, or posted with
 * the answer "ours" and a key the document leaves handed back through {@link #pollUnhandledKey}. Every method answers
 * false while there is no document: nothing was there to take the event.</p>
 */
public final class HostInput implements CgSystemInput.Mouse, CgSystemInput.Keyboard {

    private final Supplier<UIDocument> document;

    HostInput(Supplier<UIDocument> document) {
        this.document = document;
    }

    @Override
    public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
        UIDocument target = document.get();
        if (target == null) return false;
        DocumentDriver<?> driver = target.driver();
        return driver != null ? driver.consumeMouseEvent(event) : target.input().consumeMouseEvent(event);
    }

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        UIDocument target = document.get();
        if (target == null) return false;
        DocumentDriver<?> driver = target.driver();
        return driver != null ? driver.consumeKeyboardEvent(event) : target.input().consumeKeyboardEvent(event);
    }

    /** An input method's run in progress; an empty {@code text} ends it. */
    public boolean consumeComposition(String text, int caret) {
        UIDocument target = document.get();
        if (target == null) return false;
        DocumentDriver<?> driver = target.driver();
        return driver != null ? driver.consumeComposition(text, caret) : target.input().consumeComposition(text, caret);
    }

    /** The oldest key the document dispatched and did not take, or null. On the host's thread. */
    @Nullable
    public CgSystemInput.Keyboard.Event pollUnhandledKey() {
        UIDocument target = document.get();
        DocumentDriver<?> driver = target == null ? null : target.driver();
        return driver == null ? null : driver.pollUnhandledKey();
    }
}
