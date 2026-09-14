package com.crystalgui.app.uibuilder.glyph;

import javax.annotation.Nullable;

import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.CgUiSvg;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.overlay.Tooltip;

/**
 * A node's glyph in 12 px, tinted by its role — what a Hierarchy row and the Inspector's header both draw.
 *
 * <pre>{@code
 * GlyphView glyph = new GlyphView(tooltip);   // or null where the words are already on screen
 * row.append(glyph.element());
 * glyph.show(node);                           // per bind, and per frame: cheap when nothing moved
 * }</pre>
 *
 * <p>Rewrites the icon, the role class and the tooltip's words only when the node or its
 * {@link KindGlyphs#signature} changed, so asking every frame costs a few field reads for a settled glyph. A
 * glyph follows computed style, which an edit only changes on the next frame and a sheet or theme switch changes
 * with no edit at all — so asking per frame is what keeps it true.</p>
 */
public final class GlyphView {

    /** On the glyph element. A sheet sizes it; {@code .__glyph__.__glyph-<role>__} colours it. */
    public static final String CLASS = "__glyph__";

    private final UIElement slot = new UIElement();

    @Nullable
    private final Tooltip tip;

    @Nullable
    private UIElement node;

    private int signature;

    @Nullable
    private KindGlyphs.Glyph shown;

    /** @param tip the tooltip whose region over the glyph says its words, or null for none */
    public GlyphView(@Nullable Tooltip tip) {
        this.tip = tip;
        slot.addClass(CLASS);
        slot.setHitTest(false);
    }

    public UIElement element() {
        return slot;
    }

    /** What is drawn now, or null before the first {@link #show}. */
    @Nullable
    public KindGlyphs.Glyph shown() {
        return shown;
    }

    /** Draws {@code next}'s glyph. Returns whether anything a reader sees changed. */
    public boolean show(UIElement next) {
        int nextSignature = KindGlyphs.signature(next);
        if (next == node && nextSignature == signature && shown != null) return false;
        node = next;
        signature = nextSignature;
        return draw(KindGlyphs.of(next));
    }

    /** Draws the glyph of a new node of {@code kind} — a row offering the kind rather than showing a node. */
    public boolean showKind(Name kind) {
        return showGlyph(KindGlyphs.ofKind(kind));
    }

    /** Draws {@code glyph} as given — what a Library starter, which is no one kind, is drawn by. */
    public boolean showGlyph(KindGlyphs.Glyph glyph) {
        node = null;
        return draw(glyph);
    }

    private boolean draw(KindGlyphs.Glyph glyph) {
        if (glyph.equals(shown)) return false;
        if (shown == null || !glyph.icon().equals(shown.icon())) {
            CgUiSvg drawn = CgUiSvg.ofIcon(glyph.icon());
            CgUiDrawable painted = drawn == null ? CgUiDrawable.EMPTY : drawn;
            StyleGroup.defaultPipeline(slot.getStyle().getGeneralGroup(), g -> g.overlay(painted));
        }
        if (shown == null || glyph.role() != shown.role()) {
            if (shown != null) slot.removeClass(shown.role().cssClass());
            slot.addClass(glyph.role().cssClass());
        }
        if (tip != null && (shown == null || !glyph.words().equals(shown.words()))) tip.addRegion(slot, glyph.words());
        shown = glyph;
        return true;
    }
}
