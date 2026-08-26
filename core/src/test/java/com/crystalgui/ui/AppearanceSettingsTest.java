package com.crystalgui.ui;

import com.crystalgui.core.data.DataContext;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.settings.Settings;
import com.crystalgui.core.settings.SettingsRegistry;
import com.crystalgui.style.theme.ThemeRegistry;
import com.crystalgui.style.theme.UiTheme;
import com.crystalgui.ui.elements.workbench.WorkbenchSettings;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
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

        // BOTH pairs are offered, and that is the assertion worth keeping: the default moved to Islands
        // to match the frame, and Dark+ staying in the list is what makes the scheme axis a choice rather
        // than a rename. A user who wants VS Code's colours is one dropdown away.
        assertTrue("the scheme dropdown must offer Islands Dark",
                WorkbenchSettings.EDITOR_SCHEME.getOptions().contains("Islands Dark"));
        assertTrue("and must still offer Dark+",
                WorkbenchSettings.EDITOR_SCHEME.getOptions().contains("Dark+"));
        assertEquals("Islands Dark", WorkbenchSettings.EDITOR_SCHEME.getDefaultValue());
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

    /**
     * A preference is written to the store the application <b>listens on</b>, which stops being the
     * window's root element the moment the application is opened as a window.
     *
     * <h3>The two expressions agree in every fixture that puts the application at the root</h3>
     *
     * <p>Which is the harness — {@code new UIWindow(Ui.of(editor))} — and was every host until a window
     * compositor arrived. There {@code window.ui.rootElement.settings()} and the application's own store
     * are the same object and nothing can tell them apart. In a client the editor sits inside a
     * {@code WindowFrame} inside the desktop and they are two stores: the preference was written to one
     * while {@code WorkbenchSettings.install} subscribed to the other, so picking a theme stored the
     * choice, changed nothing on screen, and lost it on restart.</p>
     *
     * <p>The {@code assertNotSame} is the load-bearing half. Without it this passes in exactly the flat
     * topology that hid the bug.</p>
     */
    @Test
    public void theSettingsHostIsTheApplicationsOwnStoreNotTheWindowRoots() {
        UIElement root = new UIElement();
        UIElement betweenTheTwo = new UIElement();      // the desktop and its WindowFrame, in miniature
        UIElement application = new UIElement() {
            @Override
            public Object getData(DataKey<?> key) {
                return key == UiDataKeys.SETTINGS_HOST ? settings() : super.getData(key);
            }
        };
        UIElement somewhereInside = new UIElement();
        root.addChild(betweenTheTwo);
        betweenTheTwo.addChild(application);
        application.addChild(somewhereInside);

        Settings resolved = DataContext.from(somewhereInside).get(UiDataKeys.SETTINGS_HOST);
        assertSame("a preference must be written where the application listens",
                application.settings(), resolved);
        assertNotSame("the fixture is meant to hold TWO stores, or it proves nothing",
                root.settings(), resolved);
    }
}
