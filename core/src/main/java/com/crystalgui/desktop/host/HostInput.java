package com.crystalgui.desktop.host;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.viewport.CgUiViewport;

import javax.annotation.Nullable;
import java.util.function.Predicate;
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
 * <p>The document's {@link CgUiViewport} decides how an event reaches it: dispatched here, in lockstep, or posted with
 * the answer "ours" and a key the document leaves handed back through {@link #pollUnhandledKey}. Every method answers
 * false while there is no viewport: nothing was there to take the event.</p>
 */
public final class HostInput implements CgSystemInput.Mouse, CgSystemInput.Keyboard {

    private final Supplier<CgUiViewport> viewport;
    /** Offered each key before the document; true takes it. */
    private final Predicate<CgSystemInput.Keyboard.Event> first;

    HostInput(Supplier<CgUiViewport> viewport) {
        this(viewport, key -> false);
    }

    HostInput(Supplier<CgUiViewport> viewport, Predicate<CgSystemInput.Keyboard.Event> first) {
        this.viewport = viewport;
        this.first = first;
    }

    @Override
    public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
        CgUiViewport target = viewport.get();
        return target != null && target.consumeMouseEvent(event);
    }

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        CgUiViewport target = viewport.get();
        if (target == null) return false;
        if (first.test(event)) return true;
        return target.consumeKeyboardEvent(event);
    }

    /** An input method's run in progress; an empty {@code text} ends it. */
    public boolean consumeComposition(String text, int caret) {
        CgUiViewport target = viewport.get();
        return target != null && target.consumeComposition(text, caret);
    }

    /** The oldest key the document dispatched and did not take, or null. On the host's thread. */
    @Nullable
    public CgSystemInput.Keyboard.Event pollUnhandledKey() {
        CgUiViewport target = viewport.get();
        return target == null ? null : target.pollUnhandledKey();
    }
}
