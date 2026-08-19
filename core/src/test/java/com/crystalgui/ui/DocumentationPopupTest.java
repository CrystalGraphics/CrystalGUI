package com.crystalgui.ui;

import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.lang.DeclarationSite;
import com.crystalgui.text.lang.Signature;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.SymbolModifier;
import com.crystalgui.text.lang.TypeRef;
import com.crystalgui.text.syntax.SyntaxToken;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.editor.DocumentationPopup;
import com.crystalgui.ui.text.TextRange;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * M11 §24.1 — the Quick Documentation popup.
 *
 * <h3>What these cover</h3>
 *
 * <p>The <b>definition line</b>, which is the only band whose content is computed rather than copied, and
 * the <b>band clearing</b>, which is the failure {@code UIText}'s own note records: a highlight reassigned
 * only on the paths that have something to say leaves the previous symbol's ranges live on the paths that
 * do not — and those ranges are offsets into a string that has since been replaced, so the band lands on
 * whatever moved into those characters. Nothing throws, the popup looks right, and one word is the wrong
 * colour. Asserting the ranges is the only way to see it.</p>
 *
 * <p>Deliberately not covered: where the box lands or how big it is. That is placement and cascade, both
 * pinned elsewhere, and asserting pixels here would break on any legitimate restyle.</p>
 */
public class DocumentationPopupTest extends UiTestBase {

    private DocumentationPopup popup;
    private UIWindow window;

    @Before
    public void openAPopup() {
        popup = new DocumentationPopup();
        UIElement root = new UIElement().layout(l -> l.width(600).height(400));
        window = new UIWindow(Ui.of(root));
        // THE USER-AGENT SHEET IS NOT INSTALLED FOR YOU, and without it this class could not have caught
        // the bug below: with no rules at all, an element matches nothing whether it is attached or not,
        // so an assertion about styling passes against the broken version for the wrong reason.
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(600, 400);
        for (int i = 0; i < 2; i++) window.updateWithoutPainting();
    }

    private static SymbolInfo field(String name, String type, SymbolModifier... modifiers) {
        return new SymbolInfo(name, SymbolKind.FIELD, TypeRef.of(type), "com.example.Host", null,
                Set.of(modifiers), null);
    }

    private void show(SymbolInfo symbol) {
        popup.show(window, symbol, 10f, 10f, 14f);
        for (int i = 0; i < 2; i++) window.updateWithoutPainting();
    }

    /** {@code static final Method entryPoint} — modifiers, then type, then name, in Java's own order. */
    @Test
    public void theDefinitionLineReadsModifiersThenTypeThenName() {
        show(field("entryPoint", "Method", SymbolModifier.STATIC, SymbolModifier.FINAL));
        assertEquals("static final Method entryPoint", popup.definitionText());
    }

    /**
     * Each role's characters are banded, so the scheme's colours land on the right words.
     *
     * <p>Asserted as offsets into the rendered line rather than as colours, because the colour is the
     * stylesheet's and the <em>range</em> is this widget's only contribution to it.</p>
     */
    @Test
    public void eachRoleIsBandedOverItsOwnCharacters() {
        show(field("entryPoint", "Method", SymbolModifier.STATIC));
        String line = popup.definitionText();
        assertEquals("static Method entryPoint", line);

        assertEquals(List.of(TextRange.of(0, 6)), ranges(DocumentationPopup.HL_MODIFIER));
        assertEquals("static", line.substring(0, 6));

        assertEquals(List.of(TextRange.of(7, 13)), ranges(DocumentationPopup.HL_TYPE));
        assertEquals("Method", line.substring(7, 13));

        assertEquals(List.of(TextRange.of(14, 24)), ranges(DocumentationPopup.HL_NAME));
        assertEquals("entryPoint", line.substring(14, 24));
    }

