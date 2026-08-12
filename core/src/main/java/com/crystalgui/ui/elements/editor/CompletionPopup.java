package com.crystalgui.ui.elements.editor;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.core.property.ObservableList;
import com.crystalgui.core.search.SearchMatch;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.text.lang.CompletionItem;
import com.crystalgui.text.lang.SymbolModifier;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Popover;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.list.ListRenderer;
import com.crystalgui.ui.elements.list.ListView;
import com.crystalgui.ui.elements.list.SelectionMode;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.text.TextRange;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

/**
 * The completion list, drawn at the caret — §18.5.
 *
 * <h3>Anatomy, taken from IntelliJ's own popup</h3>
 *
 * <pre>
 *   [icon]  labelWithMatchedCharactersBanded ............... detail
 * </pre>
 *
 * <p>Left to right: a kind icon, the label with the matched characters banded, and the detail
 * <b>right-aligned</b> at the far edge — a return type for a method, a package for a type. IntelliJ splits
 * that right-hand column into a "tail" abutting the name and a "type" at the edge; here the tail is simply
 * part of the {@link CompletionItem#label()}, which is what {@link CompletionItem#filterKey()} exists for.
 * A method shows {@code println(int x)}, filters on {@code println}, and inserts {@code println(} — the
 * four-field design already carries the split, so a fifth text field would be a second way to say it.</p>
 *
 * <h3>It never takes focus, and that is the whole interaction model</h3>
 *
 * <p>The <em>document</em> is the input. Focus stays in the editor, the popup is a view of a selection, and
 * arrows move the selection without moving focus — the ARIA combobox rule {@code QuickPick} already
 * implements and {@code ListView.restoreFocusIfRealised} already names. Letting the list take focus would
 * take the caret out of the text on the first arrow press, which is the one thing a completion popup must
 * never do.</p>
 *
 * <p>So this is {@link Popover.Mode#MANUAL}, not {@code AUTO}: light dismiss would close it on the very
 * press that put the caret somewhere, and Escape is routed by the editor, which has to decide between
 * "close the popup" and "clear the find bar" itself.</p>
 *
 * <h3>Placement is {@code Popover}'s, anchored to the word rather than the caret</h3>
 *
 * <p>Anchoring to the replacement start keeps the list still while the prefix is typed. Anchored to the
 * caret it would step right one character per keystroke, which looks like the list is running away from
 * the word it is completing.</p>
 */
public final class CompletionPopup extends Popover {

    public static final String POPUP_CLASS = "__completion__";
    public static final String ROW_CLASS = "__completion-row__";
    public static final String ICON_CLASS = "__completion-icon__";
    public static final String STATIC_MARK_CLASS = "__completion-mark-static__";
    public static final String FINAL_MARK_CLASS = "__completion-mark-final__";
    public static final String LABEL_CLASS = "__completion-label__";
    public static final String PARAMS_CLASS = "__completion-params__";
    public static final String DETAIL_CLASS = "__completion-detail__";
    public static final String DEPRECATED_CLASS = "__completion-deprecated__";
    public static final String HINT_CLASS = "__completion-hint__";

    /**
     * A key that accepts, and the word the strip uses for it.
     *
     * <h3>One table, read by both halves</h3>
     *
     * <p>The strip at the bottom of the popup exists to tell you which key does what, so it must be built
     * from the same list the key handler consults — a strip written as a literal string is a promise that
     * stops being kept the first time a binding moves, and nothing fails when it does.</p>
     *
     * <p>These are the popup's <b>own</b> keys rather than registered commands, so there is nothing for
     * {@code Keymap.acceleratorFor} to resolve and the usual "read the accelerator from the keymap" rule
     * does not apply here. Stating that is better than leaving the next reader to wonder why it was
     * skipped.</p>
     */
    public record AcceptKey(int keyCode, String keyName, String verb, boolean replaces) {
    }

    /** Enter inserts, Tab replaces — IntelliJ's pairing, and VS Code's. */
    public static final List<AcceptKey> ACCEPT_KEYS = List.of(
            new AcceptKey(CgKeyCodes.KEY_RETURN, "Enter", "insert", false),
            new AcceptKey(CgKeyCodes.KEY_TAB, "Tab", "replace", true));

