package com.crystalgui.desktop.app;

import java.util.function.DoubleConsumer;

import javax.annotation.Nullable;

import com.crystalgui.render.Surface;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;

/**
 * A window's client area in its application's document: the root of what the application shows in that window, drawn
 * into a surface of its own that the window's frame, in the shell's document, shows.
 *
 * <pre>{@code
 * ClientRoot client = new ClientRoot();           // on the application's thread
 * client.setContent(new PlannerView());          // the application's own tree
 * appDocument.document().append(client);
 * }</pre>
 *
 * <p>It sits at the corner of the document, absolute, at the size its frame gives it, so what a surface holds is the
 * client's own picture and two windows of one application share no pixels. Applications never build one:
 * {@link AppWindows#open} does.</p>
 */
public final class ClientRoot extends UIElement implements Surface.Owner {

    /** What puts it in a surface and at the document's corner; installed with the document. */
    public static final String SHEET = ".__client__ { position: absolute; left: 0px; top: 0px; will-change: transform; }"
            + " .__client-front__ { z-index: 1; }"
            + " .__client-caption__ { flex-direction: row; align-items: center; }";

    /** What the sheets address it by. */
    public static final Name NAME = Name.of("clientroot");

    private static final String CLASS = "__client__";
    private static final String FRONT = "__client-front__";
    private static final String CAPTION = "__client-caption__";

    @Nullable
    private volatile Surface surface;
    @Nullable
    private UIElement content;
    /** The revision of what is inside it at the last commit checked; the application's thread. */
    private long seenRevision = -1L;
    @Nullable
    private volatile Runnable changed;
    @Nullable
    private volatile DoubleConsumer contentWidth;
    private float seenWidth = -1f;

    public ClientRoot() {
        super(NAME);
        addClass(CLASS);
    }

    /**
     * Makes this the client of a window's caption: its content is a row at its natural width, and {@code width} hears
     * that width after each commit it changes in, so the frame can give the caption exactly that much room.
     *
     * <pre>{@code
     * ClientRoot bar = new ClientRoot();
     * bar.setContent(menuBar);
     * bar.captionOf(w -> portal.setWidth(w));
     * }</pre>
     */
    void captionOf(DoubleConsumer width) {
        this.contentWidth = width;
    }

    /** Lays its content out as a caption's bar: a row at its natural width. The application's thread. */
    void asCaption() {
        addClass(CAPTION);
    }

    /** Shows {@code root} here, in place of what was. The application's thread. */
    public void setContent(@Nullable UIElement root) {
        if (content == root) return;
        // ONLY WHAT IT SHOWED: the floats a workbench put over it are not its content.
        if (content != null) remove(content);
        content = root;
        if (root != null) append(root);
    }

    /** What it shows now. */
    @Nullable
    public UIElement content() {
        return content;
    }

    /** Gives it the size its frame's client slot has, in logical pixels. The application's thread. */
    public void setClientSize(float width, float height) {
        layout(l -> l.width(width).height(height));
    }

    /**
     * Puts it above the application's other clients, which share its corner: the pointer's events hit the one on top.
     * The application's thread.
     */
    void front(boolean on) {
        toggleClass(FRONT, on);
    }

    /** Its picture as it last painted, or null before the first paint. Any thread: a frame draws it. */
    @Nullable
    public Surface picture() {
        Surface held = surface;
        return held != null && held.hasPicture() ? held : null;
    }

    /** What runs, on the application's thread, after a commit in which something inside it changed: the frame showing it draws again. */
    public void onChanged(@Nullable Runnable then) {
        this.changed = then;
    }

    /** The application's thread, after each commit of its document. */
    void committed() {
        DoubleConsumer width = contentWidth;
        if (width != null && content != null) {
            Box bar = content.box();
            float now = bar == null ? 0f : bar.width();
            if (now != seenWidth) {
                seenWidth = now;
                width.accept(now);
            }
        }
        Box box = box();
        long revision = box == null ? -1L : box.innerRevision();
        if (revision == seenRevision) return;
        seenRevision = revision;
        Runnable then = changed;
        if (then != null) then.run();
    }

    @Override
    public void surface(Surface surface) {
        this.surface = surface;
    }

    @Override
    public boolean keepsSurface() {
        return true;
    }
}
