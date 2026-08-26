package com.crystalgui.mc.platform.service.content;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgui.mc.client.NativeTooltipHost;
import com.crystalgui.ui.elements.slot.NativeContent;
import com.crystalgui.ui.elements.slot.NativeContentService;
import com.crystalgui.ui.elements.slot.NativeProfile;
import com.crystalgui.ui.elements.slot.NativeSurface;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import org.lwjgl.opengl.GL11;
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
            if (descriptor.startsWith(Mc1710Content.SLOT_PREFIX)) {
                return new Mc1710Content.BoundSlot(
                        Integer.parseInt(descriptor.substring(Mc1710Content.SLOT_PREFIX.length())));
            }
            if (descriptor.startsWith(Mc1710Content.ITEM_PREFIX)) {
                return resolveItem(descriptor.substring(Mc1710Content.ITEM_PREFIX.length()));
            }
            if (descriptor.startsWith(Mc1710Content.FLUID_PREFIX)) {
                return resolveFluid(descriptor.substring(Mc1710Content.FLUID_PREFIX.length()));
            }
        } catch (RuntimeException malformed) {
            // Unrecognised is ORDINARY: a descriptor can name a mod that is no longer installed, or a
            // container this client never opened. Answering empty draws a bare well, where throwing would
            // turn a stale layout into a crash on the paint path.
            return NativeContent.EMPTY;
        }
        return NativeContent.EMPTY;
    }

    private NativeContent resolveItem(String body) {
        // name:damage:count, and the name may itself contain a colon (`minecraft:stone`), so the two
        // numeric fields are taken from the END rather than by splitting forwards.
        int lastColon = body.lastIndexOf(':');
        if (lastColon < 0) return NativeContent.EMPTY;
        int prevColon = body.lastIndexOf(':', lastColon - 1);
        if (prevColon < 0) return NativeContent.EMPTY;

        String name = body.substring(0, prevColon);
        int damage = Integer.parseInt(body.substring(prevColon + 1, lastColon));
        int count = Integer.parseInt(body.substring(lastColon + 1));

        Item item = (Item) Item.itemRegistry.getObject(name);
        if (item == null) return NativeContent.EMPTY;
        return new Mc1710Content.DisplayItem(new ItemStack(item, count, damage));
    }

    private NativeContent resolveFluid(String body) {
        int lastColon = body.lastIndexOf(':');
        if (lastColon < 0) return NativeContent.EMPTY;
        int prevColon = body.lastIndexOf(':', lastColon - 1);
        if (prevColon < 0) return NativeContent.EMPTY;

        Fluid fluid = FluidRegistry.getFluid(body.substring(0, prevColon));
        if (fluid == null) return NativeContent.EMPTY;
        int amount = Integer.parseInt(body.substring(prevColon + 1, lastColon));
        int capacity = Integer.parseInt(body.substring(lastColon + 1));
        return new Mc1710Content.DisplayFluid(new FluidStack(fluid, amount), capacity);
    }

    // ── Drawing ─────────────────────────────────────────────────────────────

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
            GL11.glColor4f(1f, 1f, 1f, 1f);

            RenderHelper.enableGUIStandardItemLighting();
            itemRenderer.zLevel = 0f;
            itemRenderer.renderItemAndEffectIntoGUI(mc.fontRenderer, mc.getTextureManager(), stack, 0, 0);
            // The host's own decorations -- stack size, durability bar, and whatever another mod has
            // hooked into item rendering. Reproducing them here would be a worse copy that drifts.
            itemRenderer.renderItemOverlayIntoGUI(mc.fontRenderer, mc.getTextureManager(), stack, 0, 0);
            RenderHelper.disableStandardItemLighting();
        } finally {
            popProjection();
        }
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
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);

            int colour = fluidStack.getFluid().getColor(fluidStack);
            GL11.glColor4f(((colour >> 16) & 0xFF) / 255f,
                           ((colour >> 8) & 0xFF) / 255f,
                           (colour & 0xFF) / 255f,
                           1f);
            mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);

            Tessellator tessellator = Tessellator.instance;
            tessellator.startDrawingQuads();
            // Bottom-up, because a tile that is cut should be cut at the top -- a tank fills upward and a
            // partial tile at the waterline is what that looks like. The slot has already narrowed the box
            // to the filled portion, so this only decides where the seam falls inside it.
            for (float y = boxH; y > 0f; y -= TILE) {
                float tileH = Math.min(TILE, y);
                float top = y - tileH;
                for (float x = 0f; x < boxW; x += TILE) {
                    float tileW = Math.min(TILE, boxW - x);
                    addTile(tessellator, icon, x, top, tileW, tileH);
                }
            }
            tessellator.draw();
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
     */
    private static void addTile(Tessellator tessellator, IIcon icon, float x, float y, float w, float h) {
        double uMin = icon.getMinU();
        double vMin = icon.getMinV();
        double uMax = uMin + (icon.getMaxU() - uMin) * (w / TILE);
        double vMax = vMin + (icon.getMaxV() - vMin) * (h / TILE);
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
    public void drawTooltip(NativeContent content, float x, float y, int screenWidth, int screenHeight) {
        ItemStack stack = stackOf(content);
        if (stack == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;

        ScaledResolution resolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int factor = Math.max(1, resolution.getScaleFactor());

        try (CgGlScope scope = CgGlState.hostForeign(
                CgGlSlot.PROGRAM, CgGlSlot.TEXTURES, CgGlSlot.BLEND, CgGlSlot.DEPTH,
                CgGlSlot.CULL, CgGlSlot.ALPHA_TEST, CgGlSlot.COLOR_MASK)) {
            // No shader program: vanilla's tooltip is fixed-function, and whatever material CrystalGUI
            // last bound would otherwise still be active and consume the draw.
            GL20.glUseProgram(0);
            pushProjection(0d, resolution.getScaledWidth_double(), resolution.getScaledHeight_double(), 0d);
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
                NativeTooltipHost.draw(stack, Math.round(x) / factor, Math.round(y) / factor);
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
