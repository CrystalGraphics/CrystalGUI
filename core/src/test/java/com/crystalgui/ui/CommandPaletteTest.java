package com.crystalgui.ui;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.chrome.ChromeCommands;
import com.crystalgui.ui.elements.chrome.CommandPalette;
import com.crystalgui.ui.elements.chrome.QuickPick;
import com.crystalgui.core.collection.pick.QuickPickItem;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.FlexDirection;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.ui.UIElement;

/**
 * The command palette, and above all <b>which element its commands are resolved against</b>.
 *
 * <p>Ranking lives in {@code QuickPickSourceTest} and is headless. What needs a window is the half that is
 * invisible when wrong: a palette whose commands are scoped to the palette itself looks like it works and
 * is simply missing most of its contents.</p>
 */
public class CommandPaletteTest extends UiTestBase {

    /** Physical pixels per logical pixel — {@code UIWindow}'s default. */
    private static final float UI_SCALE = 2f;

    /** Stands in for a {@code DockArea} or an editor — something a command's {@code enabledWhen} walks up
     * the tree to find. Every scoped command in the codebase resolves its target this way. */
    private static final class Scope extends UIElement {
    }

    private static final String SCOPED = "test.scopedCommand";
    private static final String GLOBAL = "test.globalCommand";
    /** Enabled by a provider on the WINDOW rather than by anything on the focus path. */
    private static final String WINDOW_SCOPED = "test.windowScopedCommand";
    /** A third row, so navigation tests distinguish "moved" from "wrapped" — with two rows both
     * answers are index 1 and the test passes for either behaviour. */
    private static final String THIRD = "test.thirdCommand";

    private UIWindow window;
    private Scope scope;
    private UIElement inner;

    /** Every source element a command was actually invoked with, in order. */
    private final List<UIElement> invokedWith = new ArrayList<>();

