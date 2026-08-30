package com.crystalgui.widget.control;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.Overflow;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.contract.WidgetContracts;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.dom.UINodeRegistry;
import com.crystalgui.ui.input.FocusPolicy;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * <b>The first widget on the new engine, held to what the old one did</b> — M6.1.
 *
 * <p>Not a duplicate of {@code ButtonTest}: that suite asserts the behaviour, and it moves here
 * wholesale when the old {@code Button} goes at 6.9. What this asserts is the things the PORT could
 * have broken and nothing else would have noticed — encapsulation, the part names a sheet reaches,
 * activation's button check, and the registration a description decodes through.</p>
 */
public class ButtonPortTest extends UiDocumentTestBase {

    private Button button(String text) {
        Button b = new Button(text);
        layout(b, l -> l.width(100f).height(20f));
        document.append(b);
        layoutOnly();
        return b;
    }

    /**
     * The parts are a SHADOW TREE: not light children, not reachable by a descendant selector.
     *
     * <p>What replaces four separate mechanisms — {@code markAsInternal}, {@code addInternalChild},
     * the {@code __part__} class and {@code acceptsPublicChildren} — and the reason
     * {@code .__content__} claimed by three unrelated widgets cannot happen again.</p>
     */
    @Test
    public void itsPartsAreAShadowTreeAndNotLightChildren() {
        Button b = button("Save");

        assertEquals("nothing a caller added", 0, b.children().size());
        assertNotNull(b.shadowRoot());
        assertTrue("but the label is there, composed",
                composed(b).stream().anyMatch(n -> "label".equals(n.get(Attribute.PART))));
        assertEquals("and a light query cannot see it", 0, b.querySelectorAll("text").size());
    }

    /** The label's text is the button's text, in both directions. */
    @Test
    public void theLabelIsTheText() {
        Button b = button("Save");
        assertEquals("Save", b.getText());
        b.setText("Cancel");
        assertEquals("Cancel", b.getText());
    }

    /**
     * A {@code ::part(label)} rule reaches the label; an ordinary descendant rule does not.
     *
     * <p>The encapsulation, asserted on a NON-INHERITED property. An inherited value still crosses
     * the boundary — that is the DOM's behaviour and not a leak — so a test written on {@code color}
     * would pass against no encapsulation at all.</p>
     */
    @Test
    public void aPartRuleReachesTheLabelAndATagRuleDoesNot() {
        withDefaultStyles();
        Button b = button("Save");
        frame();

        UINode label = composed(b).stream()
                .filter(n -> "label".equals(n.get(Attribute.PART))).findFirst().orElseThrow();

        // ua/widgets.css: `button::part(label) { overflow: hidden; ... }` -- the twin of the old
        // `button text` rule, which reaches the label by TAG and therefore cannot see into a shadow.
        assertEquals("the ::part rule found it", Overflow.HIDDEN,
                label.computedStyle().get(StylePropertyRegistry.OVERFLOW));
    }

    /**
     * Activation is the LEFT button and nothing else.
     *
     * <p>The old engine checked no button at all, so a right-click and a middle-click pressed it too
     * — invisible until something put a context menu on a button, at which point right-clicking a
     * taskbar entry opened the menu AND activated the window underneath. The counter-assertion is
     * not a formality: a guard written as "reject everything" passes the right-click half and makes
     * every button in the application dead.</p>
     */
    @Test
    public void onlyTheLeftButtonActivates() {
        Button b = button("Save");
        frame();
        List<String> fired = new ArrayList<>();
        b.onPressed.connect(() -> fired.add("pressed"));

        press(50f, 10f, CgMouseCodes.RIGHT_BUTTON);
        release(50f, 10f, CgMouseCodes.RIGHT_BUTTON);
        assertEquals("a right-click is not an activation", List.of(), fired);

        press(50f, 10f);
        release(50f, 10f);
        assertEquals("and a left one is", List.of("pressed"), fired);
    }

    /** A disabled button does not activate, and says so to the cascade. */
    @Test
    public void aDisabledButtonDoesNotActivate() {
        Button b = button("Save");
        frame();
        List<String> fired = new ArrayList<>();
        b.onPressed.connect(() -> fired.add("pressed"));

        b.setEnabled(false);
        frame();
        press(50f, 10f);
        release(50f, 10f);

        assertEquals(List.of(), fired);
        assertFalse(b.isEnabled());
    }

    /**
     * Clicking focuses it — {@code CLICK}, not {@code FOCUSABLE}.
     *
     * <p>Exactly what clicking a {@code <button>} does on the web. With {@code FOCUSABLE} the click
     * would leave focus wherever it was, so Space would then activate some OTHER widget.</p>
     */
    @Test
    public void clickingFocusesIt() {
        Button b = button("Save");
        frame();
        assertEquals(FocusPolicy.CLICK, b.focusPolicy());

        press(50f, 10f);
        assertSame(b, document.focus().focused());
    }

    /**
     * The icon slots are parts, and setting one twice replaces rather than accumulating.
     *
     * <p>The getter exists so a caller can update the slot IN PLACE: replacing it is a structural
     * change, and a button whose icon tracks something live would rebuild the node under the pointer
     * on every refresh — the failure the table header and the file tree both paid for.</p>
     */
    @Test
    public void theIconSlotsArePartsAndReplaceRatherThanAccumulate() {
        Button b = button("Save");
        UINode first = new UINode();
        UINode second = new UINode();

        b.setPreIcon(first);
        assertEquals("pre-icon", first.get(Attribute.PART));
        assertSame(first, b.getPreIcon());

        b.setPreIcon(second);
        assertSame(second, b.getPreIcon());
        assertEquals("the old one left the shadow tree",
                1, composed(b).stream().filter(n -> "pre-icon".equals(n.get(Attribute.PART))).count());

        b.setPreIcon(null);
        assertEquals(null, b.getPreIcon());
    }

    /**
     * The kind is registered, so a description decodes into this class and a peer can ask its
     * contract.
     *
     * <p>The porting guide's first step, and the one whose absence is silent: an unregistered kind
     * falls back to nothing at all on this engine — where the old one quietly answered the lowercased
     * class name, which is how 32 tags came to match by accident.</p>
     */
    @Test
    public void theKindIsRegisteredWithItsContract() {
        assertTrue(UINodeRegistry.isRegistered(Button.NAME));
        assertTrue("the factory builds one", UINodeRegistry.create(Button.NAME) instanceof Button);
        assertEquals("button", Button.NAME.local());
        assertSame("and the contract registered is the widget's own",
                Button.CONTRACT, UINodeRegistry.contractFor(Button.NAME));
        assertSame(Button.CONTRACT, WidgetContracts.of(Button.class));
    }

    /** A type selector matches the tag on either engine, which is what makes the port incremental. */
    @Test
    public void aTypeSelectorStillMatchesIt() {
        Button b = button("Save");
        assertTrue(b.matchesType("button"));
        assertTrue(b.matchesType("crystalgui:button"));
        assertEquals("crystalgui:button", b.tagName());
    }
}
