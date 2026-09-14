package com.crystalgui.widget.composite;

import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.widget.collection.tree.TreeView;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.overlay.Popover;
import com.crystalgui.widget.text.UIText;

/**
 * <b>Search a categorised library and pick one thing</b> — Unity's Create Node window, VS Code's command
 * palette, Blender's Add menu.
 *
 * <p>A popover holding a {@link SearchTree} under a title bar the whole menu drags by. What it lists is
 * yours; a panel that wants the same search docked rather than floating uses {@link SearchTree} alone.</p>
 *
 * <pre>{@code
 * final class ThingMenu extends CreateMenu<MyNode, Thing> {
 *     ThingMenu() {
 *         super(NAME, "Add Thing");
 *         setRows(new SearchTree.Rows<MyNode, Thing>() {
 *             public List<MyNode> roots(String query) { return library.tree(query); }
 *             public List<MyNode> children(MyNode node) { return node.children(); }
 *             public String label(MyNode node)          { return node.label(); }
 *             public boolean isCategory(MyNode node)    { return node.isFolder(); }
 *             public Thing payload(MyNode node)         { return node.thing(); }
 *         });
 *     }
 * }
 *
 * menu.onChosen.connect(thing -> place(thing));
 * menu.openAt(x, y, invoker);
 * }</pre>
 *
 * <p>Two parameters: {@code N} is whatever your tree is made of and {@code T} is what choosing a leaf
 * produces. Set the rows before opening — a menu with none lists nothing and says nothing about it.</p>
 *
 * <p>The sheet gives the menu a definite height: the virtualised tree inside has no intrinsic one.</p>
 */
public class CreateMenu<N, T> extends Popover {

    /** This widget's kind. A subclass with its own look declares its own and passes it up. */
    public static final Name NAME = Name.of("createmenu");

    public static final String TITLE_BAR_CLASS = "__title-bar__";
    public static final String TITLE_CLASS = "__title__";

    private final UIElement titleBar = new UIElement();
    private final UIText titleLabel;
    private final SearchTree<N, T> body = new SearchTree<>();

    /** Fires with what was chosen, after which the menu closes. A folder emits nothing; it opens. */
    public final Signal.Value<T> onChosen = new Signal.Value<>();

    public CreateMenu(String title) {
        this(NAME, title);
    }

    protected CreateMenu(Name name, String title) {
        super(name);
        this.titleLabel = new UIText(title);

        titleBar.addClass(TITLE_BAR_CLASS);
        titleLabel.addClass(TITLE_CLASS);
        titleLabel.setHitTest(false);
        titleBar.append(titleLabel);
        titleBar.onMouseDown.attachListener((element, event) -> {
            beginMove(event.getPosition().x(), event.getPosition().y());
            // Or the surface underneath takes it as a press on empty background and starts a marquee —
            // the menu is a promoted child, so its input still travels through whatever opened it.
            event.stopPropagation();
        }, false, true);

        body.setRefocusAfterPress(this::isOpen);
        body.onChosen.connect(chosen -> {
            onChosen.emit(chosen);
            hide();
        });

        append(titleBar);
        append(body);
    }

    /** Says what this menu lists. Set once, before opening. */
    public CreateMenu<N, T> setRows(SearchTree.Rows<N, T> rows) {
        body.setRows(rows);
        return this;
    }

    /** What the title bar says. */
    public CreateMenu<N, T> setTitle(String title) {
        titleLabel.setText(title);
        return this;
    }

    /** @see SearchTree#DEFAULT_AUTO_EXPAND_THRESHOLD */
    public CreateMenu<N, T> setAutoExpandThreshold(int entries) {
        body.setAutoExpandThreshold(entries);
        return this;
    }

    public int getAutoExpandThreshold() {
        return body.getAutoExpandThreshold();
    }

    // ── Opening ─────────────────────────────────────────────────────────────

    /**
     * Opens at a point, anchored to whatever invoked it, with the query, folders and scroll reset — the menu
     * is one element reused, so without the reset a reopen shows the last visit's state.
     */
    public CreateMenu<N, T> openAt(float rootX, float rootY, @Nullable UIElement invoker) {
        body.reset();
        showAt(rootX, rootY, invoker);
        // Focus the box, not the first row: the menu exists to be typed into.
        if (document() != null) body.focusSearch();
        return this;
    }

    /**
     * Drags the whole menu by its title bar.
     *
     * <p><b>The drag source is the window, not the title bar</b>: every drag coordinate is converted through
     * its source's own transform, so sourcing a move from the thing being moved measures in a moving frame.</p>
     */
    private void beginMove(float pointerX, float pointerY) {
        UIDocument window = document();
        if (window == null) return;
        Box placed = box();
        final float startLeft = placed == null ? 0f : placed.x();
        final float startTop = placed == null ? 0f : placed.y();
        // No payload, no drop targets, no activation threshold: a move must track the first pixel.
        Drag.start(window, pointerX, pointerY,
                (mx, my, sx, sy, dx, dy) -> moveTo(startLeft + dx, startTop + dy));
    }

    /** None: the title bar and the search tree ARE this widget, and the constructor rebuilds them. */
    @Override
    public List<UIElement> describedChildren() {
        return List.of();
    }

    // ── Accessors, for a theme or a test ────────────────────────────────────

    /** The search box and the tree under the title bar. */
    public SearchTree<N, T> body() {
        return body;
    }

    public TextField searchField() {
        return body.searchField();
    }

    /** The whole search box — icon, field and clear. */
    public SearchField searchBox() {
        return body.searchBox();
    }

    public TreeView<N> treeView() {
        return body.treeView();
    }

    /** The draggable header. */
    public UIElement titleBar() {
        return titleBar;
    }

    /** @see SearchTree#visibleEntries */
    public List<N> visibleEntries() {
        return body.visibleEntries();
    }

    /** @see SearchTree#visibleOffers */
    public List<N> visibleOffers() {
        return body.visibleOffers();
    }

    /** @see SearchTree#allOffers */
    public List<N> allOffers() {
        return body.allOffers();
    }

    /** @see SearchTree#entries */
    public List<UIElement> entries() {
        return body.entries();
    }
}
