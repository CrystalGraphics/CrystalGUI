package com.crystalgui.widget.config.inspector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Test;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.data.DataContext;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.data.DataProvider;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.ConfiguratorPanel;
import com.crystalgui.widget.scroll.ScrollerView;

/**
 * <b>One scroller per tab, and it is the panel.</b>
 *
 * <p>There were two: a {@code ScrollerView} host between the tab and the panel, and the panel, which is
 * a {@code ScrollerView} itself. Nested scroll containers are wrong in two visible ways at once and both
 * were reported together.</p>
 *
 * <p>The inner panel was sized to its content rather than to the tab, so its horizontal bar sat just
 * below the last row instead of at the bottom of the region. And the wheel: a view that can only scroll
 * SIDEWAYS takes the plain wheel — browsers do this, and it is right — which the inner panel could,
 * having no vertical overflow of its own. Its horizontal end then chained out to the host, which
 * scrolled down. One notch, both axes.</p>
 *
 * <p>Asserted as "no scrolling ancestor" rather than by counting scrollers in the tree, because the tab
 * strip is legitimately allowed to be one.</p>
 */
public class InspectorHasOneScrollerPerTabTest extends UiDocumentTestBase {

    private static final DataKey<Subject> SUBJECT = DataKey.create("test.scrollers", Subject.class);

    private static final String TAB = "Facts";

    private static final class Subject extends UIElement implements DataProvider {
        @Override
        public Object getData(DataKey<?> key) {
            return key == SUBJECT ? this : null;
        }
    }

    private final InspectorSection section = new InspectorSection() {
        @Override
        public String tab() {
            return TAB;
        }

        @Override
        public boolean accepts(DataContext context) {
            return context.get(SUBJECT) != null;
        }

        @Override
        public void build(InspectorForm form, DataContext context) {
            form.row(ConfigDescriptor.info("a", "size"), "64.0 x 24.0");
        }
    };

    @After
    public void removeSection() {
        InspectorRegistry.remove(section);
    }

    @Test
    public void aPanelIsNotNestedInsideASecondScroller() {
        InspectorRegistry.register(section);
        Subject editor = new Subject();
        document.append(editor);
        Inspector inspector = new Inspector();
        document.append(inspector);

        inspector.inspect(editor);
        document.frame(0.016f, W, H);

        ConfiguratorPanel panel = panelIn(inspector);
        assertNotNull("the section wrote no panel to look at", panel);

        List<String> scrolling = new ArrayList<>();
        for (UIElement at = panel.parentElement(); at != null && at != inspector.parentElement();
             at = at.parentElement()) {
            if (at instanceof ScrollerView) scrolling.add(at.getClass().getSimpleName());
        }
        assertEquals("the panel scrolls inside another scroller: " + scrolling,
                List.of(), scrolling);
    }

    private static ConfiguratorPanel panelIn(UIElement from) {
        if (from instanceof ConfiguratorPanel found) return found;
        for (UIElement child : from.composedChildren()) {
            ConfiguratorPanel found = panelIn(child);
            if (found != null) return found;
        }
        return null;
    }
}
