package com.crystalgui.app.uibuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.text.UIText;

import dev.vfyjxf.taffy.style.FlexDirection;

/** The Element tab's heading says what the Hierarchy row says, and follows a layout change the same way. */
public class KindHeaderTest extends UiDocumentTestBase {

    @Before
    public void registry() {
        UIElementRegistry.bootstrap();
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
    }

    private static String title(KindHeader header) {
        for (UIElement child : header.children()) {
            if (child instanceof UIText text && !text.hasClass(KindHeader.TAG_CLASS)) return text.getText();
        }
        return null;
    }

    private static String tag(KindHeader header) {
        for (UIElement child : header.children()) {
            if (child instanceof UIText text && text.hasClass(KindHeader.TAG_CLASS)) return text.getText();
        }
        return null;
    }

    @Test
    public void aContainerHeadingReadsItsLayoutAndFollowsAChangeOnTheNextFrame() {
        UIElement node = new UIElement();
        node.append(new UIElement(), new UIElement());
        document.append(node);
        KindHeader header = new KindHeader(node);
        document.append(header);
        document.update(W, H);
        frame();
        assertEquals("Column layout", title(header));
        assertEquals("a sheet spells this engine's kinds without their namespace", "element", tag(header));

        node.layout(l -> l.flexDirection(FlexDirection.ROW));
        document.update(W, H);
        frame();
        assertEquals("Row layout", title(header));
    }

    @Test
    public void anAddonKindKeepsItsNamespaceInTheTag() {
        assertEquals("mymod:machine", KindHeader.tagOf(Name.of("mymod", "machine")));
        assertTrue(KindHeader.tagOf(Name.of("button")).equals("button"));
    }
}
