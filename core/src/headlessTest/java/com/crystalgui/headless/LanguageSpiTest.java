package com.crystalgui.headless;

import com.crystalgui.fs.Resource;
import com.crystalgui.text.Change;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.lang.CompletionItem;
import com.crystalgui.text.lang.CompletionList;
import com.crystalgui.text.lang.CompletionProvider;
import com.crystalgui.text.lang.LanguageServices;
import com.crystalgui.text.lang.Resolver;
import com.crystalgui.text.lang.SemanticTokenProvider;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.SymbolModifier;
import com.crystalgui.text.lang.TypeRef;
import com.crystalgui.text.lang.Versioned;
import com.crystalgui.text.syntax.Language;
import com.crystalgui.text.syntax.LanguageRegistry;
import com.crystalgui.text.syntax.SyntaxTokenizer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The semantic-layer SPIs, exercised with no engine present — which is the point of running them here.
 *
 * <h3>Why {@code headlessTest} and not {@code test}</h3>
 *
 * <p>This source set runs with CrystalGraphics core deliberately absent, and the language module is not on
 * its classpath either. So it is the enforcement of {@code plan/lang-stack.md} §5.3: if anything in
 * {@code com.crystalgui.text.lang} ever reached an engine, a native, or a font, it would fail here with
 * {@code NoClassDefFoundError} rather than on a dedicated server months later. <b>The absence is the
 * assertion</b>, exactly as it is for GL.</p>
 */
public class LanguageSpiTest {

    // ── Versioned: the staleness spine ───────────────────────────────────────────────────────────

    @Test
    public void anAnswerKnowsWhetherItStillDescribesTheDocument() {
        Versioned<String> answer = Versioned.of(7, "int");

        assertTrue(answer.isFresh(7));
        assertFalse("one edit later, this answer is about text that has moved", answer.isFresh(8));
        assertEquals("int", answer.value());
    }

    @Test
    public void nothingHereIsDistinctFromNotFresh() {
        // The two failure modes a consumer must be able to tell apart: "I looked and there is no symbol at
        // that offset" versus "I looked at a document you have since changed". Conflating them either
        // shows a stale hover or suppresses a correct empty one.
        Versioned<SymbolInfo> nothing = Versioned.none(3);

        assertTrue("an empty answer about the CURRENT document is still current", nothing.isFresh(3));
        assertFalse(nothing.isPresent());
        assertNull(nothing.value());
    }

    // ── The colour bridge ────────────────────────────────────────────────────────────────────────

    @Test
    public void everySymbolKindNamesACapture() {
        // Colour conformance against the schemes is asserted in StyleGovernanceTest, which needs CSS and
        // therefore cannot run here. This is the half that can: no kind may answer with nothing, because a
        // semantic token with an empty name resolves to no highlight at all and the symbol silently
        // renders as body text -- the invisible-when-wrong failure this whole stack keeps producing.
        for (SymbolKind kind : SymbolKind.values()) {
            String capture = kind.captureName();
            assertNotNull(kind + " must name a capture", capture);
            assertFalse(kind + " named an empty capture", capture.isEmpty());
            assertFalse(kind + " named a capture with whitespace in it: '" + capture + "'",
                    capture.contains(" "));
        }
    }

    @Test
    public void aStaticFinalFieldIsColouredAsAConstantWhateverItsKindSays() {
        // ECJ reports a `static final` field as a FIELD with two modifiers, which is right as a fact about
        // the language and wrong as a colour -- both reference IDEs draw it as a constant. The carve-out
        // lives on SymbolInfo rather than in each engine, because every engine would need it and they
        // would not agree.
        SymbolInfo constant = new SymbolInfo("MAX", SymbolKind.FIELD, TypeRef.of("int"), null, null,
                Set.of(SymbolModifier.STATIC, SymbolModifier.FINAL), null);
        SymbolInfo plainField = new SymbolInfo("count", SymbolKind.FIELD, TypeRef.of("int"), null, null,
                Set.of(), null);
        SymbolInfo staticOnly = new SymbolInfo("cache", SymbolKind.FIELD, TypeRef.of("Map"), null, null,
                Set.of(SymbolModifier.STATIC), null);

        assertEquals(SymbolKind.CONSTANT.captureName(), constant.captureName());
        assertEquals("variable.member", plainField.captureName());
        assertEquals("static alone is not a constant -- a mutable static field is not one",
                "variable.member", staticOnly.captureName());
    }

    // ── TypeRef: why it is not a String ──────────────────────────────────────────────────────────

    @Test
    public void aTypeCarriesBothItsDisplayAndItsIdentity() {
        // The two differ for every parameterised type, and a cache keyed on the wrong one has an entry per
        // instantiation rather than per type.
        TypeRef list = TypeRef.of("List<String>", "java.util.List");

        assertEquals("List<String>", list.displayName());
        assertEquals("java.util.List", list.qualifiedName());
    }

