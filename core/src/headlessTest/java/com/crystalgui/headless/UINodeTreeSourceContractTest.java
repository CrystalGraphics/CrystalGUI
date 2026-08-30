package com.crystalgui.headless;

import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.dom.UINodeTreeSource;
import com.crystalgui.ui.dom.TreeSource;

/**
 * The seam suite over the NEW engine — {@link UINodeTreeSource} over {@link UINode}.
 *
 * <p>Every assertion is inherited; this is the fixture and nothing else, which is the milestone's
 * claim about networking made concrete: the mirror needs a {@code TreeSource} and a
 * {@code NodeMirror} from the second engine, and the seam suite passing here unchanged is the proof
 * that the source is right. Scaffolding is shadow content — a shadow root's child is exactly what the
 * old engine's internal child was for, and exactly as invisible to a peer.</p>
 */
public class UINodeTreeSourceContractTest extends TreeSourceContract<UINode> {

    @Override
    protected Fixture<UINode> fixture() {
        return new Fixture<UINode>() {
            @Override public UINode node() {
                return new UINode();
            }

            @Override public UINode named(String id) {
                return new UINode().setId(id);
            }

            @Override public void add(UINode parent, UINode child) {
                parent.append(child);
            }

            @Override public void addAt(UINode parent, UINode child, int index) {
                parent.insertAt(index, child);
            }

            @Override public void remove(UINode parent, UINode child) {
                parent.remove(child);
            }

            @Override public void addScaffolding(UINode parent, UINode child) {
                (parent.shadowRoot() != null ? parent.shadowRoot() : parent.attachShadow()).append(child);
            }

            @Override public void addClass(UINode node, String className) {
                node.addClass(className);
            }

            @Override public String idOf(UINode node) {
                return node.id();
            }

            @Override public TreeSource<UINode> sourceOver(UINode root) {
                return new UINodeTreeSource(root);
            }

            @Override public String plainKindName() {
                return "crystalgui:element";
            }
        };
    }
}
