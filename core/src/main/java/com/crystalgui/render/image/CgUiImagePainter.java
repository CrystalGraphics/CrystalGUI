package com.crystalgui.render.image;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.crystalgraphics.api.canvas.CgCanvas;
import com.crystalgraphics.api.canvas.CgCanvasBox;
import com.crystalgraphics.api.canvas.CgCanvasLayer;
import com.crystalgraphics.api.material.CgBlendMode;
import com.crystalgraphics.api.paint.brush.CgBrush;
import com.crystalgraphics.api.paint.brush.CgNineSlice;
import com.crystalgraphics.api.paint.brush.CgSpriteSheet;
import com.crystalgraphics.api.paint.brush.CgTiling;
import com.crystalgraphics.api.paint.pattern.CgGrid;
import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.vector.CgCornerRadii;
import com.crystalgraphics.api.vector.svg.CgSvg;
import com.crystalgraphics.gpu.resource.CgTexture2D;
import com.crystalgraphics.gpu.resource.CgTextureManager;
import com.crystalgraphics.gpu.resource.CgTextureSettings;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgraphics.api.paint.CgColor;
import com.crystalgui.render.texture.asset.FileIconTheme;
import com.crystalgui.style.property.visual.DrawableAlign;
import com.crystalgui.style.property.visual.DrawableFit;

/**
 * Draws a {@link CgUiImage} on a paint context's canvas: the one place a style's image becomes draws. One per painter
 * (a box tree's, a widget's), on its document's paint thread. Describe the draw, then {@link Draw#draw draw} it, in one
 * expression: the builder is this painter's scratch, reset by the next call.
 *
 * <pre>{@code
 * CgUiImagePainter images = new CgUiImagePainter();
 * images.image(ctx, icon, x, y, 16, 16).color(textColor).draw();                       // an icon in the text's colour
 * images.image(ctx, background, 0, 0, w, h).color(tint).radii(corners)
 *       .border(1, 1, 1, 1, edge, edge, edge, edge).draw();                            // shaped into the box
 * images.fill(ctx, 0xFF203040, 0, 0, w, h).radii(corners).draw();                      // a background colour
 * images.fit(icon, x, y, w, h, DrawableFit.CONTAIN, align, out);                       // where a sized image lands
 * }</pre>
 *
 * <ul>
 *   <li>{@code color} tints a fill, a gradient and a picture, and is the {@code currentColor} of an icon, a mark and a
 *       painter. White by default.</li>
 *   <li>{@code pictureTint} multiplies an icon's or a picture's own colours ({@code overlay-tint}). White by
 *       default.</li>
 *   <li>{@code radii} and {@code border} shape a fill, a gradient, a grid or a picture into the box; an icon, a mark
 *       and a painter draw unclipped, the border drawn over them.</li>
 * </ul>
 */
public final class CgUiImagePainter {

    /** How a sprite sheet loads: texel for texel, its pixels kept sharp. */
    static final CgTextureSettings PIXELS = CgTextureSettings.PLAIN.withSampler(CgSampler.NEAREST_CLAMP);
    /** Above this device size an SVG is artwork, not a UI icon: it is not fitted to the pixel grid. */
    private static final float SNAP_MAX_DEVICE_PX = 128f;
    private static final int WHITE = 0xFFFFFFFF;

    /** Resolved resources, shared by every painter: a value resolves once. Threads: concurrent. */
    private static final Map<CgUiImage.Picture, Picture> PICTURES = new ConcurrentHashMap<>();
    private static final Map<CgUiImage.Sheet, CgBrush> SHEETS = new ConcurrentHashMap<>();
    /** {@link CgSpriteSheet#generation} when {@link #SHEETS} was filled. */
    private static volatile int sheetGeneration = CgSpriteSheet.generation();
    private static final Map<CgUiImage.Icon, Icon> ICONS = new ConcurrentHashMap<>();
    /** This painter's grids as paints; the cascade holds few. */
    private final Map<CgUiImage.Grid, CgGrid> grids = new HashMap<>();

    private final Draw draw = new Draw();

