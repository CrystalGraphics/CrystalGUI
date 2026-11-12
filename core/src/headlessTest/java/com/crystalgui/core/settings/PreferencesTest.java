package com.crystalgui.core.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.crystalgraphics.storage.config.CgLocalConfigStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** The desktop-wide preferences: one value under every document's settings, kept across a restart. */
public class PreferencesTest {

    private static final Setting<String> THEME = Setting.string(Preferences.THEME, "Theme", "Crystal Dark");
    private static final Setting<String> LOCAL = Setting.string("test.local", "Local", "none");

    @Before
    @After
    public void clean() {
        PreferencesStore.detachForTesting();
        Preferences.update(p -> p.withAll(Map.of()));
    }

    @Test
    public void aDesktopWideSettingWrittenInOneDocumentIsTheValueInEveryOther() {
        Settings first = new Settings();
        Settings second = new Settings();
        assertEquals("Crystal Dark", second.get(THEME));

        first.set(SettingsLayer.USER, THEME, "Crystal Light");

        assertEquals("Crystal Light", second.get(THEME));
        assertEquals(SettingsLayer.USER, second.sourceOf(THEME));
        assertEquals("Crystal Light", Preferences.current().theme());
    }

    @Test
    public void theWritersOwnListenersHearIt() {
        Settings settings = new Settings();
        boolean[] heard = {false};
        settings.onChanged.connect(change -> heard[0] = change.affects(THEME));
        settings.set(SettingsLayer.USER, THEME, "Islands Dark");
        assertTrue(heard[0]);
        assertFalse("writing the same value again changes nothing", settings.set(SettingsLayer.USER, THEME, "Islands Dark"));
    }

    @Test
    public void aSettingNobodyDeclaredStaysInItsOwnDocument() {
        Settings first = new Settings();
        Settings second = new Settings();
        first.set(SettingsLayer.USER, LOCAL, "mine");
        assertEquals("mine", first.get(LOCAL));
        assertEquals("none", second.get(LOCAL));
    }

    @Test
    public void resettingADesktopWideSettingReturnsItsDefaultEverywhere() {
        Settings settings = new Settings();
        settings.set(SettingsLayer.USER, THEME, "Crystal Light");
        settings.reset(SettingsLayer.USER, THEME);
        assertNull(Preferences.current().theme());
        assertEquals("Crystal Dark", new Settings().get(THEME));
    }

    @Test
    public void theTypedViewsReadWhatWasWritten() {
        Preferences.update(p -> p.withUiScale(1.5f).withLocale(Locale.JAPAN).withTokenOverride("accent", "#FF00AA")
                .withKeys("edit.save", List.of("Mod+S", "Mod+Shift+S")));
        Preferences now = Preferences.current();
        assertEquals(1.5f, now.uiScale(), 0f);
        assertEquals(Locale.JAPAN, now.locale());
        assertEquals("#FF00AA", now.tokenOverrides().get("--accent"));
        assertEquals(List.of("Mod+S", "Mod+Shift+S"), now.keymap().get("edit.save"));
    }

    @Test
    public void preferencesSurviveARestart() throws Exception {
        Path dir = Files.createTempDirectory("cgui-prefs");
        PreferencesStore.attach(new CgLocalConfigStorage(dir));
        Preferences.update(p -> p.withUiScale(3f).with(Preferences.THEME, "Crystal Light"));

        // A new process: nothing set, the same directory
        PreferencesStore.detachForTesting();
        Preferences.update(p -> p.withAll(Map.of()));
        assertEquals(2f, Preferences.current().uiScale(), 0f);
        PreferencesStore.attach(new CgLocalConfigStorage(dir));

        assertEquals(3f, Preferences.current().uiScale(), 0f);
        assertEquals("Crystal Light", Preferences.current().theme());
    }
}
