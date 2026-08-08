package com.crystalgui.text.diagnostic;

import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.fs.Resource;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every problem in the workspace, by resource — the missing half of VS Code's {@code IMarkerService}.
 *
 * <p>Ported from {@code vs/platform/markers/common/markerService.ts}. {@link DiagnosticSet} already
 * carries that service's <b>owner</b> dimension; this carries its <b>resource</b> one.</p>
 *
 * <h3>Why this is an index of sets and not a map of lists</h3>
 *
 * <p>The obvious port is {@code Map<Resource, Map<Owner, List<Diagnostic>>>} with everything reading
 * through it — which is what the reference does, because in VS Code a text model always has a URI. Here it
 * does not: a {@code TextEditor} is reusable and is used in the harness with no file behind it at all, so
 * making the resource mandatory would mean inventing an identity for editors that have none, and every
 * existing producer would have to be told about a resource to say anything.</p>
 *
 * <p>So a document keeps owning its {@link DiagnosticSet} — which is also what lets a document with no view
 * open still have problems — and <b>registers it here under its resource</b>. The index is then a view over
 * sets that already exist rather than a second place they live, and nothing that works today has to change
 * to keep working. What it adds is the question no per-document set could answer: <em>how many problems are
 * there in the workspace</em>, and <em>which files have them</em>.</p>
 *
 * <h3>Attachment is the document's, and so is detachment</h3>
 *
 * <h3>One per workspace, never a process global</h3>
 *
 * <p>VS Code injects its marker service per window; ours is an ordinary object a workspace owns, and the
 * difference is not stylistic. As a static it accumulated every document ever opened: the index holds a
 * listener on each set, so nothing it indexed could ever be collected, and a test suite that opens files
 * without closing them killed the worker on memory rather than failing an assertion. An instance dies with
 * the workbench that owns it.</p>
 *
 * <p>A closed document's problems are not the workspace's problems any more, and this holds a listener on
 * every set it indexes — so a document that attached and never detached would keep its set, its
 * diagnostics and its listener alive for the rest of the process. {@link #detach} is the other half, and
 * it is the half that leaks.</p>
 */
public final class Markers {

    public Markers() {
    }

    /**
     * Something changed, and this is which resource — VS Code's {@code onMarkerChanged}.
     *
     * <p>Carries the resource rather than nothing, unlike {@link DiagnosticSet#onChanged}, because a
     * listener here is watching <em>many</em> documents: told only "something moved" it would have to
     * re-read every one of them to find out what.</p>
     */
    public final Signal.Value<Resource> onDidChange = new Signal.Value<>();

    /** Insertion-ordered, so listing the workspace's problem files is stable between reads. */
    private final Map<Resource, DiagnosticSet> sets = new LinkedHashMap<>();

    private final Map<Resource, Connection> watches = new LinkedHashMap<>();

    /**
     * Indexes a document's problems under its resource. Replacing an existing entry drops the old watch.
     *
     * @return the set that was passed in, so a caller can chain
     */
    public DiagnosticSet attach(Resource resource, DiagnosticSet set) {
        if (resource == null || set == null) return set;
        detach(resource);
        sets.put(resource, set);
        watches.put(resource, set.onChanged.connect(() -> onDidChange.emit(resource)));
        onDidChange.emit(resource);
        return set;
    }

    /** Stops indexing a resource — what closing a document does. @see Markers */
    public void detach(Resource resource) {
        Connection watch = watches.remove(resource);
        if (watch != null) watch.disconnect();
        if (sets.remove(resource) != null) onDidChange.emit(resource);
    }

    /** The set indexed for a resource, or null when nothing has attached one. */
    @Nullable
    public DiagnosticSet forResource(Resource resource) {
        return sets.get(resource);
    }

    /** Everything known about one resource, in document order. */
    public List<Diagnostic> read(Resource resource) {
        DiagnosticSet set = sets.get(resource);
        return set == null ? List.of() : set.all();
    }

    /** Every indexed resource, in the order they were attached. */
    public List<Resource> resources() {
        return new ArrayList<>(sets.keySet());
    }

    /** Only the resources that actually have something to report — what a Problems tree lists. */
    public List<Resource> resourcesWithProblems() {
        List<Resource> found = new ArrayList<>();
        for (Map.Entry<Resource, DiagnosticSet> entry : sets.entrySet()) {
            if (!entry.getValue().isEmpty()) found.add(entry.getKey());
        }
        return found;
    }

    /** How many of one severity there are across the whole workspace. */
    public int count(DiagnosticSeverity severity) {
        int total = 0;
        for (DiagnosticSet set : sets.values()) total += set.count(severity);
        return total;
    }

    /** The worst severity anywhere in the workspace, or null when it is clean. */
    @Nullable
    public DiagnosticSeverity worst() {
        DiagnosticSeverity worst = null;
        for (DiagnosticSet set : sets.values()) {
            worst = DiagnosticSeverity.worst(worst, set.worst());
        }
        return worst;
    }

    /** Everything, everywhere. Ordered by resource, then by position within each. */
    public List<Diagnostic> all() {
        List<Diagnostic> everything = new ArrayList<>();
        for (DiagnosticSet set : sets.values()) everything.addAll(set.all());
        return everything;
    }

    /** Drops every index entry and its watch — what disposing a workspace does. */
    public void clear() {
        for (Connection watch : watches.values()) watch.disconnect();
        watches.clear();
        sets.clear();
        onDidChange.disconnectAll();
    }
}
