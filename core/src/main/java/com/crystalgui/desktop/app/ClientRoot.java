package com.crystalgui.desktop.app;

import javax.annotation.Nullable;

import com.crystalgui.render.Surface;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.layout.LayoutMinimum;

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
    private volatile Measure measure;
    private float seenWidth = -1f, seenHeight = -1f;

    public ClientRoot() {
        super(NAME);
        addClass(CLASS);
    }

    /** Hears the size of what a caption client shows. */
    interface Measure {
        void size(float width, float height);
    }

    /**
     * Makes this the client of a window's caption: {@code measure} hears the size of its content, at its natural size,
     * after each commit it changes in, so the frame can give the caption exactly that much room.
     *
     * <pre>{@code
     * ClientRoot bar = new ClientRoot();
     * bar.setContent(menuBar);
     * bar.captionOf((w, h) -> portal.setSize(w, h));
     * }</pre>
     */
    void captionOf(Measure measure) {
        this.measure = measure;
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

    private volatile float minimumWidth, minimumHeight;

    /**
     * The smallest its window's client may be, as the content asks for it ({@link LayoutMinimum}): a toolbar and a status
     * bar keep their height, and a band that may give way says how little it needs. Worked out on the application's
     * thread after each commit it changed in, so the shell's thread may read it. Zero before the first commit and for a
     * caption.
     */
    float minimum(boolean vertical) {
        return vertical ? minimumHeight : minimumWidth;
    }

    /** The application's thread, after each commit of its document. */
    void committed() {
        Measure sized = measure;
        if (sized != null && content != null) {
            Box bar = content.box();
            float w = bar == null ? 0f : bar.width(), h = bar == null ? 0f : bar.height();
            if (w != seenWidth || h != seenHeight) {
                seenWidth = w;
                seenHeight = h;
                sized.size(w, h);
            }
        }
        Box box = box();
        long revision = box == null ? -1L : box.innerRevision();
        if (revision == seenRevision) return;
        seenRevision = revision;
        if (content != null && sized == null) {
            minimumWidth = LayoutMinimum.of(content, false);
            minimumHeight = LayoutMinimum.of(content, true);
        }
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

    private volatile int reserveWidth, reserveHeight;

    /** The shell's thread: the desktop's size in device pixels, which no window outgrows. */
    void reserve(int width, int height) {
        reserveWidth = width;
        reserveHeight = height;
    }

    @Override
    public int reserveWidth() {
        return reserveWidth;
    }

    @Override
    public int reserveHeight() {
        return reserveHeight;
    }

    /** Always: the shell shows nothing but this picture, so glass inside the client must not take it off the surface. */
    @Override
    public boolean wantsPicture() {
        return true;
    }
}
