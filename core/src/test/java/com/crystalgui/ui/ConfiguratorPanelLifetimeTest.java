package com.crystalgui.ui;

import com.crystalgui.core.settings.Setting;
import com.crystalgui.core.settings.Settings;
import com.crystalgui.core.settings.SettingsLayer;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.config.ConfigControl;
import com.crystalgui.ui.elements.config.ConfiguratorPanel;
import com.crystalgui.ui.elements.config.SettingsConfigurator;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>A bound control follows its store only while it is in a tree.</b>
 *
 * <p>The store outlives the control by a long way — a {@code Settings} lives as long as the application,
 * a {@code GraphDocument} as long as the file is open — while an inspector rebuilds its controls on every
 * click. Nothing disconnected them, so the store accumulated one dead listener per row per rebuild, each
 * holding a widget that had already left the tree. Invisible from both ends: the host subscribed, the
 * store notified, nothing failed, and the only symptom was a session that got slower the longer it ran.</p>
 *
 * <p><b>The engine does this, not the owner.</b> An earlier version had every owner release — a panel
 * replacing rows, a node being deleted, a graph clearing its nodes, a port editor being unmounted: four
 * owners sharing no supertype, each needing to remember a call whose omission is invisible. These assert
 * the behaviour with nobody calling anything.</p>
 */
public class ConfiguratorPanelLifetimeTest extends UiTestBase {

    private static final Setting<Integer> INDENT = Setting.integer("editor.indent", "Indent", 4);
    private static final Setting<Boolean> WRAP = Setting.bool("editor.wrap", "Wrap", false);

    private static final List<Setting<?>> DECLARATIONS = List.of(INDENT, WRAP);

    private UIWindow window;
    private UIElement root;
    private Settings settings;
    private ConfiguratorPanel panel;

    @Before
    public void setUp() {
        root = new UIElement();
        settings = root.settings();
        panel = new ConfiguratorPanel();
        root.addChild(panel);

        window = new UIWindow(Ui.of(root));
        window.init(800, 600);
        window.updateWithoutPainting();
    }

    private void fill() {
        SettingsConfigurator.build(panel, settings, SettingsLayer.USER, DECLARATIONS, null);
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
     * <b>And the surviving rows still follow the store.</b>
     *
     * <p>The half that stops the cheap wrong fix from passing. Disconnecting everything, or never
     * subscribing at all, satisfies the count assertion perfectly and leaves an inspector that silently
     * stops reflecting edits made anywhere else. The count says what was released; this says what was
     * not.</p>
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
     * <p>The case that makes this a binding rather than a release, and the one a release-on-detach design
     * would get wrong silently. A control legitimately leaves the tree and comes back — a tab hidden and
     * shown, a pane retargeted — and dropping the subscription without re-establishing it would leave the
     * row permanently deaf, in exactly the situations nobody writes a test for.</p>
     *
     * <p>The catch-up is the other half: the store can move while the control is away, so re-subscribing
     * without re-reading would bring back a row that is confidently wrong.</p>
     */
    @Test
    public void aDetachedControlStopsFollowingAndPicksUpAgainOnReturn() {
        fill();
        int live = settings.onChanged.connectionCount();
        assertTrue(live > 0);

        panel.removeSelf();
        assertEquals("a control out of the tree must follow nothing",
                0, settings.onChanged.connectionCount());

        // Moved while it was away — nobody was listening, by design.
        settings.set(SettingsLayer.USER, INDENT, 11);

        root.addChild(panel);
        assertEquals("coming back did not re-establish the binding",
                live, settings.onChanged.connectionCount());

        ConfigControl control = panel.control(INDENT.getId());
        assertNotNull(control);
        assertEquals("it came back stale rather than re-reading the store", 11,
                ((Number) control.getValueObject()).intValue());
    }
}
