package com.crystalgui.workbench.chrome.menu;

import com.crystalgui.core.command.MenuContributor;
import com.crystalgui.core.undo.Edit;
import com.crystalgui.ui.service.Focus;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.layout.Tab;
import com.crystalgui.widget.overlay.ContextMenu;
import com.crystalgui.widget.overlay.Popover;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuEntry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.widget.overlay.Menu;
import com.crystalgui.widget.overlay.MenuItem;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.workbench.chrome.menu.MenuBarView;
import com.crystalgui.widget.overlay.MenuBuilder;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.core.data.ReadOnlyVec2f;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.UINode;

/**
 * {@link MenuBarView} and {@link MenuBuilder}.
 *
 * <h3>What these assert, and what they deliberately do not</h3>
 *
 * <p>Not pixels, and not that a popover appears where it should — {@code PopoverTest} owns placement.
 * These pin the two things that make the bar a <em>contribution surface</em> rather than a widget with a
 * list in it: that a command reaches a menu <b>without the bar knowing it exists</b>, and that the six
 * row-rendering rules {@code MenuBuilder} inherited from {@code ContextMenu} still hold now that two
 * callers depend on them.</p>
 *
 * <p>And the affordance: that pressing a title actually opens it, and that hovering a second title while
 * one is open switches. Every UI bug in the previous four stacks was an affordance failure where the
 * mechanism was right and the way in was not, so the route is what is tested — never
 * {@code bar.open(id)} alone, which is the back door.</p>
 */
public class MenuBarViewTest extends UiDocumentTestBase {

    private static int counter;

    private UINode root;
    private CommandRegistry registry;
    private MenuBarView bar;
    private MenuId fileMenu;
    private MenuId editMenu;
    private final List<String> ran = new ArrayList<>();

    @Before
    public void setUp() {
        ran.clear();
        int id = counter++;
        // Fresh ids per test: MenuId interns, and a submenu declaration is permanent -- see
        // MenuSectionsTest for the full reason.
        fileMenu = MenuId.of("bar/file/" + id);
        editMenu = MenuId.of("bar/edit/" + id);

        registry = new CommandRegistry();
        registry.register(Command.of("f.new", "New").menu(fileMenu, "1_new", 10)
                .run(c -> ran.add("f.new")));
        registry.register(Command.of("f.save", "Save").menu(fileMenu, "2_save", 10)
                .run(c -> ran.add("f.save")));
        registry.register(Command.of("e.undo", "Undo").menu(editMenu, "1_undo", 10)
                .run(c -> ran.add("e.undo")));

        root = new UINode().layout(l -> l.width(600).height(400));
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);

