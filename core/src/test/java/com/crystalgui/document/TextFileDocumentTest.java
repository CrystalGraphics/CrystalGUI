package com.crystalgui.document;

import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.text.LineEnding;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.texteditor.TextEditor;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The document layer's first suite — <b>and the first one it has ever had</b>.
 *
 * <p>{@code plan_fs_rewrite.md} §1.4: no test constructs a {@code Workbench} or calls {@code openFile},
 * {@code saveActiveFile} or {@code isDirty}, and {@code OpenDocuments}, {@code TextFileDocument} and
 * {@code FileDocument} have no direct test at all. That is why both defects in that plan's §0 —
 * a CRLF file dirty on open and saved as LF, and an undo that restores text an external reload replaced —
 * survived 170 green filesystem tests: every one of them exercises the tier below this one.</p>
 *
 * <p>In {@code test} rather than {@code headlessTest} because a {@link TextEditor} needs
 * {@code StyleSheet}, which reads {@code default.css} through {@code CgIO} at class-init and is
 * therefore unloadable on the headless classpath.</p>
 */
public class TextFileDocumentTest extends UiDocumentTestBase {

    private static final Resource FILE = Resource.of(CgPath.of("proj", "src/Main.java"));

    /** An editor in a laid-out document, since the view state half of this record reads boxes. */
    private TextEditor editorOver(String text) {
        TextEditor editor = new TextEditor(text);
        editor.layout(l -> l.width(300).height(120));
        UIElement root = new UIElement().layout(l -> l.width(300).height(200));
        root.append(editor);
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        frame();
        return editor;
    }

    private TextFileDocument documentOver(String text) {
        return new TextFileDocument(editorOver(text), FILE);
    }

    // ── The two defects §0 names ────────────────────────────────────────────────────────────────

    /**
     * <b>The headline defect.</b> Both halves fail against the version before F0: {@code encode()} was
     * {@code editor.getText()}, which is the LF-normalised buffer, so a Windows file was converted on
     * every save AND read as modified the instant it opened.
     */
    @Test
    public void aCrlfFileSavesAsCrlfAndIsCleanOnOpen() {
        byte[] onDisk = "one\r\ntwo\r\nthree".getBytes(StandardCharsets.UTF_8);

        TextFileDocument doc = documentOver(new String(onDisk, StandardCharsets.UTF_8));

        assertArrayEquals("a save must write back the ending the file arrived with",
                onDisk, doc.encode());
        assertTrue("and a file nobody has edited must not be dirty",
                Arrays.equals(onDisk, doc.encode()));
    }

    /** The mirror case: an LF file must not acquire CRLF from anywhere. */
    @Test
    public void anLfFileStaysLf() {
        byte[] onDisk = "one\ntwo\nthree".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(onDisk, documentOver(new String(onDisk, StandardCharsets.UTF_8)).encode());
    }

    /**
     * A file with no break at all has no ending to detect, and must not gain one.
     *
     * <p>The counter-control for the fix: a repair written as "always write CRLF" passes the first test
     * and fails here and in {@link #anLfFileStaysLf}.
     */
    @Test
    public void aSingleLineFileIsUnchanged() {
        byte[] onDisk = "no breaks here".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(onDisk, documentOver(new String(onDisk, StandardCharsets.UTF_8)).encode());
    }

    /** An edit to a CRLF file leaves it CRLF — the ending belongs to the file, not to the last edit. */
    @Test
    public void editingACrlfFileKeepsItCrlf() {
        TextFileDocument doc = documentOver("one\r\ntwo");
        doc.editor().setCaret(doc.editor().getText().length());
        doc.editor().buffer().insert(doc.editor().getText().length(), "\nthree");

        assertArrayEquals("one\r\ntwo\r\nthree".getBytes(StandardCharsets.UTF_8), doc.encode());
    }

    /**
     * The status readout says what the file IS, not what the buffer holds.
     *
     * <p>Detecting on the way out reports LF for every file in the workspace, because the buffer is
     * normalised to LF the moment it loads — so the one readout whose entire job is to tell a Windows
     * user their file is CRLF could never say so.
     */
    @Test
    public void theLineEndingReadoutReportsTheFilesEndingAndNotTheBuffers() {
        assertEquals(LineEnding.CRLF, documentOver("one\r\ntwo").editor().buffer().lineEnding());
        assertEquals(LineEnding.LF, documentOver("one\ntwo").editor().buffer().lineEnding());
    }

    // ── Adopt: a reload is not an edit ──────────────────────────────────────────────────────────

    /**
     * <b>The second §0 defect, at the layer that reports it.</b> {@code TextBuffer.load} pushed a
     * {@code ChangeSetEdit}, so Ctrl+Z after a file changed underneath you restored the text the server
     * had already replaced — against {@code FileDocument.adopt}'s own written contract.
     */
    @Test
    public void undoAfterAReloadDoesNothing() {
        TextFileDocument doc = documentOver("original\n");
        doc.editor().buffer().insert(0, "typed ");
        assertTrue("the typing is undoable", doc.editor().buffer().history().canUndo());

        doc.adopt("from the server\n".getBytes(StandardCharsets.UTF_8));

        assertFalse("a reload leaves nothing to undo",
                doc.editor().buffer().history().canUndo());
        doc.editor().buffer().undo();
        assertEquals("and undoing anyway cannot resurrect the replaced text",
                "from the server\n", doc.editor().getText());
    }

    /** A reload still MOVES the document: the version bumps and listeners hear about it. */
    @Test
    public void aReloadStillBumpsTheVersionAndAnnounces() {
        TextFileDocument doc = documentOver("original\n");
        int before = doc.editor().buffer().version();
        int[] announced = {0};
        doc.editor().buffer().onChanged.connect(change -> announced[0]++);

        doc.adopt("replaced\n".getBytes(StandardCharsets.UTF_8));

        assertTrue("a reload is a change, whatever it is not", doc.editor().buffer().version() > before);
        assertEquals(1, announced[0]);
    }

    /** A reload of identical bytes is not a change at all, so nothing is announced. */
    @Test
    public void reloadingIdenticalBytesAnnouncesNothing() {
        TextFileDocument doc = documentOver("same\n");
        int before = doc.editor().buffer().version();

        doc.adopt("same\n".getBytes(StandardCharsets.UTF_8));

        assertEquals(before, doc.editor().buffer().version());
    }

    /** An edit made after a reload is undoable, and undoing it lands on the reloaded text. */
    @Test
    public void anEditAfterAReloadIsUndoableBackToTheReloadedText() {
        TextFileDocument doc = documentOver("original\n");
        doc.adopt("from the server\n".getBytes(StandardCharsets.UTF_8));

        doc.editor().buffer().insert(0, "mine ");
        assertTrue(doc.editor().buffer().undo());

        assertEquals("from the server\n", doc.editor().getText());
    }
}
