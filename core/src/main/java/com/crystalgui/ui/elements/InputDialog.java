package com.crystalgui.ui.elements;

import com.crystalgui.ui.UIElement;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.StyleOrigin;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Popover;
import com.crystalgui.ui.elements.TextField;
import com.crystalgui.ui.elements.UIText;

import java.util.function.Consumer;

import javax.annotation.Nullable;

/**
 * The two prompts a file explorer needs: ask for a name, and confirm a destruction.
 *
 * <h3>A popup, not a dialog — IntelliJ's shape</h3>
 *
 * <p>A caption and a field, centred in the window, and nothing else. No title bar, no close button, no OK
 * and no Cancel. <b>Enter commits and Escape cancels</b>, which are the only two things anybody does to a
 * name prompt and both keys the user already reaches for. Buttons that duplicate keys the popup already
 * answers are two more things to read and one more row to lay out.</p>
 *
 * <p>{@link Popover} is the base, so light dismiss, Escape and top-layer promotion all come from there —
 * pressing anywhere outside cancels, which is a third way out that costs nothing to have.</p>
 *
 * <h3>Centred, and re-centred once it has a size</h3>
 *
 * <p>A popup is out of flow, so its size is unknown until it has been laid out — and a prompt positioned
 * before that lands in the top-left corner, which is exactly where the first version of this appeared. So
 * it opens, measures, and moves itself once.</p>
 */
public final class InputDialog {

    public static final String PROMPT_CLASS = "__prompt__";
    public static final String CAPTION_CLASS = "__prompt-caption__";

    private InputDialog() {
    }

    /**
     * Asks for a line of text.
     *
     * <p>{@code onAccept} runs only for a non-blank value the user confirmed with Enter — a cancelled or
     * emptied prompt reports nothing rather than an empty string, because every caller would otherwise
     * have to re-check it and one of them would forget.</p>
     */
    public static void ask(@Nullable UIElement from, String title, String label, String initial,
                           Consumer<String> onAccept) {
        UIWindow window = from == null ? null : from.getAttachedWindow();
        if (window == null) return;

        Popover popup = prompt(window, from, title);
        TextField field = new TextField();
        field.setPlaceholder(label);
        field.setText(initial);
        popup.addChild(field);

        field.onSubmit.connect(value -> {
            String name = value.trim();
            popup.hide();
            if (!name.isEmpty()) onAccept.accept(name);
        });

        centre(window, popup);
        // FOCUSED and SELECTED, so typing replaces the old name. A rename prompt that opens with the caret
        // at one end makes the commonest case -- replace the whole name -- start with a select-all the
        // user has to think about.
        window.getInputHandler().requestFocus(field);
        field.selectAll();
    }

    /**
     * Asks a yes/no question.
     *
     * <p>Enter confirms and Escape cancels, same as the name prompt — so the destructive answer sits
     * behind a deliberate key rather than a button that happens to be under the pointer.</p>
     */
    public static void confirm(@Nullable UIElement from, String title, String message,
                               Runnable onConfirm) {
        UIWindow window = from == null ? null : from.getAttachedWindow();
        if (window == null) return;

        Popover popup = prompt(window, from, title);

        // A field carries Enter, rather than a key handler on the popover: a popup with nothing focusable
        // in it cannot receive a key at all, and a second mechanism for what TextField already does is a
        // second mechanism to keep in step.
        TextField confirmKey = new TextField();
        confirmKey.setPlaceholder(message + "  —  Enter to confirm, Escape to cancel");
        popup.addChild(confirmKey);
        confirmKey.onSubmit.connect(value -> {
            popup.hide();
            onConfirm.run();
        });

        centre(window, popup);
        window.getInputHandler().requestFocus(confirmKey);
    }

    /** The shared shell: one caption, promoted and light-dismissable. */
    private static Popover prompt(UIWindow window, @Nullable UIElement from, String title) {
        Popover popup = new Popover();
        popup.addClass(PROMPT_CLASS);

        UIText caption = new UIText(title);
        caption.addClass(CAPTION_CLASS);
        popup.addChild(caption);

        window.addOverlay(popup, from);
        popup.showAt(0f, 0f, null);
        restoreFocusOnClose(window, popup, from);
        return popup;
    }

    /**
     * Hands focus back to whatever the prompt was opened from.
     *
     * <p>The prompt takes focus for its field, and until now nothing gave it back — so after confirming a
     * delete, focus was left on a text field inside a popup that had already closed. Everything that
     * resolves outward from the focused element then found nothing: <b>Ctrl+Z did not undo the delete</b>,
     * and neither did Delete, F2 or any other panel-scoped key, because a keymap and an
     * {@code UndoScope} both walk up from focus and there was no longer a path from there to the panel.</p>
     *
     * <p>{@code showAt} is deliberately given a <b>null invoker</b> — a prompt is not a toggle, and naming
     * its trigger as the invoker would exempt that element from light dismiss — so {@code Popover}'s own
     * restore has nothing to aim at and this has to be explicit.</p>
     *
     * <p>Only if {@code from} is still in the tree and can actually hold focus: the element a delete was
     * invoked from is quite often the row that the delete just removed. Focus then stays where it is
     * rather than being pushed onto something detached, which is worse than useless — hit testing and
     * hover both go looking for it.</p>
     */
    private static void restoreFocusOnClose(UIWindow window, Popover popup, @Nullable UIElement from) {
        if (from == null) return;
        popup.onClosed.connect(() -> {
            if (from.getAttachedWindow() != window || !from.focusable()) return;
            // POINTER-sourced, so closing a prompt does not leave a focus ring on the panel behind it --
            // the user did not tab here, they finished a dialog.
            window.getInputHandler().requestPointerFocus(from);
        });
    }

    /**
     * Puts the popup in the middle of the window, once it knows how big it is — <b>and keeps it invisible
     * until it is there</b>.
     *
     * <p>A ticker rather than a computation at show time: {@code showAt} runs before the promoted node has
     * ever been laid out, so width and height are both zero at that moment and centring against them puts
     * it in the corner. This drops itself the first frame the size is real.</p>
     *
     * <p>Measuring first is unavoidable, so the flicker had to be removed at the other end: the popup used
     * to be <em>painted</em> in the corner while it waited, and the sheet's open transition faded it in
     * there before it jumped to the middle. Every New File and every Delete opened with a visible hop
     * across the window.</p>
     *
     * <p>Held down at {@code IMPORTANT} so it outranks the {@code popover.__open__} rule that would
     * otherwise fade it in immediately, and <b>removed</b> rather than set to 1 once placed — dropping the
     * candidate hands the property back to the stylesheet, so the popup fades in exactly as every other
     * popover does, in the right place, with the timing still owned by the sheet.</p>
     */
    private static void centre(UIWindow window, Popover popup) {
        StyleGroup.importantPipeline(popup.getStyle().getGeneralGroup(), g -> g.opacity(0f));
        window.registerTicker(delta -> {
            if (!popup.isOpen()) return false;
            float width = popup.getRuntimeCache().getWidth();
            float height = popup.getRuntimeCache().getHeight();
            if (width <= 0f || height <= 0f) return true;   // not laid out yet; look again next frame
            popup.moveTo(Math.max(0f, (window.getScreenWidth() - width) / 2f),
                    Math.max(0f, (window.getScreenHeight() - height) / 2f));
            popup.getStyle().removeCandidates(StylePropertyRegistry.OPACITY,
                    slot -> slot.origin() == StyleOrigin.IMPORTANT);
            return false;
        });
    }
}
