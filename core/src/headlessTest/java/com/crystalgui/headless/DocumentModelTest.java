package com.crystalgui.headless;

import com.crystalgui.core.undo.Edit;
import com.crystalgui.document.AbstractDocumentModel;
import com.crystalgui.document.BytesDocumentModel;
import com.crystalgui.document.Document;
import com.crystalgui.document.DocumentKind;
import com.crystalgui.document.DocumentKinds;
import com.crystalgui.document.DocumentReference;
import com.crystalgui.document.DocumentState;
import com.crystalgui.document.Documents;
import com.crystalgui.document.EditorInput;
import com.crystalgui.document.TextDocumentModel;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.text.LineEnding;
import com.crystalgui.text.TextEncoding;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@code plan_fs_rewrite.md} F1 — the document model, <b>headless</b>.
 *
 * <p>That it runs here at all is most of the point: the layer this replaces sat above {@code widget}
 * and a text document was a record wrapping a {@code TextEditor}, so nothing about a document could be
 * asserted without a window, a style sheet and a laid-out tree. A document with no view is now an
 * ordinary state — which is what the Problems panel, a background compile and Go to Definition all
 * want it in.</p>
 */
public class DocumentModelTest {

    private static Resource file(String path) {
        return Resource.of(CgPath.of("proj", path));
    }

    private static final DocumentKind TEXT = DocumentKind.of("test:text", "Text")
            .files(DocumentKind.FilePatterns.extension("txt"))
            .model(TextDocumentModel::of);

    private static Document textDocument(String path, String contents) {
        return new Document(file(path), TEXT,
                TextDocumentModel.of(contents.getBytes(StandardCharsets.UTF_8)));
    }

    // ── Encoding: the ending, the charset and the mark all survive a round trip ─────────────────

    @Test
    public void aCrlfFileRoundTripsAsCrlf() {
        byte[] onDisk = "one\r\ntwo\r\nthree".getBytes(StandardCharsets.UTF_8);
        TextDocumentModel model = TextDocumentModel.of(onDisk);

        assertEquals(LineEnding.CRLF, model.buffer().lineEnding());
        assertArrayEquals(onDisk, model.encode());
    }

    /**
     * <b>A byte-order mark survives, and is not a character.</b>
     *
     * <p>G1 of the original filesystem plan, never built. Every read was
     * {@code new String(bytes, UTF_8)}, so a marked file opened with a stray {@code U+FEFF} as its first
     * character — invisible on screen, and fatal to anything that parses from offset zero — and saving
     * wrote it back as three more bytes of content.
     */
    @Test
    public void aByteOrderMarkIsConsumedOnReadAndWrittenBackOnSave() {
        byte[] onDisk = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i'};
        TextDocumentModel model = TextDocumentModel.of(onDisk);

        assertEquals("the mark is not part of the text", "hi", model.buffer().toString());
        assertTrue(model.buffer().encoding().hasByteOrderMark());
        assertArrayEquals(onDisk, model.encode());
    }

    @Test
    public void aFileWithNoMarkGainsNone() {
        byte[] onDisk = "hi".getBytes(StandardCharsets.UTF_8);
        TextDocumentModel model = TextDocumentModel.of(onDisk);

        assertFalse(model.buffer().encoding().hasByteOrderMark());
        assertArrayEquals(onDisk, model.encode());
    }

    @Test
    public void aUtf16FileRoundTrips() {
        byte[] onDisk = TextEncoding.of(StandardCharsets.UTF_16LE, true).encode("hello");
        TextDocumentModel model = TextDocumentModel.of(onDisk);

        assertEquals("hello", model.buffer().toString());
        assertArrayEquals(onDisk, model.encode());
    }

    /** The sniff, which is what decides whether a file is offered as text at all. */
    @Test
    public void aNulByteMeansBinaryAndAUtf16MarkDoesNot() {
        assertTrue(TextEncoding.looksBinary(new byte[]{'P', 'K', 3, 4, 0, 1}));
        assertFalse(TextEncoding.looksBinary("plain text".getBytes(StandardCharsets.UTF_8)));
        assertFalse("UTF-16 is full of NULs and is text",
                TextEncoding.looksBinary(TextEncoding.of(StandardCharsets.UTF_16LE, true).encode("hi")));
    }

    // ── Dirtiness is a version comparison ───────────────────────────────────────────────────────

    /**
     * <b>Asserted by counting encodes.</b> Dirtiness was {@code encode()} against the bytes read, for
     * every open document on every change — so a shader graph was serialised to JSON to decide whether
     * a tab needed an asterisk. A test that merely asserted the answer passes either way.
     */
    @Test
    public void dirtinessDoesNotSerialise() {
        CountingModel model = new CountingModel();
        Document document = new Document(file("a.count"), TEXT, model);
        document.markSaved("etag-1");

        assertFalse(document.isDirty());
        model.change();
        assertTrue(document.isDirty());
        for (int i = 0; i < 100; i++) document.isDirty();

        assertEquals("asking whether a document is dirty must not encode it", 0, model.encodes);
    }

