package com.crystalgui.ui.elements;

import javax.annotation.Nullable;
import com.crystalgui.ui.contract.RatePolicy;
import com.crystalgui.ui.contract.Event;
import com.crystalgui.ui.contract.WidgetContracts;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.StateTypes;
import com.crystalgui.ui.contract.State;
import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgui.render.text.FontFamilyCache;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.text.Rope;
import com.crystalgui.text.WordClassifier;
import com.crystalgui.text.WordOperations;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgui.core.property.Property;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.style.property.visual.text.LineHeightValue;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIFrameTicker;
import com.crystalgui.ui.event.FocusEvent;
import com.crystalgui.ui.event.KeyboardEvent;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.input.FocusPolicy;

import java.util.function.Predicate;
import java.util.regex.Pattern;
import com.crystalgraphics.platform.CgPlatform;

/**
 * A single-line editable text field.
 *
 * <h3>Two strings, not one</h3>
 * <p>{@link #getText()} is the raw box contents — whatever has been typed, valid or not.
 * {@link #getValue()} is the published value, and it is a bindable {@link Property}. They differ
 * while an edit is in flight, which is the whole point: you have to be able to type {@code "-"} on
 * the way to {@code "-5"} without {@code "-"} reaching whatever is bound to the field.</p>
 *
 * <p>When the raw text is published is governed by {@link UpdateMode}, which defaults to
 * {@link UpdateMode#ON_COMMIT} — Enter, blur, or an explicit {@link #commit()}.</p>
 *
 * <h3>Caret geometry</h3>
 * <p>Positions come from measuring <em>substrings</em> — {@code width(text.substring(0, i))} — not
 * from glyph or cluster data. That keeps every index in Java's own UTF-16 terms and avoids the
 * UTF-8 byte offsets {@code CgShapedRun.clusterIds()} would drag in. The widths for every caret
 * position are measured once per text change into {@link #prefixWidths}, so typing and clicking are
 * array lookups rather than re-shaping.</p>
 *
 * <p><b>Known approximation.</b> The width of a prefix is not exactly the pen position of that glyph
 * in the fully-shaped string: kerning across the caret, or a ligature spanning it, can differ
 * slightly, and for a cursive script like Arabic it would be plainly wrong. For the Latin UI text
 * this is built for it is invisible. A complex-script field is the point to switch to
 * {@code clusterIds()}.</p>
 *
 * <h3>Validation</h3>
 * <p>Two layers with different powers, each of which the caller and the {@link Mode} contribute to
 * independently:</p>
 * <ol>
 *   <li><b>Keystroke</b> — {@link #setCharFilter}/{@link #setCharPattern} reject a character
 *       outright, so it never enters the text at all.</li>
 *   <li><b>Whole text</b> — {@link #setPattern}/{@link #setTextValidator}, plus number parsing and
 *       {@link #setRange}, mark the content {@code :invalid} without blocking editing.</li>
 * </ol>
 * <p>The caller's constraints and the mode's are <b>AND-ed</b>, never substituted: a filter is a
 * restriction, and restrictions compose. So {@code setMode(INTEGER).setCharFilter(...)} cannot
 * accidentally reopen the field to letters. The cost is that a contradictory pair — say
 * {@code setMode(INTEGER).setPattern("[a-z]+")} — is simply unsatisfiable; a caller who wants
 * complete control uses {@link Mode#STRING}.</p>
 */
public class TextField extends UIElement implements UIFrameTicker {

    public static final State<TextField, Mode> MODE =
            State.of("mode", StateTypes.enumOf(Mode.class), TextField::getMode, TextField::setMode, Mode.STRING);

    public static final State<TextField, UpdateMode> UPDATE_MODE =
            State.of("updateMode", StateTypes.enumOf(UpdateMode.class),
                    TextField::getUpdateMode, TextField::setUpdateMode, UpdateMode.ON_COMMIT);

    public static final State<TextField, String> PLACEHOLDER =
            State.<TextField, String>of("placeholder", StateTypes.STRING,
                            TextField::getPlaceholder, TextField::setPlaceholder, "")
                    .omittedWhen("");

    public static final State<TextField, String> TEXT =
            State.<TextField, String>of("text", StateTypes.STRING,
                            TextField::getText, TextField::setText, "")
                    .omittedWhen("");

    /**
     * The value as PARSED by the mode, written and never read back.
     *
     * <p>Derived from {@link #TEXT}, so applying it would be applying the same thing twice -- the
     * hand-written pair wrote it and did not read it either, which is easy to mistake for an
     * oversight. It travels because a server reading a numeric field wants the number rather than the
     * string the user is halfway through typing.</p>
     */
    public static final State<TextField, String> VALUE =
            State.<TextField, String>of("value", StateTypes.STRING,
                            TextField::getValue, (field, ignored) -> { }, "")
                    .omittedWhen("");

    /** Every keystroke, debounced. @see RatePolicy#TYPING */
    public static final Event<TextField, String> TEXT_CHANGED = Event.<TextField, String>of("text",
            (field, sink) -> field.attachListener(sink::accept),
            new Event.Payload<String>() {
                @Override public <T> void write(StateMap<T> out, String value) {
                    out.putString("text", value == null ? "" : value);
                }
                @Override public <T> String read(StateMap<T> in) {
                    return in.getString("text", "");
                }
            }, RatePolicy.TYPING)
            .sanitizedBy((field, text) -> field.truncateToMaxLength(text));

    /**
     * The edit was MEANT -- Enter, or focus leaving the field.
     *
     * <p>The pair with {@link #TEXT_CHANGED} is what lets a server take a cheap running view of an edit
     * and an expensive one only when the user commits. {@code UpdateMode} has drawn this distinction
     * locally since it existed and had no way to say it over a wire.</p>
     */
    public static final Event<TextField, String> COMMITTED = Event.<TextField, String>of("commit",
            (field, sink) -> field.onSubmit.connect(sink::accept),
            new Event.Payload<String>() {
                @Override public <T> void write(StateMap<T> out, String value) {
                    out.putString("text", value == null ? "" : value);
                }
                @Override public <T> String read(StateMap<T> in) {
                    return in.getString("text", "");
                }
            }, RatePolicy.IMMEDIATE)
            .sanitizedBy((field, text) -> field.truncateToMaxLength(text));

