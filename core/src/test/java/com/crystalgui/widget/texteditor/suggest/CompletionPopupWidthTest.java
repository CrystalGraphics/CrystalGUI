package com.crystalgui.widget.texteditor.suggest;

import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.lang.CompletionItem;
import com.crystalgui.text.lang.CompletionList;
import com.crystalgui.text.lang.CompletionProvider;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.Versioned;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.widget.texteditor.suggest.CompletionPopup;
import com.crystalgui.widget.texteditor.suggest.CompletionSession;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The type column at a completion row's right edge keeps its width when the list is tight.
 *
 * <p>Reported from the harness as "why are the return types spilling out of view": a list of constants
 * showed <em>String</em> as {@code Str}, <em>double</em> as {@code do} and <em>Object</em> as {@code Ob}.</p>
 *
 * <h3>It was not spilling out. It was being crushed inside.</h3>
 *
 * <p>Worth stating because the two look identical on screen and lead to opposite fixes. The column was
 * {@code flex-shrink: 1}, deliberately — the reasoning being that a truncated return type is merely less
 * informative while a truncated identifier is unreadable — and the label beside it could not shrink at
 * all. So when a row was tight the type absorbed the entire squeeze on its own: measured here at
 * <b>0.0px and 4.4px</b> against a natural 26–31px, which reads as text running off the edge.</p>
 *
 * <h3>Why the assertion is "the same type is the same width everywhere"</h3>
 *
 * <p>Shrink is distributed by content, so a squeezed column is a <em>different</em> width on every row —
 * {@code String} came out 26.5px on one and 0.0px on another in the same list. A rigid column cannot do
 * that. The invariant is font-independent, which a pixel assertion would not be, and it fails against the
 * old rule for the right reason rather than by coincidence.</p>
 */
public class CompletionPopupWidthTest extends UiDocumentTestBase {

    /** Offers a fixed list, immediately — enough to open a session and fill the list. */
    private static CompletionProvider offering(List<CompletionItem> items) {
        return new CompletionProvider() {
            @Override
            public void complete(Request request, Consumer<Versioned<CompletionList>> answer) {
                answer.accept(Versioned.of(0, CompletionList.complete(items)));
            }

            @Override
            public void resolveItem(CompletionItem item, Consumer<CompletionItem> answer) {
                answer.accept(item);
            }
        };
    }

    private static CompletionItem constant(String name, String type) {
        return CompletionItem.builder(name, SymbolKind.FIELD).detail(type).build();
    }

    @Test
    public void theTypeColumnIsNotSqueezedAwayWhenTheRowIsTight() {
        // DELIBERATELY NARROW. The bug needs rows that do not fit; in a roomy popup the spacer takes up
        // the slack and nothing is ever asked to give, which is why a first attempt at this test passed
        // against the broken rule.
        UINode root = new UINode().layout(l -> l.width(320).height(600));
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);

        List<CompletionItem> items = new ArrayList<>();
        items.add(constant("ACCENTED", "String"));
        items.add(constant("NEGATIVE_EXPONENT", "double"));
        items.add(constant("NOTHING", "Object"));
        items.add(constant("MAX_RETRIES", "int"));
        // Names of very different lengths in one list, which is what makes the squeeze uneven and so
        // makes "the same type, two widths" observable at all.
        items.add(constant("A_CONSTANT_WITH_A_DELIBERATELY_LONG_NAME_THAT_DOES_NOT_FIT", "String"));

        TextBuffer buffer = new TextBuffer("");
        CompletionSession session = CompletionSession.open(
                buffer, offering(items), 0, CompletionProvider.TriggerKind.EXPLICIT, null);
        assertTrue("the stub provider must have produced a session", session != null);

        // NOT parented by hand first: `attach` promotes it with `addOverlay` only when it has no parent,
        // and an in-flow popup is sized by the root's flex column rather than by its own width write.
        CompletionPopup popup = new CompletionPopup();
        popup.attach(document, session);
        for (int i = 0; i < 6; i++) frame();