    /** A method carries its parameter list, and the list is deliberately outside the name band. */
    @Test
    public void aMethodShowsItsParameterListOutsideTheNameBand() {
        SymbolInfo method = new SymbolInfo("arraycopy", SymbolKind.METHOD, TypeRef.of("void"),
                "java.lang.System", null, Set.of(SymbolModifier.STATIC), null,
                List.of(TypeRef.of("Object"), TypeRef.of("int")));
        show(method);

        assertEquals("static void arraycopy(Object, int)", popup.definitionText());
        assertEquals("the band must stop at the name", List.of(TextRange.of(12, 21)),
                ranges(DocumentationPopup.HL_NAME));
    }

    /**
     * <b>The band-clearing trap.</b> A symbol with no modifiers, shown after one that had them, must leave
     * no modifier band behind — the popup is reused and the ranges are offsets into a replaced string.
     */
    @Test
    public void aSymbolWithNoModifiersClearsThePreviousSymbolsBands() {
        show(field("entryPoint", "Method", SymbolModifier.STATIC, SymbolModifier.FINAL));
        assertFalse("precondition: the first symbol banded its modifiers",
                ranges(DocumentationPopup.HL_MODIFIER).isEmpty());

        show(field("x", "int"));

        assertEquals("int x", popup.definitionText());
        assertTrue("a modifier band must not survive onto a symbol that has none",
                ranges(DocumentationPopup.HL_MODIFIER).isEmpty());
        assertEquals("and the surviving bands must describe the NEW string",
                List.of(TextRange.of(0, 3)), ranges(DocumentationPopup.HL_TYPE));
        assertEquals(List.of(TextRange.of(4, 5)), ranges(DocumentationPopup.HL_NAME));
    }

    /**
     * <b>A symbol with no name renders its type and does not throw.</b>
     *
     * <p>Both engines produce one deliberately — {@code EcjSourceAnalyzer.expressionAt} and
     * {@code RhinoResolution} answer a bare {@link TypeRef} for {@code list.get(0)}, because a call is a
     * value rather than a declaration and there is nothing to point go-to-definition at. Resolution is
     * shared with completion, which is the consumer that wants exactly that, so hover receives them too.</p>
     *
     * <p>It rendered the name band unguarded, so {@code TextRange.of(7, 7)} threw
     * {@code "TextRange must be non-empty"} out of a <b>hover tick</b> — a crash from moving the mouse
     * across a chained call, on the commonest shape in Java. Asserted on the text rather than on the
     * absence of an exception alone, because the trailing separator belongs to the name: without that the
     * line is {@code "String "} and the box measures a space it has no name to justify.</p>
     */
    @Test
    public void anExpressionWithNoNameRendersItsTypeAlone() {
        show(new SymbolInfo("", SymbolKind.UNKNOWN, TypeRef.of("String"), null, null, Set.of(), null));

        assertEquals("String", popup.definitionText());
        assertTrue("there is no name, so there is no name band",
                ranges(DocumentationPopup.HL_NAME).isEmpty());
        assertEquals("and the type is still banded over its own characters",
                List.of(TextRange.of(0, 6)), ranges(DocumentationPopup.HL_TYPE));
    }

    /**
     * And the band-clearing rule holds across it: a nameless symbol shown after a named one must not keep
     * the previous name's range, which would be a colour over a string that no longer contains it.
     */
    @Test
    public void anExpressionWithNoNameDropsThePreviousSymbolsNameBand() {
        show(field("entryPoint", "Method", SymbolModifier.STATIC));
        assertFalse("fixture is pointless if nothing was banded",
                ranges(DocumentationPopup.HL_NAME).isEmpty());

        show(new SymbolInfo("", SymbolKind.UNKNOWN, TypeRef.of("String"), null, null, Set.of(), null));

        assertEquals("String", popup.definitionText());
        assertTrue("the previous symbol's name band is live over unrelated characters",
                ranges(DocumentationPopup.HL_NAME).isEmpty());
    }

