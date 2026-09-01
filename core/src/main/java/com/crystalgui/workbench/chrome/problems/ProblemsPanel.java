package com.crystalgui.workbench.chrome.problems;

import com.crystalgui.workbench.chrome.preferences.Preferences;
import com.crystalgui.core.collection.tree.FilteredTreeSource;
import com.crystalgui.core.collection.tree.TreeRow;
import com.crystalgui.core.command.ClipboardCommands;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.data.DataProvider;
import com.crystalgui.core.search.SearchMatch;
import com.crystalgui.core.search.SearchMatcher;
import com.crystalgui.core.search.SearchQuery;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.signal.ConnectionGroup;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.fs.Resource;
import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.render.texture.asset.FileIconTheme;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.diagnostic.DiagnosticTag;
import com.crystalgui.text.diagnostic.Markers;
import com.crystalgui.text.diagnostic.ProblemNode;
import com.crystalgui.text.diagnostic.ProblemsTreeSource;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.input.UIInputHandler;
import com.crystalgui.ui.service.AnchoredPlacement;
import com.crystalgui.widget.collection.tree.TreeRenderer;
import com.crystalgui.widget.collection.tree.TreeSearch;
import com.crystalgui.widget.collection.tree.TreeView;
import com.crystalgui.widget.overlay.ContextMenu;
import com.crystalgui.widget.overlay.Menu;
import com.crystalgui.widget.overlay.MenuItem;
import com.crystalgui.widget.text.UIText;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Every problem in the workspace, grouped by file — the Problems panel.
 *
 * <p>VS Code's {@code vs/workbench/contrib/markers} view, over our {@link Markers} index.</p>
 *
 * <h3>Workspace-wide, which is what the resource index was for</h3>
 *
 * <p>This used to bind to a single {@code DiagnosticSet} — the active document's — so it could only ever
 * show the file already on screen, which is the one case where the editor's own error stripe already tells
 * you everything. Its own javadoc said as much while it had no way to do better. {@link Markers} gives it
 * the list of every file with something to report, so the panel is now the thing you consult to find out
 * <em>where</em> the problems are rather than a second opinion about the file you are looking at.</p>
 *
 * <h3>It reports a choice; it still does not navigate</h3>
 *
 * <p>{@link #onProblemChosen} fires with a {@link ProblemNode} and that is all. The panel has no editor and
 * deliberately cannot get one: the problem you clicked is now routinely in a file that is <b>not open</b>,
 * so "navigate" means <em>open the document, then reveal the row</em> — a workspace-level act. The node
 * carries its resource for exactly that reason.</p>
 *
 * <h3>Filtering lives in the source</h3>
 *
 * <p>Because it changes the tree's shape rather than its paint — see {@link ProblemsTreeSource}. A file
 * whose only error is filtered out has to stop being a row.</p>
 */
public class ProblemsPanel extends UINode implements DataProvider {

    public static final Name NAME = Name.of("problemspanel");

    /** The scope tabs, placed on the container's title line rather than inside this panel. */
    /**
     * The controls this panel contributes to its container's header.
     *
     * <p>Not an {@code @Override} yet: {@code HeaderContributor} is typed on {@code UIElement} and
     * its only consumer is {@code ViewContainer}, which is a 6.7 class. The METHOD is what a
     * container calls, so nothing is lost by the interface arriving with the thing that reads it —
     * and declaring a second interface now would mean guessing its shape a batch early.</p>
     */
    public UINode headerContent() {
        return tabs;
    }


    public static final String PANEL_CLASS = "__problems__";
    public static final String CONTENT_CLASS = "__content__";
    public static final String LIST_CLASS = "__problem-list__";
    /** One row — a file heading or a problem under it. */
    public static final String ROW_CLASS = "__problem__";
    /** On a file heading, so the sheet can weight it against the problems beneath. */
    public static final String FILE_CLASS = "__problem-file__";
    /** The severity glyph, or the file's icon — a class the sheet turns into a drawable. */
    public static final String ICON_CLASS = "__severity__";
    public static final String MESSAGE_CLASS = "__message__";
    /** The trailing {@code :591}, or a file's folder. Dimmer: where to look, not what is wrong. */
    public static final String LINE_CLASS = "__line__";
    /** How many problems are in a file, on its heading. */
    public static final String COUNT_CLASS = "__problem-count__";
    /** Severity, as a class. Same convention as the notification cards. */
    public static final String SEVERITY_PREFIX = "severity-";
    /** The chevron. Its own hit target, so a file folds on one click. */
    public static final String TWISTY_CLASS = "__twisty__";
    /** The file heading's icon, from the same theme the project tree uses. */
    public static final String FILETYPE_PREFIX = "filetype-";
    public static final String EMPTY_CLASS = "__problems-empty__";
    /** Rendering, not severity — @see DiagnosticTag */
    public static final String TAG_UNNECESSARY = "tag-unnecessary";
    public static final String TAG_DEPRECATED = "tag-deprecated";

    /**
     * The row a user chose — a double click, or Enter on the selection.
     *
     * <p>Never fired for a mere selection change: arrowing through a list is not a decision to go
     * somewhere. Carries the node rather than the diagnostic, so a listener knows which file to open.</p>
     */
    public final Signal.Value<ProblemNode> onProblemChosen = new Signal.Value<>();

    /**
     * How a command finds this panel — the data seam, rather than a static or a constructor argument.
     *
     * <p>The three rows of this panel's context menu are {@code Command}s, because {@code MenuBuilder} is
     * the only thing that turns commands into rows and a second builder would drift within a release. A
     * command resolves its subject by walking outward from the element the menu was opened on, so the
     * panel has to be answerable along that walk. That is what a {@link DataKey} is for.</p>
     */
    public static final DataKey<ProblemsPanel> PROBLEMS_PANEL =
            // `.new` UNTIL 6.9, the convention 6.3 set for `menuBar.new` and 6.4 for `graphView.new`.
            // A DataKey is interned by NAME and its TYPE is what it names, so the old engine's copy
            // and this one cannot share a key: `create` throws the moment both classes initialise,
            // which is any test that touches both. The OLD name is the one that must not move --
            // `ContextKeys.find` resolves a key by name out of a `when` expression.
            DataKey.create("problemsPanel.new", ProblemsPanel.class);

    /**
     * The row the menu was opened on — <b>not</b> the selection.
     *
     * <p>A context menu resolves against what was CLICKED while a menu bar resolves against what has
     * FOCUS; opposite rules and both right, because a right-click names its subject. Held here rather
     * than read back from the list because the list's own context row is private to its Copy support and
     * is cleared as soon as that runs.</p>
     */
    @Nullable
    private ProblemNode contextNode;

    /** What the context menu's rows act on, or null when the menu was opened over nothing. */
    @Nullable
    public ProblemNode contextProblem() {
        return contextNode;
    }

    @Override
    public Object getData(DataKey<?> key) {
        if (key == PROBLEMS_PANEL) return this;
        // SUPER LAST, so the generic ELEMENT answer stays reachable -- the rule every override follows.
        // No super: DataProvider is an interface the node does not implement, so an unanswered
        // key is null -- which is the walk's own signal to try the next step out.
        return null;
    }

    /** Opens the quick fixes for the right-clicked problem, in the editor showing it. */
    public boolean showQuickFixesForContext() {
        ProblemNode node = contextNode;
        if (node == null || node.isFile() || node.diagnostic() == null) return false;
        // ONE SIGNAL, not both. The quick-fixes handler navigates as part of what it does -- the actions
        // are resolved from an offset, so it has to open and position before it can ask -- and emitting
        // onProblemChosen as well would run that same open twice.
        onQuickFixesRequested.emit(node);
        return true;
    }

    /**
     * Asked for rather than performed, because this panel has no editor.
     *
     * <p>The same arrangement {@link #onProblemChosen} already documents: navigating to a problem is a
     * workspace-level act, and a panel that reached for an editor would be reaching past the host that
     * owns one. Alt+Enter in the editor is the same list; this is only the route to it.</p>
     */
    public final Signal.Value<ProblemNode> onQuickFixesRequested = new Signal.Value<>();

    private final UINode content = new UINode();
    private final UIText empty = new UIText("No problems have been detected in the workspace");

    @Nullable
    private ProblemsTreeSource source;

    /** Headings already auto-opened once. @see #openNewHeadings */
    private final Set<ProblemNode> seenHeadings = new HashSet<>();

    /** The shared search component, rebuilt with the tree. @see TreeSearch */
    @Nullable
    private TreeSearch<ProblemNode> search;
    @Nullable
    private TreeView<ProblemNode> tree;

    private final ConnectionGroup binding = new ConnectionGroup();

    /**
     * The view-options strip and its menu.
     *
     * <p>Built eagerly rather than on first press, for the reason {@code MainPreviewPanel} records about
     * its own: a toggle whose state lives in a menu that does not exist yet is a toggle nothing can
     * inspect without simulating a click.</p>
     */
    private void buildHead() {
        viewOptions.addClass(VIEW_OPTIONS_CLASS);
        viewOptions.setFocusPolicy(FocusPolicy.CLICK);
        viewOptions.onMouseDown.attachListener((element, event) -> {
            event.stopPropagation();
            openViewMenu(event.getPosition().x(), event.getPosition().y());
        }, false, true);
        // THE DROPDOWN CORNER, as its own element rather than a second overlay: `overlay` is one drawable,
        // and IntelliJ's gutter mark sits in the icon's bottom-right corner rather than replacing it. It is
        // what says the eye opens a menu instead of toggling something.
        gutterMark.addClass(GUTTER_MARK_CLASS);
        gutterMark.setHitTest(false);
        viewOptions.append(gutterMark);

        head.addClass(HEAD_CLASS);
        head.append(viewOptions);
        // A COLUMN OF [tabs, [gutter | tree]] -- the tabs span the panel and the gutter runs beside the
        // tree only, which is IntelliJ's arrangement. The body exists because those two axes cannot be one
        // element.
        body.addClass(BODY_CLASS);
        body.append(head);
        buildTabs();
        append(body);

        // CHECKABLE through the MENU rather than the item, which is what reserves the mark gutter for
        // every row -- an item that made itself checkable would sit indented against its neighbours.
        errorsItem = viewMenu.addCheckableItem(SHOW_ERRORS);
        warningsItem = viewMenu.addCheckableItem(SHOW_WARNINGS);
        infosItem = viewMenu.addCheckableItem(SHOW_INFOS);
        // NO "Show Active File Only" ROW. Scope is the tab strip -- see FILE_TAB. Leaving it here as well
        // would be two controls for one piece of state, which is the arrangement where they disagree.
        viewMenu.onItemActivated.connect(item -> applyViewChoice(item.getText()));
        // Must be IN the tree to be promoted to the top layer — a Menu is a Popover, and an unparented one
        // has nothing to promote from.
        append(viewMenu);
        syncViewMenu();
    }

    /**
     * IntelliJ's scope tabs: {@code File} with a count, and {@code Project Errors}.
     *
     * <p>Plain elements rather than a {@code TabView}: that widget owns panes and switches between them,
     * and there is one tree here shown two ways. A tab strip that swapped content would mean two trees and
     * two expansion states for one question.</p>
     */
    private void buildTabs() {
        tabs.addClass(TABS_CLASS);
        buildTab(fileTab, FILE_TAB, true).append(fileCount);
        fileCount.addClass(TAB_COUNT_CLASS);
        fileCount.setHitTest(false);
        buildTab(projectTab, PROJECT_TAB, false);
        tabs.append(fileTab);
        tabs.append(projectTab);
        // NOT added here: the container puts these on its title line. @see HeaderContributor
        syncTabs();
    }

    private UINode buildTab(UINode tab, String label, boolean fileScope) {
        tab.addClass(TAB_CLASS);
        tab.setFocusPolicy(FocusPolicy.CLICK);
        UIText text = new UIText(label);
        text.setHitTest(false);
        tab.append(text);
        tab.onMouseDown.attachListener((element, event) -> {
            event.stopPropagation();
            setFileScope(fileScope);
        }, false, true);
        return tab;
    }

    /** The view menu, so a test can activate a row rather than reaching past it to the setter it calls.
     * Both of this panel's earlier bugs lived in a route that its tests stepped over. */
    public Menu viewMenu() {
        return viewMenu;
    }

    private void openViewMenu(float screenX, float screenY) {
        UIDocument window = document();
        if (window == null) return;
        // ROOT space, not physical pixels: a promoted menu's containing block is the root, so a raw
        // pointer position lands wherever that number falls in root coordinates.
        var at = AnchoredPlacement.pointerToRoot(window, screenX, screenY);
        viewMenu.showAt(at.x(), at.y(), null);
    }

    /** Resolved by label at activation time rather than captured per item — one listener, and a row added
     * later cannot be forgotten. */
    private void applyViewChoice(String label) {
        switch (label) {
            case SHOW_ERRORS -> setSeverityShown(DiagnosticSeverity.ERROR, !isShown(DiagnosticSeverity.ERROR));
            case SHOW_WARNINGS ->
                    setSeverityShown(DiagnosticSeverity.WARNING, !isShown(DiagnosticSeverity.WARNING));
            case SHOW_INFOS -> {
                // ONE ROW, TWO SEVERITIES. LSP separates INFORMATION from HINT and VS Code shows them
                // under one switch, because the distinction is about how they are drawn in the text
                // rather than about whether you want to see the list of them.
                boolean shown = !isShown(DiagnosticSeverity.INFORMATION);
                setSeverityShown(DiagnosticSeverity.INFORMATION, shown);
                setSeverityShown(DiagnosticSeverity.HINT, shown);
            }
            default -> { }
        }
        syncViewMenu();
    }

    private boolean isShown(DiagnosticSeverity severity) {
        return source == null || source.isShown(severity);
    }

    /** Writes the live filter state onto the menu, so opening it shows what is actually in force. */
    private void syncViewMenu() {
        errorsItem.setSelected(isShown(DiagnosticSeverity.ERROR));
        warningsItem.setSelected(isShown(DiagnosticSeverity.WARNING));
        infosItem.setSelected(isShown(DiagnosticSeverity.INFORMATION));
    }

    /**
     * Chooses the scope — IntelliJ's two tabs. {@code true} is {@link #FILE_TAB}.
     *
     * <p>Default is the file, which is IntelliJ's: the panel opens describing what you are looking at, and
     * the project tab is one click away. The empty state names the scope, so an empty {@code File} tab
     * cannot be misread as a clean project.</p>
     */
    public ProblemsPanel setFileScope(boolean fileOnly) {
        if (activeFileOnly == fileOnly) return this;
        activeFileOnly = fileOnly;
        showOnly(fileOnly ? activeResource : null);
        syncTabs();
        return this;
    }

    public boolean isFileScope() {
        return activeFileOnly;
    }

    /** Marks the tab in force and re-counts the file one. */
    private void syncTabs() {
        fileTab.swapPrefixedClass(TAB_SELECTED_CLASS, activeFileOnly ? TAB_SELECTED_CLASS : "");
        projectTab.swapPrefixedClass(TAB_SELECTED_CLASS, activeFileOnly ? "" : TAB_SELECTED_CLASS);
        // ONLY THE FILE TAB IS BADGED, which is IntelliJ's choice and the right one: the count that helps
        // is the one for the thing you are working on. A project total is what the status bar already says.
        int inFile = source == null || activeResource == null
                ? 0 : source.matching(activeResource).size();
        fileCount.setText(inFile == 0 ? "" : String.valueOf(inFile));
    }

    /**
     * Tells the panel which file is in front.
     *
     * <p>Held whether or not the filter is on, so switching it on narrows to what you are looking at now
     * rather than to whatever was in front when you last switched it off.</p>
     */
    public ProblemsPanel setActiveResource(@Nullable Resource resource) {
        if (Objects.equals(activeResource, resource)) return this;
        activeResource = resource;
        if (activeFileOnly) showOnly(resource);
        return this;
    }

    /**
     * Folds or unfolds a file — what the chevron asks for.
     *
     * <p>Straight through to {@link TreeView#requestToggle}, which owns the deferral: folding from inside
     * a press recycles the row the press landed on, and this panel hand-rolled that deferral twice and got
     * it wrong twice before it moved where it belonged.</p>
     */
    public void requestFold(ProblemNode file) {
        if (file != null && file.isFile() && tree != null) tree.requestToggle(file);
    }

    /** The strip holding the view-options button. */
    public static final String HEAD_CLASS = "__problems-head__";
    /** IntelliJ's eye — opens {@link #viewMenu}. */
    public static final String VIEW_OPTIONS_CLASS = "__view-options__";
    /** The row holding the gutter and the tree, below the tabs. */
    public static final String BODY_CLASS = "__problems-body__";
    /** IntelliJ's little corner mark saying the eye opens a menu. */
    public static final String GUTTER_MARK_CLASS = "__dropdown-gutter__";

    /**
     * The view menu's rows, verbatim from VS Code's {@code markersViewActions.ts} — same wording, same
     * order, minus the one that would be inert.
     *
     * <p>{@code Hide Excluded Files} is deliberately absent: it filters against the workspace's exclude
     * globs, and this engine has none, so the row would be a switch that does nothing. The rest map one
     * to one onto filters the source already applies.</p>
     */
    private static final String SHOW_ERRORS = "Show Errors";
    private static final String SHOW_WARNINGS = "Show Warnings";
    /** One row for both {@code INFORMATION} and {@code HINT} — "Infos" is VS Code's single bucket. */
    private static final String SHOW_INFOS = "Show Infos";

    /**
     * The two scopes, as IntelliJ's tabs rather than VS Code's menu item.
     *
     * <p>VS Code spells this {@code Show Active File Only} inside the filter menu; IntelliJ makes it two
     * tabs across the top, and the tabs are better for the same reason a tab is better than a checkbox
     * anywhere — <b>the current scope is readable without opening anything</b>. A filter hidden in a menu
     * is a mode you can be in without knowing, and "the Problems panel is empty" means two very different
     * things depending on which one you are in.</p>
     */
    public static final String FILE_TAB = "File";
    public static final String PROJECT_TAB = "Project Errors";

    public static final String TABS_CLASS = "__problem-tabs__";
    public static final String TAB_CLASS = "__problem-tab__";
    /** On the tab in force. The selector engine has no {@code :checked} for a plain element. */
    public static final String TAB_SELECTED_CLASS = "__selected__";
    /** The count beside {@code File}, which is the only tab IntelliJ badges. */
    public static final String TAB_COUNT_CLASS = "__tab-count__";

    private final UINode body = new UINode();
    private final UINode head = new UINode();
    private final UINode gutterMark = new UINode();
    private final UINode viewOptions = new UINode();
    private final Menu viewMenu = new Menu();
    private MenuItem errorsItem;
    private MenuItem warningsItem;
    private MenuItem infosItem;

    private final UINode tabs = new UINode();
    private final UINode fileTab = new UINode();
    private final UINode projectTab = new UINode();
    private final UIText fileCount = new UIText("");

    /** The file in front, so {@link #ACTIVE_FILE_ONLY} has something to narrow to. */
    @Nullable
    private Resource activeResource;

    /** IntelliJ opens on the File tab, describing what you are looking at. */
    private boolean activeFileOnly = true;

    public ProblemsPanel() {
        super(NAME);
        addClass(PANEL_CLASS);
        content.addClass(CONTENT_CLASS);
        buildHead();
        // Into the BODY, beside the gutter — not straight onto the panel, which is now a column of
        // [tabs, body]. Added while empty: markAsInternal() RECURSES, and the tree adds and recycles its
        // own rows, so stamping a populated subtree marks those internal too and removeChild then silently
        // refuses them.
        body.append(content);

        empty.addClass(EMPTY_CLASS);
        empty.setHitTest(false);
        content.append(empty);
    }

    /** The tree, once something has been bound. Null before that. */
    @Nullable
    public TreeView<ProblemNode> tree() {
        return tree;
    }

    /** The filter this panel is showing through, or null before binding. */
    @Nullable
    /**
     * The search model — highlight through {@link TreeSearch}, filter through the source's own text filter.
     *
     * <p>Not {@code TreeSearch.byText} alone, because this panel <b>already knows how to narrow itself</b>:
     * {@code ProblemsTreeSource.setTextFilter} filters problems by message and keeps the file headings
     * that still have one. Handing the component a {@code FilteredTreeSource} instead would be a second
     * filter arguing with that one over the same list.</p>
     *
     * <p>It also fixes what highlight alone cannot: a problem's message lives on a row inside a file
     * heading, so until the heading is expanded there is nothing on screen to match. Filter mode reaches
     * it because the <em>source</em> does the narrowing.</p>
     */
    private TreeSearch.Model<ProblemNode> searchModel() {
        return new TreeSearch.Model<>() {
            @Nullable
            private SearchQuery query;

            @Override
            public void setQuery(SearchQuery next, boolean filtering) {
                query = next == null || next.isEmpty() ? null : next;
                if (source == null) return;
                source.setTextFilter(filtering ? query : null);
                // REVEALING IS THE COMPONENT'S, not this panel's. There used to be a loop here expanding
                // every file heading, written because filtering alone left a heading standing over a match
                // nobody could see. It is a property of Filter mode rather than of problems -- Preferences
                // needed the identical loop the moment it migrated -- so TreeSearch does it, and puts the
                // expansion back when the query clears, which this never did.
            }

            @Override
            public boolean isMatch(ProblemNode item) {
                return query != null && SearchMatcher.match(query, searchTextOf(item), 0) != null;
            }

            @Override
            public List<SearchMatch.Range> matchRanges(ProblemNode item) {
                if (query == null) return List.of();
                SearchMatch match = SearchMatcher.match(query, searchTextOf(item), 0);
                return match == null ? List.of() : match.ranges();
            }

            @Override
            public int descendantMatches(ProblemNode item) {
                // The file heading already shows its problem count; a second number beside it would be
                // two answers to one question.
                return 0;
            }
        };
    }

    /**
     * What a row is searched by — its message, or the file's name for a heading.
     *
     * <p>The one thing {@link TreeSearch} cannot know. A problem's <em>message</em> is what anybody is
     * looking for ("cannot find symbol"), and a file heading is searched by name so narrowing to one file
     * works the way it does in the project tree.</p>
     */
    private static String searchTextOf(ProblemNode node) {
        if (node.isFile()) {
            return node.resource() == null ? "" : node.resource().name();
        }
        return node.diagnostic() == null ? "" : node.diagnostic().message();
    }

    /** Opens the search box — Ctrl+F, and the panel's menu entry. */
    public void openFind() {
        if (search != null) search.open();
    }

    public boolean isFindOpen() {
        return search != null && search.isOpen();
    }

    /** The search component, or null before a workspace is bound. */
    @Nullable
    public TreeSearch<ProblemNode> search() {
        return search;
    }

    public ProblemsTreeSource source() {
        return source;
    }

    /**
     * Points the panel at a workspace's problems. Safe to call repeatedly.
     *
     * <p>The previous index's listener is dropped — hygiene rather than correctness, since a refresh reads
     * from the current source either way, but a leaked one retains an abandoned workspace and does a full
     * rebuild per bind on every change to any index this panel has ever shown.</p>
     */
    public ProblemsPanel bindTo(@Nullable Markers markers) {
        binding.disconnectAll();
        if (markers == null) {
            source = null;
            if (tree != null) {
                tree.removeSelf();
                tree = null;
            }
            refresh();
            return this;
        }
        seenHeadings.clear();
        source = new ProblemsTreeSource(markers);
        // THE SCOPE SURVIVES A REBIND. A fresh source defaults to the whole workspace, so without this the
        // File tab would stay lit while the tree quietly showed everything.
        source.setOnlyResource(activeFileOnly ? activeResource : null);
        // REBUILT RATHER THAN RE-POINTED: a TreeView takes its source at construction and offers no way to
        // swap one. Rebinding is a rare, deliberate act — a workspace opening or closing — so the cost is a
        // tree that is thrown away roughly never, and the alternative is a setter on TreeView whose only
        // caller would be this line.
        if (tree != null) tree.removeSelf();
        tree = new TreeView<>(source);
        tree.addClass(LIST_CLASS);
        tree.setItemHeight(16f);
        // A PROBLEM IS NOT WORTH HALF-READING. A driver's message names a line, a symbol and a reason,
        // and the part truncated in a narrow panel is the end -- which is the part that says what is
        // wrong. The project tree gives a long filename the same answer.
        tree.setHorizontalScrolling(true);
        tree.setRenderer(new ProblemRenderer());
        tree.onRowActivated.connect(this::chooseRow);
        content.append(tree);
        // SEARCH, FOR ONE LAMBDA. The bar, the two modes, the arrows, the counter, the keys and the
        // amber marking are all TreeSearch's -- this panel supplies only what a problem's searchable text
        // is, which is the seam VS Code calls IKeyboardNavigationLabelProvider and IntelliJ calls the
        // speed-search converter.
        //
        // Rebuilt with the tree, because it installs onto one: a component pointed at a tree that has
        // been replaced would drive rows nobody is showing.
        // HIGHLIGHT-ONLY, which is IntelliJ's speed search: ProblemsTreeSource already narrows itself
        // through setTextFilter and its own severity and scope rules, so handing the component a second
        // way to hide rows would be two filters disagreeing about one list.
        // IN THE PANEL'S OWN COLUMN, after the tabs and before the body -- not in `content`. The list and
        // the empty state are both position: absolute in there, stacked so neither one's arrival resizes
        // the panel, so an in-flow bar beside them shares their y and the first row is drawn over it.
        search = TreeSearch.installOn(tree, this, 0, searchModel(), node -> onProblemChosen.emit(node));

        // ITS OWN MENU, so the shared list one does not also open -- attach keeps one live menu per site,
        // but two attachments on one element are two listeners and both would fire.
        tree.suppressDefaultContextMenu();
        ContextMenu.attach(tree, CommandRegistry.global(), element -> {
            int index = tree.indexOfRowElement(element);
            if (index < 0) return null;
            TreeRow<ProblemNode> row = tree.rowAt(index);
            contextNode = row == null ? null : row.item();
            // NAMED WITHOUT BEING SELECTED, which is ContextMenu's own rule: selecting instead would
            // destroy the selection the menu was opened over.
            tree.setContextRow(index);
            if (contextNode == null || contextNode.isFile()) {
                // A HEADING IS NOT A PROBLEM. It has no diagnostic to fix, quote or jump to, so it gets
                // the plain Copy rather than three rows that would all be dead.
                return ContextMenu.builder().item(ClipboardCommands.COPY);
            }
            return ContextMenu.builder()
                    .item(ProblemsCommands.SHOW_QUICK_FIXES)
                    .item(ClipboardCommands.COPY, "Copy Problem Description")
                    .item(ProblemsCommands.JUMP_TO_SOURCE);
        });
        search.input().setPlaceholder("Search problems");
        binding.add(markers.onDidChange.connect(resource -> refresh()));
        refresh();
        return this;
    }

    /** Restricts the tree to one file — VS Code's "Show Active File Only". Null shows the workspace. */
    public ProblemsPanel showOnly(@Nullable Resource resource) {
        if (source == null) return this;
        if (Objects.equals(source.onlyResource(), resource)) return this;
        source.setOnlyResource(resource);
        refresh();
        return this;
    }

    /** Shows or hides one severity across the whole tree. */
    public ProblemsPanel setSeverityShown(DiagnosticSeverity severity, boolean shown) {
        if (source == null || source.isShown(severity) == shown) return this;
        source.setShown(severity, shown);
        refresh();
        return this;
    }

    /** Substring filter against every message. */
    public ProblemsPanel setTextFilter(@Nullable String text) {
        if (source == null) return this;
        source.setTextFilter(text);
        refresh();
        return this;
    }

    /** Every problem currently shown, in tree order. The surface a test asserts on. */
    public List<Diagnostic> visibleProblems() {
        List<Diagnostic> shown = new ArrayList<>();
        if (tree == null) return shown;
        for (TreeRow<ProblemNode> row : tree.visibleRows()) {
            if (!row.item().isFile()) shown.add(row.item().diagnostic());
        }
        return shown;
    }

    /** Every file currently shown, in tree order. */
    public List<Resource> visibleFiles() {
        List<Resource> shown = new ArrayList<>();
        if (tree == null) return shown;
        for (TreeRow<ProblemNode> row : tree.visibleRows()) {
            if (row.item().isFile()) shown.add(row.item().resource());
        }
        return shown;
    }

    /** Drops the listener on whatever this was bound to. */
    public void dispose() {
        bindTo(null);
    }

    private void chooseRow(int index) {
        if (tree == null || index < 0) return;
        TreeRow<ProblemNode> row = tree.rowAt(index);
        if (row == null) return;
        // A FILE HEADING IS NOT A DESTINATION. Activating one opens it, which is what a tree already does
        // with a twisty -- so choosing it would be a second way to spell "expand".
        if (row.item().isFile()) {
            tree.toggleExpanded(row.item());
            return;
        }
        onProblemChosen.emit(row.item());
    }

    private void refresh() {
        boolean anything = source != null && source.shownCount() > 0;
        if (tree != null) {
            tree.refresh();
            openNewHeadings();
            tree.setDisplayed(anything);
        }
        empty.setDisplayed(!anything);
        empty.setText(emptyMessage());
        syncTabs();
    }

    /**
     * Opens each file heading the first time it appears.
     *
     * <p><b>A problem list arrives to be read, not to be opened.</b> Both references show theirs expanded —
     * VS Code's Problems view and IntelliJ's Problems tool window — and the reason is that a collapsed
     * heading shows a filename and a count, which is what the status bar already says. The panel is opened
     * to see the messages.</p>
     *
     * <p><b>Only the first time.</b> A heading the user has collapsed must stay collapsed, and this runs on
     * every refresh — a diagnostic arriving, a tab switching, a filter changing — so re-opening whatever is
     * closed would make a heading impossible to fold at all. {@code seenHeadings} is what separates "new"
     * from "closed on purpose"; {@code ProblemNode} is a record, so a heading that comes and goes with the
     * same resource is the same key.</p>
     */
    private void openNewHeadings() {
        if (source == null || tree == null) return;
        List<ProblemNode> open = null;
        for (ProblemNode root : source.roots()) {
            if (!seenHeadings.add(root)) continue;
            if (open == null) open = new ArrayList<>(tree.expandedItems());
            open.add(root);
        }
        if (open != null) tree.setExpandedItems(open);
    }

    /**
     * Which kind of empty this is — and there are now three.
     *
     * <p>An empty tree in the {@code File} tab, an empty tree in {@code Project Errors}, and a tree
     * filtered to nothing are the same picture and completely different news; only one of them is worth
     * celebrating. Saying which is what stops a scope or a filter reading as "everything got fixed", and
     * it is the reason the scope is a visible tab rather than a checkbox in a menu.</p>
     */
    private String emptyMessage() {
        if (source == null) return "No problems have been detected in the workspace";
        if (isNarrowed(source)) return "No problems match the current filter";
        if (activeFileOnly) {
            return activeResource == null
                    ? "No file is open"
                    : "No problems in " + activeResource.name();
        }
        return "No problems have been detected in the workspace";
    }

    /** Whether anything BUT the scope is narrowing the tree — the scope has its own wording. */
    private static boolean isNarrowed(ProblemsTreeSource source) {
        if (!source.textFilter().isEmpty()) return true;
        for (DiagnosticSeverity severity : DiagnosticSeverity.values()) {
            if (!source.isShown(severity)) return true;
        }
        return false;
    }

    /**
     * One row, serving both levels.
     *
     * <p><b>One template, not two.</b> The view pools and recycles a single element per slot, so a row is a
     * file heading one frame and a problem the next — which means every slot a row can ever need is built in
     * {@code createTemplate} and only shown or hidden in {@code bind}. Creating one during bind lands it
     * after that frame's layout pass, which this engine has paid for three times over.</p>
     */
    private final class ProblemRenderer implements TreeRenderer<ProblemNode> {

        /**
         * What each realised row is currently showing.
         *
         * <p>Read by the twisty <b>at press time</b>, never captured into its listener: rows recycle as the
         * tree scrolls and a listener may only be attached once, so a captured node would keep folding
         * whichever file its slot was first used for. The same trap the editor's gutter arrows document.</p>
         */
        private final Map<UINode, ProblemNode> rowItems = new IdentityHashMap<>();

        /**
         * What Copy puts on the clipboard — <b>the message, and nothing else</b>.
         *
         * <p>The inherited default is {@code String.valueOf}, which for a record is its generated
         * {@code toString}, so copying a row produced the entire object graph: {@code ProblemNode[resource=…,
         * diagnostic=Diagnostic[start=508:8, end=…, code=1610612976, tags=[], related=[]]]}. What a person
         * copying an error wants is the sentence they are about to search for or paste into a report, which
         * is what both references put on the clipboard too.</p>
         *
         * <p>A heading copies its filename, for the same reason: it is what the row says.</p>
         */
        @Override
        public String copyTextFor(ProblemNode item) {
            if (item == null) return "";
            if (item.isFile()) return item.resource() == null ? "" : item.resource().name();
            Diagnostic diagnostic = item.diagnostic();
            return diagnostic == null ? "" : diagnostic.message();
        }

        @Override
        public UINode createTemplate() {
            UINode row = new UINode();
            row.addClass(ROW_CLASS);

            // THE ONE PART THAT KEEPS THE POINTER. Everything else refuses it so a press lands on the row —
            // click targeting takes the exact element hit and never walks up to a handler-bearing ancestor.
            // A chevron is a control in its own right, which is what lets a file fold on ONE click while
            // choosing a problem still takes two.
            UINode twisty = new UINode();
            twisty.addClass(TWISTY_CLASS);
            twisty.onMouseDown.attachListener((element, event) -> {
                ProblemNode node = rowItems.get(row);
                if (node == null || !node.isFile() || tree == null) return;
                event.stopPropagation();
                // DEFERRED TO THE NEXT FRAME, because setExpanded refreshes synchronously and this runs
                // from the press that folded the row -- so the refresh recycles every realised row
                // INCLUDING the one under the pointer, from inside its own listener. Collapsing left the
                // children on screen while the heading claimed to be shut: two files collapsed with three
                // problem rows still under them, one of them drawn twice. Expanding looked fine, which is
                // why it survived a screenshot. ProjectFileTree defers its chevron for the same reason.
                requestFold(node);
            }, false, false);

            // DOUBLE CLICK NAVIGATES; a single click only selects. It has to be raised here because
            // `onRowActivated` is Enter only -- its javadoc says so, and says a renderer raises the
            // pointer half from its own template. Without this the panel was keyboard-navigable and
            // completely inert to the mouse.
            //
            // Two clicks rather than one for the reason `FilesRenderer` already records: one press has to
            // mean "this is the row I am talking about", because a press is how you aim the selection, a
            // Shift-range, or anything a command resolves from it. Navigating on that same press means a
            // problem cannot be selected without also being jumped to.
            //
            // ONLY FOR A PROBLEM ROW. A file heading is not a destination -- chooseRow says as much and
            // folds it instead -- and the chevron already spells that.
            //
            // The keyboard guard is not optional: Space and Enter on a focused element synthesise the same
            // MouseEvent.Down a real click would, so without it Enter would activate twice.
            row.onMouseDown.attachListener((element, event) -> {
                if (event.getDetail() == UIInputHandler.KEYBOARD_DETAIL) return;
                if (event.getDetail() < 2) return;
                ProblemNode node = rowItems.get(row);
                if (node == null || node.isFile() || tree == null) return;
                int index = tree.indexOfRowElement(row);
                if (index >= 0) tree.onRowActivated.emit(index);
            }, false, false);

            UINode icon = new UINode();
            icon.addClass(ICON_CLASS);
            icon.setHitTest(false);

            UIText label = new UIText("");
            label.addClass(MESSAGE_CLASS);
            label.setHitTest(false);
            // MUST REPORT ITS OWN WIDTH, or there is nothing for the row to overflow with and the
            // horizontal range is always exactly the viewport -- so the bar never appears and a driver's
            // message is simply cut off, which is the one thing this panel scrolls sideways to avoid.
            // UIText latches whether it self-sizes from its FIRST measurement, before any rule here has
            // matched, so it has to be told in Java at construction. Same call, same reason, as the
            // project tree's label and the Blackboard's type column.

            UIText detail = new UIText("");
            detail.addClass(LINE_CLASS);
            detail.setHitTest(false);

            UIText count = new UIText("");
            count.addClass(COUNT_CLASS);
            count.setHitTest(false);

            row.append(twisty);
            row.append(icon);
            row.append(label);
            row.append(detail);
            row.append(count);
            return row;
        }

        @Override
        public void bind(ProblemNode item, TreeRow<ProblemNode> row, int index, UINode template) {
            rowItems.put(template, item);
            List<UINode> parts = template.children();
            UINode icon = parts.get(1);
            UIText label = (UIText) parts.get(2);
            UIText detail = (UIText) parts.get(3);
            UIText count = (UIText) parts.get(4);

            if (item.isFile()) {
                template.addClass(FILE_CLASS);
                // THE FILE'S OWN ICON, from the theme the project tree already uses -- a heading naming
                // water.glsl should look like water.glsl does everywhere else. The severity slot is
                // emptied rather than left, or a row recycled from a problem keeps its error glyph.
                icon.swapPrefixedClass(SEVERITY_PREFIX, SEVERITY_PREFIX + "file");
                String name = item.resource().name();
                FileIconTheme theme = FileIconTheme.getDefault();
                CgUiDrawable glyph = theme.drawableFor(name, false, false);
                // DEFAULT origin, exactly as the project tree writes it: the theme's JSON is a default the
                // cascade can beat, and writing it inline would make the icon the one part of a row a
                // stylesheet cannot touch. It also means the severity rules — which are STYLESHEET origin —
                // still win on a row recycled from a problem, so there is nothing to clear.
                StyleGroup.defaultPipeline(icon.getStyle().getGeneralGroup(),
                        g -> g.overlay(glyph == null ? CgUiDrawable.EMPTY : glyph));
                icon.swapPrefixedClass(FILETYPE_PREFIX, theme.classFor(name, false));
                label.setText(item.resource().name());
                detail.setText(folderOf(item.resource()));
                count.setDisplayed(true);
                int problems = source == null ? 0 : source.matching(item.resource()).size();
                count.setText(String.valueOf(problems));
                setTag(template, TAG_UNNECESSARY, false);
                setTag(template, TAG_DEPRECATED, false);
                return;
            }
            // The filetype class goes with the file row, or a problem row inherits its heading's glyph.
            icon.swapPrefixedClass(FILETYPE_PREFIX, "");
            template.removeClass(FILE_CLASS);
            Diagnostic diagnostic = item.diagnostic();
            // SWAPPED, never added: a template is a different row every time the view recycles it, so
            // adding `severity-error` without removing `severity-file` leaves both on the element and the
            // cascade resolves whichever happens to win.
            icon.swapPrefixedClass(SEVERITY_PREFIX, SEVERITY_PREFIX + severityClass(diagnostic));
            label.setText(diagnostic.message());
            // OMITTED, not dashed, when there is nothing to point at: a graph's node-level problem simply
            // ends after its message.
            detail.setText(diagnostic.hasPosition() ? ":" + (diagnostic.start().row() + 1) : "");
            count.setDisplayed(false);
            setTag(template, TAG_UNNECESSARY, diagnostic.hasTag(DiagnosticTag.UNNECESSARY));
            setTag(template, TAG_DEPRECATED, diagnostic.hasTag(DiagnosticTag.DEPRECATED));
        }
    }

    private static void setTag(UINode row, String cls, boolean present) {
        if (present) row.addClass(cls);
        else row.removeClass(cls);
    }

    /** The folder a file sits in, shown dim beside its name — VS Code's second column. */
    private static String folderOf(Resource resource) {
        String path = resource.path();
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "" : path.substring(0, slash);
    }

    /** The class the sheet keys the glyph and the colour off. */
    private static String severityClass(Diagnostic diagnostic) {
        DiagnosticSeverity severity = diagnostic.severity();
        if (severity == DiagnosticSeverity.ERROR) return "error";
        if (severity == DiagnosticSeverity.WARNING) return "warning";
        return "info";
    }
}