    /** Mode first: it decides how the text is parsed, so text applied before it is parsed by the old one. */
    public static final WidgetContract<TextField> CONTRACT = WidgetContracts.register(
            WidgetContract.of(TextField.class, "textfield")
                    .state(MODE)
                    .state(UPDATE_MODE)
                    .state(PLACEHOLDER)
                    .state(TEXT)
                    .state(VALUE)
                    .event(TEXT_CHANGED)
                    .event(COMMITTED)
                    .primary(TEXT)
                    .build());

    /** What the content is expected to be. Drives parsing, the auto-constraints and {@code :invalid}. */
    public enum Mode {
        STRING,
        INTEGER,
        LONG,
        FLOAT,
        DOUBLE;

        public boolean isNumber() {
            return this != STRING;
        }
    }

    /** When edits are published to {@link #value}. */
    public enum UpdateMode {
        /** Every accepted keystroke that leaves the field valid updates the value. */
        IMMEDIATE,
        /** Only Enter, blur, or an explicit {@link #commit()} updates the value. The default. */
        ON_COMMIT
    }

    /**
     * The published value — bindable. <b>Not</b> the raw box contents; see {@link #getText()}.
     *
     * <p>Only ever assigned from {@link #commit()}, and only with an already-final string. Do not
     * normalise or clamp inside a {@code value.changed} listener: {@link Property#set} silently
     * drops a re-entrant {@code set} made from within its own emit, so such a listener would appear
     * to work and then quietly do nothing.</p>
     */
    public final Property<String> value = new Property<>("");

    /** Enter was pressed. Fires after the commit, carrying the committed value. */
    public final Signal.Value<String> onSubmit = new Signal.Value<>();

    /** Exactly what's in the box, valid or not. */
    private String text = "";
    private String placeholder = "";

    /** Caret and selection anchor, as UTF-16 indices into {@link #text}. Equal = no selection. */
    private int caret = 0;
    private int selectionAnchor = 0;

    /** Horizontal scroll, in logical px, keeping the caret in view on a too-long string. */
    private float displayOffset = 0f;

    /** {@code prefixWidths[i]} = rendered width of {@code text.substring(0, i)}. */
    private float[] prefixWidths = {0f};
    private String measuredText = null;
    private float measuredFontSize = -1f;

    private UpdateMode updateMode = UpdateMode.ON_COMMIT;
    /** Guards the two-way sync between {@link #value} and {@link #text} from feeding back on itself. */
    private boolean pushingToText = false;

    private Mode mode = Mode.STRING;
    private double min = -Double.MAX_VALUE;
    private double max = Double.MAX_VALUE;
    private double step = 1d;
    private boolean invalid = false;

    // Caller-supplied constraints. Null means "unset" — kept SEPARATE from the mode-derived ones
    // below so that setMode/setRange re-deriving theirs can never silently wipe the caller's.
    private Predicate<Character> userCharFilter = null;
    private Predicate<String> userTextValidator = null;
    private Pattern userCharPattern = null;
    private Pattern userPattern = null;
    /** HTML's {@code maxlength}. Negative means unlimited, which is the default. */
    private int maxLength = -1;

    // Derived from mode + range by applyModeConstraints(). Never written by a setter.
    private Predicate<Character> modeCharFilter = c -> true;
    private Pattern modePattern = null;

    /** Half-period. 0.53s is what Windows and Chrome use. */
    private static final float DEFAULT_BLINK_SECONDS = 0.53f;
    private float blinkSeconds = DEFAULT_BLINK_SECONDS;
    private float blinkPhase = 0f;
    private boolean caretVisible = true;

    public TextField() {
        setFocusPolicy(FocusPolicy.CLICK);

        // An external change to the bound value has to reach the visible box. commit() sets
        // pushingToText around its own write so this doesn't fight it; the pattern (and the flag)
        // mirror ScrollerView.syncing.
        value.changed.connect((oldValue, newValue) -> {
            if (pushingToText) return;
            pushingToText = true;
            try {
                String next = newValue == null ? "" : newValue;
                writeRaw(next, next.length());
                revalidate();
            } finally {
                pushingToText = false;
            }
        });

        this.events.getGroup(MouseEvent.Down.class).attachListener((el, event) -> {
            if (!isEnabled()) return;
            int index = indexAt(screenToLocal(event.getPosition().x(), event.getPosition().y()).x());
            if (event.getDetail() >= 2) {
                selectWordAt(index);
                return;
            }
            moveCaret(index, CgModifiers.hasShift(currentModifiers()));

            // Drag-select. The press has already placed the caret, which becomes the anchor; every
            // drag update just re-runs click-to-caret with select=true so the selection grows from it.
            // Coordinates arrive already converted to this element's local space.
            var window = getAttachedWindow();
            if (window == null) return;
            window.getInputHandler().getDragController().startDrag(this,
                    event.getPosition().x(), event.getPosition().y(),
                    (mouseX, mouseY, startX, startY, dx, dy) -> moveCaret(indexAt(mouseX), true));
        }, false, true);

        this.events.getGroup(KeyboardEvent.Down.class).attachListener((el, event) -> {
            if (!isEnabled()) return;
            // Control keys first; anything they don't claim is treated as a typed character. Without
            // this second half the field is completely unwritable — handleKey returns false for every
            // printable key and nothing else was consuming the character.
            if (handleKey(event.getKeyCode(), event.getModifiers())) {
                event.stopPropagation();
                return;
            }
            // Ctrl-combos that reach here (Ctrl+S and friends) carry a control character, which
            // insertChar rejects anyway, but bailing early keeps them from being swallowed.
            if (CgModifiers.hasCtrl(event.getModifiers())) return;
            // ALT TOO, and for the same reason. Alt+W is a chord; typed into a field it inserted a "w" AND
            // consumed the event, so the keymap -- which resolves after dispatch and only if nothing
            // stopped it -- never saw the binding. Every Alt shortcut in the application was therefore dead
            // exactly where its own tooltip said to press it.
            if (CgModifiers.hasAlt(event.getModifiers())) return;
            char typed = event.getCharacter();
            if (typed != '\0' && !Character.isISOControl(typed)) {
                insertChar(typed);
                event.stopPropagation();
            }
        }, false, false);

        // Wheel-to-step, like a browser's number input. Gated on FOCUS so a wheel passing over a
        // field inside a scrolling list doesn't silently rewrite a value the user never touched —
        // the same reason browsers require focus for this.
        this.events.getGroup(MouseEvent.Scroll.class).attachListener((el, event) -> {
            if (!isFocused()) return;
            // NEGATED on purpose. getScroll() uses the SCROLLING convention — positive means "further
            // down the document", so a wheel push away from you arrives negative. A value spinner uses
            // the opposite convention: wheel up means MORE. Passing the raw sign through here would
            // make the field count down when you scroll up.
            if (stepBy(-Math.round(event.getScroll()))) event.stopPropagation();
        }, false, true);

        // The first widget in the codebase to listen to its own focus. On a CLICK the Focus event is
        // emitted BEFORE MouseEvent.Down, so caret-to-end lands first and click-to-caret then
        // overrides it: Tab arrives at the end, a click arrives where you clicked.
        this.events.getGroup(FocusEvent.Focus.class).attachListener((el, event) -> {
            moveCaret(text.length(), false);
            var window = getAttachedWindow();
            // registerTicker is backed by a HashSet, so re-registering on every refocus is
            // idempotent; the ticker drops itself by returning false once unfocused.
            if (window != null) window.registerTicker(this);
        }, false, false);

        this.events.getGroup(FocusEvent.Blur.class).attachListener((el, event) -> {
            // NOTE: isFocused() is ALREADY false here — emitAndLoseFocus clears it before emitting.
            // Do not gate this on focus state.
            commit();
            resetBlink();
        }, false, false);
    }

