package com.crystalgui.desktop.app;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;

/** A float of an application with a document of its own stays in it; any other opens on the desktop. */
public class ClientFloatTest extends UiDocumentTestBase {

    @Test
    public void aFloatOfAnApplicationDocumentIsInThatDocumentOverItsWindow() {
        UIDocument own = new UIDocument();
        own.styles().addStylesheet(StyleSheet.parse(ClientRoot.SHEET));
        ClientRoot client = new ClientRoot();
        UIElement content = new UIElement();
        client.setContent(content);
        own.append(client);
        client.setClientSize(400, 300);

        WindowFrame torn = AppWindows.addFloat(content, new WindowFrame("Torn"));
        own.update(400, 300);

        assertSame("in the client, beside what it shows", client, torn.parent());
        assertSame("the content is still shown", content, client.content());
        assertNull("it is no window of a desktop", torn.desktop());
        assertNotNull("and it has a place in its document", torn.box());

        client.setContent(new UIElement());
        assertSame("a new content leaves the float", client, torn.parent());
    }

    @Test
    public void aFloatOfAnythingElseIsAWindowOfTheDesktop() {
        withDefaultStyles();
        UIElement anchor = new UIElement();
        document.append(anchor);
        WindowFrame torn = AppWindows.addFloat(anchor, new WindowFrame("Torn"));
        frame();
        assertSame(Desktop.of(document), torn.desktop());
        assertTrue(Desktop.of(document).windowManager().windows().contains(torn));
    }
}
