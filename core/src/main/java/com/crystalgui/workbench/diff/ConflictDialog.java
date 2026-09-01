package com.crystalgui.workbench.diff;

import com.crystalgui.fs.CgPath;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.overlay.Dialog;
import com.crystalgui.widget.overlay.Tooltip;
import com.crystalgui.widget.text.UIText;

import javax.annotation.Nullable;

/**
 * Somebody else changed this file. Which version survives?
 *
 * <h3>Why this is a modal dialog and not the balloon it replaces</h3>
 *
 * <p>The protocol half of this was done deliberately and first: {@code Failure.isConflict()} carries the
 * live etag and a delta against a file that moved is <b>refused rather than merged</b>, because merging
 * is a decision with a UI attached and does not belong in a write path. This is that UI.</p>
 *
 * <p>What it replaces was a {@code Notification} offering one action, <i>"Reopen to take theirs"</i>. Two
 * things are wrong with that and neither is cosmetic. It <b>fades</b> — a balloon the user was not looking
 * at takes the decision by default, and the default is "your save silently did not happen". And its one
 * button <b>destroys unsaved work in one click with no confirmation</b>, while the other resolution — keep
 * mine — was not offered at all, so a user who wanted it had to know to copy their buffer out first.</p>
 *
 * <p>A conflict is one of the few moments in an editor where every route loses something. That earns the
 * interruption: the whole argument against modals is that they interrupt, and this is the case where
 * interrupting is the point.</p>
 *
 * <h3>Three outcomes, and the safe one is what Escape and focus do</h3>
 *
 * <table>
 *   <tr><th>Choice</th><th>What it destroys</th></tr>
 *   <tr><td><b>Keep mine</b></td><td>Their changes on the server</td></tr>
 *   <tr><td><b>Take theirs</b></td><td>Your unsaved edits</td></tr>
 *   <tr><td><b>Cancel</b></td><td>Nothing — the file stays modified and unsaved</td></tr>
 * </table>
 *
 * <p><b>Cancel is focused, not the first button.</b> {@code Dialog}'s focusing steps take the first
 * focusable descendant, which would be whichever button reads first — so focus is requested explicitly
 * afterwards, exactly as {@link Dialog#showModal()}'s javadoc says a caller should. Escape reaches the
 * same place through the close watcher. Both destructive choices therefore require a deliberate click,
 * and neither is one keystroke away.</p>
 *
 * <h3>It is deliberately built the plain way</h3>
 *
 * <p>Through {@link Dialog#showModal()} and nothing else: no {@code left}/{@code top} written by hand, no
 * direct top-layer promotion, and no assumption about what the backdrop covers. That is what lets window-scoped
 * modality retarget promotion from the global top layer to a window's own overlay slot without touching
 * this file — and the same three rules are already recorded for anchored popups, where only
 * {@code AnchoredPlacement} may write a position.</p>
 */
public final class ConflictDialog {

    public static final String CLASS = "__conflict__";
    public static final String MESSAGE_CLASS = "__conflict-message__";
    public static final String WHO_CLASS = "__conflict-who__";
    public static final String ACTIONS_CLASS = "__conflict-actions__";

    private ConflictDialog() {
    }

    /**
     * Asks which version survives.
     *
     * @param from        anything attached to the window this should open over
     * @param path        the file that moved under us
     * @param otherEditor who else has it open, or {@code null} when nobody knows. Presence data — see
     *                    {@code WorkspacePresence}. A conflict with a name on it is a thing that happened;
     *                    a conflict without one is the file mysteriously changing by itself, which is the
     *                    same information and reads as a bug
     * @param onKeepMine  overwrite the server's copy with what is on screen
     * @param onTakeTheirs discard the unsaved edits and reload
     */
    public static void ask(@Nullable UINode from, CgPath path, @Nullable String otherEditor,
                           Runnable onKeepMine, Runnable onTakeTheirs) {
        ask(from, path, otherEditor, onKeepMine, onTakeTheirs, null);
    }

    /**
     * As above, plus the third answer: <b>merge them</b>.
     *
     * <p>The other two are both destructive and the choice between them is made blind — a person is asked
     * which version survives without being shown what either one says. Most of the time neither answer is
     * the one they want, because the two sets of edits are in different parts of the file and both should
     * live. That is what {@code onMerge} opens.</p>
     *
     * @param onMerge opens a merge over the two versions, or {@code null} where there is no base to merge
     *                against — a file that was never read has no common ancestor, so the option is omitted
     *                rather than offered and then refused
     */
    public static void ask(@Nullable UINode from, CgPath path, @Nullable String otherEditor,
                           Runnable onKeepMine, Runnable onTakeTheirs, @Nullable Runnable onMerge) {
        UIDocument window = from == null ? null : from.document();
        if (window == null) {
            // NOTHING SILENT. Without a window there is nowhere to ask, and defaulting to either
            // resolution would destroy something the user never chose. Refusing the save is the only
            // answer that loses nothing, and the caller's own failure path already reports it.
            return;
        }

        Dialog dialog = new Dialog("Conflict");
        dialog.addClass(CLASS);

        UIText message = new UIText(path.name() + " changed on the server since you opened it.");
        message.addClass(MESSAGE_CLASS);
        dialog.getContent().append(message);

        if (otherEditor != null && !otherEditor.isEmpty()) {
            UIText who = new UIText(otherEditor + " has it open.");
            who.addClass(WHO_CLASS);
            dialog.getContent().append(who);
        }

        UINode actions = new UINode();
        actions.addClass(ACTIONS_CLASS);
        dialog.getContent().append(actions);

        // READING ORDER PUTS THE DESTRUCTIVE PAIR FIRST AND THE WAY OUT LAST, which is the usual
        // arrangement; what makes it safe is that focus and Escape both land on Cancel regardless.
        // MERGE FIRST: it is the only non-destructive answer and usually the right one, so it reads before
        // the pair that throws work away. Focus still lands on Cancel below -- that is what makes the
        // destructive pair safe, and it is unchanged by adding a safe option above them.
        Button mergeThem = onMerge == null ? null
                : choice(actions, "Merge…", "Combine both sets of edits, deciding only where they clash");
        Button keepMine = choice(actions, "Keep mine", "Overwrite the server's copy");
        Button takeTheirs = choice(actions, "Take theirs", "Discard your unsaved edits");
        Button cancel = choice(actions, "Cancel", "Leave the file modified and unsaved");

        keepMine.onPressed.connect(() -> {
            dialog.close();
            onKeepMine.run();
        });
        takeTheirs.onPressed.connect(() -> {
            dialog.close();
            onTakeTheirs.run();
        });
        cancel.onPressed.connect(dialog::close);
        if (mergeThem != null) {
            mergeThem.onPressed.connect(() -> {
                dialog.close();
                onMerge.run();
            });
        }

        // THROUGH addOverlay, which resolves the host through UIDocument.overlayHost -- the one seam that
        // knows where an overlay belongs. Adding to the root directly works today and is exactly what
        // window-scoped modality will change: the host becomes the nearest window frame's own overlay
        // slot rather than the global one, and every caller that went through here retargets for free.
        window.addOverlay(dialog, from);
        dialog.onClosed.connect(dialog::removeSelf);
        dialog.showModal();

        // AFTER showModal, per Dialog's own instruction: the focusing steps take the first focusable
        // descendant, and here that is the first thing that destroys something.
        window.focus().requestFocus(cancel);
    }

    private static Button choice(UINode row, String label, String tooltip) {
        Button button = new Button(label);
        Tooltip.attach(button, tooltip);
        row.append(button);
        return button;
    }
}