    /** Space and Enter must arrive as characters, not as a synthesized click. */
    @Override
    public boolean consumesTextInput() {
        return isEnabled();
    }

    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    /** Drives {@code :blank}, so a theme can style the placeholder without any engine support. */
    @Override
    public boolean isBlank() {
        return text.isEmpty();
    }

    /** Drives {@code :invalid}. */
    @Override
    public boolean isInvalid() {
        return invalid;
    }

    // ── Value ───────────────────────────────────────────────────────────────

    /** Exactly what's in the box, including content that doesn't currently validate. */
    public String getText() {
        return text;
    }

    /** The published value — what a binding sees. Updated per {@link UpdateMode}. */
    public String getValue() {
        return value.get();
    }

    /**
     * Programmatic, authoritative assignment: shows {@code newText} and publishes it if it validates.
     *
     * <p>Unlike {@link #commit()} this never clamps and never reverts — setting {@code "50"} on a
     * 0..10 field leaves {@code "50"} on screen and merely marks it {@code :invalid}. Rewriting what
     * a caller explicitly asked for would be a surprise; rewriting what a <em>user</em> typed, at the
     * moment they finish, is not.</p>
     */
    public TextField setText(String newText) {
        String next = newText == null ? "" : newText;
        if (!next.equals(text)) {
            // Caret to the END, as assigning to a browser input's .value does. Clamping the old
            // caret instead leaves it at 0 for the common set-then-type case, so the next keystroke
            // lands in front of the text you just installed.
            writeRaw(next, next.length());
            revalidate();
        }
        publishIfValid();
        return this;
    }

    public String getPlaceholder() {
        return placeholder;
    }

