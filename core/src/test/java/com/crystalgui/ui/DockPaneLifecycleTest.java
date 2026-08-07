package com.crystalgui.ui;

import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.dock.DockArea;
import com.crystalgui.ui.elements.dock.DockGroup;
import com.crystalgui.ui.elements.dock.DockInput;
import com.crystalgui.ui.elements.dock.DockLayout;
import com.crystalgui.ui.elements.dock.DockLeaf;
import com.crystalgui.ui.elements.dock.DockPane;
import com.crystalgui.ui.elements.dock.DockPaneProvider;
import com.crystalgui.ui.elements.dock.DockPanelDescriptor;
import com.crystalgui.ui.elements.dock.DockPanelRef;
import com.crystalgui.ui.elements.dock.DockPanelRegistry;
import dev.vfyjxf.taffy.style.FlexDirection;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * <b>{@link DockPane} — one instance per (group, type), retargeted rather than rebuilt.</b>
 *
 * <p>This is what makes tab switching cheap in both references, and what an "inspector" needs: a panel
 * that follows the active document is a pane whose {@code setInput} is called again, not an application
 * swapping children into a host it owns. That swap is where {@code assertOnlyChild}, the stacked-Inspector
 * bug and the internal-child recursion trap all came from.</p>
 */
public class DockPaneLifecycleTest extends UiTestBase {

    private static final String TYPE = "doc";

    /** Records everything the framework does to it, in order. */
    private static final class Recording implements DockPane {
        final UIElement view = new UIElement();
        final List<String> events = new ArrayList<>();
        DockInput input;
        String restored = "";
        int disposals;

        @Override public UIElement view() { return view; }

        @Override public void setInput(DockInput input) {
            this.input = input;
            events.add("setInput:" + input.resource());
        }

        @Override public void clearInput() { events.add("clearInput"); }
        @Override public void onVisible() { events.add("onVisible"); }
        @Override public void onHidden() { events.add("onHidden"); }

        @Override public void writeViewState(StateMap<?> out) {
            events.add("write");
            out.putString("caret", "at:" + (input == null ? "?" : input.resource()));
        }

        @Override public void readViewState(StateMap<?> in) {
            events.add("read");
            restored = in.getString("caret", "");
        }

        @Override public void dispose() {
            disposals++;
            events.add("dispose");
        }
    }

    private final List<Recording> created = new ArrayList<>();
    private int refusedCreates;

    private UIWindow window;
    private DockArea area;
    private DockLayout layout;
    private DockLeaf leaf;

    private DockPanelRef panelFor(String file) {
        return new DockPanelRef(TYPE).withState(DockPanelRef.PATH, "mymod.proj:" + file);
    }

    private final DockPanelRef alpha = panelFor("a.txt");
    private final DockPanelRef beta = panelFor("b.txt");

