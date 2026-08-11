package com.crystalgui.render.texture;

import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.render.texture.asset.FileIconTheme;
import com.crystalgui.render.texture.svg.SvgDocument;

import javax.annotation.Nullable;

/**
 * Draws an {@link SvgDocument} into a rect — the drawable face of the SVG stack.
 *
 * <h3>Why this is not {@code SvgDocument} plementing the interface directly</h3>
 *
 * <p>The obvious move is to put {@code draw} on the document and be done. It does not work, and the reason
 * is the same one that makes {@code currentColor} late-bound in the first place: <b>a document is a shared,
 * cached parse result and a drawable carries presentation state.</b> {@link CgUiDrawable#draw} has no tint
 * parameter, so a document implementing it would have to hold the tint as a field — and a selected file-tree
 * row and an unselected one, drawing the same icon in the same frame, would be writing to the same field.
 * Whichever painted last would win for both.</p>
 *
 * <p>So: one document, as many drawables over it as there are appearances. The same relationship
 * {@link CgUiSprite} has with the sprite it points at.</p>
 *
 * <h3>Fitted, never stretched</h3>
 *
 * <p>The viewBox is scaled to fit inside the rect and centred, preserving aspect — SVG's own
 * {@code preserveAspectRatio="xMidYMid meet"} default, and the same rule {@link CgUiShape} spells out for
 * its own square coordinate space. No icon system lets its artwork stretch to its container, and a rect
 * whose aspect drifts with zoom is how you find out.</p>
 */
public final class CgUiSvg implements CgUiDrawable {

    private SvgDocument document;

    /**
     * The un-suffixed icon name when this drawable was built from one, otherwise null.
     *
     * <p><b>Late-bound like {@code currentColor}, and for the same reason.</b> An icon ships as two
     * drawings — {@code java.svg} and {@code java_dark.svg} — and which one applies is a property of the
     * active theme, not of the drawable. Resolving it at construction bakes in whatever variant happened
     * to be current, so every icon built before a theme swap keeps the old drawing: the editor tabs, the
     * breadcrumbs and the status bar all did.</p>
     *
     * <p>The alternative was a broadcast every consumer subscribes to, which is a contract whose second
     * half fails silently — it worked for the file tree, the one consumer somebody remembered to refresh
     * by hand. Resolving here makes every consumer correct without knowing this exists, including ones
     * written later. {@code SvgDocument.of} caches, so a re-resolve is a map lookup rather than a parse.</p>
     */
    @Nullable
    private final String iconName;

    /** The variant {@link #document} was resolved for, so a swap is detected rather than polled. */
    @Nullable
    private FileIconTheme.Variant resolvedFor;

    /**
     * The document to draw, re-resolved if the icon variant has moved under us.
     *
     * <p>Only ever does work for a drawable built from an icon NAME. One built from a path is a fixed
     * document by construction — {@code icon()} in a stylesheet, a sprite, anything with no light/dark
     * pair — and must not start guessing at variants of a path a caller chose deliberately.</p>
     */
    /**
     * Forces a variant for this drawable alone, or {@code null} to follow the theme.
     *
     * <p><b>For an icon drawn on an accented chip.</b> The variant is otherwise a property of the whole
     * application — one switch saying "everything is light" or "everything is dark" — and that is right
     * until a control paints a saturated fill under its own icon. A selected activity-rail button is blue
     * in both themes, so in a LIGHT theme its icon is suddenly the one thing on screen sitting on a dark
     * ground, and the light drawing goes muddy on it. IntelliJ flips exactly these to the dark variant
     * for the same reason.</p>
     *
     * <p>Only palette icons need this. A {@code currentColor} mark takes the element's {@code color}
     * (see {@link #followsTextColor()}) and a rule can simply make it white — which is what the folder
     * glyph beside these does. This exists for the two-tone IntelliJ icons that carry their own literal
     * colours and cannot be tinted at all.</p>
     */
    public CgUiSvg setVariantOverride(@Nullable FileIconTheme.Variant variant) {
        if (variantOverride == variant) return this;
        variantOverride = variant;
        // Force the next document() to re-resolve. Not a direct reload: this is called from state changes
        // (a panel gaining focus) that can fire several times a frame, and SvgDocument.of caches anyway.
        resolvedFor = null;
        return this;
    }

    @Nullable
    private FileIconTheme.Variant variantOverride;

