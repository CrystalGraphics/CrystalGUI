package com.crystalgui.ui;

import com.crystalgui.core.notify.Notification;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.dock.DockArea;
import com.crystalgui.ui.elements.dock.DockBannerBar;
import com.crystalgui.ui.elements.dock.DockBanners;
import com.crystalgui.ui.elements.dock.DockGroup;
import com.crystalgui.ui.elements.dock.DockLayout;
import com.crystalgui.ui.elements.dock.DockLeaf;
import com.crystalgui.ui.elements.dock.DockPanelDescriptor;
import com.crystalgui.ui.elements.dock.DockPanelRef;
import com.crystalgui.ui.elements.dock.DockPanelRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * <b>{@code DockBannerProvider} — IntelliJ's {@code EditorNotificationProvider}.</b>
 *
 * <p>The motivating case: {@code compiled_graph.shader} opens as an ordinary editor with
 * {@code setReadOnly(true)}, so typing in it silently does nothing — which reads as a broken editor
 * rather than as a generated file, and there was nowhere for it to say otherwise.</p>
 */
public class DockBannerTest extends UiTestBase {

    private final DockPanelRef alpha = new DockPanelRef("alpha");

    private UIWindow window;
    private DockArea area;
    private DockLeaf leaf;
    private UIElement built;

    @Before
    public void setUp() {
        DockBanners.resetForTesting();

        built = new UIElement();
        DockPanelRegistry<UIElement> registry = new DockPanelRegistry<>();
        registry.register(new DockPanelDescriptor("alpha", "Alpha"), ref -> built);

        leaf = new DockLeaf(alpha);
        area = new DockArea(registry, DockLayout.of(leaf));

        UIElement root = new UIElement().layout(l -> l.width(600).height(400));
        root.addChild(area);
        area.layout(l -> l.width(600).height(400));

        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));
        window.init(1200, 800);
    }

    @After
    public void tearDown() {
        DockBanners.resetForTesting();
    }

    private UIElement tabContent() {
        DockGroup group = area.groupFor(leaf);
        assertNotNull(group);
        assertNotNull(group.tabFor(alpha));
        return group.tabFor(alpha).content().getChildren().get(0);
    }

    private void frame() {
        window.updateWithoutPainting();
    }

    /**
     * <b>Nothing answered, nothing wrapped.</b>
     *
     * <p>The property that keeps this free for every ordinary tab in the engine. A wrapper column per
     * panel would add a flex level between a pane and its content on every tab to serve the rare one,
     * and the extra box is not free.</p>
     */
    @Test
    public void aPanelWithNoBannerIsNotWrapped() {
        frame();
        assertSame("an unbannered panel gained a wrapper", built, tabContent());
    }

    /** A provider that answers puts its strip above the panel's own content, in that order. */
    @Test
    public void aProviderPutsItsBannerAboveTheContent() {
        DockBanners.register(panel -> Notification.warning("this file is generated"));
        frame();

        UIElement wrapper = tabContent();
        assertFalse("the content should now be wrapped", wrapper == built);
        assertTrue("the banner must come first", wrapper.getChildren().get(0) instanceof DockBannerBar);
        assertSame("and the panel's own content after it", built, wrapper.getChildren().get(1));
    }

    /**
     * <b>Declining is the common answer, and must cost nothing.</b>
     *
     * <p>A provider is asked about every panel in the dock, not only the ones it cares about — so a
     * registry that wrapped on a null answer would put a box around every tab the moment any feature
     * registered a provider for one of them.</p>
     */
    @Test
    public void aProviderThatDeclinesLeavesThePanelAlone() {
        DockBanners.register(panel -> "beta".equals(panel.typeId())
                ? Notification.info("not this one") : null);
        frame();

        assertSame(built, tabContent());
    }
}
