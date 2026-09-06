package com.crystalgui.template;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import com.crystalgui.net.window.Networked;
import com.crystalgui.net.window.UiType;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.text.UIText;

/**
 * A {@code Networked} panel laid out by a document: inflate, bind by id, create what is left, then
 * {@code build}.
 *
 * <p>What a mod puts in its own tests — the assertion that a panel class and the document it names still
 * agree, with no server anywhere.</p>
 */
public class UiTypeTemplateTest {

    @After
    public void forgetTemplates() {
        UiTemplates.reloadAll();
    }

    /** What a model is here: nothing. The document is the layout and the test is about wiring. */
    record StatusModel(String subject) {
    }

    @UiTemplate.Source("cguitest:ui/status")
    public static final class StatusPanel extends UIElement implements Networked<StatusModel> {

        public static final Name NAME = Name.of("cguitest", "statuspanel");

        static final UiType<StatusPanel, StatusModel> TYPE =
                UiType.of("cguitest:status", StatusPanel::new);

        @Bound UIText title;
        @Bound UIText subject;
        @Bound(optional = true) Button missing;

        /** Not in the document, so it is created and named as any unassigned field is. */
        UIText footer;

        boolean built;

        public StatusPanel() {
            super(NAME);
        }

        @Override
        public void build(StatusModel model) {
            built = true;
            // The tree is already here, which is the whole claim: a template-backed panel arranges
            // nothing and reads what it was given.
            subject.setText(model.subject());
        }
    }

    @Test
    public void aPanelIsLaidOutByItsDocument() {
        UIElementRegistry.bootstrap();
        StatusPanel panel = StatusPanel.TYPE.build(new StatusModel("Dev"));

        assertNotNull("the template was inflated into the panel", panel.title);
        assertEquals("Status", panel.title.getText());
        assertEquals("Dev", panel.subject.getText());
        assertTrue(panel.built);
    }

    /** Inflate, then bind, then create what is left: a field the document has not got is still made. */
    @Test
    public void whatTheDocumentHasNotGotIsStillCreated() {
        UIElementRegistry.bootstrap();
        StatusPanel panel = StatusPanel.TYPE.build(new StatusModel("Dev"));

        assertNotNull("an unbound field is created as it always was", panel.footer);
        assertEquals("and named after itself", "footer", panel.footer.id());
        assertNull("an optional bound field the document has not got stays null", panel.missing);
    }

    /** The bound elements are the ones in the tree, not copies of them. */
    @Test
    public void theBoundFieldsAreTheTreesOwnElements() {
        UIElementRegistry.bootstrap();
        StatusPanel panel = StatusPanel.TYPE.build(new StatusModel("Dev"));

        assertSame(panel.title, panel.querySelector("#title"));
    }
}
