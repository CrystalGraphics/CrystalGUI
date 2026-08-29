package com.crystalgui.text.diagnostic;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.text.TextPoint;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Every {@link Diagnostic} currently known about one document, kept in document order.
 *
 * <p>The per-document half of VS Code's {@code IMarkerService}, whose verbs — {@link #changeOne},
 * {@link #changeAll}, {@link #remove}, {@link #read} — this borrows.</p>
 *
 * <h3>Keyed by owner, because a document has more than one producer</h3>
 *
 * <p>This held a single flat list, which meant the <b>last writer won</b> — and that is the exact failure
 * {@code Workbench.onStatus} had before the status bar was keyed, arriving a second time in a different
 * package. VS Code keys markers by {@code (owner, resource)} precisely so {@code typescript},
 * {@code eslint} and a linter can each replace only their own findings on one file; IntelliJ does the same
 * per inspection.</p>
 *
 * <p>It was already binding here before it was fixed. {@code ShaderGraphEditor} has <b>four</b> independent
 * producers — the emitter's own problems, the GLSL driver's refusal, the preview's failure, and graph-level
 * warnings — and merged all four into one list by hand on every compile, because the model could not hold
 * them separately. Each is now an owner that replaces itself and leaves the others alone, which is also
 * what lets the driver's verdict persist across a recompile that did not reach the driver.</p>
 *
 * <h3>Replace-all per owner, not incremental</h3>
 *
 * <p>An owner either succeeds or returns the complete list of what it found; there is no such thing as
 * "one error was fixed" arriving on its own. An incremental API would have to answer which of the previous
 * diagnostics survived, and nothing that feeds this can answer that — the honest model is that each run
 * supersedes that owner's last one wholesale.</p>
 *
 * <p>This is also why the set carries no identity per diagnostic. Two compiles of the same broken file
 * produce equal records, and {@code equals} on the list is what keeps an unchanged recompile silent, so it
 * repaints nothing.</p>
 *
 * <h3>One set per document, and it is not the editor's</h3>
 *
 * <p>The same boundary the undo stack draws. Diagnostics describe the <em>document</em>, so two views onto
 * one file show the same problems, and a document with no view open still has them.</p>
 *
 * <h3>Known gap: there is no resource dimension</h3>
 *
 * <p>VS Code's service is keyed by {@code (owner, resource)} and is global, which is what lets its Problems
 * view list files that are not open. Ours is per-document, so the resource half is implicit. Not added on
 * speculation: a {@code TextEditor} deliberately has no resource — it is reusable and is used in the
 * harness with no file behind it at all — and there is no project-wide problems view to consume the map.
 * Structure with no consumer is what this pass is removing elsewhere, not adding here.</p>
 */
public final class DiagnosticSet {

    /**
     * Who a diagnostic is attributed to when nobody said — {@link #setAll} writes here.
     *
     * <p>Named rather than left blank so the single-producer case is one owner among others rather than a
     * special case in every query.</p>
     */
    public static final String DEFAULT_OWNER = "default";

    /** Fires whenever the contents change. Carries no value: a listener re-reads, so ten mutations in a
     * tick collapse to one repaint, exactly as {@code TreeObserver.stateChanged} does. */
    public final Signal.Action onChanged = new Signal.Action();

    /** Insertion-ordered, so {@link #owners()} is stable and a merge is reproducible. */
    private final Map<String, List<Diagnostic>> byOwner = new LinkedHashMap<>();

    /** Every owner's contents, merged and sorted. Rebuilt on mutation, never on read. */
    private List<Diagnostic> merged = List.of();

    public boolean isEmpty() {
        return merged.isEmpty();
    }

    public int size() {
        return merged.size();
    }

    /** Everything, in document order, from every owner. */
    public List<Diagnostic> all() {
        return Collections.unmodifiableList(merged);
    }

    /** What one owner last reported. Empty when it has never reported or has been removed. */
    public List<Diagnostic> read(String owner) {
        return Collections.unmodifiableList(byOwner.getOrDefault(owner, List.of()));
    }

    /** Who has reported anything, in the order they first did. */
    public Set<String> owners() {
        return Collections.unmodifiableSet(byOwner.keySet());
    }

    /**
     * Replaces everything {@code owner} has to say, leaving every other owner alone.
     *
     * <p>No-ops when that owner's contents are unchanged, so a recompile that finds the same problems does
     * not repaint every squiggle in the file — the same equality guard {@code replaceOrPutCandidate} uses
     * to make widget geometry settle.</p>
     */
    public DiagnosticSet changeOne(String owner, @Nullable Collection<Diagnostic> diagnostics) {
        String key = owner == null ? DEFAULT_OWNER : owner;
        List<Diagnostic> incoming = sanitise(diagnostics);
        List<Diagnostic> previous = byOwner.get(key);
        if (incoming.isEmpty()) {
            if (previous == null) return this;
            byOwner.remove(key);
        } else {
            if (incoming.equals(previous)) return this;
            byOwner.put(key, incoming);
        }
        remerge();
        return this;
    }

    /**
     * Replaces <b>every</b> owner's contents in one act, announcing once.
     *
     * <p>VS Code's {@code changeAll}, and the reason it exists here is the same: a producer that writes
     * several owners together — the shader graph writes four on every compile — would otherwise announce
     * once per owner, and a Problems panel bound to it would rebuild three times for one compile.</p>
     *
     * <p>An owner absent from {@code contents} is <em>removed</em>, not left behind. That is what makes
     * this a replacement rather than a merge, and it is what a producer that owns all of them means.</p>
     */
    public DiagnosticSet changeAll(@Nullable Map<String, ? extends Collection<Diagnostic>> contents) {
        Map<String, List<Diagnostic>> next = new LinkedHashMap<>();
        if (contents != null) {
            for (Map.Entry<String, ? extends Collection<Diagnostic>> entry : contents.entrySet()) {
                List<Diagnostic> sanitised = sanitise(entry.getValue());
                if (sanitised.isEmpty()) continue;
                next.put(entry.getKey() == null ? DEFAULT_OWNER : entry.getKey(), sanitised);
            }
        }
        if (next.equals(byOwner)) return this;
        byOwner.clear();
        byOwner.putAll(next);
        remerge();
        return this;
    }

    /** Drops everything {@code owner} said. Silent when it had said nothing. */
    public DiagnosticSet remove(String owner) {
        if (byOwner.remove(owner == null ? DEFAULT_OWNER : owner) == null) return this;
        remerge();
        return this;
    }

    /**
     * Replaces the {@link #DEFAULT_OWNER}'s contents — the single-producer spelling.
     *
     * <p><b>Kept, and the name is the reason it nearly was not.</b> It does not set <em>all</em>: it
     * replaces one owner's slice and leaves every other owner untouched, which is precisely the thing a
     * reader would assume it did not do. The case for deleting it was that one honest vocabulary beats a
     * convenience that lies.</p>
     *
     * <p>It stays because the alternative is worse in the direction that matters. Deleting it makes every
     * single-producer document write {@code changeOne(DEFAULT_OWNER, list)} — and a producer that has to
     * name an owner it does not have will invent one, so instead of one shared default there would be a
     * scattering of ad-hoc owner strings that never collide with each other and never merge either. The
     * convenience is what keeps the single-producer case pointed at a <em>known</em> key.</p>
     *
     * <p>What is not kept is the ambiguity: this is documented as one owner's write, {@link #changeAll} is
     * the one that means all of them, and the pair reads correctly beside each other. Deleting {@code add}
     * — which appended to the default owner and was the genuinely misleading one, since "add" says nothing
     * about replacing — is the other half of that decision.</p>
     */
    public DiagnosticSet setAll(@Nullable Collection<Diagnostic> incoming) {
        return changeOne(DEFAULT_OWNER, incoming);
    }

    /** Empties every owner. */
    public DiagnosticSet clear() {
        if (byOwner.isEmpty()) return this;
        byOwner.clear();
        remerge();
        return this;
    }

    private static List<Diagnostic> sanitise(@Nullable Collection<Diagnostic> incoming) {
        if (incoming == null || incoming.isEmpty()) return List.of();
        List<Diagnostic> copy = new ArrayList<>(incoming);
        copy.removeIf(Objects::isNull);
        return copy;
    }

    /**
     * Rebuilds the merged view, and announces only if it actually moved.
     *
     * <p>The guard is on the <em>merged</em> list rather than on the owner that changed, because that is
     * what every consumer reads. An owner swapping two equal diagnostics for each other changes its own
     * slot and changes nothing anybody can see.</p>
     */
    private void remerge() {
        List<Diagnostic> next = new ArrayList<>();
        for (List<Diagnostic> owned : byOwner.values()) next.addAll(owned);
        Collections.sort(next);
        if (next.equals(merged)) return;
        merged = next;
        onChanged.emit();
    }

    // ── Queries ─────────────────────────────────────────────────────────────────────────────────

    /** The worst severity on {@code row}, or null when the row is clean — what decides a squiggle's colour
     * and a gutter icon when several problems overlap. */
    @Nullable
    public DiagnosticSeverity worstOnRow(int row) {
        DiagnosticSeverity worst = null;
        for (Diagnostic diagnostic : merged) {
            if (diagnostic.touchesRow(row)) worst = DiagnosticSeverity.worst(worst, diagnostic.severity());
        }
        return worst;
    }

    public int count(DiagnosticSeverity severity) {
        int count = 0;
        for (Diagnostic diagnostic : merged) {
            if (diagnostic.severity() == severity) count++;
        }
        return count;
    }

    /** The worst severity anywhere, or null when there is nothing to report. Drives the inspection
     * widget's overall state — a green tick versus a red cross. */
    @Nullable
    public DiagnosticSeverity worst() {
        DiagnosticSeverity worst = null;
        for (Diagnostic diagnostic : merged) {
            worst = DiagnosticSeverity.worst(worst, diagnostic.severity());
        }
        return worst;
    }

    // ── Navigation ──────────────────────────────────────────────────────────────────────────────

    /**
     * The first diagnostic starting strictly after {@code from}, wrapping to the first when there is none.
     *
     * <p>Wrapping rather than stopping is IntelliJ's F2 and VS Code's F8: the navigation is a cycle, so
     * repeatedly pressing it walks every problem and returns, and the last one is not a dead end. Strictly
     * after, so pressing it while already sitting on a problem moves.</p>
     */
    @Nullable
    public Diagnostic nextFrom(TextPoint from) {
        if (merged.isEmpty()) return null;
        for (Diagnostic diagnostic : merged) {
            if (diagnostic.start().compareTo(from) > 0) return diagnostic;
        }
        return merged.get(0);
    }

    /** The mirror of {@link #nextFrom}, wrapping to the last. */
    @Nullable
    public Diagnostic previousFrom(TextPoint from) {
        if (merged.isEmpty()) return null;
        for (int i = merged.size() - 1; i >= 0; i--) {
            if (merged.get(i).start().compareTo(from) < 0) return merged.get(i);
        }
        return merged.get(merged.size() - 1);
    }
}