    /** {@code Press Enter to insert, Tab to replace} — derived, never written out. */
    static String hintText() {
        StringBuilder built = new StringBuilder("Press ");
        for (int i = 0; i < ACCEPT_KEYS.size(); i++) {
            if (i > 0) built.append(", ");
            AcceptKey key = ACCEPT_KEYS.get(i);
            built.append(key.keyName()).append(" to ").append(key.verb());
        }
        return built.toString();
    }

    /** The highlight name a stylesheet targets with {@code ::highlight(completion-match)}. */
    public static final String MATCH_HIGHLIGHT = "completion-match";

    /**
     * Row height in logical px. <b>Paired with {@code .__completion-row__ { height }} in the sheet</b>, for
     * the reason {@code QuickPick} states: a virtualised list needs the number in Java to turn an index into
     * a scroll offset, and it cannot read the cascade.
     */
    private static final float ROW_HEIGHT = 16f;

    /**
     * How many rows before it scrolls.
     *
     * <p>IntelliJ shows about nineteen. Eleven was chosen when the rows were taller and it made the popup
     * shorter than its own scrollbar was useful for — you could see a sixth of a member list at a time.</p>
     */
    private static final int MAX_VISIBLE_ROWS = 19;

    /** Used until the probe has measured, and as the floor afterwards. */
    private static final float MIN_WIDTH = 260f;

    /** Past this a signature is truncated rather than allowed to span the window. */
    private static final float MAX_WIDTH = 620f;

    /** Room for the scrollbar and a little air at the right edge, added to whatever the probe measures. */
    private static final float WIDTH_SLACK = 24f;

    /** Kept off the window edges when the popup would otherwise hang past them. */
    private static final float MARGIN = 8f;

    /** The bottom strip's height, paired with {@code .__completion-hint__} in the sheet — the flip-vs-drop
     * decision in {@link #reposition} is computed from the popup's total height and cannot read the cascade. */
    private static final float HINT_HEIGHT = 16f;

    private final ObservableList<CompletionSession.Row> rows = new ObservableList<>();
    private final ListView<CompletionSession.Row> list = new ListView<>(rows);
    private final RowRenderer renderer = new RowRenderer();

    /**
     * An off-list row, laid out but never painted, bound to the longest item so its natural width can be
     * read back.
     *
     * <h3>Why a probe rather than measuring the strings</h3>
     *
     * <p>The row's width is glyph advances through a resolved font stack, and <b>synthetic bold is wider
     * than the regular face</b> — so any measurement not taken on the painting path is short by exactly the
     * emphasis, and truncates a few pixels early. {@code UIText.measureEllipsised} already paid for this
     * once. Binding the real template and letting the layout engine size it means there is one measurement
     * path, not two that can disagree.</p>
     *
     * <p><b>Absolutely positioned</b>, so it takes part in layout without contributing to the popup's own
     * box — an in-flow probe would make the popup as tall as its rows plus one.</p>
     */
    private UIElement widthProbe;

    /** The bottom strip. See {@link #ACCEPT_KEYS} — its text is derived from the same list the editor reads. */
    private final UIText hint = new UIText(hintText());

    /** What the probe last measured, so the width only moves when the content does. */
    private float measuredWidth;

    @Nullable
    private CompletionSession session;

    /** Where the popup wants to sit — the word's screen position, written by the editor each frame. */
    private float anchorX;
    private float anchorY;
    private float anchorLineHeight;

    public CompletionPopup() {
        setMode(Mode.MANUAL);
        // NEVER focusable, and neither is the list. See the class note -- this is a view of a selection the
        // editor owns, not a thing you tab into.
        setFocusPolicy(FocusPolicy.NONE);
        addClass(POPUP_CLASS);

        list.setSelectionMode(SelectionMode.SINGLE);
        list.setItemHeight(ROW_HEIGHT);
        list.setFocusPolicy(FocusPolicy.NONE);
        list.setRenderer(renderer);
        addInternalChild(list);

        hint.addClass(HINT_CLASS);
        hint.setHitTest(false);
        addInternalChild(hint);

        widthProbe = renderer.createTemplate();
        widthProbe.setHitTest(false);
        // Laid out, never seen: opacity rather than display, because display:none is skipped by Taffy
        // entirely and a box that is not laid out cannot be measured.
        StyleGroup.importantPipeline(widthProbe.getStyle().getLayoutGroup(),
                l -> l.positionType(dev.vfyjxf.taffy.style.TaffyPosition.ABSOLUTE));
        widthProbe.generalStyle(g -> g.opacity(0f));
        addInternalChild(widthProbe);

        // A click still accepts. The press lands on the row, focus never moves (the list refuses it), and
        // the editor's caret is untouched -- which is why this can be wired without fighting the rule above.
        list.onRowActivated.connect(index -> {
            if (session == null) return;
            session.setSelectedIndex(index);
            session.accept();
        });
    }

    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    public ListView<CompletionSession.Row> rowList() {
        return list;
    }

