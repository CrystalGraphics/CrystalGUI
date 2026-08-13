package com.crystalgui.ui;

import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.text.lang.LanguageServices;
import com.crystalgui.text.lang.Resolver;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.TypeRef;
import com.crystalgui.text.lang.Versioned;
import com.crystalgui.ui.elements.editor.TextEditor;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.Assert.*;

/**
 * M11 §24.1 — the hover trigger, which is how Quick Documentation is actually used.
 *
 * <h3>What is worth pinning, and it is all timing</h3>
 *
 * <p>The popup's contents are covered by {@code DocumentationPopupTest}. What breaks silently here is
 * <em>when</em> it appears and disappears, and every one of these rules is invisible until it is wrong in
 * a way that reads as the feature being flaky rather than as a rule being missing:</p>
 *
 * <ul>
 *   <li>the delay must not restart while the pointer drifts across the <em>same</em> word, or the box
 *       appears only if you hold perfectly still;</li>
 *   <li>the pointer being past the end of a short line must not resolve that line's last token, because
 *       {@code offsetAtLocal} clamps and would happily report one;</li>
 *   <li>leaving the word must not hide instantly, or the box cannot be reached with the pointer;</li>
 *   <li>and a {@code Ctrl+Q} popup must survive all of it, because it was asked for.</li>
 * </ul>
 *
 * <p>Time is driven by calling {@code tickFrame} directly rather than by sleeping — the rest timer takes a
 * delta, so a test can hand it one.</p>
 */
public class HoverDocumentationTest extends UiTestBase {

    /** Answers immediately, so these tests are about the timer rather than about the resolve. */
    private static final class Immediate implements Resolver {
        private int asks;
        private int lastOffset = -1;

        @Override
        public void resolveAt(int offset, Consumer<Versioned<SymbolInfo>> answer) {
            asks++;
            lastOffset = offset;
            answer.accept(Versioned.of(version, new SymbolInfo("beta", SymbolKind.FIELD,
                    TypeRef.of("int"), "com.example.Host", null, Set.of(), null)));
        }

        @Override
        public void expectedTypeAt(int offset, Consumer<Versioned<TypeRef>> answer) {
            answer.accept(Versioned.none(0));
        }

        @Override
        public void membersOf(TypeRef type, int at, Consumer<Versioned<List<SymbolInfo>>> answer) {
            answer.accept(Versioned.none(0));
        }

        private long version;
    }

    private static final float PAST_THE_DELAY = 0.6f;

    private UIWindow window;
    private TextEditor editor;
    private Immediate resolver;

    @Before
    public void openAnEditor() {
        resolver = new Immediate();
        editor = new TextEditor("alpha beta\ngamma\n");
        editor.setLanguageServices(new LanguageServices() {
            @Override public String id() { return "test"; }
            @Override public Resolver resolver() { return resolver; }
        });
        resolver.version = editor.buffer().version();

        editor.layout(l -> l.width(400).height(200));
        UIElement root = new UIElement().layout(l -> l.width(400).height(200));
        root.addChild(editor);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(com.crystalgui.style.sheet.StyleSheet.DEFAULT);
        window.init(400, 200);
        window.setUiScale(1f);
        settle();
    }

    private void settle() {
        for (int i = 0; i < 4; i++) window.updateWithoutPainting();
    }

    /** Puts the pointer over an offset and lets the rest timer run past the delay. */
    private void hoverOver(int offset, float seconds) {
        editor.hoverPointerForTest(offset);
        editor.tickFrame(seconds);
        settle();
    }

    private boolean popupOpen() {
        return editor.documentationPopup() != null && editor.documentationPopup().isOpen();
    }

    /** Resting on a token opens the popup — no keystroke involved. */
    @Test
    public void restingOnATokenOpensTheDocumentation() {
        assertFalse(popupOpen());
        hoverOver(6, PAST_THE_DELAY);
        assertTrue("resting on a word should open the box", popupOpen());
        assertEquals("beta", editor.documentationPopup().shownSymbol().name());
    }

    /** Nothing happens before the delay has elapsed — otherwise crossing a line strobes popups. */
    @Test
    public void nothingOpensBeforeTheDelay() {
        hoverOver(6, 0.1f);
        assertFalse("a tenth of a second is not a hover", popupOpen());
        editor.tickFrame(PAST_THE_DELAY);
        settle();
        assertTrue(popupOpen());
    }

