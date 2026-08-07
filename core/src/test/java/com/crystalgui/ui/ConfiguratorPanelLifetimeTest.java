package com.crystalgui.ui;

import com.crystalgui.core.settings.Setting;
import com.crystalgui.core.settings.Settings;
import com.crystalgui.core.settings.SettingsLayer;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.config.ConfigControl;
import com.crystalgui.ui.elements.config.ConfiguratorPanel;
import com.crystalgui.ui.elements.config.SettingsConfigurator;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>A rebuilt panel must release the rows it replaced.</b>
 *
 * <p>A bound control subscribes to its <em>store</em> so an edit made anywhere else reaches the widget,
 * and the store outlives the control by a long way — a {@code Settings} lives as long as the application,
 * a {@code GraphDocument} as long as the file is open, while an inspector rebuilds its controls on every
 * click. Nothing disconnected them, so the store accumulated one dead listener per row per rebuild, each
 * holding a widget that had already left the tree.</p>
 *
 * <p><b>Invisible from both ends</b>, which is why it is worth a test rather than a review: the host looks
 * correct because it subscribed, and the store looks correct because it notified. Nothing fails, nothing
 * logs, and the only symptom is a session that gets slower the longer it is used.</p>
 */
public class ConfiguratorPanelLifetimeTest extends UiTestBase {

    private static final Setting<Integer> INDENT = Setting.integer("editor.indent", "Indent", 4);
    private static final Setting<Boolean> WRAP = Setting.bool("editor.wrap", "Wrap", false);

    private static final List<Setting<?>> DECLARATIONS = List.of(INDENT, WRAP);

    /** The leak itself: twenty rebuilds must leave the store exactly as one does. */
    @Test
    public void rebuildingAPanelDoesNotAccumulateListenersOnTheStore() {
        Settings settings = new UIElement().settings();
        ConfiguratorPanel panel = new ConfiguratorPanel();

        SettingsConfigurator.build(panel, settings, SettingsLayer.USER, DECLARATIONS, null);
        int afterOne = settings.onChanged.connectionCount();
        assertTrue("the rows are supposed to follow the store at all", afterOne > 0);

        for (int i = 0; i < 20; i++) {
            panel.clearRows();
            SettingsConfigurator.build(panel, settings, SettingsLayer.USER, DECLARATIONS, null);
        }

        assertEquals("every rebuild left its listeners attached to the store",
                afterOne, settings.onChanged.connectionCount());
    }

    /**
     * <b>And the surviving rows still follow the store.</b>
     *
     * <p>Not a second version of the test above — it is the half that stops the cheap wrong fix from
     * passing. Disconnecting <em>everything</em> on the store, or never subscribing at all, satisfies the
     * count assertion perfectly and leaves an inspector that silently stops reflecting edits made
     * anywhere else. The count says what was released; this says what was not.</p>
     */
    @Test
    public void theRebuiltRowsStillFollowTheStore() {
        Settings settings = new UIElement().settings();
        ConfiguratorPanel panel = new ConfiguratorPanel();

        SettingsConfigurator.build(panel, settings, SettingsLayer.USER, DECLARATIONS, null);
        panel.clearRows();
        SettingsConfigurator.build(panel, settings, SettingsLayer.USER, DECLARATIONS, null);

        settings.set(SettingsLayer.USER, INDENT, 7);

        ConfigControl control = panel.control(INDENT.getId());
        assertNotNull("the rebuild should have produced a row for it", control);
        assertEquals("the live row did not hear the store change", 7,
                ((Number) control.getValueObject()).intValue());
    }
}