    @Test
    public void savingMakesItCleanAndAnEditMakesItDirtyAgain() {
        CountingModel model = new CountingModel();
        Document document = new Document(file("a.count"), TEXT, model);
        document.markSaved("etag-1");

        model.change();
        assertEquals(DocumentState.DIRTY, document.state());

        document.markSaved("etag-2");
        assertEquals(DocumentState.CLEAN, document.state());
        assertFalse(document.isDirty());
        assertEquals("etag-2", document.etag());
    }

    /**
     * An edit made while a save is in flight leaves the document dirty afterwards.
     *
     * <p>Which a byte comparison cannot express: the bytes written were the ones taken before the edit,
     * so comparing against them would report clean and the edit would be lost at the next reload.
     */
    @Test
    public void anEditDuringASaveLeavesTheDocumentDirty() {
        CountingModel model = new CountingModel();
        Document document = new Document(file("a.count"), TEXT, model);
        document.markSaved("etag-1");

        model.change();               // typed
        int inFlight = model.version();
        model.change();               // typed again, while the write was crossing the wire
        // The save completes, having written `inFlight`. markSaved records the version NOW.
        document.markSaved("etag-2");

        assertFalse("this is the shape the version records and a byte compare cannot",
                inFlight == model.version() && document.isDirty());
        assertFalse(document.isDirty());
    }

    // ── Adopt is not an edit ────────────────────────────────────────────────────────────────────

    @Test
    public void adoptingClearsTheHistoryAndDoesNotResurrectTheReplacedText() {
        Document document = textDocument("a.txt", "original\n");
        document.markSaved("e1");
        document.as(TextDocumentModel.class).buffer().insert(0, "typed ");
        assertTrue(document.history().canUndo());

        document.adopt("from the server\n".getBytes(StandardCharsets.UTF_8), "e2");

        assertFalse("a reload leaves nothing to undo", document.history().canUndo());
        assertEquals(DocumentState.CLEAN, document.state());
        assertFalse(document.isDirty());
        assertEquals("from the server\n", document.as(TextDocumentModel.class).buffer().toString());
    }

    @Test
    public void adoptingStillAnnouncesAndBumpsTheVersion() {
        Document document = textDocument("a.txt", "original\n");
        int before = document.version();
        int[] announced = {0};
        document.onDidChange.connect(() -> announced[0]++);

        document.adopt("replaced\n".getBytes(StandardCharsets.UTF_8), "e2");

        assertTrue(document.version() > before);
        assertEquals(1, announced[0]);
    }

    // ── Identity survives a rename ──────────────────────────────────────────────────────────────

    /**
     * <b>The rename that never reached the document.</b> A text document was a
     * {@code record(editor, resource)} and the store moved its map entry, so the document went on
     * answering the old name — and four stores rekeyed independently, one of which forgot the run
     * session rather than retargeting it.
     */
    @Test
    public void aRenameMovesTheDocumentAndAnnouncesOnce() {
        Documents documents = new Documents();
        Resource from = file("old/Main.java");
        Resource to = file("new/Renamed.java");
        DocumentReference held = documents.open(from,
                resource -> new Document(resource, TEXT, TextDocumentModel.of(new byte[0])));

        List<String> announced = new ArrayList<>();
        held.document().onDidChangeResource.connect((was, now) -> announced.add(was + " -> " + now));

        documents.retarget(from, to);

        assertEquals("one event, not a remove and an insert", 1, announced.size());
        assertEquals(to, held.document().resource());
        assertSame("and it is the SAME document", held.document(), documents.get(to));
        assertNull("no longer at the old address", documents.get(from));
    }

    @Test
    public void retargetingToTheSameResourceAnnouncesNothing() {
        Document document = textDocument("a.txt", "");
        int[] announced = {0};
        document.onDidChangeResource.connect((was, now) -> announced[0]++);

        document.retarget(document.resource());

        assertEquals(0, announced[0]);
    }

    // ── Reference counting ──────────────────────────────────────────────────────────────────────

