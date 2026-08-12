package com.crystalgui.ui.elements.editor;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.core.property.ObservableList;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.search.SearchMatch;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.text.lang.CompletionItem;
import com.crystalgui.text.lang.SymbolModifier;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.Menu;
import com.crystalgui.ui.elements.MenuItem;
import com.crystalgui.ui.elements.Popover;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.elements.list.ListRenderer;
import com.crystalgui.ui.elements.list.ListView;
import com.crystalgui.ui.elements.list.SelectionMode;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.input.UIDragController;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.text.TextRange;
import org.joml.Vector2f;
import org.joml.Vector3f;

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
    public static final String HINT_TEXT_CLASS = "__completion-hint-text__";
    public static final String OPTIONS_CLASS = "__completion-options__";
    public static final String GRIP_CLASS = "__completion-grip__";

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

    /** Two rows and the strip — below this the popup is a box with nothing readable in it. */
    private static final float MIN_HEIGHT = 2f * ROW_HEIGHT + HINT_HEIGHT;

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
    private final UIElement hint = new UIElement();

    /** The corner grab, at the strip's right-hand end. */
    private final UIElement grip = new UIElement();
    private final UIText hintLabel = new UIText(hintText());

    /**
     * The overflow button, and the reason it exists at all.
     *
     * <p>IntelliJ puts two marks at this end of the strip: a lightbulb and a kebab. <b>Only the kebab is
     * here</b>, because only it has something to do — its menu carries "Sort by Name", which is a real
     * toggle we can honour. The lightbulb offers quick-fixes, and we have none; a mark that does nothing
     * when pressed is worse than an absent one, so it stays absent until there is something behind it.</p>
     *
     * <p><b>An icon rather than a glyph.</b> The obvious spelling is a {@code U+22EE} vertical ellipsis in
     * the label, and the bundled {@code MinecraftRegular.otf} does not have one \u2014 it draws tofu, exactly as
     * a {@code U+2026} horizontal ellipsis does, which {@code UIText} already carries a whole fallback for.
     * Three dots are not creative work, so the icon is ours and needs no attribution; it is drawn in
     * {@code currentColor}, so it inherits this button's hover state like every other chrome mark.</p>
     */
    private final Button options = new Button("");

    /** What the probe last measured, so the width only moves when the content does. */
    private float measuredWidth;

    /**
     * The size the user dragged this popup to, or {@code -1} while it is still automatic.
     *
     * <h3>Why the resize is driven here rather than by {@code resize:}</h3>
     *
     * <p>{@code UIResizer} writes at <b>INLINE</b> and {@link #reposition} writes width at
     * <b>IMPORTANT</b>, which beats it — so the grabber could change the height (which nothing else wrote)
     * and could never change the width at all. Half of a resize working is worse than none: it reads as a
     * broken widget rather than an unsupported gesture.</p>
     *
     * <p>Owning the drag removes the origin fight and makes the latch honest at the same time — it is set
     * from the pointer having <em>moved</em>, not from a press, which is the rule {@code NavigatorView}
     * shipped backwards once.</p>
     */
    private float userWidth = -1f;
    private float userHeight = -1f;

    private boolean isUserSized() {
        return userWidth > 0f && userHeight > 0f;
    }

    @Nullable
    private CompletionSession session;

    /** Where the popup wants to sit — the word's screen position, written by the editor each frame. */
    private float anchorX;
    private float anchorY;
    private float anchorLineHeight;

    /**
     * The last position {@link #reposition} resolved, and the origin a move-drag adds its delta to.
     *
     * <p><b>Not {@code getWindowX()}.</b> A promoted element's Taffy parent is the ROOT while its DOM parent
     * is whatever hosts it, so a walk up the DOM chain reading Taffy locations counts the host's offset on
     * top of a location that is already root-relative. The popup jumped by exactly that amount on the first
     * mouse-move and then tracked correctly, which reads as the drag "snapping to the corner" rather than
     * as a coordinate-space error. Remembering what we wrote sidesteps the question entirely.</p>
     */
    private float placedLeft;
    private float placedTop;

    /**
     * Set once the strip has actually dragged the popup somewhere.
     *
     * <p>{@link #reposition} runs every frame from the placement ticker and writes {@code left}/{@code top}
     * at IMPORTANT, so without standing down it puts the popup straight back at the caret on the very next
     * frame — a move that visibly happens and is instantly undone. {@code Popover.moveTo} sets
     * {@code freelyPositioned} for exactly this, but this class <b>overrides</b> {@code reposition} and so
     * never consults it; the override is what {@code QuickPick} recommends for staying live through a
     * resize, and this is the half of that trade it has to pay for itself.</p>
     */
    private boolean userMoved;

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
        installStripDrag();
        installGrip();
        hintLabel.addClass(HINT_TEXT_CLASS);
        hintLabel.setHitTest(false);
        hint.addChild(hintLabel);

        UIElement hintSpacer = new UIElement();
        hintSpacer.layout(l -> l.width(0f).flexGrow(1f));
        hintSpacer.setHitTest(false);
        hint.addChild(hintSpacer);

        options.addClass(OPTIONS_CLASS);
        // FocusPolicy.NONE, like everything else in here: pressing it must not take the caret out of the
        // document, which is the one thing this popup may never do.
        options.setFocusPolicy(FocusPolicy.NONE);
        options.onPressed.connect(this::openOptionsMenu);
        hint.addChild(options);

        // LAST, so it is the bottom-RIGHT corner. Added before the options button it sat inside the strip
        // with the kebab to its right, which is not a corner and does not read as a grab.
        grip.addClass(GRIP_CLASS);
        hint.addChild(grip);
        addInternalChild(hint);

        widthProbe = renderer.createTemplate();
        widthProbe.setHitTest(false);
        // Laid out, never seen: opacity rather than display, because display:none is skipped by Taffy
        // entirely and a box that is not laid out cannot be measured.
        StyleGroup.importantPipeline(widthProbe.getStyle().getLayoutGroup(),
                l -> l.positionType(dev.vfyjxf.taffy.style.TaffyPosition.ABSOLUTE));
        widthProbe.generalStyle(g -> g.opacity(0f));
        addInternalChild(widthProbe);

        // ListView raises onRowActivated from ENTER, and the popup never holds focus, so it never fired
        // at all. A click reaches the row itself; see RowRenderer.createTemplate.
        list.onRowActivated.connect(onRowClicked::emit);
    }

    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    public ListView<CompletionSession.Row> rowList() {
        return list;
    }

    /**
     * A row was clicked, carrying its index.
     *
     * <p>Raised rather than handled here, because accepting is more than the edit: the caret has to be
     * placed and <b>focus has to go back to the editor</b>, which this widget deliberately does not hold
     * and cannot restore. {@code TextEditor} owns both, so it owns the acceptance — the same reason the key
     * handling lives there too.</p>
     */
    public final Signal.Value<Integer> onRowClicked = new Signal.Value<>();

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
        resetUserGeometry();
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

    /**
     * Forgets a dragged size and position.
     *
     * <p>Called when a session opens, so a popup dragged somewhere for one list does not pin every later
     * one to that spot — a completion list is anchored to a word, and the next word is somewhere else.
     * IntelliJ keeps the SIZE across popups and not the position; ours keeps neither yet, which is the
     * conservative half and is easy to relax once there is a preference to store it in.</p>
     */
    private void resetUserGeometry() {
        userMoved = false;
        if (!isUserSized()) return;
        userWidth = -1f;
        userHeight = -1f;
        // The fill idiom has to be undone too, or the list keeps growing into a box that is once again
        // sized to its content -- which resolves to zero and shows an empty popup.
        StyleGroup.importantPipeline(list.getStyle().getLayoutGroup(), l -> l.flexGrow(0f));
        StyleGroup.importantPipeline(getStyle().getLayoutGroup(), l -> l.heightAuto());
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

    /**
     * Dragging the bottom strip moves the popup.
     *
     * <h3>The drag's source is the popup's PARENT, never the popup</h3>
     *
     * <p>Every {@code DragListener} coordinate is converted through the source's own transform, so a drag
     * sourced on something the drag itself moves measures its deltas in a frame that is moving with the
     * cursor — the popup would accelerate away rather than follow. The canvas pan carries the same warning
     * for the same reason. The parent is the overlay host and stays put.</p>
     *
     * <p>The press point is converted into the parent's space through {@code getWindowX/Y} rather than
     * being passed along raw: the event arrives in the <em>strip's</em> coordinates, and handing those to a
     * drag sourced on the parent offsets every delta by wherever the strip happens to sit.</p>
     */
    private void installStripDrag() {
        hint.events.getGroup(MouseEvent.Down.class).attachListener((element, event) -> {
            // The strip's own controls come first: a press on the options button must open the menu, not
            // start dragging the window. Filtering on the target is what SplitView's divider does.
            if (event.getTarget() != hint) return;
            UIWindow window = getAttachedWindow();
            UIElement host = getParent();
            if (window == null || host == null) return;

            float startLeft = placedLeft;
            float startTop = placedTop;
            float[] press = pressInHostSpace(hint, host, event.getPosition().x(), event.getPosition().y());
            float pressX = press[0];
            float pressY = press[1];

            window.getInputHandler().getDragController().startDrag(host, pressX, pressY,
                    new UIDragController.DragListener() {
                        @Override
                        public void onDragUpdate(float mx, float my, float sx, float sy,
                                                 float dx, float dy) {
                            // Latched by MOVEMENT, like the resize: a press that goes nowhere must leave
                            // the popup anchored to its word.
                            if (dx == 0f && dy == 0f) return;
                            userMoved = true;
                            placedLeft = startLeft + dx;
                            placedTop = startTop + dy;
                            StyleGroup.importantPipeline(getStyle().getLayoutGroup(),
                                    l -> l.left(placedLeft).top(placedTop));
                        }
                    });
            event.stopPropagation();
        }, false, false);
    }

    /**
     * Dragging the grip resizes in <b>both</b> axes.
     *
     * <p>Sourced on the parent for the same reason the strip drag is: a drag sourced on something the drag
     * itself resizes measures its deltas in a frame that is changing under it.</p>
     */
    private void installGrip() {
        grip.events.getGroup(MouseEvent.Down.class).attachListener((element, event) -> {
            UIWindow window = getAttachedWindow();
            UIElement host = getParent();
            if (window == null || host == null) return;

            float startWidth = getRuntimeCache().getWidth();
            float startHeight = getRuntimeCache().getHeight();
            float[] press = pressInHostSpace(grip, host, event.getPosition().x(), event.getPosition().y());
            float pressX = press[0];
            float pressY = press[1];

            window.getInputHandler().getDragController().startDrag(host, pressX, pressY,
                    new UIDragController.DragListener() {
                        @Override
                        public void onDragUpdate(float mx, float my, float sx, float sy,
                                                 float dx, float dy) {
                            // Latched by MOVEMENT: a press that drags nowhere leaves the popup automatic.
                            if (dx == 0f && dy == 0f) return;
                            userWidth = Math.max(MIN_WIDTH, startWidth + dx);
                            userHeight = Math.max(MIN_HEIGHT, startHeight + dy);
                            applyUserSize();
                        }
                    });
            event.stopPropagation();
        }, false, false);
    }

    /**
     * A press inside {@code from}, expressed in {@code host}'s coordinates.
     *
     * <p>Through the <b>transform</b> chain rather than the layout chain, because this popup is promoted:
     * its Taffy parent is the root while its DOM parent is the overlay host, so summing layout offsets up
     * the DOM chain double-counts. The transform chain is the single definition of where an element
     * actually is on screen — the same one hit-testing uses, which is what makes it the right one for a
     * pointer position.</p>
     *
     * <p>It matters because {@code UIDragController} reports deltas from the start point it was given: a
     * start in the wrong space offsets every subsequent delta by a constant, so the popup jumps once and
     * then tracks perfectly. That is a much harder symptom to read than one that never works.</p>
     */
    private static float[] pressInHostSpace(UIElement from, UIElement host, float localX, float localY) {
        Vector3f world = from.getRuntimeCache().localToWorld.get()
                .transformPosition(new Vector3f(localX, localY, 0f));
        Vector2f inHost = host.screenToLocal(world.x, world.y);
        return new float[] { inHost.x, inHost.y };
    }

    /**
     * Writes a dragged size, and hands the leftover space to the list.
     *
     * <p>The second half is the one that was missing: growing the popup without growing the list left a
     * taller box with the same nineteen rows in it and empty space underneath — the resize appeared to do
     * nothing but add margin. The list takes {@code height: 0; flex-grow: 1}, which is the fill idiom, and
     * it works here <em>because</em> the popup now has an explicit height: a popover sized by its own
     * content has no free space to distribute, which is the trap {@code QuickPick.sizeListToContent}
     * documents.</p>
     */
    private void applyUserSize() {
        StyleGroup.importantPipeline(getStyle().getLayoutGroup(),
                l -> l.width(userWidth).height(userHeight));
        StyleGroup.importantPipeline(list.getStyle().getLayoutGroup(),
                l -> l.height(0f).flexGrow(1f));
    }

    /**
     * The sort menu — IntelliJ's own, which offers exactly this one toggle.
     *
     * <p>Built fresh each time rather than retained, so its checked state cannot drift from the preference
     * it displays. A menu is a handful of elements and this opens on a deliberate press, not per frame.</p>
     */
    private void openOptionsMenu() {
        UIWindow window = getAttachedWindow();
        if (window == null) return;
        Menu menu = new Menu();
        MenuItem sortByName = menu.addCheckableItem("Sort by Name");
        sortByName.setSelected(CompletionRanking.isSortByName());
        sortByName.onPressed.connect(() -> {
            CompletionRanking.setSortByName(!CompletionRanking.isSortByName());
            // The live list re-sorts without re-querying -- the items have not changed, only their order.
            if (session != null) session.reorder();
        });
        window.addOverlay(menu, this);
        menu.showAt(options.getWindowX(), options.getWindowY() + HINT_HEIGHT, options);
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
        if (isUserSized()) return;
        float height = Math.min(rowCount, MAX_VISIBLE_ROWS) * ROW_HEIGHT;
        StyleGroup.importantPipeline(list.getStyle().getLayoutGroup(), l -> l.height(height));
    }

    /**
     * Points the probe at the row most likely to be the widest.
     *
     * <p>Chosen by <b>character count</b>, which is a proxy and is stated as one: in a proportional font
     * {@code IIIIIIII} is narrower than {@code mmmm}, so the pick can be off by a row. The
     * <em>measurement</em> is real either way, and {@link #WIDTH_SLACK} covers the difference — the
     * alternative is measuring every item on every keystroke to decide which one to measure.</p>
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
        // THE USER OWNS WHAT THEY HAVE DRAGGED. Either latch stops this method writing anything, because
        // both halves are written by the same IMPORTANT pipeline and this one runs every frame -- it would
        // win every argument it is allowed to have.
        if (isUserSized() || userMoved) return;
        float wanted = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, measuredWidth));
        float width = Math.max(0f, Math.min(wanted, window.getScreenWidth() - 2f * MARGIN));
        float height = Math.min(Math.max(rows.size(), 1), MAX_VISIBLE_ROWS) * ROW_HEIGHT + HINT_HEIGHT;

        float left = Math.max(MARGIN, Math.min(anchorX, window.getScreenWidth() - width - MARGIN));
        // FIT, then flip, then SHRINK — and the third step is the one that was missing.
        //
        // Flip-or-clamp was fine at eleven rows and wrong at nineteen: a 320px popup near the bottom of the
        // window has room on neither side, so the clamp pinned it to y=8 and it opened in the top-left
        // corner of the screen while the caret was three hundred lines further down. It read as the anchor
        // having been lost entirely, rather than as a list that did not fit.
        //
        // Capping the height to whichever side has more room keeps it attached to the word, which is the
        // property that actually matters: a shorter list is a small cost, and an unanchored one is not a
        // completion popup at all.
        float below = anchorY + anchorLineHeight;
        float roomBelow = window.getScreenHeight() - below - MARGIN;
        float roomAbove = anchorY - MARGIN;

        final float top;
        final float fitted;
        if (height <= roomBelow) {
            top = below;
            fitted = height;
        } else if (height <= roomAbove) {
            top = anchorY - height;
            fitted = height;
        } else if (roomBelow >= roomAbove) {
            top = below;
            fitted = Math.max(MIN_HEIGHT, roomBelow);
        } else {
            fitted = Math.max(MIN_HEIGHT, roomAbove);
            top = Math.max(MARGIN, anchorY - fitted);
        }

        placedLeft = left;
        placedTop = top;
        StyleGroup.importantPipeline(getStyle().getLayoutGroup(),
                l -> l.width(width).left(left).top(top));
        // Only when it had to be cut. Otherwise the list keeps sizing itself to its rows, which is what
        // makes a two-item popup two items tall rather than a mostly-empty box.
        if (fitted < height) {
            float listHeight = Math.max(ROW_HEIGHT, fitted - HINT_HEIGHT);
            StyleGroup.importantPipeline(list.getStyle().getLayoutGroup(), l -> l.height(listHeight));
        }
    }

    // ── Rows ────────────────────────────────────────────────────────────────────────────────────

    /** Icon, banded label, right-aligned detail. See the class note for where the anatomy comes from. */
    private final class RowRenderer implements ListRenderer<CompletionSession.Row> {

        @Override
        public UIElement createTemplate() {
            // BUILT HERE, never in bind(). An element created during bind lands after that frame's layout
            // pass -- the trap the command palette's key chips and the editor's gutter arrows each paid for.
            Row row = new Row();
            row.addClass(ROW_CLASS);
            // THE ROW'S OWN INDEX IS READ AT CLICK TIME, never captured. A template is a different row every
            // time the view reuses it, so a listener holding the index it was built with would accept
            // whatever happened to be at that position when the popup opened -- the exact trap the editor's
            // pooled gutter arrows already carry a warning about.
            row.events.getGroup(MouseEvent.Down.class).attachListener((element, event) -> {
                if (row.index >= 0) onRowClicked.emit(row.index);
                event.stopPropagation();
            }, false, false);

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
            row.index = index;
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
            row.index = -1;
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
        /** Which model row this template currently shows, or -1 while pooled. Read at click time. */
        int index = -1;
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
