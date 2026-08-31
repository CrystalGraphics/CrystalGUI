package com.crystalgui.widget.texteditor.part;

import com.crystalgui.core.async.FrameProfile;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.widget.texteditor.TextEditor;
import com.crystalgui.widget.texteditor.lang.EditorLanguageFeatures;
import dev.vfyjxf.taffy.style.TaffyPosition;

/**
 * A bulb in the gutter on the caret's row when there is something to offer there — IntelliJ's.
 *
 * <h3>Why it exists at all</h3>
 *
 * <p>Alt+Enter and the error stripe between them cover everything except the case that matters most: a
 * problem on the line you are already looking at. The stripe answers "where are the problems in this
 * file", and the key answers "show me the actions" — but only if you already suspect there are any. A
 * bulb is the only affordance that says <em>here, now</em>, and without one the whole feature is
 * discoverable by prior knowledge alone.</p>
 *
 * <h3>It is driven by the DIAGNOSTIC, not by the action list</h3>
 *
 * <p>The obvious rule is "show it when there are actions", and it is the wrong one: actions come from an
 * engine asynchronously, so a bulb keyed on them would fire a request per frame for the caret's row and
 * flicker on whatever came back. Keyed on whether a diagnostic covers the caret it is synchronous — the
 * tracked ranges are already in the buffer — and it costs nothing per frame.</p>
 *
 * <p>That is honest rather than approximate, and only because of what the merge guarantees: every
 * diagnostic offers at least the shape-derived actions, so a bulb over a problem can never promise a list
 * that turns out to be empty.</p>
 *
 * <p><b>And the rule did have to change, exactly as that note said it would.</b> It held only while every
 * action came from a problem; an INTENTION has none, so "Replace with lambda" lit no bulb, marked no
 * stripe and left a working feature discoverable by prior knowledge alone. The diagnostic path is still
 * the synchronous fast path — see {@link #somethingToOffer()} for the half that asks, and for why it asks
 * once per caret move rather than once per frame.</p>
 */
public final class QuickFixBulbPart extends EditorViewPart {

    static final String BULB_CLASS = "__quick-fix-bulb__";

    private UINode bulb;

    public QuickFixBulbPart(TextEditor editor) {
        super(editor);
    }

