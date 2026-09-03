package com.crystalgui.workbench.chrome.status;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.notify.StatusBar;
import com.crystalgui.core.notify.StatusBarAlignment;
import com.crystalgui.core.notify.StatusBarEntry;
import com.crystalgui.core.notify.StatusBarEntryAccessor;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.widget.overlay.Tooltip;
import com.crystalgui.widget.text.UIText;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The status bar's view half — VS Code's {@code statusbarPart} over {@code IStatusbarService}.
 *
 * <p>What these pin is the seam, not the picture: the view shows what the <em>service</em> holds, at the
 * end and in the order the <em>writer</em> chose, and it keeps one element per entry rather than
 * rebuilding. Nothing here asserts a pixel — the bar's geometry is the stylesheet's business.</p>
 */
public class StatusBarViewTest extends UiDocumentTestBase {

    private StatusBarView bar;

    @Before
    public void setUp() {
        StatusBar.resetForTesting();
        bar = new StatusBarView();
        UIElement root = new UIElement().layout(l -> l.width(400).height(100));
        root.append(bar);
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        settle();
    }

    @After
    public void tearDown() {
        StatusBar.resetForTesting();
    }

    private void settle() {
        for (int i = 0; i < 4; i++) frame();
    }

    private List<UIElement> itemsIn(String groupClass) {
        UIElement group = deepOrNull(bar, "." + groupClass);
        assertNotNull("no " + groupClass + " group", group);
        return deepAll(group, StatusBarView.ITEM_CLASS);
    }

    private static StatusBarEntryAccessor add(String name, String text, StatusBarAlignment alignment) {
        return StatusBar.addEntry(StatusBarEntry.of(name, text), name, alignment);
    }

    private static String textOf(UIElement item) {
        return ((UIText) item).getText();
    }

    /** The writer's alignment decides the end. The view never guesses from the id or the text. */
    @Test
    public void anEntryLandsAtTheEndItsWriterChose() {
        add("explorer", "created notes.txt", StatusBarAlignment.LEFT);
        add("caret", "Ln 51, Col 39", StatusBarAlignment.RIGHT);
        settle();

        assertEquals(1, itemsIn(StatusBarView.LEFT_CLASS).size());
        assertEquals(1, itemsIn(StatusBarView.RIGHT_CLASS).size());
        assertEquals("Ln 51, Col 39", textOf(itemsIn(StatusBarView.RIGHT_CLASS).get(0)));
    }

    /**
     * <b>An entry keeps its element across an update.</b>
     *
     * <p>The engine's standing rule is that a widget must never rebuild the elements it is being clicked
     * on, and entries are written from per-frame paths — the shader graph's line-owner readout fires
     * on every caret move in the generated source. A view that rebuilt per change would be discarding and
     * recreating its whole tree continuously, which no screenshot would ever show.</p>
     */
    @Test
    public void updatingAnEntryReusesItsElement() {
        StatusBarEntryAccessor caret = add("caret", "Ln 1, Col 1", StatusBarAlignment.RIGHT);
        settle();
        UIElement before = itemsIn(StatusBarView.RIGHT_CLASS).get(0);

        caret.update(caret.entry().withText("Ln 2, Col 7"));
        settle();
        List<UIElement> after = itemsIn(StatusBarView.RIGHT_CLASS);

        assertEquals("still one entry", 1, after.size());
        assertSame("the slot was rebuilt rather than updated", before, after.get(0));
        assertEquals("Ln 2, Col 7", textOf(after.get(0)));
    }

    /** Disposing the handle takes its element with it — the bar is the present, not a log. */
    @Test
    public void disposingAnEntryRemovesIt() {
        StatusBarEntryAccessor explorer = add("explorer", "created notes.txt", StatusBarAlignment.LEFT);
        settle();
        assertEquals(1, itemsIn(StatusBarView.LEFT_CLASS).size());

        explorer.dispose();
        settle();
        assertTrue("the slot outlived the entry", itemsIn(StatusBarView.LEFT_CLASS).isEmpty());
    }

    /**
     * <b>Two writers that choose the same id do not share a slot.</b>
     *
     * <p>The reason the service hands out handles at all. A string-keyed {@code set(id, text)} gave the
     * second writer the first one's slot and lost whichever spoke first — the same collision that made
     * {@code Workbench.onStatus} unusable, merely narrowed from "one slot for everyone" to "one slot per
     * string". With an accessor per registration it is not expressible.</p>
     */
    @Test
    public void twoWritersSharingAnIdEachKeepTheirOwnEntry() {
        StatusBar.addEntry(StatusBarEntry.of("Build", "compiling"), "shared", StatusBarAlignment.LEFT);
        StatusBar.addEntry(StatusBarEntry.of("Index", "indexing"), "shared", StatusBarAlignment.LEFT);
        settle();

        List<UIElement> left = itemsIn(StatusBarView.LEFT_CLASS);
        assertEquals("one writer overwrote the other", 2, left.size());
    }