    @Before
    public void setUp() {
        DockPanelRegistry<UIElement> registry = new DockPanelRegistry<>();
        registry.register(new DockPanelDescriptor(TYPE, TYPE), ref -> new UIElement());
        registry.register(new DockPanelDescriptor("plain", "plain"), ref -> new UIElement());
        registry.registerPane(new DockPaneProvider() {
            @Override public boolean accepts(DockInput input) { return TYPE.equals(input.typeId()); }
            @Override public DockPane create() {
                Recording pane = new Recording();
                created.add(pane);
                return pane;
            }
        });
        registry.registerPane(new DockPaneProvider() {
            @Override public boolean accepts(DockInput input) { return false; }
            @Override public DockPane create() {
                refusedCreates++;
                throw new AssertionError("a provider that refuses must not be asked to create");
            }
        });

        leaf = new DockLeaf(alpha);
        layout = DockLayout.of(leaf);

        area = new DockArea(registry, layout);
        UIElement root = new UIElement().layout(l -> l.width(600).height(400)
                .flexDirection(FlexDirection.COLUMN));
        root.addChild(area);
        area.layout(l -> l.width(600).height(400));

        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));
        window.init(1200, 800);
        frame();
        frame();
    }

    private void frame() {
        window.updateWithoutPainting();
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
    }

    private Recording pane() {
        assertEquals("expected exactly one pane for this type", 1, created.size());
        return created.get(0);
    }

    private void activate(DockPanelRef panel) {
        leaf.activate(panel);
        area.syncGroups();
        frame();
    }

    // ── Retargeting ─────────────────────────────────────────────────────────────────────────────

    @Test
    public void theActivePanelGetsThePaneAndItsInput() {
        Recording pane = pane();
        assertNotNull(pane.input);
        assertEquals(Resource.of(CgPath.parse("mymod.proj:a.txt")), pane.input.resource());
        assertSame("the pane's view is not in the active panel's host",
                area.groupFor(leaf), area.groupOf(pane.view));
    }

    /**
     * <b>The pane instance survives a retarget — the assertion the whole design rests on.</b>
     *
     * <p>Mutation check for it: make {@code retargetPane} build a pane per input instead of per type and
     * this fails while everything else still passes, because a fresh pane also renders correctly. That is
     * exactly why identity is asserted rather than behaviour.</p>
     */
    @Test
    public void switchingTabsReusesTheSamePaneInstance() {
        Recording first = pane();
        leaf.add(beta);
        activate(beta);

        assertEquals("a second pane was built for the same type", 1, created.size());
        assertSame(first, pane());
        assertEquals(Resource.of(CgPath.parse("mymod.proj:b.txt")), first.input.resource());
    }

    /** Re-activating what is already showing is not a retarget. */
    @Test
    public void reActivatingTheSameInputDoesNothing() {
        Recording pane = pane();
        int before = pane.events.size();

        activate(alpha);
        for (int i = 0; i < 5; i++) frame();

        assertEquals("a settled group retargeted anyway: " + pane.events,
                before, pane.events.size());
    }

    /** The view moves to the newly active panel's host, and leaves the old one. */
    @Test
    public void theViewMovesToTheActivePanelsHost() {
        Recording pane = pane();
        UIElement firstHost = pane.view.getParent();
        assertNotNull(firstHost);

        leaf.add(beta);
        activate(beta);

        UIElement secondHost = pane.view.getParent();
        assertNotNull(secondHost);
        assertFalse("the view did not move hosts", firstHost == secondHost);
        assertTrue("the old host should be empty", firstHost.getChildren().isEmpty());
    }

    // ── Ordering and view state ─────────────────────────────────────────────────────────────────

    /**
     * <b>onHidden precedes setInput precedes onVisible, and the state handoff brackets it.</b>
     *
     * <p>Ordering is the contract because a pane must be able to save what it was showing before it is
     * pointed elsewhere. Getting it backwards saves the incoming input's state over the outgoing one's,
     * which is the same class of bug as the stacked inspectors — silent, and only visible as a caret in
     * the wrong place.</p>
     */
    @Test
    public void theHandoffHappensInTheRightOrder() {
        Recording pane = pane();
        pane.events.clear();

        leaf.add(beta);
        activate(beta);

        assertEquals(List.of("write", "onHidden", "setInput:mymod.proj:b.txt", "onVisible"), pane.events);
    }

    /**
     * <b>View state lands against the right input when three tabs rotate.</b>
     *
     * <p>The framework keys it, never the pane — a pane that keyed its own would overwrite one input's
     * state with another's the first time two of them were of the same type.</p>
     */
    @Test
    public void viewStateFollowsItsOwnInputThroughARotation() {
        DockPanelRef gamma = panelFor("c.txt");
        Recording pane = pane();
        leaf.add(beta);
        leaf.add(gamma);

        activate(beta);
        activate(gamma);
        activate(alpha);

        assertEquals("alpha got back somebody else's view state",
                "at:mymod.proj:a.txt", pane.restored);

        activate(beta);
        assertEquals("beta got back somebody else's view state",
                "at:mymod.proj:b.txt", pane.restored);
    }

    // ── Provider selection and lifetime ─────────────────────────────────────────────────────────

    @Test
    public void aProviderThatRefusesIsNeverAskedToCreate() {
        assertEquals(0, refusedCreates);
    }

    /** A type with no provider still gets its factory-built content — panes are additive, not a rewrite. */
    @Test
    public void aPanelWithNoProviderStillUsesItsFactory() {
        DockPanelRef plain = new DockPanelRef("plain");
        leaf.add(plain);
        activate(plain);

        DockGroup group = area.groupFor(leaf);
        assertNotNull(group);
        assertNotNull("a factory-built panel lost its content", group.tabFor(plain));
    }

    /**
     * <b>{@code clearInput} then dispose, once, and only when the pane leaves the group.</b>
     *
     * <p>A pane whose tab merely stopped being active is still this group's and comes back the moment it
     * is selected again — disposing there would rebuild it on every tab switch, which is the cost this
     * whole mechanism exists to remove.</p>
     */
    @Test
    public void closingEveryPanelOfATypeReleasesItsPane() {
        Recording pane = pane();
        leaf.add(beta);
        activate(beta);
        assertEquals("disposed while its type still had panels", 0, pane.disposals);

        area.closePanelDiscarding(alpha);
        frame();
        frame();
        assertEquals("disposed while another panel of its type was still open", 0, pane.disposals);

        area.closePanelDiscarding(beta);
        frame();
        frame();

        assertEquals("the pane was not released when its last panel closed", 1, pane.disposals);
        assertTrue("clearInput must run before dispose",
                pane.events.indexOf("clearInput") < pane.events.indexOf("dispose"));
    }
}