    @Nullable
    private SvgDocument document() {
        if (iconName == null) return document;
        FileIconTheme.Variant current =
                variantOverride != null ? variantOverride : FileIconTheme.getVariant();
        if (current != resolvedFor) {
            resolvedFor = current;
            document = SvgDocument.of(
                    FileIconTheme.toResourcePath(FileIconTheme.withVariant(iconName, current)));
        }
        return document;
    }

    /**
     * What {@code currentColor} resolves to, multiplied by the context colour at draw time.
     *
     * <p>White by default, so an untinted icon takes the context colour alone — which is exactly what
     * {@link CgUiShape} does, and it is what makes a monochrome set (Feather, Lucide, Tabler — all authored
     * as {@code stroke="currentColor"}) theme from the cascade for free.</p>
     */
    private int tintArgb = 0xFFFFFFFF;

    private boolean monochrome;

    private float strokeHalfWidth;

    public CgUiSvg(SvgDocument document) {
        this.document = document;
        this.iconName = null;
    }

    private CgUiSvg(String iconName) {
        this.iconName = iconName;
    }

    /** Loads and wraps in one step, sharing the parsed document via {@link SvgDocument#of}. */
    @Nullable
    public static CgUiSvg of(String path) {
        SvgDocument document = SvgDocument.of(path);
        return document == null ? null : new CgUiSvg(document);
    }

    /**
     * Wraps an icon by NAME, following the light/dark variant for as long as the drawable lives.
     *
     * <p>The variant-aware counterpart to {@link #of(String)}, and what anything holding an icon across a
     * theme swap wants: a tab, a rail button, a breadcrumb. Takes the un-suffixed name — {@code
     * "crystalgui:code"} — and does its own {@code withVariant} + {@code toResourcePath}, so a caller that
     * applies either itself is bypassing the very thing this exists for.</p>
     *
     * <p>Null when the icon resolves to nothing in the CURRENT variant, matching {@code of}. A name whose
     * dark drawing is missing is not that case — {@code withVariant} already falls back to the base file,
     * which is how a variant-neutral icon ships once.</p>
     */
    @Nullable
    public static CgUiSvg ofIcon(@Nullable String iconName) {
        if (iconName == null) return null;
        CgUiSvg svg = new CgUiSvg(iconName);
        return svg.document() == null ? null : svg;
    }

    @Nullable
    public SvgDocument getDocument() {
        return document();
    }

    /**
     * Follows the element's {@code color} only when nothing gave this drawable a tint of its own.
     *
     * <p><b>Not a blanket yes, and not a blanket no.</b> A no is what shipped, and it made the whole
     * {@code currentColor} story a lie: the chrome marks in {@code ui/icons/} are Feather glyphs authored
     * as {@code stroke="currentColor"} precisely so the cascade can colour them, and they were painted
     * against a hard white context colour instead. Harmless while every surface was dark and the answer
     * was meant to be white anyway — the light theme is what makes it a bug, and the focused activity
     * rail button is where it shows first, since that one has a blue chip under it.</p>
     *
     * <p>A yes would have been a different bug. {@code icon(path, #RRGGBB, monochrome)} in a stylesheet
     * sets a tint HERE, and {@link #draw} multiplies tint by context colour — so an icon that was already
     * given a colour, on an element that also sets {@code color}, would come out as the product of the
     * two. The find bar's arrows set both and would have gone from grey to near-black.</p>
     *
     * <p>Hence the test: an <em>untinted</em> drawable has nothing of its own to protect and takes the
     * cascade's colour; a tinted one keeps what it was given. Multi-colour art is safe either way —
     * outside {@linkplain #setMonochrome monochrome} mode the file's literal colours are never touched,
     * and only its {@code currentColor} elements resolve against this.</p>
     */
    @Override
    public boolean followsTextColor() {
        return tintArgb == 0xFFFFFFFF;
    }

    /** What {@code currentColor} resolves to. The file's own literal colours are left alone — see {@link #setMonochrome}. */
    public CgUiSvg setTint(int argb) {
        this.tintArgb = argb;
        return this;
    }

    /**
     * Forces <b>every</b> colour in the file to the tint, not only {@code currentColor}.
     *
     * <p>Off by default, which is the distinction that matters: a themed monochrome set is authored as
     * {@code currentColor} and wants the tint; a logo has its own palette and must keep it. Turning this on
     * is asking for a silhouette, and is the right answer for a disabled state or a drag ghost.</p>
     */
    public CgUiSvg setMonochrome(boolean monochrome) {
        this.monochrome = monochrome;
        return this;
    }

