package com.crystalgui.ui.elements.chrome;

import com.crystalgui.core.property.ObservableList;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSet;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.list.ListRenderer;
import com.crystalgui.ui.elements.list.ListView;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Every problem in a document, one per line — the Problems panel.
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
 * {@link #bound}, so a leaked listener firing rebuilds the list from the <em>current</em> set and the
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
    public static final String LIST_CLASS = "__problem-list__";
    /** One problem. @see #ProblemsPanel */
    public static final String ROW_CLASS = "__problem__";
    /** The severity glyph — a class the sheet turns into an icon and a colour. */
    public static final String ICON_CLASS = "__severity__";
    public static final String MESSAGE_CLASS = "__message__";
    /** The trailing {@code :591}. Dimmer, because it is where to look rather than what is wrong. */
    public static final String LINE_CLASS = "__line__";
    /** Severity, as a class. Same convention as the notification cards. */
    public static final String SEVERITY_PREFIX = "severity-";
    public static final String EMPTY_CLASS = "__problems-empty__";

    /** The row a user chose — a double click, or Enter on the selection. Never fired for a mere
     * selection change: arrowing through a list is not a decision to go somewhere. */
    public final Signal.Value<Diagnostic> onProblemChosen = new Signal.Value<>();

    private final UIElement content = new UIElement();
    private final ObservableList<Diagnostic> rows = new ObservableList<>();
    private final ListView<Diagnostic> list = new ListView<>(rows);
    private final UIText empty = new UIText("No problems");

    @Nullable
    private DiagnosticSet bound;
    @Nullable
    private Connection binding;

    public ProblemsPanel() {
        addClass(PANEL_CLASS);

        content.addClass(CONTENT_CLASS);
        // Marked internal exactly ONCE, while empty. markAsInternal() RECURSES, and ListView adds and
        // recycles its own rows -- stamping a populated subtree marks those internal too, after which
        // removeChild silently refuses them. That is the bug that put duplicate unclickable tabs in the
        // dock, and the wrapper is the same fix.
        addInternalChild(content);

        list.addClass(LIST_CLASS);
        // ONE ROW PER PROBLEM, not three columns — IntelliJ's shape, and the columns were overhead for
        // what is really one line: the severity is an icon, the message is the line, and the row it is on
        // is a dim suffix. A header over three sortable columns is a lot of chrome to say "warning, line
        // 143", and the Line column spent most of its width on a number four characters long.
        list.setItemHeight(16f);
        // A PROBLEM IS NOT WORTH HALF-READING. A driver's message names a line, a symbol and a reason, and
        // the part that gets truncated in a narrow panel is the end -- which is the part that says what is
        // wrong. Scrolling sideways is the same answer the project tree already gives a long filename.
        list.setHorizontalScrolling(true);
        list.setRenderer(new ProblemRenderer());
        content.addChild(list);

        empty.addClass(EMPTY_CLASS);
        empty.setHitTest(false);
        content.addChild(empty);

        list.onRowActivated.connect(index -> {
            if (index >= 0 && index < rows.size()) onProblemChosen.emit(rows.get(index));
        });

        refresh();
    }

    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    public ListView<Diagnostic> list() {
        return list;
    }

    /** The rows currently shown, in list order. The surface a test asserts on. */
    public List<Diagnostic> visibleProblems() {
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
        list.dispose();
    }

    private void refresh() {
        // ONE ANNOUNCEMENT, not one per row. This was clear() followed by add() per diagnostic, so a set of
        // n problems emitted n+1 changes and the ListView rebuilt its realised window n+1 times for a
        // single compile. The same fix TreeView needed, for the same reason.
        rows.setAll(bound == null ? List.of() : bound.all());
        // The empty state and the list swap, rather than the list simply being blank. An empty viewport
        // reads as "loading" or "broken"; a sentence reads as "there is nothing wrong".
        boolean anything = !rows.isEmpty();
        list.generalStyle(g -> g.opacity(anything ? 1f : 0f));
        empty.generalStyle(g -> g.opacity(anything ? 0f : 1f));
    }

    /**
     * The row: severity glyph, message, and the line it is on.
     *
     * <p>Built in {@code createTemplate} and only written into by {@code bind} — an element created during
     * bind lands after that frame's layout pass, which this engine has paid for three times over.</p>
     */
    private static final class ProblemRenderer implements ListRenderer<Diagnostic> {

        @Override
        public UIElement createTemplate() {
            UIElement row = new UIElement();
            row.addClass(ROW_CLASS);

            UIElement icon = new UIElement();
            icon.addClass(ICON_CLASS);
            icon.setHitTest(false);

            UIText message = new UIText("");
            message.addClass(MESSAGE_CLASS);
            message.setHitTest(false);

            UIText line = new UIText("");
            line.addClass(LINE_CLASS);
            line.setHitTest(false);
            line.forceSelfSizeWidth();

            row.addChild(icon);
            row.addChild(message);
            row.addChild(line);
            return row;
        }

        @Override
        public void bind(Diagnostic diagnostic, int index, UIElement template) {
            List<UIElement> parts = template.getChildren();
            // SWAPPED, never added: a template is a different problem every time the view recycles it, so
            // adding `severity-error` without removing `severity-warning` leaves both on the element and
            // the cascade resolves whichever happens to win — a random colour rather than a wrong one.
            parts.get(0).swapPrefixedClass(SEVERITY_PREFIX, SEVERITY_PREFIX + severityClass(diagnostic));
            ((UIText) parts.get(1)).setText(diagnostic.message());
            // OMITTED, not dashed, when there is nothing to point at. With the column gone there is no
            // empty cell to fill, so a graph's node-level problem simply ends after its message.
            ((UIText) parts.get(2)).setText(
                    diagnostic.hasPosition() ? ":" + (diagnostic.start().row() + 1) : "");
        }
    }

    /** The class the sheet keys the glyph and the colour off. */
    private static String severityClass(Diagnostic diagnostic) {
        DiagnosticSeverity severity = diagnostic.severity();
        if (severity == DiagnosticSeverity.ERROR) return "error";
        if (severity == DiagnosticSeverity.WARNING) return "warning";
        return "info";
    }
}
