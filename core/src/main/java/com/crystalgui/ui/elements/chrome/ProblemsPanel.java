package com.crystalgui.ui.elements.chrome;

import com.crystalgui.core.property.ObservableList;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSet;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.table.TableColumn;
import com.crystalgui.ui.elements.table.TableView;

import javax.annotation.Nullable;

/**
 * Every problem in a document, as a sortable table — the Problems panel.
 *
 * <p>A dock panel by construction rather than by inheritance: {@code DockPanelRegistry} takes a factory
 * producing any {@link UIElement}, so this needs to know nothing about docking to live in one.</p>
 *
 * <h3>It reports a choice; it does not navigate</h3>
 *
 * <p>{@link #onProblemChosen} fires and that is all. The panel has no editor reference and deliberately
 * cannot get one: in a real workspace the problem you clicked may be in a file that is not open, so
 * "navigate" means <em>open the document, then reveal the row</em> — a workspace-level act. A panel that
 * held an editor would be able to serve only the file already on screen, which is the one case where the
 * error stripe already tells you everything.</p>
 *
 * <h3>Binding is re-pointable, and the old connection is dropped</h3>
 *
 * <p>{@link #bindTo} disconnects the previous set's listener. <b>This is hygiene, not correctness, and the
 * distinction is worth stating because it is easy to overclaim.</b> {@link #refresh} always reads from
 * {@link #bound}, so a leaked listener firing rebuilds the table from the <em>current</em> set and the
 * contents stay right. What it actually costs is retention — an abandoned {@link DiagnosticSet} keeps this
 * panel reachable — and work: every bind without a disconnect adds another listener, so a panel re-pointed
 * n times does n full rebuilds on every change to any set it has ever been shown.</p>
 *
 * <p>Neither effect is observable from outside without a test-only seam, so this line is deliberately
 * <b>not</b> covered by a test. An earlier one claimed to cover it by asserting the panel showed the wrong
 * contents after a rebind — which cannot happen, and the assertion passed with the disconnect deleted.</p>
 */
public class ProblemsPanel extends UIElement {

    public static final String PANEL_CLASS = "__problems__";
    public static final String CONTENT_CLASS = "__content__";
    public static final String TABLE_CLASS = "__problems-table__";
    public static final String EMPTY_CLASS = "__problems-empty__";

    /** The row a user chose — a double click, or Enter on the selection. Never fired for a mere
     * selection change: arrowing through a list is not a decision to go somewhere. */
    public final Signal.Value<Diagnostic> onProblemChosen = new Signal.Value<>();

    private final UIElement content = new UIElement();
    private final ObservableList<Diagnostic> rows = new ObservableList<>();
    private final TableView<Diagnostic> table = new TableView<>(rows);
    private final UIText empty = new UIText("No problems");

    @Nullable
    private DiagnosticSet bound;
    @Nullable
    private Connection binding;

    public ProblemsPanel() {
        addClass(PANEL_CLASS);

        content.addClass(CONTENT_CLASS);
        // Marked internal exactly ONCE, while empty. markAsInternal() RECURSES, and TableView adds and
        // recycles its own rows -- stamping a populated subtree marks those internal too, after which
        // removeChild silently refuses them. That is the bug that put duplicate unclickable tabs in the
        // dock, and the wrapper is the same fix.
        addInternalChild(content);

        table.addClass(TABLE_CLASS);
        // 1-based, because every editor, compiler and person counts lines from one. The model is 0-based
        // and stays that way; this is the only place the two meet.
        // NAMED, not blank. IntelliJ shows no header over its severity icons, but this table always draws
        // a header row -- so a blank cell there is a sortable control with no label, no affordance and no
        // hint that clicking it did anything. Observed: the panel came up sorted alphabetically by
        // severity with nothing on screen to explain why, which reads as the ordering being wrong rather
        // than as a sort having been applied.
        table.addColumn(TableColumn.<Diagnostic>of("Severity", ProblemsPanel::severityLabel)
                .width(64f).sortable());
        table.addColumn(TableColumn.<Diagnostic>of("Problem", Diagnostic::message).flexible().sortable());
        table.addColumn(TableColumn.<Diagnostic>of("Line",
                d -> String.valueOf(d.start().row() + 1)).width(48f).sortable());
        content.addChild(table);

        empty.addClass(EMPTY_CLASS);
        empty.setHitTest(false);
        content.addChild(empty);

        table.onRowActivated.connect(index -> {
            if (index >= 0 && index < rows.size()) onProblemChosen.emit(rows.get(index));
        });

        refresh();
    }

    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    public TableView<Diagnostic> table() {
        return table;
    }

    /** The rows currently shown, in table order. The surface a test asserts on. */
    public java.util.List<Diagnostic> visibleProblems() {
        return rows.asUnmodifiableList();
    }

    /** Points the panel at a document's problems, or at nothing. Safe to call repeatedly. */
    public ProblemsPanel bindTo(@Nullable DiagnosticSet set) {
        if (binding != null) {
            binding.disconnect();
            binding = null;
        }
        bound = set;
        if (set != null) binding = set.onChanged.connect(this::refresh);
        refresh();
        return this;
    }

    /** Drops the listener on whatever this was bound to. A panel discarded without this keeps its
     * document's set alive through the connection. */
    public void dispose() {
        bindTo(null);
        table.dispose();
    }

    private void refresh() {
        rows.clear();
        if (bound != null) {
            for (Diagnostic diagnostic : bound.all()) rows.add(diagnostic);
        }
        // The empty state and the table swap, rather than the table simply being blank. A header row over
        // nothing reads as "loading" or "broken"; a sentence reads as "there is nothing wrong".
        boolean anything = !rows.isEmpty();
        table.generalStyle(g -> g.opacity(anything ? 1f : 0f));
        empty.generalStyle(g -> g.opacity(anything ? 0f : 1f));
    }

    /** Words, not symbols — this column is read, and a glyph the bundled font lacks draws a blank
     * advance rather than failing. */
    private static String severityLabel(Diagnostic diagnostic) {
        DiagnosticSeverity severity = diagnostic.severity();
        if (severity == DiagnosticSeverity.ERROR) return "Error";
        if (severity == DiagnosticSeverity.WARNING) return "Warning";
        if (severity == DiagnosticSeverity.INFORMATION) return "Note";
        return "Hint";
    }
}
