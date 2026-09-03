package com.crystalgui.document;

import com.crystalgui.core.dispose.Disposable;

/**
 * One holder's claim on a {@link Document} — <b>the document lives while any reference does</b>.
 *
 * <h3>What counting replaces</h3>
 *
 * <p>{@code plan_fs_rewrite.md} D3. A document's lifetime was a tab's: {@code OpenDocuments.close}
 * disposed whatever was in the map. So anything else holding it — the Problems panel following a file,
 * an index reading it, a background compile — was holding something that could be disposed underneath
 * it, which is the reported <em>"Parser is closed"</em>. The mirror failure is the leak: a document
 * nothing holds staying alive because nobody was sure who owned it.</p>
 *
 * <p>VS Code's {@code ITextModelService.createModelReference} answers an {@code IReference} for exactly
 * this reason, and its models are disposed when the last one goes.</p>
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