    /**
     * <b>The owner band is coloured like an import line</b> — packages, the owner's own kind, and its
     * type parameters, in the same three capture names the editor uses.
     *
     * <p>It was one flat grey run, three lines above an editor colouring the identical text. The three
     * rules interact and each was wrong on its own: a capitalisation split can separate a package from a
     * type and can <em>never</em> tell an interface from a class, which is why the last segment takes the
     * engine's {@code containerKind}; and the last segment ends at the {@code <}, which left the
     * parameters marked by nothing at all.</p>
     */
    @Test
    public void theOwnerBandIsColouredLikeAnImportLine() {
        show(new SymbolInfo("sort", SymbolKind.METHOD, TypeRef.of("void"), "java.util.List<E>", null,
                Set.of(), null).withContainerKind(SymbolKind.INTERFACE));

        // "java.util.List<E>" -- java, util, List, E
        assertEquals(List.of(TextRange.of(0, 4), TextRange.of(5, 9)), ownerRanges("module"));
        assertEquals("the owner's own kind, not a capitalisation guess",
                List.of(TextRange.of(10, 14)), ownerRanges("type.interface"));
        assertTrue("an interface must not also be marked as a plain type",
                ownerRanges("type").isEmpty());
        assertEquals(List.of(TextRange.of(15, 16)), ownerRanges("type.parameter"));
    }

    /**
     * <b>The band a symbol does not need is cleared, not merely left unassigned.</b>
     *
     * <p>The last segment's band is named after the owner's <em>kind</em>, so it is
     * {@code type.interface} for one symbol and {@code type.enum} or plain {@code type} for the next.
     * Assigning only the one this symbol needs leaves the previous symbol's ranges live over a string
     * that has since been replaced — which is not a stale colour but a colour over the wrong text
     * entirely, exactly as {@code UIText}'s own note records. Hovering an enum constant after an
     * interface drew interface cyan across characters 10–14 of
     * {@code com.crystalgui.language.grammar.Main.Severity}, which is {@code lgui}.</p>
     */
    @Test
    public void anOwnerBandDropsThePreviousSymbolsKind() {
        show(new SymbolInfo("sort", SymbolKind.METHOD, TypeRef.of("void"), "java.util.List<E>", null,
                Set.of(), null).withContainerKind(SymbolKind.INTERFACE));
        assertFalse("fixture is pointless if nothing was marked",
                ownerRanges("type.interface").isEmpty());

        show(new SymbolInfo("FATAL", SymbolKind.ENUM_MEMBER, null,
                "com.crystalgui.language.grammar.Main.Severity", null, Set.of(), null)
                .withContainerKind(SymbolKind.ENUM));

        assertTrue("the previous owner's kind is still banded over unrelated characters",
                ownerRanges("type.interface").isEmpty());
        assertEquals(List.of(TextRange.of(37, 45)), ownerRanges("type.enum"));
    }

    /** The ranges registered under {@code name} on the owner band. */
    private List<TextRange> ownerRanges(String name) {
        UIText owner = (UIText) popup.querySelector("." + DocumentationPopup.OWNER_TEXT_CLASS);
        assertNotNull("no owner band", owner);
        return owner.highlights().get(name);
    }

    /**
     * A type is declared with a <b>keyword</b>, not with a type — {@code class Host}, never {@code Host}.
     *
     * <p>The keyword bands with the modifiers because that is what it is: {@code class} is part of the
     * declaration, not a reference to a type.</p>
     *
     * <h3>And the NAME is banded as a type, which this used to assert the opposite of</h3>
     *
     * <p>It expected {@code HL_NAME}, which resolves to {@code --syntax-variable} — right for a field or
     * a local and wrong for the thing being declared. A hovered class rendered its own name in the local
     * colour, three lines under an editor drawing that same word as a type. The owner band one row up had
     * already settled this and says how: it sets the <em>editor's</em> capture names rather than a pair
     * only this popup uses, so one scheme colours both.</p>
     */
    @Test
    public void aTypeIsDeclaredWithItsKeywordAndItsNameReadsAsAType() {
        show(new SymbolInfo("Host", SymbolKind.CLASS, null, "com.example", null, Set.of(), null));
        assertEquals("class Host", popup.definitionText());
        assertTrue("a declaration keyword is not a type reference",
                ranges(DocumentationPopup.HL_TYPE).isEmpty());
        assertEquals(List.of(TextRange.of(0, 5)), ranges(DocumentationPopup.HL_MODIFIER));
        assertTrue("a class name still bands as a variable, so it draws in the local colour",
                ranges(DocumentationPopup.HL_NAME).isEmpty());
        assertEquals("the name must carry the editor's own `type` capture, or no scheme reaches it",
                List.of(TextRange.of(6, 10)), ranges("type"));
    }

