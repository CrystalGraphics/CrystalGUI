package com.crystalgui.ui;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.Dialog;
import com.crystalgui.ui.elements.Dropdown;
import com.crystalgui.ui.elements.Menu;
import com.crystalgui.ui.elements.MenuItem;
import com.crystalgui.ui.elements.Popover;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.input.UIInputHandler;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The Popover API port — light dismiss, the popover stack, Escape, and the menu/dropdown widgets on top.
 *
 * <p>The mechanism worth testing hardest is <b>light dismiss</b>, because its two failure modes are
 * opposites and each is individually plausible: dismiss too eagerly and a submenu cannot be reached
 * without killing its parent, or dismiss too timidly and a dropdown button closes on press and reopens on
 * click, flickering forever. Both come down to what counts as "inside" a popover, which is why the invoker
 * carve-out gets its own tests.</p>
 */
public class PopoverTest extends UiTestBase {

    private UIWindow window;
    private UIInputHandler input;
    private UIElement root;
    private UIElement outside;
    private UIElement invoker;
    private Popover popover;

    @Before
    public void build() {
        root = new UIElement().layout(l -> l.width(400).height(300));

        outside = new UIElement().layout(l -> l.width(60).height(20));
        outside.setId("outside");
        outside.setFocusPolicy(FocusPolicy.CLICK);
        root.addChild(outside);

        invoker = new UIElement().layout(l -> l.width(60).height(20));
        invoker.setId("invoker");
        invoker.setFocusPolicy(FocusPolicy.CLICK);
        root.addChild(invoker);

        popover = new Popover();
        popover.layout(l -> l.width(80).height(40));
        root.addChild(popover);

        window = new UIWindow(Ui.of(root));
        window.init(800, 600); // uiScale 2
        settle();
        input = window.getInputHandler();
        input.beginFrame();
        input.endFrame();
    }

    private void settle() {
        window.getStyleEngine().calculateStyle(0.016f);
        window.calculateLayout();
    }

