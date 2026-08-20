package com.crystalgui.ui;

import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.dock.DockArea;
import com.crystalgui.ui.elements.dock.DockGroup;
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * <b>A restored tab is a title until something activates it.</b>
 *
 * <h3>What this is protecting</h3>
 *
 * <p>{@code DockGroup.rebuildStrip} used to build every panel in the leaf, so restoring a session with
 * five tabs open built five editors on the frame the workbench appeared — four of them behind a
 * {@code display: none} nobody was going to look at. A document is not a cheap element:
 * {@code Workbench.registerDocumentType} builds a {@code TextEditor}, a tokenizer with its own parse
 * tree, a {@code LanguageServices} that starts a compile, and reads the file. Measured at ~480 ms for
 * <em>two</em> tabs in a real client.</p>
 *
 * <p>Asserted by <b>counting factory calls</b> rather than by asking the group what it has built. The
 * count is the thing that actually costs the time, so it cannot pass against an implementation that
 * defers a flag and builds the widget anyway — and it needs no accessor existing only for a test.</p>
 */
public class DockLazyTabTest extends UiTestBase {

    private static final String TYPE = "doc";

    /** Every panel the registry was asked to build, in order. One entry per build. */
    private final List<String> built = new ArrayList<>();

    private UIWindow window;
    private DockArea area;
    private DockLeaf leaf;

    private static DockPanelRef panelFor(String file) {
        return new DockPanelRef(TYPE).withState(DockPanelRef.PATH, "mymod.proj:" + file);
    }

    private final DockPanelRef alpha = panelFor("a.txt");
    private final DockPanelRef beta = panelFor("b.txt");
    private final DockPanelRef gamma = panelFor("c.txt");

    @Before
    public void setUp() {
        DockPanelRegistry<UIElement> registry = new DockPanelRegistry<>();
        registry.register(new DockPanelDescriptor(TYPE, TYPE), ref -> {
            built.add(ref.state(DockPanelRef.PATH, "?"));
            return new UIElement();
        });

        // THREE PANELS, ONE LEAF -- which is a restored session, and the shape the eager build was
        // paying for. alpha is the active one because DockLeaf activates what it is constructed with.
        leaf = new DockLeaf(alpha);
        leaf.add(beta);
        leaf.add(gamma);
        leaf.activate(alpha);

        area = new DockArea(registry, DockLayout.of(leaf));
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

    private DockGroup group() {
        DockGroup group = area.groupFor(leaf);
        assertNotNull("the leaf must have a group by now", group);
        return group;
    }

    /**
     * <b>Only the active panel is built</b>, however many tabs the session restored.
     *
     * <p>The count is the whole assertion: three tabs, one build. This is the line that fails if
     * {@code rebuildStrip} ever goes back to filling every content box.</p>
     */
    @Test
    public void onlyTheActivePanelIsBuilt() {
        assertEquals("three restored tabs must cost one build", 1, built.size());
        assertEquals("and it must be the ACTIVE one, not merely the first",
                "mymod.proj:a.txt", built.get(0));
    }

    /**
     * And every tab is <b>fully present</b> regardless — the deferral is the widget behind it and
     * nothing else.
     *
     * <p>Title, icon and decoration all come from the registry rather than from the content, so an
     * unmaterialised tab is drawn, draggable and closable exactly like a built one. If this ever fails,
     * laziness has leaked into something the user can see.</p>
     */
    @Test
    public void everyTabExistsEvenWhenItsContentDoesNot() {
        assertEquals(List.of(alpha, beta, gamma), group().panels());
        assertNotNull("an unbuilt panel still has its tab", group().tabFor(beta));
        assertNotNull(group().tabFor(gamma));
        assertEquals("and none of them built anything", 1, built.size());
    }

    /** Activating a panel builds it — once, at the moment it is asked for. */
    @Test
    public void activatingBuildsIt() {
        leaf.activate(beta);
        area.syncGroups();
        frame();

        assertEquals(2, built.size());
        assertEquals("mymod.proj:b.txt", built.get(1));
        assertTrue("gamma was never looked at and must still be unbuilt",
                built.stream().noneMatch(path -> path.endsWith("c.txt")));
    }

    /**
     * <b>Going back to a panel does not build it again.</b>
     *
     * <p>The point of {@code DockGroup.content} being keyed per panel: building late must still be
     * building <em>once</em>, or lazy tabs would trade a slow first frame for an editor that loses its
     * scroll position and undo stack every time you switch away from it and back.</p>
     */
    @Test
    public void comingBackReusesTheSameContent() {
        UIElement first = group().tabFor(alpha).content().getChildren().get(0);

        leaf.activate(beta);
        area.syncGroups();
        frame();
        leaf.activate(alpha);
        area.syncGroups();
        frame();

        assertEquals("two panels looked at, two builds", 2, built.size());
        assertSame("the same element must come back", first,
                group().tabFor(alpha).content().getChildren().get(0));
    }

    /**
     * <b>A tab CLICK materialises, and writes back to the leaf.</b>
     *
     * <p>The regression this exists for. Every other route to a selection change — the file tree,
     * {@code activatePanel}, {@code openFile} — calls {@code syncGroups} itself, and a tab click was the
     * one that changed the model and left the view to {@code TabView}, which only knows how to swap
     * which content box is visible. Survivable while every box was already filled; not survivable once
     * the box is filled <em>by</em> activation.</p>
     *
     * <p>Driven through {@code TabView.selectTab} because that is what emits the selection the group
     * listens to — the same signal a real press produces.</p>
     */
    @Test
    public void clickingATabBuildsItAndActivatesIt() {
        group().tabView().selectTab(group().tabFor(gamma));
        frame();

        assertEquals("the click must have built it", 2, built.size());
        assertEquals("mymod.proj:c.txt", built.get(1));
        assertEquals("and the model must have followed", gamma, leaf.activePanel());
        assertEquals("with the content actually in the tab", 1,
                group().tabFor(gamma).content().getChildren().size());
    }
}
