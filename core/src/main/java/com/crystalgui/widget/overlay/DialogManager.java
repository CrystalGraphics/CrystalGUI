package com.crystalgui.widget.overlay;

import com.crystalgui.ui.dom.UINode;
import lombok.Getter;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Coordinates a set of {@link Dialog}s sharing one container: stacking order, activation, and where a
 * new one lands.
 *
 * <h3>Not a {@code UINode}</h3>
 * <p>Follows {@link CheckboxGroup}'s precedent — a coordinator over elements rather than an element
 * itself. There is nothing to paint and nothing to lay out; introducing a node would put a box in the
 * tree whose only job is to not affect anything.</p>
 *
 * <h3>One tree, not many</h3>
 * <p>The alternative shape considered was several real {@code UIDocument}s with something arbitrating
 * between them. That duplicates the entire runtime per window — a Taffy tree, a style engine, an input
 * handler each — to express what is already expressible: absolutely-positioned siblings ordered by
 * {@code z-index}. {@code sortedChildren} sorts z-descending per parent and painting walks it
 * reversed, so "raise to front" is exactly "hold a higher z-index than your siblings", and hit-testing
 * agrees with painting for free because both read the same ordering.</p>
 *
 * <h3>Entirely ours</h3>
 * <p>The web has no window manager, so unlike the top layer or {@code resize} there is no spec to
 * port here — only the stacking model underneath it, which is CSS's. Every policy decision below
 * (raise-on-click, cascade placement, monotonic z) is a choice, and is commented as one.</p>
 */
public final class DialogManager {

    /**
     * Where the first dialog lands, and how far each subsequent one is offset, in logical pixels.
     *
     * <p>Not CSS values and deliberately not: placement policy belongs to the manager, not to a
     * stylesheet — a theme has no opinion about where the third window opens. Same treatment as
     * {@code UIDragController.DEFAULT_THRESHOLD_PX}, which is also a behavioural constant rather than
     * a visual one.</p>
     */
    public static final float DEFAULT_CASCADE_STEP = 24f;

    /**
     * Base stacking index. Managed dialogs are raised from here upward, so they sit above ordinary
     * page content (which defaults to 0) without any of them needing to know about the others.
     */
    private static final int BASE_Z = 10;

    private final UINode stage;
    private final float cascadeStep;
    private final List<Dialog> dialogs = new ArrayList<>();

    private int topZ = BASE_Z;
    private int cascadeIndex;

    /** The dialog most recently raised — the "active window". */
    @Getter
    @Nullable
    private Dialog active;

    public DialogManager(UINode stage) {
        this(stage, DEFAULT_CASCADE_STEP);
    }

    public DialogManager(UINode stage, float cascadeStep) {
        this.stage = Objects.requireNonNull(stage, "stage");
        this.cascadeStep = cascadeStep;
    }

    /** Managed dialogs in the order they were added. Read-only. */
    public List<Dialog> getDialogs() {
        return Collections.unmodifiableList(dialogs);
    }

    /**
     * Adds {@code dialog} to the stage, places it, and starts managing it.
     *
     * <p>Placement cascades: each new dialog is offset from the last so a freshly opened one is
     * visibly a <em>new</em> window rather than pixel-perfectly hiding the previous one. Dialogs clamp
     * themselves to their container, so a cascade that would run off the edge simply stops there —
     * no wrap logic needed, and no window ever lands out of reach.</p>
     */
    public Dialog manage(Dialog dialog) {
        Objects.requireNonNull(dialog, "dialog");
        if (dialogs.contains(dialog)) return dialog;

        dialogs.add(dialog);
        if (dialog.parent() != stage) stage.append(dialog);

        float offset = cascadeStep * cascadeIndex++;
        dialog.moveTo(offset, offset);

        // Capture phase, deliberately. "Clicking a window activates it" has to hold for a click
        // anywhere inside it — including on a button that consumes the event for its own purposes.
        // Only the capture phase sees the press before a descendant can stop it.
        dialog.onMouseDown.attachListener((el, event) -> raise(dialog), true, false);

        raise(dialog);
        return dialog;
    }

    /** Stops managing {@code dialog} and removes it from the stage. */
    public void unmanage(Dialog dialog) {
        if (dialog == null || !dialogs.remove(dialog)) return;
        if (dialog.parent() == stage) stage.remove(dialog);
        if (active == dialog) active = dialogs.isEmpty() ? null : dialogs.get(dialogs.size() - 1);
    }

    /**
     * Brings {@code dialog} to the front and makes it active.
     *
     * <p>A monotonically increasing z-index rather than a renumbering pass over every dialog: the
     * relative order is all that matters, so there is nothing to gain from compacting, and a
     * renumber would have to touch every sibling on every click. Idempotent — raising the front-most
     * dialog writes nothing, because {@code replaceOrPutCandidate} discards an unchanged value.</p>
     */
    public void raise(Dialog dialog) {
        if (dialog == null || !dialogs.contains(dialog)) return;
        active = dialog;
        if (isFrontMost(dialog)) return;
        dialog.generalStyle(g -> g.zIndex(++topZ));
    }

    private boolean isFrontMost(Dialog dialog) {
        int z = dialog.getStyle().getGeneralGroup().zIndex();
        for (Dialog other : dialogs) {
            if (other != dialog && other.getStyle().getGeneralGroup().zIndex() >= z) return false;
        }
        return true;
    }

    /** Opens every managed dialog. */
    public void showAll() {
        for (Dialog dialog : dialogs) dialog.show();
    }

    /** Closes every managed dialog. Order is irrelevant — closing does not restack anything. */
    public void closeAll() {
        for (Dialog dialog : dialogs) dialog.close();
    }
}
