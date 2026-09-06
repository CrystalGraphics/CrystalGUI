package com.crystalgui.workbench.dock.panel;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.dom.UIElement;

/**
 * A view that can be <b>pointed at</b> things, rather than built for one — VS Code's {@code EditorPane},
 * IntelliJ's {@code FileEditor}.
 *
 * <h3>The difference from a panel factory, and why it matters</h3>
 *
 * <p>{@code DockPanelRegistry} builds one element per panel ref and caches it, so a group showing five
 * files holds five editors. A pane is the other arrangement: <b>one instance per (group, type)</b>, and
 * switching tabs calls {@link #setInput} on the same object. That is what makes tab switching cheap in
 * both references, and it is the mechanism this engine lacked.</p>
 *
 * <p>It is also what an "inspector" needs. Before this, a panel that follows the active document was
 * built by an <em>application</em> swapping a child into a host element it owned — which is where
 * {@code assertOnlyChild}, the stacked-Inspector bug and the internal-child recursion trap all came
 * from. A pane that follows something is just a pane whose {@code setInput} is called again.</p>
 *
 * <h3>The contract that follows</h3>
 *
 * <p><b>A pane must hold no per-input state except through view state.</b> Two tabs of one type in one
 * group share the instance, so anything remembered in a field belongs to whichever input was last set.
 * {@link #writeViewState}/{@link #readViewState} exist for what must survive a retarget, and they are
 * driven by the framework — the pane never decides which input its state belongs to, because a pane that
 * keyed its own state is the same bug as the stacked inspectors one level down.</p>
 */
public interface DockPane extends Disposable {

    /** The element the dock puts in the tab. Stable for the life of the pane. */
    UIElement view();

    /**
     * Point this pane at an input — on first show, and on every retarget.
     *
     * <p>Called with the framework having already stored the outgoing input's view state and about to
     * hand back the incoming one's, so an implementation reads the input and nothing else.</p>
     */
    void setInput(DockInput input);

    /** No longer showing anything. Release per-input state; keep the pane. */
    default void clearInput() {
    }

    /** Became the visible tab. IntelliJ's {@code selectNotify}. */
    default void onVisible() {
    }

    /** Stopped being the visible tab. IntelliJ's {@code deselectNotify}. */
    default void onHidden() {
    }

    /** Caret, scroll, folds — whatever must survive being pointed elsewhere and back. */
    default void writeViewState(StateMap<?> out) {
    }

    default void readViewState(StateMap<?> in) {
    }

    @Override
    default void dispose() {
    }
}
