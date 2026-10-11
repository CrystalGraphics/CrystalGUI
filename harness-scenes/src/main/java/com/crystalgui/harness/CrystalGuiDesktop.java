package com.crystalgui.harness;

import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.HarnessDesktop;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgui.app.vfxlab.VfxLab;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.dom.DocumentDriver;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;

/**
 * The harness's desktop over every 3D scene: a CrystalGUI document holding the engine's {@link Desktop}, wired as
 * {@code cgui-desktop} wires its own, built on the first window. Apps: {@code vfx-lab} ({@link VfxLab}), also on F6.
 * Shown, it is a screen as {@code CgUiScreen} is in game: the cursor is free, the camera holds, every key and click is
 * its own, and Escape that nothing in it wants (a popup, a focused field) hides it again, as F6 does.
 */
final class CrystalGuiDesktop implements HarnessDesktop {

    public static final String VFX_LAB = "vfx-lab";

    /** Every CrystalGUI scene's scale, so text reads at the harness's window sizes. */
    private static final float SCALE = 2f;

    /** The root fills the window, the desktop fills the root. */
    private static final String STYLE = ".harness-root { width: 100%; height: 100%; }\n";

    private HarnessContext ctx;
    private UIDocument document;
    private DocumentDriver<Void> driver;
    private DocumentDriver.Painter<Void> painter;
    private Desktop desktop;
    private volatile VfxLab lab;
    private CgRenderStage.Registration labFrames;
    private volatile double seconds;
    private volatile boolean shown;

    @Override
    public void attach(HarnessContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public boolean open(String app) {
        if (!VFX_LAB.equals(app)) return false;
        if (document == null) build();
        driver.run(() -> {
            if (lab == null) lab = new VfxLab();
            VfxLab.openOn(desktop, lab);
        });
        if (labFrames == null) {
            labFrames = CgWorldRenderer.get().onFrame(view -> {
                VfxLab running = lab;
                if (running != null) running.render(seconds);
            });
        }
        shown = true;
        return true;
    }

    private void build() {
        document = new UIDocument();
        driver = DocumentDriver.attach(document);
        painter = DocumentDriver.whole(document);
        driver.run(() -> {
            if (driver.sequence() == null) document.markFrameThread();
            document.styles().addStylesheet(StyleSheet.DEFAULT);
            document.styles().addStylesheet(StyleSheet.parse(STYLE));
            document.boxes().setUiScale(SCALE);
            UIElement root = new UIElement();
            root.addClass("harness-root");
            document.append(root);
            desktop = Desktop.of(document);
        });
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        seconds = frame.getElapsedTime();
        if (driver == null) return;
        // A key the document took on its own thread and left: Escape nothing wanted hides the desktop.
        for (CgSystemInput.Keyboard.Event left; (left = driver.pollUnhandledKey()) != null; ) {
            if (left.pressed() && left.key() == CgKeyCodes.KEY_ESCAPE) shown = false;
        }
        if (!shown) return;
        VfxLab running = lab;
        if (running != null) running.drawViewport(ctx.getScreenWidth(), ctx.getScreenHeight());
        driver.frame(frame.getDeltaTime(), ctx.getScreenWidth(), ctx.getScreenHeight(), painter);
    }

    @Override
    public boolean shown() {
        return shown;
    }

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        boolean f6 = event.pressed() && !event.repeat() && event.key() == CgKeyCodes.KEY_F6;
        if (!shown) {
            if (f6) open(VFX_LAB);
            return !f6;
        }
        if (f6) {
            shown = false;
            return false;
        }
        boolean escape = event.pressed() && event.key() == CgKeyCodes.KEY_ESCAPE;
        boolean taken = driver.offerKey(event, () -> {
            if (document.input().consumeKeyboardEvent(event)) return true;
            // A focused field gives up its focus before the desktop goes.
            if (escape && document.focus().focused() != null) {
                document.focus().clear();
                return true;
            }
            return false;
        });
        if (escape && !taken) shown = false;
        return false;
    }

    @Override
    public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
        if (!shown) return true;
        driver.offerMouse(event, () -> document.input().consumeMouseEvent(event));
        return false;
    }

    @Override
    public void dispose() {
        if (labFrames != null) labFrames.close();
        if (lab != null) lab.delete();
        if (driver != null) driver.close();
    }
}
