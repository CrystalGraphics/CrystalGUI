package com.crystalgui.app.uibuilder.style;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.crystalgui.core.async.PendingReply;
import com.crystalgui.core.async.Reply;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.core.property.Property;
import com.crystalgui.document.Document;
import com.crystalgui.document.DocumentKind;
import com.crystalgui.document.DocumentReference;
import com.crystalgui.document.TextDocumentModel;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;

/**
 * <b>L5 S.1–S.6 — a sheet in the project is editable, and an edit is one text change at the bytes it names.</b>
 *
 * <p>The whole spine in one place because every part of it only means anything with the others: a target
 * that cannot be written to, a field with no buffer behind it, or an editor for a property the registry
 * does not know are each half a feature.</p>
 */
public class StyleEditingTest {

    private static final String SHEET = """
            /* keep me */
            .card {
                background-color: #112233;
                opacity: 0.5;
                /* padding-left: 4px; */
            }
            """;

    private UIDocument window;
    private UIElement node;
    private TextBuffer buffer;
    private SheetDocuments sheets;

    @Before
    public void openASheet() {
        // The labs are registered process-wide by the sections, so a test asserting the type-driven
        // defaults has to stand in the same world rather than in whichever one ran first.
        StyleLabs.register();
        window = new UIDocument();
        node = new UIElement();
        node.addClass("card");
        window.append(node);

        buffer = new TextBuffer(SHEET);
        sheets = new SheetDocuments(resource -> reply(reference(resource, buffer)),
                Resource.of(CgPath.of("p", "ui/menu.cgui")));
        sheets.install(window, List.of("menu.css"));
        frame();
    }

    private void frame() {
        for (int i = 0; i < 4; i++) window.frame(1f / 60f, 400, 300);
    }

    // ── S.1 ─────────────────────────────────────────────────────────────────

    @Test
    public void aProjectSheetStylesTheCanvasAndFollowsItsBuffer() {
        SheetDocuments.Sheet sheet = sheets.byId("menu.css");
        assertNotNull("the sheet opened", sheet);
        assertTrue("and it is editable, being a file rather than a jar entry", sheet.isEditable());
        assertTrue("installed on the window", window.styles().hasStylesheet(sheet.sheet(), null));
        assertEquals("the rule reached the element", Integer.valueOf(0xFF112233),
                node.getStyle().getComputed(StylePropertyRegistry.BACKGROUND_COLOR));

        // TYPED INTO, from anywhere: an editor tab on this file is this same buffer.
        buffer.load(SHEET.replace("#112233", "#445566"));
        frame();
        assertEquals("the canvas followed the text", Integer.valueOf(0xFF445566),
                node.getStyle().getComputed(StylePropertyRegistry.BACKGROUND_COLOR));
    }

    @Test
    public void aSheetTakenOffTheDocumentIsTakenOffTheWindow() {
        SheetDocuments.Sheet sheet = sheets.byId("menu.css");
        sheets.install(window, List.of());
        assertFalse(window.styles().hasStylesheet(sheet.sheet(), null));
        assertNull(sheets.byId("menu.css"));
    }

    // ── S.2 ─────────────────────────────────────────────────────────────────

    @Test
    public void theRuleThatMatchedIsATargetAndInlineIsAlwaysOne() {
        StyleTargets targets = StyleTargets.of(node, sheets);
        assertTrue("inline is first", targets.targets().get(0).isInline());

        StyleTarget rule = ruleTarget(targets);
        assertNotNull("the matched rule is a target", rule);
        assertEquals(".card", rule.label());
        assertEquals("menu.css", rule.sheetLabel());
        assertTrue("a project rule can be written to", rule.isEditable());

        assertEquals("read from the TEXT, not from the cascade", "#112233",
                rule.declaring("background-color").value());
        // A COMMENTED DECLARATION IS STILL LISTED, which is what makes the toggle reversible.
        StyleTarget.Declared off = rule.declaring("padding-left");
        assertNotNull("a commented-out declaration is listed", off);
        assertTrue(off.disabled());

        assertTrue("a key nothing carries falls back to inline", targets.chosen("rule:gone:9").isInline());
    }

    // ── S.3 ─────────────────────────────────────────────────────────────────

    @Test
    public void anEditIsOneTextChangeAndTheCommentSurvives() {
        StyleFields fields = StyleFields.on(null, ruleTarget(StyleTargets.of(node, sheets)), node);
        fields.value("opacity").set("0.25");

        assertTrue("the value changed in place", buffer.toString().contains("opacity: 0.25;"));
        assertTrue("and the comment above the rule is untouched", buffer.toString().startsWith("/* keep me */"));
        frame();
        assertEquals("the canvas restyled from the new text", Float.valueOf(0.25f),
                node.getStyle().getComputed(StylePropertyRegistry.OPACITY));
    }

