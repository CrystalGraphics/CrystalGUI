package com.crystalgui.widget.config;

import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.signal.ConnectionGroup;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;

import com.crystalgui.core.config.ConfigDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import com.crystalgui.widget.overlay.Tooltip;

/**
 * <b>The bare editor for one value — no label, no row, no idea where it is mounted.</b>
 *
 * <h3>Why this is not the row</h3>
 * <p>LDLib2's {@code Configurator} <em>is</em> the row: it owns a label, an inline area and a tip, and
 * every concrete editor extends it. That is the right design for an inspector and the wrong one here,
 * because this engine has <b>three hosts and only one of them is a row</b>:</p>
 *
 * <ul>
 *   <li>the inspector — {@link Configurator}, a label column beside a control column;</li>
 *   <li>a node's property row — the same shape at node scale;</li>
 *   <li><b>a node's unconnected input</b> — the control alone, floating outside the node box beside its
 *       port, labelled by the port itself.</li>
 * </ul>
 *
 * <p>The third is the common case in a node graph, and it has no row to inherit. Fold the label into
 * the control and it can reuse nothing. So the control is the unit of reuse and the row wraps one.
 * Unity's own editor is built this way: the same dropdown appears beside a port on a master node and as
 * an inspector row, changing only its anchor and its width.</p>
 *
 * <h3>{@link #changed} fires for the USER, never for a programmatic set</h3>
 * <p>A host that writes a value back in response to a change — every host does, sooner or later — would
 * otherwise drive an infinite echo. {@link #setValueObject} therefore silences the signal for the
 * duration of the write, once, here, so that no concrete control has to remember it. {@code TextField}
 * and {@code ColorSelector} both already carry a private version of this guard; this is the general
 * one.</p>
 *
 * <h3>Undo belongs to the host</h3>
 * <p>LDLib2 hangs an {@code EditAction} off the row. Ours cannot: an {@code UndoStack} belongs to a
 * <em>document</em>, and a control has no document — the same control edits a shader graph, a settings
 * object and a preview pane. So {@link #changed} reports what the user did and the host decides whether
 * that is an {@code Edit}, which is also what keeps a read-only inspector from silently recording
 * history.</p>
 */
public abstract class ConfigControl extends UIElement {

    /** Marks every control in the kit, whatever its kind — the hook a host or theme sizes them by. */
    public static final String CONTROL_CLASS = "__config-control__";

    /** Fires when the <b>user</b> changes the value. Never on {@link #setValueObject}. */
    public final Signal.Value<Object> changed = new Signal.Value<>();

    /**
     * {@code true} when a continuous gesture starts, {@code false} when it ends.
     *
     * <h3>What this is for, and why {@link #changed} could not carry it</h3>
     * <p>A drag — a {@linkplain com.crystalgui.ui.input.DragScrub scrub}, a slider, a colour wheel — emits
     * a value <b>per frame</b>. Every one of those is a real change and must be reported, because that is
     * what makes a preview update live. But the whole gesture is <em>one</em> thing the user did, and a
     * host recording each frame as an undo step turns Ctrl+Z into a hundred presses.</p>
     *
     * <p>The host cannot infer the boundaries — a burst of changes and a slow scrub look identical from
     * the outside, which is exactly the guess {@code UndoStack}'s time window is making. So the control
     * states them, and the host maps them onto whatever it is recording into:
     * {@code UndoStack.beginMergeRun()} / {@code endMergeRun()} for a graph document, nothing at all for a
     * read-only inspector.</p>
     *
     * <p>Silent for an ordinary discrete edit — typing in a box, picking from a dropdown — since there is
     * no gesture to bracket. A listener that only ever sees {@code changed} keeps working unchanged.</p>
     */
    public final Signal.Value<Boolean> interacting = new Signal.Value<>();

    /**
     * The live subscriptions, rebuilt from {@link #follows} whenever this control enters a tree.
     *
     * <h3>Why a control follows anything at all</h3>
     *
     * <p>So that an edit made <em>elsewhere</em> reaches the widget — {@code settings.onChanged},
     * {@code document.onChanged}. Those stores outlive the control by a long way: a {@code Settings} lives
     * as long as the application and a {@code GraphDocument} as long as the file is open, while a control
     * is rebuilt every time an inspector's subject changes.</p>
     *
     * <p>Nothing disconnected them, so a store accumulated one listener per row per rebuild, each holding
     * a widget that had already left the tree. Invisible from both ends: the host subscribed, the store
     * notified, nothing failed, and the only symptom was a session that got slower the longer it ran.</p>
     *
     * <p><b>Private on purpose.</b> A public group invites a binder to register a connection directly,
     * which is the manual-lifetime pattern {@link #follows} exists to remove — and one that would then go
     * missing on the first re-attach, since this is cleared wholesale.</p>
     */
    private final ConnectionGroup connections = new ConnectionGroup();

