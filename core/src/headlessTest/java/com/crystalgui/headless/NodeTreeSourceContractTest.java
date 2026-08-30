package com.crystalgui.headless;

import com.crystalgui.ui.dom.Node;
import com.crystalgui.ui.dom.NodeTreeSource;
import com.crystalgui.ui.dom.TreeSource;

/**
 * The seam suite over the NEW engine — {@link NodeTreeSource} over {@link Node}.
 *
 * <p>Every assertion is inherited; this is the fixture and nothing else, which is the milestone's
 * claim about networking made concrete: the mirror needs a {@code TreeSource} and a
 * {@code NodeMirror} from the second engine, and the seam suite passing here unchanged is the proof
 * that the source is right. Scaffolding is shadow content — a shadow root's child is exactly what the
 * old engine's internal child was for, and exactly as invisible to a peer.</p>
 */
public class NodeTreeSourceContractTest extends TreeSourceContract<Node> {

    @Override
    protected Fixture<Node> fixture() {
        return new Fixture<Node>() {
            @Override public Node node() {
                return new Node();
            }

            @Override public Node named(String id) {
                return new Node().setId(id);
            }

            @Override public void add(Node parent, Node child) {
                parent.append(child);
            }

            @Override public void addAt(Node parent, Node child, int index) {
                parent.insertAt(index, child);
            }

            @Override public void remove(Node parent, Node child) {
                parent.remove(child);
            }

            @Override public void addScaffolding(Node parent, Node child) {
                (parent.shadowRoot() != null ? parent.shadowRoot() : parent.attachShadow()).append(child);
            }

            @Override public void addClass(Node node, String className) {
                node.addClass(className);
            }

            @Override public String idOf(Node node) {
                return node.id();
            }

            @Override public TreeSource<Node> sourceOver(Node root) {
                return new NodeTreeSource(root);
            }

            @Override public String plainKindName() {
                return "crystalgui:element";
            }
        };
    }
}