    /**
     * A <b>field</b>'s name still bands as {@code HL_NAME}, which is the half the change above must not
     * take with it — a variable's name is a variable, and only a declared TYPE reads as a type.
     */
    @Test
    public void aFieldsNameIsStillAVariable() {
        show(field("count", "int"));
        assertTrue("a field's name was banded as a type",
                ranges("type").stream().noneMatch(r -> r.equals(
                        TextRange.of(popup.definitionText().indexOf("count"),
                                popup.definitionText().indexOf("count") + "count".length()))));
        assertFalse("a field's name lost its banding altogether",
                ranges(DocumentationPopup.HL_NAME).isEmpty());
    }

    /**
     * <b>{@code java.lang.System} rendered its own name twice.</b>
     *
     * <p>An engine reports a class whose {@code type()} is itself, so writing modifiers-then-type-then-name
     * produced {@code final System System} — visibly wrong, and wrong in the single most likely thing
     * anybody hovers first. IntelliJ writes {@code public final class System}; ours writes the same minus
     * the visibility {@link SymbolModifier} does not carry.</p>
     */
    @Test
    public void aClassWhoseTypeIsItselfDoesNotPrintItsNameTwice() {
        show(new SymbolInfo("System", SymbolKind.CLASS, TypeRef.of("System"), "java.lang", null,
                Set.of(SymbolModifier.FINAL), null));
        assertEquals("final class System", popup.definitionText());
    }

    /**
     * The body band is hidden rather than empty when there is no documentation to show.
     *
     * <p>This used to read "which is every symbol today", and that stopped being true at M13 §25.6 —
     * the Java engine populates {@code documentation} now. The rule it pins did not change: a symbol
     * with no doc comment still gets no band, because an empty one is a gap that reads as a rendering
     * failure. The widget was always ready for this; nothing here needed editing but the sentence.</p>
     */
    @Test
    public void theBodyBandIsHiddenWithNoDocumentationAndShownWithSome() {
        show(field("x", "int"));
        assertFalse("a symbol with no documentation must not draw the band",
                popup.isBodyShown());

        show(field("x", "int").withDocumentation("Holds the thing."));
        assertTrue(popup.isBodyShown());
    }

    /**
     * A declaration site changes nothing visible now that the bottom band is gone — but it is still
     * carried, and {@code editor.goToDefinition} reads it.
     */
    @Test
    public void aSymbolWithADeclarationSiteStillShowsNormally() {
        SymbolInfo here = field("x", "int")
                .withDeclaration(DeclarationSite.here(new TextPoint(2, 0), new TextPoint(2, 1)));
        show(here);
        assertEquals(here, popup.shownSymbol());

        SymbolInfo elsewhere = field("y", "int").withDeclaration(new DeclarationSite(
                Resource.of(CgPath.parse("mymod.proj:src/Other.java")),
                new TextPoint(1, 0), new TextPoint(1, 1)));
        show(elsewhere);
        assertEquals(elsewhere, popup.shownSymbol());
    }

    /**
     * The owner band draws the <b>owner's</b> icon, never the symbol's own.
     *
     * <p>A member is declared in a type and a type is declared in a package. Drawing the symbol's kind put
     * a method glyph beside a class name, which reads as "the method {@code java.io.PrintStream}" — a
     * confident statement of the wrong thing, and the sort that is only ever noticed in a screenshot.</p>
     */
    @Test
    public void theOwnerIconIsTheOwnersKindAndNotTheSymbolsOwn() {
        show(new SymbolInfo("println", SymbolKind.METHOD, TypeRef.of("void"), "java.io.PrintStream",
                null, Set.of(), null));
        assertTrue("a method is owned by a class", ownerIconHasKind("class"));

        show(new SymbolInfo("System", SymbolKind.CLASS, null, "java.lang", null, Set.of(), null));
        assertTrue("a top-level class is owned by a package", ownerIconHasKind("package"));
    }

