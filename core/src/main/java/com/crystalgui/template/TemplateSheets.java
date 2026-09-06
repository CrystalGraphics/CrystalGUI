package com.crystalgui.template;

import javax.annotation.Nullable;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.style.StyleScope;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.ui.dom.UIDocument;

/**
 * The one place a template resolves a stylesheet id — and therefore the one place in this package that
 * touches a file.
 *
 * <p>{@code StyleSheetRegistry} reads through CrystalGraphics, which a dedicated server has not got, so
 * a template holds sheet <b>ids</b> and a client calls this when it has a window. Keeping it to one
 * class is what makes "inflation is headless" checkable rather than hoped for.</p>
 */
final class TemplateSheets {

    private TemplateSheets() {
    }

    /** Adds the sheet under {@code id} to {@code window}, scoped to {@code root} when given, once. */
    static void install(UIDocument window, String id, @Nullable StyleScope root) {
        try {
            StyleSheet sheet = StyleSheetRegistry.of(id);
            if (sheet == null) return;
            if (root == null && window.styles().getSheets().contains(sheet)) return;
            window.styles().addStylesheet(sheet, root);
        } catch (RuntimeException | LinkageError missing) {
            // A sheet a document names and the host has not got costs the LOOK, never the tree. The
            // LinkageError arm is the headless one: reaching this at all on a server is the bug, and
            // saying so beats a NoClassDefFoundError three frames up.
            CrystalGuiCore.LOGGER.warn("[cgui] a document names the stylesheet '{}', which could not be "
                    + "loaded here; the tree is built without it", id, missing);
        }
    }
}