    /** The rows on screen, in rank order — the surface a test asserts on. */
    public List<CompletionSession.Row> visibleRows() {
        return rows.asUnmodifiableList();
    }

    // ── Showing ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Attaches to the window and follows {@code session} until it closes.
     *
     * <p>The popup subscribes rather than being pushed to, so a late answer landing in the session reaches
     * the screen without the editor having to know an answer arrived.</p>
     */
    public void attach(UIWindow window, CompletionSession newSession) {
        this.session = newSession;
        if (getParent() == null) window.addOverlay(this, null);
        showAt(anchorX, anchorY, null);
        newSession.onChanged.connect(this::refresh);
        newSession.onClosed.connect(this::detach);
        refresh();
    }

    public void detach() {
        session = null;
        rows.clear();
        if (isOpen()) hide();
    }

    /** Told where the completed word is on screen, in the window's coordinates. */
    public void setAnchor(float x, float y, float lineHeight) {
        this.anchorX = x;
        this.anchorY = y;
        this.anchorLineHeight = lineHeight;
    }

    /**
     * Where the popup believes the completed word is — the only observable of the placement path.
     *
     * <p>Worth exposing because everything downstream of it is a style write that a test cannot read back
     * meaningfully, and because the anchor is where the two coordinate spaces meet: a popup at the right
     * offset in the wrong space looks plausible on screen and is wrong by exactly {@code uiScale}.</p>
     */
    public float anchorX() {
        return anchorX;
    }

    public float anchorY() {
        return anchorY;
    }

    private void refresh() {
        if (session == null) return;
        rows.clear();
        for (CompletionSession.Row row : session.visibleRows()) rows.add(row);
        sizeToContent(rows.size());
        bindWidthProbe(session.visibleRows());
        int selected = session.selectedIndex();
        if (selected >= 0 && selected < rows.size()) {
            list.setFocusedIndex(selected);
            list.select(selected);
            list.scrollToIndex(selected);
            // AND THEN PIN IT, when the selection is on the first page.
            //
            // setFocusedIndex defers a focus restore that scrolls the row into view, and on the frame a
            // popup opens there is no laid-out viewport to compute that against -- so the list came to rest
            // exactly one row down, which hides the SELECTED row off the top. The symptom is not "the list
            // is scrolled": it is a popup with no visible selection at all, opening on the second-best
            // match. QuickPick.refresh carries the same two lines for the same reason, measured there at
            // scrollTop=22 in a viewport whose only valid offset was 0.
            if (selected < MAX_VISIBLE_ROWS) list.setScrollImmediate(0f, 0f);
        } else {
            list.setFocusedIndex(-1);
            list.clearSelection();
        }
    }

    /**
     * Height from the row count, width fixed.
     *
     * <p>Not {@code flex-grow}, for the reason {@code QuickPick} records: a popover's height comes from its
     * own content, so there is no free space to distribute and a growing child resolves to zero — a popup
     * with nothing visible in it.</p>
     */
    private void sizeToContent(int rowCount) {
        float height = Math.min(rowCount, MAX_VISIBLE_ROWS) * ROW_HEIGHT;
        StyleGroup.importantPipeline(list.getStyle().getLayoutGroup(), l -> l.height(height));
    }

    /**
     * Points the probe at the row most likely to be the widest.
     *
     * <p>Chosen by <b>character count</b>, which is a proxy and is stated as one: in a proportional font
     * {@code IIIIIIII} is narrower than {@code mmmm}, so the pick can be off by a row. The <em>measurement</em>
     * is real either way, and {@link #WIDTH_SLACK} covers the difference — the alternative is measuring
     * every item on every keystroke to choose which one to measure.</p>
     */
    private void bindWidthProbe(List<CompletionSession.Row> candidates) {
        if (widthProbe == null || candidates.isEmpty()) return;
        CompletionSession.Row widest = null;
        int longest = -1;
        for (CompletionSession.Row row : candidates) {
            String detail = row.item().detail();
            int length = row.item().label().length() + (detail == null ? 0 : detail.length());
            if (length > longest) {
                longest = length;
                widest = row;
            }
        }
        if (widest != null) renderer.bind(widest, -1, widthProbe);
    }

