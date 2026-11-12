package com.crystalgui.desktop.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.motion.WindowAnimator;
import com.crystalgui.desktop.window.WindowChrome;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.text.UIText;

/** Which of an application's windows its document follows: the one last pressed in, by its content or its caption. */
public class WindowFollowTest extends UiDocumentTestBase {

    private static final class Stub implements AppDocument.Backend {
        @Override
        public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
            return true;
        }

        @Override
        public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
            return true;
        }

        @Override
        public void close() {
        }
    }

    /** A root with a bar of its own in the window's caption, as a workbench has. */
    private static final class Chromed extends UIElement implements WindowChrome {
        private final UIElement bar = new UIElement();

        Chromed() {
            bar.append(new UIText("File"));
        }

        @Override
        public UIElement captionChrome() {
            return bar;
        }
    }

    private static final ApplicationKind KIND = ApplicationKind.of("test:follow", "Follow").standalone().separateDocument();

    private boolean animationsWere;
    private AppDocument application;
    private AppWindow small, large;

    @Before
    public void open() {
        animationsWere = WindowAnimator.isEnabled();
        WindowAnimator.setEnabled(false);
        withDefaultStyles();
        Desktop desktop = Desktop.of(document);
        UIDocument own = new UIDocument();
        own.styles().addStylesheet(StyleSheet.parse(ClientRoot.SHEET + PopupLayer.SHEET));
        own.useTopLayer(new PopupLayer());
        application = new AppDocument(own, new Stub());
        small = AppWindows.open(desktop, KIND, application, WindowSpec.titled("Small").content(new UIElement()).size(240, 160));
        large = AppWindows.open(desktop, KIND, application, WindowSpec.titled("Large").size(520, 360));
        large.setContent(new Chromed());
        for (int i = 0; i < 6; i++) frame();
    }

    @After
    public void restore() {
        WindowAnimator.setEnabled(animationsWere);
    }

    private static ClientPortal captionIn(UIElement root) {
        if (root instanceof ClientPortal portal && portal.caption()) return portal;
        for (UIElement child : root.composedChildren()) {
            ClientPortal found = captionIn(child);
            if (found != null) return found;
        }
        return null;
    }

    private void report(ClientPortal portal) {
        portal.paintContent(null, portal.box());
    }

    @Test
    public void aPressInTheCaptionOfAnotherWindowMakesItTheOneTheDocumentFollows() {
        ClientPortal smallContent = (ClientPortal) small.frame().content().children().get(0);
        ClientPortal largeContent = (ClientPortal) large.frame().content().children().get(0);
        report(smallContent);
        report(largeContent);
        application.activate(smallContent);
        assertEquals("the small window", smallContent.box().width(), application.windowWidth(), 0.5f);

        ClientPortal caption = captionIn(large.frame());
        assertNotNull("the large window adopted its bar", caption);
        application.activate(caption);
        assertEquals("the large window, by its caption", largeContent.box().width(), application.windowWidth(), 0.5f);
        assertEquals("with the caption as the origin of its popups", caption, application.active());
    }
}
