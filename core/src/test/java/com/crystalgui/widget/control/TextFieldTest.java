package com.crystalgui.widget.control;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgraphics.platform.service.CgInputService;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.testsupport.TestPlatformService;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgui.core.property.Property;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * {@link TextField} — editing, caret arithmetic and the validation layers.
 *
 * <p>Keyboard goes through {@code consumeKeyboardEvent}, the same entry point real input uses, so a
 * key that never reaches the field fails here rather than looking fine in isolation. That only
 * became true of <em>characters</em> late: the original tests typed via {@code insertChar} and sent
 * {@code '\0'} for every key, which covered the model but not the wiring, and a field that could not
 * be typed into at all passed all of them. Use {@link #type} for anything about typing.</p>
 *
 * <p>Caret geometry (pixel positions) is left to the harness scene: measuring text needs the native
 * FreeType bindings, which aren't on the headless test classpath. Everything here is index
 * arithmetic, which is where the surrogate-pair and validation bugs actually live.</p>
 */
public class TextFieldTest extends UiDocumentTestBase {


    private TextField field;
    private int modifiers = 0;
    private String clipboardContents = "";

    @Before
    public void setUp() {
        modifiers = 0;
        clipboardContents = "";
        // Clipboard and modifiers live on the same service, so one stub covers both.
        TestPlatformService.install().input(new CgInputService() {
            @Override public int getCurrentModifiers() { return modifiers; }
            @Override public int translateKeyboardCodes(int platformCode) { return platformCode; }
            @Override public boolean isKeyDown(int localKeyCode) { return false; }
            @Override public int translateMouseCodes(int platformCode) { return platformCode; }
            @Override public boolean isMouseDown(int localMouseCode) { return false; }
            @Override public int howManyMouseButtons() { return 3; }
            @Override public String getClipboard() { return clipboardContents; }
            @Override public void setClipboard(String text) { clipboardContents = text; }
        });

        field = new TextField();
        // Sized so the wheel tests can actually hit-test it. Everything else here is index
        // arithmetic and doesn't care.
        field.layout(l -> l.width(200).height(20));
        UIElement root = new UIElement().layout(l -> l.width(300).height(100));
        root.append(field);
        document.append(root);
        // A frame must have completed before any key is accepted: consumeKeyboardEvent bails while
        // `firstFrameOver` is false, and that only flips in endFrame().
        frame();
        document.focus().requestFocus(field);
    }



    /**
     * Advances animation time. Deliberately separate from {@link #frame()}, which does NOT tick —
     * only {@code paintFrame()} drives {@code tickAnimations}, and these tests never paint.
     */
    private void tick(float seconds) {
        frame(seconds);
    }

    /**
     * Wheel notches over the field, through the real input path. <b>Positive = scrolled UP</b>, i.e.
     * pushed away from you — stated in the reader's terms, not the platform's.
     *
     * <p>The platform's sign is the opposite: {@code NORMALIZE_TOP_LEFT_ORIGIN = -1} means a wheel
     * push away arrives NEGATIVE, because for scrolling "positive" means further down the document.
     * The negation lives here so these tests can't be written against whichever sign the
     * implementation happens to use — which is exactly how the first version of them encoded a
     * backwards spinner and passed.</p>
     *
     * <p>Two frames are needed: the wheel is dispatched to whatever is <em>hovered</em>, and hover is
     * resolved in {@code endFrame()}, so the pointer has to arrive first. Raw input is in physical
     * pixels and layout is logical, hence the {@code * 2f} for the default uiScale.</p>
     */
    private void scrollUp(int notches) {
        var box = field.box();
        int x = Math.round((box.x() + 4f) * 2f);
        int y = Math.round((box.y() + 4f) * 2f);
        document.input().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(x, y, 0, 0, -1, false, 0f, -1L));
        frame();
        document.input().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(x, y, 0, 0, -1, false, -notches, -1L));
        frame();
    }

    /** Somewhere else to move focus to, so blur actually fires. */
    private Button focusSink() {
        Button button = new Button("ok");
        field.parent().append(button);
        frame();
        return button;
    }

    private void key(int keyCode) {
        document.input().consumeKeyboardEvent(
                new CgSystemInput.Keyboard.Event('\0', keyCode, true, false, 0L));
        document.input().consumeKeyboardEvent(
                new CgSystemInput.Keyboard.Event('\0', keyCode, false, false, 0L));
    }

    /**
     * Types a character through the real input path.
     *
     * <p>This exists because its absence hid a total failure: every editing test called
     * {@code field.insertChar()} directly and {@link #key} always sent {@code '\0'}, so the model was
     * covered while the event wiring that reaches it was not — and the field was in fact completely
     * unwritable in the running scene. Anything about typing belongs here, not on insertChar.</p>
     */
    private void type(char c) {
        document.input().consumeKeyboardEvent(
                new CgSystemInput.Keyboard.Event(c, CgKeyCodes.KEY_NONE, true, false, 0L));
        document.input().consumeKeyboardEvent(
                new CgSystemInput.Keyboard.Event(c, CgKeyCodes.KEY_NONE, false, false, 0L));
    }

    private void type(String s) {
        for (int i = 0; i < s.length(); i++) type(s.charAt(i));
    }

    private void ctrl(int keyCode) {
        modifiers = CgModifiers.CTRL;
        key(keyCode);
        modifiers = 0;
    }

    // ── The Space-bridge fix, both directions ───────────────────────────────

    /**
     * The prerequisite. Space on a focused element is normally turned into a synthesized mouse press
     * so buttons get keyboard activation for free — a text field has to opt out, or every space
     * typed would fire its press handlers at the physical cursor position.
     */
    @Test
    public void spaceDoesNotSynthesiseAClickOnATextField() {
        int[] presses = {0};
        field.onMouseDown.attachListener((el, e) -> presses[0]++, false, false);

        key(CgKeyCodes.KEY_SPACE);

        assertEquals("space fired a synthetic click on the text field", 0, presses[0]);
        assertTrue(field.consumesTextInput());
    }

    /** The regression guard — the half that would silently break. A Button must still activate. */
    @Test
    public void spaceStillActivatesAButton() {
        Button button = new Button("ok");
        field.parent().append(button);
        frame();
        document.focus().requestFocus(button);

        int[] presses = {0};
        button.onMouseDown.attachListener((el, e) -> presses[0]++, false, false);

        key(CgKeyCodes.KEY_SPACE);

        assertEquals("button lost its keyboard activation", 1, presses[0]);
        assertFalse(button.consumesTextInput());
    }

    // ── Editing ─────────────────────────────────────────────────────────────

    @Test
    public void typingInsertsAtTheCaret() {
        field.insert("hello");
        assertEquals("hello", field.getText());
        assertEquals(5, field.getCaret());

        field.setText("");
        field.insert("ab");
        key(CgKeyCodes.KEY_LEFT);
        field.insertChar('X');
        assertEquals("aXb", field.getText());
    }

    @Test
    public void backspaceAndDeleteRemoveOneCharacter() {
        field.insert("abc");
        key(CgKeyCodes.KEY_BACK);
        assertEquals("ab", field.getText());

        key(CgKeyCodes.KEY_HOME);
        key(CgKeyCodes.KEY_DELETE);
        assertEquals("b", field.getText());
    }

    /** Deleting at either edge must be a no-op, not an exception. */
    @Test
    public void deletingAtTheEdgesIsSafe() {
        key(CgKeyCodes.KEY_BACK);
        key(CgKeyCodes.KEY_DELETE);
        assertEquals("", field.getText());

        field.insert("a");
        key(CgKeyCodes.KEY_END);
        key(CgKeyCodes.KEY_DELETE);
        assertEquals("a", field.getText());
    }

    @Test
    public void homeAndEndJumpToTheEnds() {
        field.insert("hello");
        key(CgKeyCodes.KEY_HOME);
        assertEquals(0, field.getCaret());
        key(CgKeyCodes.KEY_END);
        assertEquals(5, field.getCaret());
    }

    // ── Selection ───────────────────────────────────────────────────────────

    @Test
    public void shiftArrowExtendsTheSelection() {
        field.insert("hello");
        modifiers = CgModifiers.SHIFT;
        key(CgKeyCodes.KEY_LEFT);
        key(CgKeyCodes.KEY_LEFT);
        modifiers = 0;

        assertTrue(field.hasSelection());
        assertEquals("lo", field.getSelectedText());
    }

    @Test
    public void typingReplacesTheSelection() {
        field.insert("hello");
        ctrl(CgKeyCodes.KEY_A);
        type('X');
        assertEquals("X", field.getText());
    }

    @Test
    public void selectAllCoversTheWholeValue() {
        field.insert("hello");
        ctrl(CgKeyCodes.KEY_A);
        assertEquals("hello", field.getSelectedText());
    }

    // ── Clipboard ───────────────────────────────────────────────────────────

    @Test
    public void copyCutAndPasteGoThroughTheClipboardSpi() {
        field.insert("hello");
        ctrl(CgKeyCodes.KEY_A);
        ctrl(CgKeyCodes.KEY_C);
        assertEquals("hello", clipboardContents);

        ctrl(CgKeyCodes.KEY_A);
        ctrl(CgKeyCodes.KEY_X);
        assertEquals("", field.getText());
        assertEquals("hello", clipboardContents);

        ctrl(CgKeyCodes.KEY_V);
        assertEquals("hello", field.getText());
    }

    /** A filtered field must not be bypassable by pasting what it refuses to accept typed. */
    @Test
    public void pasteIsFilteredToo() {
        field.setCharFilter(Character::isDigit);
        clipboardContents = "a1b2c3";
        ctrl(CgKeyCodes.KEY_V);
        assertEquals("123", field.getText());
    }

    // ── Code-point stepping (surrogate pairs) ───────────────────────────────

    /**
     * An emoji is two {@code char}s. The caret must cross it in ONE press and never land between the
     * halves — stepping by {@code char} would leave it inside, producing a broken substring on the
     * very next edit. An ASCII-only test passes with this entirely absent.
     */
    @Test
    public void caretStepsOverAnAstralCharacterInOnePress() {
        String emoji = "😀";          // U+1F600, two chars
        field.setText("a" + emoji + "b");
        key(CgKeyCodes.KEY_HOME);

        key(CgKeyCodes.KEY_RIGHT);
        assertEquals(1, field.getCaret());
        key(CgKeyCodes.KEY_RIGHT);
        assertEquals("caret landed inside the surrogate pair", 3, field.getCaret());
        key(CgKeyCodes.KEY_RIGHT);
        assertEquals(4, field.getCaret());
    }

    /** ...and backspace removes the whole emoji, not half of it. */
    @Test
    public void backspaceRemovesAWholeAstralCharacter() {
        String emoji = "😀";
        field.setText("a" + emoji);
        key(CgKeyCodes.KEY_END);
        key(CgKeyCodes.KEY_BACK);

        assertEquals("backspace split a surrogate pair", "a", field.getText());
    }

    // ── Validation ──────────────────────────────────────────────────────────

    @Test
    public void charFilterRejectsKeystrokesOutright() {
        field.setCharFilter(Character::isDigit);
        field.insertChar('a');
        field.insertChar('4');
        assertEquals("4", field.getText());
    }

    @Test
    public void textValidatorMarksInvalidWithoutBlockingEditing() {
        field.setTextValidator(s -> s.length() <= 3);
        field.insert("abcd");

        assertEquals("editing must not be blocked", "abcd", field.getText());
        assertTrue("should be flagged invalid", field.isInvalid());
    }

    /** The key affordance: a partially-typed number stays editable rather than being wiped. */
    @Test
    public void partiallyTypedNumberStaysEditable() {
        field.setMode(TextField.Mode.INTEGER);
        field.insertChar('-');

        assertEquals("a lone '-' must survive on the way to '-5'", "-", field.getText());
        assertTrue(field.isInvalid());

        field.insertChar('5');
        assertEquals("-5", field.getText());
        assertFalse(field.isInvalid());

        // getNumber reads the PUBLISHED value, and the default ON_COMMIT mode hasn't published yet.
        field.commit();
        assertEquals(-5d, field.getNumber(0), 0.001);
    }

    @Test
    public void outOfRangeNumberIsInvalid() {
        field.setMode(TextField.Mode.INTEGER).setRange(0, 10);
        field.setText("50");
        assertTrue(field.isInvalid());

        field.setText("7");
        assertFalse(field.isInvalid());
        assertEquals(7d, field.getNumber(0), 0.001);
    }

    /** The published value only tracks content that validated — an invalid edit must not leak out. */
    @Test
    public void publishedValueOnlyFollowsValidContent() {
        field.setMode(TextField.Mode.INTEGER);
        field.setText("42");
        assertEquals("42", field.getValue());

        field.setText("4x");
        assertTrue(field.isInvalid());
        assertEquals("published value should still be the last good one", "42", field.getValue());
        assertEquals("setText must not rewrite what the caller asked for", "4x", field.getText());
    }

    @Test
    public void signalFiresOnlyOnCommittedChanges() {
        field.setMode(TextField.Mode.INTEGER);
        int[] fired = {0};
        field.attachListener(v -> fired[0]++);

        field.setText("1");
        assertEquals(1, fired[0]);
        field.setText("1x");        // invalid — no commit
        assertEquals(1, fired[0]);
        field.setText("2");
        assertEquals(2, fired[0]);
    }

    // ── Pseudo-class hooks ──────────────────────────────────────────────────

    @Test
    public void blankAndInvalidHooksTrackState() {
        assertTrue("an empty field should be :blank", field.isBlank());
        field.insert("x");
        assertFalse(field.isBlank());

        field.setMode(TextField.Mode.INTEGER);
        assertTrue("'x' is not an integer", field.isInvalid());
    }

    // ── Typing through the real input path ──────────────────────────────────
    //
    // These are the guard for a bug that shipped: the keyboard listener only ran handleKey, which
    // claims control keys and returns false for everything else, so no printable character was ever
    // inserted and the field was completely unwritable. Every test above uses insertChar/insert
    // directly, so none of them noticed. Anything about typing goes here.

    @Test
    public void typingACharacterInsertsIt() {
        type("hi");
        assertEquals("hi", field.getText());
        assertEquals("caret should follow what was typed", 2, field.getCaret());
    }

    @Test
    public void typingHonoursTheCharFilter() {
        field.setCharFilter(Character::isDigit);
        type("a1b2");
        assertEquals("non-digits must be rejected at the keystroke", "12", field.getText());
    }

    @Test
    public void typingASpaceInsertsASpaceRatherThanActivating() {
        type("a b");
        assertEquals("a b", field.getText());
    }

    @Test
    public void controlCharactersAreNotInserted() {
        // Enter and Tab arrive with a real character ('\r', '\t') that must not land in the text.
        document.input().consumeKeyboardEvent(
                new CgSystemInput.Keyboard.Event('\r', CgKeyCodes.KEY_RETURN, true, false, 0L));
        assertEquals("", field.getText());
    }

    @Test
    public void ctrlCombosDoNotTypeTheirLetter() {
        // Ctrl+S is unhandled, but it must not insert an 's'.
        modifiers = CgModifiers.CTRL;
        document.input().consumeKeyboardEvent(
                new CgSystemInput.Keyboard.Event('s', CgKeyCodes.KEY_S, true, false, 0L));
        modifiers = 0;
        assertEquals("", field.getText());
    }

    @Test
    public void aDisabledFieldCannotBeTypedInto() {
        field.setEnabled(false);
        type("nope");
        assertEquals("", field.getText());
    }

    // ── Caret blink ─────────────────────────────────────────────────────────

    @Test
    public void caretBlinksWhileFocused() {
        assertTrue("caret should start solid", field.isCaretVisible());
        tick(0.6f);
        assertFalse("caret should have blinked off", field.isCaretVisible());
        tick(0.6f);
        assertTrue("caret should have blinked back on", field.isCaretVisible());
    }

    /** Browsers hold the caret solid while you type — a caret vanishing mid-word is maddening. */
    @Test
    public void typingResetsTheBlinkToSolid() {
        tick(0.6f);
        assertFalse(field.isCaretVisible());
        type('a');
        assertTrue("typing must restart the blink solid", field.isCaretVisible());
    }

    @Test
    public void caretMovementAlsoResetsTheBlink() {
        field.insert("hello");
        tick(0.6f);
        assertFalse(field.isCaretVisible());
        key(CgKeyCodes.KEY_LEFT);
        assertTrue(field.isCaretVisible());
    }

    @Test
    public void blinkStopsOnBlurAndResumesOnRefocus() {
        document.focus().requestFocus(focusSink());
        tick(0.6f);
        tick(0.6f);
        assertTrue("an unfocused field must not keep blinking", field.isCaretVisible());

        document.focus().requestFocus(field);
        tick(0.6f);
        assertFalse("refocusing must re-register the ticker", field.isCaretVisible());
    }

    @Test
    public void zeroBlinkSecondsKeepsTheCaretSolid() {
        field.setCaretBlinkSeconds(0f);
        tick(0.6f);
        tick(0.6f);
        assertTrue(field.isCaretVisible());
    }

    // ── Selection across a blur ─────────────────────────────────────────────

    /**
     * Blurring must not destroy the selection — only stop drawing it.
     *
     * <p>The band used to paint whenever {@code hasSelection()} was true, with no focus check, so a
     * blurred field kept a live-looking highlight. The fix is in {@code paintOverlay}'s guard, NOT in
     * the blur handler: browsers keep the range so refocusing restores it, and reaching for
     * {@code clearSelection()} on blur would lose it instead. This pins the state half of that —
     * whether the band actually stops rendering is a paint concern and lives in the harness scene,
     * since measuring it needs a GL context.</p>
     */
    @Test
    public void blurKeepsTheSelectionRangeItJustStopsPaintingIt() {
        field.setText("hello");
        document.focus().requestFocus(field);
        field.selectAll();
        assertTrue(field.hasSelection());

        document.focus().requestFocus(focusSink());

        assertFalse("the field must actually be blurred", field.isFocused());
        assertTrue("the range survives the blur", field.hasSelection());
        assertEquals(0, field.getSelectionStart());
        assertEquals(5, field.getSelectionEnd());
    }

    // ── Caret position on focus ─────────────────────────────────────────────

    @Test
    public void focusPutsTheCaretAtTheEnd() {
        field.setText("hello");
        key(CgKeyCodes.KEY_HOME);
        assertEquals(0, field.getCaret());

        document.focus().requestFocus(focusSink());
        document.focus().requestFocus(field);

        assertEquals("focus should land the caret at the end", 5, field.getCaret());
    }

    // ── Update mode / commit ────────────────────────────────────────────────

    @Test
    public void commitFiresOnEnterNotPerKeystroke() {
        int[] fired = {0};
        field.attachListener(v -> fired[0]++);

        type("123");
        assertEquals("ON_COMMIT must not publish per keystroke", 0, fired[0]);
        assertEquals("", field.getValue());

        key(CgKeyCodes.KEY_RETURN);
        assertEquals(1, fired[0]);
        assertEquals("123", field.getValue());
    }

    @Test
    public void commitFiresOnBlur() {
        int[] fired = {0};
        field.attachListener(v -> fired[0]++);

        type("abc");
        document.focus().requestFocus(focusSink());

        assertEquals(1, fired[0]);
        assertEquals("abc", field.getValue());
    }

    @Test
    public void immediateModeFiresPerKeystroke() {
        field.setUpdateMode(TextField.UpdateMode.IMMEDIATE);
        int[] fired = {0};
        field.attachListener(v -> fired[0]++);

        type("123");
        assertEquals(3, fired[0]);
        assertEquals("123", field.getValue());
    }

    @Test
    public void enterAlsoFiresOnSubmitAndStillBubbles() {
        int[] submits = {0};
        int[] ancestorSaw = {0};
        field.onSubmit.connect(v -> submits[0]++);
        field.parentElement().events.getGroup(com.crystalgui.ui.event.KeyboardEvent.Down.class)
                .attachListener((el, e) -> ancestorSaw[0]++, false, true);

        type("hi");
        key(CgKeyCodes.KEY_RETURN);

        assertEquals(1, submits[0]);
        assertTrue("Enter must keep bubbling so a dialog can submit", ancestorSaw[0] > 0);
    }

    @Test
    public void escapeRevertsToTheCommittedValue() {
        field.setText("abc");
        int[] fired = {0};
        field.attachListener(v -> fired[0]++);

        type("XY");
        assertEquals("abcXY", field.getText());

        key(CgKeyCodes.KEY_ESCAPE);
        assertEquals("abc", field.getText());
        assertEquals("reverting must not publish anything", 0, fired[0]);
    }

    // ── Binding ─────────────────────────────────────────────────────────────

    @Test
    public void externalPropertyChangePushesIntoTheBox() {
        Property<String> model = new Property<>("hi");
        field.bindValueBidirectional(model);
        assertEquals("binding should adopt the model's value", "hi", field.getText());

        model.set("yo");
        assertEquals("an external set must reach the visible box", "yo", field.getText());

        type("!");
        key(CgKeyCodes.KEY_RETURN);
        assertEquals("committing must write back through the binding", "yo!", model.get());
    }

    // ── Mode-derived constraints ────────────────────────────────────────────

    @Test
    public void integerModeInstallsItsOwnCharFilter() {
        field.setMode(TextField.Mode.INTEGER);   // note: no setCharFilter call
        type("a1b2");
        assertEquals("the mode should filter keystrokes by itself", "12", field.getText());
    }

    @Test
    public void nonNegativeRangeRejectsMinusAtTheKeystroke() {
        field.setMode(TextField.Mode.INTEGER).setRange(0, 100);
        type("-5");
        assertEquals("'-' is unreachable when the range can't be negative", "5", field.getText());
    }

    /** Order-independence: setRange re-derives, so it must not clobber a caller's own filter. */
    @Test
    public void rangeChangeDoesNotWipeAUserCharFilter() {
        field.setCharFilter(c -> c != '7');
        field.setRange(0, 100);
        type("7");
        assertEquals("", field.getText());
    }

    @Test
    public void userCharFilterComposesWithTheModeFilter() {
        field.setMode(TextField.Mode.INTEGER).setCharFilter(c -> c != '7');
        type("a17");
        assertEquals("both filters must apply, not just the last one set", "1", field.getText());
    }

    @Test
    public void charPatternFiltersKeystrokes() {
        field.setCharPattern("[0-9]");
        type("a1b2");
        assertEquals("12", field.getText());
    }

    @Test
    public void patternMarksInvalidWithoutBlockingEditing() {
        field.setPattern("[a-z]{3}");
        type("ab");
        assertTrue(field.isInvalid());
        type("c");
        assertFalse(field.isInvalid());
        type("d");
        assertEquals("editing must not be blocked", "abcd", field.getText());
        assertTrue(field.isInvalid());
    }

    /** The whole point of the loose mode patterns — these are real states on the way to a number. */
    @Test
    public void aTrailingDecimalPointStaysTypable() {
        field.setMode(TextField.Mode.DOUBLE);
        type("1.");
        // Not asserted invalid: Double.parseDouble("1.") actually succeeds, so this is a complete
        // number as far as Java is concerned. What matters is that it survives the keystroke and the
        // format pattern rather than being eaten.
        assertEquals("1.", field.getText());

        type("5");
        assertEquals("1.5", field.getText());
        assertFalse(field.isInvalid());
    }

    /** A half-typed exponent — genuinely unparseable, and it still has to survive. */
    @Test
    public void aHalfTypedExponentStaysTypable() {
        field.setMode(TextField.Mode.DOUBLE);
        type("1e-");
        assertEquals("1e-", field.getText());
        assertTrue(field.isInvalid());

        type("5");
        assertEquals("1e-5", field.getText());
        assertFalse(field.isInvalid());
    }

    // ── Clamping ────────────────────────────────────────────────────────────

    @Test
    public void outOfRangeClampsOnCommitOnly() {
        field.setMode(TextField.Mode.INTEGER).setRange(0, 10);
        type("50");
        assertEquals("clamping mid-typing would fight the typist", "50", field.getText());
        assertTrue(field.isInvalid());

        key(CgKeyCodes.KEY_RETURN);
        assertEquals("10", field.getText());
        assertEquals("10", field.getValue());
        assertFalse(field.isInvalid());
    }

    /**
     * Note the content has to be something the KEYSTROKE filter allows through — with the mode's
     * auto-constraints in place a letter can't reach an INTEGER field at all, so {@code "7-"} (which
     * passes the char filter but fails the format pattern) is the reachable unparseable state.
     */
    @Test
    public void unparseableContentRevertsOnCommit() {
        field.setMode(TextField.Mode.INTEGER);
        field.setText("7");
        type("-");
        assertEquals("7-", field.getText());
        assertTrue(field.isInvalid());

        key(CgKeyCodes.KEY_RETURN);
        assertEquals("the box must never show something that isn't the value", "7", field.getText());
        assertEquals("7", field.getValue());
    }

    // ── Wheel stepping ──────────────────────────────────────────────────────

    /** Wheel UP raises the value — the spinner convention, which is the INVERSE of the scroll one. */
    @Test
    public void wheelUpRaisesTheValue() {
        field.setMode(TextField.Mode.INTEGER).setStep(5).setText("10");
        scrollUp(1);
        assertEquals("scrolling up must count up, not down", "15", field.getText());
        scrollUp(-1);
        scrollUp(-1);
        assertEquals("5", field.getText());
    }

    /** A wheel notch is a complete gesture, so it publishes even under ON_COMMIT. */
    @Test
    public void wheelStepPublishesImmediately() {
        field.setMode(TextField.Mode.INTEGER).setText("1");
        int[] fired = {0};
        field.attachListener(v -> fired[0]++);

        scrollUp(1);
        assertEquals(1, fired[0]);
        assertEquals("2", field.getValue());
    }

    @Test
    public void wheelStepRespectsTheRange() {
        field.setMode(TextField.Mode.INTEGER).setRange(0, 3).setText("2");
        scrollUp(1);
        scrollUp(1);
        scrollUp(1);
        assertEquals("3", field.getText());
    }

    @Test
    public void wheelStepsFromZeroOnAnEmptyField() {
        field.setMode(TextField.Mode.INTEGER);
        scrollUp(1);
        assertEquals("1", field.getText());
    }

    /** Nothing to step in a text field, and a stray wheel must not rewrite it. */
    @Test
    public void wheelDoesNothingToAStringField() {
        field.setText("abc");
        scrollUp(1);
        assertEquals("abc", field.getText());
    }

    /** Gated on focus, so a wheel crossing a field in a scrolling list can't silently edit it. */
    @Test
    public void wheelIsIgnoredWhenTheFieldIsNotFocused() {
        field.setMode(TextField.Mode.INTEGER).setText("10");
        document.focus().requestFocus(focusSink());
        scrollUp(1);
        assertEquals("10", field.getText());
    }

    // ── Negatives and decimal points reach the box ──────────────────────────

    @Test
    public void aSignedRangeAdmitsTheMinusSign() {
        field.setMode(TextField.Mode.INTEGER).setRange(-50, 50);
        type("-12");
        assertEquals("-12", field.getText());
        assertFalse(field.isInvalid());
    }

    @Test
    public void aDoubleFieldAdmitsDecimalPointsAndSigns() {
        field.setMode(TextField.Mode.DOUBLE).setRange(-10, 10);
        type("-1.25");
        assertEquals("-1.25", field.getText());
        assertFalse(field.isInvalid());
        field.commit();
        assertEquals(-1.25d, field.getNumber(0), 0.0001);
    }

    // ── maxLength ───────────────────────────────────────────────────────────

    @Test
    public void typingStopsAtMaxLength() {
        field.setMaxLength(3);
        type("12345");
        assertEquals("123", field.getText());
    }

    /** A paste is truncated to what fits, never refused whole — the behaviour every browser has. */
    @Test
    public void insertTruncatesRatherThanRejecting() {
        field.setMaxLength(4);
        field.insert("ab");
        field.insert("cdef");
        assertEquals("abcd", field.getText());
    }

    /**
     * Room is what SURVIVES the edit, not the current length. Measuring against the latter wedges a
     * full field forever: select-all-and-retype would see zero room and refuse its own replacement.
     */
    @Test
    public void replacingASelectionMayInsertAsMuchAsItRemoves() {
        field.setMaxLength(3);
        type("123");
        field.selectAll();
        field.insert("789");
        assertEquals("789", field.getText());
    }

    /**
     * {@code setText} is exempt, as the web's {@code maxlength} is for assignment to {@code .value}.
     * A widget formatting its own field has to be able to put back what it computed.
     */
    @Test
    public void setTextIgnoresMaxLength() {
        field.setMaxLength(3);
        field.setText("0.690");
        assertEquals("0.690", field.getText());
    }

    @Test
    public void negativeMaxLengthIsUnlimited() {
        field.setMaxLength(-1);
        type("0.6905");
        assertEquals("0.6905", field.getText());
    }

    // ── Configuration must stay side-effect free ────────────────────────────

    /** Reconfiguring a field is not an edit. This is what pulled the commit out of revalidate(). */
    @Test
    public void configurationChangesDoNotPublish() {
        field.setText("5");
        int[] fired = {0};
        field.attachListener(v -> fired[0]++);

        field.setMode(TextField.Mode.INTEGER);
        field.setRange(0, 100);
        field.setPattern("\\d+");
        field.setTextValidator(s -> true);
        field.setCharFilter(c -> true);

        assertEquals("configuration must not emit a value change", 0, fired[0]);
    }

    // ── Word-wise editing ───────────────────────────────────────────────────

    /**
     * <b>Ctrl+Backspace deletes a word.</b> The field only ever handled Ctrl with A/C/V/X, so this fell
     * through to the plain switch and removed a single character — indistinguishable from a modifier the
     * field simply ignored.
     */
    @Test
    public void ctrlBackspaceDeletesTheWordBeforeTheCaret() {
        field.setText("hello brave world");
        key(CgKeyCodes.KEY_END);

        ctrl(CgKeyCodes.KEY_BACK);

        assertEquals("hello brave ", field.getText());
    }

    /** And Ctrl+Delete takes the word after it. */
    @Test
    public void ctrlDeleteRemovesTheWordAfterTheCaret() {
        field.setText("hello brave world");
        key(CgKeyCodes.KEY_HOME);

        ctrl(CgKeyCodes.KEY_DELETE);

        assertEquals("brave world", field.getText().stripLeading().isEmpty()
                ? field.getText() : field.getText().stripLeading());
    }

    /**
     * A selection wins. Extending past a deliberate selection would destroy more than the user pointed
     * at, which is what every editor avoids.
     */
    @Test
    public void ctrlBackspaceWithASelectionDeletesOnlyTheSelection() {
        field.setText("hello brave world");
        key(CgKeyCodes.KEY_HOME);
        for (int i = 0; i < 5; i++) shiftKey(CgKeyCodes.KEY_RIGHT);

        ctrl(CgKeyCodes.KEY_BACK);

        assertEquals(" brave world", field.getText());
    }

    /** Word-wise movement, the same primitive — nobody who has Ctrl+Backspace stops at Ctrl+Backspace. */
    @Test
    public void ctrlArrowsMoveByWord() {
        field.setText("hello brave world");
        key(CgKeyCodes.KEY_END);

        ctrl(CgKeyCodes.KEY_LEFT);
        assertEquals("caret should sit at the start of 'world'", 12, field.getCaret());

        ctrl(CgKeyCodes.KEY_RIGHT);
        assertEquals(17, field.getCaret());
    }

    private void shiftKey(int keyCode) {
        modifiers = CgModifiers.SHIFT;
        key(keyCode);
        modifiers = 0;
    }
}
