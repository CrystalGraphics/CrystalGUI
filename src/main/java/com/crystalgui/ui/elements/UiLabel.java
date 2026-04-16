package com.crystalgui.ui.elements;

import com.crystalgui.core.geometry.UiRect;
import com.crystalgui.core.property.Property;
import com.crystalgui.core.render.CgUiPaintContext;
import com.crystalgui.ui.UIElement;
import dev.vfyjxf.taffy.geometry.FloatSize;
import dev.vfyjxf.taffy.geometry.TaffySize;
import dev.vfyjxf.taffy.style.AvailableSpace;
import dev.vfyjxf.taffy.util.MeasureFunc;
import io.github.somehussar.crystalgraphics.api.font.CgFontFamily;
import io.github.somehussar.crystalgraphics.api.font.CgFontMetrics;
import io.github.somehussar.crystalgraphics.api.font.CgTextLayoutBuilder;
import io.github.somehussar.crystalgraphics.api.text.CgTextLayout;
import lombok.Getter;

import javax.annotation.Nullable;

/**
 * Text label element with property-backed text, font, and color.
 *
 * <p>Text and font changes dirty both layout and render. Color changes dirty
 * render only. Intrinsic sizing uses {@link CgTextLayoutBuilder} to measure
 * text through CrystalGraphics' CPU-only text layout pipeline — no GL context
 * or render state is touched during measurement.</p>
 */
public class UiLabel extends UIElement {

    private final Property<String> textProperty;
    @Getter
    private CgFontFamily fontFamily;
    private final Property<Integer> colorProperty;

    /** Cached text layout for draw-time alignment. */
    @Nullable
    private CgTextLayout cachedLayout;
    /** Box dimensions used when cachedLayout was computed; zero until first measure. */
    private float cachedForWidth;
    private float cachedForHeight;

    /** Reusable layout builder — pure CPU, no GL involvement. */
    private final CgTextLayoutBuilder layoutBuilder = new CgTextLayoutBuilder();

    /** Taffy measure function — returned by getMeasureFunc() for layout integration. */
    private final MeasureFunc measureFunc = (knownDimensions, availableSpace) -> measureText(knownDimensions, availableSpace);

    public UiLabel(CgFontFamily fontFamily, String text, int color) {
        this.fontFamily = fontFamily;
        this.textProperty = new Property<>(text);
        this.colorProperty = new Property<>(color);
        // Labels are passive visual elements — let clicks pass through to parent
        setHitTestVisible(false);

        // Wire property changes to dirty flags
        textProperty.changed.connect((oldVal, newVal) -> {
            cachedLayout = null;
            markLayoutDirty();
            markRenderDirty();
        });

        colorProperty.changed.connect((oldVal, newVal) -> markRenderDirty());
    }

    // ── Property accessors ──────────────────────────────────────────────

    public Property<String> textProperty() {
        return textProperty;
    }

    public String getText() {
        return textProperty.get();
    }

    public void setText(String text) {
        textProperty.set(text);
    }

    public Property<Integer> colorProperty() {
        return colorProperty;
    }

    public int getColor() {
        return colorProperty.get();
    }

    public void setColor(int color) {
        colorProperty.set(color);
    }

    public void setFontFamily(CgFontFamily fontFamily) {
        if (this.fontFamily != fontFamily) {
            this.fontFamily = fontFamily;
            cachedLayout = null;
            markLayoutDirty();
            markRenderDirty();
        }
    }

    // ── Measure function for Taffy integration ──────────────────────────

    @Override
    @Nullable
    public MeasureFunc getMeasureFunc() {
        return measureFunc;
    }

    /**
     * Measures intrinsic text size. Pure CPU — no GL, no render state.
     *
     * <p>Converts Taffy AvailableSpace to raw floats for CgTextLayoutBuilder:
     * definite(v) → v, maxContent/minContent → 0 (unbounded).</p>
     */
    private FloatSize measureText(FloatSize knownDimensions, TaffySize<AvailableSpace> availableSpace) {
        String text = textProperty.get();
        if (text == null || text.isEmpty() || fontFamily == null) {
            return new FloatSize(0, 0);
        }

        float maxWidth = resolveAvailableSpace(availableSpace.width);
        float maxHeight = resolveAvailableSpace(availableSpace.height);

        // Use known dimensions if provided
        if (knownDimensions.width > 0) maxWidth = knownDimensions.width;
        if (knownDimensions.height > 0) maxHeight = knownDimensions.height;

        CgTextLayout layout = layoutBuilder.layout(text, fontFamily, maxWidth, maxHeight);
        cachedLayout = layout;
        cachedForWidth = maxWidth;
        cachedForHeight = maxHeight;
        return new FloatSize(layout.getTotalWidth(), layout.getTotalHeight());
    }

    private static float resolveAvailableSpace(AvailableSpace space) {
        if (space.getType() == AvailableSpace.Type.DEFINITE) {
            return space.getValue();
        }
        // maxContent and minContent → unbounded (0 in CG text-layout convention)
        return 0.0f;
    }

    // ── Drawing ─────────────────────────────────────────────────────────

    @Override
    public void draw(CgUiPaintContext ctx) {
        if (fontFamily == null || textProperty.get() == null || textProperty.get().isEmpty()
                || !ctx.hasTextServices()) {
            return;
        }

        UiRect box = getLayoutState().getLayoutBox();
        if (box.getWidth() <= 0 || box.getHeight() <= 0) {
            return;
        }

        ensureCachedLayout(box);

        float drawX = box.getX();
        float drawY = box.getY();

        if (cachedLayout != null && cachedLayout.getMetrics() != null) {
            CgFontMetrics metrics = cachedLayout.getMetrics();
            drawY += metrics.getAscender();
        }

        ctx.drawText(fontFamily, textProperty.get(), drawX, drawY, colorProperty.get());
    }

    private void ensureCachedLayout(UiRect box) {
        if (cachedLayout != null
                && Float.compare(cachedForWidth, box.getWidth()) == 0
                && Float.compare(cachedForHeight, box.getHeight()) == 0) {
            return;
        }
        String text = textProperty.get();
        if (text == null || text.isEmpty() || fontFamily == null) return;
        cachedLayout = layoutBuilder.layout(text, fontFamily, box.getWidth(), box.getHeight());
        cachedForWidth = box.getWidth();
        cachedForHeight = box.getHeight();
    }
}
