package com.crystalgui.ui.elements.chrome;

import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.Dialog;
import com.crystalgui.ui.elements.TextField;
import com.crystalgui.ui.elements.UIText;

import java.util.function.Consumer;

import javax.annotation.Nullable;

/**
 * The two modal prompts a file explorer needs: ask for a name, and confirm a destruction.
 *
 * <h3>Modal, and that is the point</h3>
 *
 * <p>Both are questions with no meaningful "later". {@link Dialog#showModal} already gives the focus trap,
 * the Escape handling and the inertness of everything behind — see {@code UIElement.isInert}'s note on why
 * a modal is not simply an inherited flag — so what is left here is two small compositions rather than a
 * dialog framework.</p>
 *
 * <h3>Enter commits and Escape cancels, in both</h3>
 *
 * <p>Which is worth stating because it is the half that gets forgotten: a name prompt you have to reach for
 * the mouse to accept is a name prompt that is slower than the menu that opened it. Escape is
 * {@code Dialog}'s already — it is a close watcher — so only Enter is wired here.</p>
 */
public final class InputDialog {

    public static final String PROMPT_CLASS = "__prompt__";
    public static final String MESSAGE_CLASS = "__prompt-message__";
    public static final String BUTTONS_CLASS = "__prompt-buttons__";

    private InputDialog() {
    }

    /**
     * Asks for a line of text.
     *
     * <p>{@code onAccept} runs only for a non-blank value that the user actually confirmed — a cancelled
     * or emptied prompt reports nothing at all rather than an empty string, because every caller here
     * would otherwise have to re-check it and one of them would forget.</p>
     */
    public static void ask(@Nullable UIElement from, String title, String label, String initial,
                           Consumer<String> onAccept) {
        UIWindow window = from == null ? null : from.getAttachedWindow();
        if (window == null) return;

        Dialog dialog = new Dialog(title);
        dialog.addClass(PROMPT_CLASS);

        UIText caption = new UIText(label);
        caption.addClass(MESSAGE_CLASS);
        TextField field = new TextField();
        field.setText(initial);

        dialog.getContent().addChild(caption);
        dialog.getContent().addChild(field);

        Runnable accept = () -> {
            String value = field.getText().trim();
            dialog.close();
            if (!value.isEmpty()) onAccept.accept(value);
        };

        dialog.getContent().addChild(buttons(dialog, "OK", accept));
        field.onSubmit.connect(value -> accept.run());

        window.ui.rootElement.addChild(dialog);
        dialog.showModal();
        // FOCUSED and SELECTED, so typing replaces the old name. A rename prompt that opens with the
        // caret at one end makes the commonest case -- replace the whole name -- start with a select-all
        // the user has to think about.
        window.getInputHandler().requestFocus(field);
        field.selectAll();
    }

    /** Asks a yes/no question. {@code onConfirm} runs only on the affirmative. */
    public static void confirm(@Nullable UIElement from, String title, String message,
                               Runnable onConfirm) {
        UIWindow window = from == null ? null : from.getAttachedWindow();
        if (window == null) return;

        Dialog dialog = new Dialog(title);
        dialog.addClass(PROMPT_CLASS);

        UIText caption = new UIText(message);
        caption.addClass(MESSAGE_CLASS);
        dialog.getContent().addChild(caption);
        dialog.getContent().addChild(buttons(dialog, "Delete", () -> {
            dialog.close();
            onConfirm.run();
        }));

        window.ui.rootElement.addChild(dialog);
        dialog.showModal();
    }

    /**
     * The confirm/cancel row.
     *
     * <p>Cancel is built first and the affirmative second, so the affirmative sits on the <b>right</b> —
     * the Windows and web convention, and the one this engine's Dialog title bar already implies by
     * putting its close button there. Consistency within one application matters more than which platform
     * convention is chosen.</p>
     */
    private static UIElement buttons(Dialog dialog, String affirmative, Runnable onAffirmative) {
        UIElement row = new UIElement();
        row.addClass(BUTTONS_CLASS);

        Button cancel = new Button("Cancel");
        cancel.attachListener(dialog::close);
        Button ok = new Button(affirmative);
        ok.attachListener(onAffirmative);

        row.addChild(cancel);
        row.addChild(ok);
        return row;
    }
}
