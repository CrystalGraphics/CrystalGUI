package com.crystalgui.ui;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.lang.CompletionItem;
import com.crystalgui.text.lang.CompletionList;
import com.crystalgui.text.lang.CompletionProvider;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.Versioned;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.editor.CompletionPopup;
import com.crystalgui.ui.elements.editor.CompletionSession;
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
public class CompletionPopupWidthTest extends UiTestBase {

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
        UIElement root = new UIElement().layout(l -> l.width(320).height(600));
        UIWindow window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(320, 600);

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
        popup.attach(window, session);
        for (int i = 0; i < 6; i++) window.updateWithoutPainting();

        Map<String, Float> widthByType = new HashMap<>();
        int measured = 0;
        for (UIElement row : rowsIn(popup)) {
            UIText detail = detailOf(row);
            if (detail == null || detail.getText().isEmpty()) continue;
            measured++;
            float width = detail.getRuntimeCache().getWidth();
            assertTrue("the type '" + detail.getText() + "' was squeezed to " + width, width > 0f);
            Float seen = widthByType.putIfAbsent(detail.getText(), width);
            if (seen != null) {
                assertEquals("'" + detail.getText() + "' must be the same width in every row",
                        seen, width, 0.5f);
            }
        }
        assertTrue("no rows carried a type, so nothing was measured", measured >= 4);
    }

    /** The type column — the last child of a row, after the growing spacer. */
    private static UIText detailOf(UIElement row) {
        for (UIElement child : row.getChildren()) {
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
    private static List<UIElement> rowsIn(UIElement popup) {
        List<UIElement> found = new ArrayList<>();
        collectRows(popup, popup, found);
        return found;
    }

    private static void collectRows(UIElement element, UIElement popup, List<UIElement> out) {
        if (element.hasClass(CompletionPopup.ROW_CLASS) && element.getParent() != popup) out.add(element);
        for (UIElement child : element.getChildren()) collectRows(child, popup, out);
    }
}
