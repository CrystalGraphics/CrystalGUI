package com.crystalgui.document;

/**
 * Anything with no kind — <b>bytes, read-only, and honest about it</b>.
 *
 * <p>What a file opens as when no {@link DocumentKind} claims it: an image, an archive, a binary a NUL
 * sniff refused to treat as text. It exists so that "open this file" always has an answer, and so the
 * answer is a viewer that shows what the file is rather than an editor that would write nonsense back.
 * VS Code's binary editor and IntelliJ's "file is not displayable" both occupy this slot.</p>
 *
 * <p>{@link #adopt} works — a file changing on disk still updates the viewer — and nothing else does:
 * the version moves only when the bytes are replaced from outside, so such a document is never dirty
 * and its save has nothing to write.</p>
 */
public final class BytesDocumentModel extends AbstractDocumentModel {

    private byte[] bytes;

    public BytesDocumentModel(byte[] bytes) {
        this.bytes = bytes == null ? new byte[0] : bytes;
    }

    @Override
    public byte[] encode() {
        // A COPY. The array is this model's state and a caller that kept the reference could edit the
        // document by writing into what it was handed to save.
        return bytes.clone();
    }

    @Override
    public void adopt(byte[] incoming) {
        this.bytes = incoming == null ? new byte[0] : incoming;
        adopted();
    }

    /** How big it is, which is most of what a viewer for one of these can honestly say. */
    public int size() {
        return bytes.length;
    }
}