    /**
     * Overrides the stroke half-width, in screen pixels. {@code 0} keeps the file's own widths.
     *
     * <p>Only consulted in {@linkplain #setMonochrome monochrome} mode, because the two go together: a set
     * whose colour the consumer decides is a set whose weight the consumer decides. Honouring the file's
     * {@code stroke-width} is right the rest of the time — it is stated in viewBox units, so it scales with
     * the icon instead of thinning as the icon grows.</p>
     */
    public CgUiSvg setStrokeHalfWidth(float halfWidth) {
        this.strokeHalfWidth = halfWidth;
        return this;
    }

    @Override
    public void draw(CgUiPaintContext ctx, float mouseX, float mouseY,
                     float x, float y, float width, float height) {
        SvgDocument document = document();
        if (document == null || document.isEmpty()) return;
        float boxWidth = document.width(), boxHeight = document.height();
        if (boxWidth <= 0f || boxHeight <= 0f || width <= 0f || height <= 0f) return;

        float scale = Math.min(width / boxWidth, height / boxHeight);
        float left = x + (width - boxWidth * scale) * 0.5f;
        float top = y + (height - boxHeight * scale) * 0.5f;
        if (snappable(ctx, Math.max(boxWidth, boxHeight) * scale)) {
            left = ctx.snapXToDevicePixel(left);
            top = ctx.snapYToDevicePixel(top);
        }

        int argb = ArgbMath.multiply(tintArgb, ctx.getColor());

        if (monochrome) {
            document.renderMonochrome(ctx, left, top, scale, argb, strokeHalfWidth);
        } else {
            document.render(ctx, left, top, scale, argb);
        }
    }

    // ── Pixel-grid fitting ──────────────────────────────────────────────────────────────────────────

    /**
     * Above this device size, artwork is being displayed rather than used as a UI icon — leave it alone.
     *
     * <p>Hinting is a small-size concern: it is the difference between a 16px icon's edges landing on
     * pixel boundaries or across them, and by 128px an edge that is half a pixel out is invisible. The
     * cap is what keeps the zoomable canvas out of this entirely.</p>
     */
    private static final float SNAP_MAX_DEVICE_PX = 128f;

    /**
     * Whether this draw is a UI icon on a settled pose, and therefore worth fitting to the pixel grid.
     *
     * <p>The second condition is the one that is easy to miss: <b>a canvas mid-zoom must not snap.</b>
     * Snapping quantizes position and size, so under a continuously changing pose an icon would jump
     * between quantized values as the zoom animated — legible as judder, and worse than the blur it
     * removes. Requiring the pose scale to sit on a half-integer restricts this to the states a UI
     * actually rests at ({@code uiScale} 1, 1.5, 2) and excludes the 1.37x of an in-flight zoom.</p>
     */
    private static boolean snappable(CgUiPaintContext ctx, float logicalSize) {
        float device = ctx.deviceScale();
        if (logicalSize * device > SNAP_MAX_DEVICE_PX) return false;
        if (!ctx.isPoseAxisAligned()) return false;
        return Math.abs(device * 2f - Math.round(device * 2f)) < 0.01f;
    }

    /**
     * <b>The drawn SIZE is deliberately not quantized here, and that was tried.</b>
     *
     * <p>Snapping the scale to a grid-preserving multiple (1:1, exact halves) genuinely does sharpen a
     * hinted icon — measured on {@code javaScript.svg}, sharp pixels as a fraction of all pixels it
     * touches: 97% at 16px and 89% at 8px, against 56% at 10px and 76% at 20px. But reaching one of
     * those from an arbitrary box means <em>shrinking the icon</em>, by up to a fifth, and how large an
     * icon looks is a decision the stylesheet has already made. A drawable that quietly overrides it
     * trades a defect the author chose for one they did not, and it shows immediately on blocky artwork,
     * where a fifth of the size is far more visible than a fifth of an edge is blurry.</p>
     *
     * <p>So the size is the caller's and only the ORIGIN is snapped. An icon whose box does not match
     * its artwork is a stylesheet bug and is fixed there.</p>
     */

    /**
     * The viewBox size, read 1:1 as logical UI pixels — the convention {@link CgUiDrawable#intrinsicWidth}
     * states for texture-backed drawables, and what {@code overlay-fit: contain|cover|none} resolves
     * against.
     */
    @Override
    public float intrinsicWidth() {
        SvgDocument document = document();
        return document == null ? -1f : document.width();
    }

    @Override
    public float intrinsicHeight() {
        SvgDocument document = document();
        return document == null ? -1f : document.height();
    }
}
