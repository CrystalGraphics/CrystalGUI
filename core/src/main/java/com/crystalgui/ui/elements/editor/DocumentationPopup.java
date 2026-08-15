package com.crystalgui.ui.elements.editor;

import com.crystalgui.text.lang.Signature;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.SymbolModifier;
import com.crystalgui.text.lang.TypeRef;
import com.crystalgui.text.syntax.SyntaxToken;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Popover;
import com.crystalgui.text.lang.CodeAction;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.text.TextRange;
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
 * <p><b>The body is empty for every symbol today</b>, because no engine populates {@code documentation}
 * yet. That is fine and is why the work was ordered this way round: the definition and the location are
 * both derivable from what {@code SymbolInfo} already carries, so the popup is useful on day one and grows
 * a body without being touched. It hides the band rather than showing an empty one.</p>
 *
 * <h3>{@code Popover}, in {@code AUTO} — and not a {@code Tooltip}</h3>
 *
 * <p>A tooltip is transient and unfocusable. This box scrolls, has a control in its footer, and must
 * survive the pointer leaving the word — so it is a popover, and {@code AUTO} buys light dismiss and
 * Escape from the two stacks {@code UIWindow} already keeps, with no new machinery.</p>
 *
 * <p>It does <b>not</b> take focus, for the same reason the completion popup does not: the caret belongs
 * in the document, and a box that appears under it must not take the caret out of the text. It is
 * dismissed rather than tabbed into.</p>
 */
public final class DocumentationPopup extends Popover {

    public static final String POPUP_CLASS = "__documentation__";
    public static final String OWNER_CLASS = "__doc-owner__";
    public static final String OWNER_ICON_CLASS = "__doc-owner-icon__";
    public static final String OWNER_TEXT_CLASS = "__doc-owner-text__";
    public static final String DEFINITION_CLASS = "__doc-definition__";
    public static final String DEFINITION_BOX_CLASS = "__doc-definition-box__";
    public static final String BODY_CLASS = "__doc-body__";
    public static final String PROBLEM_CLASS = "__doc-problem__";
    public static final String PROBLEM_MESSAGE_CLASS = "__doc-problem-message__";
    public static final String PROBLEM_ACTIONS_CLASS = "__doc-problem-actions__";
    public static final String PROBLEM_ACTION_CLASS = "__doc-problem-action__";
    public static final String PROBLEM_SHORTCUT_CLASS = "__doc-problem-shortcut__";

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

    private final UIElement ownerRow = new UIElement();
    private final UIElement ownerIcon = new UIElement();
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
    private final UIElement definition = new UIElement();
    private final List<UIText> definitionLines = new ArrayList<>();
    private String definitionText = "";
    private final UIText body = new UIText("");

    /**
     * The problem section — message, then the one action worth showing without being asked.
     *
     * <p>One action inline and everything else behind "More actions…", which is IntelliJ's arrangement and
     * is what keeps a hover the size of a hover. A popup that listed every contributor's answers would be
     * taller than the code it is explaining before it said anything about the code.</p>
     */
    private final UIElement problemRow = new UIElement();
    private final UIText problemMessage = new UIText("");
    private final UIElement problemActions = new UIElement();
    private final UIText primaryAction = new UIText("");
    private final UIText primaryShortcut = new UIText("");
    private final UIText moreActions = new UIText("");
    private final UIText moreShortcut = new UIText("");

    /** What the inline action would apply, and what the overflow menu would list. */
    private final java.util.List<CodeAction> actions = new java.util.ArrayList<>();

    /** Tracked rather than read back: display is a style write, not a queryable flag. */
    private boolean problemShown;

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

    public DocumentationPopup() {
        addClass(POPUP_CLASS);
        // AUTO: light dismiss and Escape, both from the stacks UIWindow already keeps. MANUAL would mean
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
        ownerRow.addInternalChild(ownerIcon);
        ownerRow.addInternalChild(ownerText);

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

        ownerText.forceSelfSizeWidth();
        body.addClass(BODY_CLASS);

        problemMessage.addClass(PROBLEM_MESSAGE_CLASS);
        problemMessage.forceSelfSizeWidth();
        problemActions.addClass(PROBLEM_ACTIONS_CLASS);
        primaryAction.addClass(PROBLEM_ACTION_CLASS);
        primaryShortcut.addClass(PROBLEM_SHORTCUT_CLASS);
        moreActions.addClass(PROBLEM_ACTION_CLASS);
        moreActions.setText("More actions…");
        moreShortcut.addClass(PROBLEM_SHORTCUT_CLASS);
        moreShortcut.setText("Alt+Enter");
        problemActions.addInternalChild(primaryAction);
        problemActions.addInternalChild(primaryShortcut);
        problemActions.addInternalChild(moreActions);
        problemActions.addInternalChild(moreShortcut);
        problemRow.addClass(PROBLEM_CLASS);
        problemRow.addInternalChild(problemMessage);
        problemRow.addInternalChild(problemActions);
        // HIDDEN, not absent. The section is built once and shown per symbol, because an element created
        // during fill() lands after that frame's layout pass -- the trap the command palette's key chips
        // and the editor's gutter arrows each paid for.
        problemRow.setDisplayed(false);
        // ON THE MOUSE-DOWN rather than a press pair, because this popup is light-dismissable: a press
        // outside an AUTO popover closes it, and the row would be gone before any mouse-up arrived.
        primaryAction.onMouseDown.attachListener((element, event) -> {
            CodeAction primary = primaryOf(actions);
            if (primary != null) onActionChosen.emit(primary);
            event.stopPropagation();
        }, false, true);
        moreActions.onMouseDown.attachListener((element, event) -> {
            onMoreActions.emit();
            event.stopPropagation();
        }, false, true);

        // ABOVE the owner, which is IntelliJ's order and is not arbitrary: the problem is why you looked,
        // the declaration is what you were looking at. A popup that shows one instead of the other
        // regresses hover for every symbol that happens to carry a warning.
        addInternalChild(problemRow);
        addInternalChild(ownerRow);
        addInternalChild(definition);
        addInternalChild(body);

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
        problemShown = any;
        problemRow.setDisplayed(any);
        if (!any) return;

        StringBuilder message = new StringBuilder();
        for (com.crystalgui.text.diagnostic.Diagnostic problem : problems) {
            if (message.length() > 0) message.append(" · ");
            message.append(problem.message());
        }
        problemMessage.invalidateMeasurement();
        problemMessage.setText(message.toString());

        CodeAction primary = primaryOf(actions);
        boolean hasPrimary = primary != null;
        primaryAction.setDisplayed(hasPrimary);
        primaryShortcut.setDisplayed(hasPrimary);
        if (hasPrimary) {
            primaryAction.invalidateMeasurement();
            primaryAction.setText(primary.title());
            primaryShortcut.setText("Alt+Shift+Enter");
        }
        // MORE ACTIONS IS SHOWN WHENEVER THERE IS ANYTHING AT ALL, including when the only thing is the
        // primary: IntelliJ shows it beside a single fix too, because the menu is also how you reach the
        // things that are never inline. It hides only when the list is genuinely empty, which is a problem
        // nobody can do anything about rather than one whose menu happens to be short.
        boolean anyAction = !actions.isEmpty();
        moreActions.setDisplayed(anyAction);
        moreShortcut.setDisplayed(anyAction);
        problemActions.setDisplayed(anyAction);
    }

