package com.crystalgui.headless;

import com.crystalgui.document.BytesDocumentModel;
import com.crystalgui.document.Document;
import com.crystalgui.document.DocumentKind;
import com.crystalgui.document.DocumentKinds;
import com.crystalgui.document.TextDocumentModel;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.text.TextEncoding;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A file with no text in it opens as <b>bytes</b>, not as mojibake somebody can save.
 *
 * <p>Decoding a PNG as UTF-8 gives an editor full of replacement characters — and the editor is
 * writable, so the first {@code Ctrl+S} writes those characters back over the file. The decode is
 * lossy, so the original bytes are gone.</p>
 *
 * <p>Asserted against the fallback kind's own factory rather than through the workbench, because the
 * decision is the model's: which model gets built is what decides whether the file can be destroyed.</p>
 */
public class BinaryFilesOpenAsBytesTest {

    /** What the workbench registers as its fallback: text unless the bytes say otherwise. */
    private static DocumentKinds kinds() {
        DocumentKinds kinds = new DocumentKinds();
        kinds.register(DocumentKind.of("test:file", "File").fallback()
                .model((resource, bytes) -> TextEncoding.looksBinary(bytes)
                        ? new BytesDocumentModel(bytes)
                        : TextDocumentModel.of(bytes)));
        return kinds;
    }

    private static Document open(String name, byte[] bytes) {
        Resource resource = Resource.of(CgPath.of("p", name));
        DocumentKind kind = kinds().forResource(resource);
        return new Document(resource, kind, kind.createModel(resource, bytes));
    }

    /** A PNG's first eight bytes, which carry a NUL and are what a real one starts with. */
    private static final byte[] PNG_HEADER = {
            (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13,
    };

    @Test
    public void aBinaryFileOpensAsBytes() {
        Document document = open("logo.png", PNG_HEADER);
        assertTrue("a PNG is not text and must not be decoded as if it were",
                document.model() instanceof BytesDocumentModel);
    }

    /**
     * <b>The one that says why it matters.</b> Whatever a binary document encodes has to be what it was
     * given: a text model round-trips it through UTF-8, and the decode is lossy.
     */
    @Test
    public void whatItSavesIsWhatItWasGiven() {
        Document document = open("logo.png", PNG_HEADER);
        assertArrayEquals(PNG_HEADER, document.model().encode());
    }

    /** And it is never dirty, so nothing offers to save it in the first place. */
    @Test
    public void aBinaryDocumentIsNeverDirty() {
        assertFalse(open("logo.png", PNG_HEADER).isDirty());
    }

    /**
     * The counter-control. Without it a fix written as "always open as bytes" passes everything above
     * and turns every source file in the workspace into a viewer.
     */
    @Test
    public void anOrdinaryTextFileStillOpensAsText() {
        Document document = open("Main.java", "class Main { }".getBytes(StandardCharsets.UTF_8));
        assertTrue(document.model() instanceof TextDocumentModel);
    }

    /**
     * The other counter-control, and the one the sniff is written for: a UTF-16 file is half NULs by
     * construction and is text. Its mark says so.
     */
    @Test
    public void aUtf16FileIsTextDespiteItsNuls() {
        byte[] utf16 = new byte[]{(byte) 0xFF, (byte) 0xFE, 'h', 0, 'i', 0};
        Document document = open("notes.txt", utf16);
        assertTrue("a byte-order mark says outright that every other byte is a NUL",
                document.model() instanceof TextDocumentModel);
    }
}
