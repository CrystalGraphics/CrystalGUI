package com.crystalgui.workbench.dock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.workbench.dock.drag.DockDropZone;
import com.crystalgui.workbench.dock.layout.DockLayout;
import com.crystalgui.workbench.dock.layout.DockLeaf;
import com.crystalgui.workbench.dock.layout.DockPanelRef;
import com.crystalgui.workbench.dock.panel.DockPanelDescriptor;
import com.crystalgui.workbench.dock.panel.DockPanelRegistry;

import dev.vfyjxf.taffy.style.FlexDirection;

/**
 * <b>A panel is in the tree when it is told it is in front.</b>
 *
 * <p>{@code rebuild()} empties its content before {@code buildNode}, and {@code buildNode} syncs every
 * group — so a sync's own announce reached listeners while the whole tree was detached. Everything a
 * view resolves by walking outward is absent there, which is how activating a shader graph threw on a
 * null status bar and why the exception named the dock rather than the entry.</p>
 *
 * <p>The announce is dropped for the duration, not the change-edge: {@code rebuild()} announces again
 * after appending, and consuming the edge early made that second call a no-op.</p>
 */
public class DockAnnouncesAttachedPanelsTest extends UiDocumentTestBase {

    private final DockPanelRef alpha = new DockPanelRef("alpha");
    private final DockPanelRef beta = new DockPanelRef("beta");
    private final DockPanelRef gamma = new DockPanelRef("gamma");

    private final Map<String, UIElement> widgets = new HashMap<>();
    private final List<DockPanelRef> announced = new ArrayList<>();
    private final List<Boolean> attachedWhenAnnounced = new ArrayList<>();

    private DockArea area;
    private DockLayout layout;

    private DockPanelRegistry<UIElement> registry() {
        DockPanelRegistry<UIElement> registry = new DockPanelRegistry<>();
        for (String id : new String[]{"alpha", "beta", "gamma"}) {
            registry.register(new DockPanelDescriptor(id, id), ref -> {
                UIElement widget = new UIElement();
                widgets.put(ref.typeId(), widget);
                return widget;
            });
        }
        return registry;
    }

    @Before
    public void setUp() {
        DockLeaf left = new DockLeaf(alpha);
        layout = DockLayout.of(left);
        layout.drop(left, DockDropZone.SPLIT_RIGHT, new DockLeaf(beta));

        area = new DockArea(registry(), layout);
        area.onDidChangeActivePanel.connect(ref -> {
            announced.add(ref);
            UIElement widget = ref == null ? null : widgets.get(ref.typeId());
            attachedWhenAnnounced.add(widget != null && widget.document() != null);
        });

        UIElement root = new UIElement().layout(l -> l.width(600).height(400)
                                                      .flexDirection(FlexDirection.COLUMN));
        root.append(area);
        area.layout(l -> l.width(600).height(400));

        document.append(root);
        document.styleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));

        frame();
        frame();
    }

    /** The first build's announcement — the one the crash arrived on. */
    @Test
    public void theFirstBuildAnnouncesAPanelThatIsInTheTree() {
        assertEquals(1, announced.size());
        assertTrue("announced while detached", attachedWhenAnnounced.get(0));
    }

    /**
     * <b>A panel added to the already-active group.</b>
     *
     * <p>The path the crash arrived on, and the one {@code DockGroup.sync}'s own announce exists for:
     * {@code setActiveGroup} early-returns because the group did not change, so the move to the front is
     * only discovered inside {@code sync} — which runs from {@code buildNode}, with the tree detached.</p>
     */
    @Test
    public void aPanelAddedToTheActiveGroupIsAnnouncedOnceItIsInTheTree() {
        announced.clear();
        attachedWhenAnnounced.clear();

        layout.leaves().get(0).add(gamma).activate(gamma);
        area.requestRebuild();
        frame();
        frame();

        assertEquals(gamma, announced.get(announced.size() - 1));
        assertFalse("announced while detached",
                attachedWhenAnnounced.contains(Boolean.FALSE));
    }

    /**
     * <b>The edge survives the suppression.</b>
     *
     * <p>Returning before the equality check is what makes this true — an early {@code announcedPanel}
     * write would have left {@code rebuild()}'s own trailing announce with nothing to say.</p>
     */
    @Test
    public void suppressingDuringBuildDoesNotSwallowTheChange() {
        int before = announced.size();

        layout.leaves().get(0).add(gamma).activate(gamma);
        area.requestRebuild();
        frame();
        frame();

        assertTrue("the change was announced, only later", announced.size() > before);
        assertEquals(gamma, announced.get(announced.size() - 1));
    }
}