    /**
     * Drifting within the same word must not restart the timer.
     *
     * <p>Two moves onto different offsets of {@code beta}, each followed by less than the delay but more
     * than it in total. Restarting on every move would mean the popup only ever appears if the pointer is
     * perfectly still, which reads as the feature working intermittently.</p>
     */
    @Test
    public void driftingWithinOneWordDoesNotRestartTheDelay() {
        hoverOver(6, 0.25f);
        assertFalse(popupOpen());
        hoverOver(8, 0.25f);
        assertTrue("the same word should have kept accumulating", popupOpen());
    }

    /**
     * <b>Crossing another word on the way to the popup must not close it.</b>
     *
     * <p>The box opens <em>below</em> the token, so every route to it with the pointer crosses the next
     * line of code — and that line has words on it. Dismissing on "the pointer is over a different word
     * now" is the obvious rule and it makes the popup unreachable: it closed about a third of the way
     * down its own top border, which is exactly where the pointer stops being over the token's line.</p>
     */
    @Test
    public void crossingAnotherWordOnTheWayToThePopupDoesNotDismissIt() {
        hoverOver(6, PAST_THE_DELAY);
        assertTrue(popupOpen());

        // A few frames over a different word — a traversal, not a rest.
        editor.hoverPointerForTest(0);
        editor.tickFrame(0.05f);
        settle();

        assertTrue("the box must survive being crossed on the way to it", popupOpen());
    }

    /** <b>Resting</b> on another word does replace it — when the new lookup fires, not when it moved. */
    @Test
    public void restingOnAnotherWordReplacesTheBox() {
        hoverOver(6, PAST_THE_DELAY);
        assertTrue(popupOpen());

        hoverOver(0, PAST_THE_DELAY);

        assertTrue(popupOpen());
        assertEquals("alpha's offset should have been asked about", 0, resolver.lastOffset);
    }

    /**
     * Leaving the text does not hide immediately — the grace is what makes the box reachable, since it
     * sits below the token and moving towards it leaves the token at once.
     */
    @Test
    public void leavingTheWordHidesOnlyAfterTheGrace() {
        hoverOver(6, PAST_THE_DELAY);
        assertTrue(popupOpen());

        editor.hoverPointerForTest(-1);
        editor.tickFrame(0.1f);
        settle();
        assertTrue("hiding this fast makes the popup impossible to reach", popupOpen());

        editor.tickFrame(0.3f);
        settle();
        assertFalse(popupOpen());
    }

    /** Typing dismisses it: the box describes an offset and an edit moves it. */
    @Test
    public void anEditDismissesTheHoverBox() {
        hoverOver(6, PAST_THE_DELAY);
        assertTrue(popupOpen());

        editor.buffer().insert(0, "x");
        settle();
        assertFalse(popupOpen());
    }

    /**
     * A popup opened deliberately with {@code Ctrl+Q} is not dismissed by the pointer wandering — it was
     * never anchored to a hovered word, and only {@code hoverShownFor} tells the two apart.
     */
    @Test
    public void aDeliberatelyOpenedPopupSurvivesThePointerLeaving() {
        editor.setCaret(6);
        assertTrue(editor.showQuickDocumentation());
        settle();
        assertTrue(popupOpen());

        editor.hoverPointerForTest(-1);
        editor.tickFrame(2f);
        settle();
        assertTrue("Ctrl+Q is a request, not a hover", popupOpen());
    }

    /**
     * The pointer far to the right of a short line is <b>not</b> hovering that line's last token.
     *
     * <p>{@code offsetAtLocal} clamps to the nearest position by design — that is what makes clicking in
     * the blank area right of a line put the caret at its end — so without a horizontal bound this reports
     * {@code beta} for a pointer sitting in empty space, and the popup looks stuck rather than mis-aimed.</p>
     */
    @Test
    public void thePointerPastTheEndOfALineIsOverNothing() {
        // Well inside the first line vertically, and far beyond any text on it horizontally.
        assertEquals(-1, editor.hoverWordStartAtForTest(380f, 4f));
    }

    /** Turning it off means the pointer does nothing — IntelliJ's {@code Show on Mouse Move}. */
    @Test
    public void hoverCanBeTurnedOff() {
        editor.setHoverDocumentationEnabled(false);
        hoverOver(6, 2f);
        assertFalse(popupOpen());
        assertEquals("nothing should even have been asked", 0, resolver.asks);
    }
}