    @Test
    public void anEngineWithABindingCanPutItThroughTheSeamIntact() {
        // The whole reason this is an interface. An engine hands out its own implementation and gets the
        // same object back at membersOf, so generic substitution survives a round trip that a String
        // cannot make.
        final class BoundType implements TypeRef {
            private final String binding = "the engine's own ITypeBinding";

            @Override
            public String displayName() {
                return "List<String>";
            }

            @Override
            public String qualifiedName() {
                return "java.util.List";
            }
        }

        BoundType original = new BoundType();
        AtomicReference<TypeRef> received = new AtomicReference<>();
        Resolver engine = new Resolver() {
            @Override
            public void resolveAt(int offset, java.util.function.Consumer<Versioned<SymbolInfo>> answer) {
                answer.accept(Versioned.of(1, SymbolInfo.of("items", SymbolKind.FIELD, original)));
            }

            @Override
            public void expectedTypeAt(int offset, java.util.function.Consumer<Versioned<TypeRef>> answer) {
                answer.accept(Versioned.none(1));
            }

            @Override
            public void membersOf(TypeRef type, int contextOffset,
                                  java.util.function.Consumer<Versioned<List<SymbolInfo>>> answer) {
                received.set(type);
                answer.accept(Versioned.of(1, List.of()));
            }
        };

        AtomicReference<SymbolInfo> resolved = new AtomicReference<>();
        engine.resolveAt(0, versioned -> resolved.set(versioned.value()));
        engine.membersOf(resolved.get().type(), 0, ignored -> { });

        assertSame("the engine must get its own type object back, not a copy of its name",
                original, received.get());
        assertEquals("the engine's own ITypeBinding", ((BoundType) received.get()).binding);
    }

    // ── Completion items: the four *Text fields ──────────────────────────────────────────────────

    @Test
    public void eachTextFieldFallsBackToTheLabel() {
        CompletionItem simple = CompletionItem.of("length", SymbolKind.FIELD);

        assertEquals("length", simple.sortKey());
        assertEquals("length", simple.filterKey());
        assertEquals("length", simple.textToInsert());
    }

    @Test
    public void aMethodMakesAllFourDiffer() {
        // The reason there are four fields rather than one. Collapse any pair and you get one of the
        // familiar bugs: typing `foo` fails to match a row showing its signature, or accepting the row
        // pastes the parameter list in as text.
        CompletionItem method = CompletionItem.builder("foo(int, String)", SymbolKind.METHOD)
                .filterText("foo")
                .sortText("foo")
                .insertText("foo(")
                .detail("void")
                .build();

        assertEquals("foo(int, String)", method.label());
        assertEquals("foo", method.filterKey());
        assertEquals("foo", method.sortKey());
        assertEquals("foo(", method.textToInsert());
    }

    @Test
    public void documentationIsAbsentUntilResolvedAndTheTwoAreDistinguishable() {
        CompletionItem unresolved = CompletionItem.of("size", SymbolKind.METHOD);
        assertTrue("null documentation means NOT FETCHED, and a consumer must be able to see that",
                unresolved.needsResolution());

        CompletionItem resolved = unresolved.withDocumentation("Returns the number of elements.");
        assertFalse(resolved.needsResolution());
        assertEquals("Returns the number of elements.", resolved.documentation());
        assertTrue("resolving must not mutate the original -- it may still be in a list somewhere",
                unresolved.needsResolution());
    }

    @Test
    public void anAutoImportTravelsWithTheItemThatNeedsIt() {
        // Both edits are applied as one CompositeEdit at accept time, which is what makes Ctrl+Z remove
        // the name AND the import it brought. Two undo steps for one keystroke is the behaviour every
        // editor gets criticised for.
        CompletionItem item = CompletionItem.builder("ArrayList", SymbolKind.CLASS)
                .textEdit(new Change(120, 124, "ArrayList"))
                .additionalTextEdits(Change.insert(14, "import java.util.ArrayList;\n"))
                .build();

        assertEquals(1, item.additionalTextEdits().size());
        assertEquals(14, item.additionalTextEdits().get(0).from());
        assertNotNull(item.textEdit());
    }

    @Test
    public void anItemBuiltFromASymbolCarriesItsDeprecation() {
        SymbolInfo deprecated = SymbolInfo.of("stop", SymbolKind.METHOD, TypeRef.of("void"))
                .withModifiers(SymbolModifier.DEPRECATED);

        assertTrue(CompletionItem.from(deprecated).deprecated());
        assertFalse(CompletionItem.from(SymbolInfo.of("start", SymbolKind.METHOD)).deprecated());
    }

    // ── isIncomplete ─────────────────────────────────────────────────────────────────────────────

