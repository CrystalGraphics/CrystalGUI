package com.crystalgui.harness.scene;

import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.api.text.CgTextStroke;
import com.crystalgraphics.gl.render.CgVectorRenderer;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.widget.canvas.CanvasView;
import com.crystalgraphics.platform.input.CgSystemInput;
import java.util.function.BiConsumer;
import java.util.Locale;
import java.util.List;
import java.util.ArrayList;
import com.crystalgui.style.property.visual.transform.Transform;
import com.crystalgui.render.texture.CgUiBackdropFilter;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.input.keymap.KeyStroke;
import com.crystalgui.ui.input.keymap.KeymapResolver;
import com.crystalgui.ui.input.keymap.Keymap;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.ui.box.Box;
import com.crystalgui.core.collection.list.SelectionMode;
import com.crystalgui.core.collection.tree.TreeDataSource;
import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.ObservableList;
import com.crystalgui.core.property.Property;
import com.crystalgui.graph.NodeType;
import com.crystalgui.graph.NodeTypeRegistry;
import com.crystalgui.graph.port.PortType;
import com.crystalgui.app.shadergraph.ShaderGraphBridge;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.property.visual.Resize;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.text.syntax.KeywordTokenizer;
import com.crystalgui.ui.service.AnchoredPlacement;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.Checkbox;
import com.crystalgui.widget.control.CheckboxGroup;
import com.crystalgui.widget.composite.ColorSelector;
import com.crystalgui.widget.overlay.Dialog;
import com.crystalgui.widget.overlay.DialogManager;
import com.crystalgui.widget.overlay.Dropdown;
import com.crystalgui.widget.overlay.Menu;
import com.crystalgui.widget.scroll.ScrollerView;
import com.crystalgui.widget.control.Slider;
import com.crystalgui.widget.layout.SplitView;
import com.crystalgui.widget.control.Switch;
import com.crystalgui.widget.layout.Tab;
import com.crystalgui.widget.layout.TabView;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.overlay.Tooltip;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.shadow.Shadow;
import com.crystalgui.style.property.visual.shadow.ShadowList;
import com.crystalgui.style.property.visual.text.FontStyle;
import com.crystalgui.style.property.visual.text.FontWeight;
import com.crystalgui.style.property.visual.text.PaintOrder;
import com.crystalgui.style.property.visual.text.StrokeAlign;
import com.crystalgui.widget.config.ConfiguratorGroup;
import com.crystalgui.widget.config.ConfiguratorPanel;
import com.crystalgui.widget.texteditor.TextEditor;
import com.crystalgui.widget.graph.GraphNode;
import com.crystalgui.widget.graph.GraphView;
import com.crystalgui.widget.graph.NodePort;
import com.crystalgui.widget.graph.NodeWidgetFactory;
import com.crystalgui.widget.collection.list.ListRenderer;
import com.crystalgui.widget.collection.list.ListView;
import com.crystalgui.widget.collection.tree.TreeRenderer;
import com.crystalgui.widget.collection.tree.TreeView;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.input.keymap.KeyEventType;
import com.crystalgui.text.TextRange;
import dev.vfyjxf.taffy.style.FlexDirection;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;

/**
 * Every CrystalGUI widget, one page each, in one navigable window — the front door.
 *
 * <p>The other {@code cgui-*} scenes are focused regression tests: they force states programmatically,
 * stagger pixel captures across known frames, and each covers one widget in depth. This one covers
 * everything shallowly, so "what can CrystalGUI do today" is one command rather than fourteen. Reach
 * for the focused scene when you are changing a widget's behaviour; reach for this one when you want
 * to look at the library.</p>
 *
 * <p><b>The theme toggle is the other half of the point.</b> {@code default.css} (user-agent origin)
 * gives every widget functional geometry with no theme loaded; {@code ore.css} gives it appearance.
 * Toggling between them exercises both, and a widget that looks broken with Ore off has geometry
 * missing from {@code default.css} — which is a real bug, not a missing theme.</p>
 *
 * <p><b>Nothing here is lazy.</b> {@code TabView.addTab} eagerly builds both the tab and its pane, and
 * switching tabs toggles {@code display} rather than touching the tree — which is exactly what makes
 * element identity, listeners and scroll positions survive a switch. So every page below is
 * constructed during {@code init}. That is fine at this size; a page heavy enough to matter should
 * populate itself from an {@code onTabSelected} listener instead.</p>
 */
public class CgUiGalleryScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard, CgSystemInput.Mouse {

    /** Logical-to-surface scale, as the harness\'s other new-engine scenes use. */
    private static final float SCALE = 2f;

    private UIDocument document;
    private TabView pages;
    private Button themeToggle;

    /** Held, not re-fetched, so {@code removeStylesheet} has the identical instance to remove.
     * {@code StyleSheetRegistry} caches by path so a re-fetch would work too — this just says so. */
    private StyleSheet oreSheet;
    /** This scene's own layout rules. Must be re-added LAST on every toggle — see {@link #toggleTheme}. */
    private StyleSheet sceneSheet;
    private boolean oreOn = true;

    /** Backs the two-way-bound TextField pair, so the binding is visible rather than described. */
    private final Property<String> boundText = new Property<>("bound");

    @Override
    public void init(HarnessContext ctx) {
        // Held keys matter on the Slider, TextField and Scroller pages.

        this.oreSheet = StyleSheetRegistry.of("crystalgui:ore");
        this.sceneSheet = StyleSheetRegistry.of("harness:gallery");

        this.document = new UIDocument().markFrameThread();
        this.document.boxes().setUiScale(SCALE);
        UIElement sceneRoot = createDemo();
        // THE ROOT FILLS THE DOCUMENT. On the old engine the scene's root WAS the window's
        // root and took the window's size; here the DOCUMENT is the root and this is an
        // ordinary child, which sizes to its content -- so without this the scene lays out
        // at nothing and draws nothing. DEFAULT origin, so a scene sheet still wins.
        StyleGroup.defaultPipeline(sceneRoot.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).heightPercent(100f));
        this.document.append(sceneRoot);
        var engine = document.styles();
        engine.addStylesheet(StyleSheet.DEFAULT);   // USER_AGENT origin — stays through every toggle
        // NO COMMAND INSTALLS HERE ANY MORE, deliberately.
        //
        // This scene used to install UndoCommands and GraphCommands on the root, and that was the ONLY
        // place either happened -- so every other host got a graph that took focus, drew a selection and
        // answered no key at all. A widget's own keys belong to the widget: TextEditor installs
        // EditorCommands, GraphView installs GraphCommands plus the edit.undo/edit.redo chords, each
        // bound on itself so the bare letters cannot fire while typing somewhere else in the window.
        //
        // An APPLICATION's commands are still the application's -- see WorkbenchApplication, which
        // registers the dock, palette and file commands it decides to offer.
        // The graph theme. Added once and never toggled: the Ore toggle is about Minecraft chrome, and
        // a node graph has no Ore look to switch to — without it the nodes are unstyled boxes and the
        // port palette, which is the whole readability of the page, is missing.
        engine.addStylesheet(StyleSheetRegistry.of("crystalgui:graph"));
        engine.addStylesheet(oreSheet);
        engine.addStylesheet(sceneSheet);

