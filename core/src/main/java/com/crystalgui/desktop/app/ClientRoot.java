package com.crystalgui.desktop.app;

import javax.annotation.Nullable;

import com.crystalgui.render.Surface;
import com.crystalgui.ui.box.Box;
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
    public static final String SHEET = ".__client__ { position: absolute; left: 0px; top: 0px; will-change: transform; }";

    private static final String CLASS = "__client__";

    @Nullable
    private volatile Surface surface;
    @Nullable
    private UIElement content;
    /** The revision of what is inside it at the last commit checked; the application's thread. */
    private long seenRevision = -1L;
    @Nullable
    private volatile Runnable changed;

    public ClientRoot() {
        addClass(CLASS);
    }

    /** Shows {@code root} here, in place of what was. The application's thread. */
    public void setContent(@Nullable UIElement root) {
        if (content == root) return;
        removeAll();
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
