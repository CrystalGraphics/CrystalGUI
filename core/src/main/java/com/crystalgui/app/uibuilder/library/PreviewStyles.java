package com.crystalgui.app.uibuilder.library;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.crystalgui.style.ScopeGroup;
import com.crystalgui.style.StyleEngine;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.dom.UIDocument;

/**
 * The sheets a preview card's sample wears: every sheet the window has unscoped — the user-agent sheet, the
 * theme, the open documents' — installed once against the window's {@link ScopeGroup} of card roots.
 *
 * <pre>{@code
 * PreviewStyles styles = PreviewStyles.of(window);
 * new PreviewCard(styles.group());
 * styles.sync();                    // each frame; installs only when the window's sheets changed
 * }</pre>
 *
 * <p>A sample sits in a shadow root, so no rule matches it through the panel it is drawn in — a panel rule
 * with a descendant selector stops at the card.</p>
 */
public final class PreviewStyles {

    private static final Map<UIDocument, PreviewStyles> BY_WINDOW = new WeakHashMap<>();

    private final StyleEngine styles;
    private final ScopeGroup group = new ScopeGroup();

    /** The window's sheet revision the group last matched; the first sync always runs. */
    private int synced = -1;

    private PreviewStyles(UIDocument window) {
        this.styles = window.styles();
    }

    /** The window's one set: every card in a window shares its installation. */
    public static PreviewStyles of(UIDocument window) {
        return BY_WINDOW.computeIfAbsent(window, PreviewStyles::new);
    }

    public ScopeGroup group() {
        return group;
    }

    /** Mirrors the window's unscoped sheets onto the group, in order, when they differ. Free while the list is unchanged. */
    public void sync() {
        if (styles.sheetsRevision() == synced) return;
        List<StyleSheet> wanted = styles.getSheets(null);
        List<StyleSheet> installed = styles.getSheets(group);
        if (!wanted.equals(installed)) {
            for (StyleSheet sheet : installed) styles.removeStylesheet(sheet, group);
            for (StyleSheet sheet : wanted) styles.addStylesheet(sheet, group);
        }
        // AFTER the mirror's own installs, which moved the revision too.
        synced = styles.sheetsRevision();
    }
}
