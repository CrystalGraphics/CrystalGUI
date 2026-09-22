package com.crystalgui.desktop.launcher;

import com.crystalgui.core.property.ObservableList;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.app.Application;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.desktop.window.WindowIcon;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.collection.list.ListRenderer;
import com.crystalgui.widget.collection.list.ListView;
import com.crystalgui.widget.composite.SearchField;
import com.crystalgui.widget.overlay.Popover;
import com.crystalgui.widget.text.UIText;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <b>What can run</b> — every installed application, searchable, one click from launching.
 *
 * <pre>{@code
 * Launcher launcher = new Launcher();
 * launcher.showFor(startButton);
 * }</pre>
 *
 * <h3>It names no application</h3>
 *
 * <p>Everything here is an {@link ApplicationKind} read out of the desktop's own registry, so a mod
 * that ships an {@code ApplicationKinds} service appears in this list without editing a line of it —
 * which is the whole point of the manifest seam, and the reason this can live in {@code desktop} at
 * all. A launcher that named Crystal Editor would have to live above it and could never be reached
 * from the taskbar.</p>
 *
 * <h3>Running is not the same as installed</h3>
 *
 * <p>The taskbar beside it shows what <em>is</em> running; this shows what <em>can</em>. A single
 * instance already running is still listed, and activating it raises that one rather than starting a
 * second — the same thing a second {@code open} on macOS does, and what {@code ApplicationRegistry}
 * already does when asked to launch one twice.</p>
 */
public class Launcher extends Popover {

    public static final Name NAME = Name.of("launcher");

    public static final String ROW_CLASS = "__app-row__";
    public static final String ROW_NAME_CLASS = "__app-name__";
    public static final String ROW_RUNNING_CLASS = "__app-running__";
    public static final String EMPTY_CLASS = "__app-none__";

    private final SearchField search = new SearchField();
    private final ObservableList<ApplicationKind> shown = new ObservableList<>();
    private final ListView<ApplicationKind> list = new ListView<>(shown);
    private final UIText empty = new UIText("");

    public Launcher() {
        super(NAME);
        addClass("__launcher__");

        search.setPlaceholder("Search apps");
        search.onQueryChanged.connect(this::refilter);
        appendStructural(search);

        list.setRenderer(new AppRenderer());
        list.onRowActivated.connect(this::launchAt);
        appendStructural(list);

        empty.addClass(EMPTY_CLASS);
        appendStructural(empty);

        // REFILLED ON EVERY OPEN, never cached: an application registers when its jar's service runs,
        // and a list built once would be a snapshot of whatever had loaded the first time this opened.
        onConnected(this::refill);
    }

    public SearchField searchField() {
        return search;
    }

    public ListView<ApplicationKind> list() {
        return list;
    }

    /** What is listed right now, after the query. */
    public List<ApplicationKind> shown() {
        return shown.asUnmodifiableList();
    }

    /** Clears the query and rereads the registry — what opening it should always look like. */
    public void refill() {
        search("");
    }

    /**
     * Sets the query and applies it.
     *
     * <p>Separate from {@code searchField().setText(...)}, which is deliberately silent: a
     * {@code SearchField} emits {@code onQueryChanged} for a user's edit and not for a programmatic
     * write, so that setting a box's contents cannot start a feedback loop through whatever wrote it.
     * The launcher owns its query, so this is the door that both sets it and honours it — and it is
     * what a "type to search" gesture on the desktop would eventually call.</p>
     */
    public void search(String query) {
        search.setText(query == null ? "" : query);
        refilter();
    }

    private void refilter() {
        Desktop desktop = Desktop.ifPresent(document());
        List<ApplicationKind> matches = new ArrayList<>();
        if (desktop != null) {
            String query = search.getText().trim().toLowerCase(Locale.ROOT);
            for (ApplicationKind kind : desktop.applications().installed()) {
                if (matches(kind, query)) matches.add(kind);
            }
        }
        shown.setAll(matches);

        boolean nothing = matches.isEmpty();
        // TWO DIFFERENT EMPTIES. "Nothing installed" is a broken classpath and "no match" is a typo,
        // and a launcher that said the same thing for both would send somebody looking in the wrong
        // place for a missing jar.
        empty.setText(nothing
                ? (search.getText().isBlank() ? "No applications installed." : "No app matches that.")
                : "");
        search.setNotFound(nothing && !search.getText().isBlank());
    }

    /**
     * Whether {@code kind} answers {@code query}.
     *
     * <p>Substring rather than prefix, on the display name, the keywords and the id. Prefix matching
     * is what makes a launcher feel broken when somebody types the second word of a two-word name —
     * "profiler" has to find "Frame Profiler".</p>
     */
    private static boolean matches(ApplicationKind kind, String query) {
        if (query.isEmpty()) return true;
        if (kind.displayName().toLowerCase(Locale.ROOT).contains(query)) return true;
        if (kind.id().toLowerCase(Locale.ROOT).contains(query)) return true;
        for (String keyword : kind.keywordList()) {
            if (keyword.toLowerCase(Locale.ROOT).contains(query)) return true;
        }
        return false;
    }

    private void launchAt(@Nullable Integer index) {
        if (index == null || index < 0 || index >= shown.size()) return;
        ApplicationKind kind = shown.get(index);
        Desktop desktop = Desktop.ifPresent(document());
        if (desktop == null) return;
        // HIDE FIRST. Launching raises a window, and a popover still open over the thing it just
        // opened is the one frame everybody notices.
        hide();
        // THE WORKSPACE OVERLOAD, named explicitly: `null` alone is ambiguous between this and the
        // LaunchContext one, and a launcher opens an application with nothing asked of it.
        Workspace workspace = null;
        Application running = desktop.applications().launch(kind, workspace);
        if (running != null) running.activate();
    }

    /** Name on the left of its icon, a note on the right when it is already running. */
    private final class AppRenderer implements ListRenderer<ApplicationKind> {

        @Override
        public UIElement createTemplate() {
            UIElement row = new UIElement();
            row.addClass(ROW_CLASS);
            row.append(new WindowIcon());
            UIText name = new UIText("");
            name.addClass(ROW_NAME_CLASS);
            row.append(name);
            UIText note = new UIText("");
            note.addClass(ROW_RUNNING_CLASS);
            row.append(note);
            return row;
        }

        @Override
        public void bind(ApplicationKind item, int index, UIElement template) {
            List<UIElement> parts = template.children();
            if (parts.size() < 3) return;
            // THE WINDOW'S OWN ICON WIDGET, so an application with no icon gets the same coloured
            // monogram tile in the launcher that its window and taskbar entry already draw. One
            // application, one mark, wherever it appears.
            ((WindowIcon) parts.get(0)).show(item.icon(), item.displayName());
            ((UIText) parts.get(1)).setText(item.displayName());

            Desktop desktop = Desktop.ifPresent(document());
            boolean running = desktop != null && !desktop.applications().running(item).isEmpty();
            ((UIText) parts.get(2)).setText(running ? "running" : "");
        }

        @Override
        public String copyTextFor(ApplicationKind item) {
            return item.displayName();
        }
    }
}
