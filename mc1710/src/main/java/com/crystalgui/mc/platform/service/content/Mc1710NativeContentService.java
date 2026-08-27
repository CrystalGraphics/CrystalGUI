package com.crystalgui.mc.platform.service.content;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgui.mc.client.NativeTooltipHost;
import com.crystalgui.ui.elements.slot.NativeContent;
import com.crystalgui.ui.elements.slot.NativeContentService;
import com.crystalgui.ui.elements.slot.NativeDescriptors;
import com.crystalgui.ui.elements.slot.NativeProfile;
import com.crystalgui.ui.elements.slot.NativeSurface;
import com.crystalgui.ui.elements.slot.NativeTileGrid;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;
import net.minecraftforge.client.IItemRenderer;
import net.minecraftforge.client.MinecraftForgeClient;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL20;

/**
 * <b>Minecraft 1.7.10's item and fluid renderers, behind CrystalGUI's native-content seam.</b>
 *
 * <p>Client-only, and registered from {@code ClientProxy} for that reason — {@code serverSmoke} asserts
 * that no client class is even <em>loaded</em> on a dedicated server, and this one names
 * {@code RenderItem} in a field descriptor.</p>
 *
 * <h3>Raw LWJGL here, on purpose</h3>
 *
 * <p>{@code core} has no fixed-function matrix API and must not grow one — {@code CgGLBackend} offers
 * push, pop and load and deliberately no {@code glMatrixMode}, because the shape of a projection for
 * Minecraft's item renderer is Minecraft-shaped knowledge and belongs in a loader. So the matrix work is
 * done with {@code GL11} directly, which is what {@code CgUiScreen} already does for its own
 * hand-back.</p>
 *
 * <p>That is also why {@code CgGlState}'s shadow is not consulted or updated here.
 * {@link com.crystalgui.render.CgUiPaintContext#nativeContent} has already opened a
 * {@code hostForeign} scope and invalidates on the way out, which is precisely the arrangement for
 * "foreign code is about to write GL behind the shadow's back".</p>
 */
public final class Mc1710NativeContentService implements NativeContentService {

    /** Vanilla's own GUI item renderer. Stateless apart from {@code zLevel}, so one is enough. */
    private final RenderItem itemRenderer = new RenderItem();

    /** Registers this service as the platform's native-content renderer. Client side only. */
    public static void register() {
        CgPlatform.provide(NativeContentService.SERVICE, new Mc1710NativeContentService());
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    // ── Resolving ───────────────────────────────────────────────────────────

    @Override
    public NativeContent resolve(String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) return NativeContent.EMPTY;
        try {
            // The grammar is core's (NativeDescriptors), so this method contains no string knowledge at
            // all -- only the mapping from parsed refs onto 1.7.10's registries. The parsers answer null
            // rather than throwing, so the dispatch is three questions.
            NativeDescriptors.SlotRef slot = NativeDescriptors.parseSlot(descriptor);
            if (slot != null) return new Mc1710Content.BoundSlot(slot.index());

            NativeDescriptors.ItemRef item = NativeDescriptors.parseItem(descriptor);
            if (item != null) return resolveItem(item);

            NativeDescriptors.FluidRef fluid = NativeDescriptors.parseFluid(descriptor);
            if (fluid != null) return resolveFluid(fluid);
        } catch (RuntimeException foreign) {
            // The parsers cannot throw, but the REGISTRY lookups behind resolveItem/resolveFluid are
            // foreign code reached from the paint path -- a backstop here is what keeps a misbehaving
            // mod registry a bare well rather than a crash mid-frame.
            return NativeContent.EMPTY;
        }
        // Unrecognised is ORDINARY: a descriptor can name a kind this version has no renderer for --
        // `entity:` someday -- or arrive from a description written by a newer server.
        return NativeContent.EMPTY;
    }

    /** A parsed item ref against 1.7.10's registry — the only knowledge left on this side of the seam. */
    private NativeContent resolveItem(NativeDescriptors.ItemRef ref) {
        Item item = (Item) Item.itemRegistry.getObject(ref.id());
        if (item == null) return NativeContent.EMPTY;
        return new Mc1710Content.DisplayItem(new ItemStack(item, ref.count(), ref.damage()));
    }

