package com.crystalgui.document;

import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.diagnostic.DiagnosticSet;
import com.crystalgui.text.syntax.Language;
import com.crystalgui.text.syntax.SyntaxTokenizer;
import com.crystalgui.text.lang.LanguageServices;

import org.jetbrains.annotations.Nullable;

/**
 * A text document — <b>a {@link TextBuffer}, plus the language machinery that belongs to it</b>.
 *
 * <h3>No new model was written</h3>
 *
 * <p>{@code TextBuffer} already had the rope, a monotonic version, the line ending, the undo stack, the
 * decoration set, the diagnostic set and a change signal. It was <em>unused as a document model</em>
 * only because the document layer sat above {@code widget} and talked to a {@code TextEditor} instead —
 * adapting {@code editor.onChanged}, which hands over a {@code String} of the whole text per keystroke,
 * to a buffer sitting one package below with everything already computed.</p>
 *
 * <h3>The language services are the MODEL's, not a view's</h3>
 *
 * <p>They hung off the editor, so two panes onto one file would have held two parse trees and a
 * document with no tab could not analyse at all — which is the state the Problems panel, a background
 * compile and Go to Definition all want it in. Same boundary the buffer already draws for diagnostics
 * and decorations: a diagnostic describes a <b>document</b>.</p>
 */
public final class TextDocumentModel implements DocumentModel {

    private final TextBuffer buffer;
    private final Signal.Action onChanged = new Signal.Action();
    private final Connection bufferSubscription;

    @Nullable
    private Language language;
    @Nullable
    private SyntaxTokenizer tokenizer;
    @Nullable
    private LanguageServices services;

    public TextDocumentModel() {
        this(new TextBuffer());
    }

    public TextDocumentModel(TextBuffer buffer) {
        this.buffer = buffer == null ? new TextBuffer() : buffer;
        // Adapted rather than re-announced: the buffer is the source, and a second signal kept in step
        // by hand is the copy that eventually disagrees. The payload is dropped because a DocumentModel
        // says only THAT it moved -- a consumer that wants the ChangeSet asks the buffer, which is the
        // one that has it.
        this.bufferSubscription = this.buffer.onChanged.connect(change -> onChanged.emit());
    }

    /** From a file's bytes, taking its ending, charset and byte-order mark from them. */
    public static TextDocumentModel of(byte[] bytes) {
        TextBuffer buffer = new TextBuffer();
        buffer.loadBytes(bytes);
        return new TextDocumentModel(buffer);
    }

    /** The document itself. Editing goes through here; the model adds nothing in front of it. */
    public TextBuffer buffer() {
        return buffer;
    }

    // ── DocumentModel ───────────────────────────────────────────────────────────────────────────

    /** The ending, the charset and the mark restored. @see TextBuffer#encodeBytes */
    @Override
    public byte[] encode() {
        return buffer.encodeBytes();
    }

    @Override
    public void adopt(byte[] bytes) {
        buffer.loadBytes(bytes);
    }

    @Override
    public int version() {
        return buffer.version();
    }

    @Override
    public UndoStack history() {
        return buffer.history();
    }

    @Override
    public Signal.Action onChanged() {
        return onChanged;
    }

    /** Text is the one thing a three-way merge is actually for. */
    @Override
    public boolean mergeable() {
        return true;
    }

    /** The buffer's set — a document's problems belong to its document. */
    @Override
    public DiagnosticSet diagnostics() {
        return buffer.diagnostics();
    }

    // ── Language ────────────────────────────────────────────────────────────────────────────────

    @Nullable
    public Language language() {
        return language;
    }

    @Nullable
    public SyntaxTokenizer tokenizer() {
        return tokenizer;
    }

    @Nullable
    public LanguageServices services() {
        return services;
    }

    /**
     * Binds this document to a language, its tokenizer and its services.
     *
     * <p>Replacing them closes what was there — a tokenizer holds a native parse tree, and
     * {@code SyntaxTokenizer.close()} existed for a release with nothing calling it, so every parse
     * tree in the application survived until the process ended.</p>
     */
    public TextDocumentModel setLanguage(@Nullable Language language,
                                         @Nullable SyntaxTokenizer tokenizer,
                                         @Nullable LanguageServices services) {
        closeLanguage();
        this.language = language;
        this.tokenizer = tokenizer;
        this.services = services;
        return this;
    }

    /**
     * Releases the parse tree, the engine and the buffer subscription.
     *
     * <p>Reached from the last {@link DocumentReference} being released, never from a widget's
     * teardown: the dock rebuilds every panel on every split and drag, so freeing the tree there
     * releases it for a document that is still open and rebuilds it on the next frame.</p>
     */
    @Override
    public void dispose() {
        closeLanguage();
        bufferSubscription.disconnect();
    }

    private void closeLanguage() {
        if (services != null) {
            services.close();
            services = null;
        }
        if (tokenizer != null) {
            tokenizer.close();
            tokenizer = null;
        }
        language = null;
    }
}
