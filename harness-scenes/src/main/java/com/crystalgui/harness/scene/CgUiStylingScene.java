package com.crystalgui.harness.scene;

import com.crystalgraphics.harness.SceneRegistry;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.render.texture.CgUiSprite;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;

/**
 * Interactive harness scene that exercises CrystalGUI's stylesheet + transition system:
 * class/id/pseudo-class selectors, combinators, {@code !important}, and CSS-{@code transition}
 * state animations (both a paint-only property and a layout property, to prove interpolated
 * values actually reach Taffy, not just {@code getComputed()}) — plus {@code background}
 * cross-fades ({@code CgUiCrossFade}, via {@code TextureProperty}'s interpolator) between
 * color/texture/9-slice drawable pairs, triggered by hovering the {@code .fade-*} swatches.
 *
 * <p>Built on the real {@link UIDocument}/input plumbing, so
 * {@code :hover}/{@code :active} are driven by genuine mouse input in the harness window — move
 * the cursor over a button, click and hold it, tab between them (focus policy is default CLICK).</p>
 *
 * <p>Register in {@link SceneRegistry}
 * under scene id {@code "cgui-styling"}.</p>
 */
public class CgUiStylingScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard, CgSystemInput.Mouse {

    /** Logical-to-surface scale, as the harness\'s other new-engine scenes use. */
    private static final float SCALE = 2f;

    private UIDocument document;

    private final CgUiSprite panelSprite = new CgUiSprite()
            .setTexture("crystalgui:textures/gui/gdp_styles.png")
            .setTextureSizeReference(256, 256)
            .setSprite(29, 1, 13, 13)
            .setBorder(1, 1, 11, 11);

    private final CgUiSprite buttonSprite = panelSprite.copy()
            .setSprite(154, 165, 16, 16)
            .setBorder(5, 6, 9, 10);