    /**
     * A parsed fluid ref against the 1.7.10 registry, which takes BARE names ({@code water}). A
     * namespaced name from a modern description is retried with its namespace stripped, because a
     * server on a newer version legitimately describes {@code minecraft:water} and the fluid it means
     * is the one this registry calls {@code water}.
     */
    private NativeContent resolveFluid(NativeDescriptors.FluidRef ref) {
        Fluid fluid = FluidRegistry.getFluid(ref.name());
        if (fluid == null) {
            int colon = ref.name().lastIndexOf(':');
            if (colon >= 0) fluid = FluidRegistry.getFluid(ref.name().substring(colon + 1));
        }
        if (fluid == null) return NativeContent.EMPTY;
        return new Mc1710Content.DisplayFluid(new FluidStack(fluid, ref.amount()), ref.capacity());
    }

    // ── Drawing ─────────────────────────────────────────────────────────────

    /**
     * <b>Turns the lightmap texture unit off — call once per frame, before painting.</b>
     *
     * <p>Vanilla does exactly this before every GUI it draws ({@code EntityRenderer.disableLightmap}).
     * Minecraft's item and block rendering is fixed-function <em>multi</em>-texturing, so with unit 1
     * still enabled the result is modulated by whatever is bound there — it does not fail, it shades
     * wrong, which reads as broken lighting rather than as a stray texture unit.</p>
     *
     * <h3>Why this cannot live in {@code core}</h3>
     *
     * <p>It was there, at the top of {@code beginFrame}, written through {@code CgGL} — and it broke the
     * lighting it was meant to fix. {@code CgGL.glActiveTexture} is <b>deduplicated</b> against the state
     * shadow, while {@code glDisable(GL_TEXTURE_2D)} is not tracked at all: {@code capabilityChanged}
     * has no notion of texture units, so it always issues. That makes the sequence an always-issued call
     * bracketed by two that may be elided — and when the shadow's belief about the active unit is stale,
     * the disable lands on <b>unit 0</b> and switches off fixed-function texturing on the unit Minecraft
     * actually samples. Items then draw untextured, which looks like the same lighting bug.</p>
     *
     * <p>The shadow cannot be taught this either: "texturing enabled on unit 1" is not a state it can
     * represent. So the operation belongs where it can be issued unconditionally — and going through
     * {@code OpenGlHelper} rather than raw {@code GL13} additionally keeps Minecraft's own state mirror
     * in step, which {@code hostForeign} is documented as unable to do.</p>
     */
    public static void prepareHostGl() {
        OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
    }

    @Override
    public void draw(NativeSurface surface, NativeContent content) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;

