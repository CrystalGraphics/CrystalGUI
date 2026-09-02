package com.crystalgui.workbench.dock;

import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.workbench.dock.DockArea;
import com.crystalgui.workbench.dock.drag.DockDropZone;
import com.crystalgui.workbench.dock.DockGroup;
import com.crystalgui.workbench.dock.panel.DockInput;
import com.crystalgui.workbench.dock.layout.DockLayout;
import com.crystalgui.workbench.dock.layout.DockLeaf;
import com.crystalgui.workbench.dock.panel.DockPanelDescriptor;
import com.crystalgui.workbench.dock.layout.DockPanelRef;
import com.crystalgui.workbench.dock.panel.DockPanelRegistry;
import com.crystalgui.workbench.dock.drag.DockPlacement;
import dev.vfyjxf.taffy.style.FlexDirection;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * {@link DockPlacement} and {@code DockArea.groupOf} — "where should this open", as a request.
 *
 * <p>Until placement was a value, a widget wanting to open something beside itself had to find its own
 * panel ref and walk the layout. {@code CrystalEditor.showCompiled} did exactly that, which is why
 * "open the generated shader next to its graph" was application code rather than a dock capability.</p>
 */
public class DockPlacementTest extends UiDocumentTestBase {

    private DockArea area;
    private DockLayout layout;

    private final DockPanelRef alpha = new DockPanelRef("alpha");
    private final DockPanelRef beta = new DockPanelRef("beta");

    /** Panel type → the element built for it, so a test can point at a panel's own content. */
    private final Map<String, UINode> built = new HashMap<>();

    @Before
    public void setUp() {
        DockPanelRegistry<UINode> registry = new DockPanelRegistry<>();
        for (String id : new String[]{"alpha", "beta"}) {
            registry.register(new DockPanelDescriptor(id, id), ref -> {
                UINode content = new UINode();
                built.put(ref.typeId(), content);
                return content;
            });
        }

        DockLeaf left = new DockLeaf(alpha);
        layout = DockLayout.of(left);
        layout.drop(left, DockDropZone.SPLIT_RIGHT, new DockLeaf(beta));

        area = new DockArea(registry, layout);
        UINode root = new UINode().layout(l -> l.width(600).height(400)
                .flexDirection(FlexDirection.COLUMN));
        root.append(area);
        area.layout(l -> l.width(600).height(400));

        document.append(root);
        document.styleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));
        frame();
        frame();
    }


    /**
     * <b>The "next to me" primitive.</b>
     *
     * <p>A panel's own content resolves to the group holding it, so a widget can say "beside this"
     * without knowing there is a layout at all.</p>
     */
    @Test
    public void groupOfFindsTheGroupHoldingAnElement() {
        UINode betaContent = built.get("beta");
        assertNotNull("fixture wrong -- beta's content was never built", betaContent);

        DockGroup group = area.groupOf(betaContent);
        assertNotNull("a panel's own content did not resolve to its group", group);
        assertSame(area.groupFor(layout.leaves().get(1)), group);
    }

    /** The walk uses the real parent chain, so a group resolves to itself. */
    @Test
    public void aGroupResolvesToItself() {
        DockGroup group = area.groupFor(layout.leaves().get(0));
        assertSame(group, area.groupOf(group));
    }

    /** An element outside any dock is an ordinary null, not a failure. */
    @Test
    public void anElementOutsideTheDockResolvesToNothing() {
        assertNull(area.groupOf(new UINode()));
        assertNull(area.groupOf(null));
    }

    @Test
    public void withResolvesToTheGroupHoldingThatElement() {
        DockLeaf resolved = DockPlacement.resolve(DockPlacement.with(built.get("beta")), area);
        assertSame(layout.leaves().get(1), resolved);
    }

    /** Active falls back the way {@code activeGroup()} does, so a placement asked for before any click
     * answers with the work area rather than nothing. */
    @Test
    public void activeResolvesEvenBeforeAnythingHasBeenClicked() {
        area.setActiveGroup(area.groupFor(layout.leaves().get(1)));
        assertSame(layout.leaves().get(1), DockPlacement.resolve(DockPlacement.active(), area));
    }

    @Test
    public void aNamedLeafResolvesToItself() {
        DockLeaf leaf = layout.leaves().get(0);
        assertSame(leaf, DockPlacement.resolve(DockPlacement.leaf(leaf), area));
    }

    /** An element that is not in a dock cannot name a group — the caller then makes one. */
    @Test
    public void withResolvesToNothingForAnElementOutsideTheDock() {
        assertNull(DockPlacement.resolve(DockPlacement.with(new UINode()), area));
    }

    @Test
    public void leafOfFindsWhereAnInputIsOpen() {
        assertSame(layout.leaves().get(0), area.leafOf(DockInput.of(alpha)));
        assertNull(area.leafOf(DockInput.of(new DockPanelRef("nowhere"))));
    }

    @Test
    public void centralResolvesToTheCentralLeafOrNothing() {
        assertEquals(layout.centralLeaf(), DockPlacement.resolve(DockPlacement.central(), area));
    }
}
