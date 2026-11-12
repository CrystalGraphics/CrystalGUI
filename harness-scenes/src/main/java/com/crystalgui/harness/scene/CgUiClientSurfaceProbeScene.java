package com.crystalgui.harness.scene;

import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.ui.HarnessThemes;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.render.Surface;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.viewport.CgUiViewport;
import com.crystalgui.widget.text.UIText;

import javax.annotation.Nullable;

/**
 * One document showing another's surface: the client document keeps a root in a surface of its own, and a shell
 * document draws that surface's picture inside a frame of its own, both in one firing. The mechanism an
 * application on its own document stands on. Captures {@code probe} once the client has counted past 30 and exits.
 *
 * <pre>{@code
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=cgui-client-surface --seconds=8"
 * }</pre>
 */
public final class CgUiClientSurfaceProbeScene implements InteractiveSceneLifecycle {

    private static final String SHEET = """
            .client { will-change: transform; position: absolute; left: 0px; top: 0px; width: 300px; height: 160px;
                      padding: 8px; background: #2a6f3b; }
            .shell { padding: 16px; background: #20242c; width: 100%; height: 100%; }
            .frame { width: 340px; height: 240px; padding: 20px; background: #4c3f8a; }
            .portal { width: 300px; height: 160px; }
            .label { color: #FFFFFF; font-size: 14; }
            """;

    /** The client's root: in a surface of its own, handing the render thread what it is told of. */
    private static final class Client extends UIElement implements Surface.Owner {
        volatile Surface surface;

        @Override
        public void surface(Surface surface) {
            this.surface = surface;
        }

        @Override
        public boolean keepsSurface() {
            return true;
        }
    }

    /** The shell's side: draws the client's picture where it is. */
    private static final class Portal extends UIElement {
        final Client client;

        Portal(Client client) {
            this.client = client;
        }

        @Override
        public boolean paintsDynamically() {
            return true;
        }

        @Override
        public void paintContent(CgUiPaintContext ctx, Box box) {
            Surface surface = client.surface;
            if (surface != null) surface.drawPicture(ctx, 0f, 0f, box.width(), box.height());
        }
    }

    private final UIDocument clientDocument = new UIDocument();
    private final UIDocument shellDocument = new UIDocument();
    private CgUiViewport clientViewport, shellViewport;
    private CgUiViewport.Content clientContent, shellContent;
    private CgRenderStage.Registration screen;
    private UIText counter;
    private volatile boolean running = true;
    private int frames;

    @Override
    public void init(HarnessContext ctx) {
        clientViewport = CgUiViewport.open(clientDocument, CgUiViewport.Threading.fromFlags(), "probe-client");
        shellViewport = CgUiViewport.open(shellDocument, CgUiViewport.Threading.fromFlags(), "probe-shell");
        clientContent = CgUiViewport.Content.whole(clientDocument);
        shellContent = CgUiViewport.Content.whole(shellDocument);
        Client[] client = new Client[1];
        clientViewport.run(() -> {
            HarnessThemes.install(clientDocument.styles(), null);
            clientDocument.styles().addStylesheet(StyleSheet.parse(SHEET));
            client[0] = new Client();
            client[0].addClass("client");
            counter = new UIText("client: 0");
            counter.addClass("label");
            client[0].append(counter);
            clientDocument.append(client[0]);
        });
        shellViewport.run(() -> {
            HarnessThemes.install(shellDocument.styles(), null);
            shellDocument.styles().addStylesheet(StyleSheet.parse(SHEET));
            UIElement shell = new UIElement();
            shell.addClass("shell");
            UIElement frame = new UIElement();
            frame.addClass("frame");
            Portal portal = new Portal(client[0]);
            portal.addClass("portal");
            frame.append(portal);
            shell.append(frame);
            shellDocument.append(shell);
        });
        // The client first, so its surface has executed when the shell draws it.
        screen = CgRenderStage.SCREEN.register(CgRenderStage.VIEWPORT_WIDGETS, stage -> {
            clientViewport.frame(stage, clientContent);
            shellViewport.frame(stage, shellContent);
        });
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        int n = ++frames;
        clientViewport.execute(() -> counter.setText("client: " + n));
        if (n == 60) ctx.getArtifactService().requestCapture("probe");
        if (n == 70) running = false;
    }

    @Override
    public void dispose() {
        if (screen != null) screen.close();
        if (clientViewport != null) clientViewport.close();
        if (shellViewport != null) shellViewport.close();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean uses3DCamera() {
        return false;
    }

    @Override
    public boolean shouldShutdownOnComplete() {
        return true;
    }

    @Nullable
    UIDocument shell() {
        return shellDocument;
    }
}