    /** The one shown without being asked — first preferred quick fix, or nothing. */
    @Nullable
    private static CodeAction primaryOf(List<CodeAction> actions) {
        for (CodeAction action : actions) {
            if (action.preferred()) return action;
        }
        return null;
    }

    /** What the problem section is currently offering — the observable a test asserts on. */
    public List<CodeAction> offeredActions() {
        return List.copyOf(actions);
    }

    /** What the problem band says, or empty. */
    public String problemText() {
        return problemShown ? problemMessage.getText() : "";
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
    public void show(UIWindow window, SymbolInfo symbol, float x, float y, float lineHeight) {
        this.shown = symbol;
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
        if (getParent() == null) window.addOverlay(this, null);
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
        showAt(x, y + lineHeight, null);
        fill(symbol);
    }

    @Override
    public Popover hide() {
        shown = null;
        return super.hide();
    }

    private void fill(SymbolInfo symbol) {
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

        renderDefinition(symbol);

        String docs = symbol.documentation();
        // HIDDEN, not empty. An empty band is a gap under the definition that looks like a rendering
        // failure; no band is a popup that is simply shorter.
        bodyShown = docs != null && !docs.isBlank();
        body.setDisplayed(bodyShown);
        body.setText(docs == null ? "" : docs);
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
        List<TextRange> packages = new ArrayList<>();
        List<TextRange> types = new ArrayList<>();
        TextRange lastType = null;
        int from = 0;
        while (from <= path.length()) {
            int dot = path.indexOf('.', from);
            int end = dot < 0 ? path.length() : dot;
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
        for (int i = open < 0 ? path.length() : open; i < path.length(); ) {
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
            line.forceSelfSizeWidth();
            definitionLines.add(line);
            definition.addInternalChild(line);
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
            line.invalidateMeasurement();
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

        // REBASED ONTO EACH LINE. The tokens index the whole declaration, and every line after the first
        // starts somewhere into it -- so a range handed to the wrong element by its absolute offset lands
        // wherever that many characters is on THAT line, which is a colour on unrelated text rather than
        // an error.
        String[] lines = signature.text().split("\n", -1);
        int[] lineStart = new int[lines.length];
        for (int i = 1; i < lines.length; i++) {
            lineStart[i] = lineStart[i - 1] + lines[i - 1].length() + 1;
        }
        List<Map<String, List<TextRange>>> perLine = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) perLine.add(new LinkedHashMap<>());

        for (SyntaxToken token : signature.tokens()) {
            for (int i = 0; i < lines.length; i++) {
                int from = lineStart[i];
                int to = from + lines[i].length();
                int start = Math.max(token.start(), from);
                int end = Math.min(token.end(), to);
                if (end <= start) continue;
                perLine.get(i).computeIfAbsent(token.name(), any -> new ArrayList<>())
                        .add(TextRange.of(start - from, end - from));
            }
        }
        for (int i = 0; i < lines.length; i++) {
            UIText line = definitionLines.get(i);
            for (Map.Entry<String, List<TextRange>> entry : perLine.get(i).entrySet()) {
                line.highlights().set(entry.getKey(), entry.getValue());
            }
        }
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

        int nameStart = line.length();
        line.append(symbol.name());
        TextRange nameRange = TextRange.of(nameStart, line.length());
        // The parameter list is deliberately NOT highlighted as a unit: its types belong to the same
        // vocabulary as the return type, and colouring the brackets with them reads as one long type name.
        line.append(symbol.parameterList());

        definitionText = line.toString();
        layOutSignatureLines(definitionText);
        UIText only = definitionLines.get(0);
        if (!modifierRanges.isEmpty()) only.highlights().set(HL_MODIFIER, modifierRanges);
        if (typeRange != null) only.highlights().set(HL_TYPE, typeRange);
        only.highlights().set(HL_NAME, nameRange);
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
        if (owner != null && owner.isType()) return owner;
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