    /**
     * Forgets every resolved picture, sheet entry and icon: their textures die with the graphics context, and the next
     * draw resolves them again from the texture manager. {@code CgUiRenderer.contextDestroyed} calls it.
     */
    public static void forgetResources() {
        PICTURES.clear();
        SHEETS.clear();
        ICONS.clear();
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    /** A draw of {@code image} into the box at {@code (x, y)}: describe it, then {@link Draw#draw}. */
    public Draw image(CgUiPaintContext ctx, CgUiImage image, float x, float y, float width, float height) {
        return draw.reset(ctx, image, 0, x, y, width, height);
    }

    /** A flat colour into the box, shaped as an image is: a {@code background-color} with no image. */
    public Draw fill(CgUiPaintContext ctx, int argb, float x, float y, float width, float height) {
        return draw.reset(ctx, null, argb, x, y, width, height);
    }

    /** One draw being described: {@link #image} or {@link #fill} hands it out, {@link #draw} draws it. */
    public final class Draw {
        private CgUiPaintContext ctx;
        @Nullable
        private CgUiImage image;
        private int fill, color, pictureTint;
        private float x, y, width, height;
        @Nullable
        private CgCornerRadii radii;
        private final Border border = new Border();

        private Draw() {
        }

        private Draw reset(CgUiPaintContext ctx, @Nullable CgUiImage image, int fill, float x, float y, float width,
                           float height) {
            this.ctx = ctx;
            this.image = image;
            this.fill = fill;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            color = pictureTint = WHITE;
            radii = null;
            border.clear();
            return this;
        }

        /** The tint and {@code currentColor}: the element's {@code color} for an icon or a mark. */
        public Draw color(int argb) {
            color = argb;
            return this;
        }

        /** Multiplies an icon's or a picture's own colours: {@code overlay-tint}. */
        public Draw pictureTint(int argb) {
            pictureTint = argb;
            return this;
        }

        /** Rounds the box's corners; null for square. */
        public Draw radii(@Nullable CgCornerRadii radii) {
            this.radii = radii;
            return this;
        }

        /** Each side's border width and colour, drawn over the image; its middle is transparent in the top colour. */
        public Draw border(float left, float top, float right, float bottom, int leftColor, int topColor,
                           int rightColor, int bottomColor) {
            border.set(left, top, right, bottom, leftColor, topColor, rightColor, bottomColor);
            return this;
        }

        /** Draws it on the context's canvas. Nothing for an empty box. */
        public void draw() {
            Border edge = border.active() ? border : null;
            if (image == null) paintFill(ctx, fill, x, y, width, height, radii, edge);
            else paint(ctx, image, x, y, width, height, color, pictureTint, radii, edge);
        }
    }

    /** A box's border: each side's width and colour, the top's being the colour its transparent middle takes. */
    private static final class Border {
        float left, top, right, bottom;
        int leftColor, topColor, rightColor, bottomColor;

        void set(float l, float t, float r, float b, int lc, int tc, int rc, int bc) {
            left = l;
            top = t;
            right = r;
            bottom = b;
            leftColor = lc;
            topColor = tc;
            rightColor = rc;
            bottomColor = bc;
        }

        void clear() {
            left = top = right = bottom = 0f;
        }

        boolean active() {
            return left > 0f || top > 0f || right > 0f || bottom > 0f;
        }
    }

    private void paintFill(CgUiPaintContext ctx, int argb, float x, float y, float width, float height,
                           @Nullable CgCornerRadii radii, @Nullable Border border) {
        if (width <= 0f || height <= 0f) return;
        CgCanvas canvas = ctx.canvas();
        if (radii == null && border == null) canvas.fill(x, y, width, height, argb);
        else shape(canvas.box(x, y, width, height).fill(argb), radii, border).draw();
    }

    private void paint(CgUiPaintContext ctx, CgUiImage image, float x, float y, float width, float height, int color,
                       int pictureTint, @Nullable CgCornerRadii radii, @Nullable Border border) {
        if (width <= 0f || height <= 0f) return;
        CgCanvas canvas = ctx.canvas();
        switch (image) {
            case CgUiImage.None none -> borderOnly(canvas, x, y, width, height, radii, border);
            case CgUiImage.Color c -> {
                if (radii == null && border == null) canvas.fill(x, y, width, height, CgColor.multiplyArgb(c.argb(), color));
                else shape(canvas.box(x, y, width, height).tint(color).fill(c.argb()), radii, border).draw();
            }
            case CgUiImage.Paint p -> shape(canvas.box(x, y, width, height).tint(color).fill(p.paint()), radii, border)
                    .draw();
            case CgUiImage.Gradient g -> {
                if ((color >>> 24) == 0) return;
                shape(canvas.box(x, y, width, height).fill(g.paint(width, height)).tint(color), radii, border).draw();
            }
            case CgUiImage.Grid g -> {
                if ((color >>> 24) == 0 || (g.argb() >>> 24) == 0 || g.lineWidth() <= 0f) {
                    borderOnly(canvas, x, y, width, height, radii, border);
                    return;
                }
                CgGrid grid = grids.computeIfAbsent(g, k -> CgGrid.lines(k.argb()).cell(k.cellX(), k.cellY())
                        .width(k.lineWidth()));
                shape(canvas.box(x, y, width, height).fill(grid).tint(color), radii, border).draw();
            }
            case CgUiImage.Picture p -> picture(canvas, p, x, y, width, height,
                    CgColor.multiplyArgb(CgColor.multiplyArgb(p.tint(), color), pictureTint), radii, border);
            case CgUiImage.Sheet s -> {
                CgBrush brush = sheet(s);
                if (brush == null) return;
                shape(canvas.box(x, y, width, height).tint(CgColor.multiplyArgb(color, pictureTint)).fill(brush), radii,
                        border).draw();
            }
            case CgUiImage.Icon i -> {
                icon(ctx, i, x, y, width, height, color, pictureTint);
                borderOnly(canvas, x, y, width, height, radii, border);
            }
            case CgUiImage.Mark m -> {
                m.mark().paint(canvas, x, y, width, height, color);
                borderOnly(canvas, x, y, width, height, radii, border);
            }
            case CgUiImage.Painted p -> {
                canvas.save().translate(x, y);
                try {
                    p.painter().paint(canvas, width, height, color);
                } finally {
                    canvas.restore();
                }
                borderOnly(canvas, x, y, width, height, radii, border);
            }
            case CgUiImage.CrossFade f -> {
                crossFade(ctx, f, x, y, width, height, color, pictureTint, radii);
                borderOnly(canvas, x, y, width, height, radii, border);
            }
            case CgUiImage.Layers l -> {
                // The first is on top: drawn last
                for (int i = l.layers().size() - 1; i >= 0; i--) {
                    paint(ctx, l.layers().get(i), x, y, width, height, color, pictureTint, radii, null);
                }
                borderOnly(canvas, x, y, width, height, radii, border);
            }
        }
    }

    /**
     * CSS's {@code cross-fade()}: {@code from} at {@code 1 - t} and {@code to} at {@code t}, added in a layer of their
     * own (Chromium's {@code CrossfadeGeneratedImage}, {@code plus-lighter}), so two opaque images mid-fade stay opaque
     * and the backdrop never shows through. The border is the caller's, drawn once.
     */
    private void crossFade(CgUiPaintContext ctx, CgUiImage.CrossFade f, float x, float y, float width, float height,
                           int color, int pictureTint, @Nullable CgCornerRadii radii) {
        CgCanvas canvas = ctx.canvas();
        try (CgCanvasLayer both = canvas.layer(x, y, width, height)) {
            try (CgCanvasLayer from = canvas.layer(x, y, width, height).opacity(1f - f.t())) {
                paint(ctx, f.from(), x, y, width, height, color, pictureTint, radii, null);
            }
            try (CgCanvasLayer to = canvas.layer(x, y, width, height).opacity(f.t()).blend(CgBlendMode.ADDITIVE)) {
                paint(ctx, f.to(), x, y, width, height, color, pictureTint, radii, null);
            }
        }
    }

    /** A picture's texture, loaded at its first use: what a {@code mask} reads the alpha of. */
    public CgTexture2D texture(CgUiImage.Picture picture) {
        return resolve(picture).texture;
    }

    /** A sprite sheet entry's texture, or null when the sheet or the entry is missing. */
    @Nullable
    public CgTexture texture(CgUiImage.Sheet sheet) {
        CgBrush brush = sheet(sheet);
        return brush == null ? null : brush.texture();
    }

    private static CgCanvasBox shape(CgCanvasBox box, @Nullable CgCornerRadii radii, @Nullable Border border) {
        if (radii != null) box.radii(radii);
        if (border != null) {
            box.border(border.top, border.right, border.bottom, border.left, border.topColor, border.rightColor,
                    border.bottomColor, border.leftColor);
        }
        return box;
    }

    /** The border alone, over what is drawn: its middle transparent in its own colour, so its edge has no dark fringe. */
    private static void borderOnly(CgCanvas canvas, float x, float y, float w, float h, @Nullable CgCornerRadii radii,
                                   @Nullable Border border) {
        if (border == null) return;
        shape(canvas.box(x, y, w, h).fill(border.topColor & 0x00FFFFFF), radii, border).draw();
    }

    // ── Pictures ──────────────────────────────────────────────────────────────

    /** A picture's texture and what it was resolved into. */
    private static final class Picture {
        final CgTexture2D texture;
        /** The region in UV, top-left origin. */
        final float u0, v0, u1, v1;
        final float width, height;
        final CgBrush brush;
        final boolean sliced;

        Picture(CgUiImage.Picture p, CgTexture2D texture) {
            this.texture = texture;
            boolean missing = texture.isMissing();
            float refW = p.refW() > 0 ? p.refW() : texture.getWidth(), refH = p.refH() > 0 ? p.refH() : texture.getHeight();
            // A region names both sides or neither
            boolean region = p.w() > 0 && p.h() > 0;
            float rx = p.x(), ry = p.y(), rw = region ? p.w() : refW, rh = region ? p.h() : refH;
            this.width = rw;
            this.height = rh;
            this.u0 = refW > 0f ? rx / refW : 0f;
            this.v0 = refH > 0f ? ry / refH : 0f;
            this.u1 = refW > 0f ? (rx + rw) / refW : 1f;
            this.v1 = refH > 0f ? (ry + rh) / refH : 1f;
            CgUiImage.Picture.Slices s = p.slices();
            this.sliced = s != null && (s.left() > 0 || s.top() > 0 || s.right() > 0 || s.bottom() > 0);
            // The region and slices are in the reference size's pixels; the brush is in the texture's own
            float kx = !missing && refW > 0f ? texture.getWidth() / refW : 1f;
            float ky = !missing && refH > 0f ? texture.getHeight() / refH : 1f;
            CgNineSlice slice = new CgNineSlice(s == null ? 0 : Math.round(s.left() * kx),
                    s == null ? 0 : Math.round(s.top() * ky), s == null ? 0 : Math.round(s.right() * kx),
                    s == null ? 0 : Math.round(s.bottom() * ky), !p.hollow(), p.sliceScale() / kx);
            CgTiling tiling = tiling(p.repeatX(), p.repeatY());
            this.brush = CgBrush.texture(texture)
                    .region(Math.round(rx * kx), Math.round(ry * ky), Math.max(1, Math.round(rw * kx)),
                            Math.max(1, Math.round(rh * ky)))
                    .nineSlice(slice).tiling(tiling).edges(tiling).sampler(texture.sampler());
        }
    }

    /** The one tiling naming both axes' repeats; a pair none names takes the horizontal mode both ways. */
    private static CgTiling tiling(CgUiImage.Repeat x, CgUiImage.Repeat y) {
        if (x == y) {
            return switch (x) {
                case STRETCH -> CgTiling.STRETCH;
                case REPEAT -> CgTiling.REPEAT;
                case ROUND -> CgTiling.ROUND;
                case SPACE -> CgTiling.SPACE;
            };
        }
        if (x == CgUiImage.Repeat.REPEAT && y == CgUiImage.Repeat.STRETCH) return CgTiling.REPEAT_X;
        if (x == CgUiImage.Repeat.STRETCH && y == CgUiImage.Repeat.REPEAT) return CgTiling.REPEAT_Y;
        return tiling(x, x);
    }

    private static Picture resolve(CgUiImage.Picture p) {
        Picture resolved = PICTURES.get(p);
        if (resolved != null) return resolved;
        resolved = new Picture(p, CgTextureManager.get().getOrCreate(p.path(), PIXELS));
        PICTURES.put(p, resolved);
        return resolved;
    }

    private static void picture(CgCanvas canvas, CgUiImage.Picture p, float x, float y, float w, float h, int tint,
                                @Nullable CgCornerRadii radii, @Nullable Border border) {
        Picture r = resolve(p);
        // A missing texture is drawn whole, a checkerboard that reads as missing; an unsliced plain one, its region
        if (r.texture.isMissing() || (!r.sliced && radii == null && border == null)) {
            CgCanvasBox box = canvas.box(x, y, w, h).fill(r.texture).tint(tint);
            (r.texture.isMissing() ? box : box.region(r.u0, r.v0, r.u1, r.v1)).draw();
            return;
        }
        shape(canvas.box(x, y, w, h).tint(tint).fill(r.brush), radii, border).draw();
    }

    @Nullable
    private static CgBrush sheet(CgUiImage.Sheet s) {
        int generation = CgSpriteSheet.generation();
        if (generation != sheetGeneration) {
            SHEETS.clear();
            sheetGeneration = generation;
        }
        CgBrush brush = SHEETS.get(s);
        if (brush != null) return brush;
        try {
            brush = CgSpriteSheet.of(s.sheet()).brush(s.element());
        } catch (IllegalArgumentException missing) {
            CrystalGuiCore.LOGGER.warn("[sprite] {}: {}", s.css(), missing.getMessage());
            return null;
        }
        SHEETS.put(s, brush);
        return brush;
    }

    // ── Icons ─────────────────────────────────────────────────────────────────

    /** An icon's document for the variant and generation it was read at, and its brushes by colour. */
    private static final class Icon {
        @Nullable
        FileIconTheme.Variant variant;
        int generation = -1;
        @Nullable
        CgSvg svg;
        /**
         * Its brushes by what they were made of, so an icon in two colours keeps both; searched in place, a hit
         * allocating nothing. Past {@link #BRUSHES_PER_ICON} the oldest is replaced. Cleared with the document.
         */
        final int[] colors = new int[BRUSHES_PER_ICON], tints = new int[BRUSHES_PER_ICON];
        final float[] strokes = new float[BRUSHES_PER_ICON];
        final CgBrush[] brushes = new CgBrush[BRUSHES_PER_ICON];
        int count, next;

        @Nullable
        CgBrush brush(int color, int tint, float stroke) {
            for (int b = 0; b < count; b++) {
                if (colors[b] == color && tints[b] == tint && strokes[b] == stroke) return brushes[b];
            }
            return null;
        }

        void keep(int color, int tint, float stroke, CgBrush brush) {
            int b = count < BRUSHES_PER_ICON ? count++ : next;
            next = (b + 1) % BRUSHES_PER_ICON;
            colors[b] = color;
            tints[b] = tint;
            strokes[b] = stroke;
            brushes[b] = brush;
        }

        void clearBrushes() {
            Arrays.fill(brushes, null);
            count = next = 0;
        }
    }

    /** Brushes kept per icon. */
    private static final int BRUSHES_PER_ICON = 8;

    /** The icon's document now: re-read when the theme's variant or the resources changed. */
    @Nullable
    private static CgSvg document(CgUiImage.Icon i, Icon state) {
        FileIconTheme.Variant variant = i.named() ? (i.variant() != null ? i.variant() : FileIconTheme.getVariant()) : null;
        if (state.generation != CgSvg.generation() || state.variant != variant) {
            state.generation = CgSvg.generation();
            state.variant = variant;
            state.clearBrushes();
            try {
                state.svg = CgSvg.of(i.path());
            } catch (IllegalArgumentException refused) {
                state.svg = null;
                CrystalGuiCore.LOGGER.warn("[svg] {} cannot be drawn: {}", i.path(), refused.getMessage());
            }
        }
        return state.svg;
    }

    private static Icon state(CgUiImage.Icon i) {
        return ICONS.computeIfAbsent(i, k -> new Icon());
    }

    private static void icon(CgUiPaintContext ctx, CgUiImage.Icon i, float x, float y, float w, float h, int color,
                             int pictureTint) {
        CgCanvas canvas = ctx.canvas();
        Icon state = state(i);
        CgSvg svg;
        CgBrush brush;
        synchronized (state) {
            svg = document(i, state);
            if (svg == null || svg.isEmpty() || svg.width() <= 0f || svg.height() <= 0f) return;
            int argb = CgColor.multiplyArgb(i.tint(), color);
            // The half-width is in device pixels; the brush's width in canvas units
            float stroke = i.monochrome() && i.strokeHalfWidth() > 0f
                    ? i.strokeHalfWidth() * 2f / Math.max(canvas.deviceScale(), 1e-4f) : 0f;
            brush = state.brush(argb, pictureTint, stroke);
            if (brush == null) {
                CgBrush b = CgBrush.svg(svg).color(argb);
                if (pictureTint != WHITE) b = b.tint(pictureTint);
                brush = i.monochrome() ? b.flat(stroke) : b;
                state.keep(argb, pictureTint, stroke, brush);
            }
        }
        float shown = Math.max(svg.width(), svg.height()) * Math.min(w / svg.width(), h / svg.height());
        // Snapped where a hinted icon is, and a snapped icon's raster is cached
        canvas.save().pixelSnap(snappable(canvas, shown));
        canvas.image(brush, x, y, w, h).restore();
    }

    /**
     * Whether a draw is a UI icon at rest: small, axis-aligned, at a scale a UI settles at (1, 1.5, 2). An icon mid-zoom
     * must not snap, or it judders between quantised positions.
     */
    private static boolean snappable(CgCanvas canvas, float logicalSize) {
        float device = canvas.deviceScale();
        if (logicalSize * device > SNAP_MAX_DEVICE_PX) return false;
        if (!canvas.rectilinear()) return false;
        return Math.abs(device * 2f - Math.round(device * 2f)) < 0.01f;
    }

    // ── Sizing ────────────────────────────────────────────────────────────────

    /** The image's natural width in logical pixels, or -1 when it has none (a colour, a gradient, a stack). */
    public float intrinsicWidth(CgUiImage image) {
        return switch (image) {
            case CgUiImage.Picture p -> resolve(p).width;
            case CgUiImage.Sheet s -> {
                CgBrush b = sheet(s);
                yield b == null ? -1f : b.regionWidth();
            }
            case CgUiImage.Icon i -> {
                CgSvg svg = iconDocument(i);
                yield svg == null ? -1f : svg.width();
            }
            default -> -1f;
        };
    }

    /** @see #intrinsicWidth */
    public float intrinsicHeight(CgUiImage image) {
        return switch (image) {
            case CgUiImage.Picture p -> resolve(p).height;
            case CgUiImage.Sheet s -> {
                CgBrush b = sheet(s);
                yield b == null ? -1f : b.regionHeight();
            }
            case CgUiImage.Icon i -> {
                CgSvg svg = iconDocument(i);
                yield svg == null ? -1f : svg.height();
            }
            default -> -1f;
        };
    }

    @Nullable
    private static CgSvg iconDocument(CgUiImage.Icon i) {
        Icon state = state(i);
        synchronized (state) {
            return document(i, state);
        }
    }

    /**
     * Where a sized image lands in a box, into {@code out} as {@code x, y, width, height}: {@code fill} stretches,
     * {@code contain} and {@code cover} scale its natural size, {@code none} keeps it; one with no natural size fills.
     */
    public void fit(CgUiImage image, float boxX, float boxY, float boxWidth, float boxHeight, DrawableFit fit,
                    DrawableAlign align, float[] out) {
        float naturalWidth = fit == DrawableFit.FILL ? -1f : intrinsicWidth(image);
        float naturalHeight = fit == DrawableFit.FILL ? -1f : intrinsicHeight(image);
        if (naturalWidth <= 0f || naturalHeight <= 0f) {
            out[0] = boxX;
            out[1] = boxY;
            out[2] = boxWidth;
            out[3] = boxHeight;
            return;
        }
        float width, height;
        switch (fit) {
            case NONE -> {
                width = naturalWidth;
                height = naturalHeight;
            }
            case CONTAIN -> {
                float scale = Math.min(boxWidth / naturalWidth, boxHeight / naturalHeight);
                width = naturalWidth * scale;
                height = naturalHeight * scale;
            }
            case COVER -> {
                float scale = Math.max(boxWidth / naturalWidth, boxHeight / naturalHeight);
                width = naturalWidth * scale;
                height = naturalHeight * scale;
            }
            default -> {
                width = boxWidth;
                height = boxHeight;
            }
        }
        // Leftover can be negative (cover): the alignment picks which side overflows
        out[0] = boxX + align.xFactor() * (boxWidth - width);
        out[1] = boxY + align.yFactor() * (boxHeight - height);
        out[2] = width;
        out[3] = height;
    }
}
