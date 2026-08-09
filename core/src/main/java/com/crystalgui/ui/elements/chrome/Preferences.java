package com.crystalgui.ui.elements.chrome;

import com.crystalgui.core.settings.Setting;
import com.crystalgui.core.settings.Settings;
import com.crystalgui.core.settings.SettingsCategory;
import com.crystalgui.core.settings.SettingsLayer;
import com.crystalgui.core.settings.SettingsRegistry;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.StyleOrigin;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Dialog;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.config.ConfiguratorGroup;
import com.crystalgui.ui.elements.config.ConfiguratorPanel;
import com.crystalgui.ui.elements.config.SettingsConfigurator;
import com.crystalgui.ui.elements.tree.PathTreeSource;
import com.crystalgui.core.search.SearchMatcher;
import com.crystalgui.core.search.SearchQuery;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * The preferences window — a category tree on the left, that category's settings on the right.
 *
 * <h3>It owns almost nothing</h3>
 *
 * <p>{@link NavigatorView} is the shell, {@link PathTreeSource} builds the tree from setting ids,
 * {@link SettingsConfigurator} turns a declaration into a bound row and {@link ConfiguratorPanel} does the
 * groups. What is left here is the four questions only settings can answer: which ids to show, what a
 * query means, what a page contains, and how a section is titled.</p>
 *
 * <h3>A plain {@link Dialog} with a class, not a subclass of one</h3>
 *
 * <p>It was a subclass, briefly, and every {@code dialog …} rule in the sheet stopped applying: a widget's
 * cascade identity is its <b>tag</b>, so {@code Preferences extends Dialog} is a {@code preferences}. The
 * symptom was a title bar with no height and a close button stretched across it — the same failure
 * {@code AGENTS.md} records for {@code Dropdown extends Button}, and the reason {@link NavigatorView}
 * composes a {@code SplitView} rather than extending one.</p>
 *
 * <h3>Only what is writable at {@link SettingsLayer#USER}</h3>
 *
 * <p>{@code ShaderGraphSettings} declares its render queue {@code writableAt(DOCUMENT, MEMORY)} precisely
 * so it cannot become a global preference, and a window listing every registered declaration would put it
 * there — the exact failure {@code Setting.writableAt}'s own documentation describes.</p>
 *
 * <p><b>Nothing else enforces this.</b> {@code Settings.setRaw} takes a key rather than a declaration and
 * so cannot check, which means {@code writableAt} is a rule this window keeps and the store does not.</p>
 *
 * <h3>Changes apply immediately</h3>
 *
 * <p>VS Code's model rather than IntelliJ's OK/Apply/Cancel, and the second reason is the real one: a
 * buffered dialog needs a second copy of every value plus a revert path, and settings here are already
 * observable, so a checkbox re-sorts the tree as you watch. No {@code UndoStack} is passed, which is not
 * an omission — {@link SettingsLayer#USER} is not an undoable layer, and Ctrl+Z changing your font size
 * instead of undoing your work is the failure that boundary exists to prevent.</p>
 */
public final class Preferences {

    public static final String TITLE = "Preferences";

    /** The variant class on the dialog. @see Preferences the class note on why this is not a tag */
    public static final String DIALOG_CLASS = "__preferences__";

    /** On each page, so the sheet can size it without reaching through the navigator. */
    public static final String PANEL_CLASS = "__preferences-panel__";

    /** Shown for a category that holds no settings of its own. */
    public static final String EMPTY_PAGE_TEXT = "Select a category";

    private final Dialog dialog = new Dialog(TITLE);
    private final NavigatorView<String> navigator = new NavigatorView<>();
    private final Settings settings;
    private final List<Setting<?>> shown = new ArrayList<>();
    private final PathTreeSource paths;

    public Preferences(Settings settings) {
        this.settings = settings;
        dialog.addClass(DIALOG_CLASS);
        dialog.getContent().addChild(navigator);

        paths = new PathTreeSource(editableIds(), SettingsCategory::isPage, SettingsCategory::titleOf);

        navigator.setSource(paths);
        navigator.setTitleFunction(paths::title);
        navigator.setTrailFunction(this::trailFor);
        navigator.setMatcher(this::matches);
        navigator.setPageFactory(this::buildPage);
        navigator.setPlaceholder(new UIText(EMPTY_PAGE_TEXT));

        List<String> roots = paths.roots();
        if (!roots.isEmpty()) navigator.navigateTo(roots.get(0));
    }

    /** Opens it centred and <b>not</b> modal. Escape closes it while focus is inside — see {@link Dialog}. */
    public static Preferences open(UIWindow window, Settings settings) {
        Preferences preferences = new Preferences(settings);
        Dialog dialog = preferences.dialog;
        window.addOverlay(dialog, null);
        dialog.show();
        // PROMOTED though not modal: `show` leaves a dialog in normal flow, and against a root that
        // refuses public children that means a zero-sized overlay layer -- every position write then
        // clamps to the corner and it can be neither centred, dragged nor resized.
        dialog.addToTopLayer();
        dialog.onClosed.connect(dialog::removeFromTopLayer);
        centre(window, dialog);
        preferences.navigator.giveFocus();
        return preferences;
    }

    /**
     * Puts the dialog in the middle, once it has a size to be the middle of.
     *
     * <p>It cannot be done at open time: nothing has laid out, so the width is zero and the "centre" is
     * the top-left corner. Held invisible for the frame in between rather than allowed to appear in the
     * corner and jump — and the {@code opacity} candidate is <b>removed</b> rather than set back to 1, so
     * the sheet keeps ownership of how a dialog appears.</p>
     */
    private static void centre(UIWindow window, Dialog dialog) {
        StyleGroup.importantPipeline(dialog.getStyle().getGeneralGroup(), g -> g.opacity(0f));
        window.registerTicker(delta -> {
            if (dialog.getAttachedWindow() == null) return false;
            float width = dialog.getRuntimeCache().getWidth();
            float height = dialog.getRuntimeCache().getHeight();
            if (width <= 0f || height <= 0f) return true;   // not laid out yet; look again next frame
            dialog.moveTo(Math.max(0f, (window.getScreenWidth() - width) / 2f),
                    Math.max(0f, (window.getScreenHeight() - height) / 2f));
            dialog.getStyle().removeCandidates(StylePropertyRegistry.OPACITY,
                    slot -> slot.origin() == StyleOrigin.IMPORTANT);
            return false;
        });
    }

    // ── The four settings-shaped questions ──────────────────────────────────────────────────────

    /** Which ids the tree is built from. @see Preferences the note on {@code writableAt} */
    private static List<String> editableIds() {
        List<String> ids = new ArrayList<>();
        for (Setting<?> setting : SettingsRegistry.get().all()) {
            if (setting.isWritableAt(SettingsLayer.USER)) ids.add(setting.getId());
        }
        return ids;
    }

    /** Titles from the root down to {@code path} — what the breadcrumb draws. */
    private List<String> trailFor(String path) {
        List<String> trail = new ArrayList<>();
        StringBuilder walked = new StringBuilder();
        for (String segment : path.split("[.]")) {
            if (walked.length() > 0) walked.append('.');
            walked.append(segment);
            trail.add(paths.title(walked.toString()));
        }
        return trail;
    }

    /**
     * Whether anything at or under {@code path} matches the query.
     *
     * <p>Matching the tree's own titles alone would make the search decorative — typing a setting's name
     * would find nothing, which is the one thing somebody opening a search box is trying to do. So labels
     * and descriptions are searched too, and the node <em>containing</em> a hit is what survives; keeping
     * the path to a deep match reachable is {@code FilteredTreeSource}'s job.</p>
     */
    private boolean matches(String path) {
        // THE NAVIGATOR'S OWN QUERY, options and all. Rebuilding one from `query()` -- which is what this
        // did -- drops Match Case, Words and Regex, so the toggles were live in the bar and inert here.
        SearchQuery query = navigator.parsedQuery();
        if (query == null || query.isEmpty()) return true;
        if (SearchMatcher.match(query, paths.title(path), 0) != null) return true;
        // DIRECTLY UNDER, not `idsUnder`. The comment above already says keeping the path to a deep match
        // reachable is FilteredTreeSource's job -- and it is, via its own descendant walk. Doing it here as
        // well does not merely duplicate the work, it changes the ANSWER: the source has two branches, and
        // a node whose own predicate is true "keeps its whole subtree, unfiltered" (its words). So a
        // recursive matcher told it `Editor` had matched, and `ge` listed Appearance and Code Style beside
        // General because they are Editor's children.
        //
        // Answering only for this node restores the distinction the source is built around: matched
        // yourself, and you bring your subtree; kept only because something beneath you matched, and you
        // bring only the branches that did. VS Code's tree filter draws the same line (Recurse vs Visible).
        for (String id : paths.idsDirectlyUnder(path)) {
            Setting<?> setting = SettingsRegistry.get().get(id);
            if (setting == null) continue;
            if (SearchMatcher.match(query, setting.getLabel(), 0) != null) return true;
            // DESCRIPTIONS ARE NOT SEARCHED. A label is the setting's NAME -- the thing somebody types.
            // A description is prose about it, and a short query hits prose constantly: `ge` matched
            // "arrangement", "Percentage" and "change it", so Appearance, Shaders and Workbench all
            // appeared under a two-letter query with nothing on screen explaining why.
            //
            // The cost of a filtered TREE, and the reason this differs from VS Code: its settings search is
            // a RANKED LIST, so a description-only hit sinks to the bottom where it costs nothing. A tree
            // shows every survivor as an equal, so an unexplainable row is indistinguishable from a bug --
            // and it was reported as one twice. IntelliJ indexes descriptions and tells you it did; until
            // there is somewhere to say so, matching what is written on the row is the honest answer.
        }
        return false;
    }

    /** Every id on this page or on a page beneath it. */
    private List<String> idsUnder(String path) {
        List<String> ids = new ArrayList<>(paths.idsDirectlyUnder(path));
        for (String child : paths.children(path)) ids.addAll(idsUnder(child));
        return ids;
    }

    /**
     * One page: the settings declared directly on this node, under a heading per section.
     *
     * <p>Null when the node holds nothing of its own — a parent category whose settings all live in its
     * children. {@link PageStack} shows the placeholder for those rather than an empty box.</p>
     */
    @Nullable
    private UIElement buildPage(String path) {
        List<String> ids = paths.idsDirectlyUnder(path);
        if (ids.isEmpty()) return null;

        ConfiguratorPanel panel = new ConfiguratorPanel();
        panel.addClass(PANEL_CLASS);
        Map<String, UIElement> hostsBySection = new LinkedHashMap<>();
        for (String id : ids) {
            Setting<?> setting = SettingsRegistry.get().get(id);
            if (setting == null) continue;
            UIElement host = hostsBySection.computeIfAbsent(paths.sectionOf(id), section -> {
                if (section.isEmpty()) return panel;
                ConfiguratorGroup group = panel.group(sectionTitle(path, section));
                // ADDED, not merely built: `group()` deliberately does not attach, and forgetting it
                // leaves every row parented to a detached element -- a page that reports a full set of
                // controls and renders nothing.
                panel.addChild(group);
                return group.content();
            });
            if (SettingsConfigurator.addRow(panel, host, settings, SettingsLayer.USER, setting, null)
                    != null) {
                shown.add(setting);
            }
        }
        return panel;
    }

    /** A declared title if the section has one, else the segment made legible. */
    private static String sectionTitle(String path, String section) {
        String declared = SettingsCategory.titleOf(path + "." + section);
        return declared != null ? declared
                : PathTreeSource.prettify(PathTreeSource.lastSegment(section));
    }

    // ── Parts ───────────────────────────────────────────────────────────────────────────────────

    public Dialog dialog() {
        return dialog;
    }

    public Settings settings() {
        return settings;
    }

    public NavigatorView<String> navigator() {
        return navigator;
    }

    public PathTreeSource paths() {
        return paths;
    }

    /** The page on screen, or null when the category holds nothing of its own. */
    @Nullable
    public UIElement page() {
        return navigator.pages().built(navigator.pages().current());
    }

    /**
     * Every declaration a page has actually put a row in for.
     *
     * <p>Pages are built lazily, so this grows as categories are visited. Recording a setting the filter
     * merely <em>passed</em> would make it a restatement of the filter rather than evidence of what was
     * built — which is how an entirely empty window once passed its own test.</p>
     */
    public List<Setting<?>> shownSettings() {
        return new ArrayList<>(shown);
    }
}