    @Test
    public void writingAPropertyTheRuleLacksAddsItAndBlankTakesItOut() {
        StyleFields fields = StyleFields.on(null, ruleTarget(StyleTargets.of(node, sheets)), node);
        fields.add("color", "#FFFFFF");
        assertTrue(buffer.toString().contains("color: #FFFFFF"));

        fields = StyleFields.on(null, ruleTarget(StyleTargets.of(node, sheets)), node);
        fields.remove("color");
        assertFalse("blank removes the declaration", buffer.toString().contains("color: #FFFFFF"));
        assertTrue("and leaves the rest of the rule", buffer.toString().contains("opacity: 0.5;"));
    }

    @Test
    public void aToggledOffDeclarationIsACommentAndComesBack() {
        StyleFields fields = StyleFields.on(null, ruleTarget(StyleTargets.of(node, sheets)), node);
        assertTrue(fields.setEnabled("opacity", false));
        assertTrue("commented out", buffer.toString().contains("/* opacity: 0.5; */"));
        frame();
        // Out of the cascade entirely: with no sheet declaring it, the element has no candidate at all.
        assertNull("and out of the cascade", node.getStyle().getComputed(StylePropertyRegistry.OPACITY));

        fields = StyleFields.on(null, ruleTarget(StyleTargets.of(node, sheets)), node);
        assertTrue(fields.setEnabled("opacity", true));
        assertTrue("and back", buffer.toString().contains("opacity: 0.5;"));
        assertFalse(buffer.toString().contains("/* opacity"));
    }

    /**
     * <b>A field reads back what it just wrote, from the instance that wrote it.</b>
     *
     * <p>The row set is deliberately not rebuilt when a value changes, so one of these lives across many
     * edits. Answering from the cascade snapshot it was built with meant every edit read back the value it
     * had just replaced: the colour field sprang to the old colour, a slider rubber-banded, and a lab's
     * specimen never moved — while the element on the canvas, restyled from the real sheet, showed the new
     * value. Every test here rebuilt the fields after writing, which is how it got through.</p>
     */
    @Test
    public void aFieldReadsBackWhatItWroteWithoutBeingRebuilt() {
        StyleFields fields = StyleFields.on(null, ruleTarget(StyleTargets.of(node, sheets)), node);
        Property<String> opacity = fields.value("opacity");
        assertEquals("0.5", opacity.get());

        opacity.set("0.25");
        assertEquals("the same field answers the new value", "0.25", opacity.get());

        fields.value("color").set("#FFFFFF");
        assertEquals("a property the rule gained reads back too", "#FFFFFF", fields.valueOf("color"));

        fields.remove("color");
        assertEquals("and one taken out reads as nothing", "", fields.valueOf("color"));
    }

    /** The same, inline: the element itself is what an inline field reads. */
    @Test
    public void anInlineFieldReadsBackFromTheElement() {
        StyleFields inline = StyleFields.on(null, StyleTargets.of(node, sheets).chosen(""), node);
        assertEquals("nothing inline yet", "", inline.valueOf("color"));

        inline.value("color").set("#E8913A");
        assertEquals("the element's own value, in the spelling a sheet would need",
                "#E8913A", inline.valueOf("color"));
    }

    /** A declaration switched off still has a value, so its row does not blank out. */
    @Test
    public void aSwitchedOffDeclarationStillReadsItsValue() {
        StyleFields fields = StyleFields.on(null, ruleTarget(StyleTargets.of(node, sheets)), node);
        assertTrue(fields.setEnabled("opacity", false));
        assertEquals("0.5", fields.valueOf("opacity"));
    }

    /**
     * <b>{@code text-stroke} is one field on either target.</b>
     *
     * <p>A sheet refuses the longhands ({@code getAuthoredThrough}) and an element cannot hold the shorthand,
     * which the registry never registers. {@link StyleFields} answers both, so a lab binds one name.</p>
     */
    @Test
    public void theStrokeIsOneFieldOnEitherTarget() {
        StyleFields inline = StyleFields.on(null, StyleTargets.of(node, sheets).chosen(""), node);
        inline.value("text-stroke").set("3px #FFFFFF");
        assertEquals("the element carries the longhands", 3f,
                CssValues.number(inline.valueOf("text-stroke-width"), 0f), 1e-6);
        assertEquals("#FFFFFF", inline.valueOf("text-stroke-color"));
        assertEquals("and reads back as the shorthand", 3f,
                CssValues.number(TypographyLab.width(inline.valueOf("text-stroke")), 0f), 1e-6);
        assertEquals("#FFFFFF", TypographyLab.colour(inline.valueOf("text-stroke")));

        StyleFields rule = StyleFields.on(null, ruleTarget(StyleTargets.of(node, sheets)), node);
        rule.value("text-stroke").set("3px #FFFFFF");
        assertTrue("the sheet holds the shorthand", buffer.toString().contains("text-stroke: 3px #FFFFFF"));
    }

