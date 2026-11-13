package com.crystalgui.screen;

import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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
    private final List<Runnable> hostFrame = new CopyOnWriteArrayList<>();

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

    /**
     * Runs {@code work} on the host's thread, once every host frame while the screen is up: where the game may be
     * asked, since the document never asks it. Hand what it finds to the document with {@code document().execute}.
     *
     * <pre>{@code
     * screen.onHostFrame(() -> {
     *     Menu now = CgContainers.menu();                        // the game's client thread
     *     screen.document().execute(() -> show(now));            // the document's
     * });
     * }</pre>
     *
     * Any thread; a throw in it is the screen's fault. It is dropped when the screen is.
     */
    public void onHostFrame(Runnable work) {
        hostFrame.add(work);
    }

    /** Render thread: every {@link #onHostFrame} work. */
    void runHostFrame() {
        for (Runnable work : hostFrame) work.run();
    }

    void dropHostFrame() {
        hostFrame.clear();
    }

    /** On the document. */
    void runClosed() {
        for (Runnable then : closed) then.run();
        closed.clear();
    }
}