    @Before
    public void setUpWindow() {
        // Commands are GLOBAL now, so a registry populated by another test is visible here. These
        // assertions are about which rows a palette shows and in what order, so they need a known set.
        CommandRegistry.global().resetForTesting();
        scope = new Scope();
        inner = new UIElement();
        inner.setFocusPolicy(FocusPolicy.FOCUSABLE);
        scope.addChild(inner);

        UIElement root = new UIElement().layout(l -> l.width(600).height(400)
                .flexDirection(FlexDirection.COLUMN));
        root.addChild(scope);

        window = new UIWindow(Ui.of(root));
        // DEFAULT as well as the theme, and this is not incidental: every quickpick rule lives in
        // default.css, so a window without it exercises a palette with no stylesheet at all. The geometry
        // assertions below were passing for that reason while the harness showed an empty list.
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.getStyleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));
        window.init(1200, 800);

        window.getCommands().register(Command.of(SCOPED, "Scoped Action")
                .run(context -> invokedWith.add(UIElement.sourceOf(context)))
                .enabledWhen(CommandPaletteTest::hasScopeAbove));
        window.getCommands().register(Command.of(GLOBAL, "Global Action")
                .run(context -> invokedWith.add(UIElement.sourceOf(context))));
        window.getCommands().register(Command.of(THIRD, "Third Action")
                .run(context -> invokedWith.add(UIElement.sourceOf(context))));

        frame();
    }

    private static boolean hasScopeAbove(CommandContext context) {
        for (UIElement element = UIElement.sourceOf(context); element != null; element = element.getParent()) {
            if (element instanceof Scope) return true;
        }
        return false;
    }

    private void frame() {
        window.updateWithoutPainting();
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
    }

    private void key(int keyCode) {
        window.getInputHandler().consumeKeyboardEvent(
                new CgSystemInput.Keyboard.Event('\0', keyCode, true, false, 1L));
    }

    private static List<String> idsOf(QuickPick pick) {
        return pick.visibleEntries().stream().map(e -> e.item().id()).toList();
    }

    // ── The focus trap ──────────────────────────────────────────────────────────────────────────

    /**
     * <b>The load-bearing test.</b> A scoped command must be listed when the palette was opened from
     * inside its scope.
     *
     * <p>Opening a palette moves focus into the palette's own search field, which sits under the window
     * root and under no {@code Scope} at all. Any implementation that enumerates commands <em>after</em>
     * showing — or that evaluates {@code enabledWhen} against the live focused element — asks the wrong
     * question and quietly drops every context-scoped command in the application.</p>
     *
     * <p>This fails for a mutant that moves the {@code getFocusedElement()} read below the {@code open()}
     * call, which is the single most natural way to write this class wrong.</p>
     */
    @Test
    public void aScopedCommandIsListedWhenThePaletteWasOpenedFromInsideItsScope() {
        window.getInputHandler().requestFocus(inner);
        QuickPick pick = CommandPalette.open(window);

        assertTrue("scoped command missing — enablement was evaluated against the palette, not the "
                + "element focused before it opened", idsOf(pick).contains(SCOPED));
    }

    /**
     * The other half: outside its scope the command is <b>listed but disabled</b>, not absent.
     *
     * <p>This assertion was inverted until the harness disproved it. Hiding unavailable commands is VS
     * Code's rule and does not survive predicates that walk up from focus — with nothing focused, the dock
     * harness listed one command out of nine and read as a dead widget. Listing everything and dimming what
     * cannot run is IntelliJ's behaviour and the one that holds here.</p>
     */
    @Test
    public void aScopedCommandIsListedButDisabledWhenOpenedFromOutsideItsScope() {
        window.getInputHandler().requestFocus(null);
        QuickPick pick = CommandPalette.open(window);

        assertTrue("an unavailable command must still be listed", idsOf(pick).contains(SCOPED));
        assertFalse("...but must not be selectable", itemFor(pick, SCOPED).enabled());
        assertTrue(itemFor(pick, GLOBAL).enabled());
    }

    /** With nothing focused, selection must land on a row that can actually run — and must terminate.
     * A wrap-around search for an enabled row is an infinite loop when every row is disabled, which is
     * reachable: it is exactly the state an untouched window opens in. */
    @Test
    public void selectionSkipsDisabledRowsAndTerminatesWhenEveryRowIsDisabled() {
        window.getInputHandler().requestFocus(null);
        QuickPick pick = CommandPalette.open(window);
        pick.searchField().setText("Scoped");
        pick.refresh();

        assertEquals("only the disabled command matches", 1, pick.visibleEntries().size());
        assertEquals("nothing selectable, so nothing selected", -1, pick.resultList().getFocusedIndex());

        key(CgKeyCodes.KEY_DOWN);
        key(CgKeyCodes.KEY_RETURN);

        assertTrue("a disabled row must not run", invokedWith.isEmpty());
    }

    @Test
    public void clickingADisabledRowRunsNothing() {
        window.getInputHandler().requestFocus(null);
        QuickPick pick = CommandPalette.open(window);
        pick.searchField().setText("Scoped");
        pick.refresh();
        frame();
        frame();

        UIElement row = pick.resultList().realisedRows().get(0);
        assertNotNull("the disabled row is still listed and realised", row);
        clickCentreOf(row);

        assertTrue(invokedWith.isEmpty());
        assertTrue("clicking a dimmed row must not dismiss the palette either", pick.isOpen());
    }

    /**
     * Running is scoped the same way listing is.
     *
     * <p>Listing against the captured element and then <em>executing</em> against the live one would be
     * worse than either mistake alone: the command appears, is chosen, and acts on the search field.</p>
     */
    @Test
    public void acceptingRunsTheCommandAgainstTheOriginallyFocusedElement() {
        window.getInputHandler().requestFocus(inner);
        QuickPick pick = CommandPalette.open(window);

        selectById(pick, SCOPED);
        pick.accept();

        assertEquals(1, invokedWith.size());
        assertSame("the command ran against the palette's search field, not the caller", inner,
                invokedWith.get(0));
    }

    private static QuickPickItem itemFor(QuickPick pick, String id) {
        return pick.visibleEntries().stream().map(e -> e.item())
                .filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    private void selectById(QuickPick pick, String id) {
        int index = idsOf(pick).indexOf(id);
        assertTrue("'" + id + "' is not listed", index >= 0);
        pick.resultList().setFocusedIndex(index);
    }

    // ── Opening and interaction ─────────────────────────────────────────────────────────────────

    @Test
    public void openingFocusesTheSearchField() {
        QuickPick pick = CommandPalette.open(window);
        assertSame(pick.searchField().field(), window.getInputHandler().getFocusedElement());
    }

    @Test
    public void theFirstRowIsPreSelectedSoEnterOnAnUntouchedPaletteIsMeaningful() {
        QuickPick pick = CommandPalette.open(window);
        assertFalse(pick.visibleEntries().isEmpty());
        assertEquals(0, pick.resultList().getFocusedIndex());
    }

    @Test
    public void downAndUpMoveTheSelectionAndWrap() {
        window.getInputHandler().requestFocus(inner);
        QuickPick pick = CommandPalette.open(window);
        int count = pick.visibleEntries().size();
        assertTrue("need at least three rows to tell 'moved' from 'wrapped'", count >= 3);

        key(CgKeyCodes.KEY_DOWN);
        assertEquals(1, pick.resultList().getFocusedIndex());

        key(CgKeyCodes.KEY_UP);
        assertEquals(0, pick.resultList().getFocusedIndex());

        // Wrapping backwards from the top, which is what makes the last row reachable in one keystroke.
        key(CgKeyCodes.KEY_UP);
        assertEquals(count - 1, pick.resultList().getFocusedIndex());
    }

    @Test
    public void enterAcceptsTheFocusedRow() {
        window.getInputHandler().requestFocus(inner);
        QuickPick pick = CommandPalette.open(window);
        selectById(pick, GLOBAL);

        key(CgKeyCodes.KEY_RETURN);

        assertEquals(1, invokedWith.size());
        assertFalse("accepting must close the palette", pick.isOpen());
    }

    /**
     * A closed palette leaves nothing behind in the tree.
     *
     * <p>{@code CommandPalette} builds a fresh instance per open, so one that stayed attached would
     * accumulate a dead search field and a live {@code ListView} ticker on every invocation.</p>
     */
    @Test
    public void closingDetachesThePaletteFromTheTree() {
        QuickPick pick = CommandPalette.open(window);
        assertNotNull(pick.getParent());

        pick.hide();

        assertEquals(null, pick.getParent());
    }

    // ── Clicking ────────────────────────────────────────────────────────────────────────────────

    /**
     * A single click on a row runs it.
     *
     * <p>Reported from the harness as "clicking does absolutely nothing". Driven through the real input
     * handler at real coordinates, because the failure is necessarily in hit testing or dispatch — the
     * model half is already covered by {@link #enterAcceptsTheFocusedRow}, which passes.</p>
     */
    @Test
    public void clickingARowRunsIt() {
        window.getInputHandler().requestFocus(inner);
        QuickPick pick = CommandPalette.open(window);
        frame();
        frame();

        var realised = pick.resultList().realisedRows();
        assertFalse("no rows realised", realised.isEmpty());
        UIElement row = realised.get(0);
        assertNotNull("row 0 is not realised", row);

        clickCentreOf(row);

        assertEquals("a click on a palette row ran nothing", 1, invokedWith.size());
    }

    /** Physical-pixel press and release at an element's centre. {@code uiScale} defaults to 2, so logical
     * coordinates fed straight to {@code consumeMouseEvent} land at half the intended position. */
    private void clickCentreOf(UIElement element) {
        var cache = element.getRuntimeCache();
        int x = Math.round((cache.getX() + cache.getWidth() / 2f) * UI_SCALE);
        int y = Math.round((cache.getY() + cache.getHeight() / 2f) * UI_SCALE);
        window.getInputHandler().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(x, y, 0, 0, -1, false, 0f, -1L));
        frame();
        window.getInputHandler().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(x, y, 0, 0, 0, true, 0f, System.currentTimeMillis()));
        window.getInputHandler().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(x, y, 0, 0, 0, false, 0f, System.currentTimeMillis()));
        frame();
    }

    // ── Geometry ────────────────────────────────────────────────────────────────────────────────

    /**
     * The list is exactly as tall as the rows it is showing.
     *
     * <p>Asserted as a <b>relationship</b> rather than against a pixel count, so it catches drift in either
     * direction: {@code QuickPick.ROW_HEIGHT} and {@code quickpick .__row__ { height }} in {@code default.css}
     * are a forced pair (a virtualised list needs the row height in Java to map an index to a scroll offset,
     * and cannot read the cascade), and nothing else would notice them disagreeing.</p>
     *
     * <p>It is also the regression test for a real collapse: the list was first written as
     * {@code flex-grow: 1; flex-basis: 0}, which resolves to <b>zero</b> inside a popover — growing
     * distributes free space, and an element sized to its own content has none. The palette rendered as a
     * search box with nothing beneath it, and no other assertion here noticed, because the model was
     * perfectly correct and simply had no box to appear in.</p>
     */
    @Test
    public void theListIsAsTallAsItsRowsSoItNeitherCollapsesNorOverflows() {
        window.getInputHandler().requestFocus(inner);
        QuickPick pick = CommandPalette.open(window);
        frame();
        frame();

        var realised = pick.resultList().realisedRows();
        assertFalse("no rows were realised — the list resolved to zero height", realised.isEmpty());

        float rowHeight = realised.values().iterator().next().getRuntimeCache().getHeight();
        assertTrue("a realised row has no height", rowHeight > 0f);
        assertEquals(pick.visibleEntries().size() * rowHeight,
                pick.resultList().getRuntimeCache().getHeight(), 0.5f);
    }

    // ── Rows ────────────────────────────────────────────────────────────────────────────────────

    /** {@code test.globalCommand} lists under {@code Test}, from the id's namespace. */
    @Test
    public void theCategoryComesFromTheCommandIdNamespace() {
        QuickPick pick = CommandPalette.open(window);
        QuickPickItem item = pick.visibleEntries().stream()
                .map(entry -> entry.item())
                .filter(candidate -> candidate.id().equals(GLOBAL))
                .findFirst().orElseThrow();

        assertEquals("Test", item.category());
        assertEquals("Global Action", item.label());
    }

    /** The accelerator column is filled from the keymap chain above the element the palette was opened
     * from — {@code Keymap.acceleratorsFrom}, which exists for exactly this. */
    @Test
    public void aBoundCommandShowsItsAccelerator() {
        window.ui.rootElement.keymap().bind("Mod+Shift+G", GLOBAL);
        window.getInputHandler().requestFocus(inner);

        QuickPick pick = CommandPalette.open(window);
        QuickPickItem item = pick.visibleEntries().stream()
                .map(entry -> entry.item())
                .filter(candidate -> candidate.id().equals(GLOBAL))
                .findFirst().orElseThrow();

        assertNotNull("a bound command must show a keybinding", item.accelerator());
        assertTrue(item.accelerator().contains("G"));
    }

    /** The palette's own opener is a normal command and lists itself, as VS Code's does. */
    @Test
    public void theShowCommandsCommandInstallsAndIsItselfListed() {
        ChromeCommands.register();
        QuickPick pick = CommandPalette.open(window);

        assertTrue(idsOf(pick).contains(ChromeCommands.SHOW_COMMANDS));
    }

    // ── Contextuality ───────────────────────────────────────────────────────────────────────────

    private static QuickPickItem itemFor(List<QuickPickItem> items, String id) {
        return items.stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    /**
     * <b>A command that needed the focused element to be available is marked contextual.</b>
     *
     * <p>This is how the palette leads with the verbs of whatever it was opened over. It is measured
     * rather than declared: each command is asked twice, once against the focused element and once
     * against the root, and the ones whose answer changes are the ones focus decided. Nothing has to
     * name a widget, and a command that grows a guard later starts ranking correctly with no other
     * edit.</p>
     *
     * <p>The global command is the control. Without it a computation that marked <em>everything</em>
     * contextual would pass, and the ordering it drives would do nothing at all.</p>
     */
    @Test
    public void aCommandThatNeededTheFocusedElementIsMarkedContextual() {
        window.getInputHandler().requestFocus(inner);
        frame();

        List<QuickPickItem> items = CommandPalette.itemsFor(window.getCommands(), inner);

        assertTrue(itemFor(items, SCOPED).enabled());
        assertTrue("a command enabled only inside its scope did not read as contextual",
                itemFor(items, SCOPED).contextual());
        assertTrue(itemFor(items, GLOBAL).enabled());
        assertFalse("a command enabled at the root did not need the focused element",
                itemFor(items, GLOBAL).contextual());
    }

    /**
     * <b>A command the WINDOW answers for is not contextual</b> — the case that decides the baseline.
     *
     * <p>{@code DataContext.fromWindow} returns immediately when the source is null, so measuring
     * "anywhere" with an empty context takes the window's own providers away too — and those are exactly
     * how Go to File and Reload from Disk find their subject with nothing focused. Every one of them
     * would report itself unavailable at the baseline and be marked contextual, which is the opposite of
     * true, and would put them at the top of a palette opened over an editor.</p>
     *
     * <p>Nothing else in this fixture can see that: a command with no guard at all is enabled against any
     * context, empty or not, so it answers the same either way.</p>
     */
    @Test
    public void aCommandTheWindowAnswersForIsNotContextual() {
        DataKey<String> key = DataKey.create("test.windowScoped", String.class);
        window.addDataProvider(asked -> asked == key ? "yes" : null);
        window.getCommands().register(Command.of(WINDOW_SCOPED, "Window Action")
                .enabledWhereData(data -> data.has(key))
                .run(context -> { }));

        window.getInputHandler().requestFocus(inner);
        frame();

        List<QuickPickItem> items = CommandPalette.itemsFor(window.getCommands(), inner);

        assertTrue("the window's provider was not consulted at all",
                itemFor(items, WINDOW_SCOPED).enabled());
        assertFalse("a window-scoped command was measured against an empty context",
                itemFor(items, WINDOW_SCOPED).contextual());
    }

    /**
     * <b>...and the baseline is the ROOT, not an empty context.</b>
     *
     * <p>The distinction the measurement turns on. {@code CommandContext.of(null)} empties the
     * {@code DataContext} outright — {@code fromWindow} returns immediately without a source — so every
     * window-scoped command would report itself unavailable there and be marked contextual, which is the
     * opposite of true. Opened <em>at</em> the root, nothing is contextual, because nothing about focus
     * decided anything.</p>
     */
    @Test
    public void nothingIsContextualWhenThePaletteWasOpenedAtTheRoot() {
        List<QuickPickItem> items =
                CommandPalette.itemsFor(window.getCommands(), window.ui.rootElement);

        assertFalse(itemFor(items, SCOPED).enabled());
        assertFalse(itemFor(items, SCOPED).contextual());
        assertTrue(itemFor(items, GLOBAL).enabled());
        assertFalse(itemFor(items, GLOBAL).contextual());
    }
}
