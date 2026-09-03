package com.crystalgui.widget.control;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.contract.WidgetContracts;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * <b>{@link Checkbox} and {@link CheckboxGroup} on the new engine</b> — M6.1.
 *
 * <p>Same division as {@code ButtonPortTest}: {@code CheckboxTest} asserts the behaviour and moves
 * here wholesale at 6.9. What this covers is what the PORT could have broken silently — the parts
 * being a shadow tree, {@code :checked} still re-matching, and the group's membership bookkeeping,
 * which the port changed on purpose.</p>
 */
public class CheckboxPortTest extends UiDocumentTestBase {

    private Checkbox checkbox(String label) {
        Checkbox c = new Checkbox(label);
        layout(c, l -> l.width(100f).height(20f));
        document.append(c);
        layoutOnly();
        return c;
    }

    /** The mark and the label are shadow parts, not light children a caller can reach. */
    @Test
    public void itsPartsAreAShadowTree() {
        Checkbox c = checkbox("Wrap lines");

        assertEquals("nothing a caller added", 0, c.children().size());
        assertNotNull(c.shadowRoot());
        assertTrue(composed(c).stream().anyMatch(n -> "mark".equals(n.get(Attribute.PART))));
        assertTrue(composed(c).stream().anyMatch(n -> "label".equals(n.get(Attribute.PART))));
    }

    /**
     * Toggling re-matches the cascade.
     *
     * <p>{@code :checked} is a call to {@link Checkbox#isChecked()}, so the engine has no way to know
     * the answer moved — every shipped rule for the mark hangs off it, and without the invalidation
     * the mark keeps drawing its old state under a checkbox that reports the new one. The old engine
     * records this failure three times over ({@code :checked}, {@code :disabled}, {@code :hover}),
     * once per widget that met it.</p>
     */
    @Test
    public void togglingRematchesTheCascade() {
        withDefaultStyles();
        Checkbox c = checkbox("Wrap lines");
        frame();

        UIElement mark = composed(c).stream()
                                    .filter(n -> "mark".equals(n.get(Attribute.PART))).findFirst().orElseThrow();

        // The OVERLAY, not the background: `checkbox:checked ::part(mark)` sets `overlay:
        // shape("checkmark")` and the unchecked rule sets none, so the two are distinguishable
        // without comparing two drawables for equality. And `background` is a DIFFERENT PROPERTY
        // from `background-color` -- the sheet writes the former, so reading the latter here found
        // the same value in both states and failed against a cascade that was working.
        assertSame("nothing draws a mark while it is unchecked", CgUiDrawable.EMPTY,
                mark.computedStyle().get(StylePropertyRegistry.OVERLAY));

        c.setChecked(true);
        frame();

        assertNotSame("the :checked rule reached the mark through ::part(mark)", CgUiDrawable.EMPTY,
                mark.computedStyle().get(StylePropertyRegistry.OVERLAY));
    }

    /** Only the left button toggles — see {@code ButtonPortTest} for what the missing check cost. */
    @Test
    public void onlyTheLeftButtonToggles() {
        Checkbox c = checkbox("Wrap lines");
        frame();

        press(50f, 10f, CgMouseCodes.RIGHT_BUTTON);
        release(50f, 10f, CgMouseCodes.RIGHT_BUTTON);
        assertFalse("a right-click is not a toggle", c.isChecked());

        press(50f, 10f);
        release(50f, 10f);
        assertTrue("and a left one is", c.isChecked());
    }

    /** A disabled checkbox does not toggle. */
    @Test
    public void aDisabledCheckboxDoesNotToggle() {
        Checkbox c = checkbox("Wrap lines");
        c.setEnabled(false);
        frame();

        press(50f, 10f);
        release(50f, 10f);

        assertFalse(c.isChecked());
    }

    /** The signal fires for a programmatic change too, which is what a group listens to. */
    @Test
    public void theSignalFiresForProgrammaticChangesAndNotForUnchangedOnes() {
        Checkbox c = checkbox("Wrap lines");
        List<Boolean> fired = new ArrayList<>();
        c.attachListener(fired::add);

        c.setChecked(true);
        c.setChecked(true);
        c.setChecked(false);

        assertEquals(List.of(true, false), fired);
    }

