package com.crystalgui.desktop.app;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.data.ReadOnlyVec2f;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.render.Surface;
import com.crystalgui.ui.box.Box;
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

    private final AppDocument appDocument;
    private final ClientRoot client;
    private float sentWidth = -1f, sentHeight = -1f;

    ClientPortal(AppDocument appDocument, ClientRoot client) {
        this.appDocument = appDocument;
        this.client = client;
        layout(l -> l.widthPercent(100f).height(0f).flexGrow(1f));
        setFocusPolicy(FocusPolicy.CLICK_NOT_TABBABLE);
        client.onChanged(() -> {
            UIDocument shell = document();
            if (shell != null) shell.execute(this::repaint);
        });
        onMouseMove.attachListener((element, event) -> pointer(event.getPosition(), -1, false, 0f), false, true);
        onMouseDown.attachListener((element, event) -> pointer(event.getPosition(), event.getButtonId(), true, 0f),
                false, true);
        onMouseUp.attachListener((element, event) -> pointer(event.getPosition(), event.getButtonId(), false, 0f),
                false, true);
        onMouseScroll.attachListener((element, event) -> pointer(event.getPosition(), -1, false, event.getScroll()),
                false, true);
        onMouseLeave.attachListener((element, event) -> pointer(event.getPosition(), -1, false, 0f), false, true);
        onKeyDown.attachListener((element, event) -> key(event, true), false, true);
        onKeyUp.attachListener((element, event) -> key(event, false), false, true);
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
            appDocument.execute(() -> client.setClientSize(width, height));
        }
        Surface picture = client.picture();
        if (picture != null) picture.drawPicture(ctx, 0f, 0f, picture.pictureWidth(), picture.pictureHeight());
    }

    private void pointer(ReadOnlyVec2f at, int button, boolean pressed, float wheel) {
        UIDocument shell = document();
        if (shell == null) return;
        Vector2f local = toLocal(at.x(), at.y());
        float scale = shell.boxes().uiScale();
        appDocument.mouse(new CgSystemInput.Mouse.Event(Math.round(local.x * scale), Math.round(local.y * scale), 0, 0,
                button, pressed, wheel, System.currentTimeMillis()));
    }

    private void key(KeyboardEvent event, boolean pressed) {
        appDocument.key(new CgSystemInput.Keyboard.Event(event.getCharacter(), event.getKeyCode(), pressed,
                event.isRepeat(), event.getMillis()));
    }
}
