package com.crystalgui.document;

import com.crystalgui.text.diagnostic.DiagnosticSet;
import com.crystalgui.ui.dom.UIElement;

import javax.annotation.Nullable;

import com.crystalgui.core.signal.Connection;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;

/**
 * One open file, whatever kind of file it is.
 *
 * <h3>Why this exists</h3>
 *
 * <p>The workbench held {@code Map<CgPath, TextEditor>} and saved with {@code editor.getText()}, so
 * "an open file" and "a text editor" were the same thing in four separate places — the cache, the save
 * path, the active-editor accessor the Problems panel jumps through, and the panel factory. Anything that
 * is not text could be <em>opened</em> once an editor binding existed, and then could not be saved or
 * reported on.</p>
 *
 * <h3>Three methods, and no state</h3>
 *
 * <p>These are the only three questions the workbench asks: what do I show, what do I write, what do I
 * load. Everything else it needs it already knows.</p>
 *
 * <p><b>Dirtiness is deliberately not here.</b> The first version had {@code isDirty()} and
 * {@code markSaved()} on this interface, which forced every implementation to keep its own copy of the
 * bytes last read from disk and its own comparison against them — a field and two methods duplicated per
 * document kind, and a fresh chance to get "modified" wrong each time. But "differs from disk" is not
 * something a document can know: the disk is the workbench's business, since the workbench is what reads
 * and writes. It now keeps the baseline, and asks {@link #encode()} when it wants to compare. Same
 * division as the I/O itself — a document owns <em>content</em>, never paths, reads, writes or etags.</p>
 *
 * <p>An abstract base class would not have helped either: {@code ShaderGraphEditor} is a widget and
 * already extends {@code UIElement}, so the one implementation that most wanted to inherit the
 * bookkeeping is the one that could not.</p>
 *
 * <h3>Bytes, not text</h3>
 *
 * <p>{@link #encode()} returns bytes rather than a {@code String} because the thing being saved may have
 * no textual form at all — a shader graph encodes to JSON on demand, an image would not encode to text
 * under any encoding. Text documents answer with UTF-8, which is what the save path already did one layer
 * further out.</p>
 */

public interface FileDocument {

    /**
     * What this document <b>is</b> — IntelliJ's {@code FileEditor.getFile()}, VS Code's
     * {@code EditorInput.resource}.
     *
     * <p>Without it, anything wanting to go from a document back to its address needs a map kept beside
     * the document store, maintained by whoever created it. {@code CrystalEditor} had exactly that: a
     * {@code Map<String, ShaderGraphEditor>} plus a reverse linear scan over it, so that "View Generated
     * Shader" could work out which graph it was looking at.</p>
     *
     * <p>A {@link Resource} rather than a {@link CgPath} because a document need not be a file — and
     * because the two are the same string for project files, so this costs nothing to store or save.</p>
     */
    Resource resource();

    /**
     * Told when this document becomes, or stops being, the one in front.
     *
     * <h3>This is where status-bar contributions belong</h3>
     *
     * <p>The workbench knows exactly one thing no document can work out for itself — <b>which tab is
     * active</b> — and that is all it should be saying. What a document then chooses to publish is its
     * own: a text file reports a caret position, a line ending and an encoding; a shader graph reports a
     * compile summary; a diff or an image would report neither and something else instead.</p>
     *
     * <p>The first cut had the workbench writing the text readouts directly, which works and does not
     * scale: every new document type would mean editing {@code Workbench}, in a codebase whose whole
     * direction is that a document type is a <em>contribution</em>. Both references draw the line here —
     * IntelliJ's {@code StatusBarWidgetFactory} is asked per file, VS Code's extensions show and hide
     * their own items on {@code onDidChangeActiveTextEditor}.</p>
     *
     * <p><b>Deactivation is not optional.</b> A status item describes what is true right now, so a
     * document that publishes on activation and never withdraws leaves its numbers on screen underneath
     * somebody else's tab — the shader graph's compile summary sat over a plain text file exactly that
     * way. Whatever a document sets here, it clears here.</p>
     *
     * <p>Defaults to nothing, so a document with no ambient state says nothing rather than being made to
     * declare that it has nothing to say.</p>
     */
    default void setActive(boolean active) {
    }