    /**
     * Declares how this control follows something that outlives it. <b>The engine decides when.</b>
     *
     * <h3>Nobody releases these, because nobody can be trusted to</h3>
     *
     * <p>The first version of this had a binder subscribe directly and every <em>owner</em> release: a
     * {@link ConfiguratorPanel} replacing its rows, a {@code GraphNode} being deleted, the graph clearing
     * every node, and a floating port editor being unmounted. Four owners sharing no supertype, each
     * needing to remember a call whose omission is invisible — which is the same bookkeeping the
     * ownership tree exists to remove, and a fifth owner would simply not know.</p>
     *
     * <p>So the control does it. {@code onWindowChanged} fires for every element of a detached subtree,
     * so leaving the tree is already announced; the binder states the subscription and never thinks about
     * its lifetime again.</p>
     *
     * <h3>Re-established on re-attach, not merely dropped</h3>
     *
     * <p>Releasing on detach alone would be a trap: a control taken out of the tree and put back — a tab
     * hidden and shown, a pane retargeted — would come back permanently deaf, and only in cases nobody
     * tests. Re-subscribing makes detachment ordinary rather than terminal.</p>
     *
     * <p>Which is also why a control does not need to follow anything while detached: it cannot be seen,
     * so there is nothing to keep current. {@code subscribe} is expected to <b>read the live value
     * first</b> and then connect, so a control that was away during an edit comes back correct rather
     * than stale.</p>
     */
    public void follows(Supplier<Connection> subscribe) {
        if (subscribe == null) return;
        follows.add(subscribe);
        // Bound NOW when this is already in a tree, since onWindowChanged has been and gone. A binder
        // may run either side of attachment -- SettingsConfigurator builds into a detached panel, and
        // NodeFieldBinder rebuilds a port editor on a live plane -- and neither should have to know.
        if (document() != null) connections.add(subscribe.get());
    }

    /** @see #follows */
    private final List<Supplier<Connection>> follows = new ArrayList<>();

    /**
     * Subscribes what {@link #follows} declared, once this control is in a tree.
     *
     * <p>{@code onWindowChanged(previous, current)} has no counterpart here — the node tree reports
     * connect and disconnect separately rather than as one transition — and the split is faithful
     * rather than lossy. The old hook disconnected unconditionally and re-subscribed only if there was
     * a window, which is exactly {@link #disconnected} then {@link #connected}; a move BETWEEN
     * documents fires both, in that order, so the case its comment called out is still covered.</p>
     */
    @Override
    protected void connected() {
        connections.disconnectAll();
        for (Supplier<Connection> subscribe : follows) connections.add(subscribe.get());
    }

    @Override
    protected void disconnected() {
        connections.disconnectAll();
    }

    private final ConfigDescriptor descriptor;

    /** True while a programmatic write is in flight; suppresses {@link #changed}. */
    private boolean updating;

    /** @see #interacting */
    private boolean interactingNow;

    /**
     * @param name the concrete control's own kind
     *
     * <p><b>The kind comes from the subclass, always.</b> An abstract base cannot declare one — it has
     * no factory to register — and a subclass that does not pass its own reports {@code element},
     * which is the M6.2 trap: five widgets did exactly that, all registered under the built-in name,
     * and the last one to register clobbered it. {@code numbercontrol { }} in a theme would then match
     * nothing, silently, which is §1.5's failure one axis over.</p>
     */
    protected ConfigControl(Name name, ConfigDescriptor descriptor) {
        super(name);
        this.descriptor = descriptor;
        addClass(CONTROL_CLASS);
        if (descriptor.tooltip() != null) {
            Tooltip.attach(this, descriptor.tooltip());
        }
    }

    public ConfigDescriptor descriptor() {
        return descriptor;
    }

    /** The current value, in whatever type this control speaks. */
    public abstract Object getValueObject();

    /**
     * Pushes a value in without echoing it back out.
     *
     * <p>Subclasses implement {@link #applyValue}; this method owns the guard. Overriding <em>this</em>
     * instead would lose it, which is why it is final.</p>
     */
    public final void setValueObject(Object value) {
        boolean wasUpdating = updating;
        updating = true;
        try {
            applyValue(value);
        } finally {
            updating = wasUpdating;
        }
    }

    /** Writes {@code value} into the underlying widgets. Never emits. */
    protected abstract void applyValue(Object value);

    /**
     * Emits {@link #changed}, unless a programmatic write is in flight.
     *
     * <p>Every concrete control routes its widget's listener through here rather than touching
     * {@code changed} directly. A control that emits by hand is the one that will echo.</p>
     */
    protected final void emitChanged(Object value) {
        if (updating) return;
        changed.emit(value);
    }

    /** True while {@link #setValueObject} is running — for controls that rebuild rather than write. */
    protected final boolean isUpdating() {
        return updating;
    }

    /**
     * Opens a continuous gesture. Idempotent, so a control that cannot easily tell whether one is already
     * running may call it freely.
     *
     * @see #interacting
     */
    protected final void beginInteraction() {
        if (interactingNow) return;
        interactingNow = true;
        interacting.emit(true);
    }

    /**
     * Closes a continuous gesture.
     *
     * <p><b>Must be reached on every exit path</b>, including a cancelled drag — a host that opened a
     * merge run and never hears it close will keep folding the next unrelated edit into this gesture's
     * undo step, and the symptom (two actions undoing as one, occasionally) is a long way from the cause.</p>
     */
    protected final void endInteraction() {
        if (!interactingNow) return;
        interactingNow = false;
        interacting.emit(false);
    }

    /** True between {@link #beginInteraction} and {@link #endInteraction}. */
    public final boolean isInteracting() {
        return interactingNow;
    }

    /**
     * True when the control carries its own label and the host must not add one.
     *
     * <p><b>The control decides, because only it knows whether it self-describes.</b> A colour swatch
     * and a 4×4 matrix grid say what they are by being what they are; "Value: [a colour]" adds nothing
     * the colour does not, and costs the swatch the width it needs to be judged against. This is the
     * same rule {@code GraphNode.addControl} already applies through {@code FULL_WIDTH_CLASS}.</p>
     */
    public boolean selfLabelling() {
        return false;
    }
}
