package com.crystalgui.workbench.dock;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.CgUiSvg;
import com.crystalgui.render.texture.asset.FileIconTheme;
import com.crystalgui.render.texture.svg.SvgDocument;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.widget.layout.Tab;
import com.crystalgui.widget.overlay.Tooltip;
import com.crystalgui.workbench.dock.layout.DockLayout;
import com.crystalgui.workbench.dock.layout.DockLeaf;
import com.crystalgui.workbench.dock.panel.DockPanelDescriptor;
import com.crystalgui.workbench.dock.layout.DockPanelRef;
import com.crystalgui.workbench.dock.panel.DockPanelRegistry;

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
public class DockTabPresentationTest extends UiDocumentTestBase {

    private static final String FILE_TYPE = "file";
    private static final String TOOL_TYPE = "tool";

    private final DockPanelRef javaFile = new DockPanelRef(FILE_TYPE)
            .withState(DockPanelRef.TITLE, "Main.java");
    private final DockPanelRef toolWindow = new DockPanelRef(TOOL_TYPE);

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
        root.append(area);
        area.layout(l -> l.width(600).height(400));

        document.append(root);
        document.styleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));
        frame();
        frame();   // the ticker registers on the first layout, so the rebuild lands on the second
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
        assertNull("the tool document gained an icon on rebuild", tabFor(toolWindow).getPreIcon());
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

    // ── The close affordance, at the DOCK level ───────────────────────────────────

    /**
     * <b>A tab whose type is closable is built with a close button.</b>
     *
     * <h3>Why this test is here and not with the widget's own</h3>
     *
     * <p>{@code TabCloseAndRevealTest} covers {@link Tab#setClosable} thoroughly — that a button
     * appears, that revoking it removes one, that pressing it does not select the tab. Every one of
     * those kept passing while <b>no dock tab had a close button at all</b>, because they drive a
     * {@code Tab} the test constructs itself. The widget never broke; the WIRING to it did.</p>
     *
     * <p>It was lost in a merge: {@code 1f9b5b3} added the block directly above the {@code contentFor}
     * line in {@code rebuildStrip}, and {@code d397b9d} resolved that hunk in favour of the other side
     * and took both. Two days, a visible feature, and a green suite — which is what a test that stops
     * one layer short of the seam buys you.</p>
     */
    @Test
    public void aDockTabCarriesACloseButton() {
        setUp(javaFile);
        Tab tab = tabFor(javaFile);
        assertNotNull("no tab was built", tab);
        assertTrue("a closable panel type produced a tab with no close button", tab.isClosable());
    }

    /**
     * <b>And a type that says it cannot be closed gets none.</b>
     *
     * <p>The negative half, without which the assertion above passes against a strip that makes every
     * tab closable — including a region's permanent host, which would then be possible to shut with no
     * way to bring it back.</p>
     */
    @Test
    public void aPanelTypeThatRefusesClosingHasNoButton() {
        DockPanelRegistry<UIElement> registry = new DockPanelRegistry<>();
        // singleton = true, closable = FALSE -- the shape a region host has.
        registry.register(new DockPanelDescriptor(TOOL_TYPE, "Tool", true, false), ref -> new UIElement());
        DockPanelRef pinned = new DockPanelRef(TOOL_TYPE);

        leaf = new DockLeaf(pinned);
        area = new DockArea(registry, DockLayout.of(leaf));
        UIElement root = new UIElement().layout(l -> l.width(600).height(400)
                                                      .flexDirection(FlexDirection.COLUMN));
        root.append(area);
        area.layout(l -> l.width(600).height(400));
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));
        frame();
        frame();

        Tab tab = area.groupFor(leaf).tabFor(pinned);
        assertNotNull("no tab was built", tab);
        assertTrue("a panel that refuses closing was given a close button", !tab.isClosable());
    }

    /**
     * <b>Pressing it closes that panel</b> — through the same {@code closePanel} the command uses.
     *
     * <p>Asserted through the signal rather than by synthesising a click, because what broke was the
     * CONNECTION: a button that appears and is wired to nothing looks identical until pressed.</p>
     */
    @Test
    public void pressingCloseRemovesThePanel() {
        setUp(javaFile, toolWindow);
        Tab tab = tabFor(javaFile);
        assertNotNull(tab);
        assertTrue(tab.isClosable());

        tab.onCloseRequested.emit();
        frame();
        frame();

        assertTrue("the close request did not reach the dock", leaf.indexOf(javaFile) < 0);
        assertTrue("it took the wrong panel with it", leaf.indexOf(toolWindow) >= 0);
    }

    // ── An icon that is an ELEMENT rather than a name ─────────────────────────────────

    /**
     * <b>A provided element becomes the tab's icon.</b>
     *
     * <p>A file's icon is one picture and a NAME resolves it. A declaration's is not: {@code static} and
     * {@code final} are layers stacked over the glyph, and a layer is an element. So the dock takes an
     * element when one is offered — and stays ignorant of what a symbol is, which is what lets the
     * language side hand over a widget the dock has never heard of.</p>
     *
     * <p>The first version of this read {@code getPreIcon()} and filled it, which answers <b>null</b> on a
     * tab that has never had one — so a library tab lost its icon completely while every project tab kept
     * theirs, because those go through the name path and its {@code setPreIcon} is what creates the slot.
     * The seam had no test at all, which is why that shipped.</p>
     */
    @Test
    public void aProvidedElementBecomesTheTabIcon() {
        UIElement glyph = new UIElement();
        glyph.addClass("__test-glyph__");

        DockPanelRegistry<UIElement> registry = registry();
        registry.setIconElementProvider(ref -> FILE_TYPE.equals(ref.typeId()) ? glyph : null);
        setUpWith(registry, javaFile);

        Tab tab = area.groupFor(leaf).tabFor(javaFile);
        assertNotNull("no tab was built", tab);
        assertSame("the provided element is not the tab's icon", glyph, tab.getPreIcon());
        // AND IT IS IN THE TREE. An icon held by the tab but never attached draws nothing, which is the
        // same symptom as having none -- so identity alone would pass against the bug this pins.
        assertNotNull("the icon was never attached", glyph.parent());
    }

    /**
     * <b>And a panel the provider declines still gets its name-based icon.</b>
     *
     * <p>The two paths coexist on purpose: a project file needs no semantics and resolves its picture
     * from {@code document/icons/default.json}, which is exactly what a file-icon theme is for. Only a
     * declaration needs the richer answer.</p>
     */
    @Test
    public void aDeclinedElementFallsBackToTheNamedIcon() {
        DockPanelRegistry<UIElement> registry = registry();
        registry.setIconElementProvider(ref -> null);
        setUpWith(registry, javaFile);

        Tab tab = area.groupFor(leaf).tabFor(javaFile);
        assertNotNull(tab);
        UIElement slot = tab.getPreIcon();
        assertNotNull("the name path was skipped when the element provider declined", slot);
        assertNotNull("the slot carries no drawable",
                slot.getStyle().getGeneralGroup().getValueSave(StylePropertyRegistry.OVERLAY));
    }

    /** Builds the area over a registry the caller has configured. */
    private void setUpWith(DockPanelRegistry<UIElement> registry, DockPanelRef... panels) {
        leaf = new DockLeaf(panels[0]);
        for (int i = 1; i < panels.length; i++) leaf.add(panels[i]);
        area = new DockArea(registry, DockLayout.of(leaf));
        UIElement root = new UIElement().layout(l -> l.width(600).height(400)
                                                      .flexDirection(FlexDirection.COLUMN));
        root.append(area);
        area.layout(l -> l.width(600).height(400));
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));
        frame();
        frame();
    }

    // ── Hover text ─────────────────────────────────────────────────────────────────

    /** The tooltip a tab was given, or null. Internal children are ordinary entries in {@code children}. */
    /**
     * A tooltip is NOT a child of its anchor any more.
     *
     * <p>The old engine parented it there as an internal child, so it inherited the anchor's colour
     * and font. It cannot: nearly every anchor worth a tooltip is a composite with a shadow root and
     * no slot, and a light child of one is never composed -- no box, no paint, and nothing reporting
     * a problem. It joins the DOCUMENT instead, which is where it ended up anyway, being promoted to
     * the top layer the moment it shows. So it is found by its ANCHOR, not by walking children.</p>
     */
    private Tooltip tooltipOn(Tab tab) {
        for (UIElement child : document.children()) {
            if (child instanceof Tooltip tip && tip.anchor() == tab) return tip;
        }
        return null;
    }

    /**
     * <b>A tab says where its file is, and its icon says what the file IS.</b>
     *
     * <p>Two answers on one control, which is what the region mechanism exists for: the icon is
     * unhittable — as every composite part is, so a press selects the tab rather than being swallowed —
     * so a second {@code Tooltip} attached to it would never receive {@code mouseenter} and could not
     * fire at all. This asserts the wiring, not the geometry; {@code TooltipTest} owns the resolution.</p>
     */
    @Test
    public void aTabSaysWhereItIsAndItsIconSaysWhatItIs() {
        DockPanelRegistry<UIElement> registry = registry();
        registry.setTooltipProvider(ref -> "/workspace/src/Main.java");
        registry.setIconTooltipProvider(ref -> FILE_TYPE.equals(ref.typeId()) ? "Final class" : null);
        setUpWith(registry, javaFile);

        Tab tab = area.groupFor(leaf).tabFor(javaFile);
        assertNotNull("no tab was built", tab);
        assertNotNull("the tab has an icon to anchor a region to", tab.getPreIcon());

        Tooltip tip = tooltipOn(tab);
        assertNotNull("the tab was given no tooltip at all", tip);
        assertEquals("the tab does not say where its file is",
                "/workspace/src/Main.java", tip.getBaseText());
        assertEquals("the icon\'s own wording was not wired as a region of the tab\'s tooltip",
                1, tip.regionCount());
    }

    /**
     * <b>...and a panel with nothing to say is given no tooltip.</b>
     *
     * <p>An empty tooltip is not a harmless one: it is a bare rounded box that opens over the control a
     * second after the pointer stops on it, saying nothing. Absent and empty look identical in the
     * provider — both are "no text" — so the distinction has to be made at the point the tooltip would
     * be built. A console, the Problems view, anything that is not about a location lands here.</p>
     */
    @Test
    public void aPanelWithNothingToSayIsGivenNoTooltip() {
        setUpWith(registry(), javaFile);

        Tab tab = area.groupFor(leaf).tabFor(javaFile);
        assertNotNull(tab);
        assertNull("a tooltip was attached with nothing in it", tooltipOn(tab));
    }
}