        bar = new MenuBarView(registry);
        bar.addMenu(fileMenu, "&File").addMenu(editMenu, "&Edit");
        root.append(bar);
        frame();
    }

    @After
    public void tearDown() {
        // The bar contributes MainMenuCommands into whatever registry it is handed, and the global one is
        // shared -- a leaked Help entry would show up in an unrelated test's palette count.
        CommandRegistry.global().resetForTesting();
    }

    /**
     * The bar's own titles.
     *
     * <p>Filtered by class rather than indexed, because the burger is an internal child too and sits
     * first — indexing straight into the children silently pressed the burger and every affordance test
     * failed at once.</p>
     */
    private UINode titleAt(int index) {
        List<UINode> found = new ArrayList<>();
        for (UINode child : bar.children()) {
            if (child.hasClass(MenuBarView.TITLE_CLASS)) found.add(child);
        }
        return found.get(index);
    }

    /** Through the real three-phase dispatch, not by calling the listener — the route is the point. */
    private void press(UINode element) {
        document.input().send(element,
                new MouseEvent.Down(element, ORIGIN, 0, 1));
    }

    private void hover(UINode element) {
        document.input().send(element, new MouseEvent.Enter(element, ORIGIN));
    }

    private static final ReadOnlyVec2f ORIGIN = new ReadOnlyVec2f(new org.joml.Vector2f());

    // ── The thesis ──────────────────────────────────────────────────────────────────────────────

    /**
     * The test the plan set for "seamless rather than parallel".
     *
     * <p>A command registered after the bar was built, by something the bar has never heard of, appears in
     * its menu. If this fails the bar has a list in it somewhere.</p>
     */
    @Test
    public void aCommandRegisteredLaterAppearsWithoutTheBarKnowing() {
        registry.register(Command.of("f.late", "Close").menu(fileMenu, "2_save", 20)
                .run(c -> ran.add("f.late")));

        Menu menu = MenuBuilder.build(fileMenu, registry, root);
        assertEquals(List.of("New", "Save", "Close"), labelsOf(menu));
    }

    @Test
    public void sectionsAreSeparatedAndTheSeparatorIsNeverDeclared() {
        Menu menu = MenuBuilder.build(fileMenu, registry, root);
        assertEquals("one rule between 1_new and 2_save", 1, separatorsIn(menu));
    }

    @Test
    public void thereIsNoLeadingOrTrailingSeparator() {
        // One section only: nothing to separate, and a builder that emitted eagerly would leave a rule
        // above the first row.
        registry.unregister("f.save");
        Menu menu = MenuBuilder.build(fileMenu, registry, root);
        assertEquals(0, separatorsIn(menu));
    }

    @Test
    public void aDisabledCommandIsDimmedRatherThanMissing() {
        registry.register(Command.of("f.close", "Close").menu(fileMenu, "2_save", 20)
                .enabledWhen(c -> false).run(c -> ran.add("f.close")));

        Menu menu = MenuBuilder.build(fileMenu, registry, root);
        MenuItem close = itemNamed(menu, "Close");
        assertNotNull("a menu whose rows vanish is a menu whose rows are never in the same place", close);
        assertFalse(close.isEnabled());
    }

    @Test
    public void aToggleRendersAsACheckedRow() {
        boolean[] on = {true};
        registry.register(Command.of("f.wrap", "Wrap").menu(fileMenu, "3_view", 10)
                .toggledWhen(c -> on[0]).run(c -> ran.add("f.wrap")));

        MenuItem wrap = itemNamed(MenuBuilder.build(fileMenu, registry, root), "Wrap");
        assertTrue("a toggle reserves its mark column", wrap.hasClass(MenuItem.CHECKABLE_CLASS));
        assertTrue(wrap.isSelected());

        on[0] = false;
        assertFalse("read when the menu is built, not when the command was registered",
                itemNamed(MenuBuilder.build(fileMenu, registry, root), "Wrap").isSelected());
    }

    @Test
    public void anEmptySubmenuIsDroppedButADisabledOneIsNot() {
        MenuId empty = MenuId.of("bar/empty/" + counter++);
        MenuId disabled = MenuId.of("bar/disabled/" + counter++);
        fileMenu.submenu(empty, "Empty", "9_sub", 10);
        fileMenu.submenu(disabled, "Disabled", "9_sub", 20);
        registry.register(Command.of("f.nope", "Nope").menu(disabled, "g", 10)
                .enabledWhen(c -> false).run(c -> { }));

        List<String> labels = labelsOf(MenuBuilder.build(fileMenu, registry, root));
        assertFalse("an empty submenu is a registration that never happened", labels.contains("Empty"));
        assertTrue("a disabled one is an answer", labels.contains("Disabled"));
    }

    /** A {@code MenuContributor} row is registered nowhere, so it can only run through the held command. */
    @Test
    public void aComputedRowRunsEvenThoughItIsRegisteredNowhere() {
        registry.contributeMenu(fileMenu, (id, context) -> List.of(MenuEntry.Item.of(
                Command.of("f.recent.0", "project.json").run(c -> ran.add("recent")),
                "8_recent", 10)));

        MenuItem row = itemNamed(MenuBuilder.build(fileMenu, registry, root), "project.json");
        assertNotNull(row);
        row.onPressed.emit();
        assertEquals(List.of("recent"), ran);
    }

    // ── The affordance ──────────────────────────────────────────────────────────────────────────

    @Test
    public void pressingATitleOpensItsMenu() {
        assertNull(bar.openMenu());
        press(titleAt(0));
        assertEquals("a title that does not respond to a press is a bar with no way in",
                fileMenu, bar.openMenu());
    }

    @Test
    public void pressingTheOpenTitleAgainClosesIt() {
        press(titleAt(0));
        press(titleAt(0));
        assertNull(bar.openMenu());
    }

    @Test
    public void hoveringAnotherTitleSwitchesWhileOneIsOpen() {
        press(titleAt(0));
        hover(titleAt(1));
        assertEquals(editMenu, bar.openMenu());
    }

    @Test
    public void hoveringDoesNothingWhenNoMenuIsOpen() {
        hover(titleAt(1));
        assertNull("hover opens nothing on its own, which is what every native bar does",
                bar.openMenu());
    }

    @Test
    public void theOpenTitleCarriesItsClassAndGivesItUp() {
        press(titleAt(0));
        assertTrue(titleAt(0).hasClass(MenuBarView.OPEN_CLASS));
        press(titleAt(0));
        assertFalse(titleAt(0).hasClass(MenuBarView.OPEN_CLASS));
    }

    @Test
    public void closingDetachesEveryMenuInTheChain() {
        press(titleAt(0));
        int attached = countMenus(document);
        assertTrue("the menu must be in the tree, or Popover refuses to show it", attached > 0);
        bar.close();
        assertEquals("left in place they accumulate one set per press",
                0, countMenus(document));
    }

    /** Detaching the bar with a menu down would leave the chain parented into a tree nobody paints. */
    @Test
    public void removingTheBarClosesWhateverWasOpen() {
        press(titleAt(0));
        bar.removeSelf();
        assertNull(bar.openMenu());
    }

    // ── The focus owner ─────────────────────────────────────────────────────────────────────────

    /**
     * The bug the first draft shipped, and the reason this test drives the RAW mouse sink.
     *
     * <p>{@code emitMouseDown} calls {@code emitAndLoseFocus} <b>before</b> it dispatches, and a title is
     * {@code FocusPolicy.NONE}, so the press that opens the menu destroys the focus the menu needs. Every
     * test above used {@code sendInputEvent}, which skips that entirely — so all sixteen passed while
     * Split Right, Next Tab, Close Panel and every Graph and Edit entry were greyed out in the running
     * application.</p>
     */
    @Test
    public void aMenuResolvesAgainstWhatWasFocusedBeforeThePress() {
        UINode subject = new UINode().layout(l -> l.width(100).height(100));
        subject.setFocusPolicy(FocusPolicy.CLICK);
        root.append(subject);
        frame();

        List<UINode> sources = new ArrayList<>();
        registry.register(Command.of("f.probe", "Probe").menu(fileMenu, "1_new", 99)
                .enabledWhen(c -> { sources.add(UINode.sourceOf(c)); return true; })
                .run(c -> { }));

        document.focus().requestFocus(subject);
        rawPress(titleAt(0));

        assertEquals(fileMenu, bar.openMenu());
        assertTrue("the press clears focus first, so the bar must have remembered it",
                sources.contains(subject));
    }

    @Test
    public void aRowOfTheOpenMenuNeverBecomesTheRememberedFocus() {
        UINode subject = new UINode().layout(l -> l.width(100).height(100));
        subject.setFocusPolicy(FocusPolicy.CLICK);
        root.append(subject);
        frame();
        document.focus().requestFocus(subject);

        rawPress(titleAt(0));      // a menu takes focus for its own rows the moment it opens
        bar.close();

        List<UINode> sources = new ArrayList<>();
        registry.register(Command.of("f.probe2", "Probe").menu(fileMenu, "1_new", 99)
                .enabledWhen(c -> { sources.add(UINode.sourceOf(c)); return true; })
                .run(c -> { }));
        rawPress(titleAt(0));

        assertTrue("a MenuItem must never overwrite the answer the bar exists to keep",
                sources.contains(subject));
    }

    /**
     * Through the raw platform sink, so hit-testing AND {@code emitMouseDown}'s focus handling really run.
     *
     * <p>A frame first: {@code consumeMouseEvent} early-returns until one has been presented, and the
     * press resolves its target from the hover cache, which a frame is what settles.</p>
     */
    private void rawPress(UINode target) {
        frame();
        var cache = target.box();
        // THE BOX'S OWN SPACE STARTS AT ZERO. `localToWorld` maps this box's local origin to the
        // surface, so it already carries the offset that `x()`/`y()` report -- adding them composes
        // the same displacement twice and the press lands one title along, which is why this opened
        // Edit when it pressed File.
        org.joml.Vector2f centre = com.crystalgui.core.data.Transform2D.apply(cache.localToWorld(),
                cache.width() / 2f, cache.height() / 2f);
        document.input().consumeMouseEvent(
                new com.crystalgraphics.platform.input.CgSystemInput.Mouse.Event(
                        Math.round(centre.x()), Math.round(centre.y()), 0, 0,
                        CgMouseCodes.LEFT_BUTTON, true, 0f, 1L));
        frame();
    }


    private void arrow(int keyCode) {
        UINode focused = document.focus().focused();
        UINode target = focused != null ? focused : root;
        document.input().send(target,
                new com.crystalgui.ui.event.KeyboardEvent.Down(target, keyCode, '\0', false, 0, 0L));
    }

    /** The root of the open chain — what the bar actually put on screen. */
    private Menu openMenuElement() {
        return findMenu(document);
    }

    private static Menu findMenu(UINode element) {
        if (element instanceof Menu menu) return menu;
        for (UINode child : element.children()) {
            Menu found = findMenu(child);
            if (found != null) return found;
        }
        return null;
    }

    /** A release whose press landed somewhere else — the drag half of press-drag-release. */
    private void releaseOver(UINode target) {
        frame();
        var cache = target.box();
        org.joml.Vector2f centre = com.crystalgui.core.data.Transform2D.apply(cache.localToWorld(),
                cache.width() / 2f, cache.height() / 2f);
        document.input().consumeMouseEvent(
                new com.crystalgraphics.platform.input.CgSystemInput.Mouse.Event(
                        Math.round(centre.x()), Math.round(centre.y()), 0, 0,
                        CgMouseCodes.LEFT_BUTTON, false, 0f, 2L));
        frame();
    }

    // ── Mnemonics ───────────────────────────────────────────────────────────────────────────────

    /** Alt+F from anywhere, which is the whole reason the listener is on the root in capture phase. */
    @Test
    public void altPlusTheMnemonicOpensTheMenu() {
        altPress('f');
        assertEquals(fileMenu, bar.openMenu());
    }

    @Test
    public void altPlusTheMnemonicAgainClosesIt() {
        altPress('f');
        altPress('f');
        assertNull("rebuilding instead would discard the chain this very key is dispatching through",
                bar.openMenu());
    }

    @Test
    public void altSwitchesBetweenMenus() {
        altPress('f');
        altPress('e');
        assertEquals(editMenu, bar.openMenu());
    }

    @Test
    public void aLetterWithoutAltIsNotAMnemonic() {
        press(titleAt(0));
        bar.close();
        document.input().send(root,
                new com.crystalgui.ui.event.KeyboardEvent.Down(root, 0, 'f', false, 0, 0L));
        assertNull("typing in a text field must not open the File menu", bar.openMenu());
    }

    private void altPress(char letter) {
        document.input().send(root, new com.crystalgui.ui.event.KeyboardEvent.Down(
                root, 0, letter, false, com.crystalgraphics.platform.input.CgModifiers.ALT, 0L));
    }


    @Test
    public void theAmpersandIsStrippedFromTheLabel() {
        assertEquals("File", MenuBarView.strip("&File"));
        assertEquals("Save & Exit", MenuBarView.strip("Save && Exit"));
    }

    @Test
    public void theMnemonicIndexIsIntoTheStrippedLabel() {
        assertEquals(0, MenuBarView.mnemonicIndexOf("&File"));
        // The trap: computing from the RAW index gives 2 here, which underlines the wrong letter for
        // every mnemonic that is not the first character.
        assertEquals(1, MenuBarView.mnemonicIndexOf("V&iew"));
        assertEquals(-1, MenuBarView.mnemonicIndexOf("Help"));
        assertEquals("a doubled ampersand is a literal, not a marker",
                -1, MenuBarView.mnemonicIndexOf("Save && Exit"));
    }

    // ── Interaction the bar owns ────────────────────────────────────────────────────────────────

    @Test
    public void arrowsMoveBetweenMenus() {
        rawPress(titleAt(0));
        arrow(CgKeyCodes.KEY_RIGHT);
        assertEquals(editMenu, bar.openMenu());
        arrow(CgKeyCodes.KEY_LEFT);
        assertEquals("and wraps, like Menu's own Up/Down and like Tab", fileMenu, bar.openMenu());
    }

    @Test
    public void rightInsideASubmenuOpensItRatherThanMovingOn() {
        MenuId nested = MenuId.of("bar/nested/" + counter++);
        fileMenu.submenu(nested, "More", "0_first", 0);
        registry.register(Command.of("f.deep", "Deep").menu(nested, "g", 10).run(c -> { }));

        rawPress(titleAt(0));
        Menu menu = openMenuElement();
        // Focus the submenu row, then Right. Menu consumes it, so the bar must not also act.
        arrow(CgKeyCodes.KEY_DOWN);
        arrow(CgKeyCodes.KEY_RIGHT);
        assertEquals("the bar must not steal Right from a row that has somewhere to go",
                fileMenu, bar.openMenu());
        assertNotNull(menu);
    }

    @Test
    public void arrowsDoNothingWithNoMenuOpen() {
        arrow(CgKeyCodes.KEY_RIGHT);
        assertNull(bar.openMenu());
    }

    /**
     * Press a title, drag onto a row, release there.
     *
     * <p>{@code MenuItem} inherits {@code Button}'s {@code isWasPressTarget()} guard, which refuses this
     * on its own — so the arming is the whole feature. @see Menu#armForRelease
     */
    @Test
    public void pressDragReleaseChoosesTheRowItIsReleasedOver() {
        rawPress(titleAt(0));
        MenuItem save = itemNamed(openMenuElement(), "Save");
        assertNotNull(save);
        releaseOver(save);
        assertEquals(List.of("f.save"), ran);
    }

    @Test
    public void aReleaseOverARowOfAMenuNobodyIsDraggingDoesNothing() {
        // Opened by Alt rather than a press: there is no held button, so nothing may be armed.
        altPress('f');
        MenuItem save = itemNamed(openMenuElement(), "Save");
        assertNotNull(save);
        releaseOver(save);
        assertTrue("an unarmed menu must not turn a stray release into an activation", ran.isEmpty());
    }

    // ── The burger ──────────────────────────────────────────────────────────────────────────────

    @Test
    public void collapsingHidesTheTitlesAndShowsTheBurger() {
        bar.setCollapsed(true);
        frame();
        assertTrue(bar.isCollapsed());
        assertTrue("the bar says so, so a theme can restyle the whole row",
                bar.hasClass(MenuBarView.COLLAPSED_CLASS));
        assertEquals("a collapsed title takes no space", 0f,
                widthOf(titleAt(1)), 0.01f);
    }

    @Test
    public void collapsingClosesWhateverWasOpen() {
        rawPress(titleAt(0));
        bar.setCollapsed(true);
        assertNull("a menu anchored to a title that is now display:none has nothing to hang from",
                bar.openMenu());
    }

    @Test
    public void anExplicitChoiceSurvivesAWidthThatWouldNotHaveCollapsed() {
        bar.setCollapsed(true);
        frame();
        frame();
        assertTrue("the automatic check must not undo a user's answer", bar.isCollapsed());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    private static List<String> labelsOf(Menu menu) {
        List<String> out = new ArrayList<>();
        for (MenuItem item : menu.getItems()) out.add(item.getText());
        return out;
    }

    private static MenuItem itemNamed(Menu menu, String label) {
        for (MenuItem item : menu.getItems()) {
            if (label.equals(item.getText())) return item;
        }
        return null;
    }

    private static int separatorsIn(Menu menu) {
        int count = 0;
        // A PART, not a class, and found by a DEEP query rather than a child walk. The codemod
        // renamed the constant and left `hasClass`, so this asked whether a separator carried a CSS
        // class called "separator" -- nothing does, so it counted zero and the sibling test asserting
        // "no separator here" passed for free. And a menu's rows are composed through a slot, so the
        // separators are not direct children of the items container either.
        for (UINode child : deepAll(menu, "." + Menu.SEPARATOR_PART)) {
            if (Menu.SEPARATOR_PART.equals(child.get(Attribute.PART))) count++;
        }
        return count;
    }

    private static int countMenus(UINode element) {
        int count = element instanceof Menu ? 1 : 0;
        for (UINode child : element.children()) count += countMenus(child);
        return count;
    }
}
