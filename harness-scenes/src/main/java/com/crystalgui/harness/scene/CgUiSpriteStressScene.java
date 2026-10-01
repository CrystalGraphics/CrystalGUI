package com.crystalgui.harness.scene;

import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;

/**
 * Nothing but 9-slice sprites, as many as asked for — the instrument for measuring what a sprite
 * costs to draw.
 *
 * <pre>
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=cgui-sprite-stress"
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=cgui-sprite-stress" -Dcrystalgui.spritestress.count=2000
 * ./gradlew :gl-debug-harness:runHarness --args="--mode=cgui-sprite-stress" -Dcrystalgui.spritestress.rotate=true
 * </pre>
 *
 * <p><b>Why a scene of its own rather than a page of the gallery.</b> The gallery's densest page draws
 * about seven sprites a frame and {@code cgui-ore-theme} thirty, which is far below the noise floor of
 * a wall-clock measurement on a driver: three runs of the same build there spread 39-52 µs per draw,
 * wider than any difference worth finding. 500 buttons puts the sprites at the top of the profile
 * instead of the bottom, so what changes when the draw path changes is legible.</p>
 *
 * <p>Ore is the theme because it is the one that backs a {@code button} with a 9-slice sprite —
 * {@code button { background: asset("crystalgui:ore", "button") }} over 5x7 pixels of art with a
 * {@code [2,2,2,4]} border, so every button here is nine quads or one, whichever the engine picks.</p>
 *
 * <p>{@code rotate=true} transforms each button off-axis, which is what sends {@code CgUiSprite} down
 * the one-quad {@code WITH_9SLICE_FILL} path; leave it off to measure the ordinary nine-quad path.
 * The two together are the A and the B.</p>
 */
public class CgUiSpriteStressScene implements InteractiveSceneLifecycle {

    private static final float SCALE = 2f;

    /** How many sprite-backed buttons to draw. 500 is enough to dominate a frame; 2000 still runs. */
    private static final int COUNT = Integer.getInteger("crystalgui.spritestress.count", 500);

    /** Transform every cell off-axis, so each takes the one-quad path. @see com.crystalgui.render.texture.CgUiSprite */
    private static final boolean ROTATE = Boolean.getBoolean("crystalgui.spritestress.rotate");

    /**
     * Cell width in logical px (height is two thirds of it). THE KNOB THAT DECIDES WHAT IS BEING
     * MEASURED: the nine-quad path costs per INSTANCE and the one-quad path costs per PIXEL, so small
     * cells weigh submission and large ones weigh the fragment shader. Both are worth knowing, and a
     * default that only showed one of them would answer the question wrongly.
     */
    private static final int CELL = Integer.getInteger("crystalgui.spritestress.cell", 34);

    private UIDocument document;

    @Override
    public void init(HarnessContext ctx) {
        UIElement root = new UIElement();
        StyleGroup.defaultPipeline(root.getStyle().getLayoutGroup(), l -> l
                .widthPercent(100f).heightPercent(100f)
                .flexDirection(FlexDirection.ROW)
                .flexWrap(FlexWrap.WRAP));

        // PLAIN ELEMENTS, NOT BUTTONS, and that is the whole point of the instrument: a Button also
        // draws a label, text owns a different material, and the switch per widget buries the very
        // difference being measured. An element whose background IS the sprite draws the sprite and
        // nothing else, so 500 of them can batch into one draw if the path allows it -- which is
        // exactly the claim under test.
        for (int i = 0; i < COUNT; i++) {
            UIElement cell = new UIElement();
            cell.addClass("cell");
            if (ROTATE) cell.addClass("spun");
            root.append(cell);
        }

        this.document = new UIDocument().markFrameThread();
        this.document.boxes().setUiScale(SCALE);
        this.document.append(root);
        this.document.styles().addStylesheet(StyleSheet.DEFAULT);
        this.document.styles().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));
        // The window is sized so every cell RASTERISES at the default count and size: laid out past the
        // viewport a cell still costs its submission and produces no fragments, which measures half the
        // question and reads as a clean win for whichever path submits less.
        this.document.styles().addStylesheet(StyleSheet.parse(
                ".cell { width: " + CELL + "px; height: " + (CELL * 2 / 3) + "px; margin: 1px;"
                        + "        background: asset(\"crystalgui:ore\", \"button\"); }"
                        + ".spun { transform: rotate(7deg); }"));
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        document.frame(frame.getDeltaTime(), ctx.getScreenWidth() / SCALE, ctx.getScreenHeight() / SCALE);
        CgUiPaintContext paint = document.paintContext();
        paint.beginFrame(ctx.getScreenWidth(), ctx.getScreenHeight());
        document.paint(paint);
        paint.endFrame();
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
