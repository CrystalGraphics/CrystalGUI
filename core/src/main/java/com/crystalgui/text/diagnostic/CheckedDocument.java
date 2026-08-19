package com.crystalgui.text.diagnostic;

import com.crystalgui.core.async.JobKey;
import com.crystalgui.core.async.JobLane;
import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.text.TextBuffer;

import javax.annotation.Nullable;

import java.util.List;

/**
 * Runs a {@link SourceChecker} over a document as it is typed in, and files what it says.
 *
 * <h3>Everything here is about the two things a slow checker gets wrong</h3>
 *
 * <p><b>Debounced</b>, so a run of keystrokes produces one check rather than one per key — the same
 * {@link JobScheduler} arrangement the language engines use, keyed on this object so every submission
 * replaces the last and an in-flight check is abandoned rather than raced.</p>
 *
 * <p><b>Version-gated</b>, and this is the half that must not be got wrong. A diagnostic is a row and a
 * column; a squiggle is an offset; and the conversion between them is only legal against the document the
 * check actually saw. A shader check is a parse and a real one is a driver round-trip, so it is slower
 * than a Java compile and the window for a stale answer is <em>wider</em>, not narrower. An answer that
 * arrives describing text the buffer has moved on from is dropped — the next check will speak for the
 * text as it is now, and a mark placed from stale coordinates points at innocent code.</p>
 *
 * <h3>What it deliberately does not do</h3>
 *
 * <p>It does not track ranges through edits. {@code DecorationSet} does that, and it is already wired to
 * {@code DiagnosticSet.onChanged} for every producer — filing here is all a producer has to do to get
 * squiggles that survive typing, a Problems row that navigates correctly, and a status-bar count.</p>
 */
public final class CheckedDocument implements AutoCloseable {

    private final TextBuffer buffer;
    private final DiagnosticSet diagnostics;
    private final SourceChecker checker;
    private final String owner;
    private final String name;
    @Nullable private final JobScheduler scheduler;
    private final JobKey key;

    private Connection subscription = Connection.DISCONNECTED;
    private boolean closed;

    /**
     * @param owner what these problems are filed under — the Problems panel groups by it, and it is what
     *              lets this producer's answers be replaced without touching the engine's
     * @param scheduler where checks run, or null to check synchronously (tests, and a host with no loop)
     */
    public CheckedDocument(String owner, String name, TextBuffer buffer, DiagnosticSet diagnostics,
                           SourceChecker checker, @Nullable JobScheduler scheduler) {
        this.owner = owner;
        this.name = name == null || name.isEmpty() ? "source" : name;
        this.buffer = buffer;
        this.diagnostics = diagnostics;
        this.checker = checker;
        this.scheduler = scheduler;
        this.key = JobKey.of(this, owner + "-check");
    }

    /** Subscribes and checks once, so a file that is opened and not typed in is still checked. */
    public CheckedDocument start() {
        if (closed) return this;
        subscription = buffer.onChanged.connect(change -> schedule());
        checkNow();
        return this;
    }

    private void schedule() {
        if (closed) return;
        if (scheduler == null) {
            checkNow();
            return;
        }
        final String source = buffer.document().toString();
        final long version = buffer.version();
        scheduler.<List<Diagnostic>>job(key, JobLane.LATENCY, context -> checker.check(name, source))
                .debounce(DEBOUNCE_MILLIS)
                .onDone(problems -> install(problems, version))
                .submit();
    }

    /**
     * 300ms, the figure every other analysis here uses.
     *
     * <p>Shared rather than tuned per producer: two debounces on one document means two bursts of work
     * at different moments while typing, and the user experiences the slower one anyway.</p>
     */
    private static final long DEBOUNCE_MILLIS = 300;

    private void checkNow() {
        if (closed) return;
        install(checker.check(name, buffer.document().toString()), buffer.version());
    }

    /** UI thread. Files the answer, unless the document has moved on since the check began. */
    private void install(@Nullable List<Diagnostic> problems, long version) {
        if (closed) return;
        // THE VERSION THE CHECK SAW, compared against the buffer NOW. Rows and columns only mean
        // something against the text they were computed from; a mark placed from stale coordinates
        // underlines whatever has since moved into them, and corrects itself on the next check -- which
        // reads as the checker lagging rather than as an answer about a different document.
        if (version != buffer.version()) return;
        diagnostics.changeOne(owner, problems == null ? List.of() : problems);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        subscription.disconnect();
        if (scheduler != null) scheduler.cancel(key);
        diagnostics.remove(owner);
    }
}
