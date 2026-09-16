package com.crystalgui.app.uibuilder.style;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import dev.vfyjxf.taffy.style.FlexDirection;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgui.app.uibuilder.BuilderInspectorSections;
import com.crystalgui.app.uibuilder.canvas.BuilderEditor;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.core.async.PendingReply;
import com.crystalgui.core.async.Reply;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.property.Property;
import com.crystalgui.document.Document;
import com.crystalgui.document.DocumentKind;
import com.crystalgui.document.DocumentReference;
import com.crystalgui.document.TextDocumentModel;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.config.ConfigControl;
import com.crystalgui.widget.config.ValueControl;
import com.crystalgui.widget.config.inspector.Inspector;

/**
 * <b>L5 S.2–S.4 — the Styles tab is the sheet, through the panel.</b>
 *
 * <p>The unit tests beside this one pin the model; this one pins that the tab builds real controls over it,
 * because a section that throws while building shows an empty tab and nothing else.</p>
 */
public class StylesTabTest extends UiDocumentTestBase {

    private static final String SOURCE = """
            {
              "cgui": 1,
              "stylesheets": ["menu.css"],
              "root": { "kind": "element", "id": "root",
                "children": [ { "kind": "element", "id": "card", "class": "card" } ] }
            }
            """;

    private static final String SHEET = """
            .card {
                background-color: #112233;
                opacity: 0.5;
            }
            """;

    private BuilderEditor editor;
    private Inspector inspector;
    private Disposable sections;
    private TextBuffer sheet;
    private UIElement card;

    @Before
    public void openTheDocument() {
        UIElementRegistry.bootstrap();
        sections = BuilderInspectorSections.register();
        sheet = new TextBuffer(SHEET);
        editor = new BuilderEditor(new UiBuilderDocument(SOURCE.getBytes(StandardCharsets.UTF_8), "test:page"), null,
                new SheetDocuments(resource -> reply(reference(resource, sheet)),
                        Resource.of(CgPath.of("p", "ui/page.cgui"))));

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

        card = editor.document().root().getElementById("card");
        inspect(card);
    }

    @After
    public void release() {
        sections.dispose();
    }

    private void inspect(UIElement... nodes) {
        editor.selection().replaceWith(List.of(nodes));
        inspector.inspect(editor.view());
        frame();
        frame();
    }

    private ConfigControl control(String id) {
        for (UIElement each : inspector.composedSubtree()) {
            if (each instanceof ConfigControl found && id.equals(found.descriptor().id())) return found;
        }
        return null;
    }

    @Test
    public void theTabIsThere() {
        assertTrue("a Style tab", inspector.tabNames().contains(BuilderStyleSections.STYLE_TAB));
    }

    /**
     * <b>Inline is the target until a rule is picked, and picking one lists what that rule declares.</b>
     *
     * <p>The chips and the rows are one statement: what is shown is what the chosen target holds, which is
     * why choosing is not a filter over a single list.</p>
     */
    @Test
    public void pickingARuleListsWhatItDeclaresAndWritingLandsInTheSheet() {
        assertNull("inline declares nothing yet, so no row for it", control("style.opacity"));

        StyleTarget rule = ruleTarget();
        assertNotNull("the sheet's rule matched the card", rule);
        editor.selection().selectStyleTarget(rule.key());
        inspect(card);

        ConfigControl opacity = control("style.opacity");
        assertNotNull("the rule's own declaration has a row", opacity);
        assertEquals("reading the value the sheet holds", 0.5d, (Double) value(opacity), 1e-6);

        property(opacity).set(0.25d);
        assertTrue("and the write landed in the sheet's text", sheet.toString().contains("opacity: 0.25"));
        frame();
        assertEquals("which restyled the canvas", Float.valueOf(0.25f),
                card.getStyle().getComputed(StylePropertyRegistry.OPACITY));
    }

    private StyleTarget ruleTarget() {
        for (StyleTarget target : StyleTargets.of(card, editor.sheets()).targets()) {
            if (!target.isInline()) return target;
        }
        return null;
    }

    private static Object value(ConfigControl control) {
        return ((ValueControl<?>) control).property().get();
    }

    @SuppressWarnings("unchecked")
    private static Property<Double> property(ConfigControl control) {
        return ((ValueControl<Double>) control).property();
    }

    private static DocumentReference reference(Resource resource, TextBuffer buffer) {
        TextDocumentModel model = new TextDocumentModel(buffer);
        Document document = new Document(resource,
                DocumentKind.of("test.css", "CSS").model((r, bytes) -> model), model);
        return new DocumentReference() {
            @Override
            public Document document() {
                return document;
            }

            @Override
            public void dispose() {
            }
        };
    }

    private static <T> Reply<T> reply(T value) {
        PendingReply<T> settled = new PendingReply<>(null);
        settled.resolve(value);
        return settled;
    }
}
