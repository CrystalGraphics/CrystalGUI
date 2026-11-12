package com.crystalgui.desktop.app;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.data.ReadOnlyVec2f;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.render.Surface;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.KeyboardEvent;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.input.FocusPolicy;
import org.joml.Vector2f;

/**
 * Where a window's frame, in the shell's document, shows its client, in the application's: it draws the client's
 * picture, tells the client how much room it has, and hands the client the input that lands on it.
 *
 * <pre>{@code
 * ClientPortal portal = new ClientPortal(appDocument, client);   // by AppWindows
 * frame.setContent(portal);                                      // the frame never sees the client's nodes
 * }</pre>
 *
 * <ul>
 *   <li>The shell's thread owns it; the client's thread owns the client. They meet in posts: a size one way, a
 *       "drawn again" the other.</li>
 *   <li>Pointer and keys are forwarded as the platform's events in the client's pixels, until the screen routes by
 *       surface.</li>
 * </ul>
 */
public final class ClientPortal extends UIElement {

    /** What the sheets address it by: it never rings, as a pane does not. */
    public static final Name NAME = Name.of("clientportal");

    private final AppDocument appDocument;
    private final ClientRoot client;
    private final boolean caption;
    private float sentWidth = -1f, sentHeight = -1f;

    ClientPortal(AppDocument appDocument, ClientRoot client) {
        this(appDocument, client, false);
    }

    /**
     * A portal for a window's caption when {@code caption}: as big as the client's content, which the client reports, so the caption's free space stays the window's drag region.
     */
    ClientPortal(AppDocument appDocument, ClientRoot client, boolean caption) {
        super(NAME);
        this.appDocument = appDocument;
        this.client = client;
        this.caption = caption;
        if (caption) {
            layout(l -> l.width(0f).height(0f));
            client.captionOf((width, height) -> {
                UIDocument shell = document();
                if (shell != null) shell.execute(() -> layout(l -> l.width(width).height(height)));
            });
        } else {
            layout(l -> l.widthPercent(100f).height(0f).flexGrow(1f));
        }
        setFocusPolicy(FocusPolicy.CLICK_NOT_TABBABLE);
        // The first window of an application is the one its popups belong to, until another takes a press.
        if (appDocument.active() == null) appDocument.activate(this);
        client.onChanged(() -> {
            UIDocument shell = document();
            if (shell != null) shell.execute(this::repaint);
        });
        onMouseMove.attachListener((element, event) -> pointer(event.getPosition(), -1, false, 0f), false, true);
        // A PRESS CAPTURES THE POINTER, so a drag that leaves the window keeps its moves and its release, as in any window.
        onMouseDown.attachListener((element, event) -> {
            pointer(event.getPosition(), event.getButtonId(), true, 0f);
            UIDocument shell = document();
            if (shell != null) shell.input().setPointerCapture(this);
        }, false, true);
        onMouseUp.attachListener((element, event) -> {
            pointer(event.getPosition(), event.getButtonId(), false, 0f);
            UIDocument shell = document();
            if (shell != null && shell.input().pointerCaptureTarget() == this) shell.input().releasePointerCapture();
        }, false, true);
        onMouseScroll.attachListener((element, event) -> pointer(event.getPosition(), -1, false, event.getScroll()),
                false, true);
        onMouseLeave.attachListener((element, event) -> pointer(event.getPosition(), -1, false, 0f), false, true);
        onKeyDown.attachListener((element, event) -> key(event, true), false, true);
        onKeyUp.attachListener((element, event) -> key(event, false), false, true);
    }

    /** Whether this shows a window's caption rather than its content. */
    boolean caption() {
        return caption;
    }

    /** Tells the application's document how large this window's client area is, once it has one. */
    void reportSize() {
        if (sentWidth > 0f) appDocument.sized(this, sentWidth, sentHeight);
    }

    /** The document the client lives in. */
    public AppDocument application() {
        return appDocument;
    }

    /** The client this shows. */
    public ClientRoot client() {
        return client;
    }

    @Override
    public void paintContent(CgUiPaintContext ctx, Box box) {
        float width = box.width(), height = box.height();
        if (width != sentWidth || height != sentHeight) {
            sentWidth = width;
            sentHeight = height;
            appDocument.sized(this, width, height);
            appDocument.execute(() -> client.setClientSize(width, height));
        }
        Surface picture = client.picture();
        if (picture != null) picture.drawPicture(ctx, 0f, 0f, picture.pictureWidth(), picture.pictureHeight());
    }

    private void pointer(ReadOnlyVec2f at, int button, boolean pressed, float wheel) {
        UIDocument shell = document();
        if (shell == null) return;
        if (pressed) appDocument.activate(this);
        // A DRAG THAT STARTED HERE AND IS NOW OVER ANOTHER WINDOW OF THE SAME APPLICATION goes to that window's client: a tab
        // dropped from one window onto the other lands where the pointer is, not where it would be in this one.
        ClientPortal target = this;
        if (shell.input().pointerCaptureTarget() == this) {
            ClientPortal under = appDocument.portalAt(shell, at.x(), at.y());
            if (under != null) target = under;
        }
        Vector2f local = target.toLocal(at.x(), at.y());
        float scale = shell.boxes().uiScale();
        appDocument.aim(target.client);
        appDocument.mouse(new CgSystemInput.Mouse.Event(Math.round(local.x * scale), Math.round(local.y * scale), 0, 0,
                button, pressed, wheel, System.currentTimeMillis()));
    }

    /**
     * Hands a key to the application, and keeps the shell from acting on it as well: Tab moves the application's focus,
     * not the shell's. What the application leaves goes back to the host with the desktop's own unhandled keys.
     */
    private void key(KeyboardEvent event, boolean pressed) {
        event.stopPropagation();
        event.preventDefault();
        appDocument.key(new CgSystemInput.Keyboard.Event(event.getCharacter(), event.getKeyCode(), pressed,
                event.isRepeat(), event.getMillis()));
    }
}
