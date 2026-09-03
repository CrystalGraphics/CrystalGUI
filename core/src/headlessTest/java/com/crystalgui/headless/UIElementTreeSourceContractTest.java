package com.crystalgui.headless;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementTreeSource;
import com.crystalgui.ui.dom.TreeSource;

/**
 * The seam suite over the NEW engine — {@link UIElementTreeSource} over {@link UIElement}.
 *
 * <p>Every assertion is inherited; this is the fixture and nothing else, which is the milestone's
 * claim about networking made concrete: the mirror needs a {@code TreeSource} and a
 * {@code NodeMirror} from the second engine, and the seam suite passing here unchanged is the proof
 * that the source is right. Scaffolding is shadow content — a shadow root's child is exactly what the
 * old engine's internal child was for, and exactly as invisible to a peer.</p>
 */
public class UIElementTreeSourceContractTest extends TreeSourceContract<UIElement> {

    @Override
    protected Fixture<UIElement> fixture() {
        return new Fixture<UIElement>() {
            @Override public UIElement node() {
                return new UIElement();
            }

            @Override public UIElement named(String id) {
                return new UIElement().setId(id);
            }

            @Override public void add(UIElement parent, UIElement child) {
                parent.append(child);
            }

            @Override public void addAt(UIElement parent, UIElement child, int index) {
                parent.insertAt(index, child);
            }

            @Override public void remove(UIElement parent, UIElement child) {
                parent.remove(child);
            }

            @Override public void addScaffolding(UIElement parent, UIElement child) {
                (parent.shadowRoot() != null ? parent.shadowRoot() : parent.attachShadow()).append(child);
            }

            @Override public void addClass(UIElement node, String className) {
                node.addClass(className);
            }

            @Override public String idOf(UIElement node) {
                return node.id();
            }

            @Override public TreeSource<UIElement> sourceOver(UIElement root) {
                return new UIElementTreeSource(root);
            }

            @Override public String plainKindName() {
                return "crystalgui:element";
            }
        };
    }
}
