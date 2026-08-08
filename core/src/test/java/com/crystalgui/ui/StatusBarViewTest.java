package com.crystalgui.ui;

import com.crystalgui.core.notify.StatusBar;
import com.crystalgui.core.notify.StatusBarAlignment;
import com.crystalgui.core.notify.StatusBarEntry;
import com.crystalgui.core.notify.StatusBarEntryAccessor;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.Tooltip;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.chrome.StatusBarView;

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
public class StatusBarViewTest extends UiTestBase {

    private UIWindow window;
    private StatusBarView bar;

    @Before
    public void setUp() {
        StatusBar.resetForTesting();
        bar = new StatusBarView();
        UIElement root = new UIElement().layout(l -> l.width(400).height(100));
        root.addChild(bar);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(400, 100);
        settle();
    }

    @After
    public void tearDown() {
        StatusBar.resetForTesting();
    }

    private void settle() {
        for (int i = 0; i < 4; i++) window.updateWithoutPainting();
    }

    private List<UIElement> itemsIn(String groupClass) {
        UIElement group = bar.querySelector("." + groupClass);
        assertNotNull("no " + groupClass + " group", group);
        return group.getElementsByClassName(StatusBarView.ITEM_CLASS);
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
        assertEquals("one tooltip for one slot",
                1, slot.getElementsByClassName(Tooltip.LABEL_CLASS).size());

        for (int i = 0; i < 5; i++) {
            compile.update(compile.entry().withText("compiled " + i + "n/8e").withTooltip(i + " chars"));
            settle();
        }
        assertSame("the slot was rebuilt", slot, itemsIn(StatusBarView.LEFT_CLASS).get(0));
        assertEquals("a tooltip was attached per update",
                1, slot.getElementsByClassName(Tooltip.LABEL_CLASS).size());
    }
}