    /**
     * A nested type is owned by a class, and a qualified name is the only evidence there is.
     *
     * <p>Java capitalises types and not packages, so the last segment decides. It is a heuristic and is
     * documented as one — the cost of it being wrong is one glyph, never a wrong name.</p>
     */
    @Test
    public void aNestedTypeIsOwnedByAClassRatherThanAPackage() {
        show(new SymbolInfo("Entry", SymbolKind.INTERFACE, null, "java.util.Map", null, Set.of(), null));
        assertTrue(ownerIconHasKind("class"));
    }

    /** Swapped, never added — the popup is reused, so yesterday's glyph must not survive beside today's. */
    @Test
    public void theOwnerIconIsSwappedRatherThanAccumulated() {
        show(new SymbolInfo("System", SymbolKind.CLASS, null, "java.lang", null, Set.of(), null));
        show(new SymbolInfo("println", SymbolKind.METHOD, TypeRef.of("void"), "java.io.PrintStream",
                null, Set.of(), null));
        assertTrue(ownerIconHasKind("class"));
        assertFalse("the package glyph must have gone with the symbol it belonged to",
                ownerIconHasKind("package"));
    }

    private boolean ownerIconHasKind(String kind) {
        UIElement icon = popup.querySelector("." + DocumentationPopup.OWNER_ICON_CLASS);
        return icon != null && icon.hasClass("completion-kind-" + kind);
    }

    /**
     * An engine-rendered {@link Signature} <b>replaces</b> the assembled line, tokens and all.
     *
     * <p>This is the whole point of the seam carrying structure: the widget has no branch for a
     * modifier, an annotation or a parameter, and the declaration below contains all three.</p>
     */
    @Test
    public void anEngineSignatureIsDrawnWithItsOwnTokens() {
        String text = "public void println(String x)";
        show(field("println", "void").withSignature(new Signature(text, List.of(
                new SyntaxToken(0, 6, "keyword"),
                new SyntaxToken(7, 11, "type"),
                new SyntaxToken(12, 19, "function.method"),
                new SyntaxToken(20, 26, "type"),
                new SyntaxToken(27, 28, "variable.parameter")))));

        assertEquals(text, popup.definitionText());
        assertEquals(List.of(TextRange.of(0, 6)), rangesOf("keyword"));
        assertEquals("two type tokens must both survive under one name",
                List.of(TextRange.of(7, 11), TextRange.of(20, 26)), rangesOf("type"));
        assertEquals(List.of(TextRange.of(27, 28)), rangesOf("variable.parameter"));
        assertTrue("the assembled path's bands must not be left over",
                rangesOf(DocumentationPopup.HL_MODIFIER).isEmpty());
    }

    /**
     * A symbol with no signature falls back to the assembled line — and the engine path's tokens must
     * not survive onto it.
     *
     * <p>The two paths use different name sets, so a leftover would be a band under a name the new
     * string never described. That is the same failure the modifier band had, one level up.</p>
     */
    @Test
    public void fallingBackToTheAssembledLineClearsTheEnginesTokens() {
        show(field("println", "void").withSignature(new Signature("public void println()",
                List.of(new SyntaxToken(0, 6, "keyword")))));
        assertFalse(rangesOf("keyword").isEmpty());

        show(field("x", "int"));

        assertEquals("int x", popup.definitionText());
        assertTrue("the engine path's band must not survive the fallback",
                rangesOf("keyword").isEmpty());
        assertEquals(List.of(TextRange.of(0, 3)), rangesOf(DocumentationPopup.HL_TYPE));
    }

    private List<TextRange> rangesOf(String name) {
        return popup.definitionElement().highlights().get(name);
    }

