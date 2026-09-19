package com.crystalgui.app.uibuilder.style;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import com.crystalgui.app.uibuilder.inspect.BoxModelEditor;
import com.crystalgui.app.uibuilder.inspect.LiveEdits;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;

/**
 * <b>The inspector's gizmos write wherever the chips point.</b> The box model and the rows under it were the
 * element's own inline style whatever the Style tab had picked, so the two panels were two views of one target and
 * only one of them could reach a sheet.
 */
public class TargetedEditingTest {

    private static final String SHEET = """
            .card {
                padding-left: 4px;
            }
            """;

    private UIDocument window;
    private UIElement node;
    private TextBuffer sheet;
    private SheetDocuments sheets;

    @Before
    public void openASheet() {
        window = new UIDocument();
        node = new UIElement();
        node.addClass("card");
        window.append(node);
        sheet = new TextBuffer(SHEET);
        sheets = SheetFixture.install(window, sheet, "menu.css");
        frame();
    }

    private void frame() {
        for (int i = 0; i < 4; i++) window.frame(1f / 60f, 400, 300);
    }

    private StyleFields rule() {
        for (StyleTarget target : StyleTargets.of(node, sheets).targets()) {
            if (!target.isInline()) return StyleFields.on(null, target, node);
        }
        throw new AssertionError("the sheet's rule is a target");
    }

    /** A row bound to the target's declaration writes the sheet, and leaves the element alone. */
    @Test
    public void aRowWritesIntoTheRuleAndNotOntoTheElement() {
        StyleFields fields = rule();
        Property<String> top = fields.value(LayoutProperties.TOP);
        assertEquals("nothing there yet", "", top.get());

        top.set("12px");
        frame();

        assertTrue("the sheet's own text carries it: " + sheet, sheet.toString().contains("top: 12px"));
        assertFalse(LiveEdits.hasInline(node, LayoutProperties.TOP));
    }

    /** What the target declares is what a row marks and the diagram draws bold. */
    @Test
    public void whatTheTargetDeclaresIsWhatIsMarked() {
        StyleFields fields = rule();
        assertTrue("the rule sets this one", fields.declares(LayoutProperties.PADDING_LEFT));
        assertFalse("and not this one", fields.declares(LayoutProperties.PADDING_TOP));
    }

    @Test
    public void theBoxModelTypesIntoTheRule() {
        StyleFields fields = rule();
        BoxModelEditor box = new BoxModelEditor(node, fields);
        window.append(box);
        frame();

        box.beginEdit(box.cellFor(LayoutProperties.PADDING_LEFT));
        assertNotNull("a rule is somewhere to write", box.field());
        box.field().setText("12");
        box.commit();
        frame();

        assertTrue("the sheet's own text carries it: " + sheet, sheet.toString().contains("padding-left: 12px"));
        assertFalse(LiveEdits.hasInline(node, LayoutProperties.PADDING_LEFT));
    }

    /** A cancelled edit puts the declaration back — the promise the inline path already made. */
    @Test
    public void aCancelledEditLeavesTheRuleAsItWas() {
        StyleFields fields = rule();
        BoxModelEditor box = new BoxModelEditor(node, fields);
        window.append(box);
        frame();

        box.beginEdit(box.cellFor(LayoutProperties.PADDING_LEFT));
        box.field().setText("40");
        box.step(1, 0);
        box.cancel();
        frame();

        assertEquals("4px", fields.valueOf("padding-left"));
        assertTrue(sheet.toString().contains("padding-left: 4px"));
    }
}
