package com.crystalgui.widget.config.inspector;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import com.crystalgui.core.data.DataContext;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.data.DataProvider;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;

/**
 * <b>A closed document takes its inspector sections with it.</b>
 *
 * <p>The inspector retains what it shows when the subject goes detached, and again when nothing else can
 * be described — both deliberate, so the panel changes only when there is a better answer. Together they
 * meant a closed document kept its tabs on screen over whatever was opened next, because closing produces
 * exactly those two conditions and nothing distinguished it from a bad moment to ask.</p>
 *
 * <p>Attached, and framed: the subscription that hears a close is made in {@code connected()}, so a
 * detached inspector never hears one -- and the rebuild is deferred to a tick like a live one's.</p>
 */
public class InspectorForgetsAClosedSubjectTest extends UiDocumentTestBase {

    private static final DataKey<Subject> SUBJECT =
            DataKey.create("test.closedSubject", Subject.class);

    private static final String TAB = "Closable";

    /** Stands in for an editor: the thing that is released, with the real subject inside it. */
    private static final class Subject extends UIElement implements DataProvider {
        @Override
        public Object getData(DataKey<?> key) {
            return key == SUBJECT ? this : null;
        }
    }

    private static final class SubjectSection implements InspectorSection {
        @Override
        public String tab() {
            return TAB;
        }

        @Override
        public boolean accepts(DataContext context) {
            return context.get(SUBJECT) != null;
        }

        @Override
        public void build(InspectorForm form, DataContext context) {
            // A section that writes NOTHING contributes no tab -- rebuild drops an empty form -- so a
            // test whose build() is a no-op asserts against a panel that was correctly left empty.
            form.header("Closable");
        }
    }

    private final SubjectSection section = new SubjectSection();

    @After
    public void removeSection() {
        InspectorRegistry.remove(section);
    }

    @Test
    public void closingTheSubjectClearsWhatItContributed() {
        InspectorRegistry.register(section);
        Subject editor = new Subject();
        document.append(editor);
        Inspector inspector = new Inspector();
        document.append(inspector);

        inspector.inspect(editor);
        document.frame(0.016f, W, H);
        assertTrue("the section describes it while it is open; tabs=" + tabsOf(inspector),
                hasTab(inspector, TAB));

        InspectorRegistry.subjectClosed(editor);
        document.frame(0.016f, W, H);
        assertFalse("and lets go of it once it is closed; tabs=" + tabsOf(inspector),
                hasTab(inspector, TAB));
    }

    /** The subject is usually something INSIDE the editor -- the graph, a node, a focused field -- and
     * it is the editor as a whole that is released. */
    @Test
    public void closingAnEditorAlsoForgetsASubjectInsideIt() {
        InspectorRegistry.register(section);
        Subject editor = new Subject();
        UIElement inner = new UIElement();
        editor.append(inner);
        document.append(editor);
        Inspector inspector = new Inspector();
        document.append(inspector);

        inspector.inspect(inner);
        document.frame(0.016f, W, H);
        assertTrue("the inner element resolves through its editor; tabs=" + tabsOf(inspector),
                hasTab(inspector, TAB));

        InspectorRegistry.subjectClosed(editor);
        document.frame(0.016f, W, H);
        assertFalse("closing the editor forgets what was inside it; tabs=" + tabsOf(inspector),
                hasTab(inspector, TAB));
    }

    /**
     * The tab's X, which is the case that looked like a different close path entirely.
     *
     * <p>Pressing it moves focus to the button first, so the subject is already the tab strip by the
     * time the close arrives — nothing inside the editor. Ctrl+W leaves focus in the editor and appeared
     * to work; they are the same close, seen with two different subjects.</p>
     */
    @Test
    public void closingFromTheTabStripClearsItToo() {
        InspectorRegistry.register(section);
        Subject editor = new Subject();
        document.append(editor);
        UIElement closeButton = new UIElement();
        document.append(closeButton);
        Inspector inspector = new Inspector();
        document.append(inspector);

        inspector.inspect(editor);
        document.frame(0.016f, W, H);
        assertTrue("open, and described", hasTab(inspector, TAB));

        // The press lands on the X: nothing here describes it, so the retention rule holds the tabs.
        inspector.inspect(closeButton);
        document.frame(0.016f, W, H);
        assertTrue("retained while nothing better can be described", hasTab(inspector, TAB));

        InspectorRegistry.subjectClosed(editor);
        document.frame(0.016f, W, H);
        assertFalse("and the close is allowed to blank it; tabs=" + tabsOf(inspector),
                hasTab(inspector, TAB));
    }

    private static String tabsOf(Inspector inspector) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < inspector.tabs().getTabCount(); i++) {
            out.append(inspector.tabs().getTab(i).getText()).append(' ');
        }
        return out.length() == 0 ? "(none)" : out.toString();
    }

    private static boolean hasTab(Inspector inspector, String label) {
        for (int i = 0; i < inspector.tabs().getTabCount(); i++) {
            if (label.equals(inspector.tabs().getTab(i).getText())) return true;
        }
        return false;
    }
}
