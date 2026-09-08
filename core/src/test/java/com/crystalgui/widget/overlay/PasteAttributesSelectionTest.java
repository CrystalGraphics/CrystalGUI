package com.crystalgui.widget.overlay;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.crystalgui.core.attribute.AttributeSet;
import com.crystalgui.core.attribute.AttributeSlot;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.Checkbox;

/**
 * <b>The window opens asking a question, not proposing an answer.</b>
 *
 * <p>Every box started ticked, which is "paste all of it unless you object" — the whole copied set lands
 * on the target the moment somebody presses the default button, and every property they did not want has
 * to be found and cleared first. Nothing ticked asks what the window is for: which of these.</p>
 *
 * <p>That inversion creates a state the old default could not reach — nothing chosen — so Apply follows
 * the ticks. A paste of nothing closes the window and changes the target not at all, and a button that
 * silently does nothing is worse than one that says it cannot.</p>
 */
public class PasteAttributesSelectionTest extends UiDocumentTestBase {

    private UIElement host;

    @Before
    public void aHost() {
        UIElementRegistry.bootstrap();
        withDefaultStyles();
        host = new UIElement().layout(l -> l.width(600f).height(400f));
        document.append(host);
        document.update(W, H);
    }

    private static AttributeSet copied() {
        AttributeSet.Builder builder = AttributeSet.builder("crystalgui:style", "#src");
        for (String name : List.of("width", "height", "opacity")) {
            builder.add(new AttributeSlot(name, "Layout", name), "v");
        }
        return builder.build();
    }

    private Dialog open() {
        Dialog dialog = PasteAttributesDialog.open(host, copied(), "#dst", set -> { });
        assertNotNull(dialog);
        frame();
        frame();
        return dialog;
    }

    private static List<Checkbox> ticksOf(Dialog dialog, String className) {
        List<Checkbox> found = new ArrayList<>();
        for (UINode node : dialog.composedSubtree()) {
            if (node instanceof Checkbox box && box.hasClass(className)) found.add(box);
        }
        return found;
    }

    private static Button applyOf(Dialog dialog) {
        for (UINode node : dialog.composedSubtree()) {
            if (node instanceof UIElement element
                    && element.hasClass(PasteAttributesDialog.BUTTONS_CLASS)) {
                for (UINode child : element.children()) {
                    if (child instanceof Button button) return button;   // Apply, then Cancel
                }
            }
        }
        return null;
    }

    /** Neither the properties nor the group ticks that stand for them. */
    @Test
    public void nothingIsTickedWhenTheWindowOpens() {
        Dialog dialog = open();

        List<Checkbox> items = ticksOf(dialog, PasteAttributesDialog.ITEM_CLASS);
        assertFalse("no items at all, so this proves nothing", items.isEmpty());
        for (Checkbox item : items) {
            assertFalse(item.getLabel() + " opened ticked", item.isChecked());
        }
        for (Checkbox group : ticksOf(dialog, PasteAttributesDialog.GROUP_CLASS)) {
            assertFalse(group.getLabel() + " opened ticked", group.isChecked());
        }
    }

    /** And Apply has nothing to do until something is chosen — in both directions. */
    @Test
    public void applyFollowsTheTicks() {
        Dialog dialog = open();
        Button apply = applyOf(dialog);
        assertNotNull("no Apply button found", apply);
        assertFalse("nothing is chosen, so there is nothing to apply", apply.isEnabled());

        Checkbox first = ticksOf(dialog, PasteAttributesDialog.ITEM_CLASS).get(0);
        first.setChecked(true);
        frame();
        assertTrue("one property is enough to have something to do", apply.isEnabled());

        first.setChecked(false);
        frame();
        assertFalse("and taking it back leaves nothing again", apply.isEnabled());
    }

    /** A group's tick reaches Apply too, which it does through its members rather than directly. */
    @Test
    public void aGroupTickIsEnoughToWakeApply() {
        Dialog dialog = open();
        Button apply = applyOf(dialog);
        assertNotNull(apply);

        ticksOf(dialog, PasteAttributesDialog.GROUP_CLASS).get(0).setChecked(true);
        frame();
        assertTrue("ticking a whole group chooses its properties", apply.isEnabled());
    }
}
