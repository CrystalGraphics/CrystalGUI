package com.crystalgui.ui.box;

import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.text.CgShapedParagraph;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.render.text.FontFamilyCache;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.dom.UINodeRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * A run of text as a layout leaf: the first {@link Measurable}, and the shape every measured
 * skin takes (D5.10).
 *
 * <p>The old {@code UIText} could not be measured — it recomputed after layout and pushed its
 * height back into the cascade, because the layout engine's flex-wrap path measured a leaf
 * against its container's width rather than its own. That defect is fixed in the vendored fork
 * (taffy/MODIFICATIONS.md #1), so a text node answers the engine's question directly: given the
 * width you have, this is how tall I am. One pass, nothing written back.</p>
 *
 * <p>Shaping is retained and rebuilt only when the text or the resolved font family changes, never
 * on a resize; the family is reference-compared because {@code FontFamilyCache.resolve} caches by
 * {@code (paths, targetPx)}. Painting arrives with 5.4; this is the measure half.</p>
 */
public class TextNode extends UINode implements Measurable {

    public static final Name NAME = Name.of("text");

    static {
        UINodeRegistry.register(NAME, TextNode::new, UINodeRegistry.plain(NAME, false));
    }

    /** Narrow enough that every break opportunity breaks; the widest unbreakable run is the answer. */
    private static final float MIN_CONTENT_WIDTH = 1f;

    private String text = "";
    private @Nullable CgFontFamily shapedWith;
    private @Nullable String shapedText;
    private @Nullable CgShapedParagraph paragraph;
    private final List<Float> measuredAt = new ArrayList<>();

    public TextNode() {
        super(NAME);
    }

    public TextNode(String text) {
        this();
        setText(text);
    }

    public String text() {
        return text;
    }

    public TextNode setText(String text) {
        String next = Objects.requireNonNull(text, "text");
        if (next.equals(this.text)) return this;
        this.text = next;
        markTreeDirty();
        // The text IS state, so it has to be reported -- and notifyStateChanged walks out of every
        // enclosing shadow tree, so a composite's label dirties the composite (whose contract carries
        // the text) rather than a node no peer has heard of. Guarded on the value above, which is what
        // lets a panel mirror its model every tick without sending a delta per tick.
        notifyStateChanged();
        return this;
    }

    /** Every width this node was asked to measure at, oldest first. For tests. */
    public List<Float> measuredAt() {
        return Collections.unmodifiableList(measuredAt);
    }

    /**
     * 5.3's measurable is also the first thing on screen (5.4): the same retained paragraph the
     * measure shaped, laid out at the content width layout settled on, drawn through the backend's
     * text renderer with the pose the painter set. {@code color} is inherited, so a theme's text
     * colour reaches a run with no rule naming it.
     */
    @Override
    public void paintContent(CgUiPaintContext ctx, Box box) {
        if (text.isEmpty()) return;
        CgFontFamily family = resolveFamily();
        ensureShaped(family);
        float contentX = box.border().left + box.padding().left;
        float contentY = box.border().top + box.padding().top;
        float contentWidth = box.contentBoxWidth();
        CgTextLayout laid = paragraph.layout(contentWidth, 0f);
        Integer color = computedStyle().get(StylePropertyRegistry.COLOR);
        ctx.text().draw().layout(laid).family(family)
                .at(contentX, contentY)
                .color(color == null ? 0xFFFFFFFF : color)
                .pose(ctx.getPoseStack())
                .submit();
    }

    private CgFontFamily resolveFamily() {
        return FontFamilyCache.resolve(
                getStyle().getGeneralGroup().fontFamily(),
                Math.round(getStyle().getGeneralGroup().fontSize()));
    }

    private void ensureShaped(CgFontFamily family) {
        if (paragraph == null || family != shapedWith || !text.equals(shapedText)) {
            paragraph = CgTextLayout.of(text, family).shape();
            shapedWith = family;
            shapedText = text;
        }
    }

    @Override
    public Size measure(Constraints constraints) {
        // NaN is "no definite width". Max-content is one line however long, which CrystalGraphics
        // spells 0f; min-content is the widest word, which is what wrapping at (nearly) nothing gives.
        float width = constraints.wrapWidth();
        if (Float.isNaN(width)) width = constraints.wantsMinContentWidth() ? MIN_CONTENT_WIDTH : 0f;
        measuredAt.add(width);
        if (text.isEmpty()) return Size.ZERO;

        CgFontFamily family = resolveFamily();
        ensureShaped(family);
        CgTextLayout laid = paragraph.layout(width, 0f);
        return new Size(laid.totalWidth(), laid.totalHeight());
    }
}
