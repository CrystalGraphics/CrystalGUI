package com.crystalgui.ui.elements.slot;

/**
 * <b>The box a {@link NativeContentService} draws into, and the only coordinate space it is given.</b>
 *
 * <p>Always an offscreen target owned by {@link com.crystalgui.render.CgUiPaintContext}, never the
 * screen. The implementation fills {@code (0, 0)} to {@code (width, height)} in a top-left-origin space
 * measured in <b>real framebuffer pixels</b>, and the paint context composites the result into the
 * element's box afterwards.</p>
 *
 * <h3>Why the host never sees CrystalGUI's coordinate space</h3>
 *
 * <p>It could not use it if it did. CrystalGUI's ortho lives in a shader uniform and its {@code uiScale}
 * lives in a {@code PoseStack} — neither is visible to a fixed-function renderer, which reads
 * {@code GL_PROJECTION} and {@code GL_MODELVIEW}. Meanwhile {@code CgUiScreen} deliberately ignores
 * Minecraft's own GUI scale, so whatever those matrices hold mid-frame belongs to a coordinate system
 * unrelated to ours. Reconciling the two would mean composing our pose into the fixed-function stack and
 * getting the scale ratio right on every version.</p>
 *
 * <p>Handing over a small box with its own origin dissolves that entirely: the implementation sets up one
 * ortho for a space it was just told the size of, and placement — {@code uiScale}, any CSS
 * {@code transform}, the ambient scissor — is applied by the composite, through the same pose every other
 * quad in the frame goes through. There is no scale to agree on because the host never draws into our
 * space at all.</p>
 *
 * <h3>Pixels, not logical units</h3>
 *
 * <p>{@link #width()} and {@link #height()} are post-{@code uiScale} device pixels, so an 18px slot at 2x
 * asks for 36 and composites 1:1. Sizing the target in logical units instead renders at half resolution
 * and scales up — which reads as "Minecraft's item renderer is blurry" rather than as a unit mistake
 * here.</p>
 */
public interface NativeSurface {

    /** Target width in real framebuffer pixels. Always at least 1. */
    int width();

    /** Target height in real framebuffer pixels. Always at least 1. */
    int height();

    /**
     * The box's width in CrystalGUI's logical units — {@link #width()} before {@code uiScale}.
     *
     * <p>Here because the two sizes answer different questions and an implementation needs both. The
     * pixel size is the <em>resolution</em> to render at; the logical size is the <em>scale</em> content
     * should be drawn to, and only the implementation knows which of its own units that maps to.</p>
     *
     * <p>An item wants neither: it is drawn in a 16-unit space that fills whatever box it is given, so
     * its projection is {@code ortho(0, 16, 16, 0)} and the viewport does the scaling. A tiled fluid
     * wants this one, because "how many 16px tiles fit" is a question about logical size — tiling
     * against the pixel size instead gives twice as many tiles at 2x and looks like a different
     * texture.</p>
     */
    float logicalWidth();

    /** The box's height in CrystalGUI's logical units. @see #logicalWidth() */
    float logicalHeight();

    /**
     * The GL contract the paint context has established for this draw.
     *
     * <p>Honour it rather than re-deriving it from the content: a {@link NativeProfile#MODEL} surface is
     * the only one with a depth attachment, so enabling depth testing on a {@link NativeProfile#FLAT}
     * one silently gets always-pass rather than an error.</p>
     */
    NativeProfile profile();
}