    /** A press at a logical point, through the real input path. */
    private void pressAt(float logicalX, float logicalY) {
        int px = Math.round(logicalX * 2f), py = Math.round(logicalY * 2f);
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(px, py, 0, 0, -1, false, 0f, -1L));
        input.beginFrame();
        input.endFrame();
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(px, py, 0, 0, 0, true, 0f, 1L));
        input.beginFrame();
        input.endFrame();
    }

    private void escape() {
        input.consumeKeyboardEvent(new CgSystemInput.Keyboard.Event('\0', CgKeyCodes.KEY_ESCAPE, true, false, 0L));
        input.consumeKeyboardEvent(new CgSystemInput.Keyboard.Event('\0', CgKeyCodes.KEY_ESCAPE, false, false, 0L));
    }

    private void key(int code) {
        input.consumeKeyboardEvent(new CgSystemInput.Keyboard.Event('\0', code, true, false, 0L));
        input.consumeKeyboardEvent(new CgSystemInput.Keyboard.Event('\0', code, false, false, 0L));
    }

    // ── Showing ─────────────────────────────────────────────────────────────

    @Test
    public void showingPromotesAndJoinsThePopoverStack() {
        assertFalse(popover.isOpen());

        popover.showFor(invoker, invoker);
        settle();

        assertTrue(popover.isOpen());
        assertTrue("a popover paints above everything, like a tooltip", popover.isInTopLayer());
        assertEquals(1, window.getAutoPopovers().size());
        assertSame(popover, window.getTopCloseWatcher());
    }

    @Test
    public void hidingUndoesAllOfIt() {
        popover.showFor(invoker, invoker);
        settle();
        popover.hide();
        settle();

        assertFalse(popover.isOpen());
        assertFalse(popover.isInTopLayer());
        assertTrue(window.getAutoPopovers().isEmpty());
        assertNull(window.getTopCloseWatcher());
    }

    @Test
    public void aDetachedPopoverCannotBeShown() {
        Popover orphan = new Popover();
        try {
            orphan.showFor(invoker, invoker);
            fail("expected an IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("attached"));
        }
    }

    // ── Fade-in ─────────────────────────────────────────────────────────────

    /**
     * The fade's <b>precondition</b>: a closed popover is already transparent, so opening has something to
     * interpolate from.
     *
     * <p>This is the assertion that distinguishes the working mechanism from the one that silently did not.
     * An earlier attempt hand-rolled a starting style — one frame of {@code opacity: 0} at IMPORTANT origin,
     * removed on the next tick — and defeated itself: that {@code 1 -> 0} write is a transitionable change
     * too, so the engine eased <em>toward</em> zero and the removal retargeted it back before it arrived. In
     * that version a closed popover was fully opaque, which is exactly what this catches.</p>
     *
     * <p><b>Not asserted here: the intermediate values.</b> {@code TransitionEngine} advances on
     * {@code System.nanoTime()} and ignores the delta it is handed, so a test loop cannot step the clock —
     * a ramp assertion would have to sleep, and a timing assertion that sleeps is one that eventually flakes
     * and then gets deleted. What is checked instead is every input the ramp depends on: the from-value, the
     * state class the sheet keys off, and that a transition genuinely started.</p>
     */
    @Test
    public void aClosedPopoverIsTransparentSoOpeningHasSomethingToFadeFrom() {
        window.getStyleEngine().addStylesheet(com.crystalgui.style.sheet.StyleSheet.DEFAULT);
        settle();

        assertEquals("a closed popover must be transparent, or there is no `from` value to fade from",
                0f, popover.getStyle().getGeneralGroup().opacity(), 0.001f);
        assertFalse(popover.hasClass(Popover.OPEN_CLASS));
    }

    @Test
    public void openingAddsTheStateClassAndStartsATransition() {
        window.getStyleEngine().addStylesheet(com.crystalgui.style.sheet.StyleSheet.DEFAULT);
        settle();

        popover.showFor(invoker, invoker);
        assertTrue("the sheet transitions on this class, so it must land with the open state",
                popover.hasClass(Popover.OPEN_CLASS));

        window.getStyleEngine().calculateStyle(0.016f);

        // An ANIMATION-origin candidate is a running transition, and it is observable without a clock.
        assertTrue("opening must start a transition rather than snapping to opaque",
                popover.getStyle().containsCandidate(
                        com.crystalgui.style.property.StylePropertyRegistry.OPACITY,
                        slot -> slot.origin() == com.crystalgui.style.StyleOrigin.ANIMATION));
    }

    /** Closing removes the class, so the popover returns to transparent and the next open can fade again
     * rather than starting already-visible. */
    @Test
    public void closingRemovesTheStateClassAndReturnsToTransparent() {
        window.getStyleEngine().addStylesheet(com.crystalgui.style.sheet.StyleSheet.DEFAULT);
        settle();
        popover.showFor(invoker, invoker);
        settle();
        assertTrue(popover.hasClass(Popover.OPEN_CLASS));

        popover.hide();
        settle();

        // The class, not the value: dropping it starts a fade-OUT, so the computed opacity is mid-transition
        // here and settles only once real time passes. Harmless — `display: none` hides the popover the same
        // frame, so nothing is on screen to see it — but it makes the value untestable without a clock. The
        // cold-start test above pins the resting value, where no transition is in flight.
        assertFalse(popover.hasClass(Popover.OPEN_CLASS));
    }

    // ── Light dismiss ───────────────────────────────────────────────────────

    @Test
    public void aPressOutsideDismissesIt() {
        popover.showFor(invoker, invoker);
        settle();

        pressAt(10f, 10f); // `outside` sits at the top-left

        assertFalse("a press anywhere unrelated closes it", popover.isOpen());
    }

    /** The press must still do its job. Browsers both dismiss the popover and activate what was clicked;
     * dismissing first would tear the tree down under an undelivered event. */
    @Test
    public void thePressThatDismissesStillReachesItsTarget() {
        popover.showFor(invoker, invoker);
        settle();

        pressAt(10f, 10f);

        assertFalse(popover.isOpen());
        assertSame("`outside` is FocusPolicy.CLICK, so the press must still have focused it",
                outside, input.getFocusedElement());
    }

    @Test
    public void aPressInsideDoesNotDismissIt() {
        popover.showFor(invoker, invoker);
        settle();
        // The popover is placed under the invoker, which is the second 20px-tall row.
        float insideY = popover.getRuntimeCache().getY() + 5f;
        float insideX = popover.getRuntimeCache().getX() + 5f;

        pressAt(insideX, insideY);

        assertTrue("clicking your own menu must not close it", popover.isOpen());
    }

    /**
     * The invoker carve-out. Without it a dropdown button dies on its own press: light dismiss would close
     * the menu on mouse-down and the button's click would immediately reopen it, so it could never be shut
     * by clicking the button again — and would visibly flicker while trying.
     */
    @Test
    public void aPressOnTheInvokerDoesNotDismissIt() {
        popover.showFor(invoker, invoker);
        settle();

        pressAt(10f, 30f); // the invoker row

        assertTrue("the thing that opened it counts as inside it", popover.isOpen());
    }

    @Test
    public void aManualPopoverIgnoresLightDismissAndEscape() {
        popover.setMode(Popover.Mode.MANUAL);
        popover.showFor(invoker, invoker);
        settle();

        pressAt(10f, 10f);
        assertTrue("MANUAL means only code closes it", popover.isOpen());

        escape();
        assertTrue(popover.isOpen());
        assertTrue("...and it never joined the stack in the first place",
                window.getAutoPopovers().isEmpty());
    }

    // ── Escape ──────────────────────────────────────────────────────────────

    @Test
    public void escapeClosesIt() {
        popover.showFor(invoker, invoker);
        settle();

        escape();

        assertFalse(popover.isOpen());
    }

    /**
     * Escape asks the <b>topmost</b> close watcher. A dropdown opened from inside a modal must close first,
     * and only a second Escape may reach the modal — which is why 5.4's modal-only Escape became a general
     * close-watcher stack when popovers arrived as the second consumer.
     */
    @Test
    public void escapeUnwindsInnermostFirst() {
        Dialog modal = new Dialog("modal");
        modal.layout(l -> l.width(140).height(90));
        root.addChild(modal);
        Popover inner = new Popover();
        inner.layout(l -> l.width(60).height(30));
        modal.getContent().addChild(inner);
        settle();

        modal.showModal();
        settle();
        inner.showFor(modal, modal);
        settle();

        escape();
        assertFalse("the popover goes first", inner.isOpen());
        assertTrue("the modal survives the first Escape", modal.isOpen());

        escape();
        assertFalse("and closes on the second", modal.isOpen());
    }

    /**
     * A popover opened <b>from a mouse-down handler</b> must survive that press — the context-menu case.
     *
     * <p>The bug this pins was invisible to every other test here, because they all open popovers by calling
     * {@code showAt}/{@code showFor} directly rather than from inside an event dispatch. Light dismiss runs
     * after the down event is delivered, so a handler that opens a menu on press had already put it in the
     * stack by then — and the pressed element is not inside it, so dismissal closed it immediately. It
     * opened and vanished in the same frame, which from the outside is identical to never opening. Reported
     * from the harness as "I right-clicked but nothing happened".</p>
     *
     * <p>Fixed by dismissing only what was open <em>before</em> the dispatch, so this holds with
     * <b>no invoker</b> — the case that would otherwise still self-destruct silently.</p>
     */
    @Test
    public void aPopoverOpenedDuringAPressIsNotDismissedByThatPress() {
        Menu context = new Menu();
        context.addItem("Add node");
        root.addChild(context);
        settle();

        outside.onMouseDown.attachListener((el, event) -> context.showAt(100f, 80f, null), false, false);

        pressAt(10f, 10f); // lands on `outside`

        assertTrue("the press that opened it must not also close it", context.isOpen());
    }

    /** ...and the press after that still dismisses it normally, so the guard is scoped to one press rather
     * than making the popover undismissable. */
    @Test
    public void theNextPressStillDismissesIt() {
        Menu context = new Menu();
        context.addItem("Add node");
        root.addChild(context);
        settle();

        outside.onMouseDown.attachListener((el, event) -> {
            if (!context.isOpen()) context.showAt(100f, 80f, null);
        }, false, false);

        pressAt(10f, 10f);
        assertTrue(context.isOpen());

        pressAt(10f, 35f); // the invoker row, unrelated to the menu
        assertFalse("a later press dismisses as usual", context.isOpen());
    }

    // ── Nesting ─────────────────────────────────────────────────────────────

    /** Opening an unrelated popover closes the first — the spec's "close all auto popovers except the
     * ancestors of the one being shown". */
    @Test
    public void openingAnUnrelatedPopoverClosesTheFirst() {
        Popover other = new Popover();
        other.layout(l -> l.width(50).height(30));
        root.addChild(other);
        settle();

        popover.showFor(invoker, invoker);
        settle();
        other.showFor(outside, outside);
        settle();

        assertFalse(popover.isOpen());
        assertTrue(other.isOpen());
        assertEquals(1, window.getAutoPopovers().size());
    }

    /** ...but a nested one does not, or no submenu could ever be opened. */
    @Test
    public void openingANestedPopoverKeepsItsParentOpen() {
        Popover submenu = new Popover();
        submenu.layout(l -> l.width(50).height(30));
        popover.addChild(submenu);
        settle();

        popover.showFor(invoker, invoker);
        settle();
        submenu.showFor(popover, popover); // invoker is inside the parent popover
        settle();

        assertTrue("the parent must survive its own child opening", popover.isOpen());
        assertTrue(submenu.isOpen());
        assertEquals(2, window.getAutoPopovers().size());
    }

    /** And a press back in the parent closes only the child — the middle case, and the one that decides
     * whether nested menus are usable at all. */
    @Test
    public void aPressInTheParentClosesOnlyTheChild() {
        // The submenu's invoker is a specific ROW inside the parent, which is what it is in a real menu.
        // Passing the whole parent popover as the invoker makes every press in the parent count as a press
        // on the child's invoker, and light dismiss then correctly spares the child — the first version of
        // this test did that and was measuring its own bad model rather than the behaviour.
        UIElement row = new UIElement().layout(l -> l.width(40).height(10));
        popover.addChild(row);
        Popover submenu = new Popover();
        submenu.layout(l -> l.width(50).height(30));
        popover.addChild(submenu);
        settle();

        popover.showFor(invoker, invoker);
        settle();
        submenu.showFor(row, row);
        settle();

        // A point inside the parent, clear of BOTH the row that opened the submenu and the submenu's own
        // box — the submenu is placed below its row here, so it overlaps the parent's lower left. (A real
        // submenu prefers Side.RIGHT and sits beside its parent; the dismissal semantics are the same.)
        float insideX = popover.getRuntimeCache().getX() + popover.getRuntimeCache().getWidth() - 2f;
        float insideY = popover.getRuntimeCache().getY() + 2f;
        assertNotSame("precondition: the press must land on the parent, not the child",
                submenu, window.getHoveredElement(insideX * 2f, insideY * 2f));
        pressAt(insideX, insideY);

        assertTrue("reaching for the parent must not destroy it", popover.isOpen());
        assertFalse("but the child it spawned goes away", submenu.isOpen());
    }

    @Test
    public void detachingAnOpenPopoverClearsItsRegistrations() {
        popover.showFor(invoker, invoker);
        settle();

        popover.removeSelf();
        settle();

        assertTrue("a popover that left the tree must not linger in the stack",
                window.getAutoPopovers().isEmpty());
        assertNull(window.getTopCloseWatcher());
    }

    // ── Menu ────────────────────────────────────────────────────────────────

    @Test
    public void menuItemsActivateAndCloseTheMenu() {
        Menu menu = new Menu();
        root.addChild(menu);
        MenuItem cut = menu.addItem("Cut");
        menu.addItem("Copy");
        settle();

        MenuItem[] activated = { null };
        menu.onItemActivated.connect(item -> activated[0] = item);

        menu.showFor(invoker, invoker);
        settle();
        cut.onPressed.emit();

        assertSame(cut, activated[0]);
        assertFalse("activating an item is the point of a menu, so it closes", menu.isOpen());
    }

    @Test
    public void aMenuRefusesPublicChildren() {
        Menu menu = new Menu();
        root.addChild(menu);
        try {
            menu.addChild(new UIElement());
            fail("expected the composite-children guard to fire");
        } catch (RuntimeException expected) {
            // Same rule as every other composite here.
        }
    }

    @Test
    public void openingAMenuPreSelectsNothing() {
        Menu menu = new Menu();
        root.addChild(menu);
        MenuItem first = menu.addItem("first");
        MenuItem second = menu.addItem("second");
        settle();

        menu.showFor(invoker, invoker);
        settle();

        // Deliberately NOT the ARIA-suggested "first choice ready" — see Menu.onOpened's doc. Opening a
        // menu must not look like a choice was already made; focus lands on the menu itself so keyboard
        // events still reach it, but no row lights up until the user actually moves.
        assertNotSame(first, input.getFocusedElement());
        assertNotSame(second, input.getFocusedElement());
        assertSame(menu, input.getFocusedElement());
    }

    @Test
    public void arrowKeysMoveThroughTheItemsAndWrap() {
        Menu menu = new Menu();
        root.addChild(menu);
        MenuItem a = menu.addItem("a");
        MenuItem b = menu.addItem("b");
        MenuItem c = menu.addItem("c");
        settle();

        menu.showFor(invoker, invoker);
        settle();
        assertSame("nothing pre-selected on open", menu, input.getFocusedElement());

        key(CgKeyCodes.KEY_DOWN);
        assertSame("the first Down is what actually chooses the first row", a, input.getFocusedElement());
        key(CgKeyCodes.KEY_DOWN);
        assertSame(b, input.getFocusedElement());
        key(CgKeyCodes.KEY_END);
        assertSame(c, input.getFocusedElement());
        key(CgKeyCodes.KEY_DOWN);
        assertSame("a menu is a ring, per the ARIA pattern", a, input.getFocusedElement());
        key(CgKeyCodes.KEY_UP);
        assertSame(c, input.getFocusedElement());
        key(CgKeyCodes.KEY_HOME);
        assertSame(a, input.getFocusedElement());
    }

    /** A whole menu is one tab stop, which 5.3 delivered — so its items must be non-tabbable. */
    @Test
    public void aMenuIsOneTabStop() {
        Menu menu = new Menu();
        root.addChild(menu);
        MenuItem item = menu.addItem("only");
        settle();

        assertTrue("still clickable and still arrow-reachable", item.focusable());
        assertFalse("but Tab does not walk a menu item by item", item.tabbable());
    }

    @Test
    public void closingAMenuHandsFocusBack() {
        Menu menu = new Menu();
        root.addChild(menu);
        menu.addItem("only");
        settle();

        input.requestFocus(outside);
        menu.showFor(invoker, invoker);
        settle();
        assertNotSame(outside, input.getFocusedElement());

        menu.hide();
        settle();

        assertSame("a menu that swallows your place in the page is worse than one that never took focus",
                outside, input.getFocusedElement());
    }

    /**
     * Hovering a row <b>moves focus to it</b>, so exactly one row is ever highlighted.
     *
     * <p>Without this the keyboard's focused row and the mouse's hovered row light up simultaneously —
     * reported from the harness as "I'm hovering over Paste but Add node is still focus ringed". Native menus
     * and the ARIA pattern both move the active item with the pointer; it also keeps the two input modes in
     * step, so Down after hovering continues from the pointer rather than from wherever focus was stranded.</p>
     */
    @Test
    public void hoveringAMenuItemMovesFocusToIt() {
        Menu menu = new Menu();
        root.addChild(menu);
        MenuItem first = menu.addItem("Add node");
        MenuItem second = menu.addItem("Paste");
        // Explicit sizes: this class installs no stylesheet, so `menuitem { min-height }` never applies and
        // the rows would be 0x0 — unhoverable, and the probe would fall through to the root.
        first.layout(l -> l.width(80).height(14));
        second.layout(l -> l.width(80).height(14));
        settle();

        menu.showFor(invoker, invoker);
        settle();
        settle();
        assertFalse("nothing pre-selected on open", first.isFocused());

        // Hover the second row through the real input path.
        float x = second.getRuntimeCache().getX() + 2f;
        float y = second.getRuntimeCache().getY() + 2f;
        input.consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(x * 2f), Math.round(y * 2f), 0, 0, -1, false, 0f, -1L));
        input.beginFrame();
        input.endFrame();

        assertSame("focus must follow the pointer", second, input.getFocusedElement());
        assertFalse("...so the row the keyboard left behind is no longer focused", first.isFocused());
    }

    /**
     * Pointer-driven focus must not draw a ring — a ring trailing the mouse through a menu is exactly the
     * noise {@code :focus-visible} exists to avoid. Same carve-out the click path already makes, which is why
     * {@code requestPointerFocus} exists alongside {@code requestFocus} rather than replacing it.
     */
    @Test
    public void pointerDrivenFocusDoesNotRingButProgrammaticFocusDoes() {
        input.requestFocus(outside);
        assertTrue("requestFocus is PROGRAMMATIC, which rings", outside.isFocusVisible());

        input.requestPointerFocus(invoker);

        assertSame(invoker, input.getFocusedElement());
        assertTrue(invoker.isFocused());
        assertFalse("no ring for a pointer-driven focus", invoker.isFocusVisible());
    }

    /**
     * A press on the element a context menu was opened <em>from</em> dismisses it, when no invoker was named.
     *
     * <p>The complement of {@link #aPressOnTheInvokerDoesNotDismissIt}, and the pair is the whole point: an
     * invoker is spared because a toggle button's own press must not close the menu it just opened, but a
     * context menu is not a toggle. Naming its trigger surface as the invoker makes that entire surface unable
     * to dismiss the menu — left-clicking the area you had just right-clicked does nothing, which is how this
     * was found.</p>
     */
    @Test
    public void aContextMenuWithNoInvokerIsDismissedByPressingItsTriggerArea() {
        Menu context = new Menu();
        context.addItem("Add node");
        root.addChild(context);
        settle();

        // Opened from `outside`, deliberately WITHOUT naming it as the invoker.
        context.showAt(200f, 200f, null);
        settle();
        assertTrue(context.isOpen());

        pressAt(10f, 10f); // back on `outside`, the surface it came from

        assertFalse("a context menu is not a toggle; its trigger area must still dismiss it",
                context.isOpen());
    }

    /**
     * Re-showing an <b>already open</b> context menu from a press moves it, rather than the press closing it.
     *
     * <p>The case a before-snapshot of the popover stack could not express, and why the exemption is a show
     * <em>counter</em> instead: the menu is in any snapshot taken before the press, so a membership test
     * dismissed it — right-clicking elsewhere closed the menu instead of moving it. Asking "was this shown
     * during the press" covers the first-open case and this one with one rule.</p>
     */
    @Test
    public void reShowingAnOpenContextMenuDuringAPressMovesItRatherThanClosingIt() {
        Menu context = new Menu();
        context.addItem("Add node");
        root.addChild(context);
        settle();

        float[] nextPoint = { 200f, 200f };
        outside.onMouseDown.attachListener(
                (el, event) -> context.showAt(nextPoint[0], nextPoint[1], null), false, false);

        pressAt(10f, 10f);
        settle();
        settle();
        assertTrue(context.isOpen());
        assertEquals(200f, context.getRuntimeCache().getX(), 1f);

        // Press again, asking for a different position — the menu must move, not vanish.
        nextPoint[0] = 120f;
        nextPoint[1] = 60f;
        pressAt(10f, 10f);
        settle();
        settle();

        assertTrue("a re-show during the press must survive it", context.isOpen());
        assertEquals("...and must have moved to the new point", 120f,
                context.getRuntimeCache().getX(), 1f);
        assertEquals(60f, context.getRuntimeCache().getY(), 1f);
    }

    /**
     * The active row is highlighted by {@code :focus} alone — the user-agent sheet must define no
     * {@code menuitem:hover} background.
     *
     * <p>Asserted on the sheet because the defect is not expressible in engine state: CSS {@code :hover}
     * correctly stays on whatever the mouse is over, so a hover rule plus arrow-key focus lit up <b>two</b>
     * rows at once. Since {@code Menu} does focus-follows-hover the hovered row is already the focused row,
     * making a hover rule pure redundancy that can only disagree with the truth. This pins the decision so
     * re-adding the rule fails here rather than on someone's screen.</p>
     */
    @Test
    public void theUserAgentSheetHighlightsMenuRowsByFocusOnly() {
        var hoverRules = com.crystalgui.style.sheet.StyleSheet.DEFAULT.getRules().stream()
                .filter(rule -> rule.selector().compounds().stream().anyMatch(compound ->
                        compound.parts().stream().anyMatch(part ->
                                "menuitem".equals(part.identity()))))
                .filter(rule -> rule.selector().compounds().stream().anyMatch(compound ->
                        compound.parts().stream().anyMatch(part ->
                                "hover".equals(part.identity()))))
                .toList();

        assertTrue("menuitem must not carry a :hover rule — focus is the single source of the active row, was "
                        + hoverRules, hoverRules.isEmpty());
    }

    // ── Submenus ────────────────────────────────────────────────────────────

    /**
     * Activating a submenu item opens the child and <b>leaves the parent open</b>.
     *
     * <p>The bug this pins shipped visible: {@code Menu} closed on <em>every</em> item activation, which is
     * right for a leaf and wrong for a row that opens a submenu — so pressing "More..." opened the child and
     * shut the menu it belonged to in the same breath. The submenu appeared out of nowhere with no parent
     * behind it, which looks far more like a placement bug than what it was.</p>
     */
    @Test
    public void activatingASubmenuItemOpensItAndKeepsTheParentOpen() {
        Menu parent = new Menu();
        Menu child = new Menu();
        root.addChild(parent);
        root.addChild(child);
        parent.addItem("Low");
        child.addItem("Ultra+");
        MenuItem moreItem = parent.addSubmenu("More...", child);
        settle();

        parent.showFor(invoker, invoker);
        settle();
        moreItem.onPressed.emit();
        settle();

        assertTrue("the child must open", child.isOpen());
        assertTrue("...and the parent must survive it", parent.isOpen());
        assertEquals(2, window.getAutoPopovers().size());
    }

    /** addSubmenu prefers the RIGHT side, so a submenu sits beside its parent rather than on top of it. */
    @Test
    public void aSubmenuIsPlacedBesideItsParent() {
        Menu parent = new Menu();
        Menu child = new Menu();
        root.addChild(parent);
        root.addChild(child);
        MenuItem moreItem = parent.addSubmenu("More...", child);
        child.addItem("Ultra+");
        settle();

        assertEquals(com.crystalgui.ui.AnchoredPlacement.Side.RIGHT, child.getPreferredSide());

        parent.showFor(invoker, invoker);
        settle();
        moreItem.onPressed.emit();
        settle();
        settle();

        assertTrue("the child must start at or past the item's right edge, was "
                        + child.getRuntimeCache().getX() + " vs item right "
                        + (moreItem.getRuntimeCache().getX() + moreItem.getRuntimeCache().getWidth()),
                child.getRuntimeCache().getX()
                        >= moreItem.getRuntimeCache().getX() + moreItem.getRuntimeCache().getWidth() - 0.5f);
    }

    /**
     * Choosing a leaf <b>inside a submenu</b> closes the whole chain, parent included.
     *
     * <p>The ARIA pattern says activating a menuitem closes "the menu", and every native menu collapses the
     * full chain when you pick a leaf. Closing only the submenu left you staring at the menu you had just
     * chosen from — reported from the harness as "it chose Ultra, closed the submenu and left the parent
     * menu open". Escape is the operation that peels one level; choosing is not.</p>
     */
    @Test
    public void choosingALeafInASubmenuClosesTheWholeChain() {
        Menu parent = new Menu();
        Menu child = new Menu();
        root.addChild(parent);
        root.addChild(child);
        parent.addItem("Low");
        MenuItem leaf = child.addItem("Ultra+");
        MenuItem moreItem = parent.addSubmenu("More...", child);
        settle();

        parent.showFor(invoker, invoker);
        settle();
        moreItem.onPressed.emit();
        settle();
        assertTrue(parent.isOpen());
        assertTrue(child.isOpen());

        leaf.onPressed.emit();
        settle();

        assertFalse("the submenu closes", child.isOpen());
        assertFalse("...and so does the menu it was opened from", parent.isOpen());
        assertTrue(window.getAutoPopovers().isEmpty());
    }

    /** Escape, by contrast, peels exactly one level — the two operations must not converge. */
    @Test
    public void escapeClosesOnlyTheSubmenu() {
        Menu parent = new Menu();
        Menu child = new Menu();
        root.addChild(parent);
        root.addChild(child);
        parent.addItem("Low");
        child.addItem("Ultra+");
        MenuItem moreItem = parent.addSubmenu("More...", child);
        settle();

        parent.showFor(invoker, invoker);
        settle();
        moreItem.onPressed.emit();
        settle();

        escape();

        assertFalse("the innermost goes", child.isOpen());
        assertTrue("but the parent stays — this is what makes Escape different from choosing",
                parent.isOpen());
    }

    /** The chain link is derived from the invoker, so a root popover has no parent and a submenu does. */
    @Test
    public void theChainIsDerivedFromTheInvoker() {
        Menu parent = new Menu();
        Menu child = new Menu();
        root.addChild(parent);
        root.addChild(child);
        child.addItem("Ultra+");
        MenuItem moreItem = parent.addSubmenu("More...", child);
        settle();

        parent.showFor(invoker, invoker);
        settle();
        assertNull("a menu opened from an ordinary element is the root of its chain",
                parent.parentPopover());

        moreItem.onPressed.emit();
        settle();
        assertSame("a submenu resolves to the menu its invoking row belongs to",
                parent, child.parentPopover());
    }

    /** An ordinary item still closes the menu — the submenu carve-out must not become the general rule. */
    @Test
    public void anOrdinaryItemInAMenuWithSubmenusStillCloses() {
        Menu parent = new Menu();
        Menu child = new Menu();
        root.addChild(parent);
        root.addChild(child);
        MenuItem leaf = parent.addItem("Low");
        parent.addSubmenu("More...", child);
        settle();

        parent.showFor(invoker, invoker);
        settle();
        leaf.onPressed.emit();

        assertFalse(parent.isOpen());
        assertFalse(child.isOpen());
    }

    /**
     * Closing a parent takes its submenu with it — the spec's "hide all popovers until".
     *
     * <p>Without this the child is orphaned in the top layer: still painting, still taking Escape, with
     * nothing left on screen to explain where it came from.</p>
     */
    @Test
    public void closingAParentClosesItsSubmenu() {
        Menu parent = new Menu();
        Menu child = new Menu();
        root.addChild(parent);
        root.addChild(child);
        child.addItem("Ultra+");
        MenuItem moreItem = parent.addSubmenu("More...", child);
        settle();

        parent.showFor(invoker, invoker);
        settle();
        moreItem.onPressed.emit();
        settle();
        assertTrue(child.isOpen());

        parent.hide();
        settle();

        assertFalse("the child must not be left orphaned in the top layer", child.isOpen());
        assertTrue(window.getAutoPopovers().isEmpty());
        assertTrue(window.getTopLayer().isEmpty());
    }

    // ── Context menu ────────────────────────────────────────────────────────

    /** A context menu is the same widget anchored to a point, not a second class. */
    @Test
    public void aMenuCanBeAnchoredToABarePoint() {
        Menu menu = new Menu();
        root.addChild(menu);
        menu.addItem("Add node");
        settle();

        menu.showAt(120f, 60f, null);
        settle();
        settle();

        assertTrue(menu.isOpen());
        assertEquals("placed at the point it was given", 120f, menu.getRuntimeCache().getX(), 1f);
        assertEquals(60f, menu.getRuntimeCache().getY(), 1f);
    }

    // ── Dropdown ────────────────────────────────────────────────────────────

    @Test
    public void aDropdownTracksItsSelectionAndLabel() {
        Dropdown dropdown = new Dropdown("pick one");
        root.addChild(dropdown);
        dropdown.addOptions("Low", "Medium", "High");
        settle();

        assertEquals("pick one", dropdown.getText());
        assertEquals(-1, dropdown.getSelectedIndex());

        int[] emitted = { -99 };
        dropdown.attachSelectionListener(index -> emitted[0] = index);
        dropdown.select(2);

        assertEquals(2, dropdown.getSelectedIndex());
        assertEquals("High", dropdown.getSelectedOption());
        assertEquals("the label follows the selection", "High", dropdown.getText());
        assertEquals(2, emitted[0]);
    }

    @Test
    public void reSelectingTheSameOptionEmitsNothing() {
        Dropdown dropdown = new Dropdown("pick");
        root.addChild(dropdown);
        dropdown.addOptions("a", "b");
        dropdown.select(1);
        settle();

        int[] emissions = { 0 };
        dropdown.attachSelectionListener(index -> emissions[0]++);
        dropdown.select(1);
        dropdown.select(99);
        dropdown.select(-3);

        assertEquals("unchanged and out-of-range selections are both no-ops", 0, emissions[0]);
    }

    @Test
    public void pressingADropdownTogglesItsMenu() {
        Dropdown dropdown = new Dropdown("pick");
        root.addChild(dropdown);
        dropdown.addOptions("a", "b");
        settle();

        dropdown.onPressed.emit();
        settle();
        assertTrue(dropdown.getMenu().isOpen());

        dropdown.onPressed.emit();
        settle();
        assertFalse("a second press must shut it, not reopen it", dropdown.getMenu().isOpen());
    }

    @Test
    public void choosingADropdownOptionSelectsItAndCloses() {
        Dropdown dropdown = new Dropdown("pick");
        root.addChild(dropdown);
        dropdown.addOptions("Low", "High");
        settle();

        dropdown.onPressed.emit();
        settle();
        dropdown.getMenu().getItems().get(1).onPressed.emit();

        assertEquals(1, dropdown.getSelectedIndex());
        assertEquals("High", dropdown.getText());
        assertFalse(dropdown.getMenu().isOpen());
    }

    /** The state that travels is the selection, not the label — the label is derived from it, so restoring
     * the text would put the right words on a control that still believes nothing is selected. */
    @Test
    public void dropdownStateRoundTripsTheIndexNotTheText() {
        Dropdown source = new Dropdown("pick");
        root.addChild(source);
        source.addOptions("a", "b", "c");
        source.select(2);

        Dropdown target = new Dropdown("pick");
        root.addChild(target);
        target.addOptions("a", "b", "c");
        settle();

        var state = new com.crystalgui.serialization.StateMap<>(com.crystalgui.serialization.PlainOps.INSTANCE);
        source.writeStateTo(state);
        target.readStateFrom(state);

        assertEquals(2, target.getSelectedIndex());
        assertEquals("c", target.getText());
    }
}
