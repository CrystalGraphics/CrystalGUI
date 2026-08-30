package com.crystalgui.ui.service;

import static com.crystalgui.ui.service.ServiceFixtures.at;
import static com.crystalgui.ui.service.ServiceFixtures.frame;
import static com.crystalgui.ui.service.ServiceFixtures.key;
import static com.crystalgui.ui.service.ServiceFixtures.press;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Document;
import com.crystalgui.ui.dom.Node;
import com.crystalgui.ui.dom.ShadowRoot;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * The focus rows of {@code AGENTS.md}, restated as assertions against the new service.
 *
 * <p>Every one of these was a bug in the old engine before it was a rule, and several could only be
 * seen through the real press path — a test that dispatches straight at a node skips the focus walk
 * entirely, which is how sixteen passing tests once shipped a menu bar that resolved every command
 * against the wrong element.</p>
 */
public class FocusServiceTest {

    private static Node focusable(String id, float x, float y, FocusPolicy policy) {
        return at(id, x, y, 60, 30).setFocusPolicy(policy);
    }

    // ── The policy's four values, and the two that look alike ────────────────

    @Test
    public void clickNotTabbableIsFullyClickableAndOutOfTheTabRing() {
        Document document = new Document();
        Node tab = focusable("tab", 0, 0, FocusPolicy.CLICK_NOT_TABBABLE);
        Node after = focusable("after", 0, 100, FocusPolicy.CLICK);
        document.append(tab).append(after);
        frame(document);

        press(document, 20, 10);
        assertSame("click-focus tests focusesOnClick(), never == CLICK -- an equality check makes "
                + "every unselected tab dead to the mouse", tab, document.focus().focused());

        document.focus().clear();
        assertTrue(key(document, CgKeyCodes.KEY_TAB, true));
        assertSame("but Tab skips it: the roving-tabindex pattern is one tab stop, not ten",
                after, document.focus().focused());
    }

    @Test
    public void focusDelegationAndTabTraversalAskDifferentQuestions() {
        Document document = new Document();
        Node scope = at("scope", 0, 0, 400, 300).set(Attribute.FOCUS_SCOPE, true);
        Node notTabbable = focusable("roving", 10, 10, FocusPolicy.CLICK_NOT_TABBABLE);
        Node tabbable = focusable("stop", 10, 100, FocusPolicy.CLICK);
        scope.append(notTabbable).append(tabbable);
        document.append(scope);
        frame(document);

        assertSame("delegation gates on focusable()", notTabbable, document.focus().firstFocusableIn(scope));
        assertSame("traversal gates on tabbable()", tabbable, document.focus().firstTabbableIn(scope));
    }

    // ── A focusable container is a wall ──────────────────────────────────────

    @Test
    public void aContainerThatDelegatesFocusHandsItToWhatIsInside() {
        Document document = new Document();
        Node dock = at("dock", 0, 0, 400, 300).setFocusPolicy(FocusPolicy.CLICK);
        ShadowRoot shadow = dock.attachShadow(true);
        Node editor = focusable("editor", 10, 10, FocusPolicy.CLICK);
        shadow.append(editor);
        document.append(dock);
        frame(document);

        document.focus().requestFocus(dock);
        assertSame("a container takes a policy so its COMMANDS resolve; delegation is how it stops "
                + "being a wall its content is never reached through", editor, document.focus().focused());
    }

    // ── Click focus ──────────────────────────────────────────────────────────

    @Test
    public void aPressFocusesTheNearestAncestorThatTakesFocusOnClick() {
        Document document = new Document();
        Node button = focusable("button", 0, 0, FocusPolicy.CLICK);
        Node label = at("label", 5, 5, 20, 10);
        button.append(label);
        document.append(button);
        frame(document);

        press(document, 10, 8);
        assertSame("the DOM's rule -- which is why clicking a button's inner text focuses the button",
                button, document.focus().focused());
    }

    @Test
    public void onlyThePrimaryButtonMovesFocus() {
        Document document = new Document();
        Node a = focusable("a", 0, 0, FocusPolicy.CLICK);
        Node b = focusable("b", 0, 100, FocusPolicy.CLICK);
        document.append(a).append(b);
        frame(document);
        press(document, 20, 10);
        assertSame(a, document.focus().focused());

        press(document, 20, 110, CgMouseCodes.RIGHT_BUTTON);
        assertSame("a right-click opens a menu ABOUT something; it does not choose it -- a list that "
                + "drives selection from focus would have its selection destroyed by its own menu",
                a, document.focus().focused());
    }

