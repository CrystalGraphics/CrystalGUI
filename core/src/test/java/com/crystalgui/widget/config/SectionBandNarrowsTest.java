package com.crystalgui.widget.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.config.control.HeaderControl;

/**
 * <b>A section band narrows with its panel.</b> Bands are stretched to the panel's scroll width, so they must
 * be left out of it — counted in, a band held the width the panel last had and scrolled the form sideways.
 */
public class SectionBandNarrowsTest extends UiDocumentTestBase {

    @Test
    public void aBandFollowsThePanelDownAndAddsNoScrollWidth() {
        UIElementRegistry.bootstrap();
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        UIElement host = new UIElement();
        host.layout(l -> l.width(400).height(300));
        ConfiguratorPanel panel = new ConfiguratorPanel();
        panel.layout(l -> l.widthPercent(100f).heightPercent(100f));
        host.append(panel);
        document.append(host);
        panel.form().header("Force state");
        document.update(W, H);
        frame();

        host.layout(l -> l.width(180));
        document.update(W, H);
        frame();
        document.update(W, H);

        HeaderControl band = null;
        for (UIElement node : panel.composedSubtree()) {
            if (node instanceof HeaderControl header) band = header;
        }
        assertTrue("no band was built", band != null);
        assertEquals("the band kept the panel's old width", 180f, band.box().width(), 0.5f);
        assertTrue("the narrowed panel scrolls sideways: " + panel.box().scrollWidth(),
                panel.box().scrollWidth() <= panel.box().clientWidth() + 0.5f);
    }
}
