package com.crystalgui.app.uibuilder.style;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
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
import com.crystalgui.app.uibuilder.inspect.LiveEdits;
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
        // THE TAB IN FRONT, as a person looking at it has it: rows on a hidden page are not polled.
        inspector.showTab(BuilderStyleSections.STYLE_TAB);
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

    /**
     * <b>A property added from the palette appears as a row.</b>
     *
     * <p>Writing it is half the job: the set of rows is the form's SUBJECT, so a form that is not rebuilt
     * shows the same rows it had — which is what "clicking a property does nothing" looked like.</p>
     */
    @Test
    public void addingADeclarationPutsARowInTheForm() {
        assertNull("nothing declares opacity yet", control("style.opacity"));

        StyleFields fields = StyleFields.on(editor.document(), StyleTargets.of(card, editor.sheets()).chosen(""),
                card);
        fields.add("opacity", "1");
        // NO REFRESH OF OUR OWN: this is the path a palette pick takes, and the panel has to notice by
        // itself. It did not -- the edit reached the document and the rows were the ones built before it.
        frame();
        frame();

        assertNotNull("the row is in the panel", control("style.opacity"));

        fields = StyleFields.on(editor.document(), StyleTargets.of(card, editor.sheets()).chosen(""), card);
        fields.remove("opacity");
        frame();
        frame();
        assertNull("and removing it takes the row away", control("style.opacity"));
    }

    /**
     * <b>Editing a value or adding a declaration keeps every row that was already there.</b>
     *
     * <p>The list used to rebuild itself from a count of the sheet's punctuation, so an add replaced the row
     * under the pointer, and a value holding a {@code *} or a {@code ;} rebuilt the row it was typed into.</p>
     */
    @Test
    public void rowsAreKeptAcrossEditsAndAdds() {
        editor.selection().selectStyleTarget(ruleTarget().key());
        inspect(card);
        ConfigControl opacity = control("style.opacity");
        assertNotNull(opacity);

        property(opacity).set(0.3d);
        frame();
        assertSame("a value edit keeps the row", opacity, control("style.opacity"));

        StyleFields.on(null, ruleTarget(), card).add("color", "#FFFFFF");
        frame();
        assertNotNull("an add puts one row in", control("style.color"));
        assertSame("and leaves the others alone", opacity, control("style.opacity"));
    }

    /** A rule's declaration that something stronger beats is drawn struck through, and follows it. */
    @Test
    public void aDeclarationThatLosesIsMarkedWhileItLoses() {
        editor.selection().selectStyleTarget(ruleTarget().key());
        inspect(card);
        UIElement row = control("style.opacity").parentElement().parentElement();
        assertFalse(row.hasClass(BuilderStyleSections.OVERRIDDEN_CLASS));

        LiveEdits.setInline(card, StylePropertyRegistry.OPACITY, "0.9");
        frame();
        assertTrue("inline beats the rule", row.hasClass(BuilderStyleSections.OVERRIDDEN_CLASS));

        LiveEdits.clearInline(card, StylePropertyRegistry.OPACITY);
        frame();
        assertFalse("and the rule wins again", row.hasClass(BuilderStyleSections.OVERRIDDEN_CLASS));
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
