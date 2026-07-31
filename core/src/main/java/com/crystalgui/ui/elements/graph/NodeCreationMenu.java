package com.crystalgui.ui.elements.graph;

import com.crystalgui.graph.NodeType;
import com.crystalgui.graph.NodeTypeRegistry;
import com.crystalgui.graph.PortSpec;
import com.crystalgui.graph.TypeCompatibility;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.Popover;
import com.crystalgui.ui.elements.ScrollerView;
import com.crystalgui.ui.elements.TextField;
import com.crystalgui.ui.elements.UIText;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The create-node menu: a search box over a node library, optionally filtered by a wire you are holding.
 *
 * <pre>{@code
 * menu.openForOutput("vec3", compatibility);   // dropped a wire from an output
 * menu.openAll();                              // Space, or a right-click on empty canvas
 * }</pre>
 *
 * <h3>Entries are ports, not nodes</h3>
 * <p>When the menu is opened by dropping a wire, each row is a <b>(type, port)</b> pair — Unity's menu
 * *"lists every available Port on nodes that match"* the dragged type. Choosing one therefore creates
 * the node <em>and</em> lands the wire, which is a materially better interaction than creating a node
 * and leaving the user to connect the thing they just asked for. Opened without a wire, the rows are
 * plain types.</p>
 *
 * <h3>Search matches synonyms, because the library declares them</h3>
 * <p>Typing {@code plus} finds {@code Add}. That cannot be a property of the matcher — no amount of
 * fuzziness knows that a multiply is a "product" — so it is a field on {@link NodeType} and this only
 * has to ask.</p>
 *
 * <h3>A {@code Popover}, so dismissal is already solved</h3>
 * <p>Light dismiss, Escape, focus restore and the top layer all come from {@link Popover}, which is the
 * reason this is thirty lines of list-building rather than a dismissal state machine. Placement comes
 * from {@code showAt} — and nothing here writes {@code left}/{@code top}, because
 * {@code AnchoredPlacement} owns that and a second writer fights it every frame.</p>
 */
public class NodeCreationMenu extends Popover {

    public static final String SEARCH_CLASS = "__search__";
    public static final String LIST_CLASS = "__items__";
    public static final String ENTRY_CLASS = "__entry__";
    public static final String EMPTY_CLASS = "__empty__";

    private final NodeTypeRegistry library;
    private final TextField search = new TextField();
    private final ScrollerView list = new ScrollerView();
    private final UIText emptyLabel = new UIText("no matching nodes");

    /** What the menu is currently offering against, or null when it was opened without a wire. */
    @Nullable
    private String filterTypeId;
    private boolean filterFromOutput;
    private TypeCompatibility compatibility = TypeCompatibility.EXACT;

    /**
     * Fires with the chosen entry. A {@link NodeTypeRegistry.Offer} whose {@code port} is null when the
     * menu was opened without a wire — one signal rather than two, because every consumer does the same
     * thing with both: create the node, then connect if there is something to connect.
     */
    public final Signal.Value<NodeTypeRegistry.Offer> onChosen = new Signal.Value<>();

    public NodeCreationMenu(NodeTypeRegistry library) {
        this.library = library;
        addClass("nodecreationmenu");

        search.addClass(SEARCH_CLASS);
        search.setPlaceholder("search");
        // IMMEDIATE, not the default ON_COMMIT: the list has to narrow as you type. On commit-only the
        // menu would sit showing everything until Enter, which is not a search box, it is a filter you
        // have to submit.
        search.setUpdateMode(TextField.UpdateMode.IMMEDIATE);
        search.attachListener(text -> rebuild());

        list.addClass(LIST_CLASS);
        emptyLabel.addClass(EMPTY_CLASS);

        addInternalChild(search);
        addInternalChild(list);
    }

    // ── Opening ─────────────────────────────────────────────────────────────

    /** Everything in the library — Space, or a right-click on empty canvas. */
    public NodeCreationMenu openAll(float rootX, float rootY, @Nullable UIElement invoker) {
        this.filterTypeId = null;
        return open(rootX, rootY, invoker);
    }

    /** Filtered to what could <b>receive</b> a wire dragged from an output of {@code sourceTypeId}. */
    public NodeCreationMenu openForOutput(String sourceTypeId, TypeCompatibility rule,
                                          float rootX, float rootY, @Nullable UIElement invoker) {
        this.filterTypeId = sourceTypeId;
        this.filterFromOutput = true;
        this.compatibility = rule;
        return open(rootX, rootY, invoker);
    }

    /** Filtered to what could <b>feed</b> a wire dragged from an input of {@code targetTypeId}. */
    public NodeCreationMenu openForInput(String targetTypeId, TypeCompatibility rule,
                                         float rootX, float rootY, @Nullable UIElement invoker) {
        this.filterTypeId = targetTypeId;
        this.filterFromOutput = false;
        this.compatibility = rule;
        return open(rootX, rootY, invoker);
    }

    private NodeCreationMenu open(float rootX, float rootY, @Nullable UIElement invoker) {
        search.setText("");
        rebuild();
        showAt(rootX, rootY, invoker);
        // Focus the box, not the first row: the menu exists to be typed into, and a user who wanted the
        // first row would have clicked it.
        var window = getAttachedWindow();
        if (window != null) window.getInputHandler().requestFocus(search);
        return this;
    }

    // ── The list ────────────────────────────────────────────────────────────

    /** Rebuilt wholesale on every keystroke, and that is safe here in a way it is not in the graph: this
     * list is not under the pointer while it changes — the search box is. */
    private void rebuild() {
        list.clearAllChildren();
        String query = search.getText();

        List<NodeTypeRegistry.Offer> offers = currentOffers(query);
        if (offers.isEmpty()) {
            list.addChild(emptyLabel);
            return;
        }
        for (NodeTypeRegistry.Offer offer : offers) {
            list.addChild(entryFor(offer));
        }
    }

    private List<NodeTypeRegistry.Offer> currentOffers(String query) {
        if (filterTypeId == null) {
            List<NodeTypeRegistry.Offer> plain = new ArrayList<>();
            for (NodeType type : library.search(query)) plain.add(new NodeTypeRegistry.Offer(type, null));
            return plain;
        }
        return filterFromOutput
                ? library.offersForOutput(filterTypeId, compatibility, query)
                : library.offersForInput(filterTypeId, compatibility, query);
    }

    private UIElement entryFor(NodeTypeRegistry.Offer offer) {
        UIElement row = new UIElement();
        row.addClass(ENTRY_CLASS);
        PortSpec port = offer.port();
        UIText label = new UIText(port == null ? offer.type().label() : offer.label());
        label.setHitTest(false);
        row.addChild(label);
        row.onMouseDown.attachListener((el, event) -> {
            onChosen.emit(offer);
            hide();
            event.stopPropagation();
        }, false, true);
        return row;
    }

    /** The search box, for a theme or a test. */
    public TextField searchField() {
        return search;
    }

    /** The current entries, in order — what a test asserts on, and what a keyboard walk would move over. */
    public List<UIElement> entries() {
        List<UIElement> rows = new ArrayList<>();
        for (UIElement child : list.getChildren()) {
            if (child.hasClass(ENTRY_CLASS)) rows.add(child);
        }
        return rows;
    }
}
