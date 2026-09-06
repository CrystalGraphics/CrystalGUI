package com.crystalgui.widget.overlay;

import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.text.UIText;

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

    /** The shared dialog button row. @see #confirm */
    public static final String ACTIONS_CLASS = "__dialog-actions__";

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
        UIDocument window = from == null ? null : from.document();
        if (window == null) return;

        Popover popup = prompt(window, from, title);
        TextField field = new TextField();
        field.setPlaceholder(label);
        field.setText(initial);
        popup.append(field);

        field.onSubmit.connect(value -> {
            String name = value.trim();
            popup.hide();
            if (!name.isEmpty()) onAccept.accept(name);
        });

        centre(window, popup);
        // FOCUSED and SELECTED, so typing replaces the old name. A rename prompt that opens with the caret
        // at one end makes the commonest case -- replace the whole name -- start with a select-all the
        // user has to think about.
        window.focus().requestFocus(field);
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
        confirm(from, title, message, "Confirm", onConfirm);
    }

    /**
     * Asks a yes/no question, with both answers as buttons.
     *
     * <p><b>The exception to the no-buttons rule above, because there is nothing to type.</b> A name
     * prompt is a field and Enter commits what you wrote; a confirmation has no field, so one was added
     * purely to catch the keystroke and the instruction rode in its PLACEHOLDER. That put a destructive
     * action behind a key nothing the user would read had told them about, on a popup that light-dismisses
     * on the first click outside — so pressing Delete on a file put up a box that went away again and
     * deleted nothing.</p>
     *
     * <p>Escape and a click outside both cancel, and focus lands on Cancel, so the destructive answer
     * takes a deliberate press. The same arrangement {@code ConflictDialog} uses, for the same reason.</p>
     *
     * @param confirmLabel what the confirming button says. Name the ACTION — "Delete", "Discard" — never
     *                     "OK": it is the last thing read before something is destroyed
     */
    public static void confirm(@Nullable UIElement from, String title, String message,
                               String confirmLabel, Runnable onConfirm) {
        UIDocument window = from == null ? null : from.document();
        if (window == null) return;

        Dialog dialog = new Dialog(title);
        UIText caption = new UIText(message);
        caption.addClass(CAPTION_CLASS);
        dialog.getContent().append(caption);

        UIElement actions = new UIElement();
        // THE SHARED ROW, not ConflictDialog's own class: that one is scoped to `dialog.__conflict__`
        // and borrowing it matches nothing, which the sheet's own comment records as silent and total.
        actions.addClass(ACTIONS_CLASS);
        dialog.getContent().append(actions);

        Button confirm = new Button(confirmLabel);
        actions.append(confirm);
        Button cancel = new Button("Cancel");
        actions.append(cancel);

        confirm.onPressed.connect(() -> {
            dialog.close();
            onConfirm.run();
        });
        cancel.onPressed.connect(dialog::close);

        window.addOverlay(dialog, from);
        dialog.onClosed.connect(dialog::removeSelf);
        dialog.showModal();
        // AFTER showModal, per Dialog's own instruction: the focusing steps take the first focusable
        // descendant, and here that is the button that destroys something.
        window.focus().requestFocus(cancel);
    }

    /** The shared shell: one caption, promoted and light-dismissable. */
    private static Popover prompt(UIDocument window, @Nullable UIElement from, String title) {
        Popover popup = new Popover();
        popup.addClass(PROMPT_CLASS);

        UIText caption = new UIText(title);
        caption.addClass(CAPTION_CLASS);
        popup.append(caption);

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
    private static void restoreFocusOnClose(UIDocument window, Popover popup, @Nullable UIElement from) {
        if (from == null) return;
        popup.onClosed.connect(() -> {
            if (from.document() != window || !window.focus().focusable(from)) return;
            // POINTER-sourced, so closing a prompt does not leave a focus ring on the panel behind it --
            // the user did not tab here, they finished a dialog.
            window.focus().requestPointerFocus(from);
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
    /** On a prompt that has been shown but not yet placed. {@code opacity: 0} lives in the sheet. */
    public static final String PLACING_CLASS = "__placing__";

    private static void centre(UIDocument window, Popover popup) {
        // A CLASS, not an IMPORTANT write. The engine may not write into the cascade here at all, and
        // the reason is the same one the standing row gives: a resting value written from Java outranks
        // every rule that would ever want to restyle it, so the hiding belongs in the sheet
        // (`popover.__placing__ { opacity: 0 }`) and this only says WHEN. Dropping the class hands the
        // property back to the stylesheet exactly as withdrawing the candidate did, so the popup still
        // fades in on the sheet's own timing, in the right place.
        popup.addClass(PLACING_CLASS);
        // AFTER LAYOUT: this reads the popup's own measured box to centre it, and an ordinary per-frame
        // hook runs BEFORE layout -- so on the opening frame it would centre a box of zero and correct
        // on the next, which is a visible jump. Same reason Popover and Tooltip place themselves here.
        window.animation().afterLayout(popup, delta -> {
            if (!popup.isOpen()) return false;
            Box box = popup.box();
            Box viewport = window.box();
            if (box == null || viewport == null) return true;
            float width = box.width();
            float height = box.height();
            if (width <= 0f || height <= 0f) return true;   // not laid out yet; look again next frame
            popup.moveTo(Math.max(0f, (viewport.width() - width) / 2f),
                    Math.max(0f, (viewport.height() - height) / 2f));
            popup.removeClass(PLACING_CLASS);
            return false;
        });
    }
}