        if (surface.profile() == NativeProfile.MODEL) {
            drawItem(mc, surface, content);
        } else {
            drawFluid(mc, surface, content);
        }
    }

    /**
     * An item, in a 16-unit space that fills whatever box it was given.
     *
     * <p>{@code ortho(0, 16, 16, 0)} against a viewport already sized to the slot means a 16&#215;16 item
     * fills it at whatever resolution, with no scale factor computed anywhere — the viewport does it. The
     * depth range and the &#8722;2000 translate are vanilla's own GUI convention, which is what
     * {@code RenderItem} is written against.</p>
     */
    private void drawItem(Minecraft mc, NativeSurface surface, NativeContent content) {
        ItemStack stack = stackOf(content);
        if (stack == null) return;

        pushProjection(0d, 16d, 16d, 0d);
        try {
            // A real depth attachment exists on this target -- that is what the MODEL profile means, and
            // it is the whole reason a block item is not drawn inside-out here.
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            // RenderItem's 2D branch never ENABLES texturing -- it only ever inherits it, because
            // GuiContainer always has it on. drawFluid and drawTooltip both assert it; this one did not,
            // and an untextured item quad is a flat coloured square.
            GL11.glEnable(GL11.GL_TEXTURE_2D);

            // VANILLA'S OWN PREAMBLE, reproduced rather than guessed at. This is the exact sequence
            // GuiContainer.drawScreen runs before it draws a single slot, and every line of it earns its
            // place -- picking a subset is what produced three rounds of "the lighting is still wrong".
            //
            //   RenderHelper.enableGUIStandardItemLighting()
            //   glColor4f(1,1,1,1)
            //   glEnable(GL_RESCALE_NORMAL)
            //   OpenGlHelper.setLightmapTextureCoords(lightmapTexUnit, 240, 240)
            //   glColor4f(1,1,1,1)
            //
            // The two that were missing are the two that matter here:
            //
            // RESCALE_NORMAL, because renderItemIntoGUI scales a block model by 10 and a scaled normal is
            // no longer unit length -- so without it the diffuse term is wrong on every 3D item and the
            // block comes out flat and dark.
            //
            // setLightmapTextureCoords, because turning the lightmap UNIT off is not the same as saying
            // how bright the item is. The item's vertices still carry a lightmap coordinate, and left at
            // whatever the world render happened to leave it that is usually near-black. Vanilla pins it
            // to 240/240 -- full brightness -- for exactly this reason, and it is why a slot's contents
            // look the same in a bright cave mouth as at midnight.
            RenderHelper.enableGUIStandardItemLighting();
            GL11.glColor4f(1f, 1f, 1f, 1f);
            GL11.glEnable(GL12.GL_RESCALE_NORMAL);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240f, 240f);
            GL11.glColor4f(1f, 1f, 1f, 1f);

            // AND THE ONE VANILLA NEVER SETS, because it never has to. RenderItem's 2D branch ENABLES
            // GL_ALPHA_TEST (:552) without ever setting the func, and renderItemOverlayIntoGUI disables
            // blending (:709) before drawing the stack count through FontRenderer, which enables the
            // alpha test again and nothing else. So every transparent glyph texel and every transparent
            // icon texel is discarded purely by whatever glAlphaFunc the process happens to be carrying.
            //
            // Minecraft's own ambient is GREATER 0.1 (Minecraft:576), set once at startup and restored
            // all over EntityRenderer -- it is simply always true in a vanilla frame. It is NOT always
            // true here: no CgGlSlot models the alpha FUNC (ALPHA_TEST tracks the enable), the UI never
            // writes one, and at the GL default of (GL_ALWAYS, 0) nothing is discarded at all -- so the
            // stack count draws a black rectangle through the item behind it, with blending off.
            GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);

            itemRenderer.zLevel = 0f;
            drawItemIcon(mc, stack);
            // The host's own decorations -- stack size, durability bar, and whatever another mod has
            // hooked into item rendering. Reproducing them here would be a worse copy that drifts.
            itemRenderer.renderItemOverlayIntoGUI(mc.fontRenderer, mc.getTextureManager(), stack, 0, 0);
            RenderHelper.disableStandardItemLighting();
            // Paired with the enable above -- vanilla drops it on the way out too (GuiContainer line 7).
            GL11.glDisable(GL12.GL_RESCALE_NORMAL);
            // RenderItem never restores glColor, and renderEffect leaves it at the glint's purple
            // (0.5, 0.25, 0.8, 1). No CgGlSlot models glColor, so hostForeign cannot put it back either
            // and whatever draws next inherits it. Vanilla is immune only because GuiContainer re-issues
            // glColor4f(1,1,1,1) before every single slot.
            GL11.glColor4f(1f, 1f, 1f, 1f);
        } finally {
            popProjection();
        }
    }

    /**
     * The item's icon and its enchantment glint — <b>vanilla's, called verbatim</b> — followed by a
     * repair of the coverage the glint destroys on the way past.
     *
     * <h3>Why an enchanted stack needs anything special here</h3>
     *
     * <p>{@link com.crystalgui.render.CgUiPaintContext#nativeContent} draws the host into an offscreen
     * RGBA8 target and composites it with {@code gui_layer_blit.shader}
     * ({@code Blend ONE ONE_MINUS_SRC_ALPHA}), so <b>that target's alpha channel is our coverage
     * mask</b>. Minecraft treats destination alpha as a scratch working channel:</p>
     *
     * <pre>
     * RenderItem.renderGlint:669   OpenGlHelper.glBlendFunc(772, 1, 0, 0)
     *                            = glBlendFuncSeparate(GL_DST_ALPHA, GL_ONE, GL_ZERO, GL_ZERO)
     *
     *   rgb   = src.rgb * dst.a + dst.rgb * 1
     *   alpha = src.a   * 0     + dst.a   * 0   =  0
     * </pre>
     *
     * <p>772 is {@code GL_DST_ALPHA}, not {@code GL_DST_COLOR}, so the glint <em>reads</em> destination
     * alpha as its source factor and <em>writes</em> it to literal zero — over exactly the item's own
     * silhouette, {@code glDepthFunc(GL_EQUAL)} having clipped it to the texels the icon wrote depth
     * for. Neither half is incidental. The read is <b>how the glint comes out shaped like the item at
     * all</b>: both icon branches end on {@code glBlendFunc(770, 771, 1, 0)} — {@code dst.a := src.a}
     * under the alpha test — which exists to build precisely the mask the glint then consumes.</p>
     *
     * <h3>...so the mask is REBUILT afterwards, never protected during</h3>
     *
     * <p>Protecting it is the obvious repair, it was the first one shipped, and it is wrong: the
     * clearing is load-bearing. {@code renderGlint} draws its quad <b>twice</b>, and it is pass 0
     * zeroing the alpha that stops pass 1 drawing. Hold the alpha still and both passes land. See
     * {@link #rebuildCoverageMask} for the evidence; the short version is that it comes out twice as
     * intense as an inventory slot's.</p>
     *
     * <p>So vanilla is called the way any other GUI calls it — one line, no flags, no split — and the
     * coverage is re-stated afterwards out of the icons themselves.</p>
     *
     * <h3>Why the guard is three conditions</h3>
     *
     * <ul>
     *   <li><b>{@code hasEffect(0)}</b> — nothing else in the GUI path reaches {@code renderGlint}, so
     *       an unenchanted stack keeps the plain single call and pays nothing.</li>
     *   <li><b>Not the 3D-block branch</b> ({@code RenderItem:426}) — it draws no glint, so there is no
     *       damage to repair, and a repair built from flat 16x16 icon quads would be the wrong shape
     *       for a rotated block model's coverage anyway.</li>
     *   <li><b>No Forge {@code IItemRenderer}</b> — {@code ForgeHooksClient.renderInventoryItem} draws
     *       no glint either, and the repair would stamp a vanilla-icon-shaped mask over a picture the
     *       mod drew to some entirely different outline.</li>
     * </ul>
     */
    private void drawItemIcon(Minecraft mc, ItemStack stack) {
        if (!glintWillEraseCoverage(stack)) {
            itemRenderer.renderItemAndEffectIntoGUI(mc.fontRenderer, mc.getTextureManager(), stack, 0, 0);
            return;
        }

        // VANILLA, ENTIRELY UNTOUCHED -- glint included, destination alpha left to be destroyed exactly
        // as it is on framebufferMc. Vanilla decides the branch, the pass count, the per-pass glint and
        // the two-pass alpha interplay inside renderGlint; nothing here second-guesses any of it, which
        // is the only way the picture comes out identical to an inventory slot.
        itemRenderer.renderItemAndEffectIntoGUI(mc.fontRenderer, mc.getTextureManager(), stack, 0, 0);
        rebuildCoverageMask(mc, stack);
    }

    /**
     * Re-states the item's silhouette in the destination alpha, <b>without touching a single pixel of
     * colour</b>, after {@code renderEffect} has erased it.
     *
     * <h3>Why the mask has to be rebuilt rather than protected</h3>
     *
     * <p>The obvious guard — wrap {@code renderEffect} in
     * {@code glColorMask(true, true, true, false)} — is what shipped first, and it is <b>twice as
     * bright as Minecraft</b>. {@code renderGlint} draws its quad <b>twice</b> with different scroll
     * rates, both under {@code glBlendFuncSeparate(GL_DST_ALPHA, GL_ONE, GL_ZERO, GL_ZERO)}: the source
     * factor is the destination alpha, and the destination alpha is written to zero. So on a target
     * with real alpha, pass 0 draws at full strength and then <em>switches pass 1 off</em>, and
     * Minecraft's own GUI target is exactly such a target — {@code framebufferMc} is
     * {@code GL_RGBA8} ({@code Framebuffer:113}), cleared to alpha 0 ({@code Minecraft:510}), with
     * {@code fboEnable} defaulting to true. Protecting the alpha keeps pass 1 alive and doubles the
     * glint. It also explains a leather chestplate coming back as a featureless magenta blob: two
     * additive layers over a large solid icon saturate it.</p>
     *
     * <p>Two facts make "pass 1 contributes nothing" exact rather than approximate. The glint texture
     * is an <b>indexed PNG carrying a PLTE and no {@code tRNS} chunk</b> — every texel is alpha 255 —
     * so pass 0's alpha test discards none of it and it zeroes the destination alpha across the whole
     * quad, leaving pass 1 no gaps to show through. And nothing between the two draws can restore it:
     * depth cannot separate them either, since {@code RenderItem:652} sets {@code glDepthMask(false)}
     * and both quads are the same rect at the same {@code zLevel}.</p>
     *
     * <p>Mojang's own <em>world</em> glint corroborates the count from the other side.
     * {@code RenderItem:342-370} genuinely does composite two layers — {@code glBlendFunc(GL_SRC_COLOR,
     * GL_ONE)}, with no {@code DST_ALPHA} anywhere — and it <b>dims them</b>, {@code f11 = 0.76F}
     * scaling the colour before {@code glColor4f}. The GUI path uses the same {@code (0.5, 0.25, 0.8)}
     * undimmed, which is only reasonable for a single layer. Two undimmed layers sum toward
     * {@code (1.0, 0.5, 1.6)} — blue clips first, which is precisely the washed-out magenta that was
     * reported.</p>
     *
     * <p>So the alpha has to be left as vanilla's scratch channel and the mask rebuilt afterwards.</p>
     *
     * <h3>Why a rebuild, and not a redraw or the depth buffer</h3>
     *
     * <p>Re-running {@code renderItemIntoGUI} under a colour mask is the tidier-looking repair and it
     * breaks the multi-pass branch: that branch sets its <em>own</em> {@code glColorMask} at
     * {@code RenderItem:482} and restores it to all-true at {@code :492}, so the second half of the
     * repair would paint the icon back over the glint. Deriving the mask from the depth buffer fails on
     * the same branch for the same reason in a different disguise — the {@code :481} alpha-wipe quad
     * rasterises untextured and alpha-test-free across the full 20x20, so with depth testing on (which
     * is what {@link NativeProfile#MODEL} establishes) it writes depth over the whole slot and
     * {@code GL_EQUAL} would report the slot as covered.</p>
     *
     * <p>Drawing the icons ourselves has neither problem, because Minecraft is not running while it
     * happens and cannot revoke the guard. It is still all public API — {@code getRenderPasses},
     * {@code getIcon} and {@code RenderItem.renderIcon} — so no rendering behaviour is reproduced here,
     * only the coverage.</p>
     */
    private void rebuildCoverageMask(Minecraft mc, ItemStack stack) {
        Item item = stack.getItem();
        int passes = Math.max(1, item.getRenderPasses(stack.getItemDamage()));

        // Depth OFF, deliberately. renderItemAndEffectIntoGUI restores zLevel on the way out, so a
        // depth-tested rebuild would be a quad at z = 0 tested against an icon written at z = 50 --
        // LEQUAL fails, nothing draws, and the mask is silently never rebuilt. The mask does not want
        // depth for anything, so the safest thing is for z to be unable to matter.
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        // ALPHA TEST ON, which is what makes several passes UNION rather than overwrite: a later pass's
        // transparent texels are discarded and leave an earlier pass's coverage standing. It is the
        // same pairing vanilla builds its own mask with at RenderItem:499.
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        // BLENDING OFF, rather than a clever blend func. With GL_BLEND disabled the fragment is written
        // unmodified and the write mask below keeps RGB, so this is dst.a := src.a on any GL that runs
        // at all -- which is bit-identical to what vanilla's own (770, 771, 1, 0) produced for the mask
        // the glint has just destroyed. It also means no dependence on the hostile func renderEffect
        // leaves set on the way out: (GL_DST_ALPHA, GL_ONE, GL_ZERO, GL_ZERO), inert only because
        // RenderItem:659 disables blending, and pure garbage for anything that re-enables it without
        // setting its own.
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glColorMask(false, false, false, true);
        try {
            for (int pass = 0; pass < passes; pass++) {
                IIcon icon = item.getIcon(stack, pass);
                if (icon == null) continue;
                mc.getTextureManager().bindTexture(item.getSpriteNumber() == 0
                        ? TextureMap.locationBlocksTexture
                        : TextureMap.locationItemsTexture);
                itemRenderer.renderIcon(0, 0, icon, 16, 16);
            }
        } finally {
            GL11.glColorMask(true, true, true, true);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
        }
    }

    /** See {@link #drawItemIcon} — the three conditions are argued there. */
    private static boolean glintWillEraseCoverage(ItemStack stack) {
        if (!stack.hasEffect(0)) return false;
        if (stack.getItemSpriteNumber() == 0
                && RenderBlocks.renderItemIn3d(Block.getBlockFromItem(stack.getItem()).getRenderType())) {
            return false;
        }
        return MinecraftForgeClient.getItemRenderer(stack, IItemRenderer.ItemRenderType.INVENTORY) == null;
    }

    /**
     * A fluid, tiled out of the block atlas.
     *
     * <p>Flat and unlit, with depth testing explicitly off — the opposite of the item path in every
     * respect that matters, which is why {@link NativeProfile} exists rather than one bracket serving
     * both.</p>
     *
     * <p>The projection is in <b>logical</b> units so a 16-unit tile is 16 logical pixels at any
     * {@code uiScale}. Tiling against the pixel size instead would double the tile count at 2x and read
     * as a different texture.</p>
     */
    private void drawFluid(Minecraft mc, NativeSurface surface, NativeContent content) {
        if (!(content instanceof Mc1710Content.DisplayFluid)) return;
        FluidStack fluidStack = ((Mc1710Content.DisplayFluid) content).fluid();
        if (fluidStack == null || fluidStack.getFluid() == null) return;

        IIcon icon = fluidStack.getFluid().getStillIcon();
        if (icon == null) return;

        float boxW = Math.max(1f, surface.logicalWidth());
        float boxH = Math.max(1f, surface.logicalHeight());

        pushProjection(0d, boxW, boxH, 0d);
        try {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            // A SEPARATE ALPHA FUNCTION, which is where this deliberately parts company with TiC.
            //
            // TiC's FluidTankElement disables blending outright, and it is right to: it draws onto the
            // SCREEN, where a translucent fluid icon would ghost the GUI behind it. We draw into a
            // transparent-cleared FBO that gui_layer_blit.shader composites PREMULTIPLIED
            // (Blend ONE ONE_MINUS_SRC_ALPHA), and neither of the two obvious options is right there:
            //
            //   blend off                    rgb = C,        a = As   -- straight alpha into a
            //                                                            premultiplied composite, so
            //                                                            anything translucent is bright
            //   glBlendFunc(SRC_ALPHA, ...)  rgb = C*As,     a = As*As -- the NON-separate form uses the
            //                                                            colour factors for alpha too,
            //                                                            so coverage comes out squared
            //
            // The separate form gives rgb = C*As and a = As + Ad*(1-As): premultiplied colour and
            // properly accumulated coverage. It is the same pair gui_quad.shader declares for every
            // quad this engine draws, and for the opaque still-icon that is the ordinary case all three
            // agree -- so this costs nothing and is only visible on a mod fluid with real alpha.
            OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                                     GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);

            mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);

            Tessellator tessellator = Tessellator.instance;
            tessellator.startDrawingQuads();
            // THE TINT RIDES THE VERTICES, not glColor -- which is what both TiC implementations do
            // (setColorOpaque_I in SmelteryGui, glColor3ub in FluidTankElement). It must come after
            // startDrawingQuads, which resets hasColor, and it means the fluid cannot pick up whatever
            // glColor the item path left behind -- after an enchanted item that is the glint's purple.
            tessellator.setColorOpaque_I(fluidStack.getFluid().getColor(fluidStack));
            // THE GRID IS PINNED TO THE EDGE THAT MOVES, and the SURFACE is what knows which one that
            // is: FluidSlot has already narrowed the box to the filled portion, so a width and a height
            // cannot tell a tank's waterline from its floor. See NativeAnchor.
            //
            // This is the whole of "the fluid tiling looks wrong". Pinned to a STATIC edge -- which is
            // what this did, unconditionally -- the cut tile lands on the MOVING one, so the fluid's
            // surface shows a different slice of the sprite at every fill level and the seam walks as
            // the tank fills. That is the "intersects when 2 tiles repeat" artifact. Pinned to the
            // moving edge the surface is always a whole tile's edge, identical at every level, and the
            // remainder falls against a border.
            //
            // Both Tinkers' Construct tank renderers do it this way for the BOTTOM_UP case, which is
            // the reference: SmelteryGui tiles downward from `(cornerY + 68) - h - base`, and
            // RecipeHandlerBase from `position.y + position.height - amount`. Neither has to think
            // about the other three directions because neither has any.
            boolean fromRight = surface.anchor().fromRight();
            boolean fromBottom = surface.anchor().fromBottom();
            int rows = NativeTileGrid.count(boxH, TILE);
            int cols = NativeTileGrid.count(boxW, TILE);
            for (int row = 0; row < rows; row++) {
                float tileY = NativeTileGrid.startOf(boxH, TILE, row, fromBottom);
                float tileH = NativeTileGrid.sizeOf(boxH, TILE, row);
                for (int col = 0; col < cols; col++) {
                    addTile(tessellator, icon,
                            NativeTileGrid.startOf(boxW, TILE, col, fromRight), tileY,
                            NativeTileGrid.sizeOf(boxW, TILE, col), tileH,
                            fromRight, fromBottom);
                }
            }
            tessellator.draw();
            // A draw with GL_COLOR_ARRAY enabled -- which setColorOpaque_I turns on -- leaves the GL
            // CURRENT colour undefined per spec, so this is not tidiness. Without it whatever draws next
            // through fixed function inherits a colour nobody chose.
            GL11.glColor4f(1f, 1f, 1f, 1f);
        } finally {
            popProjection();
        }
    }

    /** A block texture is 16 units square, which is what makes a tile's UVs a simple proportion. */
    private static final float TILE = 16f;

    /**
     * One tile, with the partial edge handled by shrinking the UVs rather than clipping.
     *
     * <p>A scissor would be the other way to cut a tile, and it is worse here: the enclosing scissor is
     * already doing real work for whatever scroller the slot sits in, and nesting a second one inside a
     * foreign-GL bracket means restoring state the shadow cannot see.</p>
     *
     * <p><b>Which slice a cut tile shows is decided in core</b> — {@link NativeTileGrid#uvLo}/{@code uvHi}
     * carry the "sprite aligns to the anchored end" rule and the account of why (including where
     * Tinkers' Construct gets one axis of it wrong, and why this reverts {@code 0ec3db71}'s vMax
     * anchoring, which was right for the floor-pinned loop it was written against). What is left here is
     * only the mechanical map from those {@code [0..1]} fractions into this sprite's own UV interval.</p>
     */
    private static void addTile(Tessellator tessellator, IIcon icon, float x, float y, float w, float h,
                                boolean fromRight, boolean fromBottom) {
        double uSpan = icon.getMaxU() - icon.getMinU();
        double vSpan = icon.getMaxV() - icon.getMinV();
        double uMin = icon.getMinU() + uSpan * NativeTileGrid.uvLo(w / TILE, fromRight);
        double uMax = icon.getMinU() + uSpan * NativeTileGrid.uvHi(w / TILE, fromRight);
        double vMin = icon.getMinV() + vSpan * NativeTileGrid.uvLo(h / TILE, fromBottom);
        double vMax = icon.getMinV() + vSpan * NativeTileGrid.uvHi(h / TILE, fromBottom);

        tessellator.addVertexWithUV(x, y + h, 0d, uMin, vMax);
        tessellator.addVertexWithUV(x + w, y + h, 0d, uMax, vMax);
        tessellator.addVertexWithUV(x + w, y, 0d, uMax, vMin);
        tessellator.addVertexWithUV(x, y, 0d, uMin, vMin);
    }

    // ── Tooltip ─────────────────────────────────────────────────────────────

    /**
     * The stack's real Minecraft tooltip, drawn into the frame that is still open.
     *
     * <p>The one place the host draws into CrystalGUI's own target rather than into a scratch box, and it
     * has to: a tooltip's size is decided by its content, so there is nothing to size a target from. That
     * makes it also the one place a coordinate conversion is unavoidable.</p>
     *
     * <p>{@code x}/{@code y} arrive as <b>raw surface pixels</b>. Minecraft's tooltip renderer works in
     * its own GUI-scaled space, so both the position and the projection are converted into that space —
     * which is why the ortho below is vanilla's {@code setupOverlayRendering} shape rather than
     * CrystalGUI's. Wrapping the whole frame at that scale is what makes the tooltip come out the size a
     * player expects instead of the size CrystalGUI happens to be drawn at.</p>
     *
     * <p>Bracketed in {@code hostForeign} of its own: this is called after the tree has painted but before
     * {@code endFrame}, so the state it disturbs is state the composite still depends on.</p>
     */
    @Override
    public void drawTooltip(NativeContent content, float x, float y, int logicalWidth, int logicalHeight) {
        ItemStack stack = stackOf(content);
        if (stack == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;

        try (CgGlScope scope = CgGlState.hostForeign(
                CgGlSlot.PROGRAM, CgGlSlot.TEXTURES, CgGlSlot.BLEND, CgGlSlot.DEPTH,
                CgGlSlot.CULL, CgGlSlot.ALPHA_TEST, CgGlSlot.COLOR_MASK)) {
            // No shader program: vanilla's tooltip is fixed-function, and whatever material CrystalGUI
            // last bound would otherwise still be active and consume the draw.
            GL20.glUseProgram(0);
            // OUR SPACE, NOT MINECRAFT'S. The ortho is CrystalGUI's logical screen, so the tooltip is
            // drawn at the scale the UI around it is drawn at. Using ScaledResolution here -- which is
            // what this did first -- renders it at Minecraft's GUI scale instead, so it comes out a
            // different size from everything it is labelling, by however far apart the two settings are.
            pushProjection(0d, logicalWidth, logicalHeight, 0d);
            try {
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glDepthMask(false);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                GL11.glColor4f(1f, 1f, 1f, 1f);
                // Through the screen, because vanilla's tooltip renderer is a protected member of
                // GuiScreen and reproducing it would mean copying Minecraft's code into this repository.
                // It also gets the real thing -- rarity colouring, enchantments, lore, and every line
                // another mod contributes -- which is the entire reason this is delegated at all.
                NativeTooltipHost.draw(stack, Math.round(x), Math.round(y));
            } finally {
                popProjection();
            }
        }
        CgGlState.invalidateAllIfPresent();
    }

    // ── Shared ──────────────────────────────────────────────────────────────

    private static ItemStack stackOf(NativeContent content) {
        if (content instanceof Mc1710Content.BoundSlot) return ((Mc1710Content.BoundSlot) content).stack();
        if (content instanceof Mc1710Content.DisplayItem) return ((Mc1710Content.DisplayItem) content).stack();
        return null;
    }

    /**
     * Installs a projection for this surface and leaves {@code GL_MODELVIEW} active.
     *
     * <p>The near/far pair and the &#8722;2000 translate are vanilla's GUI convention. They matter: an
     * item model has real depth extent, so a shallow range clips it, and this is the range
     * {@code RenderItem} was written against.</p>
     *
     * <p><b>Leaving {@code GL_MODELVIEW} active is load-bearing</b>, not tidiness — {@code PoseStack}
     * writes through {@code glLoadMatrix} and assumes that mode without ever setting or checking it, so a
     * path that returned with {@code GL_PROJECTION} selected would corrupt the next element's transform
     * and nothing would report it.</p>
     *
     * <p>One push each, because {@code GL_PROJECTION}'s stack is only guaranteed two deep.</p>
     */
    private static void pushProjection(double left, double right, double bottom, double top) {
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(left, right, bottom, top, 1000d, 3000d);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glTranslatef(0f, 0f, -2000f);
    }

    private static void popProjection() {
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
    }
}
