package com.crystalgui.render;

import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.shader.CgShaderBindings;
import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.gl.framebuffer.CgPixelReadback;
import com.crystalgraphics.gl.texture.CgHostSamplers;
import com.crystalgraphics.platform.gl.state.CgGlCensus;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.gl.render.CgClipTable;
import com.crystalgraphics.gl.render.CgVectorRenderer;
import com.crystalgraphics.gl.render.CgQuadRenderer;
import com.crystalgraphics.gl.render.CgShapeTable;
import com.crystalgraphics.gl.texture.CgFallbackTextures;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.gl.texture.CgTextureManager;
import com.crystalgraphics.render.CgImmediate;
import com.crystalgraphics.render.draw.CgBindingTable;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.graph.CgUpload;
import com.crystalgraphics.render.graph.CgFrame;
import com.crystalgraphics.render.graph.CgFrameBuilder;
import com.crystalgraphics.render.graph.CgFrameGraph;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgPassRecorder;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgReplay;
import com.crystalgraphics.render.property.CgPropertyValues;
import com.crystalgraphics.render.property.CgSpatialTree;
import com.crystalgraphics.render.graph.CgTextureDesc;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.text.render.CgTextGamma;
import com.crystalgraphics.text.render.CgTextRenderer;
import com.crystalgraphics.trace.CgFrameImages;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.io.CgIO;
import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.text.atlas.CgGlyphAtlas;
import com.crystalgraphics.text.cache.CgFontRegistry;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.trace.UiTrace;
import com.crystalgui.render.text.FontFamilyCache;
import com.crystalgui.render.texture.CgUiRect;
import com.crystalgui.render.texture.asset.FileIconTheme;
import com.crystalgui.render.texture.svg.SvgDocument;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleRule;
import com.crystalgui.style.sheet.StyleSheet;
import lombok.Getter;
import lombok.Setter;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A document's paint context: it records a frame of the document into passes, touching no GL, and hands back a
 * sealed {@link UiFrame} that {@link UiGpu} executes. One per document, from
 * {@link com.crystalgui.ui.dom.UIDocument#paintContext()}.
 *
 * <pre>{@code
 * CgUiPaintContext ctx = document.paintContext();
 *
 * ctx.beginFrame(w, h);              // inline: the host's state saved, then recording
 * document.paint(ctx);
 * ctx.endFrame();                    // sealed, executed, composited
 *
 * ctx.recordFrame(w, h);             // or split: record on the document's thread...
 * document.paint(ctx);
 * UiGpu.present(ctx.seal());         // ...execute on the render thread
 * }</pre>
 *
 * <p>Each pass carries its own constants — the frame's time, the target's ortho; no global frame state is read or
 * written.</p>
 *
 * <p>Integrates {@link ScissorStack} for nested clip regions: a push sets the scissor the chunks recorded after
 * it draw under.</p>
 *
 * <p><b>Frame lifecycle</b> — call {@link #beginFrame} once before walking the UI tree, then {@link #endFrame} once
 * after. A draw in between is a chunk in the frame's one recording, which {@link #endFrame} executes; {@link #flush}
 * ends the open chunks and executes nothing.</p>
 */
public final class CgUiPaintContext {

    /**
     * The {@code uiScale} glyphs are warmed at.
     *
     * <p>Was {@code UIWindow.DEFAULT_UI_SCALE}, and the reason that constant existed is unchanged: a
     * warm aims at the size text is actually drawn at ({@code font-size * uiScale}), so one aimed at
     * the wrong size is SILENTLY useless -- the glyphs generate, cache, and are never looked up.</p>
     *
     * <p>It lives here now because the new engine has no single default to borrow: {@code BoxTree}
     * starts at 1 and the host sets what it wants, which on Minecraft is 2. So this is the scale the
     * WARM is for, stated where the warm is, rather than a second copy of somebody else's default.</p>
     */
    private static final float WARM_UI_SCALE = 2f;

    /** {@code namespace:path} resolved through {@link CgIO}'s waterfall (filesystem override →
     * MC resource manager → classpath) — works identically in-game and in the harness/tests,
     * unlike the hardcoded absolute Windows path this replaced ({@code C:\WINDOWS\Fonts\arial.ttf},
     * which only ever worked on the original dev's machine). CrystalGUI ships it; CrystalGraphics
     * carries no fonts. */
    private static final String DEFAULT_FONT_ASSET = "crystalgui:ui/fonts/IBMPlexSans-Regular.ttf";

    /**
     * The same preference order {@code StylePropertyRegistry.FONT_FAMILY} declares, and it has to stay
     * that way: this is the face anything drawing text <em>without</em> consulting the cascade gets, so a
     * divergence shows up as one widget in a different font from every other with nothing in any
     * stylesheet to explain it. First entry that loads wins.
     *
     * <p><b>Proportional</b>, like the cascade default — the monospace face is applied by
     * {@code ua/editor.css} to code surfaces only, and this is the UI's fallback rather than the
     * editor's.</p>
     */
    private static final String[] DEFAULT_FONT_STACK = {
            DEFAULT_FONT_ASSET,
    };

    /**
     * Compiles the shipped materials and builds everything a first frame would, so the first real frame does not.
     *
     * <p>{@code CgMaterial.load} only parses; binding each material once is what pays for the GLSL — measured, a
     * first-frame material bind fell from 300 ms to 286 by construction alone and to nothing once bound. An empty
     * frame then builds the executor's buffers, the ring and the GL state save, which were most of a first frame.
     * Called from {@link com.crystalgui.lifecycle.CgUiLifecycle#onInit}, on the GL thread with a live context.
     * Failures are swallowed: a warm-up must not be what fails a context, and the first real frame reports them.</p>
     */
    public static void warm(int width, int height) {
        CgUiPaintContext context = create();
        try {
            // Leaks the Pass RenderState of every material below — doBind applies it, unbind() restores none of
            // it. Scoped by CgUiLifecycle.onInit; no scope here.
            for (CgMaterial material : new CgMaterial[] {
                    context.boxModelMaterial, context.curveMaterial, context.layerBlitMaterial }) {
                try {
                    material.bind();
                    material.unbind();
                } catch (RuntimeException | LinkageError ignored) {
                    // An optimisation that fails is silent.
                }
            }
            // AND THE TWO ASSET CACHES, both off the render thread and neither needing GL.
            preloadIcons();
            warmGlyphs(WARM_UI_SCALE);
            try {
                context.beginFrame(Math.max(1, width), Math.max(1, height));
                context.endFrame();
            } catch (RuntimeException | LinkageError e) {
                // Not silent: a frame left open would refuse every later one.
                context.abortFrame();
                CrystalGuiCore.LOGGER.warn("[cgui] paint warm-up failed; the first real frame builds it instead", e);
            }
        } finally {
            context.release();
        }
    }

    /** Leaves a frame that threw part-way: its batches closed, its GL state back, and no frame open. */
    private void abortFrame() {
        endTextPath();
        renderer.end();   // safe unbegun: begin() may be what never ran; what it flushes goes with the recording
        recordFlushes(false);
        recorder.abandon();
        recording.reset();
        present.reset();
        imported.clear();
        layerStack.clear();
        CgGL.exitGlFree();
        gpu.abortFrame();
        frameActive = false;
    }

    /**
     * A paint context for one document — {@link com.crystalgui.ui.dom.UIDocument#paintContext()} makes it. On the render
     * thread: its font and text renderer are set up there.
     */
    public static CgUiPaintContext create() {
        // Before any frame's scope: what construction binds would otherwise stay bound for the host.
        try (CgGlScope ignored = CgGlState.saveAll()) {
            CgUiPaintContext context = new CgUiPaintContext();
            context.gpu.track(context);
            return context;
        }
    }

    /** Every box the UI draws: fills, textures, shapes, icons, composites. @see CgShapeTable */
    private static final String BOX_SHADER = "crystalgui:shaders/gui_box.shader";

    private final CgMaterial boxModelMaterial;

    /**
     * Shared material for every Bézier stroke — {@code gui_curve.shader}, the curve twin of
     * {@code gui_box.shader}. Distinct from CrystalGraphics' own {@code curve.shader} because the UI
     * needs {@code DepthTest ALWAYS} and a {@code _LayerOpacity} property, neither of which belongs
     * in the backend's reference material.
     */
    private final CgMaterial curveMaterial;

    /**
     * The mask multiply's own material — a private instance, never the caller's.
     *
     * <p>{@code _MainTex} is a Properties-block sampler, so SETTING it changes the material for good:
     * every later {@code bind()} re-binds that texture and a raw {@link #bindTexture} is overwritten.
     * Borrowing the enclosing material for the multiply therefore left every sprite drawn through it
     * afterwards reading the mask's texture, or the white fallback once the sampler was handed back —
     * themed chrome flooding white on any page that masks anything, and drawing as nothing without the
     * hand-back. Neither reads as a mask bug.</p>
     */
    private final CgMaterial maskMaterial;
    /** {@link #maskMaterial}'s pass state with the alpha-multiply blend a mask composite draws under, and its base. */
    private CgRenderState maskState, maskStateBase;

    /**
     * The box material again, for {@link #blitLayer}: a quad drawn while it is current is stamped
     * {@link CgShapeTable#PREMULTIPLIED}, since a layer was drawn over a transparent clear and its partly covered pixels
     * carry their alpha in their colour. Its own instance because a sampler property is retained: the layer it last
     * composited must not become what every box samples.
     */
    /** Package-private: {@link CgUiBackdrop} composites the capture with it. */
    final CgMaterial layerBlitMaterial;

    /** The backdrop primitive — capture, blur, and the region logic that keeps it affordable. */
    private final CgUiBackdrop backdrop;

    /** Rasterised icon fills, kept across frames. @see SvgRasterCache */
    private final SvgRasterCache svgRaster;

    /**
     * A material standing in for {@code gui_curve.shader} on the curve path, or null. Set only inside
     * {@link #withCurveMaterial}, which is how the raster cache accumulates coverage additively through
     * the same renderer and the same pose everything else uses.
     */
    @Nullable
    private CgMaterial curveMaterialOverride;

    /** One axis of the separable blur per bind. @see #backdropFor */
    /** Package-private: {@link CgUiBackdrop} owns every use of it. */
    final CgMaterial blurMaterial;
    /** The box prefilter that reduces the capture before it is blurred. Package-private, as above. */
    final CgMaterial downsampleMaterial;

    /** 1×1 fully opaque white ({@code RGBA = 255, 255, 255, 255}). */
    @Getter
    final CgTexture2D whitePixel;

    @Getter
    final PoseStack poseStack;

    /**
     * Basic wrapper over {@link com.crystalgraphics.gl.render.CgQuadRenderer}.
     * Works only for quads.
     * <br>
     * <b>Currently intended for immediate flushing, despite it being inefficient and going against the idea of "Batching"</b>
     */
    @Getter
    private final CgUiRenderer renderer;

    // ── Text ─────────────────────────────────────────────────────────────────
    /**
     * Owned independently of {@link #renderer}, though both now reach the GPU the same way:
     * CrystalGraphics' {@code CgTextRenderer} batches glyphs through its own
     * {@code CgQuadRenderer}, exactly as {@link CgUiRenderer} does for box-model quads.
     *
     * <p>They stay separate instances rather than sharing one because each batches across its own
     * {@code begin()}/{@code end()} window against its own material — only the CPU-side accumulation
     * buffer is per-instance state, while the unit-quad mesh and the instance SSBO/TBO behind them
     * are class-wide and shared regardless.</p>
     */
    @Getter
    private final CgTextRenderer textRenderer;

    /** The GL half: the frame target, the host's state, execution. */
    private final UiGpu gpu;

    // ── Visual layers ────────────────────────────────────────────────────────
    int screenWidth, screenHeight;
    long frameId;
    /** One saved frame per nested {@link #beginLayerFbo}/{@link #endLayerFbo} pair. */
    final Deque<LayerFrame> layerStack = new ArrayDeque<>();

    /**
     * @param target       what the layer is drawn into, as the frame's recording names it
     * @param enclosingWidth the size of the target around it, whose ortho comes back at the end
     * @param savedScissor the clip stack as the ENCLOSING target expressed it. A bounded layer has its
     *                     own origin, so every rect on the stack is shifted into its space on the way
     *                     in and this is what puts them back — the stack always describes the target
     *                     being drawn into, which is what lets {@code applyScissorIfNeeded} stay a
     *                     one-argument flip.
     * @param savedView    the enclosing target's view owner. @see CgPassRecorder#view
     * @param savedSpatial the node drawing was in, for a target with a space of its own, which sets nodes aside
     */
    record LayerFrame(CgGraphTexture target, int enclosingWidth, int enclosingHeight, ScissorStack.Saved savedScissor,
                      @Nullable LayerRegion region, int savedClip, int savedView, int savedSpatial, int savedEffect) {
    }
    
    static final CgFrameBufferFormat LAYER_FORMAT = CgFrameBufferFormat.builder("cgui_layer")
                         .color(0, CgTextureType.RGBA8).build();


    // ── The frame as recorded ────────────────────────────────────────────────
    //
    // Every target's draws are chunks in passes of ONE recording, executed in endFrame. A layer is a pass on a
    // transient texture the executor pools; the target around it ends its pass at the layer and continues in
    // another after, so passes are created in the order they must run and every read follows the write it sees.

    private final CgRecording recording = new CgRecording();
    /** What every renderer of this context flushes into: the passes of {@link #recording} or {@link #present}. */
    private final CgPassRecorder recorder = new CgPassRecorder();
    /** The finished frame onto the host's target, recorded and executed once the host's target is bound again. */
    private final CgRecording present = new CgRecording();
    /** The pass block of the target being drawn into: the frame's time, the target's ortho and size. */
    private final CgPassConstants passConstants = new CgPassConstants();
    /** The frame's own target, as the recording names it: {@link UiGpu}'s. */
    final CgGraphTexture frameTarget;
    /** Builds a sealed frame's two recordings; the frames come back to it when presented. */
    private final CgFrameBuilder builder = new CgFrameBuilder();
    private final CgFrameGraph graph = new CgFrameGraph();
    /** A caller's framebuffer as the recording names it: one per framebuffer a frame, so a read finds its write. */
    private final Map<CgFrameBuffer, CgGraphTexture> imported = new IdentityHashMap<>();

    /** {@code fbo} as this frame's recording names it. */
    CgGraphTexture imported(CgFrameBuffer fbo) {
        if (fbo == gpu.frameFbo) return frameTarget;
        CgGraphTexture target = imported.get(fbo);
        if (target == null) imported.put(fbo, target = CgGraphTexture.imported("cgui_target", fbo));
        return target;
    }

    /**
     * Runs {@code body} with GL when the frame executes, after everything recorded before this call, with
     * {@code target} bound: for drawing that is not recorded, such as a blit of a framebuffer this engine did not
     * write.
     */
    void recordCallback(String name, CgGraphTexture target, Runnable body) {
        drain();
        recording.callback(name, target, body);
    }

    /** Points the pass constants at a {@code width x height} target, top-left origin; chunks from now take them. */
    private void targetConstants(int width, int height) {
        passConstants.view.identity();
        passConstants.projection.identity().ortho(0, width, height, 0, -1, 1);
        passConstants.resolution(width, height);
        recorder.constants(passConstants);
    }

    // ── Scissor ─────────────────────────────────────────────────────────────
    @Getter
    private final ScissorStack scissorStack = new ScissorStack(recorder);

    // ── State elision ───────────────────────────────────────────────────────
    @Getter
    private CgTexture currentTexture;
    @Getter
    boolean frameActive;

    // ── Material switching ──────────────────────────────────────────────────
    @Getter
    private CgMaterial currentMaterial;

    /**
     * Which of the two instanced paths is currently bound. Quads and curves have separate instance
     * buffers and separate materials, and GL has exactly one program bound at a time, so they cannot
     * both be live.
     *
     * <p><b>The switch has to flush, and that is a correctness requirement rather than a tidiness
     * one.</b> The UI paints in painter's order: whatever is submitted later must land on top.
     * Letting queued quads survive a switch to curves would draw them after the curves regardless of
     * submission order, so a stroke under a panel would jump on top of it — and only when the two
     * happened to batch together, which makes it look like a z-order bug in the widget rather than a
     * batching bug here.</p>
     *
     * <p>Because every switch flushes the outgoing path, <b>at most one path ever holds pending
     * work</b>, which is what makes {@link CgUiRenderer#flush()} safe to run over both in any order.</p>
     */
    /**
     * Which renderer last bound a GL program.
     *
     * <p>{@code TEXT} is the one that is not this class's own renderer. {@code CgTextRenderer} owns a
     * separate {@code CgQuadRenderer} and binds {@code text.shader} itself, so without a state for it
     * this field would claim {@code CURVE} while GL actually had the text program bound — and
     * {@link #beginCurvePath()}'s early-return would then submit curve instances against it.</p>
     */
    private enum InstancePath { QUAD, CURVE, TEXT }

    private InstancePath activePath = InstancePath.QUAD;

    /**
     * Current layer-compositing opacity (distinct from {@link #color}'s tint — see
     * {@code gui_box.shader}'s doc comment). Every UI-facing material declares a
     * {@code _LayerOpacity} property; {@link #withMaterial} keeps whichever material is
     * currently bound in sync with this value on every switch.
     */
    @Getter
    private float layerOpacity = 1f;

    /**
     * The one {@code _LayerOpacity} binder, shared by every site that syncs the value.
     *
     * <p>A field rather than a lambda per call site. It captures {@code this} and reads the field
     * when it runs, so one instance is correct for every value it will ever carry — written inline
     * it was a fresh capture per material switch, on the path every SDF drawable takes for every
     * element of every frame.</p>
     */
    private final Consumer<CgShaderBindings> layerOpacityBinder = b -> b.set1f("_LayerOpacity", layerOpacity);

    @Getter
    private final CgFont font = loadDefaultFont();

    @Getter @Setter
    private int color = 0xFFFFFFFF;

    private CgUiPaintContext() {
        this.gpu = UiGpu.get();
        this.frameTarget = gpu.frameTarget;
        this.poseStack = new PoseStack();
        this.renderer = new CgUiRenderer(this);
        this.boxModelMaterial = CgMaterial.load(BOX_SHADER);
        this.curveMaterial = CgMaterial.load("crystalgui:shaders/gui_curve.shader");
        this.layerBlitMaterial = CgMaterial.newInstance(BOX_SHADER);
        // newInstance, not load: load() is registry-cached, so it would hand back boxModelMaterial
        // itself and reintroduce exactly the sharing this material exists to avoid.
        this.maskMaterial = CgMaterial.newInstance(BOX_SHADER);
        this.blurMaterial = CgMaterial.load("crystalgui:shaders/gui_blur.shader");
        this.blurMaterial.toggleKeyword("LINEAR_KERNEL", CgUiBackdrop.LINEAR_KERNEL);
        this.downsampleMaterial = CgMaterial.load("crystalgui:shaders/gui_downsample.shader");
        // AFTER the materials: it holds them, and a field initialiser would run before they exist.
        this.backdrop = new CgUiBackdrop(this);
        this.svgRaster = new SvgRasterCache(this);
        this.whitePixel = (CgTexture2D) CgFallbackTextures.WHITE_1x1;
        this.textRenderer = CgTextRenderer.createManualSized().poseStack(this.poseStack)
                                          .restoreStateWith(() -> {
                bindQuadPath(boxModelMaterial);
                currentTexture = null;
            });
    }

    /**
     * Inside a frame everything this context's renderers flush is recorded; outside one they draw at once, as any
     * renderer does — a scene drawing a label over the finished frame uses them that way.
     */
    private void recordFlushes(boolean recorded) {
        renderer.sink(recorded ? recorder : null);
        textRenderer.sink(recorded ? recorder : null);
    }

    /**
     * Parses the shipped icons on worker threads, so the first frame that draws one does not.
     *
     * <p>Icon parsing touches no GL — {@code CgIO} through scanning, resolution and tessellation is
     * arithmetic over strings and floats — so this REMOVES the cost rather than moving it to another
     * frame. That property is why {@link SvgDocument#preload} exists and is safe to call from here.</p>
     *
     * <p>Fire-and-forget: a document that has not parsed when something draws it parses on the render
     * thread exactly as before, so the worst case is today's behaviour.</p>
     *
     * <p>Covers the file-icon theme — 40 of the 49 icons shipped. The other nine are chrome marks named
     * only from stylesheets ({@code icon("crystalgui:folder")}), and enumerating those needs a
     * hand-written list: a second copy of a fact the sheets own, and the copy that rots. They stay
     * lazy.</p>
     */
    private static void preloadIcons() {
        try {
            Set<String> paths = new LinkedHashSet<>();
            for (String name : FileIconTheme.getDefault().iconNames()) {
                paths.add(FileIconTheme.toResourcePath(FileIconTheme.withVariant(name)));
            }
            SvgDocument.preload(paths);
        } catch (RuntimeException | LinkageError broken) {
            CrystalGuiCore.LOGGER.warn("CgUiPaintContext: icon preload failed; icons parse on demand",
                    broken);
        }
    }

    /**
     * Rasterises printable ASCII for every face the stylesheets name, before anything draws a string.
     *
     * <p>A first frame produces every distinct glyph on it <em>synchronously</em> — asynchronous
     * generation exists, but a glyph queued by the frame that needs it arrives too late to be drawn.
     * A warm has no such problem, because nothing has asked yet. Measured on the editor's first paint
     * at ~181 ms in {@code drawSubtree} before this and ~103 ms after.</p>
     *
     * <h3>Read from the sheets, never listed here</h3>
     *
     * <p>The faces and sizes come out of {@link StyleSheet#DEFAULT}'s own declarations, because a list
     * in this file is a second copy of a fact the stylesheets own — and it is the copy that rots. That
     * is not hypothetical: the first version of this method hardcoded sizes 10/12/14, while the sheets
     * declare 6, 7, 8, 9, 10 and 11. Five of the six real sizes were never warmed and two of the three
     * warmed sizes did not exist, and it still measured as an improvement — which is exactly why the
     * mistake would have survived. Nothing about a wrongly-aimed warm is visible: the work happens, the
     * cache fills, the glyphs are simply never looked up.</p>
     *
     * <p><b>Warmed at {@code size * uiScale}, and that is the whole trick.</b> A bitmap glyph is keyed
     * by the size it is rasterised at, and the renderer rasterises at the CSS size scaled by the pose,
     * so warming the CSS size fills entries no draw ever looks up. It is read from
     * {@link UIWindow#DEFAULT_UI_SCALE} rather than copied, because there is exactly one definition of
     * what {@code uiScale} means and a second would disagree with it silently — this warm being aimed
     * at sizes nothing draws is precisely the failure that would follow.</p>
     *
     * <p>One {@code CgFont} per family covers every size: {@code toBitmapAtlasGlyphKey} replaces the
     * font key's own {@code targetPx} with the effective raster size, so the instance a face was
     * resolved at does not affect which atlas entry a draw looks up. Resolving one per size instead
     * would also submit the distance-field tier once per size, and those jobs are not {@code equals}
     * — they would slip past the executor's dedup and generate the same entry six times over.</p>
     */
    private static void warmGlyphs(float uiScale) {
        try {
            Set<List<String>> families = new LinkedHashSet<>();
            Set<Integer> cssSizes = new LinkedHashSet<>();
            // The cascade's own defaults, which no rule has to restate to be in force.
            families.add(StylePropertyRegistry.FONT_FAMILY.initialValue);
            cssSizes.add(Math.round(StylePropertyRegistry.FONT_SIZE.initialValue));

            for (StyleRule rule : StyleSheet.DEFAULT.getRules()) {
                for (StyleRule.Declaration declaration : rule.declarations()) {
                    // The property is checked BEFORE the value is computed: StyleValue.compute() is
                    // lazy and cached, and forcing it for every declaration in a 6,000-line sheet to
                    // find two properties would be most of a stylesheet parse done twice.
                    if (declaration.property() == StylePropertyRegistry.FONT_FAMILY) {
                        Object value = declaration.value().compute();
                        if (value instanceof List) {
                            @SuppressWarnings("unchecked")
                            List<String> stack = (List<String>) value;
                            if (!stack.isEmpty()) families.add(stack);
                        }
                    } else if (declaration.property() == StylePropertyRegistry.FONT_SIZE) {
                        Object value = declaration.value().compute();
                        if (value instanceof Number) {
                            int px = Math.round(((Number) value).floatValue());
                            if (px > 0) cssSizes.add(px);
                        }
                    }
                }
            }

            int[] effective = new int[cssSizes.size()];
            int next = 0;
            for (int cssPx : cssSizes) effective[next++] = Math.round(cssPx * uiScale);

            int anySize = cssSizes.iterator().next();
            for (List<String> stack : families) {
                CgFontFamily family = FontFamilyCache.resolve(stack, anySize);
                if (family == null) continue;
                CgFontRegistry.get().warmAscii(family.getPrimaryFont(), effective);
            }
        } catch (RuntimeException | LinkageError broken) {
            CrystalGuiCore.LOGGER.warn("CgUiPaintContext: glyph warm failed; glyphs rasterise on demand",
                    broken);
        }
    }

    private static CgFont loadDefaultFont() {
        // WALKS THE STACK, so the preferred face can be declared before it is shipped and the UI simply
        // keeps using the next one down until it lands. Throwing on the first entry made naming a font
        // you do not yet have a crash at first paint rather than a step down the list.
        for (String candidate : DEFAULT_FONT_STACK) {
            CgFont loaded = tryLoadFont(candidate);
            if (loaded != null) return loaded;
        }
        throw new IllegalStateException("CgUiPaintContext: no default font asset could be loaded: "
                + java.util.Arrays.toString(DEFAULT_FONT_STACK));
    }

    /** Null when the asset is simply absent — a corrupt one still throws. */
    private static CgFont tryLoadFont(String asset) {
        InputStream in = CgIO.openStream(asset);
        if (in == null) {
            return null;
        }
        try {
            byte[] data = readAllBytes(in);
            return CgFont.load(data, asset, CgFontStyle.REGULAR, 16);
        } catch (IOException e) {
            throw new IllegalStateException("CgUiPaintContext: failed to read default font asset: " + asset, e);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // Nothing meaningful to do — the font either loaded successfully above or we're
                // already throwing; a close failure on a read-only stream isn't actionable.
            }
        }
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    public int mouseX, mouseY;

    // ── Frame lifecycle ─────────────────────────────────────────────────────

    /**
     * Monotonic frame counter, for work a drawable wants to rate-limit to once per frame.
     *
     * <p>Exposed as a plain token rather than a callback so the dependency points the right way: a
     * drawable can ask "is this a new frame" without this class having to know which drawables exist.</p>
     */
    public long frameId() {
        return frameId;
    }

    private final SvgDocument.LodBudget svgLodBudget = new SvgDocument.LodBudget();

    /** What this paint has spent building icon mesh tiers this frame. */
    public SvgDocument.LodBudget svgLodBudget() {
        return svgLodBudget;
    }

    /**
     * Depth of nested mirror passes. @see #mirroring()
     *
     * <p>A counter rather than a flag because a mirror can legitimately contain another — a taskbar
     * preview of a window that itself shows a preview — and a boolean would be cleared by the inner one
     * on the way out, leaving the rest of the outer pass writing world matrices again.</p>
     */
    private int mirrorDepth;

    /**
     * Draws {@code body} as a MIRROR — a second, non-authoritative rendering of something that is also
     * drawn somewhere else.
     *
     * <p>Every element reconciles its cached {@code localToWorld} against the pose it was drawn with, and
     * that cache is what HIT-TESTING walks: the engine's rule is that the two must produce an identical
     * matrix or clicks land somewhere other than what the user sees. Drawing a subtree a second time
     * under a different pose therefore leaves every element in it believing it lives wherever the copy
     * was — and a copy is normally drawn LATER (a taskbar preview lives in the top layer), so the copy
     * wins and the real window stops being clickable where it is.</p>
     *
     * <p>So a mirror pass says "paint this, but do not learn anything from it". The subtree draws exactly
     * as it would anywhere else; only the placement bookkeeping stands down.</p>
     */
    public void mirrored(Runnable body) {
        mirrorDepth++;
        try {
            body.run();
        } finally {
            mirrorDepth--;
        }
    }

    /** Whether the current draw is a mirror, and so must not update placement caches. @see #mirrored */
    public boolean mirroring() {
        return mirrorDepth > 0;
    }

    /**
     * Starts a frame inline: saves the host's GL state, then {@link #recordFrame}. Pair with {@link #endFrame}. GL a
     * paint hook still issues while recording is inside the saved state.
     */
    public void beginFrame(int screenWidth, int screenHeight) {
        if (frameActive) throw new IllegalStateException("beginFrame() called without matching endFrame()");
        gpu.beginFrame(screenWidth, screenHeight);
        recordFrame(screenWidth, screenHeight);
    }

    /**
     * Starts recording a frame of {@code screenWidth x screenHeight} into the frame's own target. Touches no GL. Pair
     * with {@link #seal}, which hands back what {@link UiGpu#present} executes.
     */
    public void recordFrame(int screenWidth, int screenHeight) {
        frameId++;
        if (frameActive) throw new IllegalStateException("recordFrame() called without matching seal()");
        CgGL.enterGlFree("ui recording");
        // A text kept while its glyphs were still generating drew without them, and an atlas that grew binds
        // another texture: new glyph content is a new epoch, read once a frame since a frame kept under the old one
        // is painted again in the next.
        long evicted = CgFontRegistry.get().getAtlasEvictionGeneration();
        long content = CgFontRegistry.get().getAtlasContentGeneration();
        if (evicted != seenFontGeneration || content != seenFontContent) {
            CgTrace.add(UiTrace.FRAME, evicted != seenFontGeneration ? "replay-epoch-evicted" : "replay-epoch-glyphs", 1);
            seenFontGeneration = evicted;
            seenFontContent = content;
            replayEpoch++;
        }
        if (keptBindings.size() > KEPT_SNAPSHOTS) {
            keptBindings.reset();
            replayEpoch++;
            CgTrace.add(UiTrace.FRAME, "replay-kept-resets", 1);
        }
        layerOriginX = 0;
        layerOriginY = 0;
        setClip(0);
        nodesSuspended = 0;
        enterNode(0);
        effectNode = 0;
        viewOwner = 0;
        recorder.view(0, 0f, 0f);
        clearPainted();
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        scissorStack.reset();
        recorder.noScissor();
        backdrop.beginFrame();

        // EACH STEP OF beginFrame TIMED SEPARATELY: `gl:begin` was measured at 19.9ms on the frame after a tab
        // closes, and the steps below have nothing in common.
        // Text: projection + atlas LRU frame tick. No beginBatch() here — drawText()
        // deliberately stays standalone-per-call, see docs/CRYSTALGUI_TEXT_RENDERING_PLAN.md §2.3.
        long timed = CgTrace.stamp(UiTrace.FRAME);
        textRenderer.context().updateOrtho(screenWidth, screenHeight);
        CgTrace.zoneDone(UiTrace.FRAME, "glbegin:textOrtho", timed);

        poseStack.pushPose();
        timed = CgTrace.stamp(UiTrace.FRAME);
        renderer.begin();
        CgTrace.zoneDone(UiTrace.FRAME, "glbegin:renderer.begin", timed);
        timed = CgTrace.stamp(UiTrace.FRAME);
        bindQuadPath(boxModelMaterial);
        CgTrace.zoneDone(UiTrace.FRAME, "glbegin:bindQuadPath", timed);
        currentMaterial = boxModelMaterial;
        currentTexture = null;
        // Every flush from here is a chunk in the frame's recording, starting with the frame target's clear; nothing
        // executes until endFrame.
        // The frame's clock, which a material reading CG_TIME animates by -- a shader graph's Time node.
        passConstants.time(CgFrameClock.seconds());
        targetConstants(screenWidth, screenHeight);
        recordFlushes(true);
        for (CgGraphTexture released : pendingReleases) recording.release(released);
        pendingReleases.clear();
        recorder.recordInto(recording, frameTarget, CgLoad.clear(0f, 0f, 0f, 0f), passConstants);
        frameActive = true; // must be set before the pool warms a slot — quad() requires an active frame

        // AFTER frameActive, with the pool's own warm-up, because warming a target SUBMITS A QUAD and
        // quads are refused outside a frame. Built here rather than on demand: an FBO created mid-draw,
        // with our own bindings in flight, came back incomplete. @see #blurLevel
        backdrop.prepareFrame();
    }

    /**
     * Unbinds the box-model material and restores GL state via the saved {@link CgGlScope}. Call once
     * after the whole UI tree has painted.
     */
    public void endFrame() {
        if (!frameActive) return;
        gpu.endFrame(seal());
    }

    /**
     * Ends the frame {@link #recordFrame} started and builds it: ordered, batched and packed, with nothing left that
     * refers to this context, which may record its next frame at once. Touches no GL.
     */
    public UiFrame seal() {
        if (!frameActive) throw new IllegalStateException("seal() without recordFrame()");
        sweepRetained();
        long timed = CgTrace.stamp(UiTrace.FRAME);
        textRenderer.endBatch();
        renderer.flush();
        recorder.stop();
        recordPresent();
        CgTrace.zoneDone(UiTrace.FRAME, "glend:flush", timed);

        timed = CgTrace.stamp(UiTrace.FRAME);
        UiFrame frame;
        try {
            CgPropertyValues values = new CgPropertyValues();
            frame = new UiFrame(build(recording, values), build(present, null), builder, values, frameId, screenWidth,
                    screenHeight);
        } finally {
            recording.reset();
            present.reset();
            imported.clear();
            recordFlushes(false);
        }
        CgTrace.zoneDone(UiTrace.FRAME, "glend:build", timed);

        currentMaterial = null;
        currentTexture = null;
        frameActive = false;
        renderer.end();
        poseStack.popPose();
        if (!poseStack.clear()) throw new IllegalStateException("Unpopped stack(s) in UI frame");
        CgGL.exitGlFree();
        return frame;
    }

    private CgFrame build(CgRecording what, @Nullable CgPropertyValues values) {
        try {
            graph.add(what.seal(), values);
            return builder.build(graph);
        } finally {
            graph.clear();
        }
    }

    // ── Public draw API ─────────────────────────────────────────────────────

    /** Solid-color fill, tint already includes opacity. */
    public void fillRect(float x, float y, float width, float height, int argb) {
        // ONE FLUSH PER RECTANGLE, and a flush is a draw call. Counted because `gl:draw` measures at
        // 21-30us per painted element -- far too much for a tree walk, and exactly the shape of
        // per-element driver overhead. 271 elements after a file is opened (up from 130 with none) at
        // one or more draw calls each is the whole 8.33ms budget spent submitting.
        CgTrace.add(UiTrace.FRAME, "drawcalls", 1);
        bindTexture(whitePixel);
        quad().at(x, y).size(width, height).color(argb).submit();
        flush();
    }

    /** Textured draw with an explicit UV sub-rect (atlas support), tint already includes opacity. */
    public void drawImage(CgTexture texture, float x, float y, float width, float height,
                           float u0, float v0, float u1, float v1, int argb) {
        bindTexture(texture);
        // A failed load resolves to the fallback checkerboard, which has no meaningful sub-rect — a
        // caller's UV crop would sample an arbitrary corner of it. Full-range UVs keep a missing
        // texture looking like the recognisable "missing texture" it is, at whatever size it was
        // asked to draw. Was previously enforced centrally in submitQuad; it now lives at the two
        // sites that can actually be handed a fallback (here and CgUiSprite).
        CgTrace.add(UiTrace.FRAME, "drawcalls", 1);
        boolean missing = texture == CgTextureManager.get().getFallback();
        CgQuadRenderer.Quad q = quad().at(x, y).size(width, height).color(argb);
        (missing ? q : q.uv(u0, v0, u1, v1)).submit();
        flush();
    }
    
    /**
     * Returns the context's text renderer object.
     *
     * <p>Its owned pose stack was wired to this context's own {@link #getPoseStack()} in
     * the constructor, so {@link CgTextRenderer.Draw#poseStack(PoseStack)}
     * may be omitted entirely — a draw with no explicit pose falls back to it.</p>
     *
     * <pre>{@code
     * // One-shot: build and submit in the same expression. No .pose(...) call needed —
     * // falls back to this context's own poseStack automatically.
     * ctx.text().draw()
     *         .text("Hello world")
     *         .font(myFont)
     *         .at(20.0f, 40.0f)
     *         .color(0xFFFFFFFF)
     *         .submit();
     *
     * // Retained: held across frames (e.g. a widget's cached label draw), only the
     * // text changes each tick. Independent of draw()'s shared immediate-mode scratch instance.
     * CgTextRenderer.Draw labelDraw = ctx.text().retainedDraw()
     *         .font(myFont).at(20.0f, 40.0f).color(0xFFFFFFFF);
     * // ... later, once per frame:
     * labelDraw.text(currentLabel).submit();
     *
     * // Manually-batched: several draws sharing one upload+draw. submit() returns the
     * // owning CgTextRenderer, so the last call in the batch can chain into endBatch().
     * ctx.text().beginBatch();
     * ctx.text().draw().text(line1).font(myFont).at(20.0f, 20.0f).color(0xFFFFFFFF).submit();
     * ctx.text().draw().text(line2).font(myFont).at(20.0f, 40.0f).color(0xFFFFFFFF)
     *         .submit().endBatch();
     * }</pre>
     */
    /**
     * How many draws the text renderer has made below the glyph tier they asked for.
     *
     * <p><b>A widget that draws text brackets its paint with this and repaints when it moves</b>,
     * which is the whole of what keeps an outline from going missing:</p>
     *
     * <pre>{@code
     * long before = ctx.textDegradedDrawCount();
     * ctx.text().draw()....submit();
     * if (ctx.textDegradedDrawCount() != before) repaint();   // provisional, come back for it
     * }</pre>
     *
     * <p>Glyph generation is budgeted per frame, so a glyph that misses the budget is drawn from the
     * bitmap tier or not at all while the atlas catches up over the next few frames. A tree that is
     * done settling repaints on damage alone, so nothing collects that on its own and the degraded
     * picture stands until something unrelated dirties it — a label that came out unstroked stays
     * unstroked until the mouse moves. The repaint has to be the DRAWING NODE's: damage becomes a
     * revision on its own box, and a retained ancestor layer keyed on {@code subtreeRevision} would
     * otherwise keep blitting the stale texture.</p>
     *
     * <p><b>Deliberately not {@code text().degradedDrawCount()}</b>: {@link #text()} switches the
     * instance path and flushes the quad one, so asking a question through it would cost a draw call
     * per ask. This reads a field.</p>
     */
    public long textDegradedDrawCount() {
        return textRenderer.getDegradedDrawCount();
    }

    /** Where the bound target's pixel (0,0) is on the screen: every enclosing bounded layer's origin, summed. */
    int layerOriginX, layerOriginY;

    /**
     * The screen area boxes have painted since the backdrop last captured, as one bounding rect -- what lets a
     * later glass element reuse that capture rather than take its own. A rect and not a flag, because a flag is
     * set by nearly every box in a frame and would recapture for every consumer.
     */
    private float paintedX0 = Float.MAX_VALUE, paintedY0 = Float.MAX_VALUE;
    private float paintedX1 = -Float.MAX_VALUE, paintedY1 = -Float.MAX_VALUE;

    /**
     * Records that a box painted over {@code (left, top)..(right, bottom)} of its own space, drawn through
     * {@code pose} into the bound target. Four corners and a min/max; no allocation.
     */
    public void notePainted(Matrix4f drawn, float left, float top, float right, float bottom) {
        Matrix4f pose = spatialNode == 0 ? drawn : notedScratch.set(drawToTarget()).mul(drawn);
        float m00 = pose.m00(), m10 = pose.m10(), m30 = pose.m30() + layerOriginX;
        float m01 = pose.m01(), m11 = pose.m11(), m31 = pose.m31() + layerOriginY;
        float ax = m00 * left + m10 * top + m30, ay = m01 * left + m11 * top + m31;
        float bx = m00 * right + m10 * top + m30, by = m01 * right + m11 * top + m31;
        float cx = m00 * right + m10 * bottom + m30, cy = m01 * right + m11 * bottom + m31;
        float dx = m00 * left + m10 * bottom + m30, dy = m01 * left + m11 * bottom + m31;
        paintedX0 = Math.min(paintedX0, Math.min(Math.min(ax, bx), Math.min(cx, dx)));
        paintedY0 = Math.min(paintedY0, Math.min(Math.min(ay, by), Math.min(cy, dy)));
        paintedX1 = Math.max(paintedX1, Math.max(Math.max(ax, bx), Math.max(cx, dx)));
        paintedY1 = Math.max(paintedY1, Math.max(Math.max(ay, by), Math.max(cy, dy)));
    }

    private final Matrix4f notedScratch = new Matrix4f();

    /** Whether anything noted since {@link #clearPainted} overlaps the screen rect {@code (x0, y0)..(x1, y1)}. */
    boolean paintedOver(int x0, int y0, int x1, int y1) {
        return paintedX1 > x0 && paintedX0 < x1 && paintedY1 > y0 && paintedY0 < y1;
    }

    void clearPainted() {
        paintedX0 = Float.MAX_VALUE;
        paintedY0 = Float.MAX_VALUE;
        paintedX1 = -Float.MAX_VALUE;
        paintedY1 = -Float.MAX_VALUE;
    }

    /**
     * The coverage correction every label is drawn with. A retained layer keeps the text it already holds until
     * its subtree repaints.
     */
    public void textGamma(CgTextGamma gamma) {
        textRenderer.gamma(gamma);
    }

    public CgTextRenderer text() {
        // TEXT OWNS A SECOND RENDERER with its own material, so switching to it flushes the quad path
        // and switching back flushes text -- meaning every alternation between a box and a label is two
        // draw calls. An editor row is exactly that alternation, repeated per line.
        CgTrace.add(UiTrace.FRAME, "textswitches", 1);
        beginTextPath();
        return textRenderer;
    }

    /**
     * Hands the GL program over to {@link CgTextRenderer}, flushing whatever this context had queued.
     *
     * <p><b>Text is a third instance path, not a variant of the quad one.</b> It has its own renderer,
     * its own material and its own instance buffer; this class simply does not own the bind. What it does
     * own is {@link #activePath}, whose entire purpose is that it "cannot drift out of step with what GL
     * actually has bound" — and {@code text()} was the one door out of this class that let it drift.</p>
     *
     * <p>The failure was invisible for as long as nothing interleaved. Draw every curve and then all the
     * text and it never bites; alternate them — an icon and a label, per row, down a file tree — and from
     * the second row on, {@code beginCurvePath()} early-returns because {@code activePath} still says
     * {@code CURVE}, so the icons submit against {@code text.shader} and the labels against
     * {@code gui_curve.shader}. Glyph quads evaluated by a stroke SDF come out as solid boxes, which is
     * exactly how it was reported.</p>
     *
     * <p>Flushing on the way out is the same painter's-order requirement the quad/curve switch already
     * documents: text submitted after an icon must not be drawn before it.</p>
     */
    private void beginTextPath() {
        if (activePath == InstancePath.TEXT) return;
        // A REAL PATH SWITCH, as opposed to a call to text(). The two are wildly different numbers and
        // only this one costs anything: a frame with 67 labels reports 67 text() calls whether they were
        // consecutive (one switch, one upload) or interleaved with boxes (67 switches, 67 uploads). The
        // batch below is worth exactly as much as the gap between them, so the gap has to be visible.
        CgTrace.add(UiTrace.FRAME, "textpath-switches", 1);
        renderer.flush();
        activePath = InstancePath.TEXT;
        currentTexture = null;
        // OPENING THE BATCH IS THE WHOLE POINT OF HAVING A TEXT PATH.
        //
        // CgTextRenderer.draw() tolerates being called with no batch open by auto-wrapping itself in a
        // begin/flush/end -- so every label costs its own upload, buffer map and draw. This used to open
        // the path and close the batch (endTextPath already called endBatch), which is half a pairing: the
        // tolerance meant it still rendered correctly, so the cost never surfaced as a bug.
        //
        // Measured on the icon grid: 59 flushes per frame for 57 labels, 1.21ms in quadRenderer.upload of
        // which 0.88ms was streamBuffer.ssbo.map, plus 1.35ms across 58 glFlush calls -- about 2.5ms/frame,
        // more than every icon's geometry put together.
        //
        // ENABLED, and the counters explain why the disabled version looked pointless. A frame drawing 59
        // labels reported 59 textpath-switches and only 2 quadpath-switches -- which cannot both be true
        // of a path that alternates, and is not: Draw.submit() auto-wraps each label in its own
        // begin/flush/END, and endBatch runs the restoreStateWith hook this class registers, which calls
        // bindQuadPath and puts activePath back to QUAD. So the switches were 1:1 with labels BECAUSE
        // there was no batch, and "batching wins nothing because every label switches anyway" had the
        // causation backwards.
        //
        // With one batch open for the whole text path, the auto-wrap stops, the restore runs once per
        // real switch instead of once per label, and consecutive labels coalesce into one upload.
        // endTextPath() closes it, and endFrame() closes any that survives a frame.
        //
        // UIText must NOT open its own batch underneath this one -- beginBatch throws on a batch that is
        // already open, which is what made enabling this line unusable before. Its shadow pass wanted a
        // batch so the two draws coalesce; this batch already gives it that, and more.
        textRenderer.beginBatch();
    }

    /**
     * Binds a texture for a COMPOSITE, dropping the state shadow's texture beliefs first.
     *
     * <p>Every UI shader samples on unit 0 and {@code bind(0)} is supposed to guarantee that. It cannot
     * on its own: binding any texture moves the ACTIVE unit as a side effect, and the engine binds
     * several of its own near the top of the range on the way into a draw -- {@code cg_DepthBuffer} at
     * {@code DEPTH_TEXTURE_UNIT}, the quad and curve instance buffers above it. Where the shadow's idea
     * of the active unit and the driver's disagree, the {@code glActiveTexture(0)} underneath is elided
     * as redundant and the {@code glBindTexture} lands on the engine's unit instead. Measured on a
     * 1.20.1 client: {@code activeUnit=28} with the wanted texture bound to 28.
     *
     * <p><b>What the sampler reads then is not nothing.</b> An unbound unit answers {@code (0,0,0,1)} --
     * opaque black -- and {@code gui_box} declares {@code _MainTex = "white"}, so a premultiplied
     * `over` composite either erases its destination or floods it. Both were measured in one run: a
     * fully EMPTY layer compositing to pure white, and a populated one compositing to black.
     *
     * <p>Only the three raw-bind composites use this -- {@link #blitLayer}, {@link #compositeMask} and
     * the backdrop's own. Everything else declares its sampler through {@code applyProperties}, which
     * binds the texture and sets the uniform together and is immune; forcing the unit globally instead
     * was tried and stamps on the text material's binding, which took every glyph in the application
     * off screen while sliders and icons still drew.
     */
    public void bindCompositeTexture(CgTexture2D texture) {
        currentTexture = null;
        bindTexture(texture);
    }

    public void bindTexture(CgTexture texture) {
        // ON THE QUAD PATH FIRST: a switch rebinds the material, whose own white _MainTex would then win over a texture
        // bound before it.
        beginQuadPath();
        if (texture == currentTexture) return;
        // Whatever is queued was submitted against the texture bound NOW: switching first would draw it
        // with this one. A cached icon relies on this -- it submits its quad and leaves the flush to
        // whoever changes the texture next, so a run of icons from the atlas is one draw.
        renderer.flushQuads();
        renderer.bindTexture(0, texture);   // recorded: the draw binds it when it executes
        currentTexture = texture;
    }

    /**
     * Starts a fluent quad, already carrying this context's {@code PoseStack} transform.
     *
     * <p>Build and {@code submit()} in one expression — the returned instance is
     * {@code CgQuadRenderer}'s shared per-renderer scratch object, so holding it past the
     * {@code submit()} is not safe (the next {@code quad()} resets and reuses it).</p>
     *
     * <pre>{@code
     * ctx.bindTexture(tex);
     * ctx.quad().at(x, y).size(w, h).uv(u0, v0, u1, v1).color(argb).submit();
     * ctx.flush();   // submit() only queues — this is what draws
     * }</pre>
     *
     * <p><b>Never call {@code .pose(...)} on the result.</b> {@link CgUiRenderer#quad()} has already
     * applied the active pose, and overwriting it drops the {@code uiScale}/element transform that
     * every logical-space coordinate in this API assumes. Defaults are the full UV rect and opaque
     * white, so a solid fill needs neither.</p>
     */
    public CgQuadRenderer.Quad quad() {
        beginQuadPath();
        CgQuadRenderer.Quad quad = renderer.quad();
        return currentMaterial == layerBlitMaterial ? quad.custom2(CgShapeTable.PREMULTIPLIED) : quad;
    }

    /** The material every box draws through. A drawable on another material comes back to it with {@link #withMaterial}. */
    public CgMaterial boxMaterial() {
        return boxModelMaterial;
    }

    /** The shapes this frame's quads name. Inside a frame. @see CgShapeTable */
    public CgShapeTable shapes() {
        return recording.shapes();
    }

    /** The one rect scratch, handed out by {@link #rect()}. */
    private final CgUiRect.Draw rectScratch = new CgUiRect.Draw();

    /**
     * Starts a rounded and/or bordered rectangle — {@link #quad()}'s counterpart for anything the
     * frame batch cannot express, and the path {@code CgUiRect} itself draws through.
     *
     * <pre>{@code
     * ctx.rect().at(x, y).size(w, h).fillColor(argb).submit();
     * ctx.rect().at(x, y).size(w, h).radius(6f, 6f).border(1f, edge).fillColor(bg).submit();
     * }</pre>
     *
     * <p>A plain rect (no radius, no border) still goes through the batch; {@code submit()} picks.
     * The fill tint is {@link #getColor()} at submit time, so set it first as any drawable expects.</p>
     *
     * <p><b>Build and {@code submit()} in one expression</b> — the returned instance is this context's
     * shared scratch, exactly as {@link #quad()}'s is, and the next {@code rect()} resets it.</p>
     */
    public CgUiRect.Draw rect() {
        return rectScratch.begin(this);
    }

    /**
     * Starts a Bézier stroke, with this context's pose already applied — the curve counterpart to
     * {@link #quad()}, and identical in every convention that matters.
     *
     * <pre>{@code
     * ctx.curve().line(x0, y0, x1, y1).width(2f).color(argb).submit();
     * ctx.curve().from(x0, y0).via(cx, cy).to(x1, y1).width(4f, 1f).colors(a, b).submit();
     * ctx.flush();   // submit() only queues — this is what draws
     * }</pre>
     *
     * <p>Widths and coordinates are both in logical units: the pose's scale is applied to the stroke
     * width as well as the geometry, so a 2px stroke stays 2 logical px at any {@code uiScale},
     * exactly as a 2px border does.</p>
     *
     * <p><b>Never call {@code .pose(...)} on the result</b> — {@link CgUiRenderer#curve()} has already
     * applied it, and overwriting it silently drops {@code uiScale} and the element transform. Same
     * rule, same reason, as {@link #quad()}.</p>
     *
     * <p>The returned object is {@code CgVectorRenderer}'s shared per-renderer scratch instance, so
     * build it and {@code submit()} in one expression rather than holding it — the next
     * {@code curve()} call resets and reuses it. Use {@code retainedCurve()} on the renderer for
     * something held across frames.</p>
     *
     * <p>Calling this switches the bound material to {@code gui_curve.shader}, flushing any queued
     * quads first so painter's order is preserved; the next {@link #quad()} switches back. Alternating
     * the two per element therefore costs a draw call each way — batch strokes together where it is
     * convenient, but correctness never depends on doing so.</p>
     */
    public CgVectorRenderer.Curve curve() {
        beginCurvePath();
        return renderer.curve();
    }

    /**
     * Starts a filled triangle, with this context's pose already applied — the fill-mode twin of
     * {@link #curve()}. Goes through the exact same material path as {@link #curve()} (it shares
     * one {@code CgVectorRenderer} and one {@code gui_curve.shader} binding, not a third one), so
     * switching between {@code quad()}/{@code curve()}/{@code triangle()} costs a flush only when
     * moving to or from the quad path — alternating {@code curve()} and {@code triangle()} is free.
     *
     * <pre>{@code
     * ctx.triangle().points(x0, y0, x1, y1, x2, y2).color(argb).submit();
     * ctx.flush();
     * }</pre>
     *
     * <p><b>Never call {@code .pose(...)} on the result</b> — same rule as {@link #quad()}/{@link
     * #curve()}, for the same reason.</p>
     */
    public CgVectorRenderer.Triangle triangle() {
        beginCurvePath();
        return renderer.triangle();
    }

    /**
     * Starts a cell — one span of a scanline tessellation, with exact-area antialiasing on the edges it
     * marks soft; see {@link CgVectorRenderer.Cell}. Same material path as {@link #curve()} and
     * {@link #triangle()}, so mixing the three costs nothing.
     *
     * <pre>{@code
     * ctx.filledCell().points(x0, y0, x1, y1, x2, y2, x3, y3)
     *         .softEdges(CgVectorRenderer.CELL_LEFT | CgVectorRenderer.CELL_RIGHT)
     *         .color(argb).submit();
     * }</pre>
     *
     * <p><b>Never call {@code .pose(...)} on the result</b> — same rule as {@link #quad()}.</p>
     */
    public CgVectorRenderer.Cell filledCell() {
        beginCurvePath();
        return renderer.filledCell();
    }

    /**
     * Makes the quad path current, flushing and unbinding the curve path if it was.
     *
     * <p>Rebinds {@link #currentMaterial} rather than {@link #boxModelMaterial}: a {@link
     * #withMaterial} body that draws a curve and then a quad must come back to <em>its own</em>
     * material, not to the default one, or the rest of that body silently renders with the wrong
     * shader.</p>
     */
    private void beginQuadPath() {
        if (activePath == InstancePath.QUAD) return;
        // ALL THREE SWITCHES COUNTED, because knowing there are 59 text switches for 59 labels says
        // every label is preceded by something non-text and NOT what. The editor's lines carry no
        // background (`.__line__` sets none), so consecutive lines ought to batch -- and measurably do
        // not. Whatever takes the path away between them is the thing to move, and only the counts can
        // name it: quads (a fill, an image) and curves (every SVG icon) are different problems.
        CgTrace.add(UiTrace.FRAME, "quadpath-switches", 1);
        endTextPath();
        renderer.flushCurves();
        // bindQuadPath sets activePath itself — the one place it is assigned for this path.
        bindQuadPath(currentMaterial != null ? currentMaterial : boxModelMaterial);
        currentTexture = null;
    }

    /** Makes the curve path current, flushing and unbinding the quad path if it was. */
    private void beginCurvePath() {
        if (activePath == InstancePath.CURVE) return;
        // @see #beginQuadPath -- every SVG icon draws through here, and an icon beside a label is one
        // alternation per row in any list.
        CgTrace.add(UiTrace.FRAME, "curvepath-switches", 1);
        endTextPath();
        renderer.flushQuads();
        activePath = InstancePath.CURVE;
        // Layer opacity is a material property, so it has to be re-applied on the material actually
        // being bound — the value living on boxModelMaterial says nothing about this one.
        curveMaterial.applyProperties(layerOpacityBinder);
        renderer.useCurveMaterial(activeCurveMaterial());
        currentTexture = null;
    }

    private CgMaterial activeCurveMaterial() {
        return curveMaterialOverride != null ? curveMaterialOverride : curveMaterial;
    }

    /**
     * Runs {@code body} with the curve path drawing through {@code material} instead of
     * {@code gui_curve.shader}. Everything the path does otherwise — the pose, the flushes, the switch
     * away from quads and text — is unchanged, so a body submits curves, triangles and quads exactly as
     * it would anywhere else.
     */
    void withCurveMaterial(CgMaterial material, Runnable body) {
        flush();
        CgMaterial saved = curveMaterialOverride;
        curveMaterialOverride = material;
        if (activePath == InstancePath.CURVE) renderer.useCurveMaterial(material);
        try {
            body.run();
            flush();
        } finally {
            curveMaterialOverride = saved;
            if (activePath == InstancePath.CURVE) {
                curveMaterial.applyProperties(layerOpacityBinder);
                renderer.useCurveMaterial(activeCurveMaterial());
            }
        }
    }

    /** Where a small icon's fills are rasterised once and drawn from. */
    public SvgRasterCache svgRaster() {
        return svgRaster;
    }

    /** The premultiplied composite material. @see #blitLayer */
    CgMaterial layerBlitMaterial() {
        return layerBlitMaterial;
    }

    /** Lifts every clip rect until {@link #resumeScissor}; for a draw into a target the clips do not describe. */
    ScissorStack.Saved suspendScissor() {
        flush();
        ScissorStack.Saved saved = scissorStack.suspend();
        reapplyScissorFor(targetHeight());
        return saved;
    }

    void resumeScissor(ScissorStack.Saved saved) {
        flush();
        scissorStack.resume(saved);
        reapplyScissorFor(targetHeight());
    }

    /**
     * Closes any open text batch before another path binds over it.
     *
     * <p>{@code endBatch()} is documented as lenient — a no-op when no batch is open — so this costs a
     * field read in the overwhelmingly common case where a caller let the text renderer auto-wrap each
     * draw. It matters for the caller that opened one explicitly and then drew a quad: those glyphs would
     * otherwise flush later, against whatever material had been bound since, and out of order.</p>
     */
    /** Symmetric with {@link #beginTextPath()}; both are guarded on {@code activePath} so the pairing holds. */
    private void endTextPath() {
        if (activePath == InstancePath.TEXT) textRenderer.endBatch();
    }

    /**
     * Binds a quad-path material and records that the quad path is now current.
     *
     * <p>Every quad-material bind in this class goes through here so {@link #activePath} cannot drift
     * out of step with what GL actually has bound — the failure that would produce is a draw against
     * the wrong shader, which renders something rather than failing.</p>
     */
    private void bindQuadPath(CgMaterial material) {
        // THE TEXT BATCH DIES HERE TOO, not only in endTextPath.
        //
        // This method is the one place activePath becomes QUAD, and several callers reach it WITHOUT
        // going through beginQuadPath: beginFrame, withMaterial, blitLayer, and the restoreStateWith
        // hook. Each of those left activePath at QUAD while a text batch was still open, so the next
        // text() saw "not TEXT", opened a second batch, and CgTextRenderer threw
        // "beginBatch() called without a matching endBatch()". Guarding only the tidy path is guarding
        // the route nothing takes.
        //
        // Re-entrant by construction and safe: endBatch runs the restore hook, which lands back here.
        // endBatch clears batchActive BEFORE invoking the hook and is documented lenient, so the inner
        // call returns immediately.
        if (activePath == InstancePath.TEXT) textRenderer.endBatch();
        renderer.useMaterial(material);
        activePath = InstancePath.QUAD;
    }

    /**
     * Draws everything submitted and not yet drawn — <b>on every path, text included</b>.
     *
     * <h3>The text batch is pending work, and this method is what "pending" is measured against</h3>
     *
     * <p>Every caller of this uses it to mean "the GPU has what I gave it, I am about to change
     * something it would otherwise be drawn under". {@link #pushScissor} and {@link #popScissor} change
     * the clip rectangle; {@link #withMaterial} binds a different shader; {@link #endLayerFbo} closes a
     * GL scope and puts the previous render TARGET back. All four flushed the quad renderer and left an
     * open text batch behind, so its glyphs were drawn later, under whatever state was current then.</p>
     *
     * <p>That was invisible until this class started holding a batch open across labels. Before it did,
     * {@code Draw.submit()} auto-wrapped each label in its own begin/flush/end, so text was never pending
     * across anything and {@code renderer.flush()} really was the whole of it. Opening one batch for the
     * text path is what made this method a lie.</p>
     *
     * <p>The symptom is not a wrong colour, it is a <b>missing label</b>: a tree row's glyphs queued
     * inside the list's scissor and flushed after {@code popScissor}, clipped to a rectangle they are no
     * longer inside. Which rows vanish depends on where the batch happened to end, so it reads as
     * intermittent — the project tree lost its root one run and a child the next.</p>
     *
     * <p>Ending the batch runs the restore hook, which lands in {@link #bindQuadPath} and puts
     * {@code activePath} back to QUAD; the next {@link #text()} reopens one. Consecutive labels with
     * nothing between them still coalesce, which is the whole of what the batch was for.</p>
     */
    public void flush() {
        endTextPath();
        renderer.flush();
    }

    /**
     * {@link #flush}, then ends the target's open pass, so what it holds so far is what a pass recorded next reads:
     * for a caller about to read the target. Drawing goes on in a pass of its own.
     */
    void drain() {
        flush();
        recorder.endPass();
    }

    /**
     * Whether a logical-space box could put anything on screen — a cheap reject before building geometry.
     *
     * <h3>Why this lives here and not on the drawable</h3>
     *
     * <p>A {@code CgUiDrawable} is handed a rect and nothing else: no projection, no pose, no viewport.
     * That looks like it makes culling impossible, and the natural repair — passing a view-projection down
     * the draw call — is the wrong one, because <b>this context already holds all three</b>. The drawable
     * does not need to be told where the screen is; it needs to be able to ask.</p>
     *
     * <h3>A rect test, deliberately NOT a frustum</h3>
     *
     * <p>{@code CgTextCuller} tests a {@link com.crystalgraphics.render.CgViewFrustum} because a 3D
     * text layout can sit at any orientation in a perspective view. The UI cannot: {@link #beginFrame}
     * installs {@code ortho(0, w, h, 0)}, so post-pose coordinates <em>are</em> window pixels and the
     * visible region is an axis-aligned rectangle. Against that, six plane dot-products would be a slower
     * way to compute an answer an overlap test gets exactly.</p>
     *
     * <p>It also honours the <b>scissor</b>, which a frustum knows nothing about. Inside a clipped
     * scroller the visible region is the clip rect, not the window, and that is usually far smaller —
     * which is exactly the case where culling pays.</p>
     *
     * <p>All four corners are transformed, not two: the pose may rotate, and a min/max over two corners
     * silently reports the wrong box the moment anything does.</p>
     *
     * <p>Conservative by construction — it answers "could this be visible", never "is it". A false
     * positive costs a draw that contributes nothing; a false negative is a missing icon, so the test is
     * an overlap on the transformed AABB and nothing cleverer.</p>
     */
    /**
     * The pose's uniform scale — how many device pixels one logical unit currently covers.
     *
     * <p>What a level-of-detail decision has to key on: the same logical size is twice the pixels at
     * {@code uiScale} 2, and a mesh chosen from the logical size alone would be visibly coarse on a HiDPI
     * display and correct everywhere else — the worst kind of bug to reproduce.</p>
     */
    public float deviceScale() {
        Matrix4f m = targetPose();
        float sx = (float) Math.sqrt(m.m00() * m.m00() + m.m01() * m.m01());
        float sy = (float) Math.sqrt(m.m10() * m.m10() + m.m11() * m.m11());
        return Math.max(sx, sy);
    }

    /**
     * Whether the pose is axis-aligned — no rotation and no skew, only scale and translation.
     *
     * <p>The precondition for {@link #snapXToDevicePixel}/{@link #snapYToDevicePixel}: under a rotation
     * "the device pixel grid" has no axis-aligned preimage in logical space, so there is no logical
     * coordinate that lands a shape on it and snapping one axis at a time is meaningless.</p>
     */
    public boolean isPoseAxisAligned() {
        Matrix4f m = targetPose();
        return Math.abs(m.m01()) < 1e-5f && Math.abs(m.m10()) < 1e-5f;
    }

    /**
     * The logical X that lands on the nearest whole <b>device</b> pixel boundary.
     *
     * <h3>Why anything cares</h3>
     *
     * <p>Icon artwork is hinted: a JetBrains 16px icon has every edge on an integer coordinate so that at
     * 1:1 each edge falls exactly on a pixel boundary and needs no antialiasing at all. Landing that
     * artwork half a pixel off puts <em>every</em> edge mid-pixel instead, and the icon is antialiased
     * where it was designed to be crisp. Measured on {@code javaScript.svg} at 16px: <b>4 partially
     * covered pixels at an integer origin, 44 at a half-pixel one</b> — the same picture with eleven
     * times the blur, which is exactly what "our icons look muddy next to IntelliJ's" turned out to
     * mean.</p>
     *
     * <p>Snapping in <em>logical</em> space is not the same thing and does not work: the pose carries a
     * translation of its own (a scrolled list, a panel at a fractional offset), so a whole logical
     * coordinate is routinely a fractional device one. The rounding has to happen after the pose, which
     * is why this lives here rather than at the call site.</p>
     *
     * <p>Returns {@code logicalX} unchanged when {@link #isPoseAxisAligned} is false.</p>
     */
    public float snapXToDevicePixel(float logicalX) {
        Matrix4f m = targetPose();
        if (!isPoseAxisAligned() || Math.abs(m.m00()) < 1e-6f) return logicalX;
        float device = m.m00() * logicalX + m.m30();
        return (Math.round(device) - m.m30()) / m.m00();
    }

    /** The Y-axis twin of {@link #snapXToDevicePixel}. */
    public float snapYToDevicePixel(float logicalY) {
        Matrix4f m = targetPose();
        if (!isPoseAxisAligned() || Math.abs(m.m11()) < 1e-6f) return logicalY;
        float device = m.m11() * logicalY + m.m31();
        return (Math.round(device) - m.m31()) / m.m11();
    }

    public boolean isVisible(float x, float y, float w, float h) {
        Matrix4f m = targetPose();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int corner = 0; corner < 4; corner++) {
            float cx = (corner & 1) == 0 ? x : x + w;
            float cy = (corner & 2) == 0 ? y : y + h;
            float px = m.m00() * cx + m.m10() * cy + m.m30();
            float py = m.m01() * cx + m.m11() * cy + m.m31();
            minX = Math.min(minX, px);
            minY = Math.min(minY, py);
            maxX = Math.max(maxX, px);
            maxY = Math.max(maxY, py);
        }

        float clipX0 = 0f, clipY0 = 0f, clipX1 = targetWidth(), clipY1 = targetHeight();
        if (scissorStack.hasScissor()) {
            // ScissorStack holds TOP-LEFT rects in the target's pixels -- the same space the pose just
            // produced, so no flip. @see ScissorStack#applyScissorIfNeeded
            clipX0 = scissorStack.currentX();
            clipX1 = clipX0 + scissorStack.currentW();
            clipY0 = scissorStack.currentY();
            clipY1 = clipY0 + scissorStack.currentH();
        }
        return maxX >= clipX0 && minX <= clipX1 && maxY >= clipY0 && minY <= clipY1;
    }

    /**
     * The width of whatever is being drawn into right now — the screen, or the innermost layer FBO.
     *
     * <p><b>A scissor rect and a cull bound are in the TARGET's pixels, and the target is not always the
     * screen.</b> Every pooled layer is screen-sized, so flipping against {@code screenHeight} was right
     * for all of them and nothing said otherwise. A window's snapshot is the first target sized to
     * something else — the window — and inside it the flip landed every clip {@code screen - window}
     * pixels too high: the editor's own {@code > .__content__} clip then scissored the bottom of the
     * photograph away, so a minimised window's preview showed its top half over flat panel colour while
     * the live preview of the same window, drawn straight to the screen, was whole. Invisible while the
     * photographed window was near screen height, which a maximised editor is; a floating one is not.
     * The standing scissor rule covers the INHERITED rect, which the snapshot already clears — this is
     * the rect pushed during the render, which has to be flipped against the buffer it lands in.</p>
     */
    private int targetWidth() {
        return layerStack.isEmpty() ? screenWidth : layerStack.peek().target().getWidth();
    }

    /** @see #targetWidth() */
    private int targetHeight() {
        return layerStack.isEmpty() ? screenHeight : layerStack.peek().target().getHeight();
    }

    /**
     * Pushes a new clip rect, intersected with whatever scissor is already active, and enables
     * {@code GL_SCISSOR_TEST} against it. Pair with {@link #popScissor()}.
     *
     * <p>{@code x}/{@code y}/{@code w}/{@code h} are in the same logical, top-left-origin,
     * pre-{@code uiScale} layout space as everything else this context draws (e.g.
     * {@link #fillRect}) — <b>not</b> physical screen pixels. This method converts internally
     * via the current {@link #poseStack} transform before touching {@link ScissorStack}, since
     * {@code glScissor} needs real physical framebuffer pixels in GL's bottom-left-origin
     * convention, and the inverted-ortho projection {@link #beginFrame} sets up for vertex
     * rendering has no effect on the separate scissor-test raster stage.</p>
     */
    public void pushScissor(float x, float y, float w, float h) {
        Matrix4f m = targetPose();
        float physX0 = m.m00() * x + m.m10() * y + m.m30();
        float physY0 = m.m01() * x + m.m11() * y + m.m31();
        float physX1 = m.m00() * (x + w) + m.m10() * (y + h) + m.m30();
        float physY1 = m.m01() * (x + w) + m.m11() * (y + h) + m.m31();


        int physX = (int) Math.floor(Math.min(physX0, physX1));
        int physY = (int) Math.floor(Math.min(physY0, physY1));
        int physW = (int) Math.ceil(Math.max(physX0, physX1)) - physX;
        int physH = (int) Math.ceil(Math.max(physY0, physY1)) - physY;
        int clip = squareClip(physX, physY, Math.max(0, physW), Math.max(0, physH));
        if (clip > 0) {
            CgTrace.add(UiTrace.FRAME, "clips-square", 1);
            scissorStack.pushClip(physX, physY, Math.max(0, physW), Math.max(0, physH), clip, clipEntry);
            setClip(clip);
            return;
        }
        // A SCISSOR IS A FLUSH TOO: a clipped container costs a draw call to enter and another to leave.
        CgTrace.add(UiTrace.FRAME, "scissors", 1);
        flush();
        // Stored TOP-LEFT, in the target's physical pixels; the flip to GL's bottom-left happens when the
        // rect is APPLIED, against whichever buffer is bound at that moment. @see ScissorStack#applyScissorIfNeeded
        if (spatialNode == 0) {
            scissorStack.pushScissor(physX, physY, Math.max(0, physW), Math.max(0, physH));
        } else {
            // In the node's space as well, which is what moves with it.
            Matrix4f p = poseStack.last().pose();
            float ax = p.m00() * x + p.m10() * y + p.m30(), ay = p.m01() * x + p.m11() * y + p.m31();
            float bx = p.m00() * (x + w) + p.m10() * (y + h) + p.m30(), by = p.m01() * (x + w) + p.m11() * (y + h) + p.m31();
            scissorStack.pushScissor(physX, physY, Math.max(0, physW), Math.max(0, physH), spatialNode,
                    Math.min(ax, bx), Math.min(ay, by), Math.max(ax, bx), Math.max(ay, by));
        }
        scissorStack.applyScissorIfNeeded(targetHeight());
    }

    /**
     * Re-applies the current clip to whatever buffer is bound now. For a caller that has changed the
     * target or the stack itself and needs GL to agree with it — a window snapshot restoring the clip it
     * set aside, for instance.
     */
    public void reapplyScissor() {
        reapplyScissorFor(targetHeight());
    }

    /** {@link #reapplyScissor} against a buffer that is not yet the one {@link #targetHeight} names â€”
     * a layer being entered, whose frame is on the stack before it is bound. */
    private void reapplyScissorFor(int targetHeight) {
        if (scissorStack.hasScissor()) scissorStack.applyScissorIfNeeded(targetHeight);
        else scissorStack.clearScissorIfNeeded();
    }

    /**
     * Pops the topmost clip rect, restoring the parent scissor (if any) or disabling
     * {@code GL_SCISSOR_TEST} entirely once the stack is empty.
     */
    public void popScissor() {
        if (scissorStack.topClip() != 0) {
            setClip(scissorStack.topRestore());
            scissorStack.popScissor();
            return;
        }
        flush();
        scissorStack.popScissor();
        if (scissorStack.hasScissor()) {
            scissorStack.applyScissorIfNeeded(targetHeight());
        } else {
            scissorStack.clearScissorIfNeeded();
        }
    }

    /** The {@link CgClipTable} entry every draw is stamped with; 0 when no clip is active. */
    private int clipEntry;

    /** Off with {@code -Dcrystalgui.paint.squareClips=false}: every {@link #pushScissor} is a scissor. */
    private static final boolean SQUARE_CLIPS = !"false".equals(System.getProperty("crystalgui.paint.squareClips"));

    /**
     * {@code (x, y, w, h)}, whole pixels of the bound target, as a clip entry each draw is stamped with: no flush and
     * no batch break, where a scissor is both. 0 where a scissor must do -- inside a target with a space of its own,
     * whose materials read no clip; under a node that is not a whole-pixel translation; past the chain's depth.
     */
    private int squareClip(int x, int y, int w, int h) {
        if (!SQUARE_CLIPS || !frameActive || nodesSuspended > 0) return 0;
        int tx = 0, ty = 0;
        if (spatialNode != 0) {
            Matrix4f world = drawToTarget();
            if (world.m00() != 1f || world.m01() != 0f || world.m10() != 0f || world.m11() != 1f) return 0;
            float fx = world.m30(), fy = world.m31();
            if (fx != (int) fx || fy != (int) fy) return 0;
            tx = (int) fx;
            ty = (int) fy;
        }
        int entry = recording.clips().addPixelRect(clipEntry, spatialNode, x - tx, y - ty, x - tx + w, y - ty + h,
                targetHeight());
        return Math.max(entry, 0);
    }

    /**
     * Clips everything drawn until {@link #popRoundedClip} to a rounded rectangle, antialiased like a rect's own
     * edge, with no layer. The clip is a {@link CgClipTable} entry stamped on every quad, curve and glyph, so it
     * costs no target, no clear and no flush; it is what a rounded {@code overflow: hidden} uses. Clips nest: a
     * draw inside two is clipped by both.
     *
     * <pre>{@code
     * if (ctx.pushRoundedClip(0, 0, w, h, rx, ry, border)) {
     *     paintChildren();
     *     ctx.popRoundedClip();
     * } else {
     *     // a mask layer
     * }
     * }</pre>
     *
     * <ul>
     *   <li>Answers false, and pushes nothing, when {@link CgClipTable#MAX_DEPTH} clips are already open or the
     *       pose collapses the rect. Any rotation, skew or scale is fine.</li>
     *   <li>A layer begun inside it draws unclipped, and is clipped when composited back.</li>
     *   <li>A material that does not read {@code CG_CLIP_*_COVERAGE} ignores it; every CrystalGUI material
     *       reads it.</li>
     * </ul>
     *
     * @param x      the rect in the current pose's space, as {@link #pushScissor} takes it
     * @param rx     horizontal corner radii in the same units: top-left, top-right, bottom-right, bottom-left
     * @param ry     vertical corner radii, same order
     * @param border edge widths left, top, right, bottom, cut away as a mask's transparent border is; null
     *               for none
     */
    public boolean pushRoundedClip(float x, float y, float w, float h, float[] rx, float[] ry,
                                   @Nullable float[] border) {
        int entry = recording.clips().add(clipEntry, spatialNode, poseStack.last().pose(), targetHeight(), x, y,
                x + w, y + h, rx, ry, border);
        if (entry < 0) return false;
        CgTrace.add(UiTrace.FRAME, "clips-rounded", 1);
        setClip(entry);
        return true;
    }

    /** Off with {@code -Dcrystalgui.paint.segments=false}: a box's paint shares chunks with its neighbours'. */
    private static final boolean SEGMENTS = !"false".equals(System.getProperty("crystalgui.paint.segments"));

    /** Off with {@code -Dcrystalgui.paint.replay=false}: every box painted every frame. Needs segments. */
    public static final boolean REPLAY = SEGMENTS && !"false".equals(System.getProperty("crystalgui.paint.replay"));

    /**
     * {@code -Dcrystalgui.paint.replayCheck=true}: a segment whose key holds is painted anyway and compared with what it
     * kept, and a box that drew otherwise under an unchanged key is named once -- a widget whose picture changes
     * without saying so. Every box paints, so it costs what replay saves.
     */
    public static final boolean REPLAY_CHECK = REPLAY && Boolean.getBoolean("crystalgui.paint.replayCheck");

    /** Snapshots kept past which they are all dropped, and every kept segment with them. */
    private static final int KEPT_SNAPSHOTS = 4096;

    /** Where each open segment started, in {@link CgPassRecorder#chunksTaken}: segments nest. */
    private long[] segmentStarts = new long[8];
    /** Per open segment, the recorder's {@link CgPassRecorder#stateChanges} where it started. */
    private long[] segmentStates = new long[8];
    /** Per open segment: the recording's operations and clip entries, then the clip entry and nodes it began under. */
    private int[] segmentMarks = new int[8 * SEGMENT_MARKS];
    private static final int SEGMENT_MARKS = 5;
    private int segments;

    /** The snapshots kept segments bind, which outlive the recordings. @see CgReplay */
    private final CgBindingTable keptBindings = new CgBindingTable();
    private long replayEpoch, seenFontGeneration, seenFontContent, seenEvictions;
    private int seenIconResets;

    /**
     * Starts a box's own paint: what the recorder takes until {@link #endSegment} is that box's and nothing else's.
     * Everything queued before is flushed, so no draw of another box shares a chunk with it. Segments nest -- a
     * widget may paint another subtree from its own content, as a window thumbnail does.
     *
     * <pre>{@code
     * ctx.beginSegment();
     * paintSelf(box);
     * node.paintContent(ctx, box);
     * ctx.endSegment();
     * paintChildren(box);
     * }</pre>
     */
    public void beginSegment() {
        if (!SEGMENTS) return;
        flush();
        if (segments == segmentStarts.length) {
            segmentStarts = Arrays.copyOf(segmentStarts, segments * 2);
            segmentStates = Arrays.copyOf(segmentStates, segments * 2);
            segmentMarks = Arrays.copyOf(segmentMarks, segments * 2 * SEGMENT_MARKS);
        }
        int s = segments++, m = s * SEGMENT_MARKS;
        segmentStarts[s] = recorder.chunksTaken();
        segmentStates[s] = recorder.stateChanges();
        segmentMarks[m] = recording.operations();
        segmentMarks[m + 1] = recording.clips().count();
        segmentMarks[m + 2] = clipEntry;
        segmentMarks[m + 3] = spatialNode;
        segmentMarks[m + 4] = effectNode;
    }

    /** Ends the innermost {@link #beginSegment}; its draws are flushed into chunks of their own. */
    public void endSegment() {
        endSegment(null);
    }

    /**
     * {@link #endSegment()}, keeping what the segment recorded in {@code keep} for {@link #replay}: answers whether it
     * did. Not kept: a segment that recorded anything but draws -- a request, a layer, a capture -- or changed pass or
     * scissor, drew in another node, or binds a texture made for this frame alone. With replay off, nothing is kept.
     */
    public boolean endSegment(@Nullable CgReplay keep) {
        if (!SEGMENTS) return false;
        flush();
        int s = --segments, m = s * SEGMENT_MARKS;
        CgTrace.add(UiTrace.FRAME, "segments", 1);
        CgTrace.add(UiTrace.FRAME, "segment-chunks", recorder.chunksTaken() - segmentStarts[s]);
        if (keep == null || !REPLAY) return false;
        boolean kept = recorder.stateChanges() == segmentStates[s] && recording.operations() == segmentMarks[m]
                && keep.capture(recorder, segmentStarts[s], recording, keptBindings, segmentMarks[m + 1],
                        segmentMarks[m + 2], segmentMarks[m + 3], segmentMarks[m + 4]);
        if (!kept) keep.clear();
        CgTrace.add(UiTrace.FRAME, kept ? "segments-kept" : "segments-unkept", 1);
        return kept;
    }

    /**
     * Records what {@code kept} holds in place of drawing it again, under the clip entry and nodes current now:
     * answers false, having recorded nothing, when it cannot. Only for a segment that would come out the same, under a
     * {@link #replayEpoch} that has not moved since it was kept.
     *
     * <pre>{@code
     * if (sameKey && ctx.replay(stretch)) return;
     * ctx.beginSegment();
     * ... paint ...
     * kept = ctx.endSegment(stretch);
     * }</pre>
     */
    public boolean replay(CgReplay kept) {
        if (!REPLAY) return false;
        flush();
        long before = recorder.chunksTaken();
        if (!kept.replay(recorder, recording, clipEntry, spatialNode, effectNode)) return false;
        CgTrace.add(UiTrace.FRAME, "segments-replayed", 1);
        CgTrace.add(UiTrace.FRAME, "chunks-replayed", recorder.chunksTaken() - before);
        return true;
    }

    /**
     * What every kept segment was recorded under: moved when a glyph atlas evicts a page or gains glyphs, the icon
     * raster clears its atlas, or the kept snapshots are dropped -- whatever changes what a kept record points at.
     * Evictions and icon resets are read live, since they move a placement in the middle of a frame.
     */
    public long replayEpoch() {
        long evictions = CgGlyphAtlas.evictions();
        int iconResets = svgRaster.resets();
        if (evictions != seenEvictions || iconResets != seenIconResets) {
            CgTrace.add(UiTrace.FRAME, iconResets != seenIconResets ? "replay-epoch-icons" : "replay-epoch-evicted", 1);
            seenEvictions = evictions;
            seenIconResets = iconResets;
            replayEpoch++;
        }
        return replayEpoch;
    }

    /** Ends the innermost {@link #pushRoundedClip} that answered true. */
    public void popRoundedClip() {
        setClip(recording.clips().parent(clipEntry));
    }

    /** The entry {@link CgUiRenderer} stamps on each instance. */
    int clipEntry() {
        return clipEntry;
    }

    /** Per instance, so a change needs no flush: what is queued keeps the entry it was stamped with. */
    private void setClip(int entry) {
        clipEntry = entry;
        textRenderer.clip(entry);
    }

    // ── Spatial nodes ───────────────────────────────────────────────────────

    /** Off with {@code -Dcrystalgui.paint.nodes=false}: every draw baked into its target's pixels, as before nodes. */
    public static final boolean NODES = !"false".equals(System.getProperty("crystalgui.paint.nodes"));

    /** The spatial and effect nodes draws are recorded in: 0 and 0 for the bound target's own space. */
    private int spatialNode, effectNode;
    /** Open targets with a space of their own -- a snapshot, a capture -- inside which no node is made. */
    private int nodesSuspended;
    /** Per spatial node of this frame, its world in the frame target's pixels as recorded: a b c d tx ty. */
    private float[] nodeWorlds = new float[6 * 64];
    /** The current draw space into the bound target's pixels, and back; recomputed after a node or target change. */
    private final Matrix4f drawToTarget = new Matrix4f(), targetToDraw = new Matrix4f(), targetPose = new Matrix4f();
    private boolean drawToTargetValid;
    /** The view owner of the passes being recorded. @see CgPassRecorder#view */
    private int viewOwner;

    /**
     * A spatial node inside the current one, for content whose origin is {@code origin} in the current draw space;
     * 0 where none can be made -- inside a target with a space of its own, past the tree's size, or with nodes off.
     * Content entered into it draws in its own space: {@code origin}'s inverse times the draw space's base. A node
     * moves by the values a compositor gives it, with nothing recorded again.
     *
     * <pre>{@code
     * Matrix4f origin = new Matrix4f(base).mul(box.localToWorld()).translate(-scrollLeft, -scrollTop, 0f);
     * int content = ctx.addNode(origin, true);
     * if (content != 0) {
     *     int outer = ctx.enterNode(content);
     *     paintChildren(new Matrix4f(origin).invert().mul(base));
     *     ctx.enterNode(outer);
     * }
     * }</pre>
     *
     * @param movable whether a compositor moves it: draws under it never batch past draws outside it
     */
    public int addNode(Matrix4f origin, boolean movable) {
        if (!NODES || nodesSuspended > 0 || !frameActive) return 0;
        CgSpatialTree tree = recording.spatial();
        if (tree.count() >= CgSpatialTree.MAX_NODES) return 0;
        float a = origin.m00(), b = origin.m01(), c = origin.m10(), d = origin.m11(), tx = origin.m30(), ty = origin.m31();
        // In a node, the origin IS the local affine; at 0 it is in the bound target, which sits at the layer origin.
        if (spatialNode == 0) {
            tx += layerOriginX;
            ty += layerOriginY;
        }
        int node = tree.add(spatialNode, a, b, c, d, tx, ty, movable);
        if (nodeWorlds.length < (node + 1) * 6) nodeWorlds = Arrays.copyOf(nodeWorlds, nodeWorlds.length * 2);
        int o = node * 6;
        if (spatialNode == 0) {
            nodeWorlds[o] = a;
            nodeWorlds[o + 1] = b;
            nodeWorlds[o + 2] = c;
            nodeWorlds[o + 3] = d;
            nodeWorlds[o + 4] = tx;
            nodeWorlds[o + 5] = ty;
        } else {
            int p = spatialNode * 6;
            float pa = nodeWorlds[p], pb = nodeWorlds[p + 1], pc = nodeWorlds[p + 2], pd = nodeWorlds[p + 3];
            nodeWorlds[o] = pa * a + pc * b;
            nodeWorlds[o + 1] = pb * a + pd * b;
            nodeWorlds[o + 2] = pa * c + pc * d;
            nodeWorlds[o + 3] = pb * c + pd * d;
            nodeWorlds[o + 4] = pa * tx + pc * ty + nodeWorlds[p + 4];
            nodeWorlds[o + 5] = pb * tx + pd * ty + nodeWorlds[p + 5];
        }
        return node;
    }

    /**
     * Records what is drawn from now on in spatial node {@code node}, one {@link #addNode} made this frame, or 0; the
     * pose is then in its space. Answers the node it replaces, to enter again after. Needs no flush: a node is stamped
     * on each instance.
     */
    public int enterNode(int node) {
        int previous = spatialNode;
        spatialNode = node;
        drawToTargetValid = false;
        textRenderer.node(node, effectNode);
        return previous;
    }

    /** The spatial node draws are recorded in; 0 for the bound target's own space. */
    public int spatialNode() {
        return spatialNode;
    }

    /** The effect node draws are grouped under; 0 for none. */
    public int effectNode() {
        return effectNode;
    }

    /**
     * The current draw space into the bound target's pixels: identity at node 0. Read at once; the matrix is this
     * context's and changes with the node or the target.
     */
    public Matrix4f drawToTarget() {
        validateDrawToTarget();
        return drawToTarget;
    }

    /** The bound target's pixels into the current draw space: what a pose set to "the target's pixels" is. */
    public Matrix4f targetToDraw() {
        validateDrawToTarget();
        return targetToDraw;
    }

    /**
     * The pose on the stack carried into the bound target's pixels: the pose itself at node 0. What a decision about
     * pixels -- snapping, culling, a scissor -- asks. Read at once.
     */
    public Matrix4f targetPose() {
        Matrix4f pose = poseStack.last().pose();
        return spatialNode == 0 ? pose : targetPose.set(drawToTarget()).mul(pose);
    }

    private void validateDrawToTarget() {
        if (drawToTargetValid) return;
        drawToTargetValid = true;
        if (spatialNode == 0) {
            drawToTarget.identity();
            targetToDraw.identity();
            return;
        }
        int o = spatialNode * 6;
        drawToTarget.identity();
        drawToTarget.m00(nodeWorlds[o]).m01(nodeWorlds[o + 1]).m10(nodeWorlds[o + 2]).m11(nodeWorlds[o + 3])
                .m30(nodeWorlds[o + 4] - layerOriginX).m31(nodeWorlds[o + 5] - layerOriginY);
        drawToTarget.invert(targetToDraw);
    }

    /** Nesting depth of the clip stack; 0 when nothing is clipped. Exposed so the top-layer paint
     * pass can assert the main tree left the stack balanced before it starts painting unclipped. */
    public int getScissorDepth() {
        return scissorStack.depth();
    }

    /**
     * Switches to {@code material} for the duration of {@code drawBody}, then eagerly restores
     * {@link #boxModelMaterial}. Used for drawables (e.g. an SDF rounded rect) that need their own
     * shader/program rather than the shared box-model one.
     *
     * <p>{@code material.unbind()} restores GL state flags but does NOT rebind whatever program was
     * previously active — so the switch back to {@link #boxModelMaterial} is explicit here, not
     * automatic. {@link #currentTexture} is invalidated on both sides of the switch since a different
     * material may wire its sampler differently even for what looks like "the same" texture reference.</p>
     *
     * <p><b>{@code bind()} must run AFTER {@code drawBody}, not before.</b> {@code applyProperties(...)}
     * is CPU-only — it marks a dirty flag but doesn't upload anything; the GPU-side upload only
     * happens inside {@code bind()}'s own dirty-check. {@code drawBody} (e.g. {@code CgUiRect}'s
     * lambda) is exactly where the caller sets its own per-instance properties (corner radius, border,
     * fill, ...) — binding before that ran would upload whatever was dirty from the *previous* draw
     * call on this material, one draw stale. Invisible for a single static drawable re-drawing the
     * same values every frame; badly broken for two different instances of the same drawable
     * alternating every frame (e.g. a cross-fade), where each draw would render with the other's
     * properties. `bind()` is safe to call unconditionally here (not just on a material switch) —
     * its own `ProgramKey`/`wiredPrograms` caching makes a repeat bind of an already-current variant
     * just a dirty re-check, not a recompile.</p>
     */
    public void withMaterial(CgMaterial material, Runnable drawBody) {
        flush();
        currentMaterial = material;
        currentTexture = null;
        material.applyProperties(layerOpacityBinder);

        // useMaterial() is called TWICE around drawBody on purpose, and both calls are load-bearing.
        //
        // The first satisfies CgQuadRenderer's precondition — Quad.submit() throws unless a material
        // is active, since that call is the renderer's only confirmation its instance buffer is
        // attached to the bound program — and auto-flushes anything still queued for the previous
        // material, whose shader those instances were computed against.
        //
        // The second preserves the bind-AFTER-drawBody invariant documented above. drawBody sets its
        // per-instance properties (corner radii, border, fill) via applyProperties, which is CPU-only
        // — the GPU upload happens inside bind(). useMaterial() rebinds on every call even for the
        // same instance, so this second call is what actually uploads what drawBody just set. Without
        // it the draw would run with whatever was dirty from the *previous* use of this material:
        // invisible for a static drawable repeating identical values, badly wrong for two instances
        // alternating every frame, which is exactly the cross-fade bug this ordering was written to fix.
        bindQuadPath(material);
        drawBody.run();
        bindQuadPath(material);
        flush();

        // Properties before the bind here, so the restored box-model material uploads the current
        // layer opacity on this bind rather than trailing a frame behind.
        boxModelMaterial.applyProperties(layerOpacityBinder);
        bindQuadPath(boxModelMaterial);
        currentMaterial = boxModelMaterial;
        currentTexture = null;
    }

    /**
     * Runs {@code drawBody} with the layer-compositing opacity temporarily set to {@code opacity},
     * then restores the previous value. Syncs the new value into {@link #currentMaterial} immediately
     * (covering draws that call {@code fillRect}/{@code drawImage} directly against the already-bound
     * material without going through {@link #withMaterial}) — any nested {@link #withMaterial} call
     * inside {@code drawBody} re-syncs it again on its own switches, so this composes correctly with
     * drawables that own their own material (e.g. an SDF rounded rect).
     *
     * <p>Used by {@code CgUiCrossFade} to draw its "to" drawable at a fractional opacity without
     * touching that drawable's own ambient tint/alpha.</p>
     */
    public void withLayerOpacity(float opacity, Runnable drawBody) {
        float previous = pushLayerOpacity(opacity);
        try {
            drawBody.run();
        } finally {
            popLayerOpacity(previous);
        }
    }

    /**
     * {@link #withLayerOpacity} without the lambda, paired with {@link #popLayerOpacity} the way
     * {@link #pushScissor} is with {@code popScissor}.
     *
     * <pre>{@code
     * float previous = ctx.pushLayerOpacity(0.5f);
     * try { ... } finally { ctx.popLayerOpacity(previous); }
     * }</pre>
     *
     * <p>For a caller on the per-element paint path, where the lambda is a fresh capture per element
     * per frame. Pass the returned value back to {@code popLayerOpacity} — it is what was in effect
     * before, not what this call set.</p>
     */
    public float pushLayerOpacity(float opacity) {
        flush();
        float previous = layerOpacity;
        // Compose with the enclosing scope rather than overwriting it — a retargeted texture-valued
        // transition can nest a drawable (e.g. a CgUiCrossFade or mixed-fill CgUiRect) inside
        // another one; an absolute overwrite here would let the innermost call silently discard
        // every enclosing opacity, leaving the outer transition's own progress with zero visual
        // effect on whatever it wraps.
        layerOpacity = previous * opacity;
        currentMaterial.applyProperties(layerOpacityBinder);
        bindQuadPath(currentMaterial);
        return previous;
    }

    /** Restores what {@link #pushLayerOpacity} returned. */
    public void popLayerOpacity(float previous) {
        flush();
        layerOpacity = previous;
        currentMaterial.applyProperties(layerOpacityBinder);
        bindQuadPath(currentMaterial);
    }

    // ── Visual layers ────────────────────────────────────────────────────────

    /**
     * The region a layer covering {@code (x0,y0)-(x1,y1)} actually needs, in the current target's
     * physical pixels â€” those bounds intersected with the live clip and with the target itself,
     * rounded outward to whole pixels.
     *
     * <p>Callers hand in the bounds of what they are about to draw; {@link BoxPainter} takes them from
     * {@link Box#inkX0 ink bounds} through the pose. Everything a layer costs â€” the allocation, the
     * clear, the composite â€” is sized from the answer, so a layer nobody can see costs nothing at all:
     * an empty region means the subtree is entirely clipped away and there is nothing to draw.</p>
     *
     * @return the region, empty when the bounds fall outside the clip
     */
    /**
     * {@code -Dcrystalgui.layers.legacy=true} — layers behave as they did before they were bounded:
     * the size of the target, never elided, never kept between frames.
     *
     * <p>For measuring, and for telling a rendering fault apart from a compositing one in a single run.
     * Same shape as CrystalGraphics' {@code -Dcrystalgraphics.state.noDedup}, and for the same reason:
     * an optimisation that cannot be turned off cannot be blamed or acquitted.</p>
     */
    public static final boolean LEGACY_LAYERS = Boolean.getBoolean("crystalgui.layers.legacy");

    /**
     * Skips painting a subtree whose ink lies wholly outside what the current target can show — Blink's cull rect.
     * {@code -Dcrystalgui.paint.cull=false} paints everything, to rule the cull out.
     */
    public static final boolean CULL = !"false".equals(System.getProperty("crystalgui.paint.cull"));

    /**
     * Clips a rounded {@code overflow: hidden} with {@link #pushRoundedClip} where its mask is the box's own
     * shape, instead of a children layer and a mask layer. {@code -Dcrystalgui.paint.roundedClip=false} takes
     * the layers everywhere, to compare the two.
     */
    public static final boolean ROUNDED_CLIP = !"false".equals(System.getProperty("crystalgui.paint.roundedClip"))
            && !LEGACY_LAYERS;

    /**
     * Whether the rectangle, in the current target's pixels, misses the live clip entirely: the scissor where one
     * is set, else the enclosing layer's region or the target. Allocates nothing, since it is asked per box.
     */
    /**
     * The live clip in the current draw space, {@code x0, y0, x1, y1} into {@code out}; false, and nothing written,
     * when the draw space is not a translation of the target's pixels.
     */
    public boolean clipInDraw(float[] out) {
        resolveClip();
        Matrix4f m = drawToTarget();
        if (m.m00() != 1f || m.m01() != 0f || m.m10() != 0f || m.m11() != 1f) return false;
        out[0] = clipX0 - m.m30();
        out[1] = clipY0 - m.m31();
        out[2] = clipX1 - m.m30();
        out[3] = clipY1 - m.m31();
        return true;
    }

    /** The opacity a folded layer multiplies into every colour drawn now; 1 outside one. @see #pushLayerOpacity */
    public float layerOpacity() {
        return layerOpacity;
    }

    public boolean outsideClip(float x0, float y0, float x1, float y1) {
        if (LEGACY_LAYERS) return false;
        resolveClip();
        return x1 <= clipX0 || x0 >= clipX1 || y1 <= clipY0 || y0 >= clipY1;
    }

    public LayerRegion layerRegion(float x0, float y0, float x1, float y1) {
        if (LEGACY_LAYERS) return new LayerRegion(0, 0, targetWidth(), targetHeight());
        resolveClip();
        int left = (int) Math.floor(Math.max(x0, clipX0));
        int top = (int) Math.floor(Math.max(y0, clipY0));
        int right = (int) Math.ceil(Math.min(x1, clipX1));
        int bottom = (int) Math.ceil(Math.min(y1, clipY1));
        return new LayerRegion(left, top, Math.max(0, right - left), Math.max(0, bottom - top));
    }

    /** {@link #resolveClip}'s answer, in the current target's pixels. */
    private float clipX0, clipY0, clipX1, clipY1;

    /**
     * What the current target can show: the scissor where one is set, else the enclosing layer's REGION rather than
     * its buffer — a pooled target is bucketed, so its slack is space the enclosing composite will never read, and a
     * child sized into it would be allocating for pixels that cannot reach the screen.
     */
    /** The live clip in the bound target's pixels, {@code x0, y0, x1, y1}, outward-rounded. @see CgUiBackdrop */
    int[] clipRect() {
        resolveClip();
        return new int[] {(int) Math.floor(clipX0), (int) Math.floor(clipY0),
                (int) Math.ceil(clipX1), (int) Math.ceil(clipY1)};
    }

    private void resolveClip() {
        if (scissorStack.hasScissor()) {
            clipX0 = scissorStack.currentX();
            clipY0 = scissorStack.currentY();
            clipX1 = clipX0 + scissorStack.currentW();
            clipY1 = clipY0 + scissorStack.currentH();
            return;
        }
        LayerFrame enclosing = layerStack.peek();
        // A NULL REGION IS THE WHOLE TARGET, which is what beginLayerFbo says it means: the caller owns
        // the buffer and every pixel of it is the layer -- a window snapshot, a backdrop capture. Those
        // have no bucketed slack to keep a child out of, so targetWidth/Height IS the clip. Reading
        // region() regardless threw straight out of the minimise animation, since photographing a
        // window is exactly the path that opens a layer without one.
        LayerRegion region = enclosing == null ? null : enclosing.region();
        clipX0 = 0f;
        clipY0 = 0f;
        clipX1 = region != null ? region.width() : targetWidth();
        clipY1 = region != null ? region.height() : targetHeight();
    }

    /**
     * A layer's texture: each side a power of two from 32 up, never past the screen, so a layer that resizes by a
     * pixel keeps its size class and the executor's pool hands back the same texture next frame.
     */
    private CgTextureDesc layerDesc(int width, int height) {
        return new CgTextureDesc(sizeClass(width, screenWidth), sizeClass(height, screenHeight), LAYER_FORMAT);
    }

    private static int sizeClass(int size, int screen) {
        int bucket = 32;
        while (bucket < size && bucket < 32 << 8) bucket <<= 1;
        return Math.max(1, Math.min(Math.max(1, screen), bucket));
    }

    // ── Retained layers ──────────────────────────────────────────────────────

    /** Retained targets are owned outright, so this is the whole ceiling on what retention costs. */
    private static final long RETAINED_BUDGET_BYTES = 48L * 1024L * 1024L;

    /** Frames a retained layer may go unasked-for before it is freed. At 60Hz, five seconds. */
    private static final long RETAINED_IDLE_FRAMES = 300L;

    private final Map<Object, RetainedLayer> retained = new LinkedHashMap<>();
    /** Requested textures released between frames: the next frame records their release first. */
    private final List<CgGraphTexture> pendingReleases = new ArrayList<>();

    /** A subtree seen once and not yet given a texture. @see #retain */
    private record Candidate(long revision, LayerRegion region, long frame) {
    }

    private final Map<Object, Candidate> candidates = new HashMap<>();
    private long retainedBytes;
    private int retainedCreated;
    private boolean retentionSuspended;

    /**
     * Runs {@code body} with {@link #retain} answering null throughout.
     *
     * <p>For a pass that draws the same subtree somewhere else — a window photographing itself. A
     * retained layer remembers WHERE it was drawn, and a second pass at other coordinates would keep
     * overwriting the live one's picture with the copy's and then the copy's with the live one's.</p>
     */
    public void withoutRetention(Runnable body) {
        boolean was = retentionSuspended;
        retentionSuspended = true;
        try {
            body.run();
        } finally {
            retentionSuspended = was;
        }
    }

    /**
     * The kept texture for a subtree, or null when it is not worth keeping one.
     *
     * <p>Ask before painting a layer. A returned layer whose {@link RetainedLayer#isFresh()} is true
     * already holds the picture for {@code revision} at {@code region} and can be composited straight
     * back; one that is not fresh is a target to paint into, followed by
     * {@link RetainedLayer#painted()}.</p>
     *
     * @param key      what the caller retains under, compared by identity — a box, in practice
     * @param revision what the subtree looks like now. @see com.crystalgui.ui.box.Box#subtreeRevision
     * @return null when the budget is spent, in which case paint into a pooled target as usual
     */
    @Nullable
    public RetainedLayer retain(Object key, LayerRegion region, long revision) {
        if (retentionSuspended || LEGACY_LAYERS) return null;
        RetainedLayer layer = retained.remove(key);
        if (layer != null) {
            // Re-inserted so iteration order stays least-recently-used first, for the sweep below.
            retained.put(key, layer);
            layer.lastFrame = frameId;
            // Too small is a miss; MUCH too big is one as well. An element that was 800px and is now 40
            // would otherwise keep its 832px texture for as long as it stayed on screen.
            int held = layer.target().getWidth(), tall = layer.target().getHeight();
            boolean fits = held >= region.width() && tall >= region.height()
                    && held <= Math.max(64, region.width() * 2) && tall <= Math.max(64, region.height() * 2);
            if (fits) {
                // A LAYER THAT MOVED IS REDRAWN, not slid: the region is where it was composited FROM as
                // well as to, and everything in it was drawn at that origin.
                boolean fresh = layer.revision == revision && layer.region.equals(region);
                layer.region = region;
                layer.setFresh(fresh, revision);
                CgTrace.add(UiTrace.FRAME, fresh ? "layers-reused" : "layers-repainted", 1);
                return layer;
            }
            // COUNTED APART FROM A FIRST SIGHTING: a layer whose element resizes every frame keeps
            // starting over as a candidate and never settles, which is a different finding.
            CgTrace.add(UiTrace.FRAME, "retain-resized", 1);
            drop(key, layer);
        }

        // NOT ON FIRST SIGHT. A layer that changes every frame -- a window mid-fade, a scroller being
        // dragged -- is never worth a texture of its own: it would repaint into it regardless, and hold
        // it against the budget while the shared pool would have served. So a subtree has to be seen
        // UNCHANGED once before it earns one. Flutter's raster cache scored pictures for complexity
        // instead, and the score was bad enough to be disabled in the engine; stability is the same
        // question answered by observation.
        Candidate seen = candidates.get(key);
        if (seen == null || seen.revision() != revision || !seen.region().equals(region)) {
            candidates.put(key, new Candidate(revision, region, frameId));
            // NOT YET, rather than no: the subtree has to be seen unchanged once. A frame where this
            // dominates is one where everything is moving, and no cache would have helped.
            CgTrace.add(UiTrace.FRAME, "retain-settling", 1);
            return null;
        }

        int width = bucket(region.width()), height = bucket(region.height());
        long bytes = (long) width * height * 4L;
        if (retainedBytes + bytes > RETAINED_BUDGET_BYTES && !evictUntil(bytes)) {
            // THE BUDGET IS SPENT, which is the one refusal a bigger budget would fix -- and the only
            // way to tell it from the others is to count it.
            CgTrace.add(UiTrace.FRAME, "retain-nobudget", 1);
            return null;
        }
        candidates.remove(key);

        long timed = CgTrace.stamp(UiTrace.FRAME);
        CgGraphTexture target = requestTexture("cgui_retained_" + retainedCreated++, width, height, LAYER_FORMAT);
        warmUpLayer(target);
        CgTrace.zoneDone(UiTrace.FRAME, "retain:createFbo", timed);
        layer = new RetainedLayer(target, region, revision);
        layer.lastFrame = frameId;
        layer.setFresh(false, revision);
        retained.put(key, layer);
        retainedBytes += bytes;
        CgTrace.add(UiTrace.FRAME, "layers-retained-new", 1);
        return layer;
    }

    /** Rounded up so an element resizing by a pixel a frame does not reallocate its texture every frame. */
    private static int bucket(int size) {
        return Math.max(64, (Math.max(1, size) + 63) & ~63);
    }

    /** Frees least-recently-used layers until {@code wanted} bytes fit, or gives up. */
    private boolean evictUntil(long wanted) {
        Iterator<Map.Entry<Object, RetainedLayer>> entries = retained.entrySet().iterator();
        while (entries.hasNext() && retainedBytes + wanted > RETAINED_BUDGET_BYTES) {
            Map.Entry<Object, RetainedLayer> entry = entries.next();
            if (entry.getValue().lastFrame == frameId) break;   // in use this very frame
            release(entry.getValue());
            entries.remove();
        }
        return retainedBytes + wanted <= RETAINED_BUDGET_BYTES;
    }

    /** Frees anything nothing has asked for in a while. Called once a frame, from {@link #endFrame}. */
    private void sweepRetained() {
        // Candidates are only useful across one frame gap, and there is one per layered box that has
        // not settled. Swept in a batch rather than per frame: the map is small and the walk is not
        // worth doing sixty times a second to save a few hundred bytes.
        if ((frameId & 63L) == 0L) candidates.values().removeIf(c -> frameId - c.frame() > 4L);
        if (retained.isEmpty()) return;
        Iterator<Map.Entry<Object, RetainedLayer>> entries = retained.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Object, RetainedLayer> entry = entries.next();
            // Ordered least-recently-used first, so the first live one ends the sweep.
            if (frameId - entry.getValue().lastFrame < RETAINED_IDLE_FRAMES) break;
            release(entry.getValue());
            entries.remove();
        }
    }

    private void drop(Object key, RetainedLayer layer) {
        retained.remove(key);
        release(layer);
    }

    private void release(RetainedLayer layer) {
        retainedBytes -= (long) layer.target().getWidth() * layer.target().getHeight() * 4L;
        releaseTexture(layer.target());
    }

    /**
     * Draws a fully transparent quad into a framebuffer this context will draw into later, as its first draw.
     *
     * <p>On at least one NVIDIA driver a lazily compiled program's first draw into a never-drawn framebuffer, in the
     * same frame, produced nothing: the masked content was missing on frame 1 and correct from frame 2. Warming a
     * target as it is made moves that first draw onto throwaway content. Call it for a framebuffer you own and hand
     * to {@link #beginLayerFbo(CgFrameBuffer)}; a layer's own texture comes from the executor's pool.</p>
     */
    public void warmUpLayer(CgFrameBuffer fbo) {
        warmUpLayer(imported(fbo));
    }

    /** {@link #warmUpLayer(CgFrameBuffer)} for a texture the recording makes: a requested one, warmed as it is made. */
    public void warmUpLayer(CgGraphTexture target) {
        CgMaterial previousMaterial = currentMaterial;
        beginLayer(target, true, null);
        // Rest the material on a texture that is never deleted: a sampler property is retained and re-bound later.
        layerBlitMaterial.applyProperties(b -> b.sampler("_MainTex", 0, whitePixel));
        int outerNode = enterNode(0);
        withMaterial(layerBlitMaterial, () -> {
            bindTexture(whitePixel);
            quad().at(0, 0).size(target.getWidth(), target.getHeight()).color(0x0).submit();
            flush();
        });
        enterNode(outerNode);
        endLayerFbo();
        // The caller's material back on the quad path: withMaterial restored the box model, not what was current.
        currentMaterial = previousMaterial;
        if (previousMaterial != null) bindQuadPath(previousMaterial);
        currentTexture = null;
    }

    /**
     * Pushes an offscreen target the size of {@code region} and redirects drawing into it, cleared
     * fully transparent. Nests. Pair with {@link #endLayerFbo}, then composite with
     * {@link #blitLayer(CgGraphTexture, float, LayerRegion)} <b>giving the same region</b>.
     *
     * <pre>{@code
     * LayerRegion region = ctx.layerRegion(x0, y0, x1, y1);
     * if (region.isEmpty()) return;                    // wholly clipped: nothing to draw
     * CgGraphTexture layer = ctx.beginLayerFbo(region);
     * // ...draw, with the caller's own transform pre-translated by (-region.x(), -region.y())
     * ctx.endLayerFbo();
     * ctx.blitLayer(layer, opacity, region);
     * }</pre>
     *
     * <p><b>The layer has its own origin, and the caller has to honour it.</b> The target's pixel
     * {@code (0,0)} is the region's top-left corner, not the screen's, so a caller drawing at absolute
     * coordinates must pre-translate its own transform by the region's negated origin. Nothing here can
     * do that for it: the pose is rebuilt per element from a base matrix this class never sees. The clip
     * stack IS shifted here, because this class owns it.</p>
     *
     * @return the layer: a texture the executor lends for the frame, which may be LARGER than the region — its
     *         size is a size class, and only the region's own corner of it is drawn or composited
     */
    public CgGraphTexture beginLayerFbo(LayerRegion region) {
        int width = Math.max(1, region.width()), height = Math.max(1, region.height());
        CgTrace.add(UiTrace.FRAME, "layers", 1);
        CgTrace.add(UiTrace.FRAME, "layers-d" + layerStack.size(), 1);
        return beginLayer(CgGraphTexture.transientTexture("cgui_layer", layerDesc(width, height)), true, region);
    }

    /**
     * As {@link #beginLayerFbo(LayerRegion)}, but rendering into a target the CALLER owns and keeps — a window's
     * photograph, which outlives the frame and is the size of what it captures. The viewport and ortho are set from
     * {@code fbo}'s own dimensions, so a caller drawing at ordinary coordinates fills it. Neither allocates nor
     * frees: a {@code createOwned} framebuffer is its maker's to delete.
     */
    public CgGraphTexture beginLayerFbo(CgFrameBuffer fbo) {
        return beginLayer(imported(fbo), true, null);
    }

    /**
     * As {@link #beginLayerFbo(CgFrameBuffer)}, but able to KEEP what the target already holds: the backdrop's
     * capture seeds its target with the scene before drawing the UI over it, and a clear would throw that away.
     */
    public CgGraphTexture beginLayerFbo(CgFrameBuffer fbo, boolean clear) {
        return beginLayer(imported(fbo), clear, null);
    }

    /** As {@link #beginLayerFbo(LayerRegion)}, into a target the caller keeps. */
    public CgGraphTexture beginLayerFbo(CgFrameBuffer fbo, LayerRegion region) {
        return beginLayer(imported(fbo), true, region);
    }

    /** As {@link #beginLayerFbo(LayerRegion)}, into a texture that outlives the frame — a {@link RetainedLayer}'s. */
    public CgGraphTexture beginLayerFbo(CgGraphTexture target, LayerRegion region) {
        return beginLayer(target, true, region);
    }

    /** As {@link #beginLayerFbo(CgFrameBuffer, boolean)}, into a texture the recording makes and keeps. */
    public CgGraphTexture beginLayerFbo(CgGraphTexture target, boolean clear) {
        return beginLayer(target, clear, null);
    }

    /**
     * A texture made when the frame executes and kept across frames until {@link #releaseTexture}: the GL object a
     * recording may not make itself.
     */
    CgGraphTexture requestTexture(String name, int width, int height, CgFrameBufferFormat format) {
        return CgGraphTexture.requested(name, new CgTextureDesc(Math.max(1, width), Math.max(1, height), format));
    }

    /**
     * A layer-format texture made when a frame executes and kept until {@link #releaseTexture}: what paint draws into
     * and keeps across frames, where it may not make a framebuffer itself.
     *
     * <pre>{@code
     * CgGraphTexture picture = ctx.requestLayer("my_picture", w, h);   // in paint, once
     * ctx.beginLayerFbo(picture, true);
     * // ... draw ...
     * ctx.endLayerFbo();
     * ctx.drawLayer(picture, x, y, w, h);                               // this frame or any later one
     * ctx.releaseTexture(picture);                                      // in a frame or between frames
     * }</pre>
     */
    public CgGraphTexture requestLayer(String name, int width, int height) {
        return requestTexture(name, width, height, LAYER_FORMAT);
    }

    /**
     * Lends the frame's recording for passes of the caller's own — into requested textures, as a shader preview
     * renders its picture — until {@link #endPasses()}, which goes on drawing where paint was. Inside a frame.
     *
     * <pre>{@code
     * CgRecording recording = ctx.beginPasses();
     * renderer.renderPending(graph, recording);
     * ctx.endPasses();
     * ctx.drawImage(renderer.textureOf(nodeId), x, y, w, h, 0f, 1f, 1f, 0f, 0xFFFFFFFF);
     * }</pre>
     *
     * <ul>
     *   <li>Every pass recorded in between must have ended by {@link #endPasses()}.</li>
     *   <li>A texture a pass writes may be drawn by anything recorded after it, in this frame or a later one.</li>
     * </ul>
     */
    public CgRecording beginPasses() {
        if (!frameActive) throw new IllegalStateException("beginPasses() outside a frame");
        drain();
        return recording;
    }

    /** Ends what {@link #beginPasses()} lent: paint continues on the target it was drawing into. */
    public void endPasses() {
        recorder.recordInto(recording, currentTarget(), CgLoad.load(), passConstants);
        currentTexture = null;
    }

    /**
     * Records {@code upload} into {@code target} — a {@link #requestLayer requested} texture — run on the render thread
     * before anything recorded after it reads the texture. Inside a frame.
     *
     * <pre>{@code
     * ctx.upload(picture, fbo -> ((CgTexture2D) fbo.getColorTexture(0)).upload(w, h, rgba, GL_RGBA, GL_UNSIGNED_BYTE));
     * ctx.drawImage(picture, x, y, w, h, 0f, 0f, 1f, 1f, 0xFFFFFFFF);
     * }</pre>
     */
    public void upload(CgGraphTexture target, CgUpload upload) {
        if (!frameActive) throw new IllegalStateException("upload() outside a frame");
        drain();
        recording.upload(target, upload);
    }

    /**
     * Frees a requested texture's storage once what was recorded before this has executed. Between frames the release
     * waits for the next frame.
     */
    public void releaseTexture(CgGraphTexture requested) {
        if (!frameActive) {
            pendingReleases.add(requested);
            return;
        }
        drain();
        recording.release(requested);
    }

    /** Frees a requested texture's storage now: teardown, with no frame to record a release into. */
    private static void deleteNow(CgGraphTexture requested) {
        CgFrameBuffer storage = requested.framebuffer();
        if (storage != null) storage.delete();
    }

    /**
     * @param region what of the target is in use, and where it goes back, or null when its whole extent is the
     *               layer — a snapshot, a backdrop capture. A region shifts the clip stack into the layer's own
     *               origin; null leaves it alone.
     */
    private CgGraphTexture beginLayer(CgGraphTexture target, boolean clear, @Nullable LayerRegion region) {
        // The enclosing target's pass ends here and continues in another after the layer, so the pass that
        // composites the layer runs after the layer's own.
        drain();
        int enclosingWidth = targetWidth(), enclosingHeight = targetHeight();
        ScissorStack.Saved savedScissor = scissorStack.suspend();
        if (region != null) {
            layerOriginX += region.x();
            layerOriginY += region.y();
        }
        layerStack.push(new LayerFrame(target, enclosingWidth, enclosingHeight, savedScissor, region, clipEntry,
                viewOwner, spatialNode, effectNode));
        // A rounded clip is in the enclosing target's pixels; it applies when this layer is composited back.
        setClip(0);
        // A TARGET WITH A SPACE OF ITS OWN -- a snapshot, a capture -- is drawn in its own pixels, at node 0. A bounded
        // layer is a piece of the frame: drawing goes on in the current node, and the layer moves with that node.
        if (region == null) {
            nodesSuspended++;
            effectNode = 0;
            enterNode(0);
        }
        viewOwner = spatialNode;
        recorder.view(viewOwner, layerOriginX, layerOriginY);
        drawToTargetValid = false;
        int width = target.getWidth(), height = target.getHeight();

        // THE INHERITED CLIP, RE-EXPRESSED FOR THIS TARGET. The stack keeps rects top-left and flips them against
        // the target's height, so a layer of another height than its parent clips the same region rather than a
        // band at its bottom. A BOUNDED layer moves the origin as well, so every inherited rect shifts with it.
        scissorStack.resume(region == null ? savedScissor : savedScissor.shifted(-region.x(), -region.y()));
        reapplyScissorFor(height);

        targetConstants(width, height);
        // TEXT HAS ITS OWN PROJECTION, and it has to follow the target too: CgTextRenderer does not read
        // cg_ProjMatrix. Inside a window's snapshot, sized to the window, a screen ortho drew every string at a
        // third of its size in the corner. updateOrtho is a no-op when the size is unchanged.
        textRenderer.context().updateOrtho(width, height);
        if (clear) {
            CgTrace.add(UiTrace.FRAME, "layer-clear-kpx",
                    (region == null ? width * height : region.width() * region.height()) / 1000);
        }
        recorder.recordInto(recording, target, clear ? CgLoad.clear(0f, 0f, 0f, 0f) : CgLoad.load(), passConstants);
        currentTexture = null;
        return target;
    }

    /**
     * Pops the innermost {@link #beginLayerFbo}: what it drew is complete, and drawing goes on in the target around
     * it, with its projection and clip. Composites nothing — see {@link #blitLayer} and {@link #compositeMask}.
     */
    public void endLayerFbo() {
        drain();
        LayerFrame frame = layerStack.pop();
        if (frame.region() != null) {
            layerOriginX -= frame.region().x();
            layerOriginY -= frame.region().y();
        } else {
            nodesSuspended--;
            effectNode = frame.savedEffect();
            enterNode(frame.savedSpatial());
        }
        viewOwner = frame.savedView();
        recorder.view(viewOwner, layerOriginX, layerOriginY);
        drawToTargetValid = false;
        targetConstants(frame.enclosingWidth(), frame.enclosingHeight());
        textRenderer.context().updateOrtho(frame.enclosingWidth(), frame.enclosingHeight());
        // The clip stack as the enclosing target expressed it -- a bounded layer shifted every rect
        // into its own origin on the way in. @see LayerFrame#savedScissor
        scissorStack.resume(frame.savedScissor());
        setClip(frame.savedClip());
        reapplyScissor();
        recorder.recordInto(recording, currentTarget(), CgLoad.load(), passConstants);
        currentTexture = null;
    }

    /**
     * The finished frame onto the host's target — whatever is bound when it executes, which is the host's once
     * {@link UiGpu} has restored its state. A recording of one draw, of its own.
     */
    private void recordPresent() {
        targetConstants(screenWidth, screenHeight);
        recorder.recordInto(present, CgGraphTexture.current(), CgLoad.load(), passConstants);
        blitLayer(frameTarget, 1f, new LayerRegion(0, 0, screenWidth, screenHeight));
        recorder.stop();
    }

    /**
     * Draws a finished target into an arbitrary rect, through the active {@link PoseStack}: a captured layer drawn
     * somewhere else and at another size — a window's snapshot in a taskbar preview. Tinted by {@link #getColor()},
     * which for one texture is exactly group opacity, since a photograph has already resolved every overlap.
     *
     * <p>Same material and flipped V as {@link #blitLayer}, for the same reasons.</p>
     */
    public void drawLayer(CgFrameBuffer fbo, float x, float y, float width, float height) {
        drawLayer(imported(fbo), x, y, width, height);
    }

    /** {@link #drawLayer(CgFrameBuffer, float, float, float, float)} for a {@link #requestLayer requested} texture. */
    public void drawLayer(CgGraphTexture layer, float x, float y, float width, float height) {
        // Declared rather than bound by hand. @see #blitLayer
        layerBlitMaterial.applyProperties(b -> b.sampler("_MainTex", 0, layer));
        withMaterial(layerBlitMaterial, () -> {
            quad().at(x, y).size(width, height)
                  .uv(0f, 1f, 1f, 0f)   // V flipped — see blitLayer's javadoc
                  .color(getColor()).submit();
            flush();
        });
    }

    // ── Backdrop capture, for glass ─────────────────────────────────────────

    /**
     * A rect's backdrop, cropped to it: the sharp crop and the blurred one.
     *
     * <p>{@code u0..v1} is the element's own rect in both textures. {@code cu0..cv1} is what the element may
     * sample: its rect padded by the blur's and the lens's reach, and never past the clip it is drawn in. A
     * refraction samples past the element's edge — clamped to the element it repeated the edge's own pixels
     * across the bezel, and unbounded it bent in whatever the clip was hiding.</p>
     */
    /**
     * The frame's captured backdrop, and where one element sits in it.
     *
     * <p>The placement is an AFFINE MAP, not a rect: {@code (u0,v0)} is where the element's own uv origin
     * lands, and {@code (ux,vx)} / {@code (uy,vy)} are the steps across its width and down its height. A
     * rotated or skewed element therefore reads a rotated patch, so what is behind it stays where it is on
     * screen rather than turning with the glass — an element is a window onto the backdrop, not a surface
     * carrying a picture of it. A rect can only be the bounding box, which is the same thing as mapping the
     * capture onto the element's own quad.</p>
     *
     * <p>{@code cu0..cv1} is the axis-aligned rect it may sample at all — its bounds padded by the blur's
     * and the lens's reach, cut to its clip — which stays a rect because the capture is one.</p>
     */
    public record Backdrop(CgTexture sharp, CgTexture blurred,
                           float u0, float v0, float ux, float vx, float uy, float vy,
                           float cu0, float cv0, float cu1, float cv1) {}

    /**
     * Captures what is behind {@code (x, y, w, h)} and blurs it — the primitive under {@code backdrop-filter}.
     *
     * <p>The work lives in {@link CgUiBackdrop}; this is the seam a drawable calls, kept here because
     * everything else a drawable needs is on the paint context too.</p>
     *
     * @param blurRadiusPx how far the blur reaches, in surface pixels. Zero hands back the capture
     *                     itself, so {@code blur 0} costs nothing beyond the grab every consumer shares
     * @return the two textures, or {@code null} when there is nothing to capture — a caller must fall
     *         back to a solid colour rather than draw nothing
     */
    @Nullable
    public Backdrop backdropFor(float x, float y, float width, float height, float blurRadiusPx) {
        return backdropFor(x, y, width, height, blurRadiusPx, 0f);
    }

    /**
     * As {@link #backdropFor(float, float, float, float, float)}, capturing {@code reach} further around the rect
     * for a consumer that samples outside it — a lens bending what is beyond its edge.
     *
     * @param reach how far past the rect a tap may land, in the rect's own units
     */
    @Nullable
    public Backdrop backdropFor(float x, float y, float width, float height, float blurRadiusPx, float reach) {
        return backdrop.forRect(x, y, width, height, blurRadiusPx, reach);
    }

    /** {@link #blitLayer(CgGraphTexture, float, LayerRegion)} for a framebuffer the caller owns, whole. */
    public void blitLayer(CgFrameBuffer fbo, float opacity) {
        blitLayer(imported(fbo), opacity, new LayerRegion(0, 0, fbo.getWidth(), fbo.getHeight()));
    }

    /** {@link #blitLayer(CgGraphTexture, float, LayerRegion)} for a framebuffer the caller owns. */
    public void blitLayer(CgFrameBuffer fbo, float opacity, LayerRegion region) {
        blitLayer(imported(fbo), opacity, region);
    }

    /**
     * Composites {@code region}'s corner of a finished layer back at {@code region}'s own position in the target
     * being drawn into, at {@code opacity}. Give it the region {@link #beginLayerFbo(LayerRegion)} was opened with:
     * past it, a texture lent by the pool holds whatever its last user left.
     *
     * <ul>
     *   <li>Premultiplied, through {@link #layerBlitMaterial}: everything in a layer was drawn over a transparent
     *       clear, so its partially covered pixels carry their alpha in their colour.</li>
     *   <li>V is flipped: a target's row 0 is its bottom, and the UI's is its top.</li>
     *   <li>The region is in the target's pixels, drawn in the current node, so the composite moves with it.</li>
     *   <li>Below full opacity it is drawn under an effect node holding {@code opacity}, which a compositor may change.
     *       Answers that node, or 0.</li>
     * </ul>
     */
    public int blitLayer(CgGraphTexture layer, float opacity, LayerRegion region) {
        return blitLayer(layer, opacity, region, false);
    }

    /** {@link #blitLayer(CgGraphTexture, float, LayerRegion)}; {@code fades} makes the effect node at full opacity too. */
    public int blitLayer(CgGraphTexture layer, float opacity, LayerRegion region, boolean fades) {
        if (region.isEmpty()) return 0;
        long timed = CgTrace.stamp(UiTrace.FRAME);
        CgTrace.add(UiTrace.FRAME, "layer-blit-kpx", region.width() * region.height() / 1000);
        float u1 = Math.min(1f, (float) region.width() / layer.getWidth());
        float v1 = Math.max(0f, 1f - (float) region.height() / layer.getHeight());
        // Declared rather than bound by hand: _MainTex has a "white" default, so an undeclared texture composites
        // the white fallback, premultiplied -- a destination flooded white rather than a missing image.
        layerBlitMaterial.applyProperties(b -> b.sampler("_MainTex", 0, layer));
        int effect = 0;
        if (NODES && (opacity < 1f || fades) && nodesSuspended == 0 && recording.effects().count() < CgSpatialTree.MAX_NODES) {
            effect = recording.effects().add(effectNode, opacity);
        }
        int outerEffect = effectNode;
        effectNode = effect != 0 ? effect : effectNode;
        float drawn = effect != 0 ? 1f : opacity;
        try {
            withMaterial(layerBlitMaterial, () -> withLayerOpacity(drawn, () -> {
                poseStack.pushPose();
                poseStack.last().pose().set(targetToDraw());
                quad().at(region.x(), region.y()).size(region.width(), region.height())
                      .uv(0f, 1f, u1, v1)
                      .color(getColor()).submit();
                flush();
                poseStack.popPose();
            }));
        } finally {
            effectNode = outerEffect;
        }
        CgTrace.zoneDone(UiTrace.FRAME, "layer:blit", timed);
        return effect;
    }

    /** The target being drawn into: the innermost layer, or the frame's own target when none is open. */
    CgGraphTexture currentTarget() {
        return layerStack.isEmpty() ? frameTarget : layerStack.peek().target();
    }

    /**
     * Multiplies {@code subtree}'s colour and alpha by {@code mask}'s alpha over {@code region}, through
     * {@link CgBlendState#MASK_ALPHA_MULTIPLY}: wherever the mask is transparent the subtree is zeroed. Both are
     * layers of one size class, so the region means the same thing in each.
     *
     * <p>The subtree is normally the layer still open around the mask; the multiply is then simply its next draw.
     * The clip does not apply — its job is to zero the subtree everywhere the mask does not cover.</p>
     */
    public void compositeMask(CgGraphTexture subtree, CgGraphTexture mask, LayerRegion region) {
        if (region.isEmpty()) return;
        long timed = CgTrace.stamp(UiTrace.FRAME);
        flush();
        boolean elsewhere = subtree != currentTarget();
        if (elsewhere) recorder.recordInto(recording, subtree, CgLoad.load(), passConstants);
        ScissorStack.Saved suspendedMask = scissorStack.suspend();
        scissorStack.clearScissorIfNeeded();
        int outerNode = enterNode(0);
        // AND THE PROJECTION, which must be the subtree's own: a mask quad the size of the layer drawn through another
        // target's ortho is stretched and shifted, and the multiply then zeroes everything it no longer reaches.
        CgMaterial masked = currentMaterial;
        int enclosingW = targetWidth(), enclosingH = targetHeight();
        targetConstants(subtree.getWidth(), subtree.getHeight());
        try {
            // On a material of ours rather than the caller's: a sampler property is retained, so setting it on the
            // enclosing material would rewrite what every later draw through it samples.
            maskMaterial.applyProperties(b -> b.sampler("_MainTex", 0, mask));
            // The multiply rides on the draw's own pipeline: a blend applied after binding would be overwritten
            // when the recorded draw binds gui_box's declared blend.
            if (activePath == InstancePath.TEXT) textRenderer.endBatch();
            CgRenderState quadState = maskMaterial.getPassRenderState(CgRenderPassVariant.FORWARD);
            if (quadState != maskStateBase) {
                maskStateBase = quadState;
                maskState = quadState.withBlend(CgBlendState.MASK_ALPHA_MULTIPLY);
            }
            renderer.useMaterial(maskMaterial, maskState);
            activePath = InstancePath.QUAD;
            currentTexture = null;
            poseStack.pushPose();
            poseStack.setIdentity();
            quad().at(0, 0).size(region.width(), region.height())
                  .uv(0f, 1f,
                      Math.min(1f, (float) region.width() / mask.getWidth()),
                      Math.max(0f, 1f - (float) region.height() / mask.getHeight()))   // V flipped, as blitLayer
                  .color(0xFFFFFFFF).submit();
            flush();
            poseStack.popPose();
        } finally {
            // The recorded draw keeps the mask it was captured with; the material goes back to a texture that lives.
            maskMaterial.applyProperties(b -> b.sampler("_MainTex", 0, whitePixel));
            bindQuadPath(masked != null ? masked : boxModelMaterial);
            currentTexture = null;
            scissorStack.resume(suspendedMask);
            targetConstants(enclosingW, enclosingH);
            enterNode(outerNode);
        }
        if (elsewhere) recorder.recordInto(recording, currentTarget(), CgLoad.load(), passConstants);
        reapplyScissor();
        currentTexture = null;
        CgTrace.zoneDone(UiTrace.FRAME, "layer:mask", timed);
    }

    /**
     * Frees what this context made — its retained layers, the backdrop's and the icon raster's textures — and its
     * renderers. {@link UiGpu#destroy} calls it at GL context destruction. Only what is genuinely its own: materials,
     * the fallback texture and the font atlases are swept by {@code CgGraphicsLifecycle.destroyContext()}, and freeing
     * them here would be a double free.
     */
    void release() {
        // Any still-open layer belongs to a frame that will never finish.
        layerStack.clear();

        recording.reset();
        imported.clear();
        for (RetainedLayer layer : retained.values()) deleteNow(layer.target());
        retained.clear();
        for (CgGraphTexture released : pendingReleases) deleteNow(released);
        pendingReleases.clear();
        candidates.clear();
        retainedBytes = 0L;

        // createOwned, so no registry sweeps these — the same reason the layer pool is freed here.
        backdrop.delete();
        svgRaster.delete();

        renderer.delete();
        textRenderer.delete();

        currentTexture = null;
        currentMaterial = null;
        frameActive = false;
    }
}
