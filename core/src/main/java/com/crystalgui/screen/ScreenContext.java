package com.crystalgui.screen;

import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;

import java.util.ArrayList;
import java.util.List;

/**
 * What a {@link ScreenKind}'s builder is given: the screen's document and its root, and the way to close it.
 *
 * <pre>{@code
 * ScreenKind.of("mymod:furnace", screen -> {
 *     screen.root().append(new FurnaceView());
 *     screen.onClosed(() -> model.save());      // on the document, before it is dropped
 * });
 * }</pre>
 *
 * <ul>
 *   <li>The builder and every callback run on the screen's own thread; {@link #close} may be called from any.</li>
 *   <li>The root fills the window. Size the content in the sheet, not from the root's pixels.</li>
 * </ul>
 */
public final class ScreenContext {

    private final ScreenKind kind;
    private final UIDocument document;
    private final UIElement root;
    private final GameScreen screen;
    private final List<Runnable> closed = new ArrayList<>();

    ScreenContext(GameScreen screen, ScreenKind kind, UIDocument document, UIElement root) {
        this.screen = screen;
        this.kind = kind;
        this.document = document;
        this.root = root;
    }

    public ScreenKind kind() {
        return kind;
    }

    public UIDocument document() {
        return document;
    }

    /** The element that fills the window; the screen's content goes in it. */
    public UIElement root() {
        return root;
    }

    /** Closes the screen at the host's next tick. Any thread; nothing if it is already closing. */
    public void close() {
        screen.requestClose();
    }

    /** Runs {@code then} on the document when the screen closes, before the document is dropped. */
    public void onClosed(Runnable then) {
        closed.add(then);
    }

    /** On the document. */
    void runClosed() {
        for (Runnable then : closed) then.run();
        closed.clear();
    }
}