        // After the window exists, because commands live on it — see installKeymap.
        installKeymap(document);
        installListStats(document);
        installTreeStats(document);
    }

    /**
     * Swaps the Ore theme in and out on the live window.
     *
     * <p>Cheap: both mutators just mark every element dirty-match, and the re-match happens at the top
     * of the next {@code paintFrame}. No rebuild, no reattach.</p>
     *
     * <p><b>The scene sheet must be removed and re-added last.</b> {@code sourceOrder} packs the sheet
     * index above the rule index, so re-adding a sheet puts it back at the <em>end</em> of the list —
     * i.e. at the highest priority, not its original position. Leave the scene sheet in place and
     * re-adding Ore would start winning this scene's own layout rules at equal specificity.</p>
     */
    private void toggleTheme() {
        var engine = document.styles();
        engine.removeStylesheet(sceneSheet);
        if (oreOn) {
            engine.removeStylesheet(oreSheet);
        } else {
            engine.addStylesheet(oreSheet);
        }
        engine.addStylesheet(sceneSheet);
        oreOn = !oreOn;
        themeToggle.setText(oreOn ? "theme: oreg" : "theme: defaultg");
    }

    private UIElement createDemo() {
        UIElement root = new UIElement()
                .layout(l -> l.paddingAll(8).flexDirection(FlexDirection.COLUMN).gapAll(6))
                .setFocusPolicy(FocusPolicy.NONE);
        root.addClass("gallery-root");
        root.addClass("panel");

        root.append(header());

        pages = new TabView();
        pages.addClass("gallery-tabs");
        // A sidebar, not a top strip: thirty tabs across the top would overflow. The rail is a
        // ScrollerView either way, so a long list still scrolls.
        pages.setTabSide(TabView.TabSide.LEFT);
        root.append(pages);

        buttonPage(page("Button", "Press-and-release on the same element. Space/Enter when focused."));
        checkboxPage(page("Checkbox", "Standalone toggles, plus a CheckboxGroup that refuses to empty."));
        switchPage(page("Switch", "The knob slides via a CSS transition on an invisible spacer."));
        sliderPage(page("Slider", "Drag, click-to-jump, arrows, Home/End, wheel."));
        textFieldPage(page("TextField", "Two validation tiers, and a two-way binding you can watch."));
        textPage(page("UIText", "Self-sizing, or wrapping once an ancestor constrains its width."));
        scrollerPage(page("Scroller", "ScrollerView opts into the wheel; a bare element does not."));
        splitViewPage(page("SplitView", "Draggable dividers, nestable."));
        tabViewPage(page("TabView", "A TabView inside a TabView pane."));
        elementPage(page("UIElement", "The styleable div everything else is built from."));
        transformPage(page("transform", "CSS transform + transform-origin. Layout never sees them; clicks follow."));
        edgesPage(page("edges", "Rotated and skewed quads, as large as the page allows: their edges are antialiased analytically, no MSAA. A sprite's slices keep their seams hard and soften only the outline."));
        tooltipPage(page("Tooltip", "Top layer: hover a row INSIDE the scroller - the tooltip escapes the clip."));
        dragPage(page("Drag", "Drag a chip onto a bin. Ghost follows the cursor; Escape cancels."));
        resizePage(page("resize", "In-flow boxes get 3 handles, like CSS. The Dialog page has all 8."));
        dialogPage(page("Dialog", "Drag to move, click to raise, X closes. New windows cascade."));
        textCssPage(page("text-css", "CSS text properties, ::highlight() ranges, and text-stroke - scroll for the stroke rows."));
        focusPage(page("focus", "Tab enters the tablist ONCE. Arrows move inside it."));
        modalPage(page("modal", "showModal(): backdrop, focus trap, Escape. Everything else inert."));
        menuPage(page("menus", "Dropdown, context menu, submenu. Click outside or Escape to dismiss."));
        keymapPage(page("keymap", "Bindings are scoped to the focused subtree. Grey text says what to press."));
        listPage(page("list", "100,000 rows. Watch the realised count while you scroll - it does not grow."));
        treePage(page("tree", "Right opens without moving focus; Left closes, or goes to the parent."));
        editorPage(page("editor", "Java and GLSL. Alt+Click for multiple carets; find/replace below."));
        curvePage(page("curve", "ctx.curve() Bezier strokes - scroll for all 15 rows. The last two are the correctness checks."));
        graphPage(page("graph", "Drag a wire onto empty space to add a node. Space opens the menu. Shift/Alt marquee. F frames."));
        shaderGraphPage(page("shadergraph", "P6.3 end to end: wire nodes, watch the .shader compile live. Space adds a node."));
        colorSelectorPage(page("colorselector", "The general colour picker: hue ring, SV square, live channel tracks."));
        configuratorPage(page("configurator", "P6.1.8: the whole control kit on one rhythm. Compare against docs/research/unity-inspector/."));
        glassPage(page("glass", "Backdrop material: blur, refraction, specular, noise. Drag the sliders."));
        textLabPage(page("text-lab", "Every text property on live controls: type into it, change the font, "
                + "drag the stroke, cast a shadow. The same specimen is drawn on dark and on light."));

        return root;
    }

    // ── glass page ──────────────────────────────────────────────────────────────

    /** Every specimen on the page, so one slider retunes all of them at once. */
    private final List<CgUiBackdropFilter> glassSpecimens = new ArrayList<>();

    private float glassPhase;

    /**
     * Liquid glass, with the parameters exposed.
     *
     * <p>The point of this page is that glass cannot be judged from a screenshot of a flat panel: the
     * blur has to have something to average, the saturation lift has to have colour to rescue, and the
     * refraction has to have a straight edge to bend. So the stage is deliberately busy and the blobs
     * DRIFT — a still frame hides the one property that separates a captured backdrop from a texture,
     * which is that it is live.</p>
     */
    private void glassPage(UIElement pane) {
        UIElement stage = new UIElement();
        stage.addClass("gl-stage");

        // The backdrop. A container the hook transforms, so one write moves every blob and none of them
        // re-lays-out: a transform is layout-free by construction, which is what makes it safe to
        // animate sixty times a second behind a live capture.
        UIElement blobs = new UIElement();
        blobs.addClass("gl-blobs");
        for (int i = 1; i <= 5; i++) {
            UIElement blob = new UIElement();
            blob.addClass("gl-blob");
            blob.addClass("gl-b" + i);
            blobs.append(blob);
        }
        stage.append(blobs);

        UIText label = new UIText("REFRACT");
        label.addClass("gl-label");
        stage.append(label);
        UIText sub = new UIText("a straight edge is what makes a lens legible");
        sub.addClass("gl-sub");
        stage.append(sub);

        // The specimens. Four shapes because a bezel behaves differently on each — see the sheet.
        stage.append(glassPanel("gl-capsule"));

        UIElement card = glassPanel("gl-card");
        UIText cardTitle = new UIText("Liquid Glass");
        cardTitle.addClass("gl-card-title");
        UIText cardBody = new UIText("blur + refraction + specular, over a live backdrop");
        cardBody.addClass("gl-card-body");
        card.append(cardTitle);
        card.append(cardBody);
        stage.append(card);

        stage.append(glassPanel("gl-circle"));

        UIElement tiles = new UIElement();
        tiles.addClass("gl-tiles");
        for (int i = 0; i < 5; i++) tiles.append(glassPanel("gl-tile"));
        stage.append(tiles);

        pane.append(stage);

        // A COMPOSITOR OVERRIDE, not the cascade: the drift is not a transition and must not become an
        // animation slot somebody then has to end. Written every frame, so a rebuilt box heals itself
        // on the next one. The hook is OWNED by the blobs and stops when they leave the tree.
        document.animation().every(blobs, delta -> {
            glassPhase += delta;
            Box box = blobs.box();
            if (box != null) {
                box.setTransform(Transform.translate((float) Math.sin(glassPhase * 0.23) * 26f,
                        (float) Math.cos(glassPhase * 0.17) * 18f));
            }
            return true;
        });

        UIElement controls = new UIElement();
        controls.addClass("gl-controls");
        UIElement left = new UIElement();
        left.addClass("gl-col");
        UIElement right = new UIElement();
        right.addClass("gl-col");

        left.append(glassControl("blur", 0f, 30f, 12f, "%.0f", CgUiBackdropFilter::setBlurRadius));
        left.append(glassControl("bezel", 0f, 30f, 10f, "%.0f", CgUiBackdropFilter::setBezel));
        left.append(glassControl("ior", 1f, 2.5f, 1.5f, "%.2f", CgUiBackdropFilter::setIor));
        right.append(glassControl("specular", 0f, 1.5f, 0.3f, "%.2f", CgUiBackdropFilter::setSpecular));
        right.append(glassControl("noise", 0f, 0.25f, 0.035f, "%.3f", CgUiBackdropFilter::setNoise));
        right.append(glassControl("saturation", 0f, 3f, 1.4f, "%.2f", CgUiBackdropFilter::setSaturation));

        controls.append(left);
        controls.append(right);
        pane.append(controls);
    }

    /** A specimen: an element whose background IS a glass material, registered for the sliders. */
    private UIElement glassPanel(String styleClass) {
        CgUiBackdropFilter glass = new CgUiBackdropFilter()
                .setBlurRadius(12f).setBezel(10f).setIor(1.5f)
                .setSpecular(0.3f).setNoise(0.035f).setSaturation(1.4f)
                .setTintArgb(0x40FFFFFF).setFallbackColorArgb(0x66202430);
        glassSpecimens.add(glass);

        UIElement panel = new UIElement();
        panel.addClass(styleClass);
        StyleGroup.inlinePipeline(panel.getStyle().getGeneralGroup(), g -> g.backdropFilter(glass));
        return panel;
    }

    /**
     * One labelled slider that retunes every specimen live.
     *
     * <p>Mutating the drawable in place rather than rebuilding it is deliberate and is why this is
     * immediate: the same {@link CgUiBackdropFilter} instance is what the cascade holds and what gets drawn each
     * frame, so a setter IS the update. Re-parsing a {@code glass(...)} declaration per drag frame would
     * churn the cascade for a value the shader reads directly.</p>
     */
    private UIElement glassControl(String name, float min, float max, float initial,
                                   String format, BiConsumer<CgUiBackdropFilter, Float> apply) {
        UIElement row = new UIElement();
        row.addClass("gl-ctl");

        UIText nameLabel = new UIText(name);
        nameLabel.addClass("gl-ctl-name");
        UIText valueLabel = new UIText(String.format(Locale.ROOT, format, initial));
        valueLabel.addClass("gl-ctl-val");

        Slider slider = new Slider();
        slider.addClass("gl-slider");
        slider.setRange(min, max).setValue(initial);
        slider.onValueChanged.connect(v -> {
            for (CgUiBackdropFilter glass : glassSpecimens) apply.accept(glass, v);
            valueLabel.setText(String.format(Locale.ROOT, format, v));
        });

        row.append(nameLabel);
        row.append(slider);
        row.append(valueLabel);
        return row;
    }

    // ── text lab page ───────────────────────────────────────────────────────────

    /** The two specimens, styled identically so an outline can be judged on both grounds. */
    private final List<UIText> textLabSpecimens = new ArrayList<>();

    private String textLabFamily = "crystalgui:ui/fonts/IBMPlexSans-Regular.ttf";
    /** -Dcrystalgui.gallery.textLabSize=N opens the lab at that size, for an unattended capture. */
    private float textLabSize = Float.parseFloat(System.getProperty("crystalgui.gallery.textLabSize", "64"));
    private FontWeight textLabWeight = FontWeight.NORMAL;
    private FontStyle textLabFontStyle = FontStyle.NORMAL;
    private float textLabStrokeValue = 2f;
    private Slider textLabStrokeSlider;
    private boolean textLabApplying;
    private UIText textLabNote;
    private boolean textLabStrokeIsEm;
    private int textLabStrokeColor = 0xFF0B5D8F;
    private int textLabColor = 0xFFF2F5F8;
    /** 0 follows {@code color}, 1 transparent, 2 white, 3 black. A MODE and not a resolved colour:
     *  resolving it once at selection pinned the fill to whatever the text colour was then, and every
     *  later text-colour change landed on {@code color} while the glyph kept being drawn from the
     *  pinned fill. */
    private int textLabFillMode;
    /** Once a fill has been written, the unset state is unreachable — so mode 0 must then write one. */
    private boolean textLabFillWritten;
    private StrokeAlign textLabAlign = StrokeAlign.OUTSET;
    private PaintOrder textLabPaintOrder = PaintOrder.NORMAL;

    // ── text-shadow ──
    /** 0 off, 1 one shadow, 2 the shadow plus a glow beneath it, 3 the shadow plus a sharp drop beneath it. */
    private int textLabShadowLayers;
    private float textLabShadowX = 3f;
    private float textLabShadowY = 4f;
    private float textLabShadowBlur = 8f;
    private float textLabShadowSpread;
    private float textLabShadowOpacity = 0.75f;
    private boolean textLabShadowInset;
    private int textLabShadowColor = 0xFF101418;
    private Slider textLabShadowXSlider, textLabShadowYSlider, textLabShadowBlurSlider, textLabShadowSpreadSlider,
            textLabShadowOpacitySlider;
    private Dropdown textLabShadowLayerDrop, textLabShadowSideDrop;
    private UIText textLabShadowCss;

    /**
     * Every text property on a control, against two grounds.
     *
     * <p>Two specimens rather than one, because an outline cannot be judged on a single background:
     * a dark stroke reads as a crisp edge on light and as a halo on dark, and only the pair shows
     * which one the author is getting. They take the SAME inline declarations, so any difference is
     * the ground and not the style.</p>
     *
     * <p>Writes are {@code INLINE} origin, which beats the sheet — a playground that lost to
     * {@code gallery.css} would look broken. And unlike the glass page, whose sliders mutate a
     * drawable the shader reads directly, these are real cascade writes: there is no other way in,
     * since a stroke is resolved from {@code ComputedStyle} per frame.</p>
     *
     * <p>What it cannot show is the {@code text-fill-color} UNSET state once anything has set it —
     * an inline candidate cannot be withdrawn here. The page opens with it unwritten so the
     * currentcolor fallback is what you see first; {@code text-css} keeps the dedicated rows.</p>
     */
    private void textLabPage(UIElement pane) {
        textLabSpecimens.clear();

        UIElement stage = new UIElement();
        stage.addClass("tl-stage");

        UIText onDark = new UIText("Handgloves");
        onDark.addClass("tl-specimen");
        UIText onLight = new UIText("Handgloves");
        onLight.addClass("tl-specimen");

        textLabSpecimens.add(onDark);
        textLabSpecimens.add(onLight);
        stage.append(textLabGround("tl-dark", onDark));
        stage.append(textLabGround("tl-light", onLight));
        pane.append(stage);

        UIElement controls = new UIElement();
        controls.addClass("tl-controls");
        UIElement colA = new UIElement();
        colA.addClass("tl-col");
        UIElement colB = new UIElement();
        colB.addClass("tl-col");
        UIElement colC = new UIElement();
        colC.addClass("tl-col");

        // ── content and face ──
        TextField content = new TextField();
        content.setText("Handgloves");
        content.addClass("tl-field");
        content.value.changed.connect((was, now) -> {
            for (UIText specimen : textLabSpecimens) specimen.setText(now);
        });
        colA.append(textLabRow("text", content));

        Dropdown family = new Dropdown();
        family.addClass("tl-drop");
        family.addOptions("IBM Plex Sans", "JetBrains Mono", "Minecraft", "monospace", "system-ui");
        family.onSelectionChanged.connect(i -> {
            textLabFamily = switch (i) {
                case 1 -> "crystalgui:ui/fonts/JetBrainsMono-Regular.ttf";
                case 2 -> "crystalgui:ui/fonts/Minecraft.otf";
                case 3 -> "monospace";
                case 4 -> "system-ui";
                default -> "crystalgui:ui/fonts/IBMPlexSans-Regular.ttf";
            };
            applyTextLab();
        });
        family.select(0);
        colA.append(textLabRow("font", family));

        colA.append(textLabSlider("size", 8f, 120f, textLabSize, "%.0f", v -> textLabSize = v));

        Dropdown weight = new Dropdown();
        weight.addClass("tl-drop");
        weight.addOptions("normal", "bold");
        weight.onSelectionChanged.connect(i -> {
            textLabWeight = i == 1 ? FontWeight.BOLD : FontWeight.NORMAL;
            applyTextLab();
        });
        weight.select(0);
        colA.append(textLabRow("weight", weight));

        Dropdown slant = new Dropdown();
        slant.addClass("tl-drop");
        slant.addOptions("normal", "italic", "oblique");
        slant.onSelectionChanged.connect(i -> {
            textLabFontStyle = i == 1 ? FontStyle.ITALIC : i == 2 ? FontStyle.OBLIQUE : FontStyle.NORMAL;
            applyTextLab();
        });
        slant.select(0);
        colA.append(textLabRow("style", slant));

        // ── the stroke ──
        // The stroke slider's range is the CAP, not a round number: at size 12 only the first 0.67px
        // of an 8px travel could ever be drawn, and a control that offers what cannot happen is how
        // this looked like a rendering bug rather than a bounded field. Re-ranged on every change, in
        // applyTextLab, since the cap moves with the font size.
        colB.append(textLabSlider("stroke", 0f, strokeSliderMax(), textLabStrokeValue, "%.2f",
                v -> textLabStrokeValue = v, sl -> textLabStrokeSlider = sl));

        Dropdown unit = new Dropdown();
        unit.addClass("tl-drop");
        // The two are genuinely different kinds: px is absolute, a percentage is of the font size.
        // Same slider number either way, so switching between them shows exactly what that costs.
        unit.addOptions("px", "% of font-size");
        unit.onSelectionChanged.connect(i -> {
            textLabStrokeIsEm = i == 1;
            applyTextLab();
        });
        unit.select(0);
        colB.append(textLabRow("unit", unit));

        Dropdown align = new Dropdown();
        align.addClass("tl-drop");
        align.addOptions("outset", "center", "inset");
        align.onSelectionChanged.connect(i -> {
            textLabAlign = i == 1 ? StrokeAlign.CENTER : i == 2 ? StrokeAlign.INSET : StrokeAlign.OUTSET;
            applyTextLab();
        });
        align.select(0);
        colB.append(textLabRow("align", align));

        Dropdown order = new Dropdown();
        order.addClass("tl-drop");
        order.addOptions("normal (stroke over)", "stroke under fill");
        order.onSelectionChanged.connect(i -> {
            textLabPaintOrder = i == 1 ? PaintOrder.STROKE : PaintOrder.NORMAL;
            applyTextLab();
        });
        order.select(0);
        colB.append(textLabRow("paint-order", order));

        // ── colours ──
        colC.append(textLabRow("text colour", textLabSwatches(argb -> textLabColor = argb, 0)));
        colC.append(textLabRow("stroke colour", textLabSwatches(argb -> textLabStrokeColor = argb, 4)));

        Dropdown fill = new Dropdown();
        fill.addClass("tl-drop");
        fill.addOptions("from color (unset)", "transparent (hollow)", "white", "black");
        fill.onSelectionChanged.connect(i -> {
            textLabFillMode = i;
            if (i != 0) textLabFillWritten = true;
            applyTextLab();
        });
        fill.select(0);
        colC.append(textLabRow("fill", fill));

        // WHAT THE NUMBER ON THE SLIDER ACTUALLY BECOMES. Without this the two units look identical:
        // at one font size each is just some number of pixels, and above the field's cap they are the
        // SAME number of pixels. What separates them is the size slider -- px holds still, a percentage
        // follows -- which nothing on the page showed.
        textLabNote = new UIText("");
        textLabNote.addClass("tl-note");
        colC.append(textLabNote);

        // Under the face and stroke controls, which leave room: a fourth column wraps off the page.
        textLabShadowControls(colA, colB);

        controls.append(colA);
        controls.append(colB);
        controls.append(colC);
        pane.append(controls);

        applyTextLab();
    }

    /**
     * One ground: a {@link CanvasView} with the specimen on its plane, so the glyph edges can be
     * inspected at the magnification this page exists for.
     *
     * <p>Zoom reaches 24x deliberately — a distance-field edge resolves over about one pixel, and
     * judging that means seeing the pixels. The specimen is a canvas NODE rather than a child of a
     * panel, which also stops it wrapping: a node is positioned on an unbounded plane, so a long
     * string runs off the side and is panned to instead of reflowing under the controls.</p>
     *
     * <p>Right-click resets. It is on {@code onMouseDown} at the BUBBLE phase, so the canvas's own
     * pan gesture — which claims the left button during capture — is untouched.</p>
     */
    private CanvasView textLabGround(String styleClass, UIText specimen) {
        CanvasView view = new CanvasView();
        view.addClass("tl-ground");
        view.addClass(styleClass);
        view.setZoomRange(0.25f, 100f);
        view.addNode(specimen, 14f, 10f);

        UIText hint = new UIText("1.00x");
        hint.addClass("tl-hint");
        view.addOverlay(hint);
        view.onViewChanged.connect(() ->
                hint.setText(String.format(Locale.ROOT, "%.2fx", view.getZoom())));

        view.onMouseDown.attachListener((el, event) -> {
            if (event.getButtonId() == CgMouseCodes.RIGHT_BUTTON) {
                view.setZoom(1f).setPan(0f, 0f);
                event.preventDefault();
            }
        }, false, true);
        return view;
    }

    /** Writes the whole state to both specimens. One place, so no control can half-update. */
    private void applyTextLab() {
        if (textLabApplying) return;   // setRange below re-enters through the slider's own listener
        textLabApplying = true;
        try {
            if (textLabStrokeSlider != null) textLabStrokeSlider.setRange(0f, strokeSliderMax());
        } finally {
            textLabApplying = false;
        }

        LengthPercent width = textLabStrokeIsEm
                ? LengthPercent.percent(textLabStrokeValue / 100f)
                : LengthPercent.px(textLabStrokeValue);
        updateTextLabNote(width);
        if (textLabShadowCss != null) {
            textLabShadowCss.setText("text-shadow: " + StylePropertyRegistry.TEXT_SHADOW.write(textLabShadows()));
        }
        for (UIText specimen : textLabSpecimens) {
            StyleGroup.inlinePipeline(specimen.getStyle().getGeneralGroup(), g -> {
                g.fontFamily(List.of(textLabFamily));
                g.fontSize(textLabSize);
                g.fontWeight(textLabWeight);
                g.fontStyle(textLabFontStyle);
                g.color(textLabColor);
                g.textStrokeWidth(width);
                g.textStrokeColor(textLabStrokeColor);
                g.strokeAlign(textLabAlign);
                g.paintOrder(textLabPaintOrder);
                g.textShadow(textLabShadows());
                // Mode 0 follows the text colour, and writes NOTHING until some other mode has
                // already pinned a candidate -- so the page opens on the real currentcolor fallback
                // and still tracks the swatches once it cannot go back to unset.

            });
        }
    }

    /**
     * The shadow column: a preset to start from, the four lengths and {@code inset} CSS gives a shadow, its
     * colour, and a second layer beneath it, so painter's order between a blurred and a sharp shadow can be
     * seen. The CSS the controls produce is printed under them, and is exactly what the specimens get.
     */
    private void textLabShadowControls(UIElement col, UIElement lengths) {
        Dropdown preset = new Dropdown();
        preset.addClass("tl-drop");
        preset.addOptions("presets...", "drop shadow", "soft shadow", "glow", "neon", "spread glow",
                "long shadow", "inset", "engraved");
        preset.onSelectionChanged.connect(this::applyTextLabShadowPreset);
        col.append(textLabRow("shadow", preset));
        // -Dcrystalgui.gallery.textLabShadow=N opens the lab on preset N, for an unattended capture of a look.
        int openOn = Integer.getInteger("crystalgui.gallery.textLabShadow", 0);

        Dropdown layers = new Dropdown();
        layers.addClass("tl-drop");
        layers.addOptions("off", "one", "+ glow beneath", "+ sharp drop beneath");
        layers.onSelectionChanged.connect(i -> {
            textLabShadowLayers = i;
            applyTextLab();
        });
        layers.select(0);
        textLabShadowLayerDrop = layers;
        col.append(textLabRow("layers", layers));

        lengths.append(textLabSlider("offset x", -24f, 24f, textLabShadowX, "%.1f",
                v -> textLabShadowX = v, sl -> textLabShadowXSlider = sl));
        lengths.append(textLabSlider("offset y", -24f, 24f, textLabShadowY, "%.1f",
                v -> textLabShadowY = v, sl -> textLabShadowYSlider = sl));
        lengths.append(textLabSlider("blur", 0f, 64f, textLabShadowBlur, "%.1f",
                v -> textLabShadowBlur = v, sl -> textLabShadowBlurSlider = sl));
        lengths.append(textLabSlider("spread", 0f, 12f, textLabShadowSpread, "%.1f",
                v -> textLabShadowSpread = v, sl -> textLabShadowSpreadSlider = sl));
        lengths.append(textLabSlider("opacity", 0f, 1f, textLabShadowOpacity, "%.2f",
                v -> textLabShadowOpacity = v, sl -> textLabShadowOpacitySlider = sl));

        Dropdown side = new Dropdown();
        side.addClass("tl-drop");
        side.addOptions("outer", "inset");
        side.onSelectionChanged.connect(i -> {
            textLabShadowInset = i == 1;
            applyTextLab();
        });
        side.select(0);
        textLabShadowSideDrop = side;
        col.append(textLabRow("side", side));

        col.append(textLabRow("shadow colour", textLabSwatches(argb -> textLabShadowColor = argb, 5)));

        textLabShadowCss = new UIText("");
        textLabShadowCss.addClass("tl-note");
        lengths.append(textLabShadowCss);

        if (openOn > 0) preset.select(openOn);
    }

    /** The list the controls describe, first shadow on top. */
    private ShadowList textLabShadows() {
        if (textLabShadowLayers == 0) return ShadowList.NONE;
        int alpha = Math.round(Math.max(0f, Math.min(1f, textLabShadowOpacity)) * 255f);
        int color = (alpha << 24) | (textLabShadowColor & 0x00FFFFFF);
        Shadow main = Shadow.of(textLabShadowX, textLabShadowY, textLabShadowBlur, textLabShadowSpread, color,
                textLabShadowInset);
        return switch (textLabShadowLayers) {
            case 2 -> ShadowList.of(main, Shadow.of(0f, 0f, 24f, 2f, 0xB04FC3F7, false));
            case 3 -> ShadowList.of(main, Shadow.of(6f, 6f, 0f, 0f, 0xFF000000, false));
            default -> ShadowList.of(main);
        };
    }

    /** Sets the controls to a named look; every control stays live afterwards. */
    private void applyTextLabShadowPreset(int preset) {
        if (preset == 0) return;
        float x = 3f, y = 4f, blur = 8f, spread = 0f, opacity = 0.75f;
        int color = 0xFF101418, layers = 1;
        boolean inset = false;
        switch (preset) {
            case 1 -> { x = 2f; y = 2f; blur = 0f; opacity = 1f; }
            case 2 -> { x = 0f; y = 6f; blur = 16f; opacity = 0.6f; }
            case 3 -> { x = 0f; y = 0f; blur = 12f; opacity = 1f; color = 0xFFE0A33C; }
            case 4 -> { x = 0f; y = 0f; blur = 4f; opacity = 1f; color = 0xFF4FC3F7; layers = 2; }
            case 5 -> { x = 0f; y = 0f; blur = 10f; spread = 3f; opacity = 0.9f; color = 0xFFD94F4F; }
            case 6 -> { x = 8f; y = 8f; blur = 2f; opacity = 0.8f; layers = 3; }
            case 7 -> { x = 2f; y = 3f; blur = 4f; opacity = 0.8f; inset = true; }
            case 8 -> { x = 0f; y = 2f; blur = 1f; opacity = 1f; color = 0xFFF2F5F8; inset = true; }
            default -> { }
        }
        textLabShadowLayers = layers;
        textLabShadowInset = inset;
        textLabShadowColor = color;
        // Each setValue lands in its store through the slider's listener, and each re-applies; the last one
        // applies the finished look.
        if (textLabShadowLayerDrop != null) textLabShadowLayerDrop.select(layers);
        if (textLabShadowSideDrop != null) textLabShadowSideDrop.select(inset ? 1 : 0);
        if (textLabShadowXSlider != null) textLabShadowXSlider.setValue(x);
        if (textLabShadowYSlider != null) textLabShadowYSlider.setValue(y);
        if (textLabShadowSpreadSlider != null) textLabShadowSpreadSlider.setValue(spread);
        if (textLabShadowOpacitySlider != null) textLabShadowOpacitySlider.setValue(opacity);
        if (textLabShadowBlurSlider != null) textLabShadowBlurSlider.setValue(blur);
        textLabShadowX = x;
        textLabShadowY = y;
        textLabShadowBlur = blur;
        textLabShadowSpread = spread;
        textLabShadowOpacity = opacity;
        applyTextLab();
    }

    /**
     * The widest stroke this size can actually draw, in whatever unit the slider is currently in.
     *
     * <p>{@code % of font-size} is the em fraction times a hundred, so its ceiling is the same number
     * at every size; px is that fraction OF the size, so its ceiling moves with the text.</p>
     */
    private float strokeSliderMax() {
        float cap = textLabStrokeCapEm();
        return textLabStrokeIsEm ? cap * 100f : cap * textLabSize;
    }

    /**
     * The cap for the face the specimens are actually drawn in.
     *
     * <p>Not {@link CgTextStroke#MAX_FIELD_WIDTH_EM}: that is the narrow band every face can hold,
     * and this lab's face is banded wider, so reading the constant would cap the slider at under half
     * of what the field really carries.</p>
     */
    private float textLabStrokeCapEm() {
        return textLabSpecimens.isEmpty()
                ? CgTextStroke.MAX_FIELD_WIDTH_EM
                : textLabSpecimens.get(0).maxStrokeWidthEm();
    }

    /**
     * Says what this width resolves to, in the three terms that decide what is drawn: pixels at the
     * current size, the em fraction the backend actually carries, and the cap.
     *
     * <p>Reading the cap off the specimen rather than repeating the number: the field's reach has
     * moved three times and is now per FACE, and a caption edited by hand is a caption that goes
     * stale.</p>
     */
    private void updateTextLabNote(LengthPercent width) {
        if (textLabNote == null) return;
        float px = width.resolve(textLabSize);
        float em = textLabSize <= 0f ? 0f : px / textLabSize;
        float capEm = textLabStrokeCapEm();
        float capPx = capEm * textLabSize;
        boolean clamped = em > capEm;

        String asked = textLabStrokeIsEm
                ? String.format("%.2f%% of %.0fpx = %.2fpx", textLabStrokeValue, textLabSize, px)
                : String.format("%.2fpx at size %.0f = %.4fem", textLabStrokeValue, textLabSize, em);
        String capped = clamped
                ? String.format(" — CLAMPED to %.2fpx, the widest this field carries.", capPx)
                : String.format(". Cap here is %.2fpx (%.4fem).", capPx, capEm);

        // The point of the two units, which only the SIZE slider shows: drag it and px holds still
        // while a percentage grows with the text.
        String units = textLabStrokeIsEm
                ? " A percentage follows the size slider; px would hold still."
                : " px holds still as the size slider moves; a percentage would follow it.";

        textLabNote.setText(asked + capped + units
                + " A stroke keeps its label on the distance-field tier down to the smallest size"
                + " the field can still antialias -- which is lower for a face banded wide, the same"
                + " banding this cap comes from; below that the glyph is a bitmap and the outline is"
                + " dropped.");
    }

    /** A row of clickable swatches — enough colours to judge an outline, without a whole picker. */
    private UIElement textLabSwatches(java.util.function.IntConsumer pick, int initial) {
        int[] colors = {0xFFF2F5F8, 0xFFE0A33C, 0xFFD94F4F, 0xFF4FA86B, 0xFF0B5D8F, 0xFF101418};
        UIElement row = new UIElement();
        row.addClass("tl-swatches");
        for (int i = 0; i < colors.length; i++) {
            int argb = colors[i];
            UIElement chip = new UIElement();
            chip.addClass("tl-chip");
            StyleGroup.inlinePipeline(chip.getStyle().getGeneralGroup(),
                    g -> g.background(com.crystalgui.render.texture.CgUiRect.ofColor(argb)));
            chip.onMouseDown.attachListener((el, event) -> {
                pick.accept(argb);
                applyTextLab();
            }, false, true);
            row.append(chip);
        }
        pick.accept(colors[initial]);
        return row;
    }

    /** A labelled row, matching the glass page's rhythm. */
    private UIElement textLabRow(String name, UIElement control) {
        UIElement row = new UIElement();
        row.addClass("tl-ctl");
        UIText label = new UIText(name);
        label.addClass("tl-ctl-name");
        row.append(label);
        row.append(control);
        return row;
    }

    /** A labelled slider that restyles both specimens on every drag frame. */
    private UIElement textLabSlider(String name, float min, float max, float initial,
                                    String format, java.util.function.Consumer<Float> store) {
        return textLabSlider(name, min, max, initial, format, store, null);
    }

    /** @param built receives the slider itself, for a caller that has to re-range it later. */
    private UIElement textLabSlider(String name, float min, float max, float initial,
                                    String format, java.util.function.Consumer<Float> store,
                                    java.util.function.Consumer<Slider> built) {
        UIElement row = new UIElement();
        row.addClass("tl-ctl");
        UIText label = new UIText(name);
        label.addClass("tl-ctl-name");
        // THE VALUE THE CONTROL WILL ACTUALLY HOLD, which is not always the one asked for: the stroke
        // slider's range is a cap that moves with the font size, and a Slider clamps into its range.
        // Clamping here rather than letting setValue do it silently keeps the number, the label and the
        // caller's field the same value -- the slider used to sit at its maximum reading 2.00.
        float start = Math.max(min, Math.min(max, initial));
        store.accept(start);

        UIText value = new UIText(String.format(Locale.ROOT, format, start));
        value.addClass("tl-ctl-val");

        Slider slider = new Slider();
        slider.addClass("tl-slider");
        slider.setRange(min, max).setValue(start);
        slider.onValueChanged.connect(v -> {
            store.accept(v);
            value.setText(String.format(Locale.ROOT, format, v));
            applyTextLab();
        });
        if (built != null) built.accept(slider);

        row.append(label);
        row.append(slider);
        row.append(value);
        return row;
    }

    private UIElement header() {
        UIElement head = new UIElement();
        head.addClass("gallery-head");

        UIText title = new UIText("CrystalGUI - widget gallery");
        title.addClass("label");
        head.append(title);

        UIElement spacer = new UIElement();
        spacer.addClass("spacer");
        head.append(spacer);

        themeToggle = new Button("theme: ore");
        themeToggle.addClass("theme-btn");
        themeToggle.attachListener(this::toggleTheme);
        head.append(themeToggle);

        return head;
    }

    /** Adds a tab, gives its pane the shared page styling and a one-line description, returns the pane. */
    private UIElement page(String label, String description) {
        Tab tab = pages.addTab(label);
        UIElement pane = tab.content();
        pane.addClass("page");

        UIText desc = new UIText(description);
        desc.addClass("desc");
        pane.append(desc);
        return pane;
    }

    private UIElement row(UIElement... children) {
        UIElement row = new UIElement();
        row.addClass("page-row");
        for (UIElement child : children) row.append(child);
        return row;
    }

    /** A fixed-width text cell, because a bare UIText sizes itself and columns would not line up. */
    private UIElement slot(String text) {
        UIElement slot = new UIElement();
        slot.addClass("slot");
        UIText label = new UIText(text);
        label.addClass("label");
        slot.append(label);
        return slot;
    }

    private UIElement swatch() {
        UIElement swatch = new UIElement();
        swatch.addClass("swatch");
        return swatch;
    }

    // ── Pages ───────────────────────────────────────────────────────────────────────────────────

    private void buttonPage(UIElement pane) {
        Button plain = new Button("click me");
        plain.attachListener(() -> buttonClicks++);

        Button withIcon = new Button("with an icon");
        withIcon.setPreIcon(swatch());

        Button disabled = new Button("disabled");
        disabled.setEnabled(false);

        pane.append(row(slot("plain"), plain));
        pane.append(row(slot("pre-icon"), withIcon));
        pane.append(row(slot("disabled"), disabled));
    }

    private int buttonClicks = 0;

    private void checkboxPage(UIElement pane) {
        Checkbox one = new Checkbox("free-standing");
        Checkbox two = new Checkbox("starts checked");
        two.setChecked(true);

        // allowEmpty(false) gives radio semantics: the last checked box refuses to un-check.
        CheckboxGroup group = new CheckboxGroup().allowEmpty(false);
        UIElement radios = row(slot("group, required"));
        for (String name : new String[]{"red", "green", "blue"}) {
            Checkbox option = new Checkbox(name);
            option.setGroup(group);
            radios.append(option);
        }

        pane.append(row(slot("standalone"), one, two));
        pane.append(radios);
    }

    private void switchPage(UIElement pane) {
        Switch off = new Switch();
        Switch on = new Switch();
        on.setChecked(true);
        pane.append(row(slot("off"), off));
        pane.append(row(slot("on"), on));
    }

    private Slider continuous;
    private Slider stepped;

    private void sliderPage(UIElement pane) {
        continuous = new Slider();
        continuous.setRange(0, 100).setValue(35);

        stepped = new Slider();
        stepped.setRange(0, 100).setStep(25).setValue(50);

        pane.append(row(slot("continuous"), continuous));
        pane.append(row(slot("step 25"), stepped));
    }

    private TextField plainField;

    private void textFieldPage(UIElement pane) {
        plainField = new TextField();
        plainField.setText("edit me");
        plainField.addClass("field");

        TextField placeholder = new TextField();
        placeholder.setPlaceholder("placeholder...");
        placeholder.addClass("field");

        // A 0..100 integer range can never be negative, so the mode's keystroke filter drops '-'
        // outright — while "500" is typable and merely lands the field in :invalid.
        TextField number = new TextField();
        number.setMode(TextField.Mode.INTEGER).setRange(0, 100).setText("42");
        number.addClass("field");

        TextField bound = new TextField();
        bound.bindValueBidirectional(boundText);
        bound.addClass("field");

        TextField mirror = new TextField();
        mirror.bindValueBidirectional(boundText);
        mirror.addClass("field");

        pane.append(row(slot("plain"), plainField));
        pane.append(row(slot("placeholder"), placeholder));
        pane.append(row(slot("int 0..100"), number));
        pane.append(row(slot("bound"), bound));
        pane.append(row(slot("...mirrors it"), mirror));
    }

    private void textPage(UIElement pane) {
        UIText self = new UIText("Self-sized: the element is exactly as wide as the glyphs.");
        self.addClass("label");

        UIText wrapped = new UIText(
                "Constrained by an ancestor, so it wraps instead: UIText only self-sizes its width "
                        + "while nothing else has given it one, and writes its measured height back "
                        + "as an !important candidate either way.");
        wrapped.addClass("desc");

        pane.append(self);
        pane.append(wrapped);
    }

    private ScrollerView scroller;

    private void scrollerPage(UIElement pane) {
        scroller = new ScrollerView();
        scroller.addClass("scroll-demo");
        for (int i = 1; i <= 14; i++) {
            UIElement rowEl = new UIElement();
            rowEl.addClass("scroll-row");
            if (i % 2 == 0) rowEl.addClass("scroll-row-alt");
            UIText label = new UIText("row " + i);
            label.addClass("label");
            rowEl.append(label);
            scroller.append(rowEl);
        }
        pane.append(scroller);
    }

    /**
     * The <b>top layer</b> — CSS Position 4 §top-layer, the machinery behind {@code <dialog>} and
     * popovers.
     *
     * <p>The scrolling list is the whole demo and the reason the feature exists. Hover any row: the
     * tooltip is anchored to a row <em>inside</em> an {@code overflow} container, yet draws outside
     * it. Before the top layer that was impossible — {@code drawSubtree} paints depth-first under
     * every ancestor's scissor, so the tooltip was clipped to the list. Scroll while hovering and it
     * tracks its row, because placement is recomputed per frame rather than cached.</p>
     *
     * <p>The other two rows are the placement fallbacks: one hard against the right edge (clamps
     * instead of overflowing) and one near the bottom (flips above its anchor).</p>
     */
    private void tooltipPage(UIElement pane) {
        Button plain = new Button("hover me");
        Tooltip.attach(plain, "A tooltip in the top layer.");

        Button multi = new Button("longer text");
        Tooltip.attach(multi, "Long enough to wrap against the tooltip's max-width from default.css.");

        pane.append(row(slot("basic"), plain));
        pane.append(row(slot("wrapping"), multi));

        // Right edge: a left-aligned tooltip would overflow, so placement clamps it inward.
        UIElement edgeRow = row(slot("clamps"), new UIElement());
        Button atEdge = new Button("near right edge");
        Tooltip.attach(atEdge, "Clamped inside the window instead of overflowing.");
        UIElement pusher = new UIElement();
        pusher.addClass("spacer");
        edgeRow.append(pusher);
        edgeRow.append(atEdge);
        pane.append(edgeRow);

        // The one that matters: anchors inside a clipping, scrolling container.
        ScrollerView list = new ScrollerView();
        list.addClass("scroll-demo");
        for (int i = 1; i <= 14; i++) {
            UIElement rowEl = new UIElement();
            rowEl.addClass("scroll-row");
            if (i % 2 == 0) rowEl.addClass("scroll-row-alt");
            UIText label = new UIText("row " + i + " - hover me");
            label.addClass("label");
            rowEl.append(label);
            Tooltip.attach(rowEl, "Row " + i + ": anchored inside the scroller, drawn outside it.");
            list.append(rowEl);
        }
        pane.append(list);
    }

    /**
     * Payload drag with drop targets, a ghost, and the cancel path — everything P2 added.
     *
     * <p>Drag a chip onto a bin. Things to look for, each of which is a bug if it misbehaves:</p>
     * <ul>
     *   <li>The <b>ghost</b> follows the cursor from wherever you grabbed the chip, and draws above
     *       everything — it is in the top layer, so the scroller below cannot clip it.</li>
     *   <li>A bin <b>highlights on enter and un-highlights on leave</b>, including when you cancel.
     *       A bin left lit is the symptom of a missing symmetric leave.</li>
     *   <li>A small movement is still a <b>click</b>, not a drag — that is the activation threshold.</li>
     *   <li><b>Escape</b> mid-drag aborts: no drop fires, the ghost disappears, the bin un-highlights.</li>
     *   <li>Nothing <b>hover-flickers</b> while you drag across the UI. That is pointer capture; before
     *       it existed, `:hover` fired on everything the cursor crossed.</li>
     * </ul>
     */
    private void dragPage(UIElement pane) {
        UIText status = new UIText("drag a chip onto a bin");
        status.addClass("label");
        pane.append(status);

        UIElement chips = new UIElement();
        chips.addClass("page-row");
        for (String name : new String[]{"alpha", "beta", "gamma"}) {
            chips.append(draggableChip(name, status));
        }
        pane.append(chips);

        UIElement bins = new UIElement();
        bins.addClass("page-row");
        bins.append(dropBin("bin one", status));
        bins.append(dropBin("bin two", status));
        pane.append(bins);
    }

    private UIElement draggableChip(String name, UIText status) {
        UIElement chip = new UIElement();
        chip.addClass("chip");
        UIText label = new UIText(name);
        label.addClass("label");
        label.setHitTest(false);
        chip.append(label);

        // The ghost lives INSIDE the chip on purpose: the drag controller excludes the source and
        // its descendants from drop targeting, so a ghost parented here can never become the drop
        // target for its own drag. It is display:none until a drag activates.
        UIElement ghost = new UIElement();
        ghost.addClass("chip");
        ghost.addClass("chip-ghost");
        UIText ghostLabel = new UIText(name);
        ghostLabel.addClass("label");
        ghost.append(ghostLabel);
        chip.append(ghost);

        chip.onMouseDown.attachListener((el, event) -> {
            // THE GHOST IS OFFERED, not set on a controller: `Drag.start` collects it, because
            // `DragGhost.follow` runs from the mouse-down handler that is about to call it and so has
            // no drag to hand it to yet.
            chip.document().input().offerGhost(ghost);
            // The payload overload => the default activation threshold, so a plain click stays a click.
            Drag.startWithPayload(chip, event.getPosition().x(), event.getPosition().y(), name,
                    new Drag.Listener() {
                        @Override public void onDragUpdate(float mx, float my, float sx, float sy,
                                                           float dx, float dy) { }
                        @Override public void onDragEnd(float mx, float my) {
                            Drag live = chip.document().input().mode(Drag.class);
                            status.setText(live == null || live.dropTarget() == null
                                    ? name + ": dropped on nothing" : status.getText());
                        }
                        @Override public void onDragCancel() { status.setText(name + ": cancelled"); }
                    });
        }, false, false);
        return chip;
    }

    private UIElement dropBin(String name, UIText status) {
        UIElement bin = new UIElement();
        bin.addClass("bin");
        UIText label = new UIText(name);
        label.addClass("label");
        label.setHitTest(false);
        bin.append(label);

        // preventDefault() is how a target accepts a drop — HTML5 DnD's one good idea, kept: an
        // element that never opts in cannot silently become a drop target.
        bin.onDragOver.attachListener((el, event) -> event.preventDefault(), false, false);
        bin.onDragEnter.attachListener((el, event) -> bin.addClass("bin-hot"), false, false);
        bin.onDragLeave.attachListener((el, event) -> bin.removeClass("bin-hot"), false, false);
        bin.onDrop.attachListener((el, event) -> {
            bin.removeClass("bin-hot");
            status.setText(event.getPayload() + " -> " + name);
        }, false, false);
        return bin;
    }

    /**
     * The CSS {@code resize} property (CSS UI 4) — drag a panel's corner grabber.
     *
     * <p>What to look for:</p>
     * <ul>
     *   <li>Each panel has a {@code __resizer__} grabber in its bottom-right corner. It appears purely
     *       because the cascade says {@code resize:} — nothing here constructs a widget.</li>
     *   <li><b>both</b> resizes freely; <b>horizontal</b> and <b>vertical</b> move one axis only.</li>
     *   <li>The <b>capped</b> panel refuses to grow past its {@code max-width}/{@code max-height} or
     *       shrink below its {@code min-*}. That clamping is Taffy's, not the resizer's — the spec
     *       says min/max are the <em>only</em> constraints on a resize.</li>
     *   <li>Resizing does not reflow the content inside the panel: the grabber is out of flow.</li>
     *   <li>Contrary to the spec, these are <b>not scroll containers</b> and it still works — see
     *       {@code Resize}'s javadoc for why we drop that restriction deliberately.</li>
     * </ul>
     */
    /**
     * CSS {@code resize} with <b>eight</b> handles -- four edges and four corners.
     *
     * <p>Not a divergence: CSS UI 4 says only that the UA "presents a bidirectional resizing
     * mechanism" and never prescribes a single corner grabber. Browsers ship one because theirs is
     * drawn in the scrollbar gutter and has nowhere else to go.</p>
     *
     * <p>What to look for:</p>
     * <ul>
     *   <li>Corners are tinted; <b>edges are invisible but grabbable</b> -- a background is not what
     *       makes something hittable, and four visible bars around every panel would be noise.</li>
     *   <li><b>Leading edges move the box as well as resizing it.</b> Drag the LEFT edge and the right
     *       edge stays put. That is the case CSS avoids needing by only ever offering bottom-right.</li>
     *   <li>{@code horizontal} shows the two side edges and <b>no corners</b> -- a corner would imply
     *       a vertical resize the mode forbids. Same for {@code vertical}.</li>
     *   <li>Corners sit above the edge strips they cross, so a corner drag wins the hit test.</li>
     * </ul>
     */
    private void resizePage(UIElement pane) {
        pane.append(row(slot("both"), resizablePanel("resize: both", Resize.BOTH, false)));
        pane.append(row(slot("horizontal"), resizablePanel("width only", Resize.HORIZONTAL, false)));
        pane.append(row(slot("vertical"), resizablePanel("height only", Resize.VERTICAL, false)));
        pane.append(row(slot("min/max"), resizablePanel("clamped 70-160 x 40-90", Resize.BOTH, true)));
    }

    private UIElement resizablePanel(String label, Resize mode, boolean capped) {
        UIElement panel = new UIElement();
        panel.addClass("rz");
        if (capped) panel.addClass("rz-capped");
        panel.generalStyle(g -> g.resize(mode));

        UIText text = new UIText(label);
        text.addClass("label");
        text.setHitTest(false);
        panel.append(text);
        return panel;
    }

    /**
     * {@link Dialog} — the web's {@code <dialog>}, modeless form, plus movement.
     *
     * <p>What to look for:</p>
     * <ul>
     *   <li>Drag a <b>title bar</b> to move a dialog. It tracks from the first pixel — no activation
     *       threshold, unlike a payload drag.</li>
     *   <li>Dialogs <b>clamp to the stage</b>, so neither can be dragged out of reach. That clamping
     *       is ours: no spec covers a movable window.</li>
     *   <li>They <b>stack against each other by z-index</b>, not by being in the top layer — a
     *       modeless dialog stays in ordinary stacking, and only {@code showModal()} would promote.
     *       Click "raise" to bring one forward.</li>
     *   <li><b>The X closes</b> it, and focus returns to whatever held it before. <b>Escape does
     *       NOT</b> close a modeless dialog — only showModal() establishes a close watcher, so
     *       browsers do not either. Escape mid-drag cancels the drag instead.</li>
     *   <li>Resizing works on them too: the second dialog sets {@code resize: both}, so the two
     *       features compose.</li>
     * </ul>
     */
    private void dialogPage(UIElement pane) {
        UIElement stage = new UIElement();
        stage.addClass("dlg-stage");
        pane.append(stage);

        // The manager owns placement and stacking. Everything the page used to do by hand — a z
        // counter, per-title-bar raise listeners, manual moveTo calls — is now its job.
        DialogManager manager = new DialogManager(stage);

        Dialog first = manager.manage(new Dialog("panel one"));
        first.addClass("dlg-a");
        UIText firstBody = new UIText("drag my title bar");
        firstBody.addClass("label");
        firstBody.setHitTest(false);
        first.getContent().append(firstBody);

        Dialog second = manager.manage(new Dialog("panel two (resizable)"));
        second.addClass("dlg-b");
        second.generalStyle(g -> g.resize(Resize.BOTH));
        second.getContent().append(new Button("a button"));

        Dialog third = manager.manage(new Dialog("panel three"));
        third.addClass("dlg-a");
        UIText thirdBody = new UIText("click me to raise");
        thirdBody.addClass("label");
        thirdBody.setHitTest(false);
        third.getContent().append(thirdBody);

        UIElement controls = new UIElement();
        controls.addClass("page-row");
        Button reopen = new Button("open all");
        reopen.attachListener(manager::showAll);
        controls.append(reopen);
        Button closeAll = new Button("close all");
        closeAll.attachListener(manager::closeAll);
        controls.append(closeAll);
        Button spawn = new Button("new window");
        spawn.attachListener(() -> {
            Dialog extra = manager.manage(new Dialog("panel " + (manager.getDialogs().size())));
            extra.addClass("dlg-a");
            extra.show();
        });
        controls.append(spawn);
        pane.append(controls);

        manager.showAll();
    }

    /**
     * 5.2 — the four CSS text properties, every one of them applied from a stylesheet rather than Java.
     *
     * <p>What to look for: the three alignments must land at the left edge, the centre and the right
     * edge of the same 190px box; {@code nowrap} must be one clipped line where the wrapping row above
     * it is several; the ellipsis row must be that same line ending in a real "…"; and the shadowed row
     * must show a dark offset copy one pixel down-right. The last row sets {@code white-space} and
     * {@code text-align} on the <em>wrapper</em>, so it also proves both inherit.</p>
     */
    private void textCssPage(UIElement outer) {
        // SCROLLED, because the stroke rows below need a big font to be worth looking at -- the field
        // only describes 3.75% of the em, so a 1px outline is invisible at label sizes and obvious at
        // display sizes. Same arrangement the curve page uses, and for the same reason.
        ScrollerView pane = new ScrollerView();
        pane.addClass("textcss-scroll");
        outer.append(pane);

        pane.append(row(slot("wrapping"), txBox("this label wraps because its box is narrower than the text", null)));
        pane.append(row(slot("align left"), txBox("aligned left", "tx-left")));
        pane.append(row(slot("align center"), txBox("aligned center", "tx-center")));
        pane.append(row(slot("align right"), txBox("aligned right", "tx-right")));
        pane.append(row(slot("nowrap"), txBox("one long line that will not wrap and so overflows", "tx-nowrap")));
        pane.append(row(slot("ellipsis"), txBox("one long line that gets cut short with an ellipsis", "tx-ellipsis")));
        pane.append(row(slot("shadow"), txBox("drop shadow behind me", "tx-shadow")));
        shadowRows(pane);
        pane.append(row(slot("inherited"), txBox("set on the WRAPPER, not the text", "tx-inherit")));

        // ── 6.1.1: ::highlight() ──────────────────────────────────────────────
        //
        // The CSS Custom Highlight API — the web's own way of styling ranges of text WITHOUT wrapping
        // them in elements, which exists on the web for our exact reason: an editor cannot afford a
        // <span> per token, and here every element is a real Taffy node. Java says where, CSS says what.
        //
        // The first row is a line of GLSL because that is the real consumer waiting downstream: the
        // shader graph's node inspector, and 6.1.7's code editor after it.
        pane.append(row(slot("syntax"), highlightBox("vec3 n = normalize(pos);", null, hl -> hl
                .mark("keyword", "vec3")
                .mark("function", "normalize")
                .mark("variable", "pos"))));

        // Decoration and background WITHOUT a colour: the text keeps its own, which is what makes a
        // search hit or a spelling mark readable rather than merely visible.
        pane.append(row(slot("decoration"), highlightBox("a misspelled word, and a deleted one", null,
                hl -> hl
                        .mark("spelling", "misspelled")
                        .mark("removed", "deleted"))));

        // Two ranges under ONE name — what a search actually produces, and the case that makes the
        // registry's sorted/disjoint rule worth having.
        pane.append(row(slot("search hits"), highlightBox("find the needle, then the next needle", null,
                hl -> hl.mark("search", "needle").mark("search", "needle"))));

        // Compare against the rows above: each coloured word's shadow must be a DARKER version of that
        // word, never the same brightness and never uniformly grey.
        pane.append(row(slot("+shadow"), highlightBox("red green blue, each with its own shadow",
                "tx-shadow-row", hl -> hl
                        .mark("red", "red")
                        .mark("green", "green")
                        .mark("blue", "blue"))));

        // The crash path: truncation paints a PREFIX, so a range reaching past the cut would fail the
        // backend's validation mid-paint. This one deliberately straddles the ellipsis.
        //
        // `.tx-ellipsis`, NOT `.tx-nowrap` — they differ by one declaration and render almost
        // identically, which is exactly the trap the stylesheet comment above `.tx-nowrap` warns about.
        pane.append(row(slot("+ellipsis"), highlightBox(
                "a highlighted range that runs straight past where this line gets cut", "tx-ellipsis",
                hl -> hl.mark("search", "runs straight past where this line gets cut"))));

        strokeRows(pane);
    }

    /**
     * {@code text-shadow} as CSS spells it: every kind of shadow on its own row, each one a class in
     * {@code gallery.css} so the rule reads beside what it draws.
     */
    private void shadowRows(UIElement pane) {
        pane.append(row(slot("hard drop"), txBox("Sharp offset", "tx-sh-hard")));
        pane.append(row(slot("blurred"), txBox("Soft drop", "tx-sh-soft")));
        pane.append(row(slot("glow"), txBox("Glowing", "tx-sh-glow")));
        pane.append(row(slot("stacked"), txBox("Neon", "tx-sh-neon")));
        // A blurred shadow listed FIRST paints over a sharp one listed second: the paint order spans atlases.
        pane.append(row(slot("list order"), txBox("Blur over hard", "tx-sh-order")));
        pane.append(row(slot("currentcolor"), txBox("Inherits colour", "tx-sh-current")));
        pane.append(row(slot("spread (L4)"), txBox("Spread glow", "tx-sh-spread")));
        pane.append(row(slot("inset (L4)"), txBox("Pressed in", "tx-sh-inset")));
        pane.append(row(slot("stroked"), txBox("Outlined", "tx-sh-stroked")));
        pane.append(row(slot("underlined"), txBox("Underline too", "tx-sh-underline")));
        pane.append(row(slot("em lengths"), txBox("Scales with size", "tx-sh-em")));
        pane.append(row(slot("rotated"), txBox("Transformed", "tx-sh-rotated")));
    }

    /**
     * {@code text-stroke} and everything that steers it.
     *
     * <p>Every row is large on purpose. The outline is read out of the glyph's distance field, which
     * carries real distance for {@code pxRange / 2} atlas texels either side of the outline — 0.0375
     * em at the shipping pairing. So a 1px stroke is a third of a pixel on a 10px label and nearly two
     * pixels on a 48px heading, and only the second is a thing anyone can judge.</p>
     *
     * <p>The last two rows are the ones to read carefully: they are the failures, shown deliberately
     * rather than avoided, so that hitting either on a real screen is recognisable instead of
     * mysterious.</p>
     */
    private void strokeRows(UIElement pane) {
        pane.append(row(slot("stroke 1px"), txBox("Crystal", "tx-stroke")));
        pane.append(row(slot("stroke 2px"), txBox("Crystal", "tx-stroke-2")));

        // The three alignments at ONE width, which is the only way the difference reads: outset keeps
        // the letterform's weight, centre eats half the width out of it, inset spends all of it inside
        // and closes the counters first.
        pane.append(row(slot("align outset"), txBox("Weight", "tx-stroke-2 tx-align-outset")));
        pane.append(row(slot("align center"), txBox("Weight", "tx-stroke-2 tx-align-center")));
        pane.append(row(slot("align inset"), txBox("Weight", "tx-stroke-2 tx-align-inset")));

        // paint-order only has anything to say when the stroke overlaps the fill, so both of these are
        // centred. With outset there is nothing underneath to hide and the two look identical.
        pane.append(row(slot("paint fill"), txBox("Order", "tx-stroke-2 tx-align-center")));
        pane.append(row(slot("paint stroke"), txBox("Order", "tx-stroke-2 tx-align-center tx-paint-stroke")));

        // A width with no colour: the stroke takes `color`, which is what the property's zero initial
        // stands for. This row must not be black.
        pane.append(row(slot("currentcolor"), txBox("Inherits", "tx-stroke-current")));

        // text-fill-color alone -- `color` still drives everything else, which is why the two are
        // separate properties.
        pane.append(row(slot("hollow"), txBox("Outline", "tx-stroke-2 tx-hollow")));

        // Set on the WRAPPER. All five properties inherit, so the label inside takes them without the
        // sheet ever naming it.
        pane.append(row(slot("inherited"), txBox("From the parent", "tx-stroke-inherit")));

        // THE CEILING, shown rather than avoided. 8px at this font size is far past the 0.0375em the
        // field can describe, so the outline stops growing instead of growing wrong -- it will look
        // identical to the 2px row, not thicker. That is the symptom to recognise when somebody asks
        // why a stroke "stopped responding".
        pane.append(row(slot("past the field"), txBox("Clamped", "tx-stroke-huge")));

        // THE TIER GAP. Small text rasterises to bitmap coverage, which has no distance to threshold,
        // so this row draws NO outline at all -- deliberately, rather than promoting the glyph to MSDF
        // behind the caller's back, which would cost more legibility at this size than the outline buys.
        pane.append(row(slot("bitmap (none)"), txBox("no outline at this size", "tx-stroke-2 tx-small")));
    }

    /**
     * Registers ranges by <b>substring search</b> rather than by literal indices.
     *
     * <p>Hand-counted offsets in a demo rot the moment anybody edits the string, and silently: the wrong
     * word lights up, or the range no longer exists. Searching forward from the previous match also
     * keeps the ranges under one name sorted and disjoint, which {@code HighlightRegistry} requires.</p>
     */
    private static final class HighlightBuilder {
        private final String text;
        private final UIText label;
        private int cursor;

        HighlightBuilder(String text, UIText label) {
            this.text = text;
            this.label = label;
        }

        HighlightBuilder mark(String name, String needle) {
            int at = text.indexOf(needle, cursor);
            if (at < 0) throw new IllegalArgumentException("gallery: '" + needle + "' is not in " + text);
            cursor = at + needle.length();
            label.highlights().add(name, TextRange.of(at, cursor));
            return this;
        }
    }

    /** A fixed-width box around a label — the label alone would self-size and leave nothing to align in. */
    private UIElement txBox(String text, String extraClass) {
        UIElement box = new UIElement();
        box.addClass("tx-box");
        // SPLIT, because addClass takes ONE name. A space-separated string went in as a single class
        // literally containing a space, which matches no selector and fails silently -- five rows of
        // the stroke section below rendered unstyled before this, which reads as the feature being
        // broken rather than as the demo being wrong.
        if (extraClass != null) {
            for (String name : extraClass.trim().split("\\s+")) {
                if (!name.isEmpty()) box.addClass(name);
            }
        }
        UIText label = new UIText(text);
        label.addClass("label");
        box.append(label);
        return box;
    }

    /** A `.tx-rich` box whose label carries named highlight ranges, styled entirely from the sheet. */
    private UIElement highlightBox(String text, String extraClass,
                                   java.util.function.Consumer<HighlightBuilder> build) {
        UIElement box = new UIElement();
        box.addClass("tx-box");
        box.addClass("tx-rich");
        if (extraClass != null) box.addClass(extraClass);

        UIText label = new UIText(text);
        label.addClass("label");
        box.append(label);
        build.accept(new HighlightBuilder(text, label));
        return box;
    }

    /**
     * 6.1.2 — the keymap.
     *
     * <p><b>What to look for.</b> Click "editor", then press <b>Mod+A</b>: the log says
     * {@code editor.selectAll}. Click "canvas" and press the same chord: {@code canvas.selectAll}. Neither
     * panel knows the other exists — resolution walks the focus path outward and takes the innermost
     * match, which is what VS Code needs a {@code when} clause to express and we get from having a
     * tree.</p>
     *
     * <p><b>Mod+Shift+P</b> works from anywhere, because it is bound on the page root rather than on a
     * panel. Application-wide is not a special case; it is just an outer scope.</p>
     *
     * <p><b>Mod+K</b> then <b>Mod+S</b> is a chord: the amber line shows the pending prefix, and it
     * clears on completion, on an unrelated key, on a focus change, or after five seconds. Without that
     * feedback a half-entered chord is indistinguishable from a dead keyboard.</p>
     *
     * <p><b>The trap row.</b> {@code B} is bound as a tool shortcut on the root. Press it with a panel
     * focused and the log fires; type it in the text field and it must type a "b" and fire nothing. That
     * guard is why every single-key shortcut in Photoshop does not corrupt every filename box.</p>
     *
     * <p><b>Space</b> is bound twice — press and release — which is the axis space-to-pan is built on.</p>
     */
    private void keymapPage(UIElement host) {
        // THE PAGE IS ITS OWN SCOPE. A keymap belongs to a node that declares one, and the tab's
        // content is an ordinary UIElement -- so the page roots itself in a KeymapNode and everything
        // below is inside it. That is what makes "page-scoped" mean anything here.
        KeymapNode pane = new KeymapNode();
        host.append(pane);
        UIText log = new UIText("(nothing yet)");
        // Never blank. An empty line is indistinguishable from a broken one, and this row exists
        // precisely to prove that a half-entered chord is visible — so it has to say what to press.
        UIText pending = new UIText("press Mod+K");

        // The tree is built before any window exists, so commands and bindings are installed later —
        // see installKeymap.
        pendingLabel = pending;
        keymapLog = log;

        pane.append(row(slot("scoped"), focusPanel("editor"), focusPanel("canvas"),
                hint("same chord, two panels")));

        UIElement logBox = new UIElement();
        logBox.addClass("km-log");
        logBox.append(log);
        pane.append(row(slot("last command"), logBox, hint("Mod+A in a panel")));

        UIElement pendingBox = new UIElement();
        pendingBox.addClass("km-pending");
        pendingBox.append(pending);
        pane.append(row(slot("chord"), pendingBox, hint("then Mod+S -> saveAll (page-scoped)")));

        TextField typing = new TextField();
        typing.addClass("km-field");
        typing.setPlaceholder("click, then type b");
        // Spelled out as a two-step check, because the interesting outcome here is a NON-event: the
        // point is that `last command` does NOT change. A hint that only names the rule leaves the
        // reader with nothing to look at.
        pane.append(row(slot("B vs typing"), typing,
                hint("1. press B in a panel -> tool.brush")));
        pane.append(row(slot(""), hint("2. type b in the box -> last command must NOT change")));

        pane.append(row(slot("global"), hint("Mod+Shift+P - declared on the command, so it needs no focus")));
        pane.append(row(slot("hold"), hint("Space fires on press AND release")));

        keymapRoot = pane;
    }

    /** Grey annotation text. The bindings are invisible by nature, so the page has to say what to press —
     * a demo nobody can operate proves nothing. */
    private UIElement hint(String text) {
        UIText label = new UIText(text);
        label.addClass("km-hint");
        return label;
    }

    /** A focusable panel that owns its own binding for a chord the other panel also binds. */
    private KeymapNode focusPanel(String name) {
        KeymapNode panel = new KeymapNode();
        panel.addClass("km-panel");
        panel.setFocusPolicy(FocusPolicy.CLICK);
        UIText label = new UIText(name);
        // pointer-events: none, and it is REQUIRED, not tidiness.
        //
        // Click-focus tests the hit TARGET's own policy — it does not walk up to a focusable
        // ancestor (see UIInputHandler, and Dialog's title bar, which calls requestFocus
        // explicitly for exactly this reason). A label filling its panel therefore swallows every
        // click, the panel never focuses, and since no binding can resolve without a focused
        // element the whole page goes dead.
        //
        // It looked fine while the panels were large, because there was bare panel left to click
        // around the text. Shrinking them to fit the pane is what exposed it — and Tab still
        // focused them perfectly throughout, which is the clue separating "not focusable" from
        // "unclickable".
        label.setHitTest(false);
        panel.append(label);
        keymapPanels.put(name, panel);
        return panel;
    }

    /**
     * A node that owns a keymap.
     *
     * <p>{@code UIElement} implements {@code KeymapScope} but owns no {@code Keymap} — {@code
     * keymapOrNull} defaults to null, which is right for the overwhelming majority of nodes and is
     * exactly what a page about SCOPING has to override. The old engine gave every element a lazily
     * created keymap; this engine makes owning one a decision, the same way {@code GraphView} and
     * {@code TextEditor} each declare theirs.</p>
     */
    private static final class KeymapNode extends UIElement {
        private final Keymap keymap = new Keymap();

        @Override
        public Keymap keymapOrNull() {
            return keymap;
        }
    }

    private final java.util.Map<String, KeymapNode> keymapPanels = new java.util.LinkedHashMap<>();
    private KeymapNode keymapRoot;
    private UIText keymapLog;
    private UIText pendingLabel;

    /**
     * Registers the commands and bindings once a window exists.
     *
     * <p>Separate from page construction because commands live on the {@code UIDocument} — deliberately,
     * rather than in a global static, so two windows can disagree about what an id means and so tests do
     * not leak registrations into each other.</p>
     */
    private void installKeymap(UIDocument window) {
        var commands = window.getCommands();
        for (var entry : keymapPanels.entrySet()) {
            String id = entry.getKey() + ".selectAll";
            commands.register(Command.of(id, "Select All in " + entry.getKey()).run(() -> logCommand(id)));
            entry.getValue().keymap.bind("Mod+A", id);
        }

        // "WORKS FROM ANYWHERE" IS A DECLARED BINDING NOW, not a binding on the window root.
        //
        // The point of the page is unchanged and the mechanism is not. The resolver walks the focus
        // path OUTWARD, so a binding on a page's pane is reachable only from inside it — descendants
        // are not ancestors. The old engine's outermost scope was the root ELEMENT, which owned a
        // keymap like every other element. Here the outermost scope is the COMMAND'S OWN declared
        // chord: `Keymap.acceleratorFor` falls through to `declaredBindings()` after every scope has
        // been asked, which is the same "reachable from anywhere" with one fewer place to put it.
        commands.register(Command.of("palette.open", "Command Palette")
                .binding("Mod+Shift+P").run(() -> logCommand("palette.open")));
        commands.register(Command.of("edit.saveAll", "Save All").run(() -> logCommand("edit.saveAll")));
        commands.register(Command.of("tool.brush", "Brush").run(() -> logCommand("tool.brush")));
        commands.register(Command.of("pan.begin", "Pan").run(() -> logCommand("pan.begin")));
        commands.register(Command.of("pan.end", "Pan end").run(() -> logCommand("pan.end")));

        keymapRoot.keymap.bind("Mod+K Mod+S", "edit.saveAll");
        keymapRoot.keymap.bind("B", "tool.brush");
        keymapRoot.keymap.bind("Space", "pan.begin");
        keymapRoot.keymap.bind("Space", "pan.end").on(KeyEventType.RELEASE);

        // ITS OWN RESOLVER, INSTALLED. The service builds a default one internally when no host has
        // supplied a `Chords`, and does not hand it out — so a page that wants to WATCH resolution
        // (the pending-chord readout below is the whole point of the multi-stroke demo) installs one
        // it holds a reference to. That is what `setChords` is documented for.
        KeymapResolver resolver = new KeymapResolver(commands);
        window.input().setChords((from, key, modifiers, pressed, repeat, millis) ->
                resolver.resolve(from, new KeyStroke(key, modifiers),
                        pressed ? KeyEventType.PRESS : KeyEventType.RELEASE, millis, repeat));
        resolver.onPendingChanged.connect(chord ->
                pendingLabel.setText(chord == null ? "press Mod+K" : chord + " ... waiting"));
    }

    private void logCommand(String id) {
        keymapLog.setText(id);
    }

    /**
     * 6.1.3 — the virtualised list.
     *
     * <p><b>What to look for.</b> The model has a hundred thousand rows. The "realised" counter is the
     * only way to see that virtualisation is happening at all: scroll as far and as fast as you like and
     * it stays in the mid-teens, while "created" stops climbing the moment the pool reaches its steady
     * size. A list that built an element per row would show 100,000 and take a while getting there.</p>
     *
     * <p>"pooled" is the recycling bench — elements that have left the window and are waiting to be
     * re-bound. Together the three numbers say the whole story: bounded window, bounded allocation,
     * everything else reused.</p>
     *
     * <p>Click a row, then scroll it far out of view and back. Focus returns to <em>the same row index</em>
     * rather than to whatever element inherited its slot — the view tracks the index, because a recycled
     * element is not a stable identity.</p>
     */
    private void listPage(UIElement pane) {
        ObservableList<String> model = new ObservableList<>();
        for (int i = 0; i < 100_000; i++) model.add("row " + i);

        ListView<String> list = new ListView<>(model);
        list.addClass("lv");
        list.setItemHeight(12f);
        list.setSelectionMode(SelectionMode.MULTIPLE);
        list.setRenderer(new ListRenderer<String>() {
            @Override
            public UIElement createTemplate() {
                // Structure and listeners ONCE. There is deliberately nowhere to put a listener in
                // bind(), which is what stops a recycled row accumulating one per scroll step.
                UIElement row = new UIElement();
                row.addClass("lv-row");
                row.setFocusPolicy(FocusPolicy.CLICK);
                UIText label = new UIText("");
                label.setHitTest(false);   // or the label eats the click and the row never focuses
                row.append(label);
                return row;
            }

            @Override
            public void bind(String item, int index, UIElement template) {
                ((UIText) template.children().get(0)).setText(item);
            }
        });
        pane.append(row(slot("100k rows"), list));

        UIText stats = new UIText("...");
        stats.addClass("lv-stat");
        pane.append(row(slot("counts"), stats));
        pane.append(row(slot(""), hint("realised stays bounded; created stops growing")));
        pane.append(row(slot(""), hint("click a row, scroll far away and back - focus returns")));
        pane.append(row(slot(""), hint("arrows / Home / End / PageUp / PageDown all navigate")));
        pane.append(row(slot(""), hint("Shift+arrow extends, Ctrl+arrow moves without selecting")));
        pane.append(row(slot(""), hint("then Space ADDS that row; Enter replaces with just it")));

        listView = list;
        listStats = stats;
    }

    private ListView<String> listView;
    private UIText listStats;

    /** Live counters. A ticker rather than a per-frame poll in render(), because the numbers are UI state
     * and the engine already has a place for that. */
    private void installListStats(UIDocument window) {
        document.animation().every(window, delta -> {
            if (listView == null || listStats == null) return false;
            listStats.setText("realised " + listView.realisedCount()
                    + "   pooled " + listView.pooledCount()
                    + "   of " + listView.getModel().size());
            return true;
        });
    }

    /**
     * 6.1.4 — the tree.
     *
     * <p><b>What to look for.</b> Click a folder, then use the arrows. <b>Right</b> on a closed node
     * opens it and the focus ring <em>stays where it is</em> — press it again to step into the first
     * child. <b>Left</b> closes an open node, and on an already-closed one jumps to the parent. That
     * asymmetry is the ARIA tree contract, and it is the part implementations usually get wrong.</p>
     *
     * <p><b>*</b> opens every sibling at the current level.</p>
     *
     * <p>The synthetic tree is 20 roots of 20 of 20 — eight thousand nodes if fully opened — and children
     * are generated on demand, so nothing exists until you ask for it. Everything else (virtualisation,
     * recycling, selection, focus surviving a scroll) is inherited from the list and is not tree code.</p>
     */
    private void treePage(UIElement pane) {
        TreeDataSource<String> source = new TreeDataSource<>() {
            @Override
            public java.util.List<String> roots() {
                java.util.List<String> out = new java.util.ArrayList<>();
                for (int i = 0; i < 20; i++) out.add("folder " + i);
                return out;
            }

            @Override
            public java.util.List<String> children(String parent) {
                java.util.List<String> out = new java.util.ArrayList<>();
                for (int i = 0; i < 20; i++) out.add(parent + "/" + i);
                return out;
            }

            @Override
            public boolean hasChildren(String item) {
                // Three levels deep, then leaves — enough to show nesting without being endless.
                return item.chars().filter(c -> c == '/').count() < 2;
            }
        };

        TreeView<String> tree = new TreeView<>(source);
        tree.addClass("tv");
        tree.setItemHeight(12f);
        tree.setSelectionMode(SelectionMode.MULTIPLE);
        tree.setRenderer(new TreeRenderer<String>() {
            @Override
            public UIElement createTemplate() {
                UIElement row = new UIElement();
                row.addClass("tv-row");
                row.setFocusPolicy(FocusPolicy.CLICK);
                // A real vector chevron (overlay: shape("chevron-right"), rotated when expanded)
                // rather than the "v"/">" glyph this used to be — the bundled Minecraft font has
                // no triangle character at all. Stays hittable, unlike NodeCreationMenu's twisty:
                // this page's own comment explains why (clicking anywhere else only focuses/selects).
                UIElement twisty = new UIElement();
                twisty.addClass("tv-twisty");
                // The twisty is HITTABLE, unlike the label. Clicking it toggles; clicking anywhere else
                // in the row just focuses and selects. Without this the tree can only be opened from the
                // keyboard, which is how the first version of this page came out looking completely inert.
                //
                // The listener is attached ONCE per pooled element and reads the row's CURRENT index at
                // click time — it cannot capture an index, because this element represents a different
                // row every time it is recycled. That is exactly the split createTemplate/bind exists for.
                twisty.onMouseDown.attachListener((el, event) -> {
                    int index = treeView.indexOfRowElement(el.parentElement());
                    if (index >= 0) treeView.toggleExpandedAt(index);
                }, false, false);
                row.append(twisty);
                UIText label = new UIText("");
                label.setHitTest(false);
                row.append(label);
                return row;
            }

            @Override
            public void bind(String item, TreeRow<String> row, int index, UIElement template) {
                // The twisty's own look is driven entirely by the __expanded__/__collapsed__/
                // __leaf__ classes TreeView already applies to `template` — see gallery.css's
                // .tv-twisty rules. No Java decision needed here any more.
                String name = item.substring(item.lastIndexOf('/') + 1);
                ((UIText) template.children().get(1)).setText(row.depth() == 0 ? item : name);
            }
        });
        treeView = tree;
        pane.append(row(slot("tree"), tree));

        treeStatus = new UIText("...");
        treeStatus.addClass("tv-status");
        pane.append(row(slot("state"), treeStatus));

        pane.append(row(slot(""), hint("click the > to open, or use the arrows")));
        pane.append(row(slot(""), hint("Right opens WITHOUT moving focus; again steps in")));
        pane.append(row(slot(""), hint("Left closes, or jumps to the parent when closed")));
        pane.append(row(slot(""), hint("* opens every sibling at this level")));
        pane.append(row(slot(""), hint("8,000 nodes if fully opened; children made on demand")));
    }

    private TreeView<String> treeView;
    private UIText treeStatus;

    /** Live tree state. The focused index is the one the arrows act on, and seeing it is the difference
     * between "Right is broken" and "nothing was focused". */
    private void installTreeStats(UIDocument window) {
        document.animation().every(window, delta -> {
            if (treeView == null || treeStatus == null) return false;
            treeStatus.setText("focus " + treeView.getFocusedIndex()
                    + "   rows " + treeView.visibleRows().size()
                    + "   realised " + treeView.realisedCount());
            return true;
        });
    }

    /**
     * 5.3 — a tablist is one Tab stop, however many tabs it has.
     *
     * <p>What to look for, all by keyboard: click "before", then press <b>Tab</b>. Focus must land on the
     * <em>selected</em> tab and on no other — one press, not three. <b>Left/Right</b> then move between
     * the tabs (they are still focusable, just not tabbable), and the stop <em>roves</em>: whichever tab
     * you leave selected is the one Tab returns to. Another <b>Tab</b> must leave the strip entirely for
     * "after", and <b>Shift+Tab</b> must retrace the same three stops backwards — asymmetry there would
     * be a keyboard trap.</p>
     *
     * <p>Clicking any tab must still focus it. That is the trap this page exists to catch: the click path
     * used to compare the policy for equality with {@code CLICK}, which would leave every unselected tab
     * dead to the mouse the moment it stopped being the selected one.</p>
     */
    private void focusPage(UIElement pane) {
        Button before = new Button("before");
        before.addClass("fc-btn");
        pane.append(before);

        TabView strip = new TabView();
        strip.addClass("fc-strip");
        for (String name : new String[] { "one", "two", "three" }) {
            UIText body = new UIText("pane " + name);
            body.addClass("label");
            strip.addTab(name).content().append(body);
        }
        pane.append(strip);

        Button after = new Button("after");
        after.addClass("fc-btn");
        pane.append(after);
    }

    /**
     * {@code showModal()} — the inert primitive and the top layer doing their jobs together.
     *
     * <p>What to look for, in this order:</p>
     * <ol>
     *   <li>Open it. A <b>scrim</b> covers the whole window, not just this page — a modal blocks the
     *       document, and the backdrop resolves against the initial containing block like the dialog
     *       itself does.</li>
     *   <li><b>Click the buttons behind it.</b> Nothing happens, and the counter does not move: everything
     *       outside the modal is inert, so hit-testing passes straight over it.</li>
     *   <li><b>Press Tab repeatedly.</b> Focus cycles inside the dialog forever and never reaches the
     *       page behind. There is no trap code — that is inertness.</li>
     *   <li><b>Press Escape.</b> It closes, because a modal establishes a close watcher. Then reopen and
     *       tick "veto Escape": {@code preventDefault()} on the cancel event keeps it open, and only the
     *       X still closes it.</li>
     *   <li>Compare with the <b>modeless</b> button: no scrim, the page stays live, Escape does nothing.
     *       That asymmetry is the spec, not an oversight.</li>
     * </ol>
     */
    private void modalPage(UIElement pane) {
        UIElement stage = new UIElement();
        stage.addClass("md-stage");
        pane.append(stage);

        UIText counter = new UIText("clicks behind the modal: 0");
        counter.addClass("label");
        stage.append(counter);

        int[] behindClicks = { 0 };
        Button behind = new Button("click me (should be blocked)");
        behind.addClass("md-btn");
        behind.attachListener(() -> counter.setText("clicks behind the modal: " + (++behindClicks[0])));
        stage.append(behind);

        Dialog modal = new Dialog("a modal dialog");
        modal.addClass("md-dialog");
        // Two focusables, so the Tab cycle is visibly a cycle rather than a single stuck stop.
        modal.getContent().append(new Button("first"));
        modal.getContent().append(new Button("second"));
        stage.append(modal);

        Checkbox veto = new Checkbox("veto Escape");
        modal.onCancel.attachListener((el, event) -> {
            if (veto.isChecked()) event.preventDefault();
        }, false, false);
        modal.getContent().append(veto);

        UIElement controls = new UIElement();
        controls.addClass("page-row");
        Button openModal = new Button("showModal()");
        openModal.attachListener(() -> {
            modal.moveTo(60f, 20f);
            modal.showModal();
        });
        controls.append(openModal);
        Button openModeless = new Button("show()");
        openModeless.attachListener(() -> {
            modal.moveTo(60f, 20f);
            modal.show();
        });
        controls.append(openModeless);
        pane.append(controls);
    }

    /**
     * Menus — one widget, three shapes: a dropdown, a context menu, and a submenu.
     *
     * <p>What to look for:</p>
     * <ol>
     *   <li>Open the <b>dropdown</b>. Pick an option — the button's label follows the selection. Press the
     *       button again while it is open: it <b>closes</b> rather than flickering, which is the invoker
     *       carve-out in light dismiss doing its job.</li>
     *   <li><b>Right-click the dark canvas.</b> The same {@code Menu} class opens at the pointer instead of
     *       under an element — a context menu is not a second widget. The label reports the point it was
     *       opened at, so the pointer-to-root conversion is visible rather than assumed.</li>
     *   <li>With a menu open, <b>click anywhere else</b>: it dismisses, and the thing you clicked still
     *       reacts. <b>Escape</b> also closes it, innermost first.</li>
     *   <li>Open the dropdown and hover <b>"More..."</b> then press it — a <b>submenu</b> opens to the
     *       right and its parent <em>stays open</em>. Clicking back in the parent closes only the submenu.
     *       Those two behaviours are opposites and both have to hold.</li>
     *   <li><b>Arrow keys</b> walk the items and wrap; the whole menu is a single Tab stop.</li>
     * </ol>
     */
    private void menuPage(UIElement pane) {
        UIElement stage = new UIElement();
        stage.addClass("mn-stage");
        pane.append(stage);

        UIText report = new UIText("nothing chosen yet");
        report.addClass("label");
        stage.append(report);

        Dropdown quality = new Dropdown("quality...");
        quality.addClass("mn-drop");
        quality.addOptions("Low", "Medium", "High", "Ultra");
        quality.attachSelectionListener(index -> report.setText("chose " + quality.getSelectedOption()));
        stage.append(quality);

        // A submenu of the dropdown's own menu. addSubmenu wires all of it: the item does not close its
        // parent, the child anchors to the row, and it prefers Side.RIGHT so it sits beside rather than over.
        Menu more = new Menu();
        more.addItem("Ultra+");
        more.addItem("Ridiculous");
        more.onItemActivated.connect(item -> report.setText("chose " + item.getText()));
        stage.append(more);

        quality.getMenu().addSubmenu("More...", more);

        UIElement canvas = new UIElement();
        canvas.addClass("mn-canvas");
        UIText hint = new UIText("right-click me");
        hint.addClass("label");
        hint.setHitTest(false);
        canvas.append(hint);
        stage.append(canvas);

        Menu context = new Menu();
        context.addItem("Add node");
        context.addItem("Paste");
        context.addItem("Select all");
        context.onItemActivated.connect(item -> report.setText(item.getText() + " (context)"));
        stage.append(context);

        canvas.onMouseDown.attachListener((el, event) -> {
            if (event.getButtonId() != CgMouseCodes.RIGHT_BUTTON) return;
            var pos = event.getPosition();
            var at = AnchoredPlacement.pointerToRoot(canvas.document(), pos.x(), pos.y());
            // NO invoker: an invoker is spared by light dismiss, which is what a toggle button needs and
            // what a context menu must not have. Naming the canvas here made the whole canvas unable to
            // dismiss the menu, so left-clicking the area you had just right-clicked did nothing.
            context.showAt(at.x(), at.y(), null);
            report.setText(String.format("context menu at %.0f, %.0f", at.x(), at.y()));
        }, false, false);
    }

    private void splitViewPage(UIElement pane) {
        SplitView split = new SplitView();
        split.addClass("split-demo");
        // Percentages here are 0..100, not 0..1 — matching LDLib2's 5..95 defaults.
        split.setPercentage(40f).setLimits(15f, 85f);

        split.first().addClass("pane-a");
        UIText left = new UIText("first pane");
        left.addClass("label");
        split.first().append(left);

        // Splits nest, and the nested one is where a divider-drag bug would show first.
        SplitView nested = new SplitView();
        nested.setOrientation(SplitView.Orientation.VERTICAL);
        nested.first().addClass("pane-b");
        nested.second().addClass("pane-c");
        UIText top = new UIText("nested top");
        top.addClass("label");
        UIText bottom = new UIText("nested bottom");
        bottom.addClass("label");
        nested.first().append(top);
        nested.second().append(bottom);
        split.second().append(nested);

        pane.append(split);
    }

    private void tabViewPage(UIElement pane) {
        TabView nested = new TabView();
        nested.addClass("nested-tabs");
        nested.setTabSide(TabView.TabSide.TOP);
        for (String name : new String[]{"one", "two", "three"}) {
            UIElement filler = new UIElement();
            filler.addClass("pane-filler");
            UIText label = new UIText("pane " + name);
            label.addClass("label");
            filler.append(label);
            nested.addTab(name).content().append(filler);
        }
        pane.append(nested);
    }

    private void elementPage(UIElement pane) {
        UIElement flat = new UIElement();
        flat.addClass("box");
        flat.addClass("box-flat");

        UIElement rounded = new UIElement();
        rounded.addClass("box");
        rounded.addClass("box-round");

        UIElement sprite = new UIElement();
        sprite.addClass("box");
        sprite.addClass("box-sprite");

        pane.append(row(slot("colour"), flat));
        pane.append(row(slot("border-radius"), rounded));
        pane.append(row(slot("9-slice"), sprite));
        pane.append(row(slot("radius, no bg"), box("box-none-round")));
        pane.append(row(slot("border only"), box("box-border-only")));
    }

    private UIElement box(String cssClass) {
        UIElement element = new UIElement();
        element.addClass("box");
        element.addClass(cssClass);
        return element;
    }

    /**
     * Everything here is driven from the scene stylesheet's {@code .tf-*} rules — no Java transform
     * calls at all, which is the point: {@code transform} is a cascading property like any other.
     *
     * <p>Each row holds a real {@link Button}, so the transformed widgets stay clickable. That is the
     * thing worth checking by hand: click the rotated one and the scaled one and confirm the counter
     * moves, because rendering and hit-testing derive their matrices from separate code paths and a
     * disagreement between them looks perfectly fine on screen.</p>
     */
    private void transformPage(UIElement pane) {
        pane.append(row(slot("none"), transformDemo("tf-none")));
        pane.append(row(slot("scale(1.6)"), transformDemo("tf-scale")));
        pane.append(row(slot("origin 0 0"), transformDemo("tf-origin")));
        pane.append(row(slot("rotate(-8deg)"), transformDemo("tf-rotate")));
        pane.append(row(slot("skewX(20deg)"), transformDemo("tf-skew")));
        pane.append(row(slot("translate + scale"), transformDemo("tf-chain")));
        pane.append(row(slot("scale + translate"), transformDemo("tf-chain-rev")));
        pane.append(row(slot("on :hover"), transformDemo("tf-hover")));
    }

    /**
     * The edge antialiasing {@code CG_QUAD_EDGE_*} gives a rotated quad, on every quad material at once:
     * a nine-slice sprite button (the ore theme), a {@code linear-gradient} box and a flat box.
     */
    private void edgesPage(UIElement pane) {
        pane.append(row(bigTransformDemo("tf-rotate", "rotate"), bigTransformDemo("tf-skew", "skew")));
        UIElement gradient = new UIElement();
        gradient.addClass("tf-big-gradient");
        gradient.addClass("tf-rotate");
        UIElement flat = new UIElement();
        flat.addClass("tf-big-flat");
        flat.addClass("tf-skew");
        pane.append(row(gradient, flat));
        // Rounded and bordered: the SDF material, whose ramp used to be cut off by its own quad.
        UIElement rounded = new UIElement();
        rounded.addClass("tf-big-rounded");
        rounded.addClass("tf-rotate");
        UIElement roundedSkew = new UIElement();
        roundedSkew.addClass("tf-big-rounded");
        roundedSkew.addClass("tf-skew");
        pane.append(row(rounded, roundedSkew));
        // Rounded overflow, rotated and skewed, one clip inside another: each fill overhangs its box, so the
        // clips -- each box's shape less its border -- are all that shapes it.
        pane.append(row(clippedDemo("tf-rotate"), clippedDemo("tf-skew")));
    }

    private UIElement clippedDemo(String transform) {
        UIElement innerFill = new UIElement();
        innerFill.addClass("tf-clip-inner-fill");
        UIElement inner = new UIElement();
        inner.addClass("tf-clip-inner");
        inner.append(innerFill);
        UIElement fill = new UIElement();
        fill.addClass("tf-clip-fill");
        fill.append(inner);
        UIElement outer = new UIElement();
        outer.addClass("tf-clip-outer");
        outer.addClass(transform);
        outer.append(fill);
        return outer;
    }

    private UIElement bigTransformDemo(String cssClass, String label) {
        Button button = new Button(label);
        button.addClass(cssClass);
        button.addClass("tf-big");
        return button;
    }

    private UIElement transformDemo(String cssClass) {
        Button button = new Button("click");
        button.addClass(cssClass);
        button.attachListener(() -> buttonClicks++);
        return button;
    }

    /**
     * 6.1.6 — the multi-line editor.
     *
     * <p><b>What to look for.</b> Type a few words and press Ctrl+Z: the whole run disappears in one
     * step rather than one character at a time, because a run of keystrokes composes into a single
     * {@code ChangeSet}. Pause for half a second mid-sentence and the pause becomes an undo boundary.
     * Backspace ends a run of typing rather than joining it.</p>
     *
     * <p>Hold a column and press Down through the short line: the caret comes back out at the column it
     * started from rather than being dragged inward — the {@code preferredColumn} every editor keeps and
     * every naive one forgets.</p>
     *
     * <p>The document is 400 lines and only the visible ones exist as elements. Scroll and watch the
     * realised count in the status line stay flat.</p>
     */
    private void editorPage(UIElement pane) {
        TextEditor editor = new TextEditor(join(JAVA_SAMPLE));
        editor.addClass("ed");
        // The built-in lexer, not tree-sitter: the harness must build without the local fork, and this is
        // the same fallback a platform whose native will not load gets. The tree-sitter backend has its
        // own suite in :language, against the real Java grammar.
        editor.setTokenizer(KeywordTokenizer.java());
        // The tokenizer colours it; the Language tells the editor how to EDIT it -- comment
        // tokens, bracket pairs. Two different questions about the same language.
        editor.setLanguage(com.crystalgui.text.syntax.Language.java());

        UIText status = new UIText("");
        status.addClass("ed-status");
        Runnable refresh = () -> status.setText("caret " + editor.caretPoint()
                + "   carets " + editor.caretCount()
                + "   sel " + editor.getSelectedText().length()
                + "   matches " + (editor.matchCount() == 0 ? "-"
                        : editor.currentMatchNumber() + "/" + editor.matchCount())
                + "   lines " + editor.buffer().lineCount()
                + "   undo " + editor.buffer().undoDepth());
        editor.onSelectionChanged.connect(refresh::run);
        editor.onChanged.connect(text -> refresh.run());
        editor.onWindowChanged.connect(refresh::run);
        refresh.run();

        // ── Language toggle ────────────────────────────────────────────────────────────────────
        // Two samples, because the two tokenizers know different words: GLSL's `vec4` and `uniform`
        // are not Java's, and switching between them is the quickest way to see that the editor itself
        // knows about neither -- it asks for named ranges and the sheet colours them.
        Button javaButton = new Button("Java");
        Button glslButton = new Button("GLSL");
        javaButton.addClass("ed-lang");
        glslButton.addClass("ed-lang");
        javaButton.addClass("ed-lang-on");
        javaButton.attachListener(() -> {
            editor.setTokenizer(KeywordTokenizer.java());
            editor.setLanguage(com.crystalgui.text.syntax.Language.java());
            editor.setText(join(JAVA_SAMPLE));
            javaButton.addClass("ed-lang-on");
            glslButton.removeClass("ed-lang-on");
            refresh.run();
        });
        glslButton.attachListener(() -> {
            editor.setTokenizer(KeywordTokenizer.glsl());
            editor.setLanguage(com.crystalgui.text.syntax.Language.glsl());
            editor.setText(join(GLSL_SAMPLE));
            glslButton.addClass("ed-lang-on");
            javaButton.removeClass("ed-lang-on");
            refresh.run();
        });

        // ── Find and replace ───────────────────────────────────────────────────────────────────
        TextField findField = new TextField();
        findField.addClass("ed-find");
        TextField replaceField = new TextField();
        replaceField.addClass("ed-find");
        Button next = new Button("Next");
        Button previous = new Button("Prev");
        Button replaceAll = new Button("Replace all");

        findField.attachListener(query -> {
            editor.find(query, false);
            refresh.run();
        });
        next.attachListener(() -> {
            editor.findNext();
            refresh.run();
        });
        previous.attachListener(() -> {
            editor.findPrevious();
            refresh.run();
        });
        replaceAll.attachListener(() -> {
            editor.replaceAll(replaceField.getText());
            refresh.run();
        });

        // ── Soft wrap ──────────────────────────────────────────────────────────────────────────
        // The sample carries one deliberately over-long line so the effect is visible without typing.
        // Watch the GUTTER: a wrapped row keeps ONE number, and the continuations are blank -- numbering
        // them would report line counts the file does not have.
        Button wrapButton = new Button("Soft wrap");
        wrapButton.addClass("ed-lang");
        wrapButton.attachListener(() -> {
            editor.setSoftWrap(!editor.isSoftWrap());
            if (editor.isSoftWrap()) wrapButton.addClass("ed-lang-on");
            else wrapButton.removeClass("ed-lang-on");
            refresh.run();
        });

        Button indentButton = new Button("Wrap indent: same");
        indentButton.addClass("ed-lang");
        indentButton.attachListener(() -> {
            com.crystalgui.text.wrap.WrapIndent next2 = switch (editor.getWrapIndent()) {
                case NONE -> com.crystalgui.text.wrap.WrapIndent.SAME;
                case SAME -> com.crystalgui.text.wrap.WrapIndent.INDENT;
                case INDENT -> com.crystalgui.text.wrap.WrapIndent.DEEP_INDENT;
                case DEEP_INDENT -> com.crystalgui.text.wrap.WrapIndent.NONE;
            };
            editor.setWrapIndent(next2);
            indentButton.setText("Wrap indent: " + next2.name().toLowerCase(java.util.Locale.ROOT));
            refresh.run();
        });

        pane.append(row(slot("language"), javaButton, glslButton,
                hint("the editor knows neither -- it publishes capture names and the sheet colours them")));
        // ── §G view decorations ────────────────────────────────────────────────────────────────
        Button guidesButton = new Button("Indent guides");
        guidesButton.addClass("ed-lang");
        guidesButton.attachListener(() -> {
            editor.setIndentGuidesVisible(!editor.isIndentGuidesVisible());
            if (editor.isIndentGuidesVisible()) guidesButton.addClass("ed-lang-on");
            else guidesButton.removeClass("ed-lang-on");
        });

        Button wsButton = new Button("Whitespace: none");
        wsButton.addClass("ed-lang");
        wsButton.attachListener(() -> {
            com.crystalgui.text.view.RenderWhitespace next2 = switch (editor.getRenderWhitespace()) {
                case NONE -> com.crystalgui.text.view.RenderWhitespace.BOUNDARY;
                case BOUNDARY -> com.crystalgui.text.view.RenderWhitespace.TRAILING;
                case TRAILING -> com.crystalgui.text.view.RenderWhitespace.ALL;
                case ALL -> com.crystalgui.text.view.RenderWhitespace.NONE;
            };
            editor.setRenderWhitespace(next2);
            wsButton.setText("Whitespace: " + next2.name().toLowerCase(java.util.Locale.ROOT));
        });

        Button rulerButton = new Button("Rulers: off");
        rulerButton.addClass("ed-lang");
        rulerButton.attachListener(() -> {
            boolean on = editor.getRulers().length > 0;
            editor.setRulers(on ? new int[0] : new int[] { 80, 100 });
            rulerButton.setText(on ? "Rulers: off" : "Rulers: 80, 100");
        });

        Button pastEndButton = new Button("Scroll past end: on");
        pastEndButton.addClass("ed-lang");
        pastEndButton.addClass("ed-lang-on");
        pastEndButton.attachListener(() -> {
            editor.setScrollBeyondLastLine(!editor.isScrollBeyondLastLine());
            boolean on = editor.isScrollBeyondLastLine();
            pastEndButton.setText("Scroll past end: " + (on ? "on" : "off"));
            if (on) pastEndButton.addClass("ed-lang-on");
            else pastEndButton.removeClass("ed-lang-on");
        });

        pane.append(row(slot("wrap"), wrapButton, indentButton,
                hint("a VIEW setting -- the document is byte-identical either way, and this is not undoable")));
        pane.append(row(slot("view"), guidesButton, wsButton, rulerButton, pastEndButton,
                hint("guides run through blank lines; boundary whitespace skips lone spaces")));
        pane.append(row(slot("editor"), editor));
        pane.append(row(slot(""), status));
        pane.append(row(slot("find"), findField, previous, next, replaceField, replaceAll,
                hint("replace all is ONE undo step")));
        pane.append(row(slot(""), hint("Alt+Click adds a caret - type at several at once, then one Ctrl+Z")));
        pane.append(row(slot(""), hint("Ctrl+Arrow and Ctrl+Backspace by word; Home toggles indent/col 0")));
        pane.append(row(slot(""), hint("Tab indents a selection, Shift+Tab outdents; Enter keeps the indent")));
        pane.append(row(slot(""), hint("Put the caret on a bracket to match it; drag the corner to resize")));
        pane.append(row(slot(""), hint("Ctrl+D next occurrence; Ctrl+Alt+Up/Down caret above/below; Ctrl+/ comment")));
        pane.append(row(slot(""), hint("Alt+Up/Down move line; Shift+Alt+Up/Down duplicate; Ctrl+Shift+K delete")));
        pane.append(row(slot(""), hint("Soft wrap: Up/Down follow VISUAL rows, Home/End the visual line")));
        pane.append(row(slot(""), hint("Ctrl+= / Ctrl+- zoom, Ctrl+0 resets -- the size pops up at the bottom")));
    }

    private static String join(String[] lines) {
        StringBuilder out = new StringBuilder();
        for (String line : lines) out.append(line).append('\n');
        return out.toString();
    }

    /** Deliberately ordinary Java: keywords, types, a call, a string, a number and both comment forms. */
    private static final String[] JAVA_SAMPLE = {
            "// P6.1.7 - the code editor. Everything here is highlighted by",
            "// KeywordTokenizer; :language does the same with a real parse.",
            "package com.crystalgui.demo;",
            "",
            "/* A block comment, which spans",
            "   more than one line on purpose. */",
            "public final class Shader {",
            "",
            "    private static final int MAX_PASSES = 8;",
            "    private final String name;",
            "",
            "    // One deliberately over-long line, so soft wrap has something to do: this comment keeps going well past any sensible column limit and will fold onto several visual rows once the toggle above is on, while remaining exactly one line in the document.",
            "",
            "    public Shader(String name) {",
            "        this.name = name;",
            "    }",
            "",
            "    public boolean compile(int passes) {",
            "        if (passes > MAX_PASSES) {",
            "            return false;",
            "        }",
            "        for (int i = 0; i < passes; i++) {",
            "            emit(\"pass \" + i);",
            "        }",
            "        return true;",
            "    }",
            "",
            "    void emit(String line) {",
            "        // try Alt+Click on a few of these lines at once",
            "    }",
            "}",
    };

    /** GLSL, because it is what the shader graph will actually edit. */
    private static final String[] GLSL_SAMPLE = {
            "// GLSL - the language the node graph will generate.",
            "#version 330 core",
            "",
            "#pragma cg_use quad",
            "",
            "uniform sampler2D _MainTex;",
            "uniform vec4 _Color;",
            "uniform float _Time;",
            "",
            "in vec2 uv;",
            "out vec4 fragColor;",
            "",
            "/* A tapered edge, the same maths the",
            "   curve renderer uses for a stroke. */",
            "float coverage(float dist, float feather) {",
            "    return 1.0 - smoothstep(-feather, feather, dist);",
            "}",
            "",
            "void main() {",
            "    vec4 base = texture(_MainTex, uv);",
            "    float wave = sin(uv.x * 12.0 + _Time * 2.0) * 0.5 + 0.5;",
            "    vec3 tint = mix(base.rgb, _Color.rgb, wave);",
            "    if (base.a < 0.01) {",
            "        discard;",
            "    }",
            "    fragColor = vec4(tint, base.a);",
            "}",
    };

    /**
     * {@code CgUiPaintContext.curve()} — Bézier strokes as an ordinary painting capability, available
     * to any element's {@code paintSelf} exactly as {@code fillRect} is.
     *
     * <p>The renderer itself is covered against a raw GL surface by the harness's
     * {@code curve-renderer-test} scene. What this page covers is what the UI layer adds:</p>
     * <ul>
     *   <li><b>Pose</b> — strokes are placed in element-local coordinates. Were the {@code PoseStack}
     *       not applied they would land at raw screen coordinates, which reads as a layout bug rather
     *       than a missing matrix, and would drift with {@code uiScale} rather than being obviously
     *       wrong at 1.</li>
     *   <li><b>Interleaving</b> — the load-bearing row. Quads and curves use different materials and
     *       different instance buffers, so alternating them forces a material switch each way, and
     *       painter's order has to survive it. A switch that failed to flush the outgoing path would
     *       draw geometry in material order instead of submission order, putting every stroke on top.</li>
     *   <li><b>Layer opacity</b> — {@code _LayerOpacity} is a material property, so it must be applied
     *       to {@code gui_curve.shader} in its own right; a curve that stayed opaque inside
     *       {@code withLayerOpacity} would mean it was only ever synced onto the quad material.</li>
     * </ul>
     */
    private void curvePage(UIElement pane) {
        // More rows than a pane can hold, deliberately — this is the one page where the interesting
        // cases are visual rather than interactive, so it is worth showing all of them and scrolling.
        ScrollerView scroll = new ScrollerView();
        scroll.addClass("curve-scroll");
        pane.append(scroll);

        for (CurveCanvas.Mode mode : CurveCanvas.Mode.values()) {
            scroll.append(row(slot(mode.label), new CurveCanvas(mode)));
        }
    }

    /**
     * A plain {@link UIElement} that paints strokes through {@code ctx.curve()} in its own local
     * space — deliberately not a new widget, since the point is that no setup and no material
     * handling is required of the caller.
     */
    private static final class CurveCanvas extends UIElement {

        private enum Mode {
            BASICS("basics", ""),
            CAPS("caps", ""),
            FEATHER("feather", ""),
            WIDTH("width", ""),
            GRADIENT("gradient", ""),
            NEON("neon", ""),
            CUBIC("cubic", ""),
            WAVE("wave", ""),
            RIBBON("ribbon", "curve-mid"),
            NODE_GRAPH("node wires", "curve-mid"),
            FAN("fan", "curve-tall"),
            SPIRAL("spiral", "curve-tall"),
            LISSAJOUS("lissajous", "curve-tall"),
            INTERLEAVED("over/under", ""),
            OPACITY("layer opacity", "");

            final String label;
            /** Extra height class, or empty for the default 74px canvas. */
            final String sizeClass;

            Mode(String label, String sizeClass) {
                this.label = label;
                this.sizeClass = sizeClass;
            }
        }

        private final Mode mode;

        CurveCanvas(Mode mode) {
            this.mode = mode;
            addClass("curve-canvas");
            if (!mode.sizeClass.isEmpty()) addClass(mode.sizeClass);
            setFocusPolicy(FocusPolicy.NONE);
        }

        @Override
        public void paintContent(CgUiPaintContext ctx, Box box) {
            super.paintContent(ctx, box);
            // ZERO, not box.x()/box.y(). BoxPainter poses every box in its OWN space, so the origin
            // the strokes are offset from is already this canvas's top-left -- and it stays correct
            // while the page scrolls, with no scroll-aware code here, because the pose carries the
            // scroll too. Adding the box's own offset on top shifted every stroke right by however
            // far along its row the canvas sat, which draws a perfectly correct picture in the wrong
            // place and reads as the strokes overflowing their canvas.
            float x = 0f;
            float y = 0f;
            // The frame clock render() hands the pipeline, so a capture at frame N is one picture.
            float t = CgRenderPipeline.getInstance().getFrameData().timeSecs;

            switch (mode) {
                case BASICS -> paintBasics(ctx, x, y);
                case CAPS -> paintCaps(ctx, x, y);
                case FEATHER -> paintFeather(ctx, x, y);
                case WIDTH -> paintWidth(ctx, x, y);
                case GRADIENT -> paintGradient(ctx, x, y);
                case NEON -> paintNeon(ctx, x, y, t);
                case CUBIC -> paintCubic(ctx, x, y, t);
                case WAVE -> paintWave(ctx, x, y, t);
                case RIBBON -> paintRibbon(ctx, x, y, t);
                case NODE_GRAPH -> paintNodeGraph(ctx, x, y, t);
                case FAN -> paintFan(ctx, x, y, t);
                case SPIRAL -> paintSpiral(ctx, x, y, t);
                case LISSAJOUS -> paintLissajous(ctx, x, y, t);
                case INTERLEAVED -> paintInterleaved(ctx, x, y);
                case OPACITY -> paintOpacity(ctx, x, y);
            }
            ctx.flush();
        }

        /** Line, arc, taper, gradient — the four things one stroke can vary. */
        private void paintBasics(CgUiPaintContext ctx, float x, float y) {
            ctx.curve().line(x + 12, y + 16, x + 96, y + 16).width(1.5f).color(0xFFE0E0E0).submit();
            ctx.curve().from(x + 12, y + 60).via(x + 54, y + 28).to(x + 96, y + 60)
                    .width(2.5f).color(0xFF6CC4FF).submit();
            ctx.curve().from(x + 120, y + 58).via(x + 162, y + 22).to(x + 204, y + 58)
                    .width(7f, 1f).color(0xFF6CC4FF).submit();
            ctx.curve().from(x + 228, y + 58).via(x + 270, y + 22).to(x + 312, y + 58)
                    .width(4f).colors(0xFFFF4D6D, 0xFF4DFFC3).submit();
            ctx.curve().from(x + 336, y + 20).via(x + 336, y + 62).to(x + 404, y + 50)
                    .width(3f).colors(0xFFFFC24D, 0xFFB36CFF).submit();
        }

        /**
         * The three caps at a width where they actually differ, against ticks at the nominal
         * endpoints — the diagonal group matters because a square cap's corner reaches
         * halfWidth*sqrt(2) along one axis only when the stroke is not axis-aligned.
         */
        private void paintCaps(CgUiPaintContext ctx, float x, float y) {
            int[] caps = { CgVectorRenderer.CAP_BUTT, CgVectorRenderer.CAP_ROUND, CgVectorRenderer.CAP_SQUARE };
            for (int i = 0; i < caps.length; i++) {
                float cy = y + 16 + i * 21;
                ctx.curve().line(x + 40, cy, x + 150, cy).width(7f).cap(caps[i]).color(0xFFFFC24D).submit();
                for (int e = 0; e < 2; e++) {
                    float tx = x + 40 + e * 110;
                    ctx.curve().line(tx, cy - 12, tx, cy + 12).width(0.5f).color(0xFF7A7A7A).submit();
                }
            }
            for (int i = 0; i < caps.length; i++) {
                float cx = x + 210 + i * 62;
                ctx.curve().line(cx, y + 16, cx + 34, y + 50)
                        .width(9f).cap(caps[i]).color(0xFFFFC24D).submit();
            }
            ctx.curve().line(x + 330, y + 16, x + 330, y + 58).width(0.5f).color(0xFF3A3F48).submit();
        }

        /** Feather sweep: a hard edge at 0, a soft glow by 12. Same width and colour throughout. */
        private void paintFeather(CgUiPaintContext ctx, float x, float y) {
            for (int i = 0; i < 8; i++) {
                float cx = x + 26 + i * 50;
                float feather = i * 1.7f;
                ctx.curve().line(cx, y + 14, cx, y + 60)
                        .width(6f).feather(feather).cap(CgVectorRenderer.CAP_ROUND)
                        .color(0xFF6CC4FF).submit();
            }
        }

        /** Width sweep, hairline to slab — the range a graph editor actually spans. */
        private void paintWidth(CgUiPaintContext ctx, float x, float y) {
            for (int i = 0; i < 9; i++) {
                float cx = x + 10 + i * 46;
                float w = 0.4f + i * 1.5f;
                ctx.curve().from(cx, y + 58).via(cx + 12, y + 18).to(cx + 24, y + 58)
                        .width(w).cap(CgVectorRenderer.CAP_ROUND).color(hue(i / 9f)).submit();
            }
        }

        /** Gradients, including taper and gradient together — near-free once the record exists. */
        private void paintGradient(CgUiPaintContext ctx, float x, float y) {
            for (int i = 0; i < 5; i++) {
                float cy = y + 14 + i * 12;
                float h0 = i / 5f;
                ctx.curve().from(x + 16, cy).via(x + 210, cy + (i - 2) * 9).to(x + 404, cy)
                        .width(1f + i * 1.6f, 1f + (4 - i) * 1.6f)
                        .colors(hue(h0), hue(h0 + 0.4f))
                        .cap(CgVectorRenderer.CAP_ROUND)
                        .submit();
            }
        }

        /**
         * Neon: the same curve three times — wide and heavily feathered for the halo, then medium,
         * then a thin bright core. Feather is what makes this work at all; without a per-instance
         * softness the halo would be a hard-edged slab.
         */
        private void paintNeon(CgUiPaintContext ctx, float x, float y, float t) {
            float bow = 22f + 10f * (float) Math.sin(t * 1.3f);
            int tint = hue(t * 0.12f);
            float[] widths = { 13f, 6f, 2f };
            float[] feathers = { 16f, 7f, 1.5f };
            int[] alphas = { 0x30, 0x60, 0xFF };
            for (int pass = 0; pass < 3; pass++) {
                int argb = (alphas[pass] << 24) | (tint & 0x00FFFFFF);
                ctx.curve().from(x + 20, y + 52).via(x + 210, y + 52 - bow * 2f).to(x + 400, y + 52)
                        .width(widths[pass]).feather(feathers[pass])
                        .cap(CgVectorRenderer.CAP_ROUND).color(argb).submit();
            }
        }

        /** Cubics — one submit() each, split CPU-side into 1-4 quadratics. Gradient must not band. */
        private void paintCubic(CgUiPaintContext ctx, float x, float y, float t) {
            for (int i = 0; i < 3; i++) {
                float cx = x + 16 + i * 136;
                float wob = 26f + 16f * (float) Math.sin(t * 0.9f + i * 1.1f);
                ctx.curve()
                        .cubic(cx, y + 38,
                                cx + 40, y + 38 - wob,
                                cx + 84, y + 38 + wob,
                                cx + 124, y + 38)
                        .width(6f, 2f)
                        .colors(hue(i / 3f), hue(i / 3f + 0.35f))
                        .cap(CgVectorRenderer.CAP_ROUND)
                        .submit();
            }
        }

        /** A travelling sine, built from a run of quadratics — the polyline case. */
        private void paintWave(CgUiPaintContext ctx, float x, float y, float t) {
            final int segments = 34;
            float span = 396f;
            float midY = y + 37;
            for (int i = 0; i < segments; i++) {
                float t0 = i / (float) segments;
                float t1 = (i + 1) / (float) segments;
                float x0 = x + 12 + span * t0;
                float x1 = x + 12 + span * t1;
                float y0 = midY + waveAt(t0, t);
                float y1 = midY + waveAt(t1, t);
                float xm = (x0 + x1) * 0.5f;
                // Control point placed so the quadratic passes THROUGH the true midpoint of the sine
                // rather than chording between the endpoints: B = 2*M - (P0 + P2)/2. Visibly smoother
                // at this segment count, and the same construction the lissajous row uses.
                float trueMidY = midY + waveAt((t0 + t1) * 0.5f, t);
                float ym = 2f * trueMidY - (y0 + y1) * 0.5f;
                ctx.curve().from(x0, y0).via(xm, ym).to(x1, y1)
                        .width(3.5f).colors(hue(t0 + t * 0.1f), hue(t1 + t * 0.1f))
                        .cap(CgVectorRenderer.CAP_ROUND).submit();
            }
        }

        private static float waveAt(float u, float t) {
            return (float) (Math.sin(u * Math.PI * 4 + t * 2.2) * 20 + Math.sin(u * Math.PI * 7 - t * 1.4) * 7);
        }

        /** Stacked tapered arcs — taper plus gradient plus overlap, the "does it look good" row. */
        private void paintRibbon(CgUiPaintContext ctx, float x, float y, float t) {
            final int strands = 14;
            for (int i = 0; i < strands; i++) {
                float p = i / (float) (strands - 1);
                float phase = t * 0.7f + p * 2.4f;
                float lift = 34f + 26f * (float) Math.sin(phase);
                ctx.curve()
                        .from(x + 18, y + 84)
                        .via(x + 210, y + 84 - lift * 2f)
                        .to(x + 402, y + 84)
                        .width(6f * (1f - p) + 0.6f, 0.6f + 6f * p)
                        .colors(hue(p * 0.5f + t * 0.05f), hue(p * 0.5f + 0.3f + t * 0.05f))
                        .cap(CgVectorRenderer.CAP_ROUND)
                        .submit();
            }
        }

        /**
         * A mock node graph — the actual thing 6.2 is being built toward. Ports are quads, wires are
         * cubics tinted from source port to destination port, which is the standard idiom and the
         * reason gradient was worth having in the instance record at all.
         */
        private void paintNodeGraph(CgUiPaintContext ctx, float x, float y, float t) {
            float[] srcY = { y + 26, y + 52, y + 78 };
            float[] dstY = { y + 34, y + 70 };
            int[] srcColor = { 0xFFFF6B6B, 0xFF6CC4FF, 0xFFFFC24D };
            int[] dstColor = { 0xFF4DFFC3, 0xFFB36CFF };

            float srcX = x + 78;
            float dstX = x + 342;

            // Node bodies first, so the wires cross over them — submission order is the z-order.
            ctx.fillRect(x + 16, y + 14, 62, 78, 0xFF2E333D);
            ctx.fillRect(x + 342, y + 22, 62, 60, 0xFF2E333D);

            for (int s = 0; s < srcY.length; s++) {
                for (int d = 0; d < dstY.length; d++) {
                    if ((s + d) % 2 == 1) continue;   // a subset, so the wires stay readable
                    float sag = 10f * (float) Math.sin(t * 1.1f + s * 0.8f + d);
                    ctx.curve()
                            .cubic(srcX, srcY[s],
                                    srcX + 96, srcY[s] + sag,
                                    dstX - 96, dstY[d] - sag,
                                    dstX, dstY[d])
                            .width(2.5f)
                            .colors(srcColor[s], dstColor[d])
                            .cap(CgVectorRenderer.CAP_ROUND)
                            .submit();
                }
            }
            ctx.flush();

            for (int s = 0; s < srcY.length; s++) ctx.fillRect(srcX - 4, srcY[s] - 4, 8, 8, srcColor[s]);
            for (int d = 0; d < dstY.length; d++) ctx.fillRect(dstX - 4, dstY[d] - 4, 8, 8, dstColor[d]);
        }

        /** Rotating spokes, each a tapered gradient arc. The harness scene's crowd-pleaser. */
        private void paintFan(CgUiPaintContext ctx, float x, float y, float t) {
            float cx = x + 210;
            float cy = y + 75;
            final int spokes = 18;
            float radius = 58f + 8f * (float) Math.sin(t * 0.9f);
            for (int i = 0; i < spokes; i++) {
                double a = (i / (double) spokes) * Math.PI * 2 + t * 0.35;
                float ex = cx + (float) Math.cos(a) * radius;
                float ey = cy + (float) Math.sin(a) * radius;
                float bx = cx + (float) Math.cos(a - 0.5) * radius * 0.55f;
                float by = cy + (float) Math.sin(a - 0.5) * radius * 0.55f;
                ctx.curve().from(cx, cy).via(bx, by).to(ex, ey)
                        .width(4.5f, 1f)
                        .colors(0xFFFFFFFF, hue(i / (float) spokes + t * 0.08f))
                        .cap(CgVectorRenderer.CAP_ROUND)
                        .submit();
            }
        }

        /** An expanding spiral in quadratic segments — many strokes, one draw call. */
        private void paintSpiral(CgUiPaintContext ctx, float x, float y, float t) {
            float cx = x + 210;
            float cy = y + 75;
            final int steps = 70;
            float turns = 3.2f;
            float maxR = 66f;
            float spin = t * 0.5f;
            for (int i = 0; i < steps; i++) {
                float u0 = i / (float) steps;
                float u1 = (i + 1) / (float) steps;
                double a0 = u0 * turns * Math.PI * 2 + spin;
                double a1 = u1 * turns * Math.PI * 2 + spin;
                double am = (a0 + a1) * 0.5;
                float r0 = maxR * u0, r1 = maxR * u1, rm = maxR * (u0 + u1) * 0.5f;
                // Radius of the control point pushed out so the quadratic bulges onto the arc rather
                // than chording it — the same trick the wave row uses, in polar form.
                float bulge = 1f / (float) Math.cos((a1 - a0) * 0.5);
                ctx.curve()
                        .from(cx + (float) Math.cos(a0) * r0, cy + (float) Math.sin(a0) * r0)
                        .via(cx + (float) Math.cos(am) * rm * bulge, cy + (float) Math.sin(am) * rm * bulge)
                        .to(cx + (float) Math.cos(a1) * r1, cy + (float) Math.sin(a1) * r1)
                        .width(0.8f + 5f * u0, 0.8f + 5f * u1)
                        .colors(hue(u0 + t * 0.1f), hue(u1 + t * 0.1f))
                        .cap(CgVectorRenderer.CAP_ROUND)
                        .submit();
            }
        }

        /** A Lissajous figure — long, self-crossing, and the best look at antialiasing quality. */
        private void paintLissajous(CgUiPaintContext ctx, float x, float y, float t) {
            float cx = x + 210;
            float cy = y + 75;
            float rx = 180f, ry = 60f;
            final int steps = 90;
            float phase = t * 0.4f;
            for (int i = 0; i < steps; i++) {
                float u0 = i / (float) steps;
                float u1 = (i + 1) / (float) steps;
                float um = (u0 + u1) * 0.5f;
                float x0 = cx + rx * lx(u0, phase), y0 = cy + ry * ly(u0, phase);
                float x1 = cx + rx * lx(u1, phase), y1 = cy + ry * ly(u1, phase);
                float xm = cx + rx * lx(um, phase), ym = cy + ry * ly(um, phase);
                // Control point that makes the quadratic pass through the true midpoint:
                // B = 2*M - (P0 + P2)/2.
                ctx.curve()
                        .from(x0, y0)
                        .via(2f * xm - (x0 + x1) * 0.5f, 2f * ym - (y0 + y1) * 0.5f)
                        .to(x1, y1)
                        .width(2.6f)
                        .colors(hue(u0 * 2f + t * 0.07f), hue(u1 * 2f + t * 0.07f))
                        .cap(CgVectorRenderer.CAP_ROUND)
                        .submit();
            }
        }

        private static float lx(float u, float phase) {
            return (float) Math.sin(u * Math.PI * 2 * 3 + phase);
        }

        /** Second axis at a 2:3 ratio against {@link #lx}, drifting so the figure keeps reshaping. */
        private static float ly(float u, float phase) {
            return (float) Math.sin(u * Math.PI * 2 * 2 + phase * 0.6);
        }

        /**
         * Stroke, then a panel over it, then a stroke over that — submitted in that order, so the
         * correct result is a sandwich. Mirrored on the right so an ordering rule that only holds one
         * way round still fails visibly.
         */
        private void paintInterleaved(CgUiPaintContext ctx, float x, float y) {
            // Both strokes must cross the panel's VERTICAL span, not just its horizontal one — a
            // stroke that merely grazes the panel's edge proves nothing about ordering, because
            // "hidden behind it" and "drawn over it" look identical when they barely touch.
            ctx.curve().line(x + 12, y + 26, x + 190, y + 26).width(8f).color(0xFFFF4D6D).submit();
            ctx.fillRect(x + 70, y + 14, 70, 40, 0xFF4D8CFF);
            ctx.curve().line(x + 12, y + 44, x + 190, y + 44).width(6f).color(0xFF4DFFC3).submit();

            ctx.fillRect(x + 250, y + 14, 70, 40, 0xFF4D8CFF);
            ctx.curve().line(x + 220, y + 34, x + 400, y + 34).width(8f).color(0xFFFFC24D).submit();
        }

        /** The lower stroke and the lower bar must fade by the same amount. */
        private void paintOpacity(CgUiPaintContext ctx, float x, float y) {
            ctx.curve().line(x + 12, y + 24, x + 180, y + 24).width(6f).color(0xFF6CC4FF).submit();
            ctx.flush();
            ctx.withLayerOpacity(0.35f, () -> {
                ctx.curve().line(x + 12, y + 52, x + 180, y + 52).width(6f).color(0xFF6CC4FF).submit();
                ctx.flush();
            });

            ctx.fillRect(x + 220, y + 18, 180, 12, 0xFF6CC4FF);
            ctx.withLayerOpacity(0.35f, () -> ctx.fillRect(x + 220, y + 46, 180, 12, 0xFF6CC4FF));
        }

        /** Opaque ARGB from a hue phase — deterministic, and only ever used for demo colour. */
        private static int hue(float phase) {
            float h = (phase % 1f + 1f) % 1f;
            float r = clamp01(Math.abs(h * 6f - 3f) - 1f);
            float g = clamp01(2f - Math.abs(h * 6f - 2f));
            float b = clamp01(2f - Math.abs(h * 6f - 4f));
            return 0xFF000000 | ((int) (r * 255f) << 16) | ((int) (g * 255f) << 8) | (int) (b * 255f);
        }

        private static float clamp01(float v) {
            return v < 0f ? 0f : (v > 1f ? 1f : v);
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────────────────────────

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        // `CgPreviewRenderer` deliberately reuses the shared `CgRenderPipeline` singleton's ONE
        // `CgFrameData` rather than owning a separate one — that's what lets a Time node's preview
        // thumbnail animate for free, inheriting whatever clock the app already drives. But nothing
        // else in this scene ever touches that clock, so `timeSecs` sat at its `0f` default for the
        // whole life of the gallery: every Time-node preview (and anything downstream of it, like a
        // Multiply node fed by Time) rendered as if CG_TIME were permanently zero. Every OTHER
        // interactive harness scene that uses the pipeline sets this per frame (see
        // CgForwardRendererScene/CgAttachedBufferStressScene) — this one just never had a reason to
        // until node previews existed.
        CgRenderPipeline.getInstance().getFrameData().timeSecs = (float) frame.getElapsedTime();
        // Deferred to the first frame because attaching registers a frame ticker on the window, which
        // does not exist while the pages are being built.
        if (shaderPreviews != null && !shaderPreviewsAttached) {
            shaderPreviews.attach();
            shaderPreviewsAttached = true;
        }
        // Retried until it takes, rather than attempted once: the panel lives inside a tab page, and
        // registerTicker is idempotent, so the cheap correct thing is to keep asking until there is a
        // window to ask. One-shot attachment is a panel that silently never draws.
        if (shaderMainPreview != null && !shaderMainPreviewAttached) {
            shaderMainPreviewAttached = shaderMainPreview.attach();
        }

        // Drag the dialog's corner and the picker SCALES rather than being cropped. `resize` writes an
        // explicit width/height — that is what CSS resize means — so turning that into a scale is the
        // host's job: read the box the dialog now offers and hand the picker a multiple of its natural
        // size. Done per frame because a resize is a drag; setScale ignores an unchanged value.
        document.frame(frame.getDeltaTime(), ctx.getScreenWidth() / SCALE, ctx.getScreenHeight() / SCALE);

        // AND THE PAINT. `paintFrame()` did both; `frame()` only advances, so a scene that
        // lost this half advanced perfectly and drew nothing.
        CgUiPaintContext paintContext = CgUiPaintContext.getInstance();
        paintContext.beginFrame(ctx.getScreenWidth(), ctx.getScreenHeight());
        document.paint(paintContext);
        paintContext.endFrame();

        var context = CgUiPaintContext.getInstance();
        Tab selected = pages.getSelectedTab();
        context.text().draw().at(0, 0)
                .text(String.format("Gallery — page=%s   theme=%s   uiScale=%.2f ([ ])   clicks=%d   slider=%.0f",
                        selected == null ? "none" : selected.getText(),
                        oreOn ? "ore" : "default",
                        document.boxes().uiScale(),
                        buttonClicks,
                        continuous.getValue()))
                .font(context.getFont().atSize(14)).submit();

        // OPEN ON A NAMED PAGE, AND PHOTOGRAPH IT. `-Dcrystalgui.gallery.page=glass` with `--seconds=N`
        // turns this scene into an unattended diagnostic: a run that leaves a PNG of ONE page on disk.
        // The property prefix must be crystalgui. or crystalgraphics. -- the build forwards only those
        // two, and a differently-named flag is accepted on the command line and reaches nothing.
        //
        // Frame 5 is too early for a page whose content is a captured backdrop: the capture is taken
        // during paint, and anything animating behind it has barely moved. 90 is about a second and a
        // half in, by which point the drift has travelled and a stale capture would be obvious.
        String wanted = System.getProperty("crystalgui.gallery.page");
        if (wanted != null && frame.getFrameNumber() == 2) {
            for (int i = 0; i < pages.getTabCount(); i++) {
                if (wanted.equalsIgnoreCase(pages.getTab(i).getText())) {
                    pages.selectIndex(i);
                    break;
                }
            }
        }
        // 90 suits a page that is animating. A page whose GLYPHS stream in needs longer -- a fresh
        // font family at a new size queues its whole ASCII warm ahead of the specimen, drained at a
        // bounded rate, so a capture at 90 catches the word half-generated and reads as missing
        // letters. -Dcrystalgui.gallery.captureFrame=N waits.
        //
        // -Dcrystalgui.gallery.textLabSize=N opens the text lab at a font size, which is the only way
        // to photograph a size-dependent answer -- the stroke cap is a fraction of the em -- without a
        // hand on the slider.
        int captureFrame = wanted == null ? 5
                : Integer.getInteger("crystalgui.gallery.captureFrame", 90);
        if (frame.getFrameNumber() == captureFrame) {
            ctx.getArtifactService().requestCapture(wanted == null ? "startup" : wanted);
        }

    }

    @Override
    public void dispose() {
        // The preview pool's targets are createOwned framebuffers, so no registry sweep reaches them —
        // this is the only thing that ever frees them.
        if (shaderPreviews != null) {
            shaderPreviews.delete();
            shaderPreviews = null;
            shaderPreviewsAttached = false;
        }
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
        // BARE brackets only. The keyboard Event carries no modifier state, so this reads the live mask --
        // without it, Ctrl+Shift+[ changes uiScale and returns true, and the editor's own fold binding
        // never sees the key at all. Any accelerator built on a bracket is invisible in this scene
        // otherwise, which is exactly how it presented: the binding was correct and untestable.
        int mods = com.crystalgraphics.platform.CgPlatform.input().getCurrentModifiers();
        boolean bare = !com.crystalgraphics.platform.input.CgModifiers.hasCtrl(mods)
                && !com.crystalgraphics.platform.input.CgModifiers.hasShift(mods)
                && !com.crystalgraphics.platform.input.CgModifiers.hasAlt(mods)
                && !com.crystalgraphics.platform.input.CgModifiers.hasSuper(mods);
        if (event.pressed() && bare) {
            switch (event.key()) {
                case CgKeyCodes.KEY_RBRACKET -> {
                    setScale(Math.min(8f, document.boxes().uiScale() + 0.1f));
                    return true;
                }
                case CgKeyCodes.KEY_LBRACKET -> {
                    setScale(Math.max(0.1f, document.boxes().uiScale() - 0.1f));
                    return true;
                }
                default -> { }
            }
        }
        return document.input().consumeKeyboardEvent(event);
    }

    /**
     * {@code [} / {@code ]} step {@code uiScale} in quarters — fractional values on purpose, since
     * those are the ones that expose pixel-alignment bugs and every other cgui scene runs at a fixed
     * 2. Punctuation rather than letters or arrows: TabView owns the arrows, and a letter would be
     * swallowed by a focused TextField.
     *
     * <p>{@code init(0, 0)} forces the re-init a scale change needs — {@code init} early-returns when
     * the physical dimensions are unchanged, and they are. Same trick {@code CgUiTextScene} uses.</p>
     */
    private void setScale(float scale) {
        document.boxes().setUiScale(scale);
    }

    // ── graph page (P6.2.2 canvas + P6.2.3 nodes, ports and wires) ──────────────────────────────

    private GraphView graph;
    private UIText graphStatus;
    private Button graphCullToggle;

    /** The last "save" — the encoded document, held as bytes rather than as an object so the reload
     * genuinely goes through decode rather than handing the same instance back. */
    private Object savedGraph;

    /**
     * GLSL-ish port types for the demo, with <b>promotion</b>: a float feeds anything.
     *
     * <p>Defined here rather than in {@code core/} deliberately, and it is the point of the whole
     * {@link PortType} interface — the types a shader graph carries are GLSL's, and CrystalGUI has no
     * business knowing them. It also gives the page something only a real type system shows: a
     * {@code float -> vec3} wire is drawn as a gradient from light blue to yellow, because a wire takes
     * a colour from each end.</p>
     */
    private record GlslType(String id, int arity) implements PortType {
        @Override
        public boolean isCompatibleWith(PortType other) {
            // GLSL promotes a scalar into any vector; it does not demote. An "are these equal?" rule
            // would forbid the useful half of a shader graph.
            return arity == 1 || (other != null && id.equals(other.id()));
        }
    }

    private static final PortType T_FLOAT = new GlslType("float", 1);

    /** The same promotion rule the ports use, in the form the library and document want: a scalar feeds
     * anything. Declared once so the menu offers exactly what a drag would accept. */
    private static final com.crystalgui.graph.TypeCompatibility GALLERY_TYPES =
            (from, to) -> from.equals("float") || from.equals(to);
    private static final PortType T_VEC3 = new GlslType("vec3", 3);

    /**
     * {@code GraphView} — the pan/zoom plane from 6.2.2 with 6.2.3's nodes, ports and wires on it,
     * styled to Unity Shader Graph's spec by {@code crystalgui:graph}.
     *
     * <p><b>Five things here are visual by nature</b>, which is why the widgets' thirty-one unit tests
     * are not the whole story:</p>
     * <ul>
     *   <li><b>Wires stay attached at every zoom.</b> Endpoints are read live from each port dot's
     *       layout, so if the {@code PoseStack} and the layout ever disagreed about where a port is, a
     *       wire would visibly detach from its dot — and only at some zoom levels, which is the tell.</li>
     *   <li><b>Type colour runs from the port into the wire.</b> The {@code float -> vec3} wire into
     *       {@code Add.B} is a gradient from light blue to yellow: both ends come from the cascade, via
     *       the dot's computed {@code border-color}, so a theme owns the palette and Java owns none of
     *       it.</li>
     *   <li><b>Drag a port onto another.</b> A compatible target accepts, an incompatible one refuses —
     *       and dropping on an already-wired input <em>replaces</em> its wire rather than refusing it.</li>
     *   <li><b>Collapse a node with the chevron.</b> Unconnected ports go; wired ones stay, or the wires
     *       would end in mid-air.</li>
     *   <li><b>Zoom out a long way.</b> The wires must stay visible: stroke width is pose-scaled, so it
     *       is clamped against the zoom, and without the clamp the graph empties out.</li>
     * </ul>
     */
    // ── P6.3: the shader graph, end to end ──────────────────────────────────

    private GraphView shaderGraph;
    private TextEditor shaderSource;
    private com.crystalgui.app.shadergraph.preview.ShaderGraphPreviews shaderPreviews;
    /** P6.3.12 — the finished shader on a mesh. Right-click it for the shape menu, drag to orbit. */
    private com.crystalgui.app.shadergraph.preview.MainPreviewPanel shaderMainPreview;
    private boolean shaderMainPreviewAttached;
    private com.crystalgraphics.shadergraph.CgShaderEmitter.Result shaderLineOwners;
    private boolean shaderPreviewsAttached;

    private UIText shaderStatus;

    /**
     * The whole P6.3 stack on one page: a node graph whose document is mapped to
     * {@code CgShaderGraph}, compiled to {@code .shader} source, and shown as it is typed.
     *
     * <p>Everything visible here is the real thing rather than a mock — the same
     * {@code CgGraphCompiler} a material would use, the same emitter, and the same five built-in nodes.
     * The only step not taken is handing the source to a driver, which needs a GL context this page does
     * not own.</p>
     */
    /**
     * The colour picker on its own, so its layout can be judged without a node around it.
     *
     * <p>A live swatch and a hex readout sit beside it, because the thing worth checking is that every
     * control is a <em>view of one colour</em> — drag any slider and the ring, the square and the
     * readout must all move together.</p>
     */
    private void colorSelectorPage(UIElement pane) {
        ColorSelector picker = new ColorSelector();
        // setInitialColor, not setColor: this opens an editing session, so it sets the "original" the
        // left swatch shows and restores. setColor moves only the current colour, which would leave the
        // swatch showing the constructor's white and make the reset undo to a colour nobody chose.
        picker.setInitialColor(0xFFB00DDB);

        UIElement swatch = new UIElement();
        swatch.addClass("color-swatch");
        UIText readout = new UIText("");

        Runnable show = () -> {
            int argb = picker.getColor();
            swatch.generalStyle(g -> g.background(com.crystalgui.render.texture.CgUiRect.ofColor(argb)));
            readout.setText(String.format("#%08X   alpha %d", argb, (argb >>> 24) & 0xFF));
        };
        picker.onColorChanged.connect(c -> show.run());
        show.run();

        UIElement side = new UIElement();
        side.addClass("color-side");
        side.append(swatch);
        side.append(readout);
        side.append(hint("Every control edits ONE colour — the ring, square and sliders are all views"));
        side.append(hint("Mode changes how the channels are shown, never the colour"));

        // In a Dialog, which already owns a draggable title bar and its own position — so the picker
        // stays a plain content widget rather than growing a second identity as a window.
        Dialog window = new Dialog("Color");
        window.addClass("color-dialog");
        window.getContent().append(picker);

        Button open = new Button("open picker");
        open.addClass("canvas-btn");
        open.attachListener(() -> window.show().moveTo(40f, 90f));




        UIElement row = new UIElement();
        row.addClass("color-row");
        row.append(side);
        pane.append(row(slot("picker"), open));
        pane.append(row);
        pane.append(window);
        window.show().moveTo(40f, 90f);
    }

    private void shaderGraphPage(UIElement pane) {
        var shaderNodes = com.crystalgraphics.shadergraph.CgShaderNodeRegistry.builtins();
        var master = new com.crystalgraphics.shadergraph.CgMasterNode();

        shaderGraph = new GraphView();
        shaderGraph.addClass("graph-view");

        // The library IS the shader node set — the create menu, its search and the widget factory all
        // come from one bridge call, with no shader-specific UI code anywhere on this page.
        // ShaderNodeLibrary.of, NOT ShaderGraphBridge.asNodeLibrary: the bridge builds the type map and
        // deliberately installs no widgets -- it is kept free of the UI -- so a caller that asks it
        // directly gets a library whose COLOR and VECTOR fields have no editor at all. Not a text field
        // as a fallback: NOTHING, so a Color node draws its output port and nothing else.
        var library = com.crystalgui.app.shadergraph.ShaderNodeLibrary.of(shaderNodes);
        shaderGraph.setNodeLibrary(library, NodeWidgetFactory.of(library).build(),
                com.crystalgui.app.shadergraph.ShaderGraphBridge.GLSL_PROMOTION);

        shaderStatus = new UIText("");
        shaderStatus.addClass("canvas-status");

        shaderSource = new TextEditor();
        // `ed` is this page's code-editor look, and it is not cosmetic here: the syntax colours are
        // `.ed text::highlight(keyword)` and friends, scoped to that class. Without it the tokenizer
        // runs, publishes every range correctly, and nothing is coloured — which reads as "the
        // tokenizer is not working" when the tokenizer is fine and the selector simply never matched.
        shaderSource.addClass("ed");
        shaderSource.addClass("shader-source");
        shaderSource.setReadOnly(true);
        // The generated file IS GLSL, so it gets the GLSL tokenizer and language rather than being
        // shown as plain text — the same pair the editor page's GLSL button sets.
        shaderSource.setTokenizer(KeywordTokenizer.glsl());
        shaderSource.setLanguage(com.crystalgui.text.syntax.Language.glsl());
        // The payoff of the line map: put the caret anywhere in the generated source and the status line
        // names the NODE that emitted it. A driver reports a line in code the user never wrote, and this
        // is the lookup that turns that into somewhere to go and look.
        shaderSource.onSelectionChanged.connect(() -> {
            if (shaderLineOwners == null || shaderStatus == null) return;
            int line = shaderSource.caretPoint().row() + 1;
            String owner = shaderLineOwners.ownerOfLine(line);
            if (owner == null) return;
            var node = shaderGraph.getDocument().node(owner);
            shaderStatus.setText("line " + line + " emitted by "
                    + (node == null ? owner : node.typeId() + "  (" + owner + ")"));
        });

        Button compile = new Button("compile");
        compile.addClass("canvas-btn");
        compile.attachListener(this::recompileShaderGraph);

        Button frame = new Button("fit");
        frame.addClass("canvas-btn");
        frame.attachListener(() -> shaderGraph.fitToContent(24f));

        pane.append(row(slot("view"), compile, frame, shaderStatus));
        // Hints BEFORE the split: the split takes the pane's slack, so anything after it is pushed off
        // the bottom.
        pane.append(row(slot(""), hint("Space opens the create menu - all five built-in nodes are in it")));
        pane.append(row(slot(""), hint("Wire into Output's BaseColor and watch the source recompile")));

        // A real SplitView rather than a fixed-width column, so the divider can be dragged: a generated
        // shader is sometimes the thing you are reading and sometimes just confirmation, and which one
        // it is changes minute to minute. 6.1's widget already owns the drag, the clamping and the
        // cursor — this only has to say where to start and how far it may go.
        SplitView split = new SplitView();
        split.addClass("shader-split");
        // PERCENTAGES, 0..100 — not a 0..1 fraction. Passing 0.62 meant 0.62%, so the graph came out a
        // three-pixel sliver, and setLimits(0.2, 0.85) then capped the drag at 0.85% so it could never
        // be recovered. The name says percentage and the API means it.
        split.setPercentage(80f);
        // Either pane collapsed to nothing is a state with no way back — the divider would have no
        // width left to grab.
        split.setLimits(20f, 95f);
        split.first().append(shaderGraph);
        split.second().append(shaderSource);
        pane.append(split);

        // A starter graph: Color * Time, into the master. Small enough to read at a glance and it
        // exercises dynamic widening (vec4 * float) plus an engine builtin, so the generated source
        // shows a compiler-emitted cast rather than a straight copy.
        var colour = library.get("cg:Input/Basic/color");
        var time = library.get("cg:Input/Basic/time");
        var multiply = library.get("cg:Math/Basic/multiply");
        var outputType = library.get(ShaderGraphBridge.MASTER_TYPE);

        GraphNode colourNode = addShaderNode(library, colour, 20f, 30f);
        GraphNode timeNode = addShaderNode(library, time, 20f, 150f);
        GraphNode multiplyNode = addShaderNode(library, multiply, 240f, 60f);
        GraphNode outputNode = addShaderNode(library, outputType, 470f, 60f);

        shaderGraph.connect(colourNode.getOutputPorts().get(0), multiplyNode.getInputPorts().get(0));
        shaderGraph.connect(timeNode.getOutputPorts().get(0), multiplyNode.getInputPorts().get(1));
        shaderGraph.connect(multiplyNode.getOutputPorts().get(0), outputNode.getInputPorts().get(1));

        // Left unconnected on purpose. These are the three nodes a preview system exists to show —
        // UV's red/green gradient on a quad, Position and Normal on a sphere — and none of them needs
        // to be wired to anything for its thumbnail to be the point.
        addShaderNode(library, library.get("cg:Input/Geometry/uv"), 20f, 330f);
        addShaderNode(library, library.get("cg:Input/Geometry/position"), 240f, 330f);
        addShaderNode(library, library.get("cg:Input/Geometry/normal"), 460f, 330f);

        // Recompile whenever the graph's shape changes. Debounced only by the fact that a connection is
        // a discrete user action; a per-keystroke trigger would want real debouncing (6.3.8).
        shaderGraph.onConnectionsChanged.connect(this::recompileShaderGraph);
        recompileShaderGraph();

        // 6.3.7 — live thumbnails. Constructed here but NOT attached: attaching registers a frame
        // ticker, and there is no window yet at page-build time. render() does it on the first frame.
        shaderPreviews = new com.crystalgui.app.shadergraph.preview.ShaderGraphPreviews(
                shaderGraph, shaderNodes, master);
        // A Space dropdown changes the emitted GLSL but not the graph's shape, so onConnectionsChanged
        // never fires for it — without this the source pane silently shows the previous variant.
        shaderPreviews.onPropertyChanged.connect(this::recompileShaderGraph);

        // 6.3.12 — the Main Preview. Parented to the graph's own pane rather than promoted into the top
        // layer: Unity's floats over the canvas, but a top-layer panel would sit above the create menu
        // and every dialog in the gallery too, which is a different claim than "above the graph".
        shaderMainPreview = new com.crystalgui.app.shadergraph.preview.MainPreviewPanel(
                shaderGraph.getDocument(), shaderNodes, master);
        // Over the canvas, not beside it: addOverlay puts it in the viewport rather than on the plane,
        // so it stays put while the graph pans underneath — which is what "floating preview" means.
        shaderGraph.addOverlay(shaderMainPreview);
    }

    /** Builds a widget for a library type and places it, keeping the document binding the factory does. */
    private GraphNode addShaderNode(com.crystalgui.graph.NodeTypeRegistry library,
                                    com.crystalgui.graph.NodeType type, float x, float y) {
        GraphNode node = shaderGraph.getNodeFactory().create(type, type.create(x, y));
        shaderGraph.addNode(node, x, y);
        return node;
    }

    /**
     * Maps the document to the compiler's IR, emits, and shows the result.
     *
     * <p>Errors go to the status line rather than being swallowed: a graph that cannot compile is the
     * normal state while one is being built, and the message names the node responsible.</p>
     */
    private void recompileShaderGraph() {
        if (shaderGraph == null || shaderSource == null) return;
        var shaderNodes = com.crystalgraphics.shadergraph.CgShaderNodeRegistry.builtins();
        var master = new com.crystalgraphics.shadergraph.CgMasterNode();

        var result = com.crystalgui.app.shadergraph.ShaderGraphBridge.compile(
                shaderGraph.getDocument(), shaderNodes, master);

        shaderSource.setText(result.source().isEmpty()
                ? "// nothing to compile yet\n" + String.join("\n", result.errors())
                : result.source());
        shaderStatus.setText(result.ok()
                ? String.format("compiled  %dn/%de  %d chars  %d varyings  %d mapped lines",
                        shaderGraph.getDocument().nodeCount(),
                        shaderGraph.getDocument().edges().size(),
                        result.source().length(), result.varyings().size(),
                        result.lineOwners().size())
                : result.errors().size() + " error(s): " + result.errors().get(0));

        // The point of the line map: put the caret anywhere in the generated source and the status line
        // names the NODE that emitted it. A driver error reports a line in code the user never wrote, and
        // this is the lookup that turns that into somewhere to go and look.
        shaderLineOwners = result;
    }

    private void graphPage(UIElement pane) {
        graph = new GraphView();
        graph.addClass("graph-view");

        graphStatus = new UIText("");
        graphStatus.addClass("canvas-status");

        Button fit = new Button("fit");
        fit.addClass("canvas-btn");
        fit.attachListener(() -> {
            graph.fitToContent(24f);
            updateGraphStatus();
        });

        Button reset = new Button("reset");
        reset.addClass("canvas-btn");
        reset.attachListener(() -> {
            graph.setZoom(1f);
            graph.setPan(0f, 0f);
            updateGraphStatus();
        });

        graphCullToggle = new Button("cull: on");
        graphCullToggle.addClass("canvas-btn");
        graphCullToggle.attachListener(() -> {
            graph.setCullingEnabled(!graph.isCullingEnabled());
            updateGraphStatus();
        });

        // 6.2.5's whole point, made visible: the graph on screen IS a document, and a document is bytes.
        // Save encodes through PlainOps (the server path, no Gson), reload decodes and rebuilds the view
        // from scratch. Draw anything, save, move things about, reload -- you get the saved graph back,
        // including node ids, so the wires reattach to the ports they were on rather than by position.
        Button save = new Button("save");
        save.addClass("canvas-btn");
        save.attachListener(() -> {
            savedGraph = com.crystalgui.graph.GraphCodecs.DOCUMENT
                    .encode(com.crystalgui.serialization.PlainOps.INSTANCE, graph.getDocument());
            updateGraphStatus();
        });

        Button reload = new Button("reload");
        reload.addClass("canvas-btn");
        reload.attachListener(() -> {
            if (savedGraph == null) return;
            graph.load(com.crystalgui.graph.GraphCodecs.DOCUMENT
                    .decode(com.crystalgui.serialization.PlainOps.INSTANCE, savedGraph));
            updateGraphStatus();
        });

        pane.append(row(slot("view"), fit, reset, graphCullToggle, save, reload, graphStatus));
        pane.append(graph);

        // The library the create-node menu offers from -- and the same descriptions the four nodes below
        // are built from, so what you can add and what is already there cannot drift apart.
        NodeTypeRegistry library = new NodeTypeRegistry();
        library.register(NodeType.of("shader.Position").label("Position").category("Input/Geometry")
                .synonyms("world", "vertex")
                .out("Out", "vec3").defaultProperty("Space", "World"));
        library.register(NodeType.of("shader.NormalVector").label("Normal Vector").category("Input/Geometry")
                .synonyms("normal")
                .out("Out", "vec3").defaultProperty("Space", "World"));
        library.register(NodeType.of("shader.PerlinNoise3D").label("Perlin noise 3D").category("Procedural")
                .synonyms("noise", "random")
                .in("Sampling Coordinates", "vec3").in("Noise Scale", "float").out("Value", "float"));
        library.register(NodeType.of("shader.Add").label("Add").category("Math")
                .synonyms("plus", "sum")
                .in("A", "vec3").in("B", "vec3").out("Out", "vec3"));
        library.register(NodeType.of("shader.Multiply").label("Multiply").category("Math")
                .synonyms("times", "product")
                .in("A", "vec3").in("B", "vec3").out("Out", "vec3"));
        library.register(NodeType.of("shader.Step").label("Step").category("Math")
                .synonyms("threshold", "cutoff")
                .in("Edge", "float").in("In", "float").out("Out", "float"));

        // The widget half. Only the two nodes with a Space dropdown need a custom builder; everything
        // else comes out of the placeholder path, which is exactly the point -- a library is usable
        // before anybody writes a single factory.
        NodeWidgetFactory factory = NodeWidgetFactory.of(library)
                .register("shader.Position", data -> withPreview(spaceNode("Position")))
                .register("shader.NormalVector", data -> spaceNode("Normal Vector"))
                // Registered ONLY so the preview survives a reload. A preview is part of what the node
                // is, so calling .preview() at the call site meant the rebuilt node came back without
                // one; the ports still come from the placeholder path underneath.
                .register("shader.Add", data -> withPreview(NodeWidgetFactory
                        .placeholder(library.get("shader.Add"), data,
                                NodeWidgetFactory.PortTypeRegistryLookup.DEFAULT)))
                .build();
        graph.setNodeLibrary(library, factory, GALLERY_TYPES);

        // The reference graph, rebuilt: Position and Normal Vector feed a noise node and an add.
        //
        // Every one of the four goes through the FACTORY rather than being newed up directly, even the
        // two with custom builders. That is what binds each widget to a library typeId — build one by
        // hand and the document can only file it as "authored as a widget", so a reload brings it back
        // as a placeholder with no controls. Which is exactly what this page did until it was noticed.
        GraphNode position = factory.create(library.get("shader.Position"),
                library.get("shader.Position").create(20f, 30f));
        NodePort positionOut = position.getOutputPorts().get(0);
        graph.addNode(position, 20f, 30f);

        GraphNode normal = factory.create(library.get("shader.NormalVector"),
                library.get("shader.NormalVector").create(20f, 210f));
        NodePort normalOut = normal.getOutputPorts().get(0);
        graph.addNode(normal, 20f, 210f);

        GraphNode noise = factory.create(library.get("shader.PerlinNoise3D"),
                library.get("shader.PerlinNoise3D").create(250f, 200f));
        NodePort noiseCoords = noise.getInputPorts().get(0);
        NodePort noiseValue = noise.getOutputPorts().get(0);
        graph.addNode(noise, 250f, 200f);

        GraphNode add = factory.create(library.get("shader.Add"),
                library.get("shader.Add").create(480f, 40f));
        NodePort addA = add.getInputPorts().get(0);
        NodePort addB = add.getInputPorts().get(1);
        graph.addNode(add, 480f, 40f);

        graph.connect(positionOut, addA);
        graph.connect(normalOut, noiseCoords);
        // float -> vec3: legal by promotion, and the one wire on the page drawn as a two-colour gradient.
        graph.connect(noiseValue, addB);

        // Selected, so the page opens showing what selection looks like.
        //
        // Through the SELECTION, never GraphNode.setSelected: that only flips the node's own flag, so the
        // page opened with a node that looked selected and that the selection model had never heard of.
        // Nothing could then deselect it — clearSilently() only walks the nodes it knows about — so the
        // ring survived every click and only a marquee (which adds the node, then drops it properly)
        // cleared it. setSelected is package-private now, so this line no longer compiles.
        graph.getSelection().selectOnly(position);

        graph.onViewChanged.connect(this::updateGraphStatus);
        graph.onConnectionsChanged.connect(this::updateGraphStatus);
        graph.getSelection().onChanged.connect(this::updateGraphStatus);
        updateGraphStatus();
    }

    /** The two nodes that need more than their ports: a title, an Out, and the Space dropdown. */
    /** {@code preview()} hands back the slot, not the node, so this keeps the builders one expression. */
    private static GraphNode withPreview(GraphNode node) {
        node.preview();
        return node;
    }

    private GraphNode spaceNode(String title) {
        GraphNode node = new GraphNode(title);
        node.addOutput(T_VEC3, "Out");
        node.addControl("Space", spaceDropdown());
        return node;
    }

    private Dropdown spaceDropdown() {
        Dropdown space = new Dropdown("World");
        space.addOptions("World", "Object", "View", "Tangent");
        space.select(0);
        space.addClass("graph-dropdown");
        return space;
    }

    /** Wire and culled counts included deliberately: a correct cull is by definition something you
     * cannot see, so the counter is the only evidence it is running at all. */
    private void updateGraphStatus() {
        if (graphStatus == null) return;
        // The selection is in the status line because a command that "does nothing" is indistinguishable
        // from a selection that was silently lost — and only one of those is a bug in the command.
        var selection = graph.getSelection();
        String selected = selection.isEmpty()
                ? "none"
                : (selection.nodes().size() + " node(s)" + (selection.wire() != null ? " + wire" : ""));
        graphStatus.setText(String.format("zoom %.2f  wires %d  culled %d  sel: %s  doc: %dn/%de%s",
                graph.getZoom(), graph.getConnections().size(), graph.culledCount(), selected,
                graph.getDocument().nodeCount(), graph.getDocument().edges().size(),
                savedGraph == null ? "" : "  saved"));
        if (graphCullToggle != null) {
            graphCullToggle.setText(graph.isCullingEnabled() ? "cull: on" : "cull: off");
        }
    }


    /**
     * P6.1.8's control kit, all of it, on one page.
     *
     * <p><b>This page is the review surface, and that is its whole job.</b> The kit is built once and
     * then reused across ~170 shader nodes, so the alternative to looking at every control together
     * here is looking at them one at a time, on whichever node happens to show one, forever. Hold it
     * next to {@code docs/research/unity-inspector/01-inspector-property.png} — same four control kinds,
     * same question: does the column line up?</p>
     *
     * <p>The log on the right is the functional half. A control that looks right and reports nothing is
     * the failure this page exists to make obvious, and an echo — a value reported when nobody typed —
     * shows up as a line appearing on its own.</p>
     */
    private void configuratorPage(UIElement pane) {
        ConfiguratorPanel panel = new ConfiguratorPanel();
        panel.addClass("cfg-panel");

        // A plain section header: full-width band, no arrow, nothing to collapse — Unity's
        // "Target Settings" caption in 07-full-window.png, unlike the collapsible group below.
        panel.add(ConfigDescriptor.header("Node Settings"), null);
        panel.add(ConfigDescriptor.text("name", "Name").tooltip("Free text"), "Untitled");
        panel.add(ConfigDescriptor.number("scale", "Scale"), 1.0);
        panel.add(ConfigDescriptor.number("opacity", "Opacity").range(0f, 1f), 0.5);
        panel.add(ConfigDescriptor.number("count", "Count").integral(true), 3);
        panel.add(ConfigDescriptor.bool("exposed", "Exposed"), true);
        panel.add(ConfigDescriptor.select("space", "Space",
                java.util.List.of("Object", "View", "World", "Tangent", "Absolute World")), "World");
        panel.add(ConfigDescriptor.vector("offset", "Offset", 3), new double[] { 0, 1, 0 });
        panel.add(ConfigDescriptor.vector("uv", "UV", 2), new double[] { 0, 0 });

        // A group, so the foldout and the indent are visible next to ungrouped rows rather than on a
        // page of their own — depth only reads as depth against something that is not indented.
        ConfiguratorGroup advanced = new ConfiguratorGroup("Advanced");
        panel.append(advanced);
        panel.addTo(advanced.content(), ConfigDescriptor.number("bias", "Bias"), 0.0);
        panel.addTo(advanced.content(), ConfigDescriptor.bool("clamp", "Clamp"), false);
        ConfiguratorGroup nested = new ConfiguratorGroup("Nested", true);
        advanced.content().append(nested);
        panel.addTo(nested.content(), ConfigDescriptor.text("note", "Note"), "two levels deep");

        panel.add(ConfigDescriptor.of("entries", "Entries", ConfigDescriptor.Kind.ARRAY)
                .element(ConfigDescriptor.text("entries.e", "")), java.util.List.of("alpha", "beta"));

        // Step 6's four remaining leaves — a group of their own so they're easy to find and compare
        // side by side rather than scattered through the page.
        ConfiguratorGroup leaves = new ConfiguratorGroup("Step 6");
        panel.append(leaves);
        panel.addTo(leaves.content(), ConfigDescriptor.color("tint", "Tint"), 0xFF3C8CFF);
        panel.addTo(leaves.content(), ConfigDescriptor.mask("layers", "Layers",
                java.util.List.of("Default", "Water", "UI", "PostProcessing")),
                java.util.Set.of("Default", "Water"));
        panel.addTo(leaves.content(), ConfigDescriptor.matrix("transform", "Transform", 4), null);
        panel.addTo(leaves.content(), ConfigDescriptor.asset("shader", "Shader"), "Shaders/Lit.shader");

        // A COLUMN of one-line labels rather than one label with newlines in it: UIText wraps, and
        // whether it also honours an explicit line break is a question this page has no business
        // depending on.
        UIElement log = new UIElement();
        log.addClass("cfg-log");
        java.util.List<String> lines = new java.util.ArrayList<>();
        panel.changed.connect((id, value) -> {
            lines.add(0, id + " = " + describe(value));
            // Newest FIRST and capped: a log that grows downward pushes itself off the page, and the
            // line worth reading is always the one that just arrived.
            while (lines.size() > 12) lines.remove(lines.size() - 1);
            // Rebuilt wholesale, which is safe only because nothing here is being clicked or dragged —
            // the rule that bit the table header. These are read-only labels in a side panel.
            log.removeAll();
            for (String line : lines) {
                UIText entry = new UIText(line);
                entry.addClass("cfg-log-line");
                log.append(entry);
            }
        });

        UIElement side = new UIElement();
        side.addClass("cfg-side");
        side.append(hint("Every control sits on ONE height - that is the property to check"));
        side.append(hint("Labels are LEFT-aligned and fixed-width, so controls share a left edge"));
        side.append(hint("A checkbox is square and does NOT fill - the one deliberate exception"));
        side.append(hint("Opacity has a range, so it is a slider; Scale has none, so it is a field"));
        side.append(hint("Groups INDENT, they do not draw a box - Unity's choice, not LDLib2's"));
        side.append(hint("Reference: docs/research/unity-inspector/01-inspector-property.png"));
        side.append(log);

        // In a Dialog: it already owns a draggable title bar, its own position, and the resize handles,
        // so the panel stays a plain content widget instead of growing a second identity as a window.
        // Same reasoning as the colour picker page above.
        Dialog window = new Dialog("Inspector");
        window.addClass("cfg-window");
        window.getContent().append(panel);

        Button open = new Button("open inspector");
        open.addClass("canvas-btn");
        open.attachListener(() -> window.show().moveTo(40f, 90f));

        // The window goes on the PANE, not in the row: a Dialog is position:absolute against its
        // containing block, so putting it in a flex row would have it float over the row's other child
        // rather than sit beside it. The side panel is inset instead, to clear where the window opens.
        UIElement row = new UIElement();
        row.addClass("cfg-row");
        row.append(side);
        pane.append(row(slot("panel"), open));
        pane.append(row);
        pane.append(window);
        window.show().moveTo(16f, 78f);
    }

    /** Readable for a log line — arrays are the reason this is not just String.valueOf. */
    private static String describe(Object value) {
        if (value instanceof double[] v) return java.util.Arrays.toString(v);
        return String.valueOf(value);
    }

    @Override
    public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
        return document.input().consumeMouseEvent(event);
    }
}
