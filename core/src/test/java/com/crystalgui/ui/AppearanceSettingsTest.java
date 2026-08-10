package com.crystalgui.ui;

import com.crystalgui.core.settings.SettingsRegistry;
import com.crystalgui.style.theme.ThemeRegistry;
import com.crystalgui.style.theme.UiTheme;
import com.crystalgui.ui.elements.workbench.WorkbenchSettings;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The Appearance settings — the {@code plan_styling.md} §3.7 pair, mirroring IntelliJ's own page:
 * a Theme dropdown and an Editor color scheme dropdown, independently selectable.
 *
 * <p>What is pinned is the <b>contract between the three parties</b>: the declarations offer what
 * the registry holds (a dropdown listing themes that cannot be selected is worse than none), the
 * defaults name the shipped pair, and every offered display name maps back to a registry id — the
 * bridge {@code apply()} crosses on every settings change.</p>
 */
public class AppearanceSettingsTest {

    /** The declarations offer the shipped pair, and default to it. */
    @Test
    public void theDeclarationsOfferTheBuiltins() {
        ThemeRegistry.registerBuiltins();
        assertTrue("the Theme dropdown must offer Crystal Dark",
                WorkbenchSettings.UI_THEME.getOptions().contains("Crystal Dark"));
        assertEquals("Crystal Dark", WorkbenchSettings.UI_THEME.getDefaultValue());

        assertTrue("the scheme dropdown must offer Dark+",
                WorkbenchSettings.EDITOR_SCHEME.getOptions().contains("Dark+"));
        assertEquals("Dark+", WorkbenchSettings.EDITOR_SCHEME.getDefaultValue());
    }

    /**
     * Every offered display name maps to a registered artifact of the right role — the name→id
     * bridge {@code apply()} crosses. A name that maps to nothing would render a dropdown entry
     * that silently un-themes the window when picked.
     */
    @Test
    public void everyOfferedNameMapsToARegisteredArtifact() {
        ThemeRegistry.registerBuiltins();
        for (String name : WorkbenchSettings.UI_THEME.getOptions()) {
            assertNotNull("no registered theme is named '" + name + "'", themeNamed(name, UiTheme.Role.THEME));
        }
        for (String name : WorkbenchSettings.EDITOR_SCHEME.getOptions()) {
            assertNotNull("no registered scheme is named '" + name + "'", themeNamed(name, UiTheme.Role.SCHEME));
        }
    }

    /** Declaring registers both settings on the appearance page. */
    @Test
    public void declareRegistersTheAppearancePair() {
        WorkbenchSettings.declare();
        assertNotNull(SettingsRegistry.get().get("appearance.theme"));
        assertNotNull(SettingsRegistry.get().get("appearance.editorScheme"));
    }

    private static UiTheme themeNamed(String displayName, UiTheme.Role role) {
        var pool = role == UiTheme.Role.THEME ? ThemeRegistry.themes() : ThemeRegistry.schemes();
        for (UiTheme theme : pool) {
            if (theme.displayName().equals(displayName)) return theme;
        }
        return null;
    }
}
