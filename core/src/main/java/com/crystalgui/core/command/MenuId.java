package com.crystalgui.core.command;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A place a menu is drawn — VS Code's {@code MenuId}, IntelliJ's action-group id.
 *
 * <h3>Why a menu needs a name</h3>
 *
 * <p>So that a widget can contribute to a menu it does not own. Today the explorer's context menu is a
 * list built by hand in {@code ExplorerCommands}, which means anything wanting an item in it has to
 * reach that method — the same coupling {@code showCompiled} had, one layer up. Naming the menu turns
 * "add an item" into a registration.</p>
 *
 * <h3>Interned, like {@code DataKey}</h3>
 *
 * <p>Same reasoning: a menu named in two places must be one menu, and identity comparison is what makes
 * a placement cheap enough to evaluate while a menu is opening.</p>
 */
public final class MenuId {

    private static final Map<String, MenuId> INTERNED = new ConcurrentHashMap<>();

    /** The command palette. Everything is here unless it opts out. */
    public static final MenuId PALETTE = of("palette");

    /** Right-click inside the file tree. */
    public static final MenuId EXPLORER_CONTEXT = of("explorer/context");

    /**
     * The explorer's {@code New ▸} submenu, nested in {@link #EXPLORER_CONTEXT}.
     *
     * <p>Its own id rather than an entry the explorer draws, so anything can contribute a "New" kind —
     * which is the whole point of naming a menu. Nested here, at declaration, because a submenu's
     * position belongs to its parent.</p>
     */
    public static final MenuId EXPLORER_NEW =
            of("explorer/context/new").nestedIn(EXPLORER_CONTEXT, "New", "1_new", 0);

    /** Right-click on a node graph's canvas. */
    public static final MenuId GRAPH_CONTEXT = of("graph/context");

    /** Right-click a row in the shader graph's blackboard — a property. */
    public static final MenuId BLACKBOARD_CONTEXT = of("blackboard/context");

    /** Right-click on an editor tab. */
    public static final MenuId EDITOR_TAB_CONTEXT = of("editor/tab/context");

    /** Right-click inside a text editor. */
    public static final MenuId EDITOR_CONTEXT = of("editor/context");

    /**
     * A window's system menu — {@code Alt+Space}, a right-click on its title bar, a right-click on its
     * taskbar entry (W13a).
     *
     * <p><b>One id for all three</b>, which is the whole point of naming a menu: the three routes differ
     * only in where they anchor and in which window the {@code DataContext} resolves to. Three menus
     * would be three lists to keep in step, and the one that is edited least is the one a user reaches
     * for when the other two are unavailable.</p>
     */
    public static final MenuId WINDOW_SYSTEM = of("window/system");

    // ── The main menu bar ───────────────────────────────────────────────────────────────────────
    //
    // SIX, not VS Code's twelve. Terminal, Debug, Go, Selection, Refactor and Build have no subject in
    // this application, and a menu that opens onto two items reads as something broken rather than as
    // something small -- so their few relevant entries are folded into the six that do have a subject.
    //
    // GRAPH is the one that is not in either reference, and it is the reason a menu bar is worth having
    // here at all: it is contributed entirely from com.crystalgui.graph, which the shell must not import.

    /** File — new, open, save, close. */
    public static final MenuId MAIN_FILE = of("main/file");

    /**
     * {@code File ▸ New}.
     *
     * <p>A second "New" submenu beside {@link #EXPLORER_NEW} rather than the same one, because the two
     * genuinely differ: the explorer's acts on the right-clicked folder and this one acts on the project
     * root. A command that suits both simply declares both placements.</p>
     */
    public static final MenuId MAIN_FILE_NEW =
            of("main/file/new").nestedIn(MAIN_FILE, "New", "1_new", 0);

    /** {@code File ▸ Open Recent} — populated by a {@link MenuContributor}, never by registration. */
    public static final MenuId MAIN_FILE_RECENT =
            of("main/file/recent").nestedIn(MAIN_FILE, "Open Recent", "2_open", 20);

    /** Edit — undo, clipboard, find. */
    public static final MenuId MAIN_EDIT = of("main/edit");

    /** View — appearance, tool windows, editor layout. Where most of the toggles live. */
    public static final MenuId MAIN_VIEW = of("main/view");

    /** {@code View ▸ Tool Windows} — one checkable row per registered tool window. */
    public static final MenuId MAIN_VIEW_TOOLWINDOWS =
            of("main/view/toolwindows").nestedIn(MAIN_VIEW, "Tool Windows", "2_toolwindows", 0);

    /** Graph — this application's own menu, contributed from the graph package. */
    public static final MenuId MAIN_GRAPH = of("main/graph");

    /** Window — panes, and the computed list of open editors. */
    public static final MenuId MAIN_WINDOW = of("main/window");

    /** Help — about, documentation. Thin, and honest about it. */
    public static final MenuId MAIN_HELP = of("main/help");

    private final String name;

    private MenuId(String name) {
        this.name = name;
    }

    public static MenuId of(String name) {
        Objects.requireNonNull(name, "name");
        return INTERNED.computeIfAbsent(name, MenuId::new);
    }

    public String name() {
        return name;
    }

    /**
     * Nests {@code child} inside this menu as a submenu titled {@code title}.
     *
     * <h3>A submenu is a menu, not an entry kind</h3>
     *
     * <p>VS Code's model, and the reason contribution works at all: if "New ▸" were a special entry the
     * owning menu had to spell out, only the owner could add anything to it. As its own {@link MenuId} it
     * is contributable by the same {@link Command#menu} call as everything else, so a feature can add
     * "New ▸ Shader Graph" without touching the explorer.</p>
     *
     * <p>Declared on the parent rather than the child so one menu can appear under two parents, and
     * ordered with the same {@code group}/{@code order} pair items use — a submenu interleaves with
     * commands rather than being pinned above or below them.</p>
     *
     * <p>Idempotent per (child, parent): re-declaring updates nothing and adds nothing, so a class whose
     * registration runs twice cannot produce two identical submenus.</p>
     */
    public MenuId submenu(MenuId child, String title, String group, int order) {
        Objects.requireNonNull(child, "child");
        for (Submenu existing : submenus) {
            if (existing.menu() == child) return this;
        }
        submenus.add(new Submenu(child, title, group, order));
        return this;
    }

    /** {@link #submenu} from the child's side, returning the child so a constant can declare its own
     * position: {@code of("explorer/context/new").nestedIn(EXPLORER_CONTEXT, "New", "1_new", 0)}. */
    public MenuId nestedIn(MenuId parent, String title, String group, int order) {
        parent.submenu(this, title, group, order);
        return this;
    }

    /** The submenus nested directly in this one, in declaration order. */
    public List<Submenu> submenus() {
        return Collections.unmodifiableList(submenus);
    }

    private final List<Submenu> submenus = new CopyOnWriteArrayList<>();

    /** A nested menu and where it sits in its parent — {@link Placement}'s shape, for a menu. */
    public record Submenu(MenuId menu, String title, String group, int order) {
    }

    @Override
    public String toString() {
        return "MenuId(" + name + ")";
    }

    /**
     * Where an action sits within a menu.
     *
     * <p>{@code group} then {@code order}, so unrelated contributors interleave predictably instead of
     * by whoever registered first — VS Code spells the pair {@code "navigation@1"}. Two actions in one
     * group from two different features still come out in a stable order, which is the property that
     * makes contribution safe.</p>
     */
    public record Placement(MenuId menu, String group, int order) {
    }
}