    @Test
    public void aPressOnNothingBlursButAPressAModalAteDoesNot() {
        Document document = new Document();
        Node field = focusable("field", 0, 0, FocusPolicy.CLICK);
        document.append(field);
        frame(document);
        press(document, 20, 10);
        assertSame(field, document.focus().focused());

        press(document, 600, 500);
        assertNull("click empty space and the active element goes away, as a browser does",
                document.focus().focused());

        Node dialog = at("dialog", 100, 100, 200, 150).set(Attribute.FOCUS_SCOPE, true);
        Node inside = focusable("inside", 110, 110, FocusPolicy.CLICK);
        dialog.append(inside);
        document.append(dialog);
        frame(document);
        document.focus().pushModal(dialog);
        document.focus().requestFocus(inside);

        press(document, 600, 500);
        assertSame("inertness ATE that press -- dropping the caret out of a dialog when its backdrop "
                + "is clicked is what no dialog anywhere does", inside, document.focus().focused());
    }

    // ── Rings ────────────────────────────────────────────────────────────────

    @Test
    public void programmaticAndKeyboardFocusRingAndAClickDoesNot() {
        Document document = new Document();
        Node a = focusable("a", 0, 0, FocusPolicy.CLICK);
        document.append(a);
        frame(document);

        press(document, 20, 10);
        assertTrue(a.isFocused());
        assertFalse(":focus-visible exists to ring keyboard focus and NOT clicks", a.isFocusVisible());

        document.focus().clear();
        document.focus().requestFocus(a);
        assertTrue("a widget that focuses itself through the programmatic path rings", a.isFocusVisible());
    }

    @Test
    public void aFocusedTextInputRingsHoweverItWasFocused() {
        Document document = new Document();
        Node field = new Node() {
            @Override
            public boolean consumesTextInput() {
                return true;
            }
        };
        field.setId("field").setFocusPolicy(FocusPolicy.CLICK);
        ServiceFixtures.layout(field, l -> l.width(100f).height(30f));
        document.append(field);
        frame(document);

        press(document, 20, 10);
        assertTrue("a caret alone is a weak affordance, and the field is where typing goes",
                field.isFocusVisible());
    }

    // ── focus-within ─────────────────────────────────────────────────────────

    @Test
    public void everyAncestorOfTheFocusOwnerIsFocusWithin() {
        Document document = new Document();
        Node panel = at("panel", 0, 0, 300, 200);
        Node control = focusable("control", 10, 10, FocusPolicy.CLICK);
        panel.append(control);
        document.append(panel);
        frame(document);

        document.focus().requestFocus(control);
        assertTrue(panel.isFocusWithin());
        assertFalse("the owner is focused, not focus-WITHIN", control.isFocusWithin());

        document.focus().clear();
        document.focus().blurIfFocused(control);
        assertFalse(panel.isFocusWithin());
    }

    // ── Modality: one predicate, scoped ──────────────────────────────────────

    @Test
    public void aModalMakesItsOwnScopeInertAndLeavesOtherScopesAlone() {
        Document document = new Document();
        Node windowA = at("a", 0, 0, 300, 300).set(Attribute.FOCUS_SCOPE, true);
        Node contentA = focusable("content-a", 10, 10, FocusPolicy.CLICK);
        Node dialogA = at("dialog-a", 20, 20, 200, 150).set(Attribute.FOCUS_SCOPE, true);
        Node inDialog = focusable("in-dialog", 30, 30, FocusPolicy.CLICK);
        dialogA.append(inDialog);
        windowA.append(contentA).append(dialogA);

        Node windowB = at("b", 400, 0, 300, 300).set(Attribute.FOCUS_SCOPE, true);
        Node contentB = focusable("content-b", 410, 10, FocusPolicy.CLICK);
        windowB.append(contentB);
        document.append(windowA).append(windowB);
        frame(document);

        document.focus().pushModal(dialogA);
        assertTrue("blocked inside its own scope", document.focus().isInert(contentA));
        assertFalse("and not inside the modal", document.focus().isInert(inDialog));
        assertFalse("a modal in ANOTHER window's scope blocks nothing here -- the whole point of scoping it",
                document.focus().isInert(contentB));

        assertSame("and which modal is responsible is a question about the scope PRESSED",
                dialogA, document.focus().blockingModal(contentA));
        assertNull(document.focus().blockingModal(contentB));

        document.focus().popModal(dialogA);
        assertFalse(document.focus().isInert(contentA));
    }

