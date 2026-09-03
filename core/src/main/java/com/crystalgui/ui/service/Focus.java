package com.crystalgui.ui.service;

import com.crystalgui.ui.dom.UIElement;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.ShadowRoot;
import com.crystalgui.ui.event.FocusEvent;
import com.crystalgui.ui.input.FocusSource;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * The focus service: one owner, one traversal, and ONE inertness predicate — over focus navigation
 * scopes rather than over the whole tree.
 *
 * <h3>What a scope is</h3>
 *
 * <p>A node carrying {@link Attribute#FOCUS_SCOPE} (a dialog, a window frame, a workbench pane) and
 * a shadow root that {@link ShadowRoot#delegatesFocus() delegates focus}. The document is the
 * outermost. Everything the old engine spelled per-feature is a question about a scope here: a modal
 * blocks its own scope, Tab is trapped inside the scope that is blocked, and "is focus already in
 * this window" is asked of the composed tree.</p>
 *
 * <h3>Modality is a property of a scope, enforced ONCE</h3>
 *
 * <p>The old engine enforced it at four points on purpose — {@code focusable()} and the
 * focusable-descendant cache saw only the inert ATTRIBUTE, Tab was scoped at its entry point,
 * hit-testing skipped inert subtrees, and {@code requestFocus} consulted the full predicate — because
 * the modal half changes for nearly every element the instant a modal opens, so anything cached that
 * depended on it needed mass invalidation. There is no such cache here: {@link #isInert} is the one
 * predicate, and the two readers that matter ({@link Input}'s hit test and this class) both ask it.</p>
 *
 * <h3>A focusable container is not a wall</h3>
 *
 * <p>{@code DockArea}, {@code GraphView} and {@code ListView} are all focusable so their COMMANDS
 * resolve, and the cost in the old engine was that focus delegation stopped on them — a torn-out
 * editor took focus and then refused every keystroke, because events dispatch root→target→root and a
 * descendant of the target is never on the path. Here a container that knows better attaches a
 * shadow root with {@code delegatesFocus}, and focusing it focuses what is inside.</p>
 */
public final class Focus {

    private final UIDocument document;
    private final List<UIElement> modals = new ArrayList<>();

    @Nullable
    private UIElement focused;
    @Nullable
    private UIElement announced;

    /**
     * <b>The focus owner changed.</b> Carries the new one, or null. {@code FocusEvent} answers "did
     * I gain focus" for something on the path; this answers "who holds focus now", which is what an
     * inspector, a context-sensitive toolbar and a status line each need. Deduplicated, so the
     * blur-then-focus pair one click produces announces two states and never the same one twice.
     */
    public final Signal.Value<UIElement> onDidChangeFocus = new Signal.Value<>();

    public Focus(UIDocument document) {
        this.document = document;
    }

    @Nullable
    public UIElement focused() {
        return focused;
    }

    /** What an observer at {@code relativeTo} is told holds focus — retargeted out of shadow trees. */
    @Nullable
    public UIElement focusedFor(@Nullable UIElement relativeTo) {
        return focused == null ? null : UIElement.retarget(focused, relativeTo);
    }

    // ── Predicates ───────────────────────────────────────────────────────────

    /** May this node hold focus at all? A superset of {@link #tabbable}. */
    public boolean focusable(@Nullable UIElement node) {
        if (node == null || !node.isConnected() || node.isFrozen()) return false;
        if (!node.focusPolicy().isFocusable() || !node.isEnabled()) return false;
        // Not rendered is not focusable -- the DOM's rule for `display: none`, asked of the box tree,
        // which is the one thing that knows. Before the first layout there are no boxes at all, and a
        // host may legitimately focus something then, so the question is only put once there are.
        //
        // NO BOX IS TWO DIFFERENT ANSWERS, and only one of them means "not rendered". A node that has
        // just been ATTACHED has no box either, and will have one the moment layout next runs -- so the
        // box alone refuses focus to anything focused in the same breath as being added.
        //
        // Which is the ordinary case, not a corner: `UIDocument.frame` runs layout and dispatches input
        // in `endFrame` AFTER it, so a popup opened by a chord builds its tree at the very end of a
        // frame and cannot be laid out until the next one. Every such widget focuses its field the
        // moment it opens -- `QuickPick.onOpened` calls `requestFocus` and the caret is the whole point
        // of the widget -- and every one of those requests was refused in silence: the command palette
        // and Go to Class both opened with the caret nowhere, so typing went to whatever held focus
        // before.
        //
        // So ask what "not rendered" actually means, and ask it of the CASCADE, which has an answer
        // before layout does. Only reached when there is no box, so nothing on the hot path pays for it.
        if (node.box() == null && document.boxes().root() != null && !willBeLaidOut(node)) return false;
        return !isInert(node);
    }


    /**
     * Whether a node with no box is merely waiting for layout rather than switched off.
     *
     * <p>A box is absent for two unrelated reasons — the subtree is not displayed, or it has not been
     * laid out yet — and {@link #focusable} must separate them. {@code display} and the {@code hidden}
     * attribute are both answerable from the cascade the instant a node is attached, which is what makes
     * this the question layout cannot yet answer.</p>
     */
    private boolean willBeLaidOut(UIElement node) {
        for (UIElement at = node; at != null; at = at.composedParent()) {
            if (!at.isDisplayed()) return false;
            if (at.computedStyle().get(LayoutProperties.DISPLAY) == TaffyDisplay.NONE) return false;
        }
        return true;
    }

    /** Is this node in the Tab sequence? {@code CLICK_NOT_TABBABLE} is focusable and is not. */
    public boolean tabbable(@Nullable UIElement node) {
        return focusable(node) && node.focusPolicy().isTabbable();
    }

    /**
     * The spec's full predicate: this node or a composed ancestor carries {@code inert}, OR a modal
     * over this node's scope is open and this node is not inside it.
     */
    public boolean isInert(@Nullable UIElement node) {
        if (node == null) return false;
        for (UIElement at = node; at != null; at = at.composedParent()) {
            if (at.get(Attribute.INERT)) return true;
        }
        return blockingModal(node) != null;
    }

    /**
     * The modal responsible for blocking {@code node}, or null.
     *
     * <p>Asked of the node's OWN scope: with per-window modality, blaming the globally topmost modal
     * pulses a window the user is not looking at while the one they clicked stays silent — worse
     * than saying nothing, because it points somewhere.</p>
     */
    @Nullable
    public UIElement blockingModal(@Nullable UIElement node) {
        if (node == null) return null;
        for (int i = modals.size() - 1; i >= 0; i--) {
            UIElement modal = modals.get(i);
            if (!modal.isConnected()) continue;
            // The first modal whose scope contains this node decides -- a modal in another window's
            // scope is not this node's business, which is the whole point of scoping it.
            //
            // The scope a modal blocks is the one CONTAINING it, never its own: a dialog is a focus
            // scope itself, so asking scopeOf(modal) answers the dialog, which contains nothing
            // outside it -- and then nothing anywhere is ever blocked.
            if (!contains(blockedScopeOf(modal), node)) continue;
            return contains(modal, node) ? null : modal;
        }
        return null;
    }

    // ── Scopes and modality ──────────────────────────────────────────────────

    /** The nearest enclosing focus navigation scope: a scope node, a delegating shadow root, or the document. */
    public UIElement scopeOf(@Nullable UIElement node) {
        for (UIElement at = node; at != null; at = at.composedParent()) {
            if (at.get(Attribute.FOCUS_SCOPE)) return at;
            if (at instanceof ShadowRoot && ((ShadowRoot) at).delegatesFocus()) return at;
        }
        return document;
    }

    /**
     * Opens {@code modal}: everything in its scope that is not inside it becomes inert, and Tab is
     * trapped within it. Nesting works and unwinds in order.
     */
    public void pushModal(UIElement modal) {
        if (modals.contains(modal)) return;
        modals.add(modal);
        modalityChanged();
    }

    public void popModal(UIElement modal) {
        if (modals.remove(modal)) modalityChanged();
    }

    /**
     * Opening or closing a modal changes WHAT IS HITTABLE without the pointer moving and without a
     * frame having run, so the hover cache is told — the same reason {@code beginFrame} invalidates
     * it unconditionally, one gesture earlier. Without this a press arriving between the modal
     * opening and the next frame is answered from a hit resolved when nothing was blocked.
     */
    private void modalityChanged() {
        document.input().invalidateHover();
    }

    /** The open modals, in the order they were opened. */
    public List<UIElement> modals() {
        return List.copyOf(modals);
    }

    /** The scope a modal makes inert: the nearest one ABOVE it. */
    /**
     * The scope {@code modal} makes inert: the nearest one ABOVE it, never its own.
     *
     * <p>A dialog is a focus scope itself, so asking {@code scopeOf(modal)} answers the dialog — which
     * contains nothing but the modal, so nothing would ever be blocked.</p>
     */
    public UIElement blockedScopeOf(UIElement modal) {
        UIElement above = modal.composedParent();
        return above == null ? document : scopeOf(above);
    }

    private static boolean contains(UIElement ancestor, UIElement node) {
        return UIElement.isShadowIncludingInclusiveAncestor(ancestor, node);
    }

    // ── Moving focus ─────────────────────────────────────────────────────────

    /**
     * The DOM's {@code element.focus()} — programmatic, so it rings and scrolls its target into
     * view. Focus that lands somewhere invisible is focus the user cannot see.
     */
    public void requestFocus(@Nullable UIElement node) {
        moveTo(node, FocusSource.PROGRAMMATIC);
    }

    /**
     * Focus because THE POINTER went there — no ring and no scroll, exactly like a click. Separate
     * from {@link #requestFocus} because that one rings, and focus-follows-hover through it would
     * draw a ring on whatever the mouse passed over, which is the noise {@code :focus-visible} exists
     * to remove.
     */
    public void requestPointerFocus(@Nullable UIElement node) {
        moveTo(node, FocusSource.POINTER);
    }

    private void moveTo(@Nullable UIElement node, FocusSource source) {
        UIElement target = delegate(node);
        if (target == null) return;
        if (!focusable(target)) return;
        if (focused == target) return;
        if (focused != null) blur(focused);
        focus(target, source);
    }

    /** A host that delegates focus hands it to the first focusable thing inside its shadow tree. */
    @Nullable
    private UIElement delegate(@Nullable UIElement node) {
        if (node == null) return null;
        ShadowRoot shadow = node.shadowRoot();
        if (shadow == null || !shadow.delegatesFocus()) return node;
        UIElement inside = firstFocusableIn(shadow);
        return inside != null ? inside : node;
    }

    /** Drops focus entirely — the DOM's {@code blur()} on whatever holds it. */
    public void clear() {
        if (focused != null) blur(focused);
    }

    /** Drops focus if — and only if — this node holds it. */
    public void blurIfFocused(@Nullable UIElement node) {
        if (node != null && focused == node) blur(node);
    }

    private void focus(UIElement target, FocusSource source) {
        focused = target;
        // A focused text field rings however it was focused: a caret alone is a weak affordance and
        // the field is where typing goes. Everything else stays unringed after a click.
        target.setFocused(true, source.ringsByDefault() || target.consumesTextInput());
        // Anything that is NOT a click reveals its target: focus that lands off-screen is focus the
        // user cannot see. A click cannot need it -- you clicked what you could already see -- and
        // scrolling there would pull the content out from under the cursor.
        if (source.scrollsIntoView() && target.box() != null) target.box().scrollIntoView();
        for (UIElement at = target.composedParent(); at != null; at = at.composedParent()) at.setFocusWithin(true);
        send(target, new FocusEvent.Focus(target));
        announce();
    }

    private void blur(UIElement target) {
        focused = null;
        target.setFocused(false, false);
        target.setPressed(false);
        for (UIElement at = target.composedParent(); at != null; at = at.composedParent()) at.setFocusWithin(false);
        send(target, new FocusEvent.Blur(target));
        announce();
    }

    private void send(UIElement target, FocusEvent event) {
        document.input().send(target, event);
    }

    private void announce() {
        if (announced == focused) return;
        announced = focused;
        onDidChangeFocus.emit(focused);
    }

    // ── The click path ───────────────────────────────────────────────────────

    /**
     * Where a press puts focus: the nearest COMPOSED ancestor of the hit that takes focus on click.
     *
     * <p>The nearest ancestor rather than the exact node is the DOM's rule — it is why clicking a
     * button's inner text focuses the button — and the old engine's composites dodged it by making
     * their parts unhittable, which stops working the moment a part is interactive: a tree's fold
     * chevron keeps the pointer and is never focusable, so a press on it blurred the owner and
     * focused nothing, and the keyboard went dead with no ring anywhere.</p>
     *
     * <p>Only the PRIMARY button moves focus. A right-click opens a menu ABOUT something; it does not
     * choose it — and a list that drives its selection from focus would otherwise have its selection
     * destroyed by the menu opened over it.</p>
     */
    void pressed(@Nullable UIElement target, int button, boolean absorbedByModal) {
        if (button != CgMouseCodes.LEFT_BUTTON) return;
        // A press that hit nothing normally blurs, as a browser does. But while a modal is open, "hit
        // nothing" can mean inertness ATE the press, and dropping the caret out of a dialog's field
        // when its backdrop is clicked is what no dialog anywhere does.
        //
        // TOLD, NOT ASKED. This used to test `blockingModal(document)` itself, which was right while
        // modality was global and became permanently false the moment a window became a focus scope:
        // a modal inside a window does not block the DOCUMENT's scope, so the guard never fired and
        // every press on a blocked window blurred the focus owner as if it were bare desktop. Only the
        // caller knows WHERE the press landed, and that is the whole question -- so the caller answers
        // it. @see Input
        if (target == null && absorbedByModal) return;

        UIElement focusTarget = target;
        while (focusTarget != null && !focusTarget.focusPolicy().focusesOnClick()) {
            focusTarget = focusTarget.composedParent();
        }
        if (focusTarget == focused) return;
        if (focused != null) blur(focused);
        if (focusTarget != null) focus(delegate(focusTarget), FocusSource.POINTER);
    }

    // ── Traversal ────────────────────────────────────────────────────────────

    /**
     * Tab and Shift+Tab, trapped inside whatever modal is over the focused scope. Wraps at both ends.
     *
     * @return whether the keystroke was spent
     */
    public boolean moveTabFocus(int key, int modifiers) {
        if (key != CgKeyCodes.KEY_TAB) return false;
        boolean reverse = CgModifiers.hasShift(modifiers);
        UIElement modal = blockingModal(focused == null ? document : focused);
        UIElement scope = modal != null ? modal : scopeOf(focused);

        UIElement next;
        if (focused == null) {
            next = reverse ? lastTabbableIn(scope) : firstTabbableIn(scope);
        } else {
            next = reverse ? previousTabbable(focused, scope) : nextTabbable(focused, scope);
            if (next == null) next = reverse ? lastTabbableIn(scope) : firstTabbableIn(scope);
        }
        if (next == null) return false;
        if (focused != null) blur(focused);
        focus(next, FocusSource.KEYBOARD);
        return true;
    }

    /** The first thing under {@code scope} that may hold focus — what a dialog hands focus to. */
    @Nullable
    public UIElement firstFocusableIn(UIElement scope) {
        for (UIElement node : order(scope)) {
            if (node != scope && focusable(node)) return node;
        }
        return null;
    }

    @Nullable
    public UIElement lastFocusableIn(UIElement scope) {
        UIElement last = null;
        for (UIElement node : order(scope)) {
            if (node != scope && focusable(node)) last = node;
        }
        return last;
    }

    @Nullable
    public UIElement firstTabbableIn(UIElement scope) {
        for (UIElement node : order(scope)) {
            if (node != scope && tabbable(node)) return node;
        }
        return null;
    }

    @Nullable
    public UIElement lastTabbableIn(UIElement scope) {
        UIElement last = null;
        for (UIElement node : order(scope)) {
            if (node != scope && tabbable(node)) last = node;
        }
        return last;
    }

    @Nullable
    public UIElement nextTabbable(UIElement from, UIElement scope) {
        List<UIElement> order = order(scope);
        int at = order.indexOf(from);
        for (int i = at + 1; i < order.size(); i++) {
            if (tabbable(order.get(i))) return order.get(i);
        }
        return null;
    }

    @Nullable
    public UIElement previousTabbable(UIElement from, UIElement scope) {
        List<UIElement> order = order(scope);
        int at = order.indexOf(from);
        if (at < 0) at = order.size();
        for (int i = at - 1; i >= 0; i--) {
            if (tabbable(order.get(i)) && order.get(i) != scope) return order.get(i);
        }
        return null;
    }

    /**
     * Document order over the COMPOSED tree — the sequence Tab walks.
     *
     * <p>Walked rather than cached. The old engine kept a {@code hasFocusableDescendant} memo per
     * element to prune this, which is a per-FRAME shape; a tab ring is asked on a keystroke, and a
     * cache whose invalidation has to track enablement, inertness and modality is a much larger
     * liability than the walk it saves.</p>
     */
    private static List<UIElement> order(UIElement scope) {
        List<UIElement> out = new ArrayList<>();
        for (UIElement node : scope.composedSubtree()) out.add(node);
        return out;
    }

    // ── Bookkeeping ──────────────────────────────────────────────────────────

    /**
     * A node left the tree or was frozen: focus cannot linger on it, and a modal that went with it
     * must be popped — the old engine's worst leak, because a modal that left without closing kept
     * the whole window inert with nothing to interact with.
     */
    public void forget(UIElement node) {
        for (UIElement at : node.composedSubtree()) {
            modals.remove(at);
            if (focused == at) blur(at);
            at.setFocusWithin(false);
        }
    }
}