    /**
     * Deliberately mirrors the documented CSS subset end to end: universal, class, compound
     * ({@code .button.primary}), id, pseudo-classes, the {@code >} child combinator,
     * {@code !important}, and two {@code transition:} entries — one on a paint-only property
     * ({@code color}), one on layout properties ({@code width}/{@code height}) so the demo also
     * proves the transition tick reaches TaffyBridge, not just the computed-style cache.
     */
    private static final String STYLE_SHEET = """
            * { z-index: 0; }

            .button {
                width: 32;
                height: 32;
                transition: background-color 250ms ease-in-out, width 200ms ease-out, height 200ms ease-out;
            }
            
            .button:hover {
                background-color: #FFFFFF;
                width: 60;
                height: 60;
            }

            .button.primary {
                background-color: #55AAFF;
            }
            .button:focus {
                background-color: #FF0000;
            }
            
            .button:active {
                width: 40;
                height: 40;
                background-color: #FFFF00;
            }

            #submit:disabled {
                background-color: #444444;
            }

            .panel > .button {
                margin-top: 2;
            }

            .color-swatch {
                background: #33CC9955;
                background-color: rgba(255, 0, 0, 0.25);
            }

            .sliced-swatch {
                background: sprite("crystalgui:textures/gui/gdp_styles.png", 154 165 16 16, 5 6 9 10);
            }

            /* image(...)'s new optional crop-rect arg: a plain (non-9-slice) sub-region of the atlas. */
            .cropped-swatch {
                background: image("crystalgui:textures/gui/gdp_styles.png", "154 165 16 16");
            }

            /* Background cross-fade demo (hover each swatch) — one entry per CgUiDrawable type
             * pairing, proving CgUiCrossFade works uniformly regardless of the concrete drawable
             * types on either side of the transition. */
            .fade-color-color {
                background: #3355AA;
                transition: background 250ms ease-in-out;
            }
            .fade-color-color:hover {
                background: #E8B23DCC;
            }

            .fade-color-texture {
                background: #33AA66;
                transition: background 250ms ease-in-out;
            }
            .fade-color-texture:hover {
                background: image("crystalgui:textures/gui/gdp_styles.png");
            }

            /* 9-slice <-> 9-slice with DIFFERENT border thicknesses (1,1,11,11 vs 5,6,9,10) —
             * CgUiCrossFade's draw-A-then-draw-B-on-top compositing has no per-pixel correspondence
             * to blend against when the two sides don't even share the same quad geometry, so this
             * looks rough by construction, not by bug. A real fix needs a dedicated 2-sampler
             * pixel-blend shader restricted to matching-geometry drawables — deliberately deferred,
             * not built here. Kept as a visible reference case, not polished. */
            .fade-texture-texture {
                background: sprite("crystalgui:textures/gui/gdp_styles.png", 154 165 16 16, 5 6 9 10);
                transition: background 250ms ease-in-out;
                transition: background 250ms ease-in-out, border-radius 250ms ease-in-out,
                border-width 250ms ease-in-out, border-color 250ms ease-in-out;
            }
            .fade-texture-texture:hover {
//                background: sprite("crystalgui:textures/gui/gdp_styles.png", 154 165 16 16, 5 6 9 10);
                border-radius: 24px;
                border-width: 2px;
                border-color: #000000

            }

            /* border-radius/border-width/border-color are a universal wrapping layer, not a special
             * background value type — they apply on top of whatever `background:` resolves to
             * (real CSS semantics). Transitions animate the underlying scalar/color longhands
             * directly (no shape-morph special case needed at the drawable level anymore). */
            .fade-sdf-color {
                background: #3355AA;
                border-radius: 24px;
                border-width: 2px;
                border-color: #000000;
                transition: background 250ms ease-in-out, border-radius 250ms ease-in-out,
                            border-width 250ms ease-in-out, border-color 250ms ease-in-out;
            }
            .fade-sdf-color:hover {
                background: #E8B23D;
                border-radius: 40px;
                border-width: 5px;
                border-color: #224488;
            }

            .fade-sdf-texture {
                background: #3355AA;
                border-radius: 40px;
                border-width: 3px;
                border-color: #224488;
                transition: background 250ms ease-in-out;
            }
            .fade-sdf-texture:hover {
                background: image("crystalgui:textures/gui/gdp_styles.png");
            }

            /* Per-corner radii, CSS border-radius order (TL TR BR BL): only the top edge is
             * rounded, bottom corners stay square. A 9-slice/sprite background can't be visually
             * clipped by this layer yet (documented gap) — this swatch uses a flat color fill so
             * the rounding/border are actually visible. */
            .rounded-corners-swatch {
                background: #EE8822;
                border-radius: 14px 14px 0px 0px;
                border-width: 2px;
                border-color: #224488;
            }
            """;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void init(HarnessContext ctx) {
        UIElement root = createStylingDemo();
        this.document = new UIDocument().markFrameThread();
        this.document.boxes().setUiScale(SCALE);
        UIElement sceneRoot = root;
        // THE ROOT FILLS THE DOCUMENT. On the old engine the scene's root WAS the window's
        // root and took the window's size; here the DOCUMENT is the root and this is an
        // ordinary child, which sizes to its content -- so without this the scene lays out
        // at nothing and draws nothing. DEFAULT origin, so a scene sheet still wins.
        StyleGroup.defaultPipeline(sceneRoot.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).heightPercent(100f));
        this.document.append(sceneRoot);
        this.document.styles().addStylesheet(StyleSheet.parse(STYLE_SHEET));
    }

    private UIElement createStylingDemo() {
        UIElement root = new UIElement()
                .generalStyle(s -> s.background(panelSprite.toRect()))
                .layout(l -> l
                        .width(420)
                        .height(320)
                        .paddingAll(16)
                        .flexDirection(FlexDirection.ROW)
                        .flexWrap(FlexWrap.WRAP)
                        .gapAll(12)
                        .justifyContent(AlignContent.CENTER)
                        .alignItems(AlignItems.CENTER)
                ).setFocusPolicy(FocusPolicy.NONE);
        root.addClass("panel");

//        UIElement row = new UIElement()
//                .layout(l -> l
//                        .flexDirection(FlexDirection.ROW)
//                        .gapAll(10)
//                        .alignItems(AlignItems.CENTER)
//                );
//        root.append(row);

        // button 0: "#submit", starts disabled -> demonstrates the #id:pseudo-class combo
        // button 1: "primary" class -> demonstrates compound selector + !important
        // button 2: plain "button" -> baseline hover/active transition
        //
        // NOTE: base width/height come from the ".button" stylesheet rule, not from a Java
        // .layout(l -> l.width(...)) call here. Setting a property via .layout()/.generalStyle()
        // creates an INLINE-origin candidate, and INLINE always outranks STYLESHEET regardless of
        // selector specificity (same as a real `style=""` attribute beating any non-!important CSS
        // rule) — so a ".button:hover { width: ... }" rule could never win against an inline base
        // width, and the property would never appear to update or transition at all.
        for (int i = 0; i < 3; i++) {
            UIElement button = new UIElement()
                    .generalStyle(s -> s.background(buttonSprite.toRect()));
            button.addClass("button");

            if (i == 0) {
                button.setId("submit");
//                button.setEnabled(false);
            } else if (i == 1) {
                button.addClass("primary");
            }

            root.append(button);
        }

        // SDF rounded-rect smoke-test: border-radius/border-width/border-color as a universal
        // wrapping layer over a flat-color background, exercised at runtime so
        // gui_rect.shader actually compiles under real GL, not just javac. border-width
        // (set via .borderAll below) now grows the layout box for real — it's the same
        // border-width-* longhand Taffy resolves, not a bespoke SDF-only number.
        UIElement roundedButton = new UIElement()
                .generalStyle(s -> s.background(com.crystalgui.render.texture.CgUiRect.ofColor(0xFFEE8822))
                        .borderRadius(10f)
                        .borderColor(0xFF224488))
                .layout(l -> l.width(48).height(48).borderAll(3));
        root.append(roundedButton);

        // Phase 7 smoke-test: background/background-color parsers exercised through the real
        // stylesheet pipeline (not constructed directly in Java), plus the new sprite(...) CSS
        // function for 9-slice-from-CSS.
        UIElement colorSwatch = new UIElement().layout(l -> l.width(24).height(48));
        colorSwatch.addClass("color-swatch");
        root.append(colorSwatch);

        UIElement slicedSwatch = new UIElement().layout(l -> l.width(32).height(32));
        slicedSwatch.addClass("sliced-swatch");
        root.append(slicedSwatch);

        UIElement croppedSwatch = new UIElement().layout(l -> l.width(32).height(32));
        croppedSwatch.addClass("cropped-swatch");
        root.append(croppedSwatch);

        // Background cross-fade demo — move the mouse over each swatch to trigger the
        // `background 700ms ease-in-out` transition. Each pairs a different CgUiDrawable
        // combination on either side of the fade:
        UIElement fadeColorColor = new UIElement().layout(l -> l.width(48).height(48)); // flat color -> flat color
        fadeColorColor.addClass("fade-color-color");
        root.append(fadeColorColor);

        UIElement fadeColorTexture = new UIElement().layout(l -> l.width(48).height(48)); // flat color -> full texture
        fadeColorTexture.addClass("fade-color-texture");
        root.append(fadeColorTexture);

        UIElement fadeTextureTexture = new UIElement().layout(l -> l.width(48).height(48)); // 9-slice -> 9-slice
        fadeTextureTexture.addClass("fade-texture-texture");
        root.append(fadeTextureTexture);

        UIElement fadeSdfColor = new UIElement().layout(l -> l.width(48).height(48)); // SDF rounded rect -> SDF rounded rect (color fill)
        fadeSdfColor.addClass("fade-sdf-color");
        root.append(fadeSdfColor);

        UIElement fadeSdfTexture = new UIElement().layout(l -> l.width(48).height(48)); // SDF rounded rect, color fill -> texture fill
        fadeSdfTexture.addClass("fade-sdf-texture");
        root.append(fadeSdfTexture);

        UIElement roundedCorners = new UIElement().layout(l -> l.width(48).height(48)); // per-corner radii: top rounded, bottom square
        roundedCorners.addClass("rounded-corners-swatch");
        root.append(roundedCorners);

        return root;
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        int w = ctx.getScreenWidth();
        int h = ctx.getScreenHeight();
        // SURFACE pixels in, LOGICAL units to lay out in -- the scale lives on the box
        // tree's root transform, so this is the only place the two spaces meet.
        document.frame(frame.getDeltaTime(), w / SCALE, h / SCALE);

        CgUiPaintContext paintContext = document.paintContext();
        paintContext.beginFrame(w, h);
        document.paint(paintContext);
        paintContext.endFrame();
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

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        final var styleEngine = document.styles();
        if (!styleEngine.getSheets().isEmpty())
            styleEngine.removeStylesheet(styleEngine.getSheets().getFirst());
        return document.input().consumeKeyboardEvent(event);
    }

    @Override
    public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
        return document.input().consumeMouseEvent(event);
    }
}