    @Override
    public void render(int firstViewLine, int lastViewLine) {
        long timed = FrameProfile.begin();
        boolean offer = editor.isGutterVisible() && somethingToOffer();
        FrameProfile.step(timed, "bulb.somethingToOffer -> " + offer);
        if (!offer) {
            hide();
            return;
        }
        int viewLine = editor.viewLineOf(editor.getCaret(),
                com.crystalgui.text.wrap.LineProjection.Affinity.LEFT);
        if (viewLine < firstViewLine || viewLine > lastViewLine) {
            // The caret is scrolled off. Hidden rather than clamped to an edge: a bulb pinned to the top
            // of the gutter would claim there is something to fix on a row that is merely visible.
            hide();
            return;
        }
        long placed = FrameProfile.begin();
        bulbElement().setDisplayed(true);

        float height = editor.lineHeight();
        // IN THE FOLD COLUMN, which is the gutter's own decoration lane -- the same box the fold arrows
        // live in, so the bulb lines up with them and needs no geometry of its own.
        final float top = editor.topOfViewLine(viewLine);
        final float width = editor.gutterFoldWidth();
        StyleGroup.defaultPipeline(bulbElement().getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE)
                        .left(0f).top(top).width(width).height(height));
        FrameProfile.step(placed, "bulb.place");
    }

    /**
     * Whether the caret has anything behind it — a diagnostic synchronously, an intention by asking.
     *
     * <h3>The synchronous half is still the fast path and still carries the common case</h3>
     *
     * <p>A diagnostic is a tracked range already in the buffer, so "is there a problem here" costs
     * nothing and cannot flicker. Every diagnostic offers at least the shape-derived actions, so a bulb
     * lit that way can never promise a list that turns out to be empty.</p>
     *
     * <h3>And the other half is what this class's own note said to add when it became necessary</h3>
     *
     * <p>The old rule was <em>bulb ⟺ diagnostic</em>, which was the same thing as <em>bulb ⟺ actions</em>
     * only while every action came from a problem. <b>Intentions broke that</b>: "Replace with lambda"
     * fires on code where nothing is wrong, so there is no diagnostic, no stripe mark and — under the old
     * rule — no bulb. The feature shipped invisible: correct, reachable by Alt+Enter, and discoverable by
     * prior knowledge alone, which is exactly the state the class header says a bulb exists to prevent.</p>
     *
     * <p><b>One ask per caret MOVE, never per frame.</b> The warning against keying on the action list was
     * about firing a request every frame for the caret's row, and it stands — so the answer is remembered
     * against the offset it was asked for, and a caret that has not moved re-uses it. A stale answer can
     * only survive an edit that leaves the caret where it was, which the next move corrects.</p>
     */
    private boolean somethingToOffer() {
        // NOTHING TO OFFER A DOCUMENT NOBODY CAN EDIT, and asking anyway was measured at 24.8ms on a
        // frame -- `bulb.somethingToOffer -> false 24793us` on the frame that opens a 1,980-line
        // decompiled class, to reach a conclusion that was available from a boolean.
        //
        // The intention half below runs the whole quick-fix catalog over the unit (JavaQuickFixes.in),
        // synchronously, from a PAINT. Every action it can find is unusable here by construction: the
        // edit path refuses a read-only buffer, so a bulb lit on one would open a menu whose every row
        // does nothing. IntelliJ does not run intention availability in a read-only file either.
        //
        // The DIAGNOSTIC half is skipped with it and that is right rather than incidental: a viewer
        // suppresses diagnostics outright (@see JavaLanguageServices#forLibrary), so there is nothing
        // for it to find. This is a bulb, and a bulb is an offer to change something.
        if (editor.isReadOnly()) return false;
        int caret = editor.getCaret();
        if (!editor.diagnosticsAt(caret).isEmpty()) {
            // Asked again when the caret comes back to a clean position, rather than trusting an answer
            // taken while a problem was covering it.
            askedFor = -1;
            return true;
        }
        // NOT WHILE THE USER IS TYPING, and the cache is why it has to be said explicitly.
        //
        // The answer is remembered against the caret OFFSET, which is exactly right for arrow keys and
        // useless for typing: every keystroke moves the caret, so the cache never hits and the whole
        // quick-fix catalog is run over the unit -- synchronously, from a paint -- once per character.
        // Measured at 208us a keystroke over seventy of them, plus the `java.codeActionsIn` behind it.
        //
        // The last answer is reused for the length of the typing settle, exactly as `settleSyntaxIfIdle`
        // reuses the last colouring. Neither reference computes intentions between keystrokes either.
        // The staleness is bounded by construction: the settle expires 300ms after the last edit and the
        // next frame asks properly, so the worst case is a bulb that lights a third of a second after
        // you stop typing -- which is when you would look for it.
        if (caret != askedFor && !editor.isTyping()) {
            askedFor = caret;
            intentionHere = false;
            editor.langFeatures().requestCodeActions(EditorLanguageFeatures.LANE_BULB, caret, actions -> {
                if (caret == askedFor) intentionHere = !actions.isEmpty();
            });
        }
        return intentionHere;
    }

    /** The caret offset the outstanding answer belongs to; {@code -1} when there is none. */
    private int askedFor = -1;
    private boolean intentionHere;

    /**
     * <b>{@code display}, not a collapsed box</b> — and it has to be, which is a trap for the next
     * pooled decoration that gets a size from the sheet.
     *
     * <p>{@code DecorationPool.hide} writes {@code width: 0; height: 0} at <b>DEFAULT</b> origin, which
     * is how every other part here retires an element. It cannot work for this one: the bulb's size comes
     * from a {@code .__quick-fix-bulb__} rule at <b>STYLESHEET</b> origin, and the cascade ranks that
     * above DEFAULT — so the write was a no-op and the bulb stayed 12×12 for ever. Since the render path
     * returns early once hidden, its {@code top} also stopped being updated, and it sat frozen on the last
     * row it had been valid for: a bulb that follows you around the file, claiming a fix on whatever line
     * it happens to be next to.</p>
     *
     * <p>{@code setDisplayed} writes at IMPORTANT, which outranks the sheet, so this is the one hide that
     * survives having a styled size. The squiggle bands are unaffected because nothing in CSS gives them
     * one — their geometry is entirely Java's, which is why the pool's idiom works there.</p>
     */
    private void hide() {
        if (bulb != null) bulb.setDisplayed(false);
    }

    private UINode bulbElement() {
        if (bulb == null) {
            bulb = new UINode();
            bulb.addClass(BULB_CLASS);
            // THE CARET'S ROW IS READ AT PRESS TIME, never captured: this element outlives every caret
            // position it is ever shown for, so a listener holding the row it was built for would open
            // the actions for wherever the caret happened to be the first time.
            bulb.onMouseDown.attachListener((element, event) -> {
                editor.showCodeActionsAt(editor.getCaret());
                event.stopPropagation();
            }, false, false);
            editor.foldLayer().append(bulb);
        }
        return bulb;
    }
}
