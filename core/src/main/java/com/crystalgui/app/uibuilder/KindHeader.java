package com.crystalgui.app.uibuilder;

import com.crystalgui.app.uibuilder.glyph.GlyphView;
import com.crystalgui.app.uibuilder.glyph.KindGlyphs;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.control.HeaderControl;
import com.crystalgui.widget.text.UIText;

/**
 * The Element tab's heading for one node: its glyph, what it is in words, and its tag as a sheet names it.
 *
 * <pre>{@code
 * form.control("kind", "", new KindHeader(node));   // ▥  Row layout ........ element
 * }</pre>
 *
 * <p>The section band itself — a {@link HeaderControl} — so it sits in the panel exactly as every other
 * heading does. The glyph and words are the node's Hierarchy row's, from one resolver, and follow a layout
 * change on the next frame as the row does. No tooltip: the words the glyph would say are printed beside it.</p>
 */
final class KindHeader extends HeaderControl {

    /** On the heading. */
    static final String CLASS = "__kind-header__";

    /** The tag, dimmed and at the trailing edge. */
    static final String TAG_CLASS = "__kind-tag__";

    /** Takes the band's free space, so the words and the tag keep their natural widths. */
    static final String SPACER_CLASS = "__kind-spacer__";

    private final UIElement node;

    private final GlyphView glyph = new GlyphView(null);

    private final UIText tag = new UIText("");

    KindHeader(UIElement node) {
        super(ConfigDescriptor.header(""));
        this.node = node;
        addClass(CLASS);
        insertAt(0, glyph.element());
        UIElement spacer = new UIElement();
        spacer.addClass(SPACER_CLASS);
        spacer.setHitTest(false);
        append(spacer);
        tag.addClass(TAG_CLASS);
        tag.setHitTest(false);
        append(tag);
        tag.setText(tagOf(node.name()));
        refresh();
        onConnected(() -> document().animation().every(this, delta -> {
            refresh();
            return true;
        }));
    }

    private void refresh() {
        if (!glyph.show(node)) return;
        KindGlyphs.Glyph shown = glyph.shown();
        if (shown != null) title().setText(shown.words());
    }

    /** {@code element} for this engine's kinds — how a sheet spells the type — and {@code mymod:machine} for another's. */
    static String tagOf(Name kind) {
        return Name.DEFAULT_NAMESPACE.equals(kind.namespace()) ? kind.local() : kind.toString();
    }
}
