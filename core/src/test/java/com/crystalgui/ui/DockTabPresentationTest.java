package com.crystalgui.ui;

import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.CgUiSvg;
import com.crystalgui.render.texture.asset.FileIconTheme;
import com.crystalgui.render.texture.svg.SvgDocument;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.Tab;
import com.crystalgui.ui.elements.dock.DockArea;
import com.crystalgui.ui.elements.dock.DockGroup;
import com.crystalgui.ui.elements.dock.DockLayout;
import com.crystalgui.ui.elements.dock.DockLeaf;
import com.crystalgui.ui.elements.dock.DockPanelDescriptor;
import com.crystalgui.ui.elements.dock.DockPanelRef;
import com.crystalgui.ui.elements.dock.DockPanelRegistry;

import dev.vfyjxf.taffy.style.FlexDirection;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * How a tab learns what to show — the seam that replaced an owner reaching in and setting it.
 *
 * <h3>What is actually at stake</h3>
 *
 * <p>A tab's presentation has two halves that behave differently, and the bugs live in the difference.
 * The <b>icon</b> is a function of the panel ref, which is immutable, so it can be resolved once when the
 * tab is built and never revisited. The <b>title</b> is not — a document gains a dirty marker by being
 * typed into, and nothing in the ref moves — so it has to be re-read.</p>
 *
 * <p>Both are <em>pulled</em> by the strip from a provider rather than pushed in afterwards, and that is
 * the property worth pinning: a dock rearrangement rebuilds every tab element, so anything pushed has to
 * be pushed again by someone who noticed the rebuild happened. Nobody notices. A pulled tab is correct on
 * the frame it is built, whoever built it.</p>
 */
public class DockTabPresentationTest extends UiTestBase {

    private static final String FILE_TYPE = "file";
    private static final String TOOL_TYPE = "tool";

    private final DockPanelRef javaFile = new DockPanelRef(FILE_TYPE)
            .withState(DockPanelRef.TITLE, "Main.java");
    private final DockPanelRef toolWindow = new DockPanelRef(TOOL_TYPE);

    private UIWindow window;
    private DockArea area;
    private DockLeaf leaf;

    /** Stands in for the workbench: a title that can go "dirty", and an icon derived from the title. */
    private boolean dirty;

    private DockPanelRegistry<UIElement> registry() {
        DockPanelRegistry<UIElement> registry = new DockPanelRegistry<>();
        registry.register(new DockPanelDescriptor(FILE_TYPE, "File"), ref -> new UIElement());
        registry.register(new DockPanelDescriptor(TOOL_TYPE, "Tool"), ref -> new UIElement());

        registry.setTitleProvider(ref -> {
            String name = ref.state(DockPanelRef.TITLE, "");
            if (name.isEmpty()) return null;
            return dirty ? name + " *" : name;
        });
        registry.setIconProvider(ref -> {
            String name = ref.state(DockPanelRef.TITLE, "");
            return name.isEmpty() ? null : FileIconTheme.getDefault().iconFor(name, false, false);
        });
        return registry;
    }

    private void setUp(DockPanelRef... panels) {
        leaf = new DockLeaf(panels[0]);
        for (int i = 1; i < panels.length; i++) leaf.add(panels[i]);
        DockLayout layout = DockLayout.of(leaf);

        area = new DockArea(registry(), layout);
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
    }

    private Tab tabFor(DockPanelRef panel) {
        DockGroup group = area.groupFor(leaf);
        assertNotNull("no group was built for the leaf", group);
        return group.tabFor(panel);
    }