    @Test
    public void tabIsTrappedInsideTheModalOverTheFocusedScope() {
        Document document = new Document();
        Node content = focusable("content", 0, 0, FocusPolicy.CLICK);
        Node dialog = at("dialog", 100, 100, 200, 150).set(Attribute.FOCUS_SCOPE, true);
        Node first = focusable("first", 110, 110, FocusPolicy.CLICK);
        Node second = focusable("second", 110, 150, FocusPolicy.CLICK);
        dialog.append(first).append(second);
        document.append(content).append(dialog);
        frame(document);
        document.focus().pushModal(dialog);
        document.focus().requestFocus(first);

        assertTrue(key(document, CgKeyCodes.KEY_TAB, true));
        assertSame(second, document.focus().focused());
        assertTrue(key(document, CgKeyCodes.KEY_TAB, true));
        assertSame("wraps INSIDE the trap; the content behind is never reached", first, document.focus().focused());
    }

    @Test
    public void aDetachedModalIsPoppedRatherThanLeavingTheTreeInert() {
        Document document = new Document();
        Node content = focusable("content", 0, 0, FocusPolicy.CLICK);
        Node dialog = at("dialog", 100, 100, 200, 150).set(Attribute.FOCUS_SCOPE, true);
        document.append(content).append(dialog);
        frame(document);
        document.focus().pushModal(dialog);
        assertTrue(document.focus().isInert(content));

        document.lifecycle().destroy(dialog);
        assertFalse("a modal that left without closing would keep the whole tree inert with nothing "
                + "left to interact with -- unrecoverable from the user's side",
                document.focus().isInert(content));
    }

    // ── Tab traversal ────────────────────────────────────────────────────────

    @Test
    public void tabWrapsAtBothEnds() {
        Document document = new Document();
        Node a = focusable("a", 0, 0, FocusPolicy.CLICK);
        Node b = focusable("b", 0, 100, FocusPolicy.CLICK);
        document.append(a).append(b);
        frame(document);
        document.focus().requestFocus(b);

        assertTrue(key(document, CgKeyCodes.KEY_TAB, true));
        assertSame(a, document.focus().focused());
    }

    @Test
    public void aDisabledOrInertNodeIsNotInTheTabRing() {
        Document document = new Document();
        Node a = focusable("a", 0, 0, FocusPolicy.CLICK);
        Node blocked = focusable("blocked", 0, 100, FocusPolicy.CLICK).set(Attribute.ENABLED, false);
        Node c = focusable("c", 0, 200, FocusPolicy.CLICK);
        document.append(a).append(blocked).append(c);
        frame(document);
        document.focus().requestFocus(a);

        assertTrue(key(document, CgKeyCodes.KEY_TAB, true));
        assertSame("stepped over, and the step is bounded by construction rather than by a scan limit",
                c, document.focus().focused());
    }

    @Test
    public void shiftTabWalksBackwards() {
        Document document = new Document();
        Node a = focusable("a", 0, 0, FocusPolicy.CLICK);
        Node b = focusable("b", 0, 100, FocusPolicy.CLICK);
        document.append(a).append(b);
        frame(document);
        document.focus().requestFocus(b);

        int shift = CgModifiers.SHIFT;
        assertTrue(document.focus().moveTabFocus(CgKeyCodes.KEY_TAB, shift));
        assertSame(a, document.focus().focused());
        assertTrue(document.focus().moveTabFocus(CgKeyCodes.KEY_TAB, shift));
        assertSame("wraps at the other end too", b, document.focus().focused());
    }

    // ── Not rendered is not focusable ────────────────────────────────────────

    @Test
    public void aNodeWithNoBoxCannotTakeFocus() {
        Document document = new Document();
        Node hidden = focusable("hidden", 0, 0, FocusPolicy.CLICK);
        ServiceFixtures.layout(hidden, l -> l.display(TaffyDisplay.NONE));
        document.append(hidden);
        frame(document);

        document.focus().requestFocus(hidden);
        assertNull("a closed dialog is display: none, and every box in it measures zero",
                document.focus().focused());
    }

    // ── The announcement ─────────────────────────────────────────────────────

    @Test
    public void theFocusOwnerIsAnnouncedOncePerRealChange() {
        Document document = new Document();
        Node a = focusable("a", 0, 0, FocusPolicy.CLICK);
        Node b = focusable("b", 0, 100, FocusPolicy.CLICK);
        document.append(a).append(b);
        frame(document);

        List<String> announced = new ArrayList<>();
        document.focus().onDidChangeFocus.connect(node -> announced.add(node == null ? "-" : node.id()));

        press(document, 20, 10);
        press(document, 20, 110);
        assertSame(b, document.focus().focused());
        assertEquals("every REAL state, and no state twice -- the empty moment between a blur and the "
                + "focus that follows it is real, and a listener reading the tree there sees no owner",
                List.of("a", "-", "b"), announced);
    }
}
