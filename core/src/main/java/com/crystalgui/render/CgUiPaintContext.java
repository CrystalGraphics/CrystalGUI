package com.crystalgui.render;

import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.render.CgFrameData;
import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.gl.render.CgVectorRenderer;
import com.crystalgraphics.gl.render.CgQuadRenderer;
import com.crystalgraphics.gl.texture.CgFallbackTextures;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.gl.texture.CgTextureManager;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.text.render.CgTextRenderer;
import com.crystalgraphics.util.io.CgIO;
import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.text.cache.CgFontRegistry;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.async.FrameProfile;
import com.crystalgui.render.text.FontFamilyCache;
import com.crystalgui.render.texture.asset.FileIconTheme;
import com.crystalgui.render.texture.svg.SvgDocument;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleRule;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.UIWindow;
import lombok.Getter;
import lombok.Setter;
import org.joml.Matrix4f;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * True immediate-mode 2D paint context for CrystalGUI's box-model layer.
 *
 * <p><b>A true process-wide singleton</b> — one paint context for the whole client, accessed via
 * {@link #getInstance()}, lazily constructed on first use. This reflects reality, not an
 * aspiration: nothing in this codebase ever constructs more than one {@link com.crystalgui.ui.UIWindow}
 * at a time (no split-screen/multi-window support exists anywhere), and {@link #beginFrame} is not
 * reentrant (throws if called without a matching {@link #endFrame}) — a second concurrent
 * {@code UIWindow} painting at the "same time" could not actually be served by a shared instance.
 * Lazy construction matters beyond avoiding needless work: it means simply constructing/using a
 * {@code UIWindow} for pure layout/tree logic (no {@link #beginFrame}/paint call) never eagerly
 * triggers GL material/font loads — a real, if small, step toward running CrystalGUI's tree/layout
 * logic headlessly (e.g. server-side) without a GL context.</p>
 *
 * <p>Being static, the instance does <em>not</em> die with the GL context that built it, so whoever
 * owns that lifecycle must call {@link #destroy()} on context destruction — see that method for what
 * is and isn't freed, and why the distinction matters.</p>
 *
 * <p>Wraps frame lifecycle in {@link CgGlScope} for GL state isolation. It does <em>not</em> restore
 * {@link CgFrameData}, which it overwrites with a screen-space camera — see {@link #beginFrame} for
 * why that needs no restore and what does.</p>
 *
 * <p>Integrates {@link ScissorStack} for nested clip regions — GL scissor is applied
 * at draw time when a scissor rect is active.</p>
 *
 * <p><b>Frame lifecycle</b> — call {@link #beginFrame} once before walking the UI tree,
 * then {@link #endFrame} once after. Every {@code fillRect}/{@code drawImage} call in between draws immediately;
 * there is no recording phase and nothing to flush. This is intentional for now, not merely unoptimized. </p>
 */
public final class CgUiPaintContext {
    /** {@code namespace:path} resolved through {@link CgIO}'s waterfall (filesystem override →
     * MC resource manager → classpath) — works identically in-game and in the harness/tests,
     * unlike the hardcoded absolute Windows path this replaced ({@code C:\WINDOWS\Fonts\arial.ttf},
     * which only ever worked on the original dev's machine). Reuses a font CrystalGraphics already
     * bundles rather than shipping a duplicate. */
    private static final String DEFAULT_FONT_ASSET = "crystalgraphics:IBMPlexSans-Regular.ttf";

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

    private static CgUiPaintContext instance;

    /** Lazily constructs the singleton on first use. See the class doc for why this must stay lazy. */
    /**
     * <b>Compiles the shipped materials now, so the first frame that draws does not.</b>
     *
     * <p>{@code CgMaterial.load} in the constructor <em>parses</em> a {@code .shader}; the GLSL is
     * compiled and linked on the first {@code bind}. So constructing this class early bought nothing —
     * measured, twice: warming by construction alone left the first frame's material bind at 300 ms
     * against 286 before. Binding each material once is what actually pays the cost.</p>
     *
     * <p>Called from {@link com.crystalgui.lifecycle.CgUiLifecycle#onInit}, which is on the GL thread with
     * a live context by definition. Never call it from anywhere else: a bind outside a frame is only safe
     * because nothing is mid-draw, and {@code CgGlScope} is not held here.</p>
     *
     * <p>Failures are swallowed. A shader that will not compile is a real problem and the first real
     * frame will report it in the ordinary way; a warm-up must not be the thing that fails a context.</p>
     */
    public void warm(int width, int height) {
        // Leaks the Pass RenderState of every material below — doBind applies it, unbind() restores
        // none of it. Scoped by CgUiLifecycle.onInit, which wraps the construction too; no scope here.
        for (CgMaterial material : new CgMaterial[] { boxModelMaterial, curveMaterial, layerBlitMaterial }) {
            try {
                material.bind();
                material.unbind();
            } catch (RuntimeException | LinkageError ignored) {
                // See the note above: an optimisation that fails is silent.
            }
        }
        // AND THE TWO ASSET CACHES, both off the render thread. Neither needs GL, which is what makes
        // them a removal rather than a move -- see each method.
        preloadIcons();
        warmGlyphs(UIWindow.DEFAULT_UI_SCALE);

        // AND ONE EMPTY FRAME, which is the larger half. Compiling the shaders left the first real
        // beginFrame at 252 ms against 285 -- so most of that cost was never the GLSL: it is the quad
        // renderer's VAO and instance buffer, the text renderer, the scissor stack and the GL state
        // save, all built on first use inside this call. A frame that draws nothing pays for all of
        // them and leaves nothing on screen.
        try {
            beginFrame(Math.max(1, width), Math.max(1, height));
            endFrame();
        } catch (RuntimeException | LinkageError ignored) {
            // As above.
        }
    }

    public static CgUiPaintContext getInstance() {
        if (instance == null) instance = new CgUiPaintContext();
        return instance;
    }

    private final CgMaterial boxModelMaterial;

    /**
     * Shared material for every Bézier stroke — {@code gui_curve.shader}, the curve twin of
     * {@code gui_quad.shader}. Distinct from CrystalGraphics' own {@code curve.shader} because the UI
     * needs {@code DepthTest ALWAYS} and a {@code _LayerOpacity} property, neither of which belongs
     * in the backend's reference material.
     */
    private final CgMaterial curveMaterial;

    /**
     * Dedicated material for {@link #blitLayer}, distinct from {@link #boxModelMaterial}.
     * A visual-layer FBO is always cleared fully transparent before anything paints into it, so
     * at every partially-covered pixel its stored color ends up premultiplied by its own alpha —
     * compositing that back onto the screen needs premultiplied blend (@{@code srcRGB=ONE}), not
     * {@link #boxModelMaterial}'s straight-alpha blend (which is correct for its other, much more
     * common use: painting straight-alpha colors directly onto an already-opaque destination).
     */
    private final CgMaterial layerBlitMaterial;

    /** 1×1 fully opaque white ({@code RGBA = 255, 255, 255, 255}). */
    @Getter
    private final CgTexture2D whitePixel;

    @Getter
    private final PoseStack poseStack;

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

    // ── GL state isolation ──────────────────────────────────────────────────
    private CgGlScope glScope;

    // ── Visual layers (offscreen FBO compositing) ───────────────────────────
    // Screen-sized, not element-sized: draws inside a layer use the same absolute screen
    // coordinates (runtimeCache.getX()/getY()) as the normal path, so nothing needs translating —
    // matches LDLib2's own "off-target spans the full window" approach for the same reason.
    private int screenWidth, screenHeight;
    private long frameId;
    private final List<CgFrameBuffer> layerFboPool = new ArrayList<>();
    /** One saved frame per nested {@link #beginLayerFbo}/{@link #endLayerFbo} pair. */
    private final Deque<LayerFrame> layerStack = new ArrayDeque<>();
    private static final CgFrameBufferFormat LAYER_FORMAT =
            CgFrameBufferFormat.builder("cgui_layer").color(0, CgTextureType.RGBA8).build();

    private record LayerFrame(CgFrameBuffer fbo, CgGlScope glScope, Matrix4f savedProjMatrix,
                               int savedViewportW, int savedViewportH) {
    }

    // ── Whole-frame MSAA ─────────────────────────────────────────────────────
    //
    // This engine's box-model/curve rendering is analytic-SDF coverage antialiasing, which has a real
    // floor: a sufficiently thin, sufficiently zoomed-out shape (a shader-graph wire, say) can be
    // narrower than the screen can resolve, at which point no per-shader tuning fixes it. Real
    // multisampling supersamples the rasterizer's own coverage test regardless of how fine the geometry
    // is, which analytic coverage cannot substitute for at that limit. One target for the whole UI
    // tree, not per-material: the hardware coverage test needs to run against the actual triangle
    // edges, so this has to be the real render destination, not a filter applied after the fact.
    //
    // Cannot seed the target from whatever's already on screen first: glBlitFramebuffer only resolves
    // multisample -> single-sample, not the reverse. So this works the same way an opacity/mask LAYER
    // already does in this file — msaaFbo clears fully transparent, the whole UI tree paints into it
    // exactly as before, it resolves into msaaResolveFbo, and that gets composited back via the
    // existing blitLayer premultiplied-alpha path.
    //
    // No "is MSAA supported" branch here: MSAA_FORMAT asks for CgFrameBufferFormat.Builder.maxSamples()
    // — the driver's max, resolved once a live GL context exists — and a driver with no real
    // multisampling just resolves that to 1, i.e. an ordinary single-sampled FBO. Redirect, resolve and
    // composite always run the same way either way.
    //
    // Renderbuffer, not a texture: msaaFbo is never sampled directly, only resolved via blitFrom, so
    // there is no reason to pay for a sampleable multisampled texture. Pure data — no GL calls — so
    // this is safe as a static constant despite CgUiPaintContext's own materials/textures needing a
    // live context; only actually creating an FBO from it does.
    private static final CgFrameBufferFormat MSAA_FORMAT =
            CgFrameBufferFormat.builder("cgui_msaa").colorRenderbuffer(0, CgTextureType.RGBA8).maxSamples().build();

    /** Built once, in the constructor — real dimensions aren't known that early (no frame has run
     * yet), so this starts 1x1 and {@link #beginFrame} resizes it in place, the same way every other
     * screen-sized FBO in this file already tracks the window. */
    private final CgFrameBuffer msaaFbo = CgFrameBuffer.createOwned("cgui_msaa", 1, 1, MSAA_FORMAT);
    /** What {@link #msaaFbo} resolves into — same shape as {@link #LAYER_FORMAT}, and what {@link
     * #blitLayer} reads from to composite. Kept separate from {@link #layerFboPool}: that pool is
     * indexed by per-element nesting depth, which has nothing to do with this FBO's role as a single
     * fixed whole-frame resolve target. */
    private final CgFrameBuffer msaaResolveFbo = CgFrameBuffer.createOwned("cgui_msaa_resolve", 1, 1, LAYER_FORMAT);

    // ── Scissor ─────────────────────────────────────────────────────────────
    @Getter
    private final ScissorStack scissorStack = new ScissorStack();

    // ── State elision ───────────────────────────────────────────────────────
    @Getter
    private CgTexture2D currentTexture;
    @Getter
    private boolean frameActive;

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
     * {@code gui_quad.shader}'s doc comment). Every UI-facing material declares a
     * {@code _LayerOpacity} property; {@link #withMaterial} keeps whichever material is
     * currently bound in sync with this value on every switch.
     */
    @Getter
    private float layerOpacity = 1f;

    @Getter
    private final CgFont font = loadDefaultFont();

    @Getter @Setter
    private int color = 0xFFFFFFFF;

    private CgUiPaintContext() {
        this.poseStack = new PoseStack();
        this.renderer = new CgUiRenderer(this);
        this.boxModelMaterial = CgMaterial.load("crystalgui:shaders/gui_quad.shader");
        this.curveMaterial = CgMaterial.load("crystalgui:shaders/gui_curve.shader");
        this.layerBlitMaterial = CgMaterial.load("crystalgui:shaders/gui_layer_blit.shader");
        this.whitePixel = (CgTexture2D) CgFallbackTextures.WHITE_1x1;
        this.textRenderer = CgTextRenderer.createManualSized().poseStack(this.poseStack)
                                          .restoreStateWith(() -> {
                bindQuadPath(boxModelMaterial);
                currentTexture = null;
            });
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

            long frame = CgGraphicsLifecycle.getCurrentFrame();
            int anySize = cssSizes.iterator().next();
            for (List<String> stack : families) {
                CgFontFamily family = FontFamilyCache.resolve(stack, anySize);
                if (family == null) continue;
                CgFontRegistry.get().warmAscii(family.getPrimaryFont(), frame, effective);
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
     * Saves GL state via {@link CgGlScope}, overwrites {@link CgFrameData} with an orthographic
     * screen-space projection, and binds the shared box-model material. Call once per frame before
     * {@code rootElement.drawSubtree(ctx)}.
     */
    /**
     * Monotonic frame counter, for work a drawable wants to rate-limit to once per frame.
     *
     * <p>Exposed as a plain token rather than a callback so the dependency points the right way: a
     * drawable can ask "is this a new frame" without this class having to know which drawables exist.</p>
     */
    public long frameId() {
        return frameId;
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

    public void beginFrame(int screenWidth, int screenHeight) {
        frameId++;
        if (frameActive) throw new IllegalStateException("beginFrame() called without matching endFrame()");
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;

        // Save GL state before UI rendering — FBO included specifically so the whole-frame MSAA
        // redirect below has something to restore back to. No raw glGetInteger query: CgGlState
        // already shadows the current binding for exactly this purpose, and endFrame's early
        // glScope.close() (see its own note) is what puts the real target back before compositing.
        glScope = CgGlState.save(
                CgGlSlot.FBO, CgGlSlot.PROGRAM, CgGlSlot.TEXTURES, CgGlSlot.BLEND,
                CgGlSlot.DEPTH, CgGlSlot.CULL, CgGlSlot.VIEWPORT);

        // Whole-frame MSAA redirect — see the class doc above msaaFbo for why this exists and why it
        // has to be the whole tree rather than one material.
        int w = Math.max(1, screenWidth), h = Math.max(1, screenHeight);
        if (msaaFbo.getWidth() != w || msaaFbo.getHeight() != h) {
            msaaFbo.resize(w, h);
            msaaResolveFbo.resize(w, h);
        }
        // The clearColor below outlives this frame — CgFrameBuffer.clear scopes FBO alone and no
        // CgGlSlot models a clear value. Not ours to fix here (every caller of it leaks the same way)
        // and harmless against MC, which sets glClearColor immediately before each of its own clears.
        // Clear DEPTH is never touched: clearColor() passes GL_COLOR_BUFFER_BIT alone, and that one
        // WOULD matter — MC writes glClearDepth once at startup, like the glDepthFunc it sets there.
        // THE FULL-SCREEN CLEAR, timed apart from the rest of beginFrame. gl:begin was measured at 33ms
        // in a client, and this is the only thing in it that touches every pixel of the surface.
        long cleared = FrameProfile.begin();
        msaaFbo.bind();
        msaaFbo.clearColor(0f, 0f, 0f, 0f);
        FrameProfile.end(cleared, "glbegin:msaaClear");

        // Overwritten and deliberately NOT restored — the javadoc used to claim otherwise and was
        // corrected rather than implemented. CgFrameData is per-frame scratch that every consumer
        // repopulates before executing a pass, so at frame level nothing reads what we leave. NESTED
        // draws are the case that does need it, and already have it: CgPreviewRenderer copies the
        // camera out and back, because its caller is this frame. Restoring here also costs more than
        // it saves — prepareFrame() moves the active texture unit, so it needs a TEXTURES scope of its
        // own or MC's fixed-function present samples the wrong unit and the window goes white.
        CgRenderPipeline pipeline = CgRenderPipeline.getInstance();
        CgFrameData fd = pipeline.getFrameData();
        // Set ortho projection for UI
        fd.viewMatrix.identity();
        fd.projMatrix.identity().ortho(0, screenWidth, screenHeight, 0, -1, 1);
        fd.viewportW = screenWidth;
        fd.viewportH = screenHeight;
        pipeline.prepareFrame();

        // Text: projection + atlas LRU frame tick. No beginBatch() here — drawText()
        // deliberately stays standalone-per-call, see docs/CRYSTALGUI_TEXT_RENDERING_PLAN.md §2.3.
        textRenderer.context().updateOrtho(screenWidth, screenHeight);

        poseStack.pushPose();
        renderer.begin();
        bindQuadPath(boxModelMaterial);
        currentMaterial = boxModelMaterial;
        currentTexture = null;
        scissorStack.reset();
        frameActive = true; // must be set before warmUp() — quad() requires an active frame

        if (!warmedUp) {
            warmUp();
            warmedUp = true;
        }
    }

    private boolean warmedUp = false;

    /**
     * Eagerly creates (and cold-draws into) the layer-FBO pool slots a masked element with children
     * commonly needs, once, on the first frame — see {@link #warmUpLayer} for why. Depth 0 covers
     * the element's own background layer; depth 1 covers its children's nested layer; depth 2
     * covers the transient mask-shape FBO. Deeper nesting (an element whose child is *also* masked)
     * isn't pre-warmed here — it's covered automatically by {@link #acquireLayerFbo}'s own per-slot
     * warm-up whenever that depth is first reached, so this is a head start for the common case, not
     * the load-bearing part of the fix.
     */
    private void warmUp() {
        acquireLayerFbo(0);
        acquireLayerFbo(1);
        acquireLayerFbo(2);
    }

    /**
     * Unbinds the box-model material and restores GL state via the saved {@link CgGlScope}. Call once
     * after the whole UI tree has painted.
     */
    public void endFrame() {
        if (!frameActive) return;

        // No explicit unbind: CgQuadRenderer owns bind/unbind (see CgUiRenderer#useMaterial), and the
        // PROGRAM slot saved by beginFrame's CgGlScope restores whatever program was bound before the
        // UI painted anyway — which is the restoration that actually matters to the 3D pipeline.
        // Flush BEFORE end(): both renderers' flush() early-returns once begun is false, so anything
        // still queued at this point would be dropped without a word. Nothing hit that while every
        // draw path flushed eagerly (fillRect/drawImage both do), but ctx.quad()/ctx.curve() are
        // public and explicitly documented as "submit() queues, flush() draws" — so a caller batching
        // a few strokes and letting the frame end is using the API exactly as described. Drawing them
        // is the only defensible reading; the pose stack is still intact here and flush() reads none
        // of it anyway, since the pose was baked at submit() time.
        // Text first: it owns a separate renderer whose batch, if a caller left one open, would otherwise
        // flush after the frame's GL scope is torn down. Lenient when no batch is active.
        // SPLIT, because gl:end is three unrelated things and one of them was measured at 48ms in a
        // client while every CPU phase in that frame was under 2ms. Draining our own queued draws, the
        // MSAA resolve blit, and the composite back onto the real target fail for completely different
        // reasons -- and a resolve that blocks is the GPU being behind, which no amount of tuning our
        // traversal would ever touch.
        long timed = FrameProfile.begin();
        textRenderer.endBatch();
        renderer.flush();
        FrameProfile.end(timed, "glend:flush");

        // Resolve the MSAA redirect (see beginFrame/msaaFbo) and composite it back onto whatever the
        // real target was. blitFrom binds its own explicit source/destination ids and needs no
        // ambient FBO binding, so it runs fine before the restore below. Closing glScope HERE — early,
        // not at this method's usual end — is what puts the real target back (it saved CgGlSlot.FBO in
        // beginFrame): blitLayer() right after draws a real quad through the normal quad() path, which
        // needs the real target actually bound, and needs an active frame the same as any other draw
        // call in this class, which is why this whole block still runs before frameActive is cleared.
        long resolved = FrameProfile.begin();
        msaaResolveFbo.blitFrom(msaaFbo, CgGL.GL_COLOR_BUFFER_BIT, CgGL.GL_NEAREST);
        FrameProfile.end(resolved, "glend:msaaResolve");
        if (glScope != null) {
            glScope.close();
            glScope = null;
        }
        // Full opacity — the resolved texture already carries whatever per-element opacity the UI tree
        // itself applied while painting into msaaFbo; this composite is the "put the finished picture
        // on screen" step, not another opacity multiply.
        //
        // SCOPED, because this draw happens AFTER glScope.close() above and would otherwise be the one
        // piece of UI state nothing restores. blitLayer binds a material — so on return from endFrame a
        // shader program of ours is still current, with the frame's own restore already spent.
        //
        // In the harness that is invisible: nothing else in that process draws, so a stale program is
        // never observed. Minecraft observes it immediately. Its final present is
        // Framebuffer.framebufferRender, which is pure fixed-function — GL_TEXTURE_2D, GL_COLOR_MATERIAL
        // and a Tessellator quad — and it never calls glUseProgram(0). So Minecraft's blit of its own
        // framebuffer to the window runs through OUR vertex shader, which expects instanced quad data
        // out of an SSBO and gets immediate-mode vertices instead.
        //
        // The symptom is genuinely bewildering: the UI renders CORRECTLY into Minecraft's framebuffer —
        // a glReadPixels there shows the whole editor — while the window shows a flat fill, because the
        // step between the two is broken rather than the drawing. Anything that reads the framebuffer
        // (a screenshot tool, a capture) therefore disagrees with the screen.
        //
        // THE SLOTS ARE EVERYTHING A MATERIAL BIND CAN WRITE, not just the PROGRAM + TEXTURES the bug
        // above names: blitLayer applies gui_layer_blit's whole RenderState (Blend, DepthTest ALWAYS,
        // DepthWrite OFF, Cull OFF) and the frame's own scope closed six lines up. Measured leaving MC
        // with depthTest on, depthWriteMask false and blend on — a world drawn with no depth
        // arbitration, so terrain stops occluding its own caves. Listed as the full set CgRenderState
        // can write, so the next material to declare Stencil or ColorMask does not start it again.
        try (CgGlScope blitScope = CgGlState.save(CgGlSlot.PROGRAM, CgGlSlot.TEXTURES,
                CgGlSlot.BLEND, CgGlSlot.DEPTH, CgGlSlot.CULL,
                CgGlSlot.STENCIL, CgGlSlot.COLOR_MASK)) {
            blitLayer(msaaResolveFbo, 1f);
        }

        currentMaterial = null;
        currentTexture = null;
        frameActive = false;
        renderer.end();

        poseStack.popPose();

        if (!poseStack.clear()) throw new IllegalStateException("Unpopped stack(s) in UI frame");
    }

    // ── Public draw API ─────────────────────────────────────────────────────

    /** Solid-color fill, tint already includes opacity. */
    public void fillRect(float x, float y, float width, float height, int argb) {
        // ONE FLUSH PER RECTANGLE, and a flush is a draw call. Counted because `gl:draw` measures at
        // 21-30us per painted element -- far too much for a tree walk, and exactly the shape of
        // per-element driver overhead. 271 elements after a file is opened (up from 130 with none) at
        // one or more draw calls each is the whole 8.33ms budget spent submitting.
        FrameProfile.count("drawcalls", 1);
        bindTexture(whitePixel);
        quad().at(x, y).size(width, height).color(argb).submit();
        flush();
    }

    /** Textured draw with an explicit UV sub-rect (atlas support), tint already includes opacity. */
    public void drawImage(CgTexture2D texture, float x, float y, float width, float height,
                           float u0, float v0, float u1, float v1, int argb) {
        bindTexture(texture);
        // A failed load resolves to the fallback checkerboard, which has no meaningful sub-rect — a
        // caller's UV crop would sample an arbitrary corner of it. Full-range UVs keep a missing
        // texture looking like the recognisable "missing texture" it is, at whatever size it was
        // asked to draw. Was previously enforced centrally in submitQuad; it now lives at the two
        // sites that can actually be handed a fallback (here and CgUiSprite).
        FrameProfile.count("drawcalls", 1);
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
    public CgTextRenderer text() {
        // TEXT OWNS A SECOND RENDERER with its own material, so switching to it flushes the quad path
        // and switching back flushes text -- meaning every alternation between a box and a label is two
        // draw calls. An editor row is exactly that alternation, repeated per line.
        FrameProfile.count("textswitches", 1);
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
        FrameProfile.count("textpath-switches", 1);
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
        //textRenderer.beginBatch();
    }

    public void bindTexture(CgTexture2D texture) {
        if (texture == currentTexture) return;
        texture.bind(0);
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
        return renderer.quad();
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
     * Makes the quad path current, flushing and unbinding the curve path if it was.
     *
     * <p>Rebinds {@link #currentMaterial} rather than {@link #boxModelMaterial}: a {@link
     * #withMaterial} body that draws a curve and then a quad must come back to <em>its own</em>
     * material, not to the default one, or the rest of that body silently renders with the wrong
     * shader.</p>
     */
    private void beginQuadPath() {
        if (activePath == InstancePath.QUAD) return;
        endTextPath();
        renderer.flushCurves();
        // bindQuadPath sets activePath itself — the one place it is assigned for this path.
        bindQuadPath(currentMaterial != null ? currentMaterial : boxModelMaterial);
        currentTexture = null;
    }

    /** Makes the curve path current, flushing and unbinding the quad path if it was. */
    private void beginCurvePath() {
        if (activePath == InstancePath.CURVE) return;
        endTextPath();
        renderer.flushQuads();
        activePath = InstancePath.CURVE;
        // Layer opacity is a material property, so it has to be re-applied on the material actually
        // being bound — the value living on boxModelMaterial says nothing about this one.
        curveMaterial.applyProperties(b -> b.set1f("_LayerOpacity", layerOpacity));
        renderer.useCurveMaterial(curveMaterial);
        currentTexture = null;
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
        renderer.useMaterial(material);
        activePath = InstancePath.QUAD;
    }

    /**
     * Flushes renderer queue and draws all submitted quads
     */
    public void flush() {
        renderer.flush();
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
     * <p>{@code CgTextCuller} tests a {@link com.crystalgraphics.api.render.CgViewFrustum} because a 3D
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
        Matrix4f m = poseStack.last().pose();
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
        Matrix4f m = poseStack.last().pose();
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
        Matrix4f m = poseStack.last().pose();
        if (!isPoseAxisAligned() || Math.abs(m.m00()) < 1e-6f) return logicalX;
        float device = m.m00() * logicalX + m.m30();
        return (Math.round(device) - m.m30()) / m.m00();
    }

    /** The Y-axis twin of {@link #snapXToDevicePixel}. */
    public float snapYToDevicePixel(float logicalY) {
        Matrix4f m = poseStack.last().pose();
        if (!isPoseAxisAligned() || Math.abs(m.m11()) < 1e-6f) return logicalY;
        float device = m.m11() * logicalY + m.m31();
        return (Math.round(device) - m.m31()) / m.m11();
    }

    public boolean isVisible(float x, float y, float w, float h) {
        Matrix4f m = poseStack.last().pose();
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

        float clipX0 = 0f, clipY0 = 0f, clipX1 = screenWidth, clipY1 = screenHeight;
        if (scissorStack.hasScissor()) {
            // ScissorStack holds GL's bottom-left-origin pixels; flip back to the top-left-origin space
            // the pose just produced. Getting this backwards culls exactly the rows that ARE visible.
            clipX0 = scissorStack.currentX();
            clipX1 = clipX0 + scissorStack.currentW();
            clipY1 = screenHeight - scissorStack.currentY();
            clipY0 = clipY1 - scissorStack.currentH();
        }
        return maxX >= clipX0 && minX <= clipX1 && maxY >= clipY0 && minY <= clipY1;
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
        // A SCISSOR IS A FLUSH TOO, and every element with overflow pushes one. Counted beside the
        // fills because they add up in the same place: a clipped container costs a draw call to enter
        // and another to leave, whatever it contains.
        FrameProfile.count("scissors", 1);
        flush();
        Matrix4f m = poseStack.last().pose();
        float physX0 = m.m00() * x + m.m10() * y + m.m30();
        float physY0 = m.m01() * x + m.m11() * y + m.m31();
        float physX1 = m.m00() * (x + w) + m.m10() * (y + h) + m.m30();
        float physY1 = m.m01() * (x + w) + m.m11() * (y + h) + m.m31();


        int physX = (int) Math.floor(Math.min(physX0, physX1));
        int physY = (int) Math.floor(Math.min(physY0, physY1));
        int physW = (int) Math.ceil(Math.max(physX0, physX1)) - physX;
        int physH = (int) Math.ceil(Math.max(physY0, physY1)) - physY;
        // Top-left-origin logical space -> GL's bottom-left-origin glScissor space.
        int glY = screenHeight - (physY + physH);
        scissorStack.pushScissor(physX, glY, Math.max(0, physW), Math.max(0, physH));
        scissorStack.applyScissorIfNeeded();
    }

    /**
     * Pops the topmost clip rect, restoring the parent scissor (if any) or disabling
     * {@code GL_SCISSOR_TEST} entirely once the stack is empty.
     */
    public void popScissor() {
        flush();
        scissorStack.popScissor();
        if (scissorStack.hasScissor()) {
            scissorStack.applyScissorIfNeeded();
        } else {
            scissorStack.clearScissorIfNeeded();
        }
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
     * happens inside {@code bind()}'s own dirty-check. {@code drawBody} (e.g. {@code CgUiRoundedRect}'s
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
        material.applyProperties(b -> b.set1f("_LayerOpacity", layerOpacity));

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
        boxModelMaterial.applyProperties(b -> b.set1f("_LayerOpacity", layerOpacity));
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
        flush();
        float previous = layerOpacity;
        // Compose with the enclosing scope rather than overwriting it — a retargeted texture-valued
        // transition can nest a drawable (e.g. a CgUiCrossFade or mixed-fill CgUiRoundedRect) inside
        // another one; an absolute overwrite here would let the innermost call silently discard
        // every enclosing opacity, leaving the outer transition's own progress with zero visual
        // effect on whatever it wraps.
        layerOpacity = previous * opacity;
        currentMaterial.applyProperties(b -> b.set1f("_LayerOpacity", layerOpacity));
        bindQuadPath(currentMaterial);
        drawBody.run();
        flush();
        layerOpacity = previous;
        currentMaterial.applyProperties(b -> b.set1f("_LayerOpacity", layerOpacity));
        bindQuadPath(currentMaterial);
    }

    // ── Visual layers ────────────────────────────────────────────────────────

    /** Acquires (creating on first use) the pooled screen-sized layer FBO for the given nesting depth. */
    private CgFrameBuffer acquireLayerFbo(int depth) {
        while (layerFboPool.size() <= depth) {
            String name = "cgui_layer_" + layerFboPool.size();
            CgFrameBuffer newFbo = CgFrameBuffer.createOwned(name, Math.max(1, screenWidth), Math.max(1, screenHeight), LAYER_FORMAT);
            layerFboPool.add(newFbo);
            warmUpLayer(newFbo);
        }
        CgFrameBuffer fbo = layerFboPool.get(depth);
        if (fbo.getWidth() != screenWidth || fbo.getHeight() != screenHeight) {
            fbo.resize(Math.max(1, screenWidth), Math.max(1, screenHeight));
        }
        return fbo;
    }

    /**
     * Cold-draws a fully transparent, immediately-discarded quad into a freshly-created layer FBO
     * via {@link #layerBlitMaterial}, once, right when that FBO is created.
     *
     * <p>Root cause this works around: the very first masked/opacity element painted anywhere in
     * the process's life is also the first point {@link #layerBlitMaterial} (compiled lazily, on
     * its own first {@code bind()}) ever draws into a brand-new, never-drawn-to FBO — on at least
     * one NVIDIA driver, that specific "cold program's first draw into a cold FBO, same frame"
     * coincidence has been observed to silently produce nothing (verified via frame-by-frame
     * capture: the masked content is simply missing on frame 1, then permanently correct from frame
     * 2 onward). Forcing that same coincidence to happen here — right when the slot is created,
     * against throwaway content nobody reads — means whatever real content later reuses this exact
     * pool slot never hits a truly first-ever draw again, on any frame.</p>
     *
     * <p>Self-scaling by construction: this runs from {@link #acquireLayerFbo} itself, so it covers
     * every nesting depth the UI tree ever actually reaches, not just whatever depth
     * {@link #warmUp()} eagerly primes at startup.</p>
     *
     * <p><b>Public, because the pool is no longer the only thing that creates one.</b> Anything holding
     * its own render target through {@link #beginLayerFbo(CgFrameBuffer)} — a window snapshot, say —
     * inherits this hazard exactly, and inherits it in its most confusing form: the first capture comes
     * out missing content and every one after it is perfect, so it reads as a race in whatever was being
     * captured rather than in the target it was drawn onto.</p>
     */
    public void warmUpLayer(CgFrameBuffer fbo) {
        flush();
        CgMaterial previousMaterial = currentMaterial;
        CgTexture2D previousTexture = currentTexture;
        try (CgGlScope scope = CgGlState.save(CgGlSlot.FBO, CgGlSlot.VIEWPORT, CgGlSlot.PROGRAM, CgGlSlot.TEXTURES, CgGlSlot.BLEND)) {
            fbo.bind();
            CgGL.glViewport(0, 0, fbo.getWidth(), fbo.getHeight());
            fbo.clearColor(0f, 0f, 0f, 0f);
            withMaterial(layerBlitMaterial, () -> {
                bindTexture(whitePixel);
                quad().at(0, 0).size(fbo.getWidth(), fbo.getHeight()).color(0x0).submit();
                flush();
            });
        }
        // CgGlScope already restored the real GL FBO/program/texture bindings to whatever was active
        // before — resync the CPU-side bookkeeping fields withMaterial() left pointing at
        // boxModelMaterial back to match, rather than leaving them stale relative to the real GL state.
        currentMaterial = previousMaterial;
        currentTexture = previousTexture;
    }

    /**
     * Pushes a new screen-sized offscreen target and redirects subsequent drawing into it —
     * cleared fully transparent, same screen-space ortho convention {@link #beginFrame} sets up
     * for the real screen (just retargeted), so draws made while a layer is active use the exact
     * same absolute coordinates they always do. Nests correctly (a layered element containing
     * another layered element) via a small per-depth FBO pool. Pair with {@link #endLayerFbo}.
     *
     * @return the acquired FBO, for the caller to composite/blit once painting into it is done
     */
    public CgFrameBuffer beginLayerFbo() {
        return beginLayerFbo(acquireLayerFbo(layerStack.size()));
    }

    /**
     * As {@link #beginLayerFbo()}, but rendering into a target the CALLER owns and keeps.
     *
     * <p>The no-argument version hands out a screen-sized FBO from a per-depth pool, which is right for
     * an opacity or mask layer: those are composited and finished within the same frame, so the pool can
     * hand the same buffer to the next element that needs one. A SNAPSHOT is the opposite — the whole
     * point is that it outlives the frame it was drawn in, so it cannot come from a pool that will reuse
     * it, and it is sized to the thing it captures rather than to the screen.</p>
     *
     * <p>Everything else is identical, including the projection: the viewport and ortho are set from
     * {@code target}'s own dimensions, so a caller drawing at ordinary coordinates fills it. Pair with
     * {@link #endLayerFbo}.</p>
     *
     * <p>Ownership stays entirely with the caller — this neither allocates nor frees. A
     * {@code createOwned} framebuffer bypasses {@code CgFrameBufferRegistry}, so nothing sweeps it and
     * whoever made it has to say when it dies.</p>
     */
    public CgFrameBuffer beginLayerFbo(CgFrameBuffer fbo) {
        flush();
        CgFrameData fd = CgRenderPipeline.getInstance().getFrameData();
        layerStack.push(new LayerFrame(fbo, CgGlState.save(CgGlSlot.FBO, CgGlSlot.VIEWPORT),
                new Matrix4f(fd.projMatrix), fd.viewportW, fd.viewportH));

        fbo.bind();
        CgGL.glViewport(0, 0, fbo.getWidth(), fbo.getHeight());
        fbo.clearColor(0f, 0f, 0f, 0f);

        fd.projMatrix.identity().ortho(0, fbo.getWidth(), fbo.getHeight(), 0, -1, 1);
        fd.viewportW = fbo.getWidth();
        fd.viewportH = fbo.getHeight();
        CgRenderPipeline.getInstance().prepareFrame();
        currentTexture = null;
        return fbo;
    }

    /** Pops the innermost {@link #beginLayerFbo}, restoring the saved GL state and projection so
     * subsequent draws land back on whatever was active before it (the parent target, or an
     * enclosing layer). Does not composite/draw anything itself — see {@link #blitLayer} and
     * {@link #compositeMask} for what to do with the finished FBO. */
    public void endLayerFbo() {
        flush();
        LayerFrame frame = layerStack.pop();
        CgFrameData fd = CgRenderPipeline.getInstance().getFrameData();
        fd.projMatrix.set(frame.savedProjMatrix());
        fd.viewportW = frame.savedViewportW();
        fd.viewportH = frame.savedViewportH();
        CgRenderPipeline.getInstance().prepareFrame();
        frame.glScope().close();
        currentTexture = null;
    }

    /** Blits a finished layer FBO (from {@link #beginLayerFbo}/{@link #endLayerFbo}) back into
     * whatever's currently bound, full-screen, tinted by {@code opacity} via {@link #withLayerOpacity}
     * — everywhere the layer's own content didn't draw stayed transparent from the initial clear,
     * so this is safe to blit full-screen regardless of the originating element's own bounds.
     *
     * <p>Uses {@link #layerBlitMaterial}, not {@link #boxModelMaterial} — {@code fbo}'s contents are
     * premultiplied alpha (every partially-covered pixel was painted starting from a transparent
     * clear), so compositing it back needs premultiplied blend, not {@code boxModelMaterial}'s
     * straight-alpha blend. See {@code gui_layer_blit.shader}'s own doc comment for the full
     * derivation — using the wrong one reproduces exactly the "AA edges/translucent content look
     * different once behind a mask or fractional opacity" symptom this material fixes.</p>
     *
     * <p>V is sampled flipped ({@code v0=1, v1=0}): content drawn at screen-space y=0 (our top-left
     * origin convention) lands at NDC y=+1, which is texture row/{@code v=1} under OpenGL's own
     * bottom-left-origin texture convention — the opposite of a normal loaded-from-disk texture
     * (pre-flipped at decode time). Same correction {@code PictureInPictureRenderer.blitTexture}
     * applies in vanilla Minecraft/LDLib2 for the identical reason.</p>
     *
     * <p>{@code fbo}'s dimensions are real physical screen pixels (that's what it was allocated
     * with), but every quad submitted through {@link #quad} is run through the active
     * {@link PoseStack} transform — which, mid-frame, still carries {@code UIWindow}'s own
     * {@code uiScale} scale meant for logical-space element coordinates. Submitting an
     * already-physical-sized quad through that same scale would double-apply it. Temporarily
     * resetting the pose to identity for just this quad avoids that.</p> */
    /**
     * Draws a finished layer FBO into an arbitrary rect, through the active {@link PoseStack}.
     *
     * <p>{@link #blitLayer} composites a layer back over the whole screen at identity, which is what an
     * opacity group needs. This is for the other case: a captured layer being drawn somewhere else and
     * at another size — a window's snapshot in a taskbar preview. So the pose is <b>kept</b> rather than
     * reset, the rect is in ordinary logical coordinates, and the caller places it like any other quad.</p>
     *
     * <p>Same material and the same flipped V as {@code blitLayer}, and for the same reasons: an FBO's
     * contents are premultiplied alpha, because every partially-covered pixel was painted starting from
     * a transparent clear, so compositing it needs premultiplied blend rather than the box model's
     * straight-alpha one. Using the wrong material reproduces exactly the "AA edges look different once
     * they have been through a layer" symptom.</p>
     */
    /**
     * {@link #drawLayer(CgFrameBuffer, float, float, float, float)} at a given alpha.
     *
     * <p>A flat tint, and for a single texture that is exactly right: group opacity only needs a layer
     * pass because a subtree's own overlapping content would be double-darkened by a per-draw multiply,
     * and a photograph of that subtree has already resolved every overlap. So a surface can be faded for
     * the cost of one quad — which is the whole point of animating one. @see UIElement#paintAsSurface</p>
     */
    public void drawLayer(CgFrameBuffer fbo, float x, float y, float width, float height) {
        CgTexture2D colorTex = (CgTexture2D) fbo.getColorTexture(0);
        withMaterial(layerBlitMaterial, () -> {
            bindTexture(colorTex);
            quad().at(x, y).size(width, height)
                  .uv(0f, 1f, 1f, 0f)   // V flipped — see blitLayer's javadoc
                  .color(getColor()).submit();
            flush();
        });
    }

    public void blitLayer(CgFrameBuffer fbo, float opacity) {
        CgTexture2D colorTex = (CgTexture2D) fbo.getColorTexture(0);
        withMaterial(layerBlitMaterial, () -> withLayerOpacity(opacity, () -> {
            bindTexture(colorTex);
            poseStack.pushPose();
            poseStack.setIdentity();
            quad().at(0, 0).size(fbo.getWidth(), fbo.getHeight())
                  .uv(0f, 1f, 1f, 0f)   // V flipped — see the javadoc above
                  .color(getColor()).submit();
            flush();
            poseStack.popPose();
        }));
    }

    /**
     * Composites a mask onto an already-rendered subtree layer: multiplies {@code subtreeFbo}'s
     * existing color+alpha by {@code maskFbo}'s alpha channel via {@link CgBlendState#MASK_ALPHA_MULTIPLY}
     * — wherever the mask's alpha is 0, the subtree's output is zeroed out too. Both FBOs are the
     * pool's screen-sized instances, so they're always the same size. Leaves GL FBO/viewport/blend
     * state restored to whatever it was before this call (caller is responsible for re-binding
     * {@code subtreeFbo} itself if it needs to keep drawing into it afterward).
     */
    public void compositeMask(CgFrameBuffer subtreeFbo, CgFrameBuffer maskFbo) {
        flush();
        try (CgGlScope scope = CgGlState.save(CgGlSlot.FBO, CgGlSlot.VIEWPORT, CgGlSlot.BLEND)) {
            subtreeFbo.bind();
            CgGL.glViewport(0, 0, subtreeFbo.getWidth(), subtreeFbo.getHeight());
            CgTexture2D maskTex = (CgTexture2D) maskFbo.getColorTexture(0);
            bindTexture(maskTex);
            CgBlendState.MASK_ALPHA_MULTIPLY.apply();
            // Same v-flip as blitLayer — maskTex is another FBO color attachment, same OpenGL
            // bottom-left-origin storage vs. our top-left screen-space convention. Same
            // identity-pose bypass as blitLayer too — this quad is already physical-pixel-sized.
            poseStack.pushPose();
            poseStack.setIdentity();
            quad().at(0, 0).size(subtreeFbo.getWidth(), subtreeFbo.getHeight())
                  .uv(0f, 1f, 1f, 0f)   // V flipped, same reason as blitLayer
                  .color(0xFFFFFFFF).submit();
            flush();
            poseStack.popPose();
        }
        currentTexture = null;
    }

    /**
     * Releases everything this context owns outright and drops the singleton, so the next
     * {@link #getInstance()} builds a fresh one.
     *
     * <p>Called on GL-context destruction via {@code CgUiLifecycle}. Note that in this engine that
     * means <b>game shutdown only</b> — there is no destroy-then-init cycle in a running process — so
     * this is not protecting a subsequent context. It is explicit, complete teardown of what this
     * class owns, matching the engine's own convention, and it is the only thing that frees the layer
     * FBO pool: those are built with {@link CgFrameBuffer#createOwned}, which bypasses
     * {@code CgFrameBufferRegistry}, so {@code deleteAll()} never reaches them.</p>
     *
     * <p><b>Only genuinely-owned resources are freed here</b>, and the distinction matters because
     * double-freeing is as bad as leaking:</p>
     * <ul>
     *   <li><b>Freed</b> — the layer FBO pool, {@link #msaaFbo}/{@link #msaaResolveFbo} (all built via
     *       {@link CgFrameBuffer#createOwned}, so all ours), the {@link CgUiRenderer}'s batch renderer,
     *       and the {@link CgTextRenderer} (CrystalGraphics' registry treats {@code deleteAll()} as a
     *       backstop and expects owners to delete their own).</li>
     *   <li><b>Not freed</b> — {@code boxModelMaterial}/{@code layerBlitMaterial} come from the
     *       cache in {@code CgMaterialRegistry}, {@code whitePixel} is a
     *       {@code CgFallbackTextures} constant, and the atlases behind {@code font} belong to
     *       {@code CgFontRegistry}. All three are swept by
     *       {@code CgGraphicsLifecycle.destroyContext()} itself; deleting them here would be a
     *       double free of objects this context merely borrows.</li>
     * </ul>
     *
     * <p>Idempotent, and safe to call when the singleton was never constructed.</p>
     */
    public static void destroy() {
        if (instance == null) return;
        instance.releaseOwnedResources();
        instance = null;
    }

    private void releaseOwnedResources() {
        // Any still-open layer frame belongs to a frame that will never finish. Drop the saved GL
        // scopes without close()-ing them: their saved state refers to the dying context, so
        // restoring it is meaningless at best.
        layerStack.clear();

        for (CgFrameBuffer fbo : layerFboPool) {
            fbo.delete();
        }
        layerFboPool.clear();

        // Same reasoning as the layer pool above — createOwned bypasses CgFrameBufferRegistry, so
        // nothing else ever frees these. Not nulled out afterward (they're final, built once in the
        // constructor) — destroy() drops the whole singleton right after this, so a fresh instance
        // with fresh FBOs is what the next getInstance() builds anyway.
        msaaFbo.delete();
        msaaResolveFbo.delete();

        renderer.delete();
        textRenderer.delete();

        glScope = null;
        currentTexture = null;
        currentMaterial = null;
        frameActive = false;
    }
}
