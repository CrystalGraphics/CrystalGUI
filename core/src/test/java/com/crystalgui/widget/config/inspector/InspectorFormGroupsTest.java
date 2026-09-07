package com.crystalgui.widget.config.inspector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.config.ConfiguratorGroup;
import com.crystalgui.widget.config.ConfiguratorPanel;

/**
 * <b>A group owns the rows written into it.</b>
 *
 * <p>{@link InspectorForm#group} hands back a <em>new</em> form scoped to the group's content, and the
 * original keeps writing at the level it was already at. Ignoring the return value is silent: the rows
 * appear exactly where they were going to appear, as SIBLINGS of an empty group — so collapsing it hides
 * nothing and the twisty reads as dead. One of seven call sites had it wrong for that long.</p>
 */
public class InspectorFormGroupsTest {

    @Test
    public void rowsGoIntoTheGroupTheFormHandsBack() {
        ConfiguratorPanel panel = new ConfiguratorPanel();
        InspectorForm form = new InspectorForm(panel);

        InspectorForm inside = form.group("Computed", true);
        inside.row(ConfigDescriptor.info("a", "one"), "1");
        inside.row(ConfigDescriptor.info("b", "two"), "2");
        // Deliberately on the ORIGINAL form: still a sibling, which is what makes the returned form
        // meaningful rather than decorative.
        form.row(ConfigDescriptor.info("c", "outside"), "3");

        ConfiguratorGroup group = groupIn(panel);
        assertNotNull("no group was added to the panel", group);
        assertEquals("the rows were written beside the group instead of into it",
                2, group.content().children().size());
        assertTrue("a group that owns nothing hides nothing when it collapses",
                group.content().children().size() > 0);
    }

    private static ConfiguratorGroup groupIn(UIElement from) {
        if (from instanceof ConfiguratorGroup found) return found;
        for (UIElement child : from.children()) {
            ConfiguratorGroup found = groupIn(child);
            if (found != null) return found;
        }
        return null;
    }
}