    /**
     * <b>The document lives while any reference does.</b> Its lifetime used to be a tab's, so anything
     * else holding it — the Problems panel, an index, a background compile — held something that could
     * be disposed underneath it. That is the reported "Parser is closed".
     */
    @Test
    public void aDocumentLivesWhileAnyReferenceDoes() {
        Documents documents = new Documents();
        CountingModel model = new CountingModel();
        Resource resource = file("a.count");

        DocumentReference tab = documents.open(resource, r -> new Document(r, TEXT, model));
        DocumentReference panel = documents.reference(resource);
        assertNotNull(panel);
        assertEquals(2, tab.document().referenceCount());

        tab.dispose();
        assertFalse("a tab closing is not the document ending", model.disposed);
        assertSame("and it is still open", tab.document(), documents.get(resource));

        panel.dispose();
        assertTrue("the LAST holder ends it", model.disposed);
        assertNull(documents.get(resource));
    }

    @Test
    public void disposingAReferenceTwiceReleasesItOnce() {
        Documents documents = new Documents();
        CountingModel model = new CountingModel();
        Resource resource = file("a.count");
        DocumentReference one = documents.open(resource, r -> new Document(r, TEXT, model));
        DocumentReference two = documents.reference(resource);

        one.dispose();
        one.dispose();

        assertFalse("a double dispose must not release somebody else's claim", model.disposed);
        two.dispose();
        assertTrue(model.disposed);
    }

    @Test
    public void openingTheSameResourceTwiceIsOneDocument() {
        Documents documents = new Documents();
        int[] built = {0};
        Resource resource = file("a.txt");

        DocumentReference first = documents.open(resource, r -> {
            built[0]++;
            return new Document(r, TEXT, TextDocumentModel.of(new byte[0]));
        });
        DocumentReference second = documents.open(resource, r -> {
            built[0]++;
            return new Document(r, TEXT, TextDocumentModel.of(new byte[0]));
        });

        assertEquals("the second caller joins the document already open", 1, built[0]);
        assertSame(first.document(), second.document());
    }

    /** Case folding is the server's rule and arrives as a strategy; {@code Resource} stays strict. */
    @Test
    public void twoCasesOfOneNameAreOneDocumentOnlyWhenTheHostFolds() {
        Documents strict = new Documents();
        strict.open(file("Main.java"), r -> new Document(r, TEXT, TextDocumentModel.of(new byte[0])));
        assertNull("strict by default", strict.get(file("main.java")));

        Documents folding = new Documents().setKeyStrategy(
                r -> Resource.of(CgPath.of("proj", r.path().toLowerCase(Locale.ROOT))));
        DocumentReference held = folding.open(file("Main.java"),
                r -> new Document(r, TEXT, TextDocumentModel.of(new byte[0])));

        assertSame(held.document(), folding.get(file("main.java")));
    }

    // ── Kinds ───────────────────────────────────────────────────────────────────────────────────

    @Test
    public void aKindClaimsItsFilesByNameExtensionAndGlob() {
        DocumentKinds kinds = new DocumentKinds();
        kinds.register(DocumentKind.of("t:graph", "Graph")
                .files(DocumentKind.FilePatterns.extension("shadergraph"))
                .model(BytesDocumentModel::new));
        kinds.register(DocumentKind.of("t:gradle", "Gradle")
                .files(DocumentKind.FilePatterns.name("build.gradle.kts"))
                .model(BytesDocumentModel::new));
        kinds.register(DocumentKind.of("t:spec", "Spec")
                .files(DocumentKind.FilePatterns.glob("*.test.js"))
                .model(BytesDocumentModel::new));

        assertEquals("t:graph", kinds.forResource(file("a/b/Thing.shadergraph")).id());
        assertEquals("t:gradle", kinds.forResource(file("build.gradle.kts")).id());
        assertEquals("t:spec", kinds.forResource(file("x/foo.test.js")).id());
        assertNull(kinds.forResource(file("readme.md")));
    }

    /** A leading dot is a name, not an extension — {@code .gitignore} is not a {@code gitignore} file. */
    @Test
    public void aLeadingDotIsNotAnExtension() {
        DocumentKinds kinds = new DocumentKinds();
        kinds.register(DocumentKind.of("t:ignore", "Ignore")
                .files(DocumentKind.FilePatterns.extension("gitignore"))
                .model(BytesDocumentModel::new));

        assertNull(kinds.forResource(file(".gitignore")));
    }