    @Test
    public void aPartialListSaysSoSoTheSessionRequeriesRatherThanFiltering() {
        // A modpack classpath is tens of thousands of types, so an unimported-type list is the best few
        // hundred for what has been typed. Narrowing THAT locally drops every type ranked out of the first
        // answer -- completion that works for common names and quietly fails for the one being looked for.
        CompletionList partial = CompletionList.partial(List.of(CompletionItem.of("A", SymbolKind.CLASS)));
        CompletionList whole = CompletionList.complete(List.of(CompletionItem.of("B", SymbolKind.CLASS)));

        assertTrue(partial.incomplete());
        assertFalse(whole.incomplete());
        assertFalse("an EMPTY answer is a complete one -- the session may stay shut",
                CompletionList.EMPTY.incomplete());
    }

    // ── The NONE constants: what a language with no engine does ──────────────────────────────────

    @Test
    public void everySpiHasAnHonestEmptyImplementation() {
        assertTrue(SemanticTokenProvider.NONE.tokensIn(0, 1000).isEmpty());

        List<Object> answers = new ArrayList<>();
        Resolver.NONE.resolveAt(5, answers::add);
        Resolver.NONE.expectedTypeAt(5, answers::add);
        Resolver.NONE.membersOf(TypeRef.of("String"), 5, answers::add);
        CompletionProvider.NONE.complete(CompletionProvider.Request.explicit(5, ""), answers::add);

        assertEquals("every NONE must answer rather than hang -- a caller waiting forever is worse than "
                + "one told there is nothing", 4, answers.size());
        for (Object answer : answers) {
            assertTrue(answer instanceof Versioned);
        }
    }

    @Test
    public void aServicesImplementationMayOverrideNothingAtAll() {
        // A GLSL adapter that only publishes diagnostics is a valid LanguageServices. The defaults are why
        // it does not have to write three empty methods to say so.
        LanguageServices minimal = () -> "glsl";

        assertEquals("glsl", minimal.id());
        assertSame(SemanticTokenProvider.NONE, minimal.semanticTokens());
        assertSame(Resolver.NONE, minimal.resolver());
        assertSame(CompletionProvider.NONE, minimal.completion());
        minimal.close();
    }

    // ── The registry seam ────────────────────────────────────────────────────────────────────────

    @Test
    public void anEntryWithoutAnEngineIsTheOrdinaryCase() {
        LanguageRegistry.Entry noEngine = new LanguageRegistry.Entry(Language.JAVA, () -> SyntaxTokenizer.NONE);

        assertNull(noEngine.services());
        assertNull("no factory means no services, and that is not an error",
                noEngine.newServices(new TextBuffer("class A {}"), null));
    }

    @Test
    public void anEntryWithAnEngineBuildsOneSetPerDocument() {
        // Per DOCUMENT, not per editor and not shared: two documents must never share a compile result,
        // and the factory is what guarantees a caller cannot accidentally hand one out twice.
        LanguageRegistry.Entry withEngine =
                new LanguageRegistry.Entry(Language.JAVA, () -> SyntaxTokenizer.NONE)
                        .withServices((buffer, resource) -> new LanguageServices() {
                            @Override
                            public String id() {
                                return "java";
                            }
                        });

        LanguageServices first = withEngine.newServices(new TextBuffer("class A {}"), null);
        LanguageServices second = withEngine.newServices(new TextBuffer("class B {}"),
                Resource.of("project", "B.java"));

        assertNotNull(first);
        assertNotNull(second);
        assertFalse("each document gets its own", first == second);
        assertEquals("java", first.id());
    }

    @Test
    public void aFactoryIsToldWhichDocumentAndWhereItLives() {
        AtomicReference<TextBuffer> seenBuffer = new AtomicReference<>();
        AtomicReference<Resource> seenResource = new AtomicReference<>();
        LanguageRegistry.Entry entry = new LanguageRegistry.Entry(Language.JAVA, () -> SyntaxTokenizer.NONE)
                .withServices((buffer, resource) -> {
                    seenBuffer.set(buffer);
                    seenResource.set(resource);
                    return () -> "java";
                });

        TextBuffer buffer = new TextBuffer("class A {}");
        Resource resource = Resource.of("project", "src/A.java");
        entry.newServices(buffer, resource);

        assertSame("the engine subscribes to the buffer itself -- that is how the editor stays out of it",
                buffer, seenBuffer.get());
        assertSame(resource, seenResource.get());
    }

    @Test
    public void anUnsavedDocumentStillGetsServices() {
        // A scratch editor, a harness scene, the shader graph's emitted source. An engine that needs a
        // path returns limited services rather than refusing -- a script that has not been saved is still
        // worth colouring.
        LanguageRegistry.Entry entry = new LanguageRegistry.Entry(Language.JAVA, () -> SyntaxTokenizer.NONE)
                .withServices((buffer, resource) -> () -> resource == null ? "java-scratch" : "java");

        assertEquals("java-scratch", entry.newServices(new TextBuffer(""), null).id());
    }
}