    /**
     * What is wrong with this document, or null when it has nothing to report.
     *
     * <h3>Asked of the DOCUMENT, not of an editor</h3>
     *
     * <p>{@code Workbench} used to bind its Problems panel to {@code activeEditor().diagnostics()} — the
     * active {@code TextEditor}. A shader graph has no {@code TextEditor}, so the panel was empty by
     * construction the whole time a graph was in front, while its compiler was producing a dozen
     * attributed errors that had nowhere to go.</p>
     *
     * <p>Same correction as {@link #setActive}: the workbench knows which tab is in front and nothing else
     * about it, so what a document has to say is the document's to answer. A text file forwards its
     * editor's set; a graph owns one it fills from each compile; an image returns null.</p>
     *
     * <p><b>The same instance every time.</b> A view binds to it and listens for changes, so a fresh set
     * per call would leave the panel watching one nobody writes to.</p>
     */
    @Nullable
    default DiagnosticSet diagnostics() {
        return null;
    }

    /** The element the dock shows for this file. */
    UIElement view();

    /**
     * What {@code Ctrl+S} writes.
     *
     * <p>Called on demand, so a document with no stored serial form — a graph — encodes at this moment
     * rather than keeping one in step with itself. It is also what the workbench compares against the
     * bytes it last read, so this must be <b>stable</b>: encoding an unchanged document twice has to give
     * equal bytes, or the file will look permanently modified.</p>
     */
    byte[] encode();

    /**
     * Replaces the contents wholesale with what was read from disk.
     *
     * <p><b>Adopting is not editing.</b> Loading a file must not push undo steps and must not leave the
     * caret or the view somewhere the user did not put it — the document/view boundary this codebase
     * already draws for scroll and selection. Ctrl+Z immediately after opening a file has to reach
     * whatever the user did <em>before</em> opening it, not unwind the load.</p>
     *
     * <p>It is also how a re-read works — a Reload from Disk, or another client's change arriving — so it
     * has to be callable on a document that is already populated.</p>
     *
     * <p><b>Throw if the bytes cannot be applied.</b> A document that quietly ignores them is the
     * dangerous case: it shows empty, reports itself modified against the file it failed to read, and the
     * first save writes that emptiness over the user's work. The workbench catches this, reports it, and
     * refuses to save that file at all — see {@code Workbench.isDirty}.</p>
     */
    void adopt(byte[] bytes);

    /**
     * Fires when this document's <b>content</b> changes — what makes it dirty, or clean again.
     *
     * <h3>Why the workbench cannot work this out for itself</h3>
     *
     * <p>Dirtiness is {@code encode()} compared against the bytes last read, so "is anything unsaved"
     * means encoding <em>every open document</em>. {@code Workbench.refreshDirtyMarkers} did exactly that
     * every frame — serialising an entire shader graph sixty times a second to notice a tab marker that
     * changes when somebody types.</p>
     *
     * <p>Each document already knows. {@code TextFileDocument} has its editor's {@code onChanged}, and a
     * graph has its {@link com.crystalgui.core.undo.UndoStack}: every document change goes through an
     * {@code Edit} by construction, which is the boundary this codebase already draws between document
     * state and view state. So the announcement costs an implementation one line and the poll goes.</p>
     *
     * <h3>No default, deliberately</h3>
     *
     * <p>A default returning a signal that never fires is an answer chosen for someone who never saw the
     * question: the implementation compiles, and its tab silently never gains a dirty marker. Abstract
     * makes the compiler ask. Same rule the platform SPI states for sound and cursor.</p>
     *
     * <p><b>May over-fire.</b> "Something changed" is enough; whether it changed <em>back</em> is
     * {@code isDirty}'s business, and re-encoding one document on an edit is nothing like re-encoding all
     * of them on a frame.</p>
     *
     * <h3>A subscription, not an exposed signal</h3>
     *
     * <p>Returning a {@code Signal} would force every implementation to <em>own</em> one, which
     * {@code TextFileDocument} — a record wrapping an editor that already has {@code onChanged} — cannot
     * do without inventing state to keep in step. Taking a listener lets an implementation adapt whatever
     * it already has, and the {@link Connection} it hands back is a {@code Disposable}, so the caller can
     * drop the subscription when the document closes.</p>
     */
    Connection onDidChange(Runnable listener);
}
