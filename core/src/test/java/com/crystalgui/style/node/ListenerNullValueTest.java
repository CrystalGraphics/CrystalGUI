package com.crystalgui.style.node;

import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.Overflow;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Losing the only rule that set a property must not take the frame loop down.
 *
 * <p>{@code getComputed} answers <b>null</b> for a property nothing has written — the initial value is
 * not a candidate — so a listener's {@code newVal} is declared {@code @Nullable} and every layout
 * property falls back to {@code initialValue} through {@code LayoutProperties.createSetter}. The
 * hand-written {@code overflow} listener did not, and it is reached by the most ordinary thing there is:
 * REMOVING A CLASS. The rematch withdraws the sheet's candidate, nothing else answers, and the raw null
 * reached a {@code switch} — {@code NullPointerException} from inside {@code calculateStyle}, which
 * fails the whole frame rather than the element.</p>
 *
 * <p>Not a taskbar fact, though that is where it surfaced: it is true of any element that gains and then
 * loses the one rule mentioning a property whose listener reads the value.</p>
 */
public class ListenerNullValueTest extends UiDocumentTestBase {

    private static final String CLIPPED = "clipme";

    private UIElement element;

    @Before
    public void setUp() {
        UIElement root = new UIElement().layout(l -> l.width(400).height(300));
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.parse("." + CLIPPED + " { overflow: clip; }"));
        element = new UIElement();
        root.append(element);
        settle();
    }

    private void settle() {
        for (int i = 0; i < 3; i++) frame();
    }

    @Test
    public void droppingTheOnlyRuleThatSetOverflowDoesNotThrow() {
        assertNull("precondition: nothing has written overflow yet",
                element.getStyle().getComputed(StylePropertyRegistry.OVERFLOW));

        element.addClass(CLIPPED);
        settle();
        assertEquals("precondition: the sheet is the property's only source",
                Overflow.CLIP, element.getStyle().getComputed(StylePropertyRegistry.OVERFLOW));

        // The rematch withdraws that candidate and leaves the property with none at any origin. The
        // listener is then handed null, and before the fix it went straight into a switch.
        element.removeClass(CLIPPED);
        settle();

        assertNull("back to no candidate, which is what the listener has to tolerate",
                element.getStyle().getComputed(StylePropertyRegistry.OVERFLOW));
        assertNotNull("the element survived the rematch", element.document());
    }
}
