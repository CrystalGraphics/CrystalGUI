package com.crystalgui.ui;

import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.dock.DockArea;
import com.crystalgui.ui.elements.dock.DockDropZone;
import com.crystalgui.ui.elements.dock.DockLayout;
import com.crystalgui.ui.elements.dock.DockLeaf;
import com.crystalgui.ui.elements.dock.DockPanelDescriptor;
import com.crystalgui.ui.elements.dock.DockPanelRef;
import com.crystalgui.ui.elements.dock.DockPanelRegistry;
import dev.vfyjxf.taffy.style.FlexDirection;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import static org.junit.Assert.assertTrue;

/**
 * <b>{@code DockArea.onDidChangeActivePanel} — the announcement that replaced three polls.</b>
 *
 * <p>{@code Workbench.revealActiveFile}, {@code Workbench}'s Problems rebind and
 * {@code CrystalEditor.followActiveGraph} each derived the front panel from the dock <em>every frame</em>
 * and compared it with a remembered copy. That is one missing announcement used three times.</p>
 *
 * <p>What these assert is the contract those three consumers now depend on, and it is deliberately not
 * "a later frame shows the new value" — asserting the frame is asserting the poll being deleted.</p>
 */
public class DockActivePanelEventTest extends UiTestBase {

    private UIWindow window;
    private DockArea area;
    private DockLayout layout;

    private final DockPanelRef alpha = new DockPanelRef("alpha");
    private final DockPanelRef beta = new DockPanelRef("beta");

    private final List<DockPanelRef> announced = new ArrayList<>();

    private DockPanelRegistry<UIElement> registry() {
        DockPanelRegistry<UIElement> registry = new DockPanelRegistry<>();
        for (String id : new String[]{"alpha", "beta", "gamma"}) {
            registry.register(new DockPanelDescriptor(id, id), ref -> new UIElement());
        }
        return registry;
    }

    /** Two side-by-side groups, subscribed from the moment the area exists. */
    @Before
    public void setUp() {
        DockLeaf left = new DockLeaf(alpha);
        layout = DockLayout.of(left);
        layout.drop(left, DockDropZone.SPLIT_RIGHT, new DockLeaf(beta));

        area = new DockArea(registry(), layout);
        area.onDidChangeActivePanel.connect(announced::add);

        UIElement root = new UIElement().layout(l -> l.width(600).height(400)
                .flexDirection(FlexDirection.COLUMN));
        root.addChild(area);
        area.layout(l -> l.width(600).height(400));

        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));
        window.init(1200, 800);
        frame();
        frame();   // the ticker registers on the first layout, so the rebuild lands on the second
    }

    private void frame() {
        window.updateWithoutPainting();
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
    }

    /** The build itself is a change: something became active where nothing was. */
    @Test
    public void theFirstBuildAnnouncesWhateverEndedUpInFront() {
        assertEquals(1, announced.size());
        assertEquals(area.activePanel(), announced.get(0));
    }

    /**
     * <b>Nothing fires on a settled frame.</b>
     *
     * <p>The direct inverse of the loops being deleted, and the assertion that would have caught them.
     * A signal that fires per rebuild rather than per change is the same loop wearing a callback.</p>
     */
    @Test
    public void aSettledFrameAnnouncesNothing() {
        announced.clear();
        for (int i = 0; i < 10; i++) frame();
        assertTrue("a settled dock announced " + announced, announced.isEmpty());
    }

    /** Activating the other group is a change, and is announced with the panel that is now in front. */
    @Test
    public void activatingAnotherGroupAnnouncesItsPanel() {
        announced.clear();
        area.setActiveGroup(area.groupFor(layout.leaves().get(1)));

        assertEquals(1, announced.size());
        assertEquals(beta, announced.get(0));
        assertEquals(beta, area.activePanel());
    }

    /**
     * <b>Opening a panel into the group that is already active announces it.</b>
     *
     * <p>The path that was missing, and it is the one launching with a document open takes.
     * {@code setActiveGroup} early-returns because the group did not change, and no rebuild runs because
     * adding to an existing group is a selection change rather than a structural one — so the active
     * panel moved in silence and everything downstream kept showing what was there before. The symptom
     * was an Inspector that stayed blank at startup while the document it should describe was on screen.</p>
     */
    @Test
    public void addingAPanelToTheActiveGroupAnnouncesIt() {
        DockLeaf leaf = layout.leaves().get(0);
        area.setActiveGroup(area.groupFor(leaf));
        announced.clear();

        DockPanelRef added = new DockPanelRef("gamma");
        leaf.add(added);
        area.syncGroups();

        assertEquals("opening into the active group announced nothing", 1, announced.size());
        assertEquals(added, announced.get(0));
    }

    /**
     * <b>Exactly once per change, however many paths reached it.</b>
     *
     * <p>The announce is deliberately idempotent and called from several places — a group change, a tab
     * selection, the end of a rebuild — because each of those alone gets one case wrong. The cost of that
     * is that they overlap, and this is the assertion that the overlap is silent.</p>
     */
    @Test
    public void reAnnouncingWithoutAChangeIsSilent() {
        announced.clear();
        area.setActiveGroup(area.groupFor(layout.leaves().get(1)));
        assertEquals(1, announced.size());

        area.setActiveGroup(area.groupFor(layout.leaves().get(1)));
        area.requestRebuild();
        frame();
        frame();

        assertEquals("the same panel was announced twice", 1, announced.size());
    }

    /**
     * <b>Null is a real answer, and must be announced.</b>
     *
     * <p>{@code activeGroup()} falls back to the <em>central</em> leaf rather than reporting nothing —
     * but this layout is a plain split with no central leaf, so there is nothing to fall back to and the
     * honest answer is null. Either way the announcement reports what {@code activePanel()} says and
     * never forms a second opinion about it, which is the property that matters.</p>
     *
     * <p>Swallowing the null instead would leave a listener unable to learn it happened. Consumers that
     * must keep showing the last real panel latch it themselves — {@code CrystalEditor.followed} — and
     * that latch only works if the null actually arrives.</p>
     */
    @Test
    public void clearingTheActiveGroupAnnouncesWhateverActivePanelNowSays() {
        area.setActiveGroup(area.groupFor(layout.leaves().get(1)));
        assertEquals(beta, area.activePanel());
        announced.clear();

        area.setActiveGroup(null);

        assertEquals(1, announced.size());
        assertEquals("the announcement must agree with activePanel(), whatever that is",
                area.activePanel(), announced.get(0));
        assertNull("no central leaf here, so there is nothing to fall back to", announced.get(0));
    }
}
