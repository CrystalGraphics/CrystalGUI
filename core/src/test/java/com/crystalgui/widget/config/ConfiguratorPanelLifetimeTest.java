package com.crystalgui.widget.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.crystalgui.core.settings.Setting;
import com.crystalgui.core.settings.Settings;
import com.crystalgui.core.settings.SettingsLayer;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;

/**
 * <b>A bound control follows its store only while it is in a tree.</b>
 *
 * <p>The store outlives the control by a long way — a {@code Settings} lives as long as the application,
 * a {@code GraphDocument} as long as the file is open — while an inspector rebuilds its controls on every
 * click. A row that connected for good would leave the store one dead listener per row per rebuild, each
 * holding a widget that already left the tree: the host subscribed, the store notified, nothing failed, and
 * the only symptom was a session that got slower the longer it ran.</p>
 *
 * <p><b>The engine does this, not the owner</b>, so these assert it with nobody releasing anything.</p>
 */
public class ConfiguratorPanelLifetimeTest extends UiDocumentTestBase {

    private static final Setting<Integer> INDENT = Setting.integer("editor.indent", "Indent", 4);
    private static final Setting<Boolean> WRAP = Setting.bool("editor.wrap", "Wrap", false);

    private static final List<Setting<?>> DECLARATIONS = List.of(INDENT, WRAP);

    private UIElement root;
    private Settings settings;
    private ConfiguratorPanel panel;

    @Before
    public void openAPanel() {
        root = new UIElement();
        settings = new Settings();
        panel = new ConfiguratorPanel();
        root.append(panel);
        document.append(root);
        document.update(W, H);
    }

    private void fill() {
        SettingsConfigurator.build(panel.form(), settings, SettingsLayer.USER, DECLARATIONS, null);
    }

    /** The leak itself: twenty rebuilds must leave the store exactly as one does. */
    @Test
    public void rebuildingAPanelDoesNotAccumulateListenersOnTheStore() {
        fill();
        int afterOne = settings.onChanged.connectionCount();
        assertTrue("the rows are supposed to follow the store at all", afterOne > 0);

        for (int i = 0; i < 20; i++) {
            panel.clearRows();
            fill();
        }

        assertEquals("every rebuild left its listeners attached to the store",
                afterOne, settings.onChanged.connectionCount());
    }

    /**
     * <b>And the surviving rows still follow the store</b> — the half that stops the cheap wrong fix passing:
     * never subscribing satisfies the count and leaves an inspector deaf to edits made anywhere else.
     */
    @Test
    public void theRebuiltRowsStillFollowTheStore() {
        fill();
        panel.clearRows();
        fill();

        settings.set(SettingsLayer.USER, INDENT, 7);

        ConfigControl control = panel.control(INDENT.getId());
        assertNotNull("the rebuild should have produced a row for it", control);
        assertEquals("the live row did not hear the store change", 7,
                ((Number) control.getValueObject()).intValue());
    }

    /**
     * <b>Detaching releases, re-attaching restores — and the value catches up.</b>
     *
     * <p>A control legitimately leaves the tree and comes back — a tab hidden and shown, a pane retargeted —
     * and a release that did not re-subscribe would leave the row deaf in exactly the cases nobody tests.
     * The store can move while it is away, so coming back also re-reads.</p>
     */
    @Test
    public void aDetachedControlStopsFollowingAndPicksUpAgainOnReturn() {
        fill();
        int live = settings.onChanged.connectionCount();
        assertTrue(live > 0);

        root.remove(panel);
        assertEquals("a control out of the tree must follow nothing",
                0, settings.onChanged.connectionCount());

        settings.set(SettingsLayer.USER, INDENT, 11);

        root.append(panel);
        assertEquals("coming back did not re-establish the binding",
                live, settings.onChanged.connectionCount());

        ConfigControl control = panel.control(INDENT.getId());
        assertNotNull(control);
        assertEquals("it came back stale rather than re-reading the store", 11,
                ((Number) control.getValueObject()).intValue());
    }
}