    @Test
    public void aKindWithNoModelIsRefusedAtRegistration() {
        try {
            new DocumentKinds().register(DocumentKind.of("t:broken", "Broken")
                    .files(DocumentKind.FilePatterns.extension("x")));
            fail("a kind that cannot open anything must be refused where it is written");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("no model"));
        }
    }

    @Test
    public void aDuplicateKindIdIsRefused() {
        DocumentKinds kinds = new DocumentKinds();
        kinds.register(DocumentKind.of("t:one", "One").model(BytesDocumentModel::new));
        try {
            kinds.register(DocumentKind.of("t:one", "Another").model(BytesDocumentModel::new));
            fail("two kinds under one id would apply in load order");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("namespaced"));
        }
    }

    /** Unregistering is what a mod unloading does, and it is why register answers a Disposable. */
    @Test
    public void unregisteringAKindWithdrawsIt() {
        DocumentKinds kinds = new DocumentKinds();
        var handle = kinds.register(DocumentKind.of("t:one", "One")
                .files(DocumentKind.FilePatterns.extension("one"))
                .model(BytesDocumentModel::new));
        assertNotNull(kinds.forResource(file("a.one")));

        handle.dispose();

        assertNull(kinds.forResource(file("a.one")));
        assertTrue(kinds.isEmpty());
    }

    /** A later kind specialises an earlier one rather than colliding with it. */
    @Test
    public void theMostRecentlyRegisteredKindWins() {
        DocumentKinds kinds = new DocumentKinds();
        kinds.register(DocumentKind.of("t:text", "Text")
                .files(DocumentKind.FilePatterns.extension("md")).model(BytesDocumentModel::new));
        kinds.register(DocumentKind.of("t:markdown", "Markdown")
                .files(DocumentKind.FilePatterns.extension("md")).model(BytesDocumentModel::new));

        assertEquals("t:markdown", kinds.forResource(file("readme.md")).id());
    }

    @Test
    public void aRegisteredKindCannotBeReDeclared() {
        DocumentKinds kinds = new DocumentKinds();
        DocumentKind kind = DocumentKind.of("t:one", "One").model(BytesDocumentModel::new);
        kinds.register(kind);
        try {
            kind.icon("something-else");
            fail("a kind is shared once registered");
        } catch (IllegalStateException expected) {
            // the point
        }
    }

    /** The one-liner a text kind is meant to be. */
    @Test
    public void aTextKindIsOneLine() {
        DocumentKind kind = DocumentKind.of("t:notes", "Notes")
                .files(DocumentKind.FilePatterns.extension("notes"))
                .text(null);

        assertTrue(kind.createModel(file("a.notes"), "hi".getBytes(StandardCharsets.UTF_8))
                instanceof TextDocumentModel);
    }

    // ── Bytes ───────────────────────────────────────────────────────────────────────────────────

    @Test
    public void anUnboundFileOpensAsBytesAndIsNeverDirty() {
        byte[] onDisk = {1, 2, 3, 0, 4};
        BytesDocumentModel model = new BytesDocumentModel(onDisk);
        Document document = new Document(file("a.bin"),
                DocumentKind.of("t:bytes", "Bytes").model(BytesDocumentModel::new), model);
        document.markSaved("e1");

        assertArrayEquals(onDisk, model.encode());
        assertFalse(document.isDirty());
        assertFalse("nothing binary is line-merged", model.mergeable());
    }

    @Test
    public void encodingBytesHandsBackACopy() {
        BytesDocumentModel model = new BytesDocumentModel(new byte[]{1, 2, 3});
        byte[] handed = model.encode();
        handed[0] = 99;

        assertEquals("a caller must not be able to edit the document by writing into what it saved",
                1, model.encode()[0]);
    }

    // ── EditorInput ─────────────────────────────────────────────────────────────────────────────

    @Test
    public void twoInputsForOneResourceAreOneTabUnlessTheyDiffer() {
        assertEquals(EditorInput.of(file("a.txt")), EditorInput.of(file("a.txt")));
        assertFalse(EditorInput.of(file("a.txt")).equals(EditorInput.of(file("b.txt"))));
        assertFalse("a read-only view is its own tab",
                EditorInput.of(file("a.txt")).equals(EditorInput.of(file("a.txt")).readOnly()));
        assertFalse(EditorInput.of(file("a.txt")).equals(EditorInput.of(file("a.txt")).as("t:other")));
    }

    // ── A model whose change cannot be an Edit ──────────────────────────────────────────────────

    @Test
    public void applyRecordsAnUndoableStepAndMarkChangedDoesNot() {
        CountingModel model = new CountingModel();
        model.changeThroughAnEdit();
        assertTrue(model.history().canUndo());

        CountingModel other = new CountingModel();
        other.change();
        assertFalse("markChanged costs the undo step, by construction", other.history().canUndo());
        assertEquals(1, other.version());
    }

    /** A model that counts what it was asked to do. */
    private static final class CountingModel extends AbstractDocumentModel {
        int encodes;
        boolean disposed;

        @Override
        public byte[] encode() {
            encodes++;
            return new byte[0];
        }

        @Override
        public void adopt(byte[] bytes) {
            adopted();
        }

        @Override
        public void dispose() {
            disposed = true;
        }

        void change() {
            markChanged();
        }

        void changeThroughAnEdit() {
            apply(new Edit() {
                @Override public void apply() { }
                @Override public void undo() { }
                @Override public String label() { return "change"; }
            });
        }
    }
}
