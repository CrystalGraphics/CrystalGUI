package com.crystalgui.widget.texteditor.doc;

import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.text.lang.LanguageServices;
import com.crystalgui.text.lang.Resolver;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.TypeRef;
import com.crystalgui.text.lang.Versioned;
import com.crystalgui.widget.texteditor.TextEditor;
import org.junit.Before;
import org.junit.Ignore;
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
public class HoverDocumentationTest extends UiDocumentTestBase {

    /** Answers immediately, so these tests are about the timer rather than about the resolve. */
    private static final class Immediate implements Resolver {
        private int asks;
        private int lastOffset = -1;

        /** Set to model an unresolved name — the shape a "did you mean" fix is offered for. */
        private boolean answerNothing;

        @Override
        public void resolveAt(int offset, Consumer<Versioned<SymbolInfo>> answer) {
            asks++;
            lastOffset = offset;
            answer.accept(answerNothing ? Versioned.of(version, null)
                    : Versioned.of(version, new SymbolInfo("beta", SymbolKind.FIELD,
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
        UINode root = new UINode().layout(l -> l.width(400).height(200));
        root.append(editor);
        document.append(root);
        document.styleEngine().addStylesheet(com.crystalgui.style.sheet.StyleSheet.DEFAULT);
        document.boxes().setUiScale(1f);
        settle();
    }

    private void settle() {
        for (int i = 0; i < 4; i++) frame();
    }

    /** Puts the pointer over an offset and lets the rest timer run past the delay. */
    private void hoverOver(int offset, float seconds) {
        // LAYOUT FIRST, then the hover -- never the other way round. `settle()` runs whole frames, and
        // a frame ends by diffing the pointer against the layout that just ran: the real pointer is
        // nowhere near the text, so the editor is told the hover LEFT and the dwell it had been
        // accumulating is reset. Ticking and then settling therefore threw away exactly what the tick
        // had just built, and a two-part dwell over one word never reached the delay.
        editor.hoverPointerForTest(offset);
        editor.tickFrame(seconds);
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

    /**
     * <b>A name that resolves to nothing still shows its problem.</b>
     *
     * <p>The case this was broken for, and the one it matters most in: the popup used to be opened from
     * inside the resolve callback, which fires only when a symbol comes back — so hovering an unresolved
     * {@code lenght()} gave a red squiggle, a lightbulb, a working Alt+Enter and no popup at all, because
     * a resolve that never succeeded was gating a band that has nothing to do with it. Diagnostics are
     * tracked ranges and are known without asking anyone.</p>
     */
    @Test
    public void aNameThatResolvesToNothingStillShowsItsProblem() {
        resolver.answerNothing = true;
        editor.diagnostics().setAll(List.of(com.crystalgui.text.diagnostic.Diagnostic.error(
                new com.crystalgui.text.TextPoint(0, 6), new com.crystalgui.text.TextPoint(0, 10),
                "cannot resolve method 'beta'")));
        settle();

        hoverOver(6, PAST_THE_DELAY);
        assertTrue("an unresolved name with a problem must still open the box", popupOpen());
        assertNull("there is no symbol to describe", editor.documentationPopup().shownSymbol());
    }

    /**
     * <b>A problem on punctuation is hoverable, even though there is no word there.</b>
     *
     * <p>The trigger required a word under the pointer, which is right for documentation and silently
     * excluded the case the popup is most useful for: a problem lands wherever the compiler puts it, and
     * that is regularly a {@code ;}, a brace or an operator. A redundant semicolon had a squiggle, a
     * Problems row and a lightbulb, and hovering it did nothing at all.</p>
     */
    @Test
    public void aProblemOnPunctuationIsHoverableEvenWithNoWordThere() {
        editor.setText("int x = 1;;\ngamma\n");
        settle();
        resolver.answerNothing = true;
        int semicolon = editor.getText().indexOf(";;") + 1;
        editor.diagnostics().setAll(List.of(com.crystalgui.text.diagnostic.Diagnostic.warning(
                new com.crystalgui.text.TextPoint(0, semicolon),
                new com.crystalgui.text.TextPoint(0, semicolon + 1), "Unnecessary semicolon")));
        settle();

        hoverOver(semicolon, PAST_THE_DELAY);
        assertTrue("a problem is worth a popup wherever it lands", popupOpen());
    }

    /** Punctuation with nothing wrong is still not hoverable — the word rule survives where it belongs. */
    @Test
    public void punctuationWithNoProblemIsStillNotHoverable() {
        editor.setText("int x = 1;;\ngamma\n");
        settle();
        hoverOver(editor.getText().indexOf(";;") + 1, PAST_THE_DELAY);
        assertFalse("only a problem earns a popup off a word", popupOpen());
    }

    /** With nothing resolved and nothing wrong, there is nothing to say — and no empty box. */
    @Test
    public void aNameThatResolvesToNothingAndHasNoProblemShowsNothing() {
        resolver.answerNothing = true;
        hoverOver(6, PAST_THE_DELAY);
        assertFalse("an empty popup is worse than none", popupOpen());
    }

    /** Nothing happens before the delay has elapsed — otherwise crossing a line strobes popups. */
    @Test
    public void nothingOpensBeforeTheDelay() {
        hoverOver(6, 0.1f);
        assertFalse("a tenth of a second is not a hover", popupOpen());
        editor.tickFrame(PAST_THE_DELAY);
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
        assertTrue("hiding this fast makes the popup impossible to reach", popupOpen());

        editor.tickFrame(0.3f);
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