    /**
     * Below the word, flipped above when there is no room — the same rule every anchored popup follows.
     *
     * <p>Written here rather than through {@code moveTo}, so this stays the single writer of
     * {@code left}/{@code top} and keeps being re-run as the window resizes. {@code moveTo} sets
     * {@code freelyPositioned}, which silences placement permanently.</p>
     */
    @Override
    public void reposition() {
        if (!isOpen()) return;
        UIWindow window = getAttachedWindow();
        if (window == null) return;

        // The probe's natural width, read a frame after it was bound. One frame late is invisible; a
        // synchronous read would be of the PREVIOUS content, which is worse and looks the same.
        if (widthProbe != null) {
            float probed = widthProbe.getRuntimeCache().getWidth();
            if (Float.isFinite(probed) && probed > 0f) measuredWidth = probed + WIDTH_SLACK;
        }
        float wanted = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, measuredWidth));
        float width = Math.max(0f, Math.min(wanted, window.getScreenWidth() - 2f * MARGIN));
        float height = Math.min(Math.max(rows.size(), 1), MAX_VISIBLE_ROWS) * ROW_HEIGHT + HINT_HEIGHT;

        float left = Math.max(MARGIN, Math.min(anchorX, window.getScreenWidth() - width - MARGIN));
        float below = anchorY + anchorLineHeight;
        // FLIP RATHER THAN CLAMP. Clamping would slide the list up over the line being typed, hiding the
        // very text the completion is about -- the one thing that must stay visible.
        float top = below + height + MARGIN > window.getScreenHeight()
                ? Math.max(MARGIN, anchorY - height)
                : below;

        StyleGroup.importantPipeline(getStyle().getLayoutGroup(),
                l -> l.width(width).left(left).top(top));
    }

    // ── Rows ────────────────────────────────────────────────────────────────────────────────────

    /** Icon, banded label, right-aligned detail. See the class note for where the anatomy comes from. */
    private static final class RowRenderer implements ListRenderer<CompletionSession.Row> {

        @Override
        public UIElement createTemplate() {
            // BUILT HERE, never in bind(). An element created during bind lands after that frame's layout
            // pass -- the trap the command palette's key chips and the editor's gutter arrows each paid for.
            Row row = new Row();
            row.addClass(ROW_CLASS);

            row.icon.addClass(ICON_CLASS);
            row.icon.setHitTest(false);
            row.addChild(row.icon);
            // MODIFIER MARKS ARE FULL-SIZE LAYERS OVER THE ICON, not small badges in a corner box.
            // JetBrains draws each mark on its own 16x16 canvas with the glyph already in the right corner
            // -- staticMark bottom-left, finalMark top-left -- so they compose by being stacked at the same
            // size, and they can both show at once because they occupy different corners. Scaling one into
            // a 9px box instead re-does positioning the artwork already did, badly.
            row.staticMark.addClass(STATIC_MARK_CLASS);
            row.staticMark.setHitTest(false);
            row.icon.addChild(row.staticMark);
            row.finalMark.addClass(FINAL_MARK_CLASS);
            row.finalMark.setHitTest(false);
            row.icon.addChild(row.finalMark);

            row.label.addClass(LABEL_CLASS);
            row.label.setHitTest(false);
            row.addChild(row.label);

            row.params.addClass(PARAMS_CLASS);
            row.params.setHitTest(false);
            row.addChild(row.params);

            // The spacer is what right-aligns the detail: it is the only thing in the row allowed to grow.
            row.spacer.layout(l -> l.width(0f).flexGrow(1f));
            row.spacer.setHitTest(false);
            row.addChild(row.spacer);

            row.detail.addClass(DETAIL_CLASS);
            row.detail.setHitTest(false);
            row.addChild(row.detail);
            return row;
        }

        @Override
        public void bind(CompletionSession.Row value, int index, UIElement template) {
            Row row = (Row) template;
            CompletionItem item = value.item();

            // SWAPPED, not added -- a template is a different row every time the view reuses it, so a
            // kind class left behind from the last binding would join the new one and the cascade would
            // resolve whichever it preferred. Same rule ProjectFileTree.swapPrefixedClass states.
            swapPrefixed(row.icon, KIND_CLASS_PREFIX,
                    KIND_CLASS_PREFIX + (item.kind() == null ? "unknown"
                            : item.kind().name().toLowerCase(java.util.Locale.ROOT)));
            // KIND AND MODIFIER ARE ORTHOGONAL, so abstract is a second class rather than a second kind --
            // an abstract method and a concrete one are the same kind and draw differently. Folding it into
            // SymbolKind would double every entry in that enum for one bit.
            swapPrefixed(row.icon, MODIFIER_CLASS_PREFIX,
                    item.is(SymbolModifier.ABSTRACT) ? MODIFIER_CLASS_PREFIX + "abstract" : null);
            row.staticMark.setDisplayed(item.is(SymbolModifier.STATIC));
            row.finalMark.setDisplayed(item.is(SymbolModifier.FINAL));

            // THE NAME AND ITS PARAMETER LIST ARE TWO ELEMENTS, because IntelliJ draws them in two
            // weights: the name bright and bold, the parameters dimmed. One UIText cannot say that, and
            // the split is free -- filterText is ALREADY the bare name, so the boundary is known exactly
            // rather than found by hunting for a bracket. A label that does not start with its own filter
            // text (a keyword, an unimported type) simply has no parameter half, which is correct.
            String whole = item.label();
            String name = item.filterKey();
            boolean splittable = !name.isEmpty() && whole.startsWith(name) && whole.length() > name.length();
            row.label.setText(splittable ? name : whole);
            row.params.setText(splittable ? whole.substring(name.length()) : "");
            row.detail.setText(item.detail() == null ? "" : item.detail());

            if (item.deprecated()) row.addClass(DEPRECATED_CLASS);
            else row.removeClass(DEPRECATED_CLASS);

            applyMatch(row.label, value.match());
        }

        /**
         * Bands the characters that matched — and clears them when nothing did.
         *
         * <p>Cleared on the empty path too, because rows are pooled: a leftover band paints over whatever
         * label lands on the element next. That is not a stale style but a mark on the wrong text entirely,
         * and it is the exact defect {@code UIText.highlightBandCount} was added to make observable.</p>
         */
        private static void applyMatch(UIText text, @Nullable SearchMatch match) {
            if (match == null || match.ranges().isEmpty()) {
                text.highlights().remove(MATCH_HIGHLIGHT);
                return;
            }
            List<TextRange> ranges = new ArrayList<>(match.ranges().size());
            for (SearchMatch.Range range : match.ranges()) {
                ranges.add(new TextRange(range.start(), range.end()));
            }
            text.highlights().set(MATCH_HIGHLIGHT, ranges);
        }

        @Override
        public void unbind(UIElement template) {
            Row row = (Row) template;
            row.label.highlights().remove(MATCH_HIGHLIGHT);
        }

        /**
         * Puts exactly one {@code prefix}-class on {@code element}, or none.
         *
         * <p>Swap rather than add: a pooled row that drew a method and is reused for a field would
         * otherwise carry both classes and the cascade would resolve whichever it preferred — which reads
         * as a random icon rather than as a stale class.</p>
         */
        private static void swapPrefixed(UIElement element, String prefix, @Nullable String wanted) {
            for (String name : new ArrayList<>(element.getClasses())) {
                if (name.startsWith(prefix) && !name.equals(wanted)) element.removeClass(name);
            }
            if (wanted != null) element.addClass(wanted);
        }

        private static final String KIND_CLASS_PREFIX = "completion-kind-";
        private static final String MODIFIER_CLASS_PREFIX = "completion-mod-";
    }

    /** The row element, holding its slots so {@code bind} never searches for them. */
    private static final class Row extends UIElement {
        /** A box with a background, not a glyph -- the drawing comes from the cascade. */
        final UIElement icon = new UIElement();
        /** Modifier overlays, parented to the icon so they follow it. */
        final UIElement staticMark = new UIElement();
        final UIElement finalMark = new UIElement();
        final UIText label = new UIText("");
        /** The dimmed parameter list, when the label has one. */
        final UIText params = new UIText("");
        final UIElement spacer = new UIElement();
        final UIText detail = new UIText("");
    }
}
