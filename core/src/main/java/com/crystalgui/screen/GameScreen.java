package com.crystalgui.screen;

import com.crystalgraphics.platform.service.CgScreens;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.settings.Preferences;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.desktop.dialog.FailureWindow;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.viewport.CgUiViewport;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.text.UIText;

import javax.annotation.Nullable;

/**
 * One {@link ScreenKind} on screen: its own document and viewport, filling the window, made when the kind is shown and
 * dropped when it closes. The host's screen holds {@link #owner()}; {@link CgUiScreen} frames, routes input to and ticks
 * it.
 *
 * <p>A fault in its build, its frame or a task sent to it replaces its content with an error panel the player closes;
 * the game carries on.</p>
 */
final class GameScreen {

    /** The class on its root, which the user-agent sheet sizes and darkens. */
    static final String ROOT_CLASS = "__game-screen__";
    static final String FAULT_CLASS = "__screen-fault__";
    private static final String FAULT_HOST_CLASS = "__screen-fault-host__";
    private static final String FAULT_TITLE_CLASS = "__screen-fault-title__";
    private static final String FAULT_DETAIL_CLASS = "__screen-fault-detail__";

    private final ScreenKind kind;
    private final UIDocument document;
    private final UIElement root = new UIElement();
    private final CgUiViewport viewport;
    private final ScreenContext context;
    private final Connection scale;
    private final CgUiViewport.Content content;

    private volatile boolean closeRequested;
    @Nullable
    private volatile Throwable fault;

    private final CgScreens.Owner owner = new CgScreens.Owner() {
        @Override
        public void shown() {
            CgUiScreen.gameScreenShown(GameScreen.this);
        }

        @Override
        public void hidden() {
            CgUiScreen.gameScreenHidden(GameScreen.this);
        }

        @Override
        public boolean pausesGame() {
            return kind.pausesGame();
        }

        /** A screen with a background covers the window, so the HUD drawn over screens (1.21.6 to 26.0) would land on it. */
        @Override
        public boolean hidesHud() {
            return kind.background() != ScreenKind.Background.NONE;
        }

        @Override
        public boolean textInputArea(float[] out) {
            float[] area = viewport.readout(CgUiViewport.TEXT_INPUT_AREA);
            if (area == null) return false;
            System.arraycopy(area, 0, out, 0, 4);
            return true;
        }
    };

    /** Builds the document and sends the kind's builder to it. Render thread. */
    GameScreen(ScreenKind kind) {
        this.kind = kind;
        document = CgUiScreen.newDocument();
        root.addClass(ROOT_CLASS);
        root.addClass("__background-" + kind.background().name().toLowerCase(java.util.Locale.ROOT) + "__");
        viewport = CgUiViewport.open(document, kind.threading() == ApplicationKind.Threading.INLINE
                ? CgUiViewport.Threading.INLINE : CgUiViewport.Threading.fromFlags(), "screen:" + kind.id());
        context = new ScreenContext(this, kind, document, root);
        scale = kind.uiScale() > 0f ? null : Preferences.PUBLISHED.changes().subscribe(
                () -> document.execute(() -> document.boxes().setUiScale(Preferences.current().uiScale())));
        if (kind.uiScale() > 0f) document.execute(() -> document.boxes().setUiScale(kind.uiScale()));
        viewport.onFault(this::fault);
        content = new CgUiViewport.Content() {
            @Override
            public boolean frame(float deltaSeconds, int width, int height) {
                float uiScale = document.boxes().uiScale();
                document.frame(deltaSeconds, width / uiScale, height / uiScale);
                return true;
            }

            @Override
            public void paint(CgUiPaintContext ctx) {
                document.paint(ctx);
            }

            @Override
            public void failed(Throwable cause) {
                fault(cause);
            }
        };
        viewport.execute(this::build);
    }

    /** On the document. */
    private void build() {
        document.append(root);
        try {
            kind.builder().build(context);
        } catch (RuntimeException | LinkageError failed) {
            fault(failed);
        }
    }

    ScreenKind kind() {
        return kind;
    }

    CgUiViewport viewport() {
        return viewport;
    }

    CgUiViewport.Content content() {
        return content;
    }

    CgScreens.Owner owner() {
        return owner;
    }

    /** Asked from any thread; the screen closes at the host's next tick. */
    void requestClose() {
        closeRequested = true;
    }

    /** Whether a close was asked for, once. Render thread. */
    boolean takeCloseRequest() {
        boolean asked = closeRequested;
        closeRequested = false;
        return asked;
    }

    @Nullable
    Throwable fault() {
        return fault;
    }

    /** A fault anywhere in it: logged once, the content replaced by the error panel. Any thread. */
    void fault(Throwable cause) {
        if (fault != null) return;
        fault = cause;
        CrystalGuiCore.LOGGER.error("[cgui] the screen '{}' stopped working", kind.id(), cause);
        document.execute(() -> {
            try {
                showFault(cause);
            } catch (RuntimeException | LinkageError panelFailed) {
                // The panel is ours and this is the last resort: the player is not left on a blank screen.
                CrystalGuiCore.LOGGER.error("[cgui] the error panel of '{}' failed too; closing it", kind.id(), panelFailed);
                requestClose();
            }
        });
    }

    /** On the document. */
    private void showFault(Throwable cause) {
        root.removeAll();
        UIElement host = new UIElement();
        host.addClass(FAULT_HOST_CLASS);
        UIElement panel = new UIElement();
        panel.addClass(FAULT_CLASS);
        UIText title = new UIText(kind.id() + " stopped working");
        title.addClass(FAULT_TITLE_CLASS);
        UIText why = new UIText(FailureWindow.detail(cause));
        why.addClass(FAULT_DETAIL_CLASS);
        Button close = new Button("Close");
        close.onPressed.connect(this::requestClose);
        panel.append(title, why, close);
        host.append(panel);
        root.append(host);
    }

    /** The kind's host-frame work, once a host frame. Render thread. */
    void hostFrame() {
        if (fault != null) return;
        try {
            context.runHostFrame();
        } catch (RuntimeException | LinkageError failed) {
            fault(failed);
        }
    }

    /** Runs the kind's close hooks on the document and drops it. Render thread. */
    void dispose() {
        context.dropHostFrame();
        if (scale != null) scale.disconnect();
        try {
            viewport.run(context::runClosed);
        } catch (RuntimeException | LinkageError failed) {
            CrystalGuiCore.LOGGER.error("[cgui] closing the screen '{}' failed", kind.id(), failed);
        }
        viewport.close();
    }
}
