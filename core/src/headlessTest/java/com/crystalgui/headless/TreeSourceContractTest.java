package com.crystalgui.headless;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.dom.ElementTreeSource;
import com.crystalgui.ui.dom.TreeSource;
import com.crystalgui.ui.elements.Button;
import org.junit.Test;

/**
 * The seam suite over the OLD engine — {@link ElementTreeSource} over {@code UIElement}.
 *
 * <p>Every structural assertion is inherited from {@link TreeSourceContract}. What is here is the
 * fixture, and the two tests that need a widget: a contract that reports something, and an element
 * refusing to be asked for what it cannot report. Those move into the base the day the new tree has a
 * widget to ask (M6).</p>
 */
public class TreeSourceContractTest extends TreeSourceContract<UIElement> {

    @Override
    protected Fixture<UIElement> fixture() {
        return new Fixture<UIElement>() {
            @Override public UIElement node() {
                return new UIElement();
            }

            @Override public UIElement named(String id) {
                UIElement element = new UIElement();
                element.setId(id);
                return element;
            }

            @Override public void add(UIElement parent, UIElement child) {
                parent.addChild(child);
            }

            @Override public void addAt(UIElement parent, UIElement child, int index) {
                parent.addChildAt(child, index);
            }

            @Override public void remove(UIElement parent, UIElement child) {
                parent.removeChild(child);
            }

            @Override public void addScaffolding(UIElement parent, UIElement child) {
                parent.addInternalChild(child);
            }

            @Override public void addClass(UIElement node, String className) {
                node.addClass(className);
            }

            @Override public String idOf(UIElement node) {
                return node.getId();
            }

            @Override public TreeSource<UIElement> sourceOver(UIElement root) {
                return new ElementTreeSource(root);
            }

            @Override public String plainKindName() {
                return "element";
            }
        };
    }

    @Test
    public void aContractCarriesWhatTheNodeReports() {
        UIElement root = new UIElement();
        Button reporting = new Button("go");
        root.addChild(reporting);
        TreeSource<UIElement> source = new ElementTreeSource(root);
        assertTrue("a Button declares that it can be activated",
                source.contractOf(reporting).eventKinds().contains("activate"));
        assertFalse("...and a plain container declares nothing",
                source.contractOf(root).reportsAnything());
    }

    @Test
    public void anElementCannotBeAskedToReportWhatItCannotObserve() {
        UIElement plain = new UIElement();
        try {
            plain.addReportedEvent("activate");
            fail("a plain UIElement has no contract, so there is no way for it to report anything -- "
                    + "this used to be recorded, described, and silently dropped by the client");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("WidgetContract"));
        }
        Button button = new Button("go");
        button.addReportedEvent("activate");           // declared, so accepted
        try {
            button.addReportedEvent("wheel");
            fail("a Button declares no wheel event and must refuse to be asked for one");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("cannot report"));
        }
    }
}