    /**
     * <b>The very first show must style its lines like every later one.</b>
     *
     * <p>{@code fill()} builds the signature's line elements, and {@code invalidateStyleMatch()} early
     * returns on a <em>detached</em> element — so filling before attaching meant no selector ever matched
     * them. The first popup of a session had lines with no font size and no {@code white-space}, so the
     * box measured itself from unstyled text and the lines drew on top of each other; from the second
     * hover onwards the popup is already attached and everything is fine, which is what made it look like
     * a race rather than an ordering mistake.</p>
     *
     * <p>Asserted through the cascade rather than by pixels: a line that matched has a {@code font-size}
     * from the sheet, and one that did not has the property's initial value.</p>
     */
    @Test
    public void theFirstShowStylesItsLinesLikeEveryLater() {
        DocumentationPopup fresh = new DocumentationPopup();
        fresh.show(window, field("entryPoint", "Method", SymbolModifier.STATIC), 10f, 10f, 14f);
        for (int i = 0; i < 3; i++) window.updateWithoutPainting();

        UIText firstLine = fresh.definitionElement();
        assertNotNull("the first show should have built a line", firstLine);
        float onFirstShow = firstLine.getStyle().getGeneralGroup().fontSize();

        // AGAINST A LATER SHOW rather than against a number. The subject is "the first one is styled like
        // the rest", and pinning the literal size would make this fail every time somebody tunes the
        // sheet — which is a change to taste, not to the invariant.
        fresh.show(window, field("x", "int"), 10f, 10f, 14f);
        for (int i = 0; i < 3; i++) window.updateWithoutPainting();
        float onLaterShow = fresh.definitionElement().getStyle().getGeneralGroup().fontSize();

        assertEquals("the first line should be styled exactly as a later one",
                onLaterShow, onFirstShow, 0.01f);
        assertNotEquals("nothing matched at all — the line kept font-size's initial value",
                16f, onFirstShow, 0.01f);
    }

    /**
     * <b>The first popup of a process measured its signature at zero and stayed there.</b>
     *
     * <p>A line built during a show is measured by {@code setText} <em>before</em> its cascade has run,
     * so it sized itself against {@code font-size}'s initial value; the real size arrived from the sheet
     * moments later and invalidated the measurement, but with no {@code MeasureFunc} that re-resolves to
     * zero — and zero in, zero out is not a geometry change, so {@code onLayoutChanged} never fired and
     * nothing asked again. A deadlock, not a lag: the width stayed zero for the popup's whole life, so
     * the box sized itself to the owner row with the signature clipped.</p>
     *
     * <p>Exactly once per process, because the line elements are pooled — from the second show they have
     * been through a layout pass. That is what made it read as a warm-up problem.</p>
     */
    @Test
    public void theFirstShowMeasuresItsSignatureLikeEveryLater() {
        DocumentationPopup fresh = new DocumentationPopup();
        SymbolInfo symbol = new SymbolInfo("println", SymbolKind.METHOD, TypeRef.of("void"),
                "java.io.PrintStream", null, Set.of(), null);

        fresh.show(window, symbol, 20f, 20f, 14f);
        for (int i = 0; i < 4; i++) window.updateWithoutPainting();
        float onFirstShow = fresh.definitionElement().getRuntimeCache().getWidth();

        fresh.hide();
        for (int i = 0; i < 2; i++) window.updateWithoutPainting();
        fresh.show(window, symbol, 20f, 20f, 14f);
        for (int i = 0; i < 4; i++) window.updateWithoutPainting();
        float onLaterShow = fresh.definitionElement().getRuntimeCache().getWidth();

        assertTrue("the signature measured nothing on the first show", onFirstShow > 0f);
        assertEquals("and it should measure the same as on any later one",
                onLaterShow, onFirstShow, 0.5f);
    }

    /** Hiding forgets what was shown, so a stale symbol cannot be read back off a closed popup. */
    @Test
    public void hidingForgetsTheSymbol() {
        show(field("x", "int"));
        assertNotNull(popup.shownSymbol());
        popup.hide();
        assertNull(popup.shownSymbol());
    }

    private List<TextRange> ranges(String name) {
        return popup.definitionElement().highlights().get(name);
    }
}