    /**
     * <b>Priority orders the group; registration order only breaks ties.</b>
     *
     * <p>VS Code's rule, higher first. Entries used to render in whatever order their writers happened to
     * run, so the right-hand group's layout was decided by the order of the lines inside
     * {@code TextFileDocument.setActive} — a bar you cannot glance at, because it rearranges itself for
     * reasons that have nothing to do with you.</p>
     */
    @Test
    public void higherPriorityRendersFurtherLeft() {
        StatusBar.addEntry(StatusBarEntry.of("Encoding", "UTF-8"), "encoding",
                StatusBarAlignment.RIGHT, 98);
        StatusBar.addEntry(StatusBarEntry.of("Cursor position", "51:39"), "caret",
                StatusBarAlignment.RIGHT, 100);
        settle();

        List<UIElement> right = itemsIn(StatusBarView.RIGHT_CLASS);
        assertEquals(2, right.size());
        assertEquals("the later registration outranks by priority", "51:39", textOf(right.get(0)));
        assertEquals("UTF-8", textOf(right.get(1)));
    }

    /**
     * Withdrawing from one end and registering at the other leaves nothing behind.
     *
     * <p>The removal pass runs before the placement pass for exactly this: an element that changed ends
     * would otherwise briefly be a child of both groups, and {@code addChild} throws on a second parent.</p>
     */
    @Test
    public void movingAnEntryBetweenEndsLeavesNothingBehind() {
        StatusBarEntryAccessor compile = add("compile", "compiled 9n/8e", StatusBarAlignment.RIGHT);
        settle();
        assertEquals(1, itemsIn(StatusBarView.RIGHT_CLASS).size());

        compile.dispose();
        add("compile", "compiled 9n/8e", StatusBarAlignment.LEFT);
        settle();
        assertEquals("left it in the old group", 0, itemsIn(StatusBarView.RIGHT_CLASS).size());
        assertEquals(1, itemsIn(StatusBarView.LEFT_CLASS).size());
    }

    /**
     * <b>Only the leading entry of each group is marked</b>, and the mark moves when the group does.
     *
     * <p>It is what the divider rule keys on: the sheet draws a border before every entry and switches it
     * off on this class, because the selector engine has no {@code :first-child}. Stamp it once and never
     * move it and the bar grows a stray rule against its left edge the first time an entry is withdrawn —
     * cosmetic, invisible in a test that only counts entries, and permanent.</p>
     */
    @Test
    public void onlyTheLeadingEntryOfEachGroupIsMarked() {
        StatusBarEntryAccessor first = add("a", "first", StatusBarAlignment.LEFT);
        add("b", "second", StatusBarAlignment.LEFT);
        add("caret", "1:1", StatusBarAlignment.RIGHT);
        settle();

        List<UIElement> left = itemsIn(StatusBarView.LEFT_CLASS);
        assertTrue("the leading left entry carries it", left.get(0).hasClass(StatusBarView.FIRST_CLASS));
        assertFalse("the one after it does not", left.get(1).hasClass(StatusBarView.FIRST_CLASS));
        assertTrue("each group has its own leading entry",
                itemsIn(StatusBarView.RIGHT_CLASS).get(0).hasClass(StatusBarView.FIRST_CLASS));

        first.dispose();
        settle();
        assertTrue("the mark followed the group's new leader",
                itemsIn(StatusBarView.LEFT_CLASS).get(0).hasClass(StatusBarView.FIRST_CLASS));
    }

    /**
     * <b>A hidden entry leaves the bar and stays enumerable.</b>
     *
     * <p>VS Code's status bar context menu and IntelliJ's Status Bar Widgets settings both work this way,
     * and both need {@link StatusBarEntry#name()} to do it: a menu lists entries by what they <em>are</em>,
     * because you cannot offer "hide 51:39" as a checkbox and the text it names changes on every
     * keystroke. The name/text split looked like redundancy until something had to enumerate the bar.</p>
     *
     * <p>{@code allEntries()} keeps listing the hidden one, or there would be no way to switch it back on.</p>
     */
    @Test
    public void aHiddenEntryLeavesTheBarButNotTheList() {
        add("caret", "51:39", StatusBarAlignment.RIGHT);
        add("encoding", "UTF-8", StatusBarAlignment.RIGHT);
        settle();
        assertEquals(2, itemsIn(StatusBarView.RIGHT_CLASS).size());

        StatusBar.setHidden("encoding", true);
        settle();
        assertEquals("the hidden entry is still rendered", 1, itemsIn(StatusBarView.RIGHT_CLASS).size());
        assertEquals("51:39", textOf(itemsIn(StatusBarView.RIGHT_CLASS).get(0)));
        assertEquals("a menu could not offer it back", 2, StatusBar.allEntries().size());
        assertTrue(StatusBar.isHidden("encoding"));

        StatusBar.setHidden("encoding", false);
        settle();
        assertEquals(2, itemsIn(StatusBarView.RIGHT_CLASS).size());
    }