    /** Half-strength, alpha kept — a hint should read as one without needing a colour of its own. */
    private static int dim(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = ((argb >> 16) & 0xFF) / 2;
        int g = ((argb >> 8) & 0xFF) / 2;
        int b = (argb & 0xFF) / 2;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public TextField setPlaceholder(String placeholder) {
        this.placeholder = placeholder == null ? "" : placeholder;
        onStyleChanged();
        return this;
    }

    /** Convenience over {@code value.changed}, matching every other widget's listener convention. */
    public TextField attachListener(Signal.Value.Listener<String> action) {
        value.changed.connect((oldValue, newValue) -> action.accept(newValue));
        return this;
    }

    /** One-way: this field follows {@code source}. */
    public Connection bindValueTo(Property<String> source) {
        return value.bindTo(source);
    }

    /** Two-way. {@code other}'s value wins at bind time, as {@link Property#bindBidirectional} does. */
    public Connection bindValueBidirectional(Property<String> other) {
        return value.bindBidirectional(other);
    }

    public UpdateMode getUpdateMode() {
        return updateMode;
    }

    public TextField setUpdateMode(UpdateMode updateMode) {
        this.updateMode = updateMode == null ? UpdateMode.ON_COMMIT : updateMode;
        if (this.updateMode == UpdateMode.IMMEDIATE) publishIfValid();
        return this;
    }

    /**
     * Publishes the current text, fixing it up first — this is the "user finished editing" path.
     *
     * <p>Out-of-range numbers are clamped and content that cannot be published at all reverts to the
     * last value, so the box never shows something that isn't the value. Both only ever happen here,
     * never mid-typing: clamping while someone types would fight them (deleting a digit from "10"
     * would snap it back).</p>
     *
     * @return whether the value actually changed
     */
    public boolean commit() {
        String candidate = text;
        if (!passesTextLayers(candidate)) {
            revertToValue();
            return false;
        }
        if (mode.isNumber()) {
            Double parsed = parseNumber(candidate);
            if (parsed == null) {          // "", a lone "-", "7x" — nothing publishable
                revertToValue();
                return false;
            }
            candidate = formatNumber(Math.max(min, Math.min(max, parsed)));
        }
        if (!candidate.equals(text)) writeRaw(candidate, candidate.length());
        revalidate();
        return publish(candidate);
    }

    /** The IMMEDIATE / setText path: publish when valid, otherwise leave the text alone. */
    private void publishIfValid() {
        if (!invalid) publish(text);
    }

    private boolean publish(String candidate) {
        if (candidate.equals(value.get())) return false;
        pushingToText = true;
        try {
            value.set(candidate);
        } finally {
            pushingToText = false;
        }
        return true;
    }

    private void revertToValue() {
        String published = value.get();
        if (!published.equals(text)) writeRaw(published, published.length());
        revalidate();
    }

    /** Parsed value for a number field, or {@code fallback} if the published value doesn't parse. */
    public double getNumber(double fallback) {
        Double parsed = parseNumber(value.get());
        return parsed == null ? fallback : parsed;
    }

    // ── Constraints ─────────────────────────────────────────────────────────

    public Mode getMode() {
        return mode;
    }

    /** Also installs this mode's own keystroke filter and format pattern — see {@link Mode}. */
    public TextField setMode(Mode mode) {
        this.mode = mode == null ? Mode.STRING : mode;
        applyModeConstraints();
        revalidate();
        return this;
    }

    /** Rejects individual keystrokes — a rejected character never enters the text at all. */
    /**
     * Caps how many characters may be <em>entered</em>, HTML's {@code maxlength}. Negative is unlimited.
     *
     * <p><b>Entered, not held.</b> {@link #setText} is deliberately exempt, exactly as the web's
     * {@code maxlength} constrains typing and pasting but not assignment to {@code .value}. A widget
     * that formats its own field — a colour channel writing {@code "0.690"}, a spinner writing a
     * clamped bound — must be able to put back whatever it computed, and a limit that fought its owner
     * would truncate the very value it was trying to display.</p>
     *
     * <p>Applied at {@link #insert}, which is the single point both typing and pasting funnel through,
     * so a paste is truncated to fit rather than refused whole — the same as every browser.</p>
     */
    /**
     * Cuts an arriving string to what this field would have let a user type.
     *
     * <p>A client cannot type past {@code maxLength}, so a longer string did not come from typing. Cut
     * rather than refused: the truncation is a value the user COULD have produced, so the handler runs
     * and the model stays sane.</p>
     */
    String truncateToMaxLength(@Nullable String text) {
        if (text == null) return "";
        return maxLength >= 0 && text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    public TextField setMaxLength(int maxLength) {
        this.maxLength = maxLength;
        return this;
    }

    public int getMaxLength() {
        return maxLength;
    }

    public TextField setCharFilter(Predicate<Character> charFilter) {
        this.userCharFilter = charFilter;
        return this;
    }

    /** Keystroke filtering as a regex, full-matched against the single character. */
    public TextField setCharPattern(String regex) {
        this.userCharPattern = regex == null ? null : Pattern.compile(regex);
        return this;
    }

    /** Marks the whole value invalid without preventing editing. */
    public TextField setTextValidator(Predicate<String> textValidator) {
        this.userTextValidator = textValidator;
        revalidate();
        return this;
    }

    /** Format checking as a regex, full-matched against the whole text. Marks {@code :invalid} only. */
    public TextField setPattern(String regex) {
        return setPattern(regex == null ? null : Pattern.compile(regex));
    }

    public TextField setPattern(Pattern pattern) {
        this.userPattern = pattern;
        revalidate();
        return this;
    }

    public Pattern getPattern() {
        return userPattern;
    }

    public double getStep() {
        return step;
    }

    /** How far one wheel notch moves a number field. Ignored by {@link Mode#STRING}. */
    public TextField setStep(double step) {
        this.step = Math.abs(step);
        return this;
    }

    /**
     * Nudges a number field by {@code notches * step}, clamped to the range.
     *
     * <p>Publishes straight away regardless of {@link UpdateMode}: a wheel notch is a complete
     * gesture, not a half-typed value, so there is nothing to wait for. Starts from the published
     * value when the box holds something unparseable, and from {@code 0} (or the nearest bound) when
     * there is nothing to start from at all — so the wheel works on an empty field.</p>
     *
     * @return whether anything moved
     */
    public boolean stepBy(int notches) {
        if (!isEnabled() || !mode.isNumber() || notches == 0 || step == 0d) return false;

        Double base = parseNumber(text);
        if (base == null) base = parseNumber(value.get());
        if (base == null) base = Math.max(min, Math.min(max, 0d));

        double next = Math.max(min, Math.min(max, base + notches * step));
        String formatted = formatNumber(next);
        if (formatted.equals(text)) return false;

        writeRaw(formatted, formatted.length());
        revalidate();
        publishIfValid();
        return true;
    }

    /** Numeric bounds, clamped on {@link #commit()}. Only meaningful for a number {@link Mode}. */
    public TextField setRange(double min, double max) {
        this.min = Math.min(min, max);
        this.max = Math.max(min, max);
        applyModeConstraints();   // a non-negative range makes '-' unwelcome at the keystroke
        revalidate();
        return this;
    }

    /**
     * Derives this mode's constraints. Called from BOTH {@link #setMode} and {@link #setRange}, so
     * the two are order-independent.
     *
     * <p>The patterns are deliberately looser than "is a valid number": {@code "-"}, {@code "1."},
     * {@code "1e"} and {@code "1e-"} all match, because every one of them is a real intermediate
     * state on the way to a number and must stay typable. The strict judge is
     * {@link #parseNumber} plus the range, in {@link #revalidate}.</p>
     */
    private void applyModeConstraints() {
        switch (mode) {
            case INTEGER, LONG -> {
                modePattern = INTEGER_PATTERN;
                modeCharFilter = c -> Character.isDigit(c) || (c == '-' && min < 0);
            }
            case FLOAT, DOUBLE -> {
                modePattern = DECIMAL_PATTERN;
                // '+'/'-' stay allowed regardless of the range: they are also exponent signs, and
                // "1e-5" is positive.
                modeCharFilter = c -> Character.isDigit(c)
                        || c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+';
            }
            default -> {
                modePattern = null;
                modeCharFilter = c -> true;
            }
        }
    }

    /** Keystroke test — the mode's filter AND the caller's, both of which may reject. */
    private boolean acceptsChar(char c) {
        if (!modeCharFilter.test(c)) return false;
        if (userCharPattern != null && !userCharPattern.matcher(String.valueOf(c)).matches()) return false;
        return userCharFilter == null || userCharFilter.test(c);
    }

    /** Whole-text test, excluding number parsing and range (which {@link #revalidate} adds). */
    private boolean passesTextLayers(String s) {
        if (modePattern != null && !modePattern.matcher(s).matches()) return false;
        if (userPattern != null && !userPattern.matcher(s).matches()) return false;
        return userTextValidator == null || userTextValidator.test(s);
    }

    /**
     * Recomputes {@code :invalid}. Deliberately does NOT publish and does NOT rewrite the text —
     * so reconfiguring the field (mode, range, pattern, validator) can never emit a value change as
     * a side effect of a call that was only ever about configuration.
     */
    private void revalidate() {
        boolean ok = passesTextLayers(text);
        if (ok && mode.isNumber()) {
            Double parsed = parseNumber(text);
            // Empty, or a lone "-"/"." on the way to a real number, is incomplete rather than wrong.
            ok = parsed != null && parsed >= min && parsed <= max;
        }
        this.invalid = !ok;
    }

    private Double parseNumber(String s) {
        if (s.isEmpty()) return null;
        try {
            return switch (mode) {
                case INTEGER -> (double) Integer.parseInt(s);
                case LONG -> (double) Long.parseLong(s);
                case FLOAT -> (double) Float.parseFloat(s);
                case DOUBLE -> Double.parseDouble(s);
                case STRING -> null;
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String formatNumber(double v) {
        return switch (mode) {
            case INTEGER -> Integer.toString((int) v);
            case LONG -> Long.toString((long) v);
            case FLOAT -> Float.toString((float) v);
            case DOUBLE -> Double.toString(v);
            case STRING -> text;
        };
    }

    private static final Pattern INTEGER_PATTERN = Pattern.compile("-?\\d*");
    private static final Pattern DECIMAL_PATTERN = Pattern.compile("[-+]?\\d*\\.?\\d*(?:[eE][-+]?\\d*)?");

    // ── Caret / selection ───────────────────────────────────────────────────

    public int getCaret() {
        return caret;
    }

    public boolean hasSelection() {
        return caret != selectionAnchor;
    }

    /** Selects everything, as Ctrl+A does. The keyboard path existed; a programmatic one did not. */
    public TextField selectAll() {
        selectionAnchor = 0;
        caret = text.length();
        resetBlink();
        onStyleChanged();
        return this;
    }

    /**
     * Selects {@code [start, end)}, leaving the caret at {@code end}.
     *
     * <p>{@link #selectAll} is the special case, and the reason the general one is wanted is inline
     * rename: F2 selects a filename's <b>stem</b> and not its extension, because the extension is almost
     * never what is being changed and selecting it means the first keystroke destroys it. Every file
     * manager does this and none of them can with select-all alone.</p>
     *
     * <p>Clamped rather than refused. A caller computing an offset from a string — the last dot in a name
     * — is one rename away from an index past the end, and throwing there would take the widget down for
     * something with an obvious right answer.</p>
     */
    public TextField setSelection(int start, int end) {
        int limit = text.length();
        this.selectionAnchor = Math.max(0, Math.min(limit, start));
        this.caret = Math.max(0, Math.min(limit, end));
        resetBlink();
        onStyleChanged();
        return this;
    }

    /** Drops any selection, leaving the caret where it is. */
    public TextField clearSelection() {
        selectionAnchor = caret;
        onStyleChanged();
        return this;
    }

    public int getSelectionStart() {
        return Math.min(caret, selectionAnchor);
    }

    public int getSelectionEnd() {
        return Math.max(caret, selectionAnchor);
    }

    public String getSelectedText() {
        return text.substring(getSelectionStart(), getSelectionEnd());
    }

    /** Moves the caret, extending the selection when {@code select} — i.e. shift is held. */
    private void moveCaret(int index, boolean select) {
        this.caret = Math.max(0, Math.min(text.length(), index));
        if (!select) this.selectionAnchor = caret;
        resetBlink();
        onStyleChanged();
    }

    /**
     * Steps one caret position, by CODE POINT rather than by {@code char}, so an astral character
     * (emoji) is crossed in a single press instead of leaving the caret between its surrogates.
     */
    private int step(int index, int direction) {
        if (direction < 0 && index <= 0) return 0;
        if (direction > 0 && index >= text.length()) return text.length();
        return text.offsetByCodePoints(index, direction);
    }

    private void selectWordAt(int index) {
        int start = index, end = index;
        while (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) start--;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) end++;
        this.selectionAnchor = start;
        this.caret = end;
        resetBlink();
        onStyleChanged();
    }

    // ── Blink ───────────────────────────────────────────────────────────────

    /** Whether the caret is in its visible half of the blink. The headless-testable surface. */
    public boolean isCaretVisible() {
        return caretVisible;
    }

    /** Blink half-period in seconds. {@code 0} keeps the caret solid — motion sensitivity. */
    public TextField setCaretBlinkSeconds(float seconds) {
        this.blinkSeconds = Math.max(0f, seconds);
        resetBlink();
        return this;
    }

    /** Restarts the blink solid. Called from every caret movement and every text write, as browsers
     * do — a caret that blinks out from under you while you type is maddening. */
    private void resetBlink() {
        this.blinkPhase = 0f;
        this.caretVisible = true;
    }

    @Override
    public boolean tickFrame(float deltaSeconds) {
        if (!isFocused()) {
            resetBlink();
            return false;   // drop the ticker; refocusing re-registers it
        }
        if (blinkSeconds <= 0f) {
            caretVisible = true;
            return true;
        }
        blinkPhase += deltaSeconds;
        while (blinkPhase >= blinkSeconds) {
            blinkPhase -= blinkSeconds;
            caretVisible = !caretVisible;
        }
        return true;
    }

    // ── Editing ─────────────────────────────────────────────────────────────

    /** Types one character, honouring the keystroke filters and replacing any selection. */
    public void insertChar(char c) {
        if (!isEnabled() || c == '\0' || Character.isISOControl(c)) return;
        if (!acceptsChar(c)) return;
        insert(String.valueOf(c));
    }

    public void insert(String s) {
        if (!isEnabled() || s.isEmpty()) return;
        int start = getSelectionStart(), end = getSelectionEnd();
        if (maxLength >= 0) {
            // Room is measured against what SURVIVES this edit, so replacing a selection can insert as
            // much as it removes — a full-field select-and-retype has to keep working at the cap, and
            // measuring against the current length instead would wedge the field permanently once full.
            int room = maxLength - (text.length() - (end - start));
            if (room <= 0) return;
            if (s.length() > room) s = s.substring(0, room);
        }
        editText(text.substring(0, start) + s + text.substring(end), start + s.length());
    }

    /** Deletes the selection, or one code point in {@code direction} when there isn't one. */
    /**
     * The offset one word away from the caret, using the editor's own word rules.
     *
     * <p>A {@code Rope} is built per call. That is not a concern at this scale — a single-line field is
     * short, and this runs on a keystroke, not a frame — and it is what lets the field share
     * {@link WordOperations} with the editor instead of growing a second, subtly different idea of where
     * a word ends.</p>
     */
    private int wordBoundary(int direction) {
        Rope document = Rope.of(text);
        return direction < 0
                ? WordOperations.previousWordStart(document, caret, WordClassifier.DEFAULT)
                : WordOperations.nextWordEnd(document, caret, WordClassifier.DEFAULT);
    }

    /**
     * Ctrl+Backspace / Ctrl+Delete.
     *
     * <p>A selection wins: with something selected these delete <em>it</em> and nothing more, which is
     * what every editor does — extending the removal past a deliberate selection would be destroying
     * more than the user pointed at.</p>
     */
    private void deleteToWordBoundary(int direction) {
        if (!isEnabled()) return;
        if (hasSelection()) {
            deleteSelectionOr(0);
            return;
        }
        int to = wordBoundary(direction);
        if (to == caret) return;
        int start = Math.min(caret, to), end = Math.max(caret, to);
        editText(text.substring(0, start) + text.substring(end), start);
    }

    private void deleteSelectionOr(int direction) {
        if (!isEnabled()) return;
        int start = getSelectionStart(), end = getSelectionEnd();
        if (start == end) {
            if (direction < 0) start = step(start, -1);
            else end = step(end, 1);
            if (start == end) return; // at the edge, nothing to remove
        }
        editText(text.substring(0, start) + text.substring(end), start);
    }

    /**
     * The path every USER edit takes — and the reason {@link UpdateMode#ON_COMMIT} works at all.
     *
     * <p>Editing must not go through {@link #setText}: that is the programmatic path and publishes
     * immediately, so routing keystrokes through it would make every keystroke a commit and
     * {@code ON_COMMIT} a no-op.</p>
     */
    private void editText(String next, int newCaret) {
        if (next.equals(text)) {
            moveCaret(newCaret, false);
            return;
        }
        writeRaw(next, newCaret);
        revalidate();
        if (updateMode == UpdateMode.IMMEDIATE) publishIfValid();
    }

    /** Lowest level: swaps the text and caret and invalidates everything derived from them. */
    private void writeRaw(String next, int newCaret) {
        this.text = next;
        this.caret = Math.max(0, Math.min(next.length(), newCaret));
        this.selectionAnchor = this.caret;
        this.measuredText = null;   // force a re-measure of prefixWidths
        resetBlink();
        onStyleChanged();
        invalidateStyleMatch();     // :blank / :invalid may have flipped
        notifyStateChanged();
    }

    private int currentModifiers() {
        var adapter = CgPlatform.input();
        return adapter == null ? 0 : adapter.getCurrentModifiers();
    }

    /** @return whether the key was consumed. */
    private boolean handleKey(int key, int modifiers) {
        boolean shift = CgModifiers.hasShift(modifiers);
        boolean ctrl = CgModifiers.hasCtrl(modifiers);

        if (ctrl) {
            switch (key) {
                case CgKeyCodes.KEY_A -> {
                    selectionAnchor = 0;
                    caret = text.length();
                    resetBlink();       // assigns caret directly, bypassing moveCaret
                    onStyleChanged();
                    return true;
                }
                case CgKeyCodes.KEY_C -> {
                    if (hasSelection()) CgPlatform.input().setClipboard(getSelectedText());
                    return true;
                }
                case CgKeyCodes.KEY_X -> {
                    if (hasSelection()) {
                        CgPlatform.input().setClipboard(getSelectedText());
                        deleteSelectionOr(0);
                    }
                    return true;
                }
                case CgKeyCodes.KEY_V -> {
                    // Paste goes through the keystroke filters too, so a filtered field can't be
                    // bypassed by pasting what it refuses to accept typed.
                    String pasted = CgPlatform.input().getClipboard();
                    StringBuilder kept = new StringBuilder(pasted.length());
                    for (int i = 0; i < pasted.length(); i++) {
                        char c = pasted.charAt(i);
                        if (!Character.isISOControl(c) && acceptsChar(c)) kept.append(c);
                    }
                    insert(kept.toString());
                    return true;
                }
                // Word-wise editing. Ported rather than hand-rolled: `WordOperations` is the same
                // boundary logic the code editor uses, so a word means the same thing in both — and
                // `WordClassifier` is what knows that `foo-bar` is two words and `foo_bar` is one,
                // which a whitespace scan (what selectWordAt below still does for double-click) does
                // not. Delete first, because that is what a user reaches for; the arrows are the same
                // primitive and it would be strange to ship one without the other.
                case CgKeyCodes.KEY_BACK -> {
                    deleteToWordBoundary(-1);
                    return true;
                }
                case CgKeyCodes.KEY_DELETE -> {
                    deleteToWordBoundary(1);
                    return true;
                }
                case CgKeyCodes.KEY_LEFT -> {
                    moveCaret(wordBoundary(-1), shift);
                    return true;
                }
                case CgKeyCodes.KEY_RIGHT -> {
                    moveCaret(wordBoundary(1), shift);
                    return true;
                }
                default -> { }
            }
        }

        switch (key) {
            case CgKeyCodes.KEY_LEFT -> moveCaret(step(caret, -1), shift);
            case CgKeyCodes.KEY_RIGHT -> moveCaret(step(caret, 1), shift);
            case CgKeyCodes.KEY_HOME -> moveCaret(0, shift);
            case CgKeyCodes.KEY_END -> moveCaret(text.length(), shift);
            case CgKeyCodes.KEY_BACK -> deleteSelectionOr(-1);
            case CgKeyCodes.KEY_DELETE -> deleteSelectionOr(1);
            case CgKeyCodes.KEY_RETURN, CgKeyCodes.KEY_NUMPADENTER -> {
                commit();
                onSubmit.emit(value.get());
                // NOT consumed on purpose: consumesTextInput() already stops the activation bridge
                // from synthesizing a click here, so letting Enter bubble costs nothing and lets an
                // enclosing dialog do submit-on-Enter. The '\r' character is ISO-control, so the
                // fall-through to the typing path discards it.
                return false;
            }
            case CgKeyCodes.KEY_ESCAPE -> {
                // Abandon the edit. Consumed ONLY if there was something to abandon, so Escape still
                // closes an enclosing dialog when the field is untouched.
                if (text.equals(value.get())) return false;
                revertToValue();
                return true;
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    // ── Measurement ─────────────────────────────────────────────────────────

    /** Style-driven, exactly as UIText resolves it — cached by (paths, size), so an unchanged
     * font-family/font-size returns the same instance. */
    private CgFontFamily resolveFamily() {
        var general = getStyle().getGeneralGroup();
        return FontFamilyCache.resolve(general.fontFamily(), Math.round(general.fontSize()));
    }

    /**
     * Rebuilds {@link #prefixWidths} when the text or size changed — one measurement per caret
     * position, done once, so interaction never re-shapes.
     */
    private void ensureMeasured() {
        // Same reasoning as UIText.recompute: measuring needs a font stack, which a detached tree
        // has no use for and a dedicated server does not have at all. prefixWidths keeps its
        // single-element default, so caretX/indexAt answer 0 rather than throwing.
        if (getAttachedWindow() == null) return;

        float fontSize = getStyle().getGeneralGroup().fontSize();
        if (text.equals(measuredText) && fontSize == measuredFontSize) return;

        float[] widths = new float[text.length() + 1];
        for (int i = 1; i <= text.length(); i++) {
            widths[i] = measure(text.substring(0, i), fontSize);
        }
        this.prefixWidths = widths;
        this.measuredText = text;
        this.measuredFontSize = fontSize;
    }

    private float measure(String s, float fontSize) {
        if (s.isEmpty()) return 0f;
        return CgTextLayout.of(s, resolveFamily()).build().totalWidth();
    }

    /** X of the caret at {@code index}, relative to the text's origin. */
    public float caretX(int index) {
        ensureMeasured();
        return prefixWidths[Math.max(0, Math.min(prefixWidths.length - 1, index))];
    }

    /**
     * Nearest caret index to a local x — click-to-caret.
     *
     * <p>Snaps to whichever boundary is closer, and only ever considers code-point boundaries, so a
     * click in the middle of an emoji lands on one side of it rather than between its surrogates.</p>
     */
    public int indexAt(float localX) {
        ensureMeasured();
        // Must subtract the same nudge paintOverlay adds, or click-to-caret lands off by it.
        float target = localX - textOriginX()
                - getStyle().getGeneralGroup().textOffsetX().resolve(getRuntimeCache().getWidth())
                + displayOffset;
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i <= text.length(); i = (i >= text.length()) ? i + 1 : step(i, 1)) {
            if (i > text.length()) break;
            float distance = Math.abs(prefixWidths[i] - target);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private float textOriginX() {
        var layout = getTaffyLayout();
        return getRuntimeCache().getX() + layout.border().left + layout.padding().left;
    }

    /**
     * Scrolls horizontally so the caret stays inside the box on a string longer than the field.
     *
     * <h3>An unfocused field shows its START, never its tail</h3>
     * <p>{@link #setText} puts the caret at the end, as assigning to a browser input's {@code .value}
     * does — so without this an overlong string would arrive already scrolled to the right, and a field
     * showing {@code -0.6…} would instead show {@code …5005}: the least informative half of the number,
     * with the sign and the leading digits off the left edge. Browsers behave the same way, and the
     * reason is the same: scrolling to the caret is a service to someone <em>typing</em>, and there is no
     * caret to serve when the field is not focused.</p>
     *
     * <p>Cheap to get wrong in the other direction too — resetting on every call rather than only while
     * unfocused would drag the view back to the start on every keystroke.</p>
     */
    private void ensureCaretVisible() {
        float inner = Math.max(1f, getTaffyLayout().contentBoxWidth());
        displayOffset = scrollOffsetFor(isFocused(), caretX(caret), caretX(text.length()),
                inner, displayOffset);
    }

    /**
     * The rule behind {@link #ensureCaretVisible}, as a function so it can be pinned.
     *
     * <p>Split out because {@code ensureCaretVisible} runs only from {@code paintOverlay}, and painting
     * needs a GL context — so the decision itself was unreachable from any test that could run on a build
     * machine. The bug it now carries a guard for was invisible for exactly that reason: it only shows on
     * a string wider than its box, which no assertion was in a position to look at.</p>
     *
     * @param caretX      pen position of the caret, from the text's own origin
     * @param totalX      pen position of the end of the string
     * @param inner       usable width of the box
     * @param current     the offset in force now
     */
    static float scrollOffsetFor(boolean focused, float caretX, float totalX, float inner, float current) {
        if (!focused) return 0f;
        float offset = current;
        if (caretX - offset < 0f) offset = caretX;
        else if (caretX - offset > inner) offset = caretX - inner;
        // Don't strand the text scrolled past its end when it shrinks.
        return Math.max(0f, Math.min(offset, Math.max(0f, totalX - inner)));
    }

    // ── Painting ────────────────────────────────────────────────────────────

    @Override
    protected void paintOverlay(CgUiPaintContext ctx) {
        super.paintOverlay(ctx);
        ensureMeasured();
        ensureCaretVisible();

        var styleGen = getStyle().getGeneralGroup();
        float fontSize = styleGen.fontSize();
        var box = getRuntimeCache();
        var layout = getTaffyLayout();

        // `text-offset-*` applies to the text, the caret and the selection band together — they are
        // one visual unit, and nudging only the glyphs would leave the caret sitting beside them.
        // Not applied to the scissor below, which clips the field, not its contents.
        float offsetX = styleGen.textOffsetX().resolve(box.getWidth());
        float offsetY = styleGen.textOffsetY().resolve(box.getHeight());

        float originX = textOriginX() - displayOffset + offsetX;

        // Font metrics drive the line box when `line-height` is `normal`, and the caret/selection
        // always. This is the ONLY place either becomes pixels — deliberately not GeneralGroup or the
        // cascade, which a dedicated server runs with no CrystalGraphics on the classpath at all.
        // paintOverlay already resolves the family for the draw call, so nothing new is reached here.
        var metrics = resolveFamily().getLayoutMetrics();

        // CSS's `line-height: normal` — the font's own ascender + descender + lineGap. Used ONLY for
        // vertical centring, i.e. where the line box sits inside the field.
        float lineHeight = LineHeightValue.isNormal(styleGen.lineHeight())
                ? metrics.getLineHeight()
                : fontSize * styleGen.lineHeight();

        // The caret and the selection band are sized to the INK box, not the line box. A line box also
        // carries lineGap — leading *between* lines — which neither a text cursor nor a selection has
        // any business drawing; including it left both hanging past the descender into the field
        // sprite's bottom bevel. Browsers size the caret this way.
        //
        // The selection uses it too, which is a correction: a browser's selection does span the full
        // line box, but only so consecutive lines leave no gap between them. TextField is single-line,
        // so that reason does not apply and the extra lineGap was simply 2px of overhang.
        //
        // Measured on MinecraftRegular at size 10: ascender 8 + descender 2 + lineGap 2 = a 12px line
        // box. So `line-height: normal` alone changes nothing for this font — dropping the lineGap is
        // what takes the caret and selection from 12px to 10px.
        float inkHeight = metrics.getAscender() + metrics.getDescender();
        // Vertically centred in the whole field rather than pinned to the content box's top: a
        // single-line field is almost always shorter than its font's line box once padding is taken
        // out, so top-aligning would push the text against the border.
        float originY = box.getY() + (box.getHeight() - lineHeight) / 2f + offsetY;

        // Clip HORIZONTALLY ONLY. The point of the clip is to stop a long string spilling past the
        // field's sides; clipping vertically to the content box would slice the glyphs, because that
        // box is routinely shorter than the line height (a 14px field with 3px padding leaves 8px for
        // 10px text). The full element height is used instead.
        // Unrounded — pushScissor quantises once, in physical space. See its javadoc.
        ctx.pushScissor(textOriginX(), box.getY(),
                Math.max(0f, layout.contentBoxWidth()),
                Math.max(0f, box.getHeight()));

        // Focused, not merely selected. A blurred field keeps its selection INDICES — browsers do too,
        // so refocusing restores the range — but painting it while something else has focus reads as a
        // second, live cursor. The Blur listener deliberately only commits and resets the blink; do not
        // reach for clearSelection() here, that would lose the range rather than just stop drawing it.
        if (isFocused() && hasSelection()) {
            float from = originX + prefixWidths[getSelectionStart()];
            float to = originX + prefixWidths[getSelectionEnd()];
            ctx.fillRect(from, originY, to - from, inkHeight, styleGen.selectionColor());
        }

        // A PLACEHOLDER IS A HINT, AND ONLY WHILE YOU ARE TYPING INTO IT.
        //
        // Drawn in the text colour it is indistinguishable from content: two find boxes reading "Search"
        // and "Replace" look like a query somebody typed, and the bar looks busy when it is empty. It is
        // dimmed, and shown only while the field has focus — which is when "what goes here?" is a question
        // the reader is actually asking.
        boolean showingPlaceholder = text.isEmpty();
        // SKIP THE DRAW, NEVER THE METHOD. An early `return` here left the scissor this pass had pushed
        // un-popped -- "Unbalanced scissor stack after the main paint pass: depth 1, expected 0", and the
        // whole window flickering before it threw. Anything that decides not to paint has to fall through
        // to the same teardown as anything that does.
        String shown = showingPlaceholder && !isFocused() ? "" : showingPlaceholder ? placeholder : text;
        if (!shown.isEmpty()) {
            ctx.text().draw()
                    .at(originX, originY)
                    .text(shown)
                    .color(showingPlaceholder ? dim(styleGen.color()) : styleGen.color())
                    .family(resolveFamily())
                    .submit();
        }

        // Caret only while focused, never alongside a selection, and only in the visible half of the
        // blink. No invalidation needed: the tree repaints every frame and tickAnimations runs first.
        if (isFocused() && !hasSelection() && caretVisible) {
            float x = originX + prefixWidths[caret];
            // originY, not a re-centred value: the glyphs are drawn `.at(originY)` and CrystalGraphics
            // puts the baseline at originY + ascender, so [originY, originY + ascender + descender] is
            // exactly [baseline - ascender, baseline + descender] — the caret starts and ends where the
            // glyphs do, for any `line-height`.
            // ITS OWN COLOUR IF IT HAS ONE. Zero means unset and the caret follows the text, which is the
            // web's `caret-color: auto` and was the only behaviour before. The distinction earns its keep
            // wherever the text is recoloured to say something about ITSELF -- a search box reds its query
            // when nothing matches, and a red caret reads as the caret being wrong.
            int caretColor = styleGen.caretColor();
            ctx.fillRect(x, originY, styleGen.caretWidth(), inkHeight,
                    caretColor == 0 ? styleGen.color() : caretColor);
        }

        ctx.popScissor();
    }
}