    /** A group keeps exactly one member checked. */
    @Test
    public void aGroupIsExclusive() {
        Checkbox a = checkbox("A");
        Checkbox b = checkbox("B");
        CheckboxGroup group = new CheckboxGroup();
        a.setGroup(group);
        b.setGroup(group);

        a.setChecked(true);
        assertSame(a, group.getCurrent());

        b.setChecked(true);
        assertFalse("the first one was unchecked by the group", a.isChecked());
        assertSame(b, group.getCurrent());
    }

    /** With {@code allowEmpty(false)} it refuses to end up with nothing checked. */
    @Test
    public void aGroupThatRefusesEmptyKeepsItsCurrentChecked() {
        Checkbox a = checkbox("A");
        CheckboxGroup group = new CheckboxGroup().allowEmpty(false);
        a.setGroup(group);
        a.setChecked(true);

        a.setChecked(false);

        assertTrue("the group put it back", a.isChecked());
        assertSame(a, group.getCurrent());
    }

    /**
     * <b>Leaving a group actually leaves it, and rejoining does not double the listener.</b>
     *
     * <p>The one behaviour the port changed. {@code setGroup(null)} used to drop the field and leave
     * the group still holding the checkbox and still listening to it — its own javadoc named
     * {@link CheckboxGroup#unregister} as the thing to call instead, which is a rule in prose where a
     * line of code will do. Making it leave properly then made <em>rejoining</em> reachable, and
     * {@code register} attached a listener every time it was called: a member that left and came back
     * was unchecked twice per change by two identical listeners.</p>
     *
     * <p>The double-listener half is invisible from the group's own state — {@code getCurrent} is
     * right either way — so it is asserted through the checkbox's signal count.</p>
     */
    @Test
    public void leavingAGroupLeavesItAndRejoiningDoesNotDoubleTheListener() {
        Checkbox a = checkbox("A");
        Checkbox b = checkbox("B");
        CheckboxGroup group = new CheckboxGroup();
        a.setGroup(group);
        b.setGroup(group);
        a.setChecked(true);

        a.setGroup(null);
        assertNull("the group forgot the member that left", group.getCurrent());
        assertEquals(List.of(b), group.getMembers());

        // Checking b must no longer reach a, which is what "left" means.
        b.setChecked(true);
        assertTrue("a is no longer the group's business", a.isChecked());

        // Rejoin, then count how many times ONE change is observed.
        a.setChecked(false);
        a.setGroup(group);
        List<Boolean> fired = new ArrayList<>();
        a.attachListener(fired::add);
        a.setChecked(true);

        assertEquals("one change, one notification", List.of(true), fired);
        assertEquals(List.of(b, a), group.getMembers());
    }

    /** The kind is registered with its contract. */
    @Test
    public void theKindIsRegisteredWithItsContract() {
        assertTrue(UIElementRegistry.isRegistered(Checkbox.NAME));
        assertTrue(UIElementRegistry.create(Checkbox.NAME) instanceof Checkbox);
        assertEquals("checkbox", Checkbox.NAME.local());
        assertSame(Checkbox.CONTRACT, UIElementRegistry.contractFor(Checkbox.NAME));
        assertSame(Checkbox.CONTRACT, WidgetContracts.of(Checkbox.class));
    }

    /**
     * LABEL is applied before CHECKED.
     *
     * <p>Declaration order is apply order, and this is one of the widgets that depends on it: a group
     * can refuse a check on arrival, so the widget has to already look like itself when it does.
     * Asserted against a FRESH instance, which is the only assertion a reordered contract cannot
     * satisfy.</p>
     */
    @Test
    public void theContractAppliesTheLabelBeforeTheCheck() {
        List<String> keys = new ArrayList<>();
        Checkbox.CONTRACT.states().forEach(s -> keys.add(s.key()));
        assertEquals(List.of("label", "checked"), keys);
        assertSame("and the primary slot is what the widget IS", Checkbox.CHECKED,
                Checkbox.CONTRACT.primary());
    }

    /** A type selector matches on either engine. */
    @Test
    public void aTypeSelectorStillMatchesIt() {
        Checkbox c = checkbox("A");
        assertTrue(c.matchesType("checkbox"));
        assertEquals("crystalgui:checkbox", c.tagName());
    }
}