    /**
     * <b>An entry naming a command is clickable, and says so.</b>
     *
     * <p>Both references make most of the bar actionable — VS Code's encoding entry runs
     * {@code changeEncoding}, IntelliJ's line-separator widget opens a popup. The entry names a command
     * <em>id</em> rather than holding a callback, which is what keeps the same verb reachable from the
     * palette and a keymap.</p>
     */
    @Test
    public void anEntryWithACommandIsMarkedClickable() {
        StatusBarEntryAccessor accessor = StatusBar.addEntry(
                new StatusBarEntry("Shader graph compilation", "2 errors", null,
                        "workbench.showProblems", StatusBarEntry.Kind.ERROR),
                "compile", StatusBarAlignment.LEFT);
        settle();

        UIElement item = itemsIn(StatusBarView.LEFT_CLASS).get(0);
        assertTrue("nothing said it could be pressed",
                item.hasClass(StatusBarView.CLICKABLE_CLASS));

        // A SUCCEEDING COMPILE HAS NOTHING TO SHOW YOU, so the mark has to come off again.
        accessor.update(accessor.entry().withCommand(null).withText("compiled 12n/9e"));
        settle();
        assertFalse("it stayed clickable with nowhere to go",
                itemsIn(StatusBarView.LEFT_CLASS).get(0).hasClass(StatusBarView.CLICKABLE_CLASS));
    }

    /**
     * <b>A clickable entry has to run its command FROM the element pressed.</b>
     *
     * <p>{@code CommandRegistry.run(id)} builds an <em>empty</em> data context, so any command guarded by
     * {@code enabledWhereData} — which every command acting on a document or a workbench is — evaluates its
     * guard against nothing, fails it, and returns false. The entry drew a pointer cursor and did nothing
     * when clicked: the exact "the command exists but nothing happens" failure {@code CommandRegistry.run}
     * warns about in its own body.</p>
     *
     * <p>The element is what makes it work, because a data context resolves by walking up the tree and then
     * asking the document — which is where a workbench registers itself as a provider.</p>
     */
    @Test
    public void aCommandNeedsTheElementItWasRunFrom() {
        boolean[] ran = { false };
        CommandRegistry.global().register(Command.of("test.needsWindow", "Needs A Window")
                .run(c -> ran[0] = true)
                            // SURFACE, not the old engine's WINDOW. `UiDataKeys.WINDOW` is a `DataKey<UIWindow>`
            // and there is no `UIWindow` anywhere in a new-engine tree, so it can never resolve --
            // the command's own precondition would be unsatisfiable and the test would be asserting
            // that a command never runs. The document plays that role here, and it is what
            // `CommandPalette.SURFACE` names.
            // THE SOURCE ELEMENT, which is what this test is about and the only thing that can
            // answer here. `UiDataKeys.WINDOW` is a `DataKey<UIWindow>` and there is no `UIWindow`
            // in a new-engine tree at all; `CommandPalette.SURFACE` is read by consumers and
            // PROVIDED by nobody, so it is null from every element. `UIElement.sourceOf` is the engine's
            // own answer to "what was this run from".
            .enabledWhen(c -> UIElement.sourceOf(c) != null));
        try {
            assertFalse("a contextless run must not silently appear to work",
                    CommandRegistry.global().run("test.needsWindow"));
            assertFalse(ran[0]);

            assertTrue("running from the pressed element should resolve the document",
                    CommandRegistry.global().run("test.needsWindow", CommandContext.of(bar)));
            assertTrue(ran[0]);
        } finally {
            CommandRegistry.global().unregister("test.needsWindow");
        }
    }