    /** <b>A tab is born with its icon.</b> Nothing pushes it in, and no frame has to pass. */
    @Test
    public void aTabIsBuiltWithItsIcon() {
        setUp(javaFile);
        Tab tab = tabFor(javaFile);
        assertNotNull("no tab was built", tab);

        UIElement slot = tab.getPreIcon();
        assertNotNull("the tab has no icon slot", slot);

        CgUiDrawable overlay = slot.getStyle().getGeneralGroup()
                .getValueSave(StylePropertyRegistry.OVERLAY);
        assertTrue("the slot holds " + overlay + ", not a vector icon", overlay instanceof CgUiSvg);

        // Identity, which is exact: SvgDocument.of caches per path, so the slot can only hold this
        // instance if it resolved this path. "Not empty" would pass on the wrong icon entirely.
        String resolved = FileIconTheme.withVariant(
                FileIconTheme.getDefault().iconFor("Main.java", false, false));
        assertSame("the tab is not showing the java icon",
                SvgDocument.of(FileIconTheme.toResourcePath(resolved)),
                ((CgUiSvg) overlay).getDocument());
    }

    /**
     * <b>A panel with no icon gets no slot</b>, rather than an empty one.
     *
     * <p>An empty slot still takes its width, so it would step that tab's label out of line with every
     * neighbour that has an icon — a misalignment that looks like a padding bug and is not one.</p>
     */
    @Test
    public void aPanelWithoutAnIconGetsNoSlot() {
        setUp(toolWindow);
        Tab tab = tabFor(toolWindow);
        assertNotNull("no tab was built", tab);
        assertNull("a panel with no icon was given an empty slot", tab.getPreIcon());
        assertEquals("Tool", tab.getText());
    }

    /**
     * <b>The title is re-read on request, in place.</b>
     *
     * <p>In place is the load-bearing half: a rebuild recreates every tab element, and a tab is a drag
     * source — so rebuilding one to change its label tears down the element the pointer is holding. The
     * assertion that the element is the SAME object is what pins that.</p>
     */
    @Test
    public void refreshingPresentationUpdatesTheTitleWithoutRebuilding() {
        setUp(javaFile);
        Tab before = tabFor(javaFile);
        assertEquals("Main.java", before.getText());

        dirty = true;
        area.refreshPanelPresentation(javaFile);
        frame();

        Tab after = tabFor(javaFile);
        assertSame("the strip was rebuilt rather than updated in place", before, after);
        assertEquals("Main.java *", after.getText());
    }

    /**
     * <b>A rebuilt strip comes back complete.</b>
     *
     * <p>This is the whole argument for pulling rather than pushing. The strip is rebuilt whenever the
     * panel set changes, and a pushed presentation would be lost — the new tab would show the registry's
     * bare descriptor title and no icon until something unrelated refreshed it.</p>
     */
    @Test
    public void aRebuiltStripStillHasIconsAndDecoratedTitles() {
        setUp(javaFile);
        dirty = true;

        // Adding a panel is what forces the rebuild; the assertions are about the ORIGINAL tab surviving
        // it with its presentation intact.
        leaf.add(toolWindow);
        area.requestRebuild();
        frame();
        frame();

        Tab rebuilt = tabFor(javaFile);
        assertNotNull("the java tab did not survive the rebuild", rebuilt);
        assertEquals("a rebuilt tab lost its dirty marker", "Main.java *", rebuilt.getText());
        assertNotNull("a rebuilt tab lost its icon", rebuilt.getPreIcon());
        assertNull("the tool window gained an icon on rebuild", tabFor(toolWindow).getPreIcon());
    }

    /** With no provider registered the dock falls back to what a ref and its descriptor say. */
    @Test
    public void withoutProvidersATabFallsBackToRefAndDescriptor() {
        DockPanelRegistry<UIElement> bare = new DockPanelRegistry<>();
        bare.register(new DockPanelDescriptor(FILE_TYPE, "File"), ref -> new UIElement());

        assertEquals("Main.java", bare.titleOf(javaFile));
        assertNull("a bare registry invented an icon", bare.iconOf(javaFile));
        assertEquals("File", bare.titleOf(new DockPanelRef(FILE_TYPE)));

        // An explicit ICON is the panel naming its own, and is read when no provider answers.
        assertEquals("crystalgui:folder",
                bare.iconOf(new DockPanelRef(FILE_TYPE).withState(DockPanelRef.ICON, "crystalgui:folder")));
    }
}