    @Test
    public void aReadOnlySheetRefusesTheWrite() {
        StyleTargets targets = StyleTargets.of(node, sheets);
        StyleTarget engine = new StyleTarget(null, "engine", 0, ".card", List.of());
        assertFalse(engine.isEditable());
        assertNotNull("and says why", engine.readOnlyReason());

        StyleFields fields = StyleFields.on(null, engine, node);
        fields.value("opacity").set("0.9");
        assertTrue("nothing was written", buffer.toString().contains("opacity: 0.5;"));
        assertFalse(targets.targets().isEmpty());
    }

    /**
     * <b>A property picked from the palette appears, even at its initial value.</b>
     *
     * <p>An inline write withdraws a value equal to what the element computes without it — right for a field
     * set back to its default, and wrong for an explicit add, which is exactly a property at its initial.
     * The row asked for vanished as it was created.</p>
     */
    @Test
    public void addingAPropertyAtItsInitialValueKeepsIt() {
        StyleFields inline = StyleFields.on(null, StyleTargets.of(node, sheets).chosen(""), node);
        inline.add("opacity", "1");
        frame();

        StyleTarget target = StyleTargets.of(node, sheets).chosen("");
        assertNotNull("the declaration is there", target.declaring("opacity"));
    }

    // ── S.4 / S.5 ───────────────────────────────────────────────────────────

    @Test
    public void everyRegisteredPropertyHasAFamilyAndTheSearchFindsIt() {
        for (StyleProperty<?> property : StylePropertyRegistry.all()) {
            assertNotNull(property.name, StyleFamilies.of(property));
        }
        assertEquals(StyleFamilies.Family.BORDER, StyleFamilies.of("border-top-left-radius-x"));
        assertEquals(StyleFamilies.Family.TEXT, StyleFamilies.of("font-weight"));
        assertEquals(StyleFamilies.Family.LAYOUT, StyleFamilies.of("grid-template-columns"));

        assertTrue("an alias people type finds the property",
                names(StyleFamilies.search("bg")).contains("background"));
        assertTrue("and so does a plain substring",
                names(StyleFamilies.search("radius")).contains("border-top-left-radius-x"));
    }

    // ── S.6 ─────────────────────────────────────────────────────────────────

    @Test
    public void everyPropertyAnswersAnEditor() {
        for (StyleProperty<?> property : StylePropertyRegistry.all()) {
            DeclarationEditors.Field field = DeclarationEditors.of(property, "id", property.name,
                    Property.of(""));
            assertNotNull(property.name, field);
            assertNotNull(property.name, field.descriptor());
            assertNotNull(property.name, field.value());
        }
    }

    @Test
    public void theEditorFollowsTheTypeAndRefusesWhatTheParserCannotRead() {
        assertEquals("a colour is a swatch", ConfigDescriptor.Kind.COLOR,
                DeclarationEditors.of(StylePropertyRegistry.COLOR, "c", "color", Property.of("")).descriptor().kind());
        assertEquals("a bounded number is a slider", ConfigDescriptor.Kind.NUMBER,
                DeclarationEditors.of(StylePropertyRegistry.OPACITY, "o", "opacity", Property.of("")).descriptor().kind());

        ConfigDescriptor overflow = DeclarationEditors.of(StylePropertyRegistry.OVERFLOW, "of", "overflow",
                Property.of("")).descriptor();
        assertEquals("an enum is a dropdown", ConfigDescriptor.Kind.SELECT, overflow.kind());
        assertTrue("of the CSS keywords, not the Java constants", overflow.options().contains("visible"));

        // A PROPERTY WITH NO LAB AND NO TYPE RULE is still editable, as text its own parser validates.
        ConfigDescriptor other = DeclarationEditors.of(StylePropertyRegistry.MASK, "m", "mask",
                Property.of("")).descriptor();
        assertNotNull("anything else is still editable, as validated text", other.validator());
        assertFalse("a value the property cannot parse is refused",
                DeclarationEditors.parses(StylePropertyRegistry.OPACITY, "not-a-number"));
        assertTrue(DeclarationEditors.parses(StylePropertyRegistry.OPACITY, "0.4"));
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private static List<String> names(List<StyleProperty<?>> properties) {
        return properties.stream().map(property -> property.name).toList();
    }

    private static StyleTarget ruleTarget(StyleTargets targets) {
        for (StyleTarget target : targets.targets()) {
            if (!target.isInline()) return target;
        }
        return null;
    }

    /** A document reference over a buffer, which is what the workspace hands back for a real file. */
    private static DocumentReference reference(Resource resource, TextBuffer buffer) {
        TextDocumentModel model = new TextDocumentModel(buffer);
        Document document = new Document(resource, DocumentKind.of("test.css", "CSS").model((r, bytes) -> model),
                model);
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