    /**
     * <b>The hide menu lists entries by NAME and toggles them.</b>
     *
     * <p>Ported from VS Code's {@code statusbarPart.ts}, which builds one checkable
     * {@code ToggleStatusbarEntryVisibilityAction} per entry labelled by {@code entry.name}. That is what
     * the name/text split is for — you cannot offer "hide 51:39" as a checkbox, and the text it would name
     * changes on every keystroke.</p>
     *
     * <p>Driven through the menu rather than through {@code StatusBar.setHidden}, because a surface that
     * reaches the setter is the whole point of it existing.</p>
     */
    @Test
    public void theHideMenuNamesEntriesAndTogglesThem() {
        StatusBar.addEntry(StatusBarEntry.of("Cursor position", "51:39"), "caret",
                StatusBarAlignment.RIGHT, 100);
        StatusBar.addEntry(StatusBarEntry.of("File encoding", "UTF-8"), "encoding",
                StatusBarAlignment.RIGHT, 98);
        settle();
        assertEquals(2, itemsIn(StatusBarView.RIGHT_CLASS).size());

        var menu = bar.hideMenu();
        List<String> labels = new java.util.ArrayList<>();
        for (com.crystalgui.widget.overlay.MenuItem item : menu.getItems()) labels.add(item.getText());
        assertEquals("the menu should name what entries ARE, not what they show",
                List.of("Cursor position", "File encoding"), labels);

        for (com.crystalgui.widget.overlay.MenuItem item : menu.getItems()) {
            if ("File encoding".equals(item.getText())) menu.onItemActivated.emit(item);
        }
        settle();

        assertEquals("the entry did not leave the bar", 1, itemsIn(StatusBarView.RIGHT_CLASS).size());
        assertEquals("51:39", textOf(itemsIn(StatusBarView.RIGHT_CLASS).get(0)));
        assertEquals("and a hidden entry must still be listed, or it cannot come back",
                2, bar.hideMenu().getItems().size());
    }

    /**
     * <b>A slot's tooltip is attached once and re-texted, never re-attached.</b>
     *
     * <p>{@code Tooltip.attach} adds a hover listener pair per call and {@code detach} leaves them inert
     * rather than removing them — its own javadoc records that a set/clear/set cycle silently accumulates
     * them. The compile summary rewrites its tooltip on every recompile, which for an animated graph is
     * every frame, so getting this wrong is unbounded growth rather than a tidiness question.</p>
     */
    @Test
    public void aSlotKeepsOneTooltipAcrossUpdates() {
        StatusBarEntryAccessor compile = StatusBar.addEntry(
                new StatusBarEntry("Build", "compiled 9n/8e", "996 chars", null,
                        StatusBarEntry.Kind.STANDARD),
                "compile", StatusBarAlignment.LEFT);
        settle();
        UIElement slot = itemsIn(StatusBarView.LEFT_CLASS).get(0);
        hover(slot);
        assertEquals("one tooltip for one slot", 1, tooltipsAnchoredTo(slot));

        for (int i = 0; i < 5; i++) {
            compile.update(compile.entry().withText("compiled " + i + "n/8e").withTooltip(i + " chars"));
            settle();
        }
        assertSame("the slot was rebuilt", slot, itemsIn(StatusBarView.LEFT_CLASS).get(0));
        hover(slot);
        assertEquals("a tooltip was attached per update", 1, tooltipsAnchoredTo(slot));
    }

    /**
     * <b>A tooltip is not a child of what it describes any more, so it is counted by its ANCHOR.</b>
     *
     * <p>{@code Tooltip.attach} used to parent the tooltip onto the thing it belongs to, which made
     * "how many tooltips does this slot have" a subtree question. It cannot: nearly every anchor
     * worth a tooltip is a composite with a shadow root and no slot, and a light child of one of
     * those is never composed -- no box, no paint, nothing reporting a problem. So a tooltip lives
     * in the document and remembers its anchor, and the question this test is really asking --
     * does a fifth update leave five tooltips behind -- is asked of that.</p>
     */
    /**
     * A tooltip JOINS THE DOCUMENT LAZILY -- on its first show, not when it is attached -- because
     * a widget attaches its own in its constructor, where its anchor is in no document yet. So a
     * fixture that never hovers has no tooltip to count, and the assertion below would hold at zero
     * whatever the widget did.
     */
    private void hover(UIElement target) {
        int[] centre = centreOf(target);
        move(centre[0], centre[1]);
        frame();
    }

    private int tooltipsAnchoredTo(UIElement anchor) {
        // Counted across the DOCUMENT, not under the anchor and not by `anchor()` -- which answers
        // the anchor a tooltip is CURRENTLY SHOWING FOR, so it is null until one is hovered. With a
        // single slot in the fixture, "how many tooltips exist" is exactly the question: one is
        // right and six is the defect (a fresh tooltip attached on every update).
        int n = 0;
        for (UIElement node : composed(document)) {
            if (node instanceof Tooltip) n++;
        }
        return n;
    }
}