        Map<String, Float> widthByType = new HashMap<>();
        int measured = 0;
        for (UINode row : rowsIn(popup)) {
            UIText detail = detailOf(row);
            if (detail == null || detail.getText().isEmpty()) continue;
            measured++;
            float width = detail.box().width();
            assertTrue("the type '" + detail.getText() + "' was squeezed to " + width, width > 0f);
            Float seen = widthByType.putIfAbsent(detail.getText(), width);
            if (seen != null) {
                assertEquals("'" + detail.getText() + "' must be the same width in every row",
                        seen, width, 0.5f);
            }
        }
        assertTrue("no rows carried a type, so nothing was measured", measured >= 4);
    }

    /**
     * <b>Nothing in a row is ever painted outside the popup.</b>
     *
     * <h3>"Never gives" is not the same claim as "gives last"</h3>
     *
     * <p>The type column was made {@code flex-shrink: 0} so it could not be shaved to a stub, and the
     * test above is what pins that. The cost only shows once a row cannot fit at all: with the label
     * already shrunk to nothing there was nothing left to give, so the row exceeded the popup and the
     * type was drawn OUTSIDE it, over the editor behind — past the scrollbar, on rows whose own detail
     * was short enough to have fit anywhere.</p>
     *
     * <p>Asserted as a geometric containment rather than as a width, because the failure is not that the
     * column is the wrong size: it is that it is in the wrong place. A width assertion passes against a
     * row that is correctly proportioned and sitting half a popup to the right.</p>
     *
     * <p>The long details are the point of the fixture — a list of short ones fits whatever the shrink
     * factors say, and passes against no fix at all.</p>
     */
    @Test
    public void nothingInARowIsPaintedOutsideThePopup() {
        UINode root = new UINode().layout(l -> l.width(320).height(600));
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);

        List<CompletionItem> items = new ArrayList<>();
        items.add(constant("META-INF", "META-INF"));
        items.add(constant("com", "com"));
        items.add(constant("getPropertyWithAVeryLongName", "java.util.concurrent.ConcurrentHashMap"));
        items.add(constant("x", "java.util.concurrent.atomic.AtomicIntegerFieldUpdater"));

        TextBuffer buffer = new TextBuffer("");
        CompletionSession session = CompletionSession.open(
                buffer, offering(items), 0, CompletionProvider.TriggerKind.EXPLICIT, null);
        assertTrue("the stub provider must have produced a session", session != null);

        CompletionPopup popup = new CompletionPopup();
        popup.attach(document, session);
        for (int i = 0; i < 8; i++) frame();

        float popupRight = popup.box().x() + popup.box().width();
        int checked = 0;
        for (UINode row : rowsIn(popup)) {
            UIText detail = detailOf(row);
            if (detail == null || detail.getText().isEmpty()) continue;
            checked++;
            float right = detail.box().x() + detail.box().width();
            assertTrue("'" + detail.getText() + "' is painted outside the popup: ends at "
                    + right + ", popup ends at " + popupRight, right <= popupRight + 0.5f);
        }
        assertTrue("no rows carried a type, so nothing was checked", checked >= 3);
    }

    /**
     * <b>Every realised row is the same width.</b>
     *
     * <p>A list is a column of identical boxes, so this ought to be free — and it was not. With enough
     * items to scroll, the rows realised first came out narrower than the ones realised after, and the
     * wider ones ran their right-aligned type column under the scrollbar and off the edge of the popup.
     * The top of the list looked perfect and the bottom looked clipped, which reads as a clipping bug
     * rather than as a sizing one.</p>
     *
     * <p>Enough items to force a scrollbar is the whole fixture: a list that fits has one batch of rows
     * and cannot show the difference, which is why every earlier test here passed.</p>
     */
    @Test
    public void everyRealisedRowIsTheSameWidth() {
        UINode root = new UINode().layout(l -> l.width(420).height(600));
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);

        List<CompletionItem> items = new ArrayList<>();
        // Long enough to scroll, and with types of very different lengths so a mis-sized row shows.
        items.add(constant("err", "PrintStream"));
        items.add(constant("in", "InputStream"));
        items.add(constant("out", "PrintStream"));
        for (int at = 0; at < 30; at++) {
            items.add(constant("member" + at, at % 2 == 0 ? "String" : "Map<String,String>"));
        }

        TextBuffer buffer = new TextBuffer("");
        CompletionSession session = CompletionSession.open(
                buffer, offering(items), 0, CompletionProvider.TriggerKind.EXPLICIT, null);
        assertTrue("the stub provider must have produced a session", session != null);

        CompletionPopup popup = new CompletionPopup();
        popup.attach(document, session);
        for (int i = 0; i < 10; i++) frame();

        float first = -1f;
        int checked = 0;
        for (UINode row : rowsIn(popup)) {
            float width = row.box().width();
            if (width <= 0f) continue;          // pooled and hidden templates measure nothing
            checked++;
            if (first < 0f) first = width;
            assertEquals("realised rows came out different widths, so the wider ones run past the popup",
                    first, width, 0.5f);

            // AND THE TYPE HAS A BOX AT ALL. This is the assertion the row-width one cannot make: a
            // collapsed detail is the same width on every row, so "all rows agree" is satisfied by every
            // one of them being ZERO. A zero-width UIText still paints -- from the right edge its
            // collapsed box sits at -- and the row's `overflow: hidden` cuts it a dozen pixels later, so
            // it reads as clipping rather than as a box that was never sized.
            UIText detail = detailOf(row);
            if (detail == null || detail.getText().isEmpty()) continue;
            assertTrue("'" + detail.getText() + "' has no width, so only the first few pixels can paint",
                    detail.box().width() > 0f);
        }
        assertTrue("no rows were realised, so nothing was checked", checked >= 5);
    }

    /** The type column — the last child of a row, after the growing spacer. */
    private static UIText detailOf(UINode row) {
        for (UINode child : row.children()) {
            if (child.hasClass(CompletionPopup.DETAIL_CLASS) && child instanceof UIText text) return text;
        }
        return null;
    }

    /**
     * The rows the user can see — <b>not</b> the width probe.
     *
     * <p>The probe carries {@code ROW_CLASS} too: it is a real row, bound to the widest item, laid out and
     * measured and then never painted. It is a direct child of the popup while the visible rows live
     * inside the list, which is the only thing telling them apart from outside. Collecting it sent a first
     * draft of this test chasing a phantom — every assertion was about the measuring stick, which is
     * <em>supposed</em> to be wider than the box, so it failed against perfectly correct geometry.</p>
     */
    private static List<UINode> rowsIn(UINode popup) {
        List<UINode> found = new ArrayList<>();
        collectRows(popup, popup, found);
        return found;
    }

    private static void collectRows(UINode element, UINode popup, List<UINode> out) {
        if (element.hasClass(CompletionPopup.ROW_CLASS) && element.parent() != popup) out.add(element);
        for (UINode child : element.children()) collectRows(child, popup, out);
    }
}
