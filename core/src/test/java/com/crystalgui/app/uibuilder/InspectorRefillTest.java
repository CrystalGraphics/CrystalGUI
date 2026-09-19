package com.crystalgui.app.uibuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.crystalgui.app.uibuilder.canvas.UIBuilderView;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.config.ConfigControl;
import com.crystalgui.widget.config.Configurator;
import com.crystalgui.widget.config.ValueControl;
import com.crystalgui.widget.config.inspector.Inspector;

import dev.vfyjxf.taffy.style.FlexDirection;

/**
 * <b>The next node of the same kind costs its values, not a panel.</b>
 *
 * <p>The Inspector refills: a row the sections place again is the row already on screen, bound to the new node.
 * What reuse makes possible and this pins is a row still answering for the node before — an edit, a validator or a
 * drag handle acting on what is no longer selected.</p>
 */
public class InspectorRefillTest extends UiDocumentTestBase {

    private static final String SOURCE = "{\n"
            + "  \"cgui\": 1,\n"
            + "  \"root\": { \"kind\": \"element\", \"id\": \"root\",\n"
            + "    \"children\": [\n"
            + "      { \"kind\": \"button\", \"id\": \"ok\", \"state\": { \"text\": \"OK\" } },\n"
            + "      { \"kind\": \"button\", \"id\": \"cancel\", \"state\": { \"text\": \"Cancel\" } }\n"
            + "    ] }\n"
            + "}\n";

    private UIBuilderView editor;
    private Inspector inspector;
    private Disposable sections;
    private UIElement ok;
    private UIElement cancel;

    @Before
    public void openTheDocument() {
        UIElementRegistry.bootstrap();
        sections = BuilderInspectorSections.register();
        editor = new UIBuilderView(new UiBuilderDocument(SOURCE.getBytes(StandardCharsets.UTF_8), "test:page"));
        UIElement row = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).width(W).height(H));
        UIElement root = new UIElement().layout(l -> l.width(500).height(500));
        root.append(editor.view());
        row.append(root);
        inspector = new Inspector();
        inspector.layout(l -> l.width(300).height(H));
        row.append(inspector);
        document.append(row);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        document.update(W, H);
        frame();
        ok = editor.document().root().getElementById("ok");
        cancel = editor.document().root().getElementById("cancel");
    }

    @After
    public void release() {
        sections.dispose();
    }

    private void inspect(UIElement node) {
        editor.selection().replaceWith(List.of(node));
        inspector.inspect(editor.view());
        frame();
        frame();
    }

    private List<Configurator> rowsShown() {
        List<Configurator> rows = new ArrayList<>();
        for (UIElement each : inspector.composedSubtree()) {
            if (each instanceof Configurator row) rows.add(row);
        }
        return rows;
    }

    private Configurator rowFor(String id) {
        for (Configurator row : rowsShown()) {
            ConfigControl control = row.control();
            if (id.equals(control.descriptor().id())) return row;
        }
        throw new AssertionError("no row for " + id);
    }

    @Test
    public void theNextNodeOfTheSameKindKeepsEveryRow() {
        inspect(ok);
        List<Configurator> first = rowsShown();
        assertFalse(first.isEmpty());

        inspect(cancel);

        List<Configurator> second = rowsShown();
        assertEquals("rows were rebuilt for a node of the same kind", first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertTrue("row " + i + " was rebuilt", first.get(i) == second.get(i));
        }
        assertEquals("cancel", ((ValueControl<?>) rowFor("id").control()).getValue());
    }

    /** The id row's validator asks whether an id is free for the node it is on — which is the new one. */
    @Test
    public void aKeptRowAnswersForTheNewNode() {
        inspect(ok);
        inspect(cancel);

        ConfigControl id = rowFor("id").control();
        assertFalse("the new node may take the old one's id", id.descriptor().validator().test("ok"));
        assertTrue("the new node may not keep its own id", id.descriptor().validator().test("cancel"));

        @SuppressWarnings("unchecked")
        Property<String> value = ((ValueControl<String>) id).property();
        value.set("dismiss");
        assertEquals("dismiss", cancel.id());
        assertEquals("the edit landed on the node shown before", "ok", ok.id());
    }

    /** A drag handle a fill hangs on a row goes with that fill: one per row, however often the row is reused. */
    @Test
    public void aKeptRowCarriesOnlyThisFillsDecorations() {
        inspect(ok);
        inspector.showTab(BuilderInspectorSections.LAYOUT_TAB);
        frame();
        Configurator left = rowFor("style.left");
        int once = left.decorations().size();

        inspect(cancel);
        inspect(ok);
        inspect(cancel);

        Configurator after = rowFor("style.left");
        assertNotNull(after);
        assertTrue("the row was rebuilt", left == after);
        assertEquals("decorations accumulated across refills", once, after.decorations().size());
    }
}
