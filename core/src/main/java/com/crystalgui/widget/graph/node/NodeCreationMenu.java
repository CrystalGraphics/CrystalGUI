package com.crystalgui.widget.graph.node;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.graph.NodeMenuTree;
import com.crystalgui.graph.NodeType;
import com.crystalgui.graph.NodeTypeRegistry;
import com.crystalgui.graph.TypeCompatibility;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.composite.CreateMenu;

/**
 * Unity's "Create Node" window: search a categorised library and pick a node type — optionally filtered
 * to what could receive the wire you dropped.
 *
 * <pre>{@code
 * NodeCreationMenu menu = new NodeCreationMenu(library);
 * menu.onChosen.connect(offer -> graph.createFrom(offer));
 * menu.openAll(worldX, worldY, graph);                       // Space, or right-click on empty canvas
 * menu.openForOutput(typeId, rule, x, y, graph);             // a wire dropped on nothing
 * }</pre>
 *
 * <p>Everything about <em>being a search menu</em> — the popover, the box, the virtualised tree, the
 * arrows, the drag by the title bar, the match tint — is {@link CreateMenu}'s and is shared with every
 * other library picker. What is here is what a <b>graph</b> means by it: the node library, the wire
 * filter, and {@link NodeMenuTree}'s categorised-or-ranked answer.</p>
 *
 * <h3>Typing {@code plus} finds {@code Add}</h3>
 *
 * <p>That cannot be a property of the matcher — no amount of fuzziness knows a multiply is a "product" —
 * so it is a field on {@link NodeType} and this only has to ask.</p>
 */
public class NodeCreationMenu extends CreateMenu<NodeMenuTree.Node, NodeTypeRegistry.Offer> {

    /**
     * This widget's kind.
     *
     * <p>Declared AT ALL because a subclass inherits its parent's kind unless it is given its own:
     * without this, every rule the sheets write for {@code nodecreationmenu} matches nothing.</p>
     */
    public static final Name NAME = Name.of("nodecreationmenu");

    private final NodeTypeRegistry library;

    /** What the menu is currently offering against, or null when it was opened without a wire. */
    @Nullable
    private String filterTypeId;

    private boolean filterFromOutput;
    private TypeCompatibility compatibility = TypeCompatibility.EXACT;

    public NodeCreationMenu(NodeTypeRegistry library) {
        super(NAME, "Create Node");
        this.library = library;
        addClass("nodecreationmenu");
        setRows(new Rows<NodeMenuTree.Node, NodeTypeRegistry.Offer>() {
            @Override
            public List<NodeMenuTree.Node> roots(String query) {
                List<NodeTypeRegistry.Offer> offers = currentOffers(query);
                // A query flattens: a result set is RANKED, not filed. `offers` already arrives
                // best-first from the registry, and re-sorting alphabetically threw that away -- which
                // is what made `vec` + Enter create Cross Product. @see NodeMenuTree#ranked
                return query.trim().isEmpty()
                        ? NodeMenuTree.categorised(offers) : NodeMenuTree.ranked(offers);
            }

            @Override
            public List<NodeMenuTree.Node> children(NodeMenuTree.Node parent) {
                return parent.children();
            }

            @Override
            public String label(NodeMenuTree.Node node) {
                return node.label();
            }

            @Override
            public boolean isCategory(NodeMenuTree.Node node) {
                return node.isCategory();
            }

            @Override
            @Nullable
            public NodeTypeRegistry.Offer payload(NodeMenuTree.Node node) {
                return node.offer();
            }

            @Override
            public List<String> categorySegments(NodeMenuTree.Node node) {
                return node.offer() == null ? List.of()
                        : NodeMenuTree.categorySegments(node.offer().type().category());
            }
        });
    }

    // ── Opening ─────────────────────────────────────────────────────────────

    /** Everything in the library — Space, or a right-click on empty canvas. */
    public NodeCreationMenu openAll(float rootX, float rootY, @Nullable UIElement invoker) {
        this.filterTypeId = null;
        openAt(rootX, rootY, invoker);
        return this;
    }

    /** Filtered to what could <b>receive</b> a wire dragged from an output of {@code sourceTypeId}. */
    public NodeCreationMenu openForOutput(String sourceTypeId, TypeCompatibility rule,
                                          float rootX, float rootY, @Nullable UIElement invoker) {
        this.filterTypeId = sourceTypeId;
        this.filterFromOutput = true;
        this.compatibility = rule;
        openAt(rootX, rootY, invoker);
        return this;
    }

    /** Filtered to what could <b>feed</b> a wire dragged from an input of {@code targetTypeId}. */
    public NodeCreationMenu openForInput(String targetTypeId, TypeCompatibility rule,
                                         float rootX, float rootY, @Nullable UIElement invoker) {
        this.filterTypeId = targetTypeId;
        this.filterFromOutput = false;
        this.compatibility = rule;
        openAt(rootX, rootY, invoker);
        return this;
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
}
