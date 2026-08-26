package com.crystalgui.ui.elements.slot;

/**
 * <b>The GL contract a piece of native content needs in order to draw correctly.</b>
 *
 * <p>An item and a fluid are not one escape hatch with two callers. Minecraft draws an item stack as a
 * depth-tested, lit model — a block item is real 3D geometry — and draws a fluid as flat, blended,
 * explicitly depth-<em>less</em> quads tiled out of the block atlas. Handing both the same bracket means
 * either paying for depth and lighting the fluid does not want, or denying the item the depth buffer it
 * cannot be correct without.</p>
 *
 * <p>So the profile travels with the content, and {@link NativeContentService} is told which contract to
 * establish before it hands GL over. Adding a third — an entity renderer wants {@link #MODEL}'s depth
 * plus a perspective projection — is a constant here and a branch in the loader, not a new seam.</p>
 *
 * <h3>Both profiles render into the same offscreen target</h3>
 *
 * <p>They differ in GL <em>state</em>, not in destination. That is deliberate and it is what makes the
 * loader side small: the target is always a known, small, top-left-origin box, so the implementation sets
 * up one ortho for it and never has to reconcile Minecraft's GUI scale against CrystalGUI's {@code
 * uiScale}. See {@link com.crystalgui.render.CgUiPaintContext#nativeContent} for why that matters.</p>
 */
public enum NativeProfile {

    /**
     * Flat, blended, no depth. Fluid sprites; anything that is a textured quad.
     *
     * <p>The implementation should enable blending and leave depth testing off. Nothing drawn under this
     * profile may rely on depth ordering — it is painted in submission order, exactly like the rest of
     * the UI.</p>
     */
    FLAT,

    /**
     * Depth-tested and lit. Item stacks; later, entity renderers.
     *
     * <p>The implementation gets a target with a real depth attachment, and is expected to enable depth
     * testing <em>and</em> depth writing and to set up whatever lighting its renderer needs. This is not
     * optional politeness: with no depth buffer {@code glEnable(GL_DEPTH_TEST)} behaves as always-pass,
     * so a block model draws its faces in submission order and comes out inside-out. That failure is
     * invisible for flat sprite items, which is precisely why it survives casual testing.</p>
     */
    MODEL
}
