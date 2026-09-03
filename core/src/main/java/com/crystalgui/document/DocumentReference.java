package com.crystalgui.document;

import com.crystalgui.core.dispose.Disposable;

/**
 * One holder's claim on a {@link Document} — <b>the document lives while any reference does</b>.
 *
 * <h3>Why counting</h3>
 *
 * <p>A tab is not the only holder: the Problems panel follows a file, an index reads it, a background
 * compile is mid-way through it. Tying the document's life to the tab means disposing something
 * underneath one of them — the reported <em>"Parser is closed"</em> — and the mirror failure is a
 * document nothing holds staying alive because nobody was sure who owned it.</p>
 *
 * <p>VS Code's {@code ITextModelService.createModelReference} answers an {@code IReference} for the
 * same reason, and its models are disposed when the last one goes.</p>
 *
 * <h3>The rule, and how a leak is seen</h3>
 *
 * <p>Reference counting fails silently in the direction of a leak: a holder that forgets to dispose
 * keeps a model alive for the session, and nothing errors. So every holder registers with
 * {@code Disposer}, and {@code Document.referenceCount()} is in the health readout — a count that only
 * grows is the symptom.</p>
 */
public interface DocumentReference extends Disposable {

    /** The document. Valid until this reference is disposed. */
    Document document();
}
