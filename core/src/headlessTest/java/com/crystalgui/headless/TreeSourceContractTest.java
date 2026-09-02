package com.crystalgui.headless;

import com.crystalgui.serialization.PlainOps;
import com.crystalgui.ui.dom.UINodeTreeSource;
import com.crystalgui.net.mirror.UINodeMirror;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.dom.ElementTreeSource;
import com.crystalgui.ui.dom.TreeSource;
import com.crystalgui.widget.control.Button;
import org.junit.Test;

/**
 * The seam suite over the OLD engine — {@link ElementTreeSource} over {@code UINode}.
 *
 * <p>Every structural assertion is inherited from {@link TreeSourceContract}. What is here is the
 * fixture, and the two tests that need a widget: a contract that reports something, and an element
 * refusing to be asked for what it cannot report. Those move into the base the day the new tree has a
 * widget to ask (M6).</p>
 */
public class TreeSourceContractTest extends TreeSourceContract<UINode> {

    @Override
    protected Fixture<UINode> fixture() {
        return new Fixture<UINode>() {
            @Override public UINode node() {
                return new UINode();
            }

            @Override public UINode named(String id) {
                UINode element = new UINode();
                element.setId(id);
                return element;
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
                parent.append(child);
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
                return "element";
            }
        };
    }

    @Test
    public void aContractCarriesWhatTheNodeReports() {
        UINode root = new UINode();
        Button reporting = new Button("go");
        root.append(reporting);
        TreeSource<UINode> source = new UINodeTreeSource(root);
        assertTrue("a Button declares that it can be activated",
                source.contractOf(reporting).eventKinds().contains("activate"));
        assertFalse("...and a plain container declares nothing",
                source.contractOf(root).reportsAnything());
    }

    @Test
    public void anElementCannotBeAskedToReportWhatItCannotObserve() {
        UINode plain = new UINode();
        try {
            new UINodeMirror<>(PlainOps.INSTANCE).addReportedEvent(plain, "activate");
            fail("a plain UINode has no contract, so there is no way for it to report anything -- "
                    + "this used to be recorded, described, and silently dropped by the client");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("WidgetContract"));
        }
        Button button = new Button("go");
        new UINodeMirror<>(PlainOps.INSTANCE).addReportedEvent(button, "activate");           // declared, so accepted
        try {
            new UINodeMirror<>(PlainOps.INSTANCE).addReportedEvent(button, "wheel");
            fail("a Button declares no wheel event and must refuse to be asked for one");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("cannot report"));
        }
    }
}
