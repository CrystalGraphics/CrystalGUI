package com.crystalgui.desktop.dialog;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgui.desktop.Desktop;
import com.crystalgui.desktop.motion.WindowAnimator;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIElement;

/** A resizable report box stops shrinking where its footer would be cut off. */
public class ReportBoxMinimumTest extends UiDocumentTestBase {

    private boolean animationsWere;
    private WindowFrame frame;

    @Before
    public void arrange() throws IOException {
        animationsWere = WindowAnimator.isEnabled();
        WindowAnimator.setEnabled(false);
        withDefaultStyles();
        Desktop desktop = Desktop.of(document).useStorage(Files.createTempDirectory("report"));
        StringBuilder trace = new StringBuilder();
        for (int i = 0; i < 12; i++) trace.append("  at some.package.Type.method(Type.java:").append(i).append(")\n");
        frame = FailureWindow.openReport(desktop, null, "Boom", "Boom", trace.toString(),
                List.of(new FailureWindow.Action("Copy", () -> { }), new FailureWindow.Action("Close", () -> { })));
        frame();
    }

    @After
    public void restore() {
        WindowAnimator.setEnabled(animationsWere);
    }

    private static UIElement find(UIElement root, String cls) {
        if (root.hasClass(cls)) return root;
        for (UIElement child : root.children()) {
            UIElement hit = find(child, cls);
            if (hit != null) return hit;
        }
        return null;
    }

    @Test
    public void theFooterStaysWholeWhenTheWindowIsAskedForLessThanItNeeds() {
        frame.resizeTo(300f, 10f);
        frame();
        frame();
        UIElement footer = find(frame.content(), FailureWindow.FOOTER_CLASS);
        assertNotNull(footer);
        Box content = frame.content().box(), foot = footer.box();
        float bottom = foot.y() + foot.height();
        Box text = find(frame.content(), FailureWindow.TEXT_CLASS).box(), scroll = find(frame.content(), FailureWindow.SCROLL_CLASS).box();
        assertTrue("the text area ends inside its column: " + (scroll.y() + scroll.height()) + " of " + text.height(),
                scroll.y() + scroll.height() <= text.height() + 0.5f);
        assertTrue("footer inside the content: " + bottom + " of " + content.height(), bottom <= content.height() + 0.5f);
    }
}
