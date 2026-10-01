package com.crystalgui.harness.scene;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.text.render.CgTextGamma;
import com.crystalgraphics.text.render.CgTextGamma.Level;
import com.crystalgui.render.UiRecorder;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.text.UIText;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgui.harness.HarnessThemes;

import java.util.logging.Logger;

/**
 * Text gamma and contrast at several strengths: one specimen of the UI's own faces and colours, captured under each
 * of {@link #PRESETS} at UI scale 1 and 2.
 *
 * <p>Writes {@code scale<n>-<preset>} for every preset. Interactive afterwards: G cycles the presets, S toggles the
 * scale.</p>
 */
public class CgUiTextGammaScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard {

    private static final Logger LOGGER = Logger.getLogger(CgUiTextGammaScene.class.getName());

    private static final String PANGRAM = "The quick brown fox jumps over the lazy dog 0123456789";

    private static final String STYLES = """
            .specimen { padding: 12; gap: 10; }
            .panel { padding: 10; gap: 3; }
            .dark { background-color: #2B2D30; }
            .light { background-color: #F7F8FA; }
            .panel text { white-space: nowrap; }
            .dark text { color: #DFE1E5; }
            .light text { color: #1E1F22; }
            .dark .secondary { color: #9DA0A8; }
            .light .secondary { color: #6C707E; }
            .blue { color: #58A6FF; }
            .red { color: #E06C75; }
            .green { color: #98C379; }
            .code { font-family: "crystalgui:ui/fonts/JetBrainsMono-Regular.ttf"; }
            .pixel { font-family: "crystalgui:ui/fonts/MinecraftRegular.otf"; }
            """;

    private static final String[] PRESET_NAMES = {"off", "chromium", "strong", "heavy", "default"};
    private static final CgTextGamma[] PRESETS = {
            CgTextGamma.NONE, CgTextGamma.of(new Level(1.2f, 0.2f)), CgTextGamma.of(Level.STRONG),
            CgTextGamma.of(Level.HEAVY), CgTextGamma.DEFAULT};

    /** Frames each preset is held before its capture; a scale change waits longer, for the glyph workers. */
    private static final int HOLD = 6, SETTLE = 40;

    private UIDocument document;
    private float scale = 1f;
    private int preset;

    @Override
    public void init(HarnessContext ctx) {
        UIElementRegistry.bootstrap();
        document = new UIDocument().markFrameThread();
        document.boxes().setUiScale(scale);
        HarnessThemes.install(document.styles(), "crystalgui:crystal-dark");
        document.styles().addStylesheet(StyleSheet.parse(STYLES));

        UIElement root = new UIElement();
        root.addClass("specimen");
        StyleGroup.defaultPipeline(root.getStyle().getLayoutGroup(), l -> l.widthPercent(100f).heightPercent(100f));

        UIElement dark = panel(root, "dark");
        for (int px : new int[]{2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 16}) line(dark, px + "px  " + PANGRAM, px);
        line(dark, "12px secondary  " + PANGRAM, 12, "secondary");
        line(dark, "12px accents  " + PANGRAM, 12, "blue");
        line(dark, "12px accents  " + PANGRAM, 12, "red");
        line(dark, "12px accents  " + PANGRAM, 12, "green");
        line(dark, "public static void main(String[] args) { return value != null; }", 12, "code");
        line(dark, "Minecraft face  " + PANGRAM, 10, "pixel");

        UIElement light = panel(root, "light");
        for (int px : new int[]{2, 3, 4, 5, 6, 7, 8, 10, 12, 14}) line(light, px + "px  " + PANGRAM, px);
        line(light, "12px secondary  " + PANGRAM, 12, "secondary");
        line(light, "public static void main(String[] args) { return value != null; }", 12, "code");

        document.append(root);
    }

    private static UIElement panel(UIElement parent, String tone) {
        UIElement panel = new UIElement();
        panel.addClass("panel");
        panel.addClass(tone);
        parent.append(panel);
        return panel;
    }

    private static void line(UIElement panel, String text, int px, String... classes) {
        UIText line = new UIText(text);
        for (String c : classes) line.addClass(c);
        StyleGroup.inlinePipeline(line.getStyle().getGeneralGroup(), g -> g.fontSize(px));
        panel.append(line);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        // Each scale settles once for the glyph workers; a preset change needs no new glyphs.
        long n = frame.getFrameNumber();
        long perScale = SETTLE + (long) PRESETS.length * HOLD;
        if (n < 2 * perScale) {
            float wantScale = n < perScale ? 1f : 2f;
            if (scale != wantScale) setScale(wantScale);
            long t = n % perScale - SETTLE;
            if (t >= 0) {
                int p = (int) (t / HOLD);
                if (t % HOLD == 0) setPreset(p);
                if (t % HOLD == HOLD - 2) {
                    ctx.getArtifactService().requestCapture("scale" + (int) scale + "-" + PRESET_NAMES[p]);
                }
            }
        }

        int w = ctx.getScreenWidth();
        int h = ctx.getScreenHeight();
        document.frame(frame.getDeltaTime(), w / scale, h / scale);
        UiRecorder paint = document.recorder();
        paint.textGamma(PRESETS[preset]);
        paint.beginFrame(w, h);
        document.paint(paint);
        paint.endFrame();
    }

    private void setPreset(int preset) {
        this.preset = preset;
        LOGGER.info("[Harness] text gamma " + PRESET_NAMES[preset] + " " + PRESETS[preset] + " at scale " + scale);
    }

    private void setScale(float scale) {
        this.scale = scale;
        document.boxes().setUiScale(scale);
    }

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        if (event.pressed() && !event.repeat()) {
            if (event.key() == CgKeyCodes.KEY_G) {
                setPreset((preset + 1) % PRESETS.length);
                return true;
            }
            if (event.key() == CgKeyCodes.KEY_S) {
                setScale(scale == 1f ? 2f : 1f);
                return true;
            }
        }
        return document.input().consumeKeyboardEvent(event);
    }

    @Override
    public void dispose() {
        document = null;
    }

    @Override
    public boolean isRunning() {
        return true;
    }

    @Override
    public boolean uses3DCamera() {
        return false;
    }

    @Override
    public boolean shouldShutdownOnComplete() {
        return false;
    }
}
