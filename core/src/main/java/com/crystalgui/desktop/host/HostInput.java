package com.crystalgui.desktop.host;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.async.UiSequence;
import com.crystalgui.ui.dom.UIDocument;

import javax.annotation.Nullable;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Where every input event a host delivers enters the engine: the router of plan engine-threaded-ui §2.5.
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
 * <p>A document on the host's thread is handed each event and the answer is what dispatch decided. A document on its
 * own {@link UiSequence} is handed it in lockstep ({@link UiSequence#runNow}), with the same answer. With
 * {@code -Dcrystalgui.ui.async=true} it is posted instead and the answer is "ours"; a key its dispatch then
 * leaves is queued for {@link #pollUnhandledKey}, Chromium's {@code UnhandledKeyboardEvent}, and the host gives it to
 * the game a frame late. Every key goes through the sequence while one is on it, so the game never sees keys out of
 * order.</p>
 *
 * <p>Every method answers false while there is no document: nothing was there to take the event.</p>
 */
public final class HostInput implements CgSystemInput.Mouse, CgSystemInput.Keyboard {

    /**
     * Whether a document on a sequence is handed input by posting rather than in lockstep. Off until recording leaves
     * the render thread: in lockstep the host gets dispatch's own answer, and nothing needs giving back to the game.
     */
    static final String ASYNC = "crystalgui.ui.async";

    private final Supplier<UIDocument> document;
    private final boolean async;
    private final Queue<CgSystemInput.Keyboard.Event> unhandledKeys = new ConcurrentLinkedQueue<>();

    HostInput(Supplier<UIDocument> document) {
        this(document, Boolean.getBoolean(ASYNC));
    }

    HostInput(Supplier<UIDocument> document, boolean async) {
        this.document = document;
        this.async = async;
    }

    @Override
    public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
        UIDocument target = document.get();
        if (target == null) return false;
        UiSequence sequence = target.sequence();
        if (sequence == null) return target.input().consumeMouseEvent(event);
        if (!async) return inLockstep(sequence, () -> target.input().consumeMouseEvent(event));
        sequence.execute(() -> target.input().consumeMouseEvent(event));
        return true;
    }

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        UIDocument target = document.get();
        if (target == null) return false;
        UiSequence sequence = target.sequence();
        if (sequence == null) return target.input().consumeKeyboardEvent(event);
        if (!async) return inLockstep(sequence, () -> target.input().consumeKeyboardEvent(event));
        sequence.execute(() -> {
            if (!target.input().consumeKeyboardEvent(event)) unhandledKeys.add(event);
        });
        return true;
    }

    /** An input method's run in progress; an empty {@code text} ends it. */
    public boolean consumeComposition(String text, int caret) {
        UIDocument target = document.get();
        if (target == null) return false;
        UiSequence sequence = target.sequence();
        if (sequence == null) return target.input().consumeComposition(text, caret);
        if (!async) return inLockstep(sequence, () -> target.input().consumeComposition(text, caret));
        sequence.execute(() -> target.input().consumeComposition(text, caret));
        return true;
    }

    /** Whether a document on a sequence is handed input by posting. */
    boolean isAsync() {
        return async;
    }

    private static boolean inLockstep(UiSequence sequence, BooleanSupplier dispatch) {
        boolean[] taken = new boolean[1];
        sequence.runNow(() -> taken[0] = dispatch.getAsBoolean());
        return taken[0];
    }

    /** The oldest key a document on a sequence dispatched and did not take, or null. On the host's thread. */
    @Nullable
    public CgSystemInput.Keyboard.Event pollUnhandledKey() {
        return unhandledKeys.poll();
    }
}
