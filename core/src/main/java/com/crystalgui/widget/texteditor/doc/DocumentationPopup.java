package com.crystalgui.widget.texteditor.doc;

import com.crystalgui.core.async.FrameProfile;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.lang.Signature;
import com.crystalgui.fs.Resource;
import com.crystalgui.text.lang.DeclarationSite;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.markup.MarkupDocument;
import com.crystalgui.text.markup.MarkupParser;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.SymbolModifier;
import com.crystalgui.text.lang.TypeRef;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.text.syntax.Language;
import com.crystalgui.text.syntax.SyntaxToken;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.service.AnchoredPlacement;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.service.Drag;
import com.crystalgui.widget.dnd.Resizer;
import com.crystalgui.ui.input.keymap.KeyChord;
import com.crystalgui.ui.input.keymap.Keymap;
import com.crystalgui.widget.overlay.Tooltip;
import com.crystalgui.ui.service.Animation;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.text.MarkupView;
import com.crystalgui.widget.scroll.Scroller;
import com.crystalgui.widget.overlay.Popover;
import com.crystalgui.text.lang.CodeAction;
import com.crystalgui.widget.scroll.ScrollerView;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.widget.text.SyntaxHighlighting;
import com.crystalgui.ui.text.TextRange;
import com.crystalgui.widget.texteditor.EditorCommands;
import com.crystalgui.widget.texteditor.TextEditor;
import com.crystalgui.widget.texteditor.find.SearchReplaceBar;
import com.crystalgui.widget.texteditor.lang.EditorLanguageFeatures;
import com.crystalgui.widget.texteditor.suggest.CompletionPopup;
import lombok.Getter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * The <b>Quick Documentation</b> popup — M11 §24.1.
 *
 * <h3>It is named after the feature, not after the trigger</h3>
 *
 * <p>IntelliJ's own wording: {@code View ▸ Quick Documentation}, {@code Ctrl+Q}. Hovering is a
 * <em>setting on it</em> ({@code Show on Mouse Move}), and pressing the key a second time promotes the
 * same content into a tool window. Calling this {@code HoverPopup} would name one of its three triggers
 * and make the other two look like separate features later.</p>
 *
 * <h3>Anatomy, which is IntelliJ's DocumentationMarkup with one band we cannot fill yet</h3>
 *
 * <p>The platform's own structure is {@code DEFINITION → CONTENT → SECTIONS → BOTTOM}, and only the
 * <em>definition</em> line differs between a class, a method and a field — everything else is the same
 * frame. Ours, top to bottom:</p>
 *
 * <pre>
 *   [icon] com.crystalgui.language.run.ScriptHost      &lt;- owner: the DECLARING type
 *   private final Method entryPoint                    &lt;- definition, syntax-coloured
 *   Rendered documentation ...                         &lt;- content, hidden while there is none
 * </pre>
 *
 * <p><b>{@code BOTTOM} — the location line and its edit-source pencil — is currently absent.</b> It read
 * {@code this file} for anything declared in the open document, which is the common case and says less
 * than the owner band above it already does; IntelliJ's names the <em>module</em> instead, which we have
 * no notion of yet. Removed rather than hidden, because a hidden child still counts for a {@code gap-all}
 * and would leave the space it used to occupy. {@code editor.goToDefinition} is the command the pencil
 * invoked and is unaffected — it is bound to {@code Ctrl+B} and to Ctrl+Click.</p>
 *
 * <p><b>{@code SECTIONS} — the {@code Params:}/{@code Returns:}/{@code Throws:} table — is deliberately
 * absent</b>, because {@link SymbolInfo#documentation()} is a single rendered string and there is nothing
 * to split it on. That is the honest shape of what the seam carries today rather than an omission to fix
 * here: the band arrives when an engine reports structured doc, and no widget change is needed to hold a
 * place for it.</p>
 *
 * <p><b>The body was empty for every symbol until M13 §25.6</b>, because no engine populated
 * {@code documentation} — and the popup shipped anyway, which is why the work was ordered this way
 * round: the definition and the location are both derivable from what {@code SymbolInfo} already
 * carries, so the box was useful on day one and grew a body <b>without a widget change</b>, exactly as
 * this paragraph predicted. The Java engine now fills it from the doc comment, here or in an attached
 * source, inheriting an override's from its supertype. It still hides the band rather than showing an
 * empty one, which is what a symbol with no comment gets.</p>
 *
 * <h3>{@code Popover}, in {@code AUTO} — and not a {@code Tooltip}</h3>
 *
 * <p>A tooltip is transient and unfocusable. This box scrolls, has a control in its footer, and must
 * survive the pointer leaving the word — so it is a popover, and {@code AUTO} buys light dismiss and
 * Escape from the two stacks {@code UIDocument} already keeps, with no new machinery.</p>
 *
 * <p>It does <b>not</b> take focus, for the same reason the completion popup does not: the caret belongs
 * in the document, and a box that appears under it must not take the caret out of the text. It is
 * dismissed rather than tabbed into.</p>
 */
public final class DocumentationPopup extends Popover {
    /**
     * Its own kind. Every concrete node needs one, and a subclass that declares none
     * INHERITS its supertype's — so this would have reported {@code popover} and matched
     * every rule written for one. The ToolWindowFrame trap, which cost a whole unstyled
     * widget; {@code NodeKindsCoverageTest} is what makes it a compile-time question.
     */
    public static final Name NAME = Name.of("documentationpopup");


    public static final String POPUP_CLASS = "__documentation__";
    public static final String OWNER_CLASS = "__doc-owner__";
    public static final String OWNER_ICON_CLASS = "__doc-owner-icon__";
    public static final String OWNER_TEXT_CLASS = "__doc-owner-text__";
    public static final String DEFINITION_CLASS = "__doc-definition__";
    public static final String DEFINITION_BOX_CLASS = "__doc-definition-box__";
    public static final String BODY_CLASS = "__doc-body__";

    /**
     * On the popup while it is {@linkplain #isPinned() pinned}.
     *
     * <p>State the widget flips from its own listener, so a class rather than a pseudo-class — the
     * engine re-evaluates a pseudo-class on its terms and a class on yours, and there is no
     * {@code :pinned} to add. Nothing in the shipped sheet styles it; it is here so a theme <em>can</em>
     * say the box is no longer a hover, which is a real thing to want to say.</p>
     */
    public static final String PINNED_CLASS = "__pinned__";

    /**
     * The scrolling region — everything except the quick-fix band at the top.
     *
     * <p><b>The declaration scrolls with the prose, and the problem does not.</b> Only the body used to
     * scroll, which put a scrollbar inside a band rather than on the popup: a long {@code implements}
     * list pushed the documentation down and there was no way to reach what it pushed off. The quick-fix
     * band stays out of it because it is the one part you act on rather than read — scrolling an action
     * out of reach is how a popup comes to have a button nobody can press.</p>
     */
    public static final String SCROLL_CLASS = "__doc-scroll__";

    /**
     * The rule between the definition and the prose — IntelliJ draws one and it earns its line.
     *
     * <p>The two bands are different <em>kinds</em> of thing: one is code, syntax-coloured and
     * read a token at a time, and the other is prose. Without a rule they read as one block whose
     * colours change halfway, which is what the gap alone gave.</p>
     *
     * <p><b>An element, not a border.</b> A one-sided {@code border-width-*} either draws all four
     * edges or none, never the one named — the invariant `statusbarview` learned and spells with a
     * `__status-sep__` of its own.</p>
     */
    public static final String SEPARATOR_CLASS = "__doc-separator__";

    /**
     * The bottom band — <b>where the declaration lives</b>, and a pencil to go there.
     *
     * <h3>It was deleted once, and the objection was right</h3>
     *
     * <p>The first version read {@code this file} for anything declared in the open document — the
     * common case — which says strictly less than the owner band directly above it. The sheet's own
     * comment recorded that and removed the band rather than leave it, noting IntelliJ names the
     * <em>module</em> instead and we have no notion of one.</p>
     *
     * <p>That is still true, and it is not the fix. <b>The band hides when the declaration is in the
     * document you are already reading</b>, which is the same rule the body band follows and answers
     * the objection at its root: a band that would say nothing does not appear. What is left is the
     * case where it says something no other band does — the symbol is declared somewhere else, and
     * this is the only place that names where.</p>
     *
     * <h3>A pencil, and deliberately no kebab</h3>
     *
     * <p>The pencil runs {@code editor.goToDefinition} — the same command Ctrl+B and Ctrl+Click run,
     * with a different affordance, which is what §24.1 says it is for. A distinct target rather than
     * making the whole row clickable, because the row's other half is a filename and a filename reads
     * as information rather than as a button.</p>
     *
     * <p><b>No kebab.</b> §24.1's anatomy has one beside the pencil and names its four entries: font
     * size, show-the-toolbar, Show-on-Mouse-Move and Download-documentation — <b>settings that do not
     * exist</b>. A kebab opening an empty menu is the shape this class already warns about one band up:
     * an affordance offering nothing from a list of perfectly good options. It arrives with the first
     * setting that is real.</p>
     */
    public static final String FOOTER_CLASS = "__doc-footer__";

    public static final String FOOTER_ICON_CLASS = "__doc-footer-icon__";

    public static final String FOOTER_TEXT_CLASS = "__doc-footer-text__";

    /** Go-to-definition with a different affordance — the same command Ctrl+B and Ctrl+Click run. */
    public static final String FOOTER_EDIT_CLASS = "__doc-footer-edit__";
    public static final String PROBLEM_CLASS = "__doc-problem__";
    public static final String PROBLEM_MESSAGE_CLASS = "__doc-problem-message__";
    public static final String PROBLEM_ACTIONS_CLASS = "__doc-problem-actions__";

    /** On the header band when it has nothing to say — see {@link #setProblem}. */
    public static final String NO_MESSAGE_CLASS = "__no-message__";
    public static final String PROBLEM_ACTION_CLASS = "__doc-problem-action__";
    public static final String PROBLEM_SHORTCUT_CLASS = "__doc-problem-shortcut__";

    /** Clear of the groove, so the box does not sit against the scrollbar it is describing. */
    private static final float STRIPE_GAP = 1f;

    /**
     * On the popup while it is showing a problem and nothing else.
     *
     * <p>A modifier on the root rather than a second widget: it is the same box with three of its four
     * bands hidden, and the only thing that has to change is that it must not reserve room below the last
     * one. Hiding the bands is not enough — the popup's own bottom padding and the band's separator both
     * survive, and together they draw an empty strip under the actions that reads as a section that
     * failed to load.</p>
     */
    public static final String PROBLEM_ONLY_CLASS = "__problem-only__";

    /**
     * The icon vocabulary is {@code CompletionPopup}'s, on purpose.
     *
     * <p>Those classes are keyed on {@link SymbolKind} rather than on completion — the widget's name is on
     * the prefix and nothing else about them belongs to it. Reusing them means a new kind is one CSS block
     * for the whole application; a second prefix here would be a second table to keep in step, and the
     * symptom of missing an entry is a blank square rather than an error.</p>
     */
    private static final String KIND_CLASS_PREFIX = "completion-kind-";

    /**
     * {@code ::highlight()} names for the definition line — a <b>public</b> contract, because the
     * stylesheet names all three and a theme may restyle them.
     */
    public static final String HL_MODIFIER = "doc-modifier";
    public static final String HL_TYPE = "doc-type";
    public static final String HL_NAME = "doc-name";

    /**
     * The muted trailing remark an engine may append to the owner it reports.
     *
     * <p>A dynamic language's answer carries a provenance a static one never needs — {@code from JSDoc},
     * {@code from last run} — and the owner band is where it belongs, because that band already says
     * where a symbol comes from. It is <b>not</b> part of the qualified path, so it is marked once and
     * excluded from the segment colouring. @see #markOwnerPath
     */
    public static final String HL_OWNER_NOTE = "doc-owner-note";

    /** What separates the owner from {@link #HL_OWNER_NOTE}. An em dash, spaced, as an engine writes it. */
    private static final String NOTE_SEPARATOR = " — ";

    private final UINode ownerRow = new UINode();
    private final UINode ownerIcon = new UINode();
    private final UIText ownerText = new UIText("");
    /**
     * The signature band — a <b>column of lines</b>, not one label.
     *
     * <p>The engine breaks a long declaration at semantic points and hands the breaks over in the text.
     * They cannot be honoured by one element: {@code WhiteSpace} has {@code NORMAL} and {@code NOWRAP}
     * and no {@code PRE}, so an embedded newline has nowhere to break — and a <em>wrapping</em>
     * {@code UIText} cannot also self-size its width, because it measures the unwrapped string to report
     * one. Self-sizing is what makes a short signature size this popup at all, so the two are mutually
     * exclusive and every attempt at both gave a box sized to the owner row or a signature cut mid-word.</p>
     */
    private final UINode definition = new UINode();
    private final List<UIText> definitionLines = new ArrayList<>();
    private String definitionText = "";
    /** @see #SEPARATOR_CLASS */
    private final UINode separator = new UINode();

    /** @see #FOOTER_CLASS */
    private final UINode footerRule = new UINode();

    private final UINode footerRow = new UINode();

    private final UINode footerIcon = new UINode();

    private final UIText footerText = new UIText("");

    private final UINode footerEdit = new UINode();

    /**
     * The prose band — <b>a {@link MarkupView}, not a text element</b>.
     *
     * <p>It was a {@code UIText} carrying whatever the engine reported, which worked only for as long as
     * the engine reported plain text. {@code JavaDocs} now emits the author's own markup, because a doc
     * comment's {@code <p>}, {@code <pre>} and {@code <li>} are the only structure it has and stripping
     * them is what made this band a wall. Parsing it here rather than in the engine is deliberate: the
     * engine's job ends at "what does this symbol say", and the same markup is what a JSDoc comment or a
     * shader node's description would arrive as.</p>
     */
    private final MarkupView body = new MarkupView();

    /** @see #SCROLL_CLASS */
    private final ScrollerView scroller = new ScrollerView();

    /** What {@link #navigateTo} was asked for, applied on the next frame. */
    @Nullable
    private SymbolInfo pendingNavigation;

    /**
     * The problem section — message, then the one action worth showing without being asked.
     *
     * <p>One action inline and everything else behind "More actions…", which is IntelliJ's arrangement and
     * is what keeps a hover the size of a hover. A popup that listed every contributor's answers would be
     * taller than the code it is explaining before it said anything about the code.</p>
     */
    private final UINode problemRow = new UINode();
    private final UIText problemMessage = new UIText("");
    private final UINode problemActions = new UINode();
    private final UIText primaryAction = new UIText("");
    private final UIText primaryShortcut = new UIText("");
    private final UIText moreActions = new UIText("");
    private final UIText moreShortcut = new UIText("");

    /** What the inline action would apply, and what the overflow menu would list. */
    private final java.util.List<CodeAction> actions = new java.util.ArrayList<>();

    /**
     * What is in the inline slot — the observable for the rule {@code primaryOf} applies.
     *
     * <p>Kept because nothing could see that rule from outside: {@link #offeredActions} reports what was
     * <em>available</em>, and the band shipped choosing nothing from a list of perfectly good fixes with
     * every test still green.</p>
     */
    @Nullable
    private CodeAction primaryShown;

    /** Tracked rather than read back: display is a style write, not a queryable flag. */
    private boolean problemShown;

    /** What the header band is currently drawing — the observable, tracked because the element has none. */
    private String headerShown = "";

    @Nullable
    private SymbolInfo shown;
    /**
     * -- GETTER --
     * Whether the doc body band is drawn — false for every symbol until an engine reports doc. 
     */
    @Getter
    private boolean bodyShown;
    /**
     * -- GETTER --
     *  Whether the pointer is inside this popup — what makes a hover popup <b>sticky</b>.
     *  <p>The editor cannot answer this for itself: the popup is a promoted overlay, so it is not in the
     *  editor's subtree and the editor's own 
     *  fires the moment the pointer starts moving
     *  towards the box. Without this the popup would vanish every time you reached for it.</p>
     */
    @Getter
    private boolean pointerOver;

    /**
     * -- GETTER --
     *  Whether a press has <b>pinned</b> this popup — IntelliJ's behaviour, and two things at once.
     *  <p>A pinned popup stops being a hover: it survives the pointer leaving the word it describes, and
     *  it stops being re-anchored, because a press also begins a move. The two are one gesture and one
     *  flag deliberately — a box you can drag but that vanishes when you reach past it, or one that
     *  stays but snaps back to its anchor, is worse than neither.</p>
     */
    @Getter
    private boolean pinned;

    public DocumentationPopup() {
        super(NAME);
        addClass(POPUP_CLASS);
        // AUTO: light dismiss and Escape, both from the stacks UIDocument already keeps. MANUAL would mean
        // writing both, and the completion popup is MANUAL only because the editor has to arbitrate Escape
        // between a popup and the find bar -- this has no such competitor.
        setMode(Mode.AUTO);
        // NEVER FOCUSED. The caret stays in the document; this is something you read, not something you
        // are in. @see CompletionPopup, which says the same thing at more length about the same trap.
        setFocusPolicy(FocusPolicy.NONE);
        // DELIBERATELY NOT markAsInternal() on this element. It makes a widget unstyleable as a selector
        // SUBJECT while still working as an ancestor, so `.__documentation__ .__doc-owner__` would match
        // and `.__documentation__` itself would not -- the box would have no border, no padding and no
        // width while its contents looked correct. The PARTS are made internal individually below, which
        // is the half that is actually wanted.
        ownerRow.addClass(OWNER_CLASS);
        ownerIcon.addClass(OWNER_ICON_CLASS);
        ownerText.addClass(OWNER_TEXT_CLASS);
        // A CODE SURFACE, so `.__syntax__::highlight(...)` reaches it -- the same rules that colour the
        // editor's import lines, rather than a second pair of names only this band would use.
        ownerText.addClass(TextEditor.SYNTAX_CLASS);
        ownerRow.append(ownerIcon);
        ownerRow.append(ownerText);

        definition.addClass(DEFINITION_BOX_CLASS);
        // OPTS INTO THE EDITOR'S CAPTURE VOCABULARY. The signature is code, so it should be coloured by
        // the rules colouring code -- from the same scheme, updated by the same theme switch. This is the
        // whole reason those forty rules stopped being selected as `texteditor text`.

        // THESE TWO DRIVE THE BOX'S WIDTH, and saying so is not optional.
        //
        // The popup is sized to its content, so every band has to report how wide it wants to be. UIText
        // decides that with a one-shot heuristic on its first post-attach layout -- and here that reading
        // lands on the owner row's width rather than on zero, so it latches "I do not size myself" and
        // never re-derives. The signature then accepts whatever narrow width it is handed for the rest of
        // its life: `final class System` first wrapped to two lines and then, once it stopped wrapping,
        // ellipsised to `final class ...` inside a box as wide as `java.lang`.
        //
        // It looked like a width bug and is a self-sizing one, which is why widening the box, changing
        // white-space and adding text-overflow each changed the SYMPTOM and none of them fixed it.
        separator.addClass(SEPARATOR_CLASS);
        separator.setHitTest(false);

        footerRule.addClass(SEPARATOR_CLASS);
        footerRule.setHitTest(false);
        footerRow.addClass(FOOTER_CLASS);
        footerIcon.addClass(FOOTER_ICON_CLASS);
        footerIcon.setHitTest(false);
        footerText.addClass(FOOTER_TEXT_CLASS);
        footerText.setHitTest(false);
        footerEdit.addClass(FOOTER_EDIT_CLASS);
        // ON THE MOUSE-DOWN, for the reason the problem actions are: this popup is light-dismissable, so
        // a press closes it and the row is gone before any mouse-up could arrive.
        footerEdit.onMouseDown.attachListener((element, event) -> {
            onGoToDeclaration.emit();
            event.stopPropagation();
        }, false, true);
        footerRow.append(footerIcon);
        footerRow.append(footerText);
        footerRow.append(footerEdit);
        body.addClass(BODY_CLASS);
        body.onLinkActivated.connect(onLinkActivated::emit);

        problemMessage.addClass(PROBLEM_MESSAGE_CLASS);
        // NOT forceSelfSizeWidth. It reports the unwrapped run, which is exactly what stops a wrapping
        // text from wrapping -- see the sheet's rule, which bounds this label instead.
        problemActions.addClass(PROBLEM_ACTIONS_CLASS);
        primaryAction.addClass(PROBLEM_ACTION_CLASS);
        primaryShortcut.addClass(PROBLEM_SHORTCUT_CLASS);
        moreActions.addClass(PROBLEM_ACTION_CLASS);
        moreActions.setText("More actions…");
        moreShortcut.addClass(PROBLEM_SHORTCUT_CLASS);
        moreShortcut.setText("Alt+Enter");
        problemActions.append(primaryAction);
        problemActions.append(primaryShortcut);
        problemActions.append(moreActions);
        problemActions.append(moreShortcut);
        problemRow.addClass(PROBLEM_CLASS);
        problemRow.append(problemMessage);
        problemRow.append(problemActions);
        // HIDDEN, not absent. The section is built once and shown per symbol, because an element created
        // during fill() lands after that frame's layout pass -- the trap the command palette's key chips
        // and the editor's gutter arrows each paid for.
        problemRow.setDisplayed(false);
        // ON THE MOUSE-DOWN rather than a press pair, because this popup is light-dismissable: a press
        // outside an AUTO popover closes it, and the row would be gone before any mouse-up arrived.
        primaryAction.onMouseDown.attachListener((element, event) -> {
            // WHAT THE SLOT IS SHOWING, not a second run of the rule that chose it. Re-deciding here was
            // harmless only while the rule depended on the action list alone; it now depends on whether
            // there is a problem too, which this listener cannot see.
            if (primaryShown != null) onActionChosen.emit(primaryShown);
            event.stopPropagation();
        }, false, true);
        moreActions.onMouseDown.attachListener((element, event) -> {
            onMoreActions.emit();
            event.stopPropagation();
        }, false, true);

        // PRESS TO PIN, AND THE SAME PRESS BEGINS A MOVE. Target AND bubble, because the press lands on
        // whatever is under it -- a paragraph, the owner row, the empty space beside the definition -- and
        // this element is never that thing. `(false, false)` subscribes the target phase only, so the
        // container would hear nothing at all.
        //
        // The three links above take their own presses and stop propagation, so they never reach here and
        // a click on "More actions..." is still a click. The resizer and the scrollbars do NOT stop
        // propagation, so they are excluded by hand below.
        onMouseDown.attachListener((element, event) -> {
            float rawX = event.getPosition().x();
            float rawY = event.getPosition().y();
            // A synthesized activation press (Space/Enter on a focused element) carries the cursor's
            // position, which may be nowhere near this box. Honouring one would teleport it.
            if (!isOpen() || !containsSurfacePoint(rawX, rawY)) return;

            // PINNED BY ANY PRESS ON THE BOX, including one aimed at a handle or a scrollbar. Dragging a
            // corner to resize and then having the popup evaporate the moment the pointer leaves the word
            // would make the resize pointless; scrolling it is even more plainly "I am reading this".
            // Only the MOVE is excluded below -- the pin is about intent, the move is about which gesture.
            pinned = true;
            addClass(PINNED_CLASS);
            if (ownsItsOwnPress(((UINode) event.getTarget()))) return;
            beginMove(rawX, rawY);
        }, false, true);

        // ABOVE the owner, which is IntelliJ's order and is not arbitrary: the problem is why you looked,
        // the declaration is what you were looking at. A popup that shows one instead of the other
        // regresses hover for every symbol that happens to carry a warning.
        append(problemRow);
        // EVERYTHING ELSE GOES INSIDE THE SCROLLER, in the order it was in. @see #SCROLL_CLASS
        //
        // The footer stays outside it and pinned: it is an action bar, and IntelliJ keeps its own at the
        // bottom of the popup rather than at the bottom of the document.
        scroller.addClass(SCROLL_CLASS);
        scroller.append(ownerRow);
        scroller.append(definition);
        scroller.append(separator);
        scroller.append(body);
        append(scroller);
        append(footerRule);
        append(footerRow);

        // Enter and Leave do NOT bubble, but one is dispatched to every element in the entered and left
        // chain -- so this fires for the pointer arriving anywhere inside, including on the footer's
        // button. Listening on the children as well would be the bug that guard exists to prevent.
        onMouseEnter.attachListener((el, event) -> pointerOver = true, false, false);
        onMouseLeave.attachListener((el, event) -> pointerOver = false, false, false);
    }

    /** Chosen inline, or picked out of the overflow menu. */
    public final com.crystalgui.core.signal.Signal.Value<CodeAction> onActionChosen =
            new com.crystalgui.core.signal.Signal.Value<>();

    /** "More actions…" — the host opens the full list, because only it knows where to put it. */
    /**
     * The footer's pencil was pressed — go to where the symbol is declared.
     *
     * <p>A signal rather than a command run from here, like every other action on this popup: the widget
     * knows a pencil was pressed and nothing else. Which command that is belongs to the editor, which is
     * where the keymap already resolves Ctrl+B.</p>
     */
    public final com.crystalgui.core.signal.Signal.Action onGoToDeclaration =
            new com.crystalgui.core.signal.Signal.Action();

    public final com.crystalgui.core.signal.Signal.Action onMoreActions =
            new com.crystalgui.core.signal.Signal.Action();

    /**
     * Fills the problem section, or hides it.
     *
     * <p>Called <b>after</b> {@code show}, because actions arrive from an engine asynchronously and the
     * popup must not wait for them — a hover that appeared only once the compiler answered would feel
     * broken on the one file slow enough to notice. The section grows in when the answer lands.</p>
     *
     * <p>Hidden rather than emptied when there is nothing wrong, and hidden as a <em>row</em> rather than
     * by clearing its text: a band with no content still occupies its share of the parent's {@code gap-all}
     * and would leave a gap above the owner for every symbol in the file.</p>
     */
    public void setProblem(List<com.crystalgui.text.diagnostic.Diagnostic> problems,
                           List<CodeAction> available) {
        actions.clear();
        if (available != null) actions.addAll(available);

        boolean any = problems != null && !problems.isEmpty();
        boolean anyAction = !actions.isEmpty();
        // THE ROW IS FOR EITHER, and it used to be for a problem alone. An INTENTION has no diagnostic
        // behind it -- "Replace with lambda" fires on code where nothing is wrong -- so the early return
        // here took the action strip with the message it did not have, and the popup for a convertible
        // anonymous class offered nothing while the gutter bulb beside it said there was something.
        problemShown = any;
        problemRow.setDisplayed(any || anyAction);
        if (!any && !anyAction) {
            problemMessage.setDisplayed(false);
            headerShown = "";
            return;
        }

        CodeAction primary = primaryOf(actions, any);
        primaryShown = primary;

        // THE HEADER IS THE DIAGNOSTIC, OR WHAT THE ACTION DOES WHEN THERE IS NO DIAGNOSTIC.
        //
        // The band paints its own ground and a bottom border, so it is a header rather than a line of
        // text -- and a header with its content hidden is not absent, it is a blank grey strip. That is
        // what an INTENTION got: "Join declaration and assignment" opened a popup whose top band said
        // nothing, which reads as a message that failed to load rather than as one that does not exist.
        //
        // A quick fix leaves `description` null on purpose. The compiler has already said the useful
        // thing, and an action's own title one line above its own title says nothing twice.
        String header = any ? joined(problems)
                : primary == null || primary.description() == null ? "" : primary.description();
        problemMessage.setDisplayed(!header.isEmpty());
        headerShown = header;
        // AND WHEN THERE IS STILL NOTHING TO SAY, the band gives up its top padding rather than drawing an
        // empty one -- the same rule, one step further along, for an intention that forgot a description.
        if (header.isEmpty()) {
            problemRow.addClass(NO_MESSAGE_CLASS);
        } else {
            problemRow.removeClass(NO_MESSAGE_CLASS);
        }
        if (!header.isEmpty()) {
            problemMessage.setText(header);
        }


        boolean hasPrimary = primary != null;
        primaryAction.setDisplayed(hasPrimary);
        primaryShortcut.setDisplayed(hasPrimary);
        if (hasPrimary) {
            primaryAction.setText(primary.title());
            primaryShortcut.setText("Alt+Shift+Enter");
        }
        // MORE ACTIONS IS SHOWN WHENEVER THERE IS ANYTHING AT ALL, including when the only thing is the
        // primary: IntelliJ shows it beside a single fix too, because the menu is also how you reach the
        // things that are never inline. It hides only when the list is genuinely empty, which is a problem
        // nobody can do anything about rather than one whose menu happens to be short.
        moreActions.setDisplayed(anyAction);
        moreShortcut.setDisplayed(anyAction);
        problemActions.setDisplayed(anyAction);
    }

    /**
     * The one shown without being asked — the <b>highest-ranked fix</b>, preferred or not.
     *
     * <h3>Why this is not "the preferred one"</h3>
     *
     * <p>It was, and that turned out to be a rule about the wrong thing. {@code preferred} means "this is
     * unambiguously THE answer", so a correction that is one of several plausible ones deliberately does
     * not set it — an import per candidate, a rename per near miss, add-throws beside surround-with-try.
     * Requiring it here meant every one of those problems showed a message and a bare "More actions…",
     * with the single obvious fix one keystroke further away than the day before. It affected most of
     * them, because most real fixes come in families.</p>
     *
     * <p>So {@code preferred} does the job it can do — it <em>ranks</em>, through
     * {@code CodeAction.ORDER} — and the popup shows whatever ranks first. The list arrives sorted, so
     * "first" is "best", and a preferred fix is still the one that gets the slot when there is one.
     * IntelliJ's hover popup behaves this way: the top fix inline, the rest behind the menu.</p>
     *
     * <p><b>Only a {@link CodeActionKind#QUICK_FIX} may take the slot WHILE THERE IS A PROBLEM.</b> That is
     * what keeps a whole-file tidy out of it — "Organize imports", "Remove unused imports" and "Copy
     * problem message" are all things to choose rather than to default to, and one keystroke from a hover
     * is exactly defaulting to it. The rule is really "the inline action must answer the message above
     * it", and a tidy does not answer anything.</p>
     *
     * <p><b>With no problem there is no message for it to answer, and the rule inverts.</b> An intention
     * is the only reason the popup opened an action strip at all — "Replace with lambda" on a convertible
     * anonymous class — so requiring a QUICK_FIX there leaves a popup showing a bare "More actions…" and
     * the one thing on offer a keystroke further away than it needs to be. That is the same shape as the
     * {@code preferred} mistake this method already records, one gate along. IntelliJ shows exactly this
     * inline, with Alt+Shift+Enter beside it.</p>
     *
     * <p>The list arrives sorted by {@code CodeAction.ORDER}, which ranks {@code QUICK_FIX} above
     * {@code REFACTOR} above {@code SOURCE} — so "first" is still "best" and the no-problem case needs no
     * ordering of its own.</p>
     */
    /** Every message, in one line — a caret can sit under more than one diagnostic. */
    private static String joined(List<com.crystalgui.text.diagnostic.Diagnostic> problems) {
        StringBuilder message = new StringBuilder();
        for (com.crystalgui.text.diagnostic.Diagnostic problem : problems) {
            if (message.length() > 0) message.append(" · ");
            message.append(problem.message());
        }
        return message.toString();
    }

    @Nullable
    private static CodeAction primaryOf(List<CodeAction> actions, boolean hasProblem) {
        for (CodeAction action : actions) {
            if (action.kind() == com.crystalgui.text.lang.CodeActionKind.QUICK_FIX) return action;
        }
        return hasProblem || actions.isEmpty() ? null : actions.get(0);
    }

    /** What the problem section is currently offering — the observable a test asserts on. */
    public List<CodeAction> offeredActions() {
        return List.copyOf(actions);
    }

    /** The action in the inline slot, or null when nothing is offered there. @see #primaryOf */
    @Nullable
    public CodeAction primaryAction() {
        return primaryShown;
    }

    /**
     * <b>What the header band actually says</b>, or empty when it is not drawn.
     *
     * <p>Reads the element rather than a flag. It used to be gated on {@code problemShown}, which was the
     * same thing as "the band has text" only while the band could hold nothing but a diagnostic — an
     * intention's description goes in the same place, and a gate on the diagnostic would report the band as
     * empty while it visibly is not. The observable has to be what a reader sees, or a test can pass
     * against a blank strip.</p>
     */
    public String headerText() {
        return headerShown;
    }

    /** What is currently on screen, or null. The only observable of what a resolve produced. */
    @Nullable
    public SymbolInfo shownSymbol() {
        return shown;
    }

    /** The declaration as it is drawn, newlines and all — the readable proof of what is rendered. */
    public String definitionText() {
        return definitionText;
    }

    /** How many lines the declaration was broken into. One for everything that fits. */
    public int definitionLineCount() {
        return Math.max(1, definitionText.isEmpty() ? 1 : definitionText.split("\n", -1).length);
    }

    /**
     * The definition band itself, so its {@code ::highlight()} ranges can be read back.
     *
     * <p>Exposed because the ranges are this widget's entire contribution to the colouring — the colours
     * are the stylesheet's — and a band over the wrong characters is invisible in every other observable:
     * the text is right, the layout is right, and one word is the wrong colour.</p>
     */
    public UIText definitionElement() {
        return definitionLines.isEmpty() ? null : definitionLines.get(0);
    }

    /** One line of the declaration, so a multi-line signature's bands can be read back per line. */
    public UIText definitionLine(int index) {
        return index >= 0 && index < definitionLines.size() ? definitionLines.get(index) : null;
    }

    /**
     * Fill from {@code symbol} and open at a point in window space.
     *
     * <p>The point is the caret's, in the coordinate space {@code left}/{@code top} are interpreted in —
     * the editor computes it from {@code getWindowX/Y}, never from the transform chain, which is in surface
     * pixels with the root transform already baked in and would place the box neatly at {@code uiScale}
     * times where it belongs.</p>
     */
    /**
     * The problem bands alone — no owner, no declaration, no doc.
     *
     * <p>What the error stripe's marks show. A mark names a <em>problem</em> and not a symbol: it sits in
     * the scrollbar's groove next to nothing at all, and there is frequently no name under it to resolve.
     * IntelliJ's stripe tooltip is the same shape — the message and its actions, and nothing else.</p>
     *
     * <p>Hides the three symbol bands rather than leaving them from a previous show, for the reason every
     * band here already records: they are pooled across symbols, and one left filled would describe
     * whatever was hovered before this.</p>
     */
    public void showProblems(UIDocument window, List<com.crystalgui.text.diagnostic.Diagnostic> problems,
                             UINode anchor) {
        this.shown = null;
        clearUserSizing();
        if (parent() == null) window.addOverlay(this, null);
        // ANCHORED TO THE MARK AND OPENED LEFTWARD, rather than dropped at the pointer. Two faults come
        // from placing it at a point: the box lands under the cursor and covers the scrollbar you were
        // reaching for, and its RIGHT edge moves with its own width -- so a one-word message and a full
        // sentence start in different places. Anchoring pins the right edge to the mark and lets the box
        // grow left from there, which is what IntelliJ does and what the activity bar's tooltips already
        // do here. AnchoredPlacement flips to the other side when there is no room, so a narrow window
        // degrades rather than clipping.
        setPreferredSide(AnchoredPlacement.Side.LEFT);
        setOffset(STRIPE_GAP);
        addClass(PROBLEM_ONLY_CLASS);
        showFor(anchor, null);
        ownerRow.setDisplayed(false);
        definition.setDisplayed(false);
        bodyShown = false;
        separator.setDisplayed(false);
        body.setDisplayed(false);
        footerRule.setDisplayed(false);
        footerRow.setDisplayed(false);
        setProblem(problems, List.of());
    }

    /**
     * The problem bands alone, opened <b>below a point in the text</b> rather than beside a stripe mark.
     *
     * <p>What a hover over a squiggle shows when there is no symbol to describe — which is not a corner
     * case but the most valuable one: a name that resolves to nothing is exactly the name with a problem
     * on it and a "did you mean" waiting behind it. {@link #showProblems} cannot serve this because it
     * anchors leftward off an element, and the stripe's reason for that (pin the right edge to a mark in
     * the scrollbar groove) is the opposite of what an in-text hover wants.</p>
     */
    public void showProblemsAt(UIDocument window, List<Diagnostic> problems,
                               float x, float y, float lineHeight) {
        this.shown = null;
        // RESTORED, for the reason show() records: showProblems changes both and this is one reused
        // instance, so an in-text hover after a stripe hover would open sideways off its own anchor.
        setPreferredSide(AnchoredPlacement.Side.BOTTOM);
        setOffset(0f);
        addClass(PROBLEM_ONLY_CLASS);
        clearUserSizing();
        if (parent() == null) window.addOverlay(this, null);
        // Below the token's LINE rather than its top, or the box covers the word it is describing.
        showAt(x, y + lineHeight, null);
        ownerRow.setDisplayed(false);
        definition.setDisplayed(false);
        bodyShown = false;
        separator.setDisplayed(false);
        body.setDisplayed(false);
        footerRule.setDisplayed(false);
        footerRow.setDisplayed(false);
        setProblem(problems, List.of());
    }

    /**
     * Replaces what this popup is showing, <b>without moving it</b>.
     *
     * <p>For following a link. {@link #show} re-anchors, which is right for a hover — a new hover is a
     * new place — and wrong here: you are reading this box, and you had to move the pointer onto a link
     * to press it, so re-anchoring walks the box across the screen on every step of a chain of
     * references. IntelliJ replaces the content in place, and the position is the one thing a reader has
     * already got used to.</p>
     *
     * <p>The dragged size is kept for the same reason — it was chosen to read this with, and the next
     * page is more of the same reading. {@link #show} deliberately forgets it, because that is a fresh
     * open.</p>
     *
     * <p>The problem band goes, though. An intention belongs to the code under the caret, not to the
     * class you just navigated to, so carrying it across would offer "Split into declaration and
     * assignment" on {@code java.lang.StringBuffer}.</p>
     */
    public void navigateTo(SymbolInfo symbol) {
        if (symbol == null) return;
        // NEXT FRAME, NOT NOW -- and this is the engine's own rule rather than caution: a widget must
        // never rebuild the elements it is being clicked on. `fill` replaces the whole body, including
        // the very `UIText` whose press is still being dispatched, and light dismiss runs AFTER that
        // dispatch: it asks the press target for its innermost popover ancestor, a detached element has
        // none, and the popup was therefore read as "pressed from outside" and closed on the click that
        // asked it to navigate.
        //
        // The old code survived that by accident. It called `show()`, which bumps `popoverShowSeq`, and
        // light dismiss spares anything shown during the press -- so re-anchoring was doubling as life
        // support, and removing it exposed a defect that had always been there. Bumping the counter from
        // here would work and would be a lie: nothing is being shown. Deferring is the honest fix, and a
        // frame is invisible to a reader.
        pendingNavigation = symbol;
        UIDocument window = document();
        if (window == null) return;
        // ONE SHOT, AND IT DROPS ITSELF BEFORE IT WORKS. Written as a lambda ending in `return false`,
        // a throw from the body skips that return -- so the ticker stays registered and throws again on
        // every frame after, out of `tickAnimations`, out of `advanceFrame`, out of `paintFrame`. One
        // failed navigation would take the whole window with it, permanently, which reads as the popup
        // having broken rather than as a single answer having been bad. The flag is set first so the
        // repeat cannot happen whatever the body does.
        window.animation().every(this, new Animation.Hook() {
            private boolean spent;

            @Override
            public boolean frame(float deltaSeconds) {
                if (spent) return false;
                spent = true;
                applyPendingNavigation();
                return false;
            }
        });
    }

    /** Swaps in the page {@link #navigateTo} asked for, a frame after the press that asked. */
    private void applyPendingNavigation() {
        SymbolInfo symbol = pendingNavigation;
        pendingNavigation = null;
        // CLOSED IN THE MEANTIME? A press outside, or Escape, between the click and this frame.
        if (symbol == null || !isOpen()) return;
        this.shown = symbol;
        removeClass(PROBLEM_ONLY_CLASS);
        definition.setDisplayed(true);
        setProblem(List.of(), List.of());
        fill(symbol);
        // THE BOX IS A DIFFERENT SIZE NOW, and it is still anchored: a shorter page would leave it
        // hanging below its anchor and a taller one would run off the bottom. `reposition` is the only
        // thing allowed to write left/top on an anchored popup.
        reposition();
    }

    public void show(UIDocument window, SymbolInfo symbol, float x, float y, float lineHeight) {
        this.shown = symbol;
        // RESTORED, because showProblems changes both and this popup is one reused instance -- a hover in
        // the text after one on the stripe would otherwise open sideways off its own anchor.
        setPreferredSide(AnchoredPlacement.Side.BOTTOM);
        setOffset(0f);
        removeClass(PROBLEM_ONLY_CLASS);
        // SHOWN AGAIN, because showProblems hides them and this popup is one reused instance. Without it a
        // stripe hover followed by an ordinary one draws a problem band over an empty box.
        definition.setDisplayed(true);
        // FORGET A DRAGGED SIZE. `resize: both` writes width and height at INLINE, which outranks both the
        // stylesheet's 420px and this widget's own content sizing -- so without this a box stretched to
        // read one long javadoc stays that size for every symbol afterwards, including a one-line field
        // with three words in it. Sizing is per-open, exactly as CompletionPopup.resetUserGeometry has it.
        clearUserSizing();
        // ATTACHED BEFORE FILLED, and the order is the whole of a first-open bug.
        //
        // fill() creates this popup's signature lines, and `invalidateStyleMatch()` EARLY-RETURNS ON A
        // DETACHED ELEMENT -- so building them while the popup was still outside the tree meant no
        // selector ever matched them. They had no font-size, no `white-space`, and not even the code
        // face, so the box measured itself from unstyled text and the lines laid out on top of one
        // another.
        //
        // It reproduced exactly once per session, which is what made it look like a race: from the second
        // hover onwards the popup is already attached, so every line built after that matches normally.
        if (parent() == null) window.addOverlay(this, null);
        // OPENED BEFORE FILLED. A closed Popover is `display: none`, so a subtree filled while it is shut
        // never lays out -- every line measured zero, and UIText re-shapes only when its text or its
        // resolved font family changes, neither of which happens again. So the width stayed zero for the
        // popup's whole life and the box sized itself to the owner row instead.
        //
        // It survived exactly one hover per process because the line elements are pooled: the second show
        // reuses lines that have since been laid out, so only the ones built during the first fill were
        // ever stuck. That is what made it look like a warm-up problem rather than an ordering one.
        //
        // Below the token's LINE rather than its top, or the box covers the word it is describing.
        long timed = FrameProfile.begin();
        showAt(x, y + lineHeight, null);
        FrameProfile.step(timed, "doc.showAt");
        // THE BUILD, TIMED. Moving the RESOLVE to a worker left this behind: the frame that receives the
        // answer was measured at `done:java-resolve 68,117us`, and everything in that number is here --
        // creating the signature lines, rendering the javadoc, and the first paint of a box full of text
        // that follows it -- `gl:toplayer` 36ms, `paint:overlay` 34ms, `text:submit` 30ms on the same frame.
        timed = FrameProfile.begin();
        fill(symbol);
        FrameProfile.step(timed, "doc.fill");
    }

    /**
     * Whether the press belongs to a part that will act on it itself.
     *
     * <p>The resize grabber and the scrollbars both start drags of their own and neither stops
     * propagation, so without this a press on either would begin a move as well: dragging the corner
     * would resize the box <em>and</em> slide it, and dragging the scrollbar would carry the whole popup
     * with the thumb. They are excluded here rather than made to stop propagation, because both are
     * shared widgets and every other consumer is relying on that press continuing to bubble.</p>
     */
    private boolean ownsItsOwnPress(@Nullable UINode target) {
        for (UINode at = target; at != null && at != this; at = at.parent()) {
            // BY CLASS for the resizer, because `UIResizer` is package-private and cannot be named from
            // here; by TYPE for the scroller, which is an ordinary public widget.
            if (at.hasClass(Resizer.RESIZER_CLASS) || at instanceof Scroller) return true;
        }
        return false;
    }

    /**
     * Starts dragging the box, from a source that <b>does not move with it</b>.
     *
     * <p>{@code UIDragController} reports its delta through {@link UINode#toLocal}, so the frame
     * the delta is measured in is the drag <em>source</em>'s. Naming this popup as its own source would
     * therefore measure each frame's movement in a frame that has already moved by it, which is the trap
     * already recorded for a canvas pan: "a pan drag's source is the viewport, never the transformed
     * plane". The parent is the frame this box is positioned inside and it stays still while the box
     * travels, so the delta stays a delta.</p>
     *
     * <p>The move goes through {@link Popover#moveTo}, which is the one legal way to write
     * {@code left}/{@code top} here: it hands ownership over from {@code AnchoredPlacement} rather than
     * competing with it, so there is still exactly one writer. Writing the position directly would be
     * overwritten by the next {@code reposition()} and the box would appear nailed down.</p>
     */
    private void beginMove(float rawX, float rawY) {
        UIDocument window = document();
        UINode frame = parent();
        Box self = box();
        if (window == null || frame == null || self == null) return;

        // THE OFFSET WITHIN THE HOST, never `worldX`. A world coordinate is in SURFACE pixels with the
        // root transform baked in, and `moveTo` writes LOGICAL left/top which are scaled again -- so
        // at the default uiScale of 2 the popup would jump to twice its own distance down the page on
        // the first pixel of the drag. `Box.x()` is already the space moveTo writes in.
        float startLeft = self.x();
        float startTop = self.y();
        Drag.start(frame, rawX, rawY,
                (mouseX, mouseY, startX, startY, deltaX, deltaY) ->
                        moveTo(startLeft + deltaX, startTop + deltaY));
    }

    /**
     * Every re-show clears the pin, and both entry points are overridden because there are two.
     *
     * <p>Popover.showAt and showFor already clear their own freely-positioned flag so a box you moved does
     * not open in that spot forever; the pin is the same fact one layer up and has to travel with it.
     * Missing one would leave a popup that is drawn at a fresh anchor while still refusing to close on
     * hover-off -- the two halves of pinning disagreeing about whether it is still pinned.</p>
     */
    @Override
    public Popover showAt(float rootX, float rootY, @Nullable UINode invoker) {
        unpin();
        return super.showAt(rootX, rootY, invoker);
    }

    /** @see #showAt */
    @Override
    public Popover showFor(UINode anchorElement, @Nullable UINode invoker) {
        unpin();
        return super.showFor(anchorElement, invoker);
    }

    /**
     * What a {@code <pre>} sample in the documentation is written in.
     *
     * <p>Forwarded to the body rather than derived here, and the popup cannot derive it: a
     * {@link SymbolInfo} says what a symbol IS, not what language the file describing it is written in.
     * The editor knows, and it is the editor that opens this.</p>
     */
    /**
     * A {@code {@link}} in the documentation was pressed, carrying its target.
     *
     * <p>Forwarded straight from the body. The target is the raw {@code href} the emitter wrote —
     * {@code java:java.util.List} for a javadoc link — and turning that into a place to go needs
     * something holding an engine, which this popup is not. The same division the footer pencil already
     * makes: the popup states what happened, {@code EditorLanguageFeatures} decides what it means.</p>
     */
    public final Signal.Value<String> onLinkActivated = new Signal.Value<>();

    public DocumentationPopup setCodeLanguage(@Nullable Language language) {
        body.setCodeLanguage(language);
        return this;
    }

    private void unpin() {
        pinned = false;
        removeClass(PINNED_CLASS);
    }

    @Override
    public Popover hide() {
        shown = null;
        // UNPINNED. This is one reused instance, so a pin left behind would make the NEXT hover -- a
        // different symbol, at a different anchor -- open already pinned and already detached from its
        // anchor, which reads as the popup being stuck.
        unpin();
        return super.hide();
    }

    private void fill(SymbolInfo symbol) {
        // BACK TO THE TOP, because this is a different document. Scroll is view state, and the view is
        // now showing something else -- following a `@see` from halfway down one comment opened the next
        // one already scrolled, with its declaration cut off above the fold, which reads as the popup
        // having rendered wrongly rather than as it having kept a position.
        //
        // IMMEDIATE rather than animated: there is nothing on screen yet to animate from, and a smooth
        // scroll would be a visible slide on every open.
        Box scrollBox = scroller.box();
        if (scrollBox != null) scrollBox.setScroll(0f, 0f);
        // THE OWNER'S ICON, NOT THE SYMBOL'S. This band names what DECLARES the symbol, so it must be
        // drawn as that -- a method shows the class it is on, a class shows its package. Drawing the
        // symbol's own kind put a method glyph next to the class name, which says the wrong thing
        // confidently: the row reads as "the method java.io.PrintStream".
        //
        // SWAPPED, never added -- this element is reused for every symbol, so a kind class left from the
        // last one would join the new one and the cascade would resolve whichever it preferred. That reads
        // as a random icon rather than as a stale class.
        SymbolKind ownerKind = ownerKindFor(symbol);
        ownerIcon.swapPrefixedClass(KIND_CLASS_PREFIX, KIND_CLASS_PREFIX
                + (ownerKind == null ? "unknown" : ownerKind.name().toLowerCase(Locale.ROOT)));

        String container = symbol.container();
        ownerRow.setDisplayed(container != null && !container.isEmpty());
        ownerText.setText(container == null ? "" : container);
        markOwnerPath(container == null ? "" : container, symbol.containerKind());

        long timed = FrameProfile.begin();
        renderDefinition(symbol);
        FrameProfile.step(timed, "doc.renderDefinition");

        String docs = symbol.documentation();
        // HIDDEN, not empty. An empty band is a gap under the definition that looks like a rendering
        // failure; no band is a popup that is simply shorter.
        // PARSED FIRST, and emptiness is asked of the DOCUMENT rather than the string. A comment that is
        // all markup and no words -- an empty `<p>`, a stray tag -- is a non-blank string that renders to
        // nothing, and testing the string would leave the separator drawn above an empty band.
        // SPLIT, because the two halves have opposite answers. Parsing markup is a pure function of a
        // STRING -- it could be done on the worker that resolved the symbol, where the string already is.
        // Building the elements for it cannot leave the frame thread at all. Only one of them is worth
        // moving, and `doc.fill` at 41ms says nothing about which.
        timed = FrameProfile.begin();
        MarkupDocument parsed = docs == null ? MarkupDocument.EMPTY : MarkupParser.parse(docs);
        FrameProfile.step(timed, "doc.parseMarkup " + (docs == null ? 0 : docs.length()) + " chars");
        timed = FrameProfile.begin();
        body.setDocument(parsed);
        FrameProfile.step(timed, "doc.setDocument");
        bodyShown = !body.isEmpty();
        // THE RULE FOLLOWS THE BAND IT DIVIDES. Left visible with no body under it, it draws a line
        // across the bottom of the popup that reads as a band which failed to load -- the same reason
        // the body itself hides rather than showing empty.
        separator.setDisplayed(bodyShown);
        body.setDisplayed(bodyShown);
        renderFooter(symbol);
    }

    /**
     * Colours the owner band's path the way the editor colours an import line.
     *
     * <h3>A capitalisation heuristic, and here it is sound</h3>
     *
     * <p>The shipped Java grammar guesses at a qualified name this way and it is a guess: it is blind to
     * {@code com.crystalgui} and fires wrongly on {@code Foo.bar}, which is exactly why the engine answers
     * for import paths instead. <b>This string is different in kind.</b> It is not source — it is
     * {@code SymbolInfo.container()}, which the analyzer built from a binding as
     * {@code package.Outer.Inner}, so the only thing a segment can be is a package fragment or a type
     * name, and Java's naming convention decides which with no ambiguity left to lose.</p>
     *
     * <p>Handed the same {@code module}/{@code type} names the editor uses rather than a private pair, so
     * one scheme colours both and a theme switch cannot leave the band behind. Dots stay uncaptured and
     * keep the band's own muted colour, which is what separates a path from a declaration.</p>
     */
    private void markOwnerPath(String path, @Nullable SymbolKind ownerKind) {
        // EVERY name cleared, on every path -- the rule the definition line already keeps, and the one
        // place the owner band did not. The last segment's band is named after the owner's KIND, so it
        // is `type.interface` for one symbol and `type.enum` or nothing at all for the next; assigning
        // only the one this symbol needs left the previous symbol's band live over a string that has
        // since been replaced. It is not a stale colour but a colour over the WRONG TEXT: hovering an
        // enum constant after an interface showed `com.crystalgui.language.grammar.Main.Severity` with
        // interface cyan across characters 10-14, which is `lgui`.
        ownerText.highlights().clear();

        // A TRAILING NOTE IS NOT PART OF THE PATH. An engine may qualify the owner it reports with why it
        // knows — JavaScript's `applyDiscount — from JSDoc`, `count — from last run` — which is real
        // information a Java answer never had to carry. Walked as a path it was coloured segment by
        // segment, so `from` and `JSDoc` were tinted as though they were a package and a type, and the
        // owner's own icon sat in front of the pair claiming they were a class.
        //
        // Split here rather than refused, because the note is worth showing and only its PRESENTATION was
        // wrong. Everything after the separator is drawn muted and marked once, and the path rules below
        // never see it.
        int note = path.indexOf(NOTE_SEPARATOR);
        int pathEnd = note < 0 ? path.length() : note;
        if (note >= 0) {
            ownerText.highlights().set(HL_OWNER_NOTE, TextRange.of(note, path.length()));
        }

        List<TextRange> packages = new ArrayList<>();
        List<TextRange> types = new ArrayList<>();
        TextRange lastType = null;
        int from = 0;
        while (from <= pathEnd) {
            int dot = path.indexOf('.', from);
            int end = dot < 0 || dot > pathEnd ? pathEnd : dot;
            // A GENERIC ARGUMENT LIST IS NOT PART OF THE PATH. `Main.Box<T>` ends its last segment at the
            // `<`, and marking through it would colour the parameters as though they were the owner.
            int stop = end;
            for (int i = from; i < end; i++) {
                if (path.charAt(i) == '<') { stop = i; break; }
            }
            if (stop > from) {
                (Character.isUpperCase(path.charAt(from)) ? types : packages)
                        .add(TextRange.of(from, stop));
                if (Character.isUpperCase(path.charAt(from))) lastType = TextRange.of(from, stop);
            }
            if (dot < 0) break;
            from = dot + 1;
        }
        // THE OWNER'S OWN KIND, from the engine, for the LAST segment -- which is the owner itself.
        // The capitalisation rule above can separate a package from a type and can never tell an
        // interface from a class, so `java.util.List` drew its interface in the class colour directly
        // under an editor drawing the same word in the interface one.
        String ownerCapture = null;
        if (lastType != null && ownerKind != null && ownerKind.isType()) {
            ownerCapture = ownerKind.captureName();
            if (!"type".equals(ownerCapture)) types.remove(lastType);
        }
        // THE OWNER'S TYPE PARAMETERS. `Main.Box<T>` ends its last SEGMENT at the `<` -- marking through
        // it would colour the parameters as though they were part of the owner's name -- and then nothing
        // marked them at all, so `<T>` sat at the band's muted colour beside a `T` the editor draws teal
        // two lines above. A container is always the DECLARATION (`Box<T>`, `ArrayList<E>`), never an
        // instantiation, so everything inside the brackets is a parameter by construction.
        List<TextRange> parameters = new ArrayList<>();
        int open = path.indexOf('<');
        for (int i = open < 0 || open > pathEnd ? pathEnd : open; i < pathEnd; ) {
            if (!Character.isJavaIdentifierStart(path.charAt(i))) { i++; continue; }
            int word = i;
            while (i < path.length() && Character.isJavaIdentifierPart(path.charAt(i))) i++;
            parameters.add(TextRange.of(word, i));
        }
        ownerText.highlights().set("type.parameter", parameters);
        ownerText.highlights().set("module", packages);
        ownerText.highlights().set("type", types);
        if (ownerCapture != null && !"type".equals(ownerCapture)) {
            ownerText.highlights().set(ownerCapture, List.of(lastType));
        }
    }

    /**
     * {@code private final Method entryPoint} — one text run, coloured by {@code ::highlight()}.
     *
     * <h3>Ranges rather than nested elements, and the reason is truncation</h3>
     *
     * <p>The obvious build is one {@link UIText} per role, which is what {@code CompletionPopup} does for
     * its two-weight label. It is wrong here because this line has to <b>ellipsise as a whole</b>: a
     * method with four generic parameters is longer than any popup should be wide, and
     * {@code text-overflow} truncates a single text run against its own box. Split across siblings there
     * is no single run to truncate — the last element would vanish entirely while the first two sat at
     * their natural width, so a long signature would lose its <em>name</em> and keep its modifiers, which
     * is the half nobody needs. One run with named ranges over it is what the Custom Highlight API exists
     * for, and truncation then falls out of it.</p>
     *
     * <p>This argued <em>wrapping</em> until the line stopped wrapping: a wrappable text element
     * contributes only its longest word to a content-sized parent's width, so the popup sized itself to
     * the owner row and broke the signature across two lines. The conclusion survived the reason
     * changing, which is worth recording rather than quietly rewriting.</p>
     *
     * <p><b>Every name is cleared before any is set.</b> A band that is merely reassigned on the paths
     * that have something to say leaves the previous symbol's ranges live on the paths that do not — and
     * the ranges are offsets into a string that has since been replaced, so the band lands on whatever
     * moved into those characters. That is the failure {@code UIText}'s own note records: not a stale
     * colour, a colour over the wrong text entirely.</p>
     */
    /**
     * The bottom band, shown only when it has somewhere to point.
     *
     * <p>{@code DeclarationSite.resource == null} means <b>this document</b> — a local, a parameter, a
     * field of the class being edited, and every symbol a script declares about itself. That is the
     * common case, and naming it "this file" is what got the band deleted the first time: it said less
     * than the owner band directly above it. So it hides, exactly as the body does when there is no
     * documentation, and what is left is the case no other band covers.</p>
     *
     * @see #FOOTER_CLASS
     */
    /**
     * The pencil's tooltip, naming the LIVE chord.
     *
     * <p>A 12px glyph with no label is unguessable, and "go to the declaration" is not what a pencil
     * suggests — IntelliJ tooltips the same control, reading "Jump to Source". Ours says the same and
     * appends whatever the keymap currently binds.</p>
     *
     * <p><b>Not from the constructor.</b> {@code Keymap.acceleratorFor} resolves outward from this
     * element, so it can only answer once the popup is in a tree whose editor has installed its keymap
     * — which is never true while the popup is being built. Re-asked as the footer renders, which is
     * once per hover and is where the row's other contents are decided anyway.</p>
     *
     * <p><b>And the Tooltip is RETAINED, never re-attached.</b> {@code Tooltip.attach} adds a listener
     * pair each time and does not replace what is there, so calling it twice leaves the first tooltip
     * showing its stale text. {@code SearchReplaceBar} and {@code StatusBarView} both carry this note;
     * this is the third place it applies.</p>
     */
    private void refreshFooterTooltip() {
        KeyChord chord = Keymap.acceleratorFor(this, EditorCommands.GO_TO_DEFINITION);
        String text = chord == null ? "Jump to Source" : "Jump to Source  " + chord;
        if (text.equals(footerTooltipText)) return;
        footerTooltipText = text;
        if (footerTooltip == null) footerTooltip = Tooltip.attach(footerEdit, text);
        else footerTooltip.setText(text);
    }

    /** What the pencil's tooltip currently says — the only observable of it. */
    @Nullable
    private String footerTooltipText;

    /** Retained, because {@code attach} adds rather than replaces. @see #refreshFooterTooltip */
    @Nullable
    private Tooltip footerTooltip;

    private void renderFooter(SymbolInfo symbol) {
        DeclarationSite site = symbol == null ? null : symbol.declaration();
        Resource resource = site == null ? null : site.resource();
        boolean elsewhere = resource != null;
        // NO RULE ABOVE THE FOOTER. It reads as a section boundary, and the footer is not a section --
        // it is a caption on the box, the way a photograph's is. IntelliJ draws none there either. The
        // row's own padding is what separates it now, which is the same distance without the line.
        footerRule.setDisplayed(false);
        footerRow.setDisplayed(elsewhere);
        if (elsewhere) refreshFooterTooltip();
        if (!elsewhere) {
            footerText.setText("");
            return;
        }
        // THE NAME, not the whole path. A popup is not a place to read a path from -- what a reader
        // wants is which file, and the pencil is what takes them to it.
        footerText.setText(resource.name());
    }

    private void renderDefinition(SymbolInfo symbol) {
        // EVERY name cleared, on every path -- see the note above. The engine-rendered path and the
        // assembled one use different name sets, so a symbol switching between them would otherwise carry
        // the other path's bands over a string it never described.
        Signature signature = symbol.signature();
        if (signature != null && !signature.isEmpty()) {
            renderEngineSignature(signature);
            return;
        }
        renderAssembledDefinition(symbol);
    }

    /**
     * Lays the signature out over as many lines as the engine broke it into.
     *
     * <h3>One {@link UIText} per line, rather than one wrapping element</h3>
     *
     * <p>Two things rule out the obvious version. The engine's {@code WhiteSpace} has {@code NORMAL} and
     * {@code NOWRAP} and no {@code PRE}, so an embedded newline has nowhere to break. And a wrapping
     * {@code UIText} cannot also <b>self-size its width</b> — it measures the unwrapped string and pushes
     * that as its width, which is what makes a short signature size this box at all. Those two are
     * mutually exclusive by construction, and every attempt to have both produced a box that was either
     * sized to the owner row or cut off mid-word.</p>
     *
     * <p>Splitting on the newline settles it: each line is a nowrap, self-sizing label, the box is as
     * wide as the longest of them, and where the breaks fall is the engine's decision made at semantic
     * points rather than the layout's made at whatever word reached the edge.</p>
     */
    private void layOutSignatureLines(String text) {
        String[] lines = text.split("\n", -1);
        while (definitionLines.size() < lines.length) {
            UIText line = new UIText("");
            line.addClass(DEFINITION_CLASS);
            line.addClass(TextEditor.SYNTAX_CLASS);
            definitionLines.add(line);
            definition.append(line);
        }
        for (int i = 0; i < definitionLines.size(); i++) {
            UIText line = definitionLines.get(i);
            // HIDDEN, never detached. A popup showing a three-line signature and then a one-line one must
            // keep the spare elements: rebuilding them lands after this frame's layout pass, which is the
            // trap a row's slots already record.
            line.setDisplayed(i < lines.length);
            // RE-MEASURE, because a line built during this very show has nothing settled to measure
            // against: it reports zero, pushes zero, and zero-in-zero-out is not a geometry change, so
            // onLayoutChanged never fires and it is never asked again. The popup then sized itself to the
            // owner row with the signature clipped -- once per process, since from the second show these
            // elements are pooled and have been through a layout pass.
            line.highlights().clear();
            line.setText(i < lines.length ? lines[i] : "");
        }
    }

    /**
     * The engine's own declaration, coloured by the capture vocabulary the editor already uses.
     *
     * <p>This is the whole point of {@link Signature} carrying tokens rather than text: the work here is
     * the same operation {@code TextEditor.ensureRowSyntax} performs for a line of code — group the ranges
     * by capture name and register them — so the signature is coloured by the same rules, from the same
     * scheme, as the code it describes. Nothing in this method knows what a modifier or an annotation is,
     * which is why a language with neither needs no change to it.</p>
     */
    private void renderEngineSignature(Signature signature) {
        definitionText = signature.text();
        layOutSignatureLines(signature.text());

        // REBASED ONTO EACH LINE by `SyntaxHighlighting`, which is where that loop now lives -- the
        // tokens index the whole declaration and every line after the first starts somewhere into it, so
        // a range handed to a line by its absolute offset lands wherever that many characters is on THAT
        // line: a colour on unrelated text rather than an error. A `<pre>` sample in a rendered doc
        // comment needs the identical operation, which is what took it out of here.
        SyntaxHighlighting.colourLines(definitionLines, signature.text(), signature.tokens());
    }

    /**
     * What a symbol reads as when no engine rendered it — a grammar-only language, a test, the keyword
     * tier.
     *
     * <p><b>Not a degraded mode.</b> Most languages will never have an engine, so this is the permanent
     * appearance for them rather than a placeholder. It hardcodes Java's declaration order, which is the
     * honest limit of what can be assembled from {@code name}, {@code kind} and {@code type} alone and
     * exactly the reason {@link Signature} exists for the languages that can do better.</p>
     */
    private void renderAssembledDefinition(SymbolInfo symbol) {
        StringBuilder line = new StringBuilder();
        List<TextRange> modifierRanges = new ArrayList<>();
        for (SymbolModifier modifier : orderedModifiers(symbol)) {
            int start = line.length();
            line.append(modifier.name().toLowerCase(Locale.ROOT)).append(' ');
            modifierRanges.add(TextRange.of(start, line.length() - 1));
        }

        TextRange typeRange = null;
        String declarationKeyword = declarationKeywordFor(symbol.kind());
        if (declarationKeyword != null) {
            // A TYPE IS ITS OWN TYPE, so rendering `type()` here printed the name twice: hovering
            // java.lang.System produced `final System System`. IntelliJ writes `public final class System`
            // -- the keyword, not a repeat -- and it is banded with the modifiers because that is what it
            // is: `class` is a keyword in the declaration, not a type reference.
            int start = line.length();
            line.append(declarationKeyword).append(' ');
            modifierRanges.add(TextRange.of(start, line.length() - 1));
        } else {
            TypeRef type = symbol.type();
            // TYPE BEFORE NAME, which is Java's order and the order every reference renders in. A language
            // whose declarations read the other way round would want this driven by the language rather
            // than hard-coded -- worth doing when there is a second one, and misleading to pretend now.
            if (type != null && !type.displayName().isEmpty()) {
                int start = line.length();
                line.append(type.displayName()).append(' ');
                typeRange = TextRange.of(start, line.length() - 1);
            }
        }

        // A SYMBOL WITH NO NAME IS A VALUE, NOT A DECLARATION, and both engines produce one deliberately:
        // `list.get(0)` resolves to a type with nothing to point go-to-definition at, which is the whole
        // answer a member lookup needs (`EcjSourceAnalyzer.expressionAt`, `RhinoResolution` line for line).
        // Resolution is shared with completion, so hover gets them too -- and rendered one unguarded, which
        // is how `TextRange.of(7, 7)` threw out of a HOVER TICK: a crash from moving the mouse over a call.
        //
        // The type alone is then the right thing to draw, and is what IntelliJ shows for an expression.
        // The separator belongs to the NAME rather than to what precedes it, or the line ends `"String "`
        // with a trailing space the box measures and no name to justify it.
        String name = symbol.name();
        TextRange nameRange = null;
        if (!name.isEmpty()) {
            int nameStart = line.length();
            line.append(name);
            nameRange = TextRange.of(nameStart, line.length());
        } else if (line.length() > 0 && line.charAt(line.length() - 1) == ' ') {
            line.setLength(line.length() - 1);
        }
        // The parameter list is deliberately NOT highlighted as a unit: its types belong to the same
        // vocabulary as the return type, and colouring the brackets with them reads as one long type name.
        line.append(symbol.parameterList());

        definitionText = line.toString();
        layOutSignatureLines(definitionText);
        UIText only = definitionLines.get(0);
        if (!modifierRanges.isEmpty()) only.highlights().set(HL_MODIFIER, modifierRanges);
        if (typeRange != null) only.highlights().set(HL_TYPE, typeRange);
        // Conditional like the two above it, and safe for the same reason: layOutSignatureLines clears
        // every band on every line before any is set, so an unset name cannot leave the previous symbol's.
        //
        // AND A TYPE'S NAME IS A TYPE. `HL_NAME` resolves to --syntax-variable, which is right for a
        // field or a local and wrong for the thing being declared: hovering a class rendered
        // `public class CgTextRenderer` with the name in the local colour, three lines under an editor
        // drawing that same word as a type. The owner band directly above had already solved this and
        // says how -- it sets the EDITOR's capture names rather than a pair only this popup uses, so one
        // scheme colours both and a scheme switch cannot leave the box behind.
        //
        // `type` first and the specific capture second, which is the owner band's shape exactly: a kind
        // whose own capture no scheme styles still lands on a real colour rather than on body text.
        if (nameRange != null) {
            String capture = declarationKeyword == null ? null : captureFor(symbol.kind());
            if (capture == null) {
                only.highlights().set(HL_NAME, nameRange);
            } else {
                only.highlights().set("type", nameRange);
                if (!"type".equals(capture)) only.highlights().set(capture, nameRange);
            }
        }
    }

    /** A kind's own capture name, or null when it is not a type being declared. */
    @Nullable
    private static String captureFor(@Nullable SymbolKind kind) {
        return kind == null ? null : kind.captureName();
    }

    /**
     * What kind of thing <b>declares</b> {@code symbol} — the icon the owner band draws.
     *
     * <h3>Inferred from the symbol's kind, because a container is only a string</h3>
     *
     * <p>{@link SymbolInfo#container()} is text ({@code java.lang}, {@code java.io.PrintStream}) and
     * carries no kind of its own, so this is a rule rather than a lookup: a <b>member</b> is declared in a
     * type, and a <b>type</b> is declared in a package. Those two cover every symbol that has an owner
     * worth drawing, and they are exactly the two cases IntelliJ shows.</p>
     *
     * <p>A <b>nested</b> type is the one case the rule alone gets wrong — {@code Map.Entry} is declared in
     * a class, not a package — and the container's last segment is the only evidence available. Java's
     * naming convention is that a type is capitalised and a package is not, which is the same signal a
     * reader uses, so it is a good heuristic and still a heuristic: a package defying the convention draws
     * a class icon. That is a wrong glyph beside a correct name, which is the cheapest way this can fail.</p>
     *
     * <p>An interface's methods draw a class icon rather than an interface one, for the same reason:
     * nothing here can tell the two apart from a qualified name. Worth fixing at the seam if it grates —
     * {@code SymbolInfo} would need the container's kind, which no consumer has needed until now.</p>
     */
    @Nullable
    private static SymbolKind ownerKindFor(SymbolInfo symbol) {
        // THE ENGINE'S ANSWER FIRST. Everything below is a guess made from the container STRING, which
        // can separate a package from a type and can never tell an interface from a class -- so every
        // member of an interface showed a class mark. `containerKind` is null for a symbol whose owner is
        // a package, and for any producer that has no binding to ask, which is what the guess is for.
        SymbolKind owner = symbol.containerKind();
        // A MODULE AND A PACKAGE ARE OWNERS TOO, and gating on `isType()` silently threw both away.
        //
        // The guard read as defensive and was a filter: an engine that had gone to the trouble of saying
        // "this member's owner is a MODULE" had its answer discarded, and the guess below then read the
        // container STRING -- `util.Greeter` looks like a type, so every member of a JavaScript module
        // drew a class mark. It hid because the module symbol ITSELF was fine: its own kind is MODULE, so
        // it reached the switch below and came out right, and only members were wrong.
        if (owner != null && (owner.isType() || owner == SymbolKind.MODULE
                || owner == SymbolKind.PACKAGE)) {
            return owner;
        }
        SymbolKind kind = symbol.kind();
        if (kind == null) return null;
        switch (kind) {
            case CLASS:
            case INTERFACE:
            case ENUM:
            case RECORD:
            case ANNOTATION:
            case EXCEPTION:
            case TYPE_PARAMETER:
                return containerLooksLikeAType(symbol.container()) ? SymbolKind.CLASS : SymbolKind.PACKAGE;
            case PACKAGE:
            case MODULE:
                return SymbolKind.PACKAGE;
            default:
                return SymbolKind.CLASS;
        }
    }

    private static boolean containerLooksLikeAType(@Nullable String container) {
        if (container == null || container.isEmpty()) return false;
        String last = container.substring(container.lastIndexOf('.') + 1);
        return !last.isEmpty() && Character.isUpperCase(last.charAt(0));
    }

    /**
     * The keyword a type-like symbol is <em>declared</em> with, or null for everything else.
     *
     * <p>{@code EXCEPTION} is absent deliberately: it is a class, and Java has no {@code exception}
     * keyword to write. {@code TYPE_PARAMETER} is absent for the opposite reason — a {@code <T>} is
     * declared with no keyword at all, so the bare name is the whole of it.</p>
     */
    @Nullable
    private static String declarationKeywordFor(@Nullable SymbolKind kind) {
        if (kind == null) return null;
        switch (kind) {
            case CLASS:
            case EXCEPTION:
                return "class";
            case INTERFACE:
                return "interface";
            case ENUM:
                return "enum";
            case RECORD:
                return "record";
            case ANNOTATION:
                return "@interface";
            case PACKAGE:
                return "package";
            case MODULE:
                return "module";
            default:
                return null;
        }
    }

    /**
     * Modifiers in <b>source order</b>, not in enum order or alphabetically.
     *
     * <p>{@link SymbolInfo#modifiers()} is a {@code Set}, so it has no order to offer and a plain iteration
     * would render an implementation detail as text. Java's conventional order is what every declaration in
     * every file is written in, so anything else reads as a typo in the popup rather than as a choice —
     * {@code final static} is not wrong so much as jarring.</p>
     *
     * <p><b>{@link SymbolModifier} carries no visibility</b>, so this renders {@code static final Method x}
     * where IntelliJ renders {@code private static final Method x}. That is the seam's shape and not a gap
     * to paper over here: adding {@code PUBLIC}/{@code PROTECTED}/{@code PRIVATE} is an edit to the enum
     * plus every engine that populates it, and the popup would then show a visibility only ECJ filled in.
     * {@code DEPRECATED} is excluded for a different reason — it is not a declaration keyword, and both
     * references draw it as a strike-through on the name rather than as a word in the signature.</p>
     */
    private static List<SymbolModifier> orderedModifiers(SymbolInfo symbol) {
        List<SymbolModifier> ordered = new ArrayList<>(3);
        for (SymbolModifier candidate : new SymbolModifier[] {
                SymbolModifier.STATIC, SymbolModifier.ABSTRACT, SymbolModifier.FINAL }) {
            if (symbol.is(candidate)) ordered.add(candidate);
        }
        return ordered;
    }
}
