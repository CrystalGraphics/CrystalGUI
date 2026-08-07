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
