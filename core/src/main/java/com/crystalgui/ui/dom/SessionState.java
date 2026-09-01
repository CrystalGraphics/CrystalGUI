package com.crystalgui.ui.dom;

import com.crystalgui.serialization.DynamicOps;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.contract.WidgetContract;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Widget state that outlives the widget — a bag of contract payloads keyed by node id.
 *
 * <h3>Why this and not an interface per panel</h3>
 *
 * <p>The first version was a {@code PanelViewState} interface a tool window implemented, and that was
 * the wrong shape: the engine <em>already</em> has a way for a widget to say what it wants preserved
 * and a way to name one ({@link UINode#setId}). A second, parallel mechanism meant every panel
 * re-implemented persistence for widgets that could already describe themselves, and it could only
 * ever reach a panel's <b>root</b> — a divider three levels down had to be proxied out by hand.</p>
 *
 * <h3>What changed in the port, and it is not a rename</h3>
 *
 * <p>The old engine read a widget through {@code writeState}/{@code readState}, a pair of overridable
 * methods on the element. The new engine has no such pair: a widget's state is DECLARED, as ordered
 * {@code State} slots on its {@link WidgetContract}, which is the same declaration the wire and the
 * projections read. So this asks the contract, and the three things that fall out of that are all
 * improvements — a widget cannot forget to implement half of it, declaration order is apply order (on
 * which four widgets depend), and a slot with no getter is a compile error rather than a value that
 * silently never travels.</p>
 *
 * <p>Opting in is {@link Attribute#SESSION_PERSISTENT} rather than a Java flag, so a description can
 * carry it and a stylesheet-driven tool window keeps its divider across a restart without its panel
 * class knowing this class exists.</p>
 *
 * @param <T> the serialised form, whatever {@link DynamicOps} the host persists with
 */
public final class SessionState<T> {

    private final DynamicOps<T> ops;
    private final Map<String, T> stored = new LinkedHashMap<>();

    /**
     * Ids already handed back, so a node that is detached and re-attached is not re-seeded.
     *
     * <p>Restoring twice would undo whatever the user did in between, which is the failure this set
     * exists to prevent: a hidden tool window is detached and reshown routinely.</p>
     */
    private final Set<String> applied = new HashSet<>();

    public SessionState(DynamicOps<T> ops) {
        this.ops = ops;
    }

    public Map<String, T> entries() {
        return stored;
    }

    public void put(String id, T payload) {
        stored.put(id, payload);
    }

    /**
     * Hands one node its remembered state, if it asked for any and has not had it yet.
     *
     * <p>A refusal is swallowed by the caller rather than here, because a widget that cannot read a
     * stale payload must not be a widget that cannot be added to a document.</p>
     */
    public void applyTo(@Nullable UINode node) {
        WidgetContract<UINode> contract = contractOf(node);
        if (contract == null) return;
        String id = node.id();
        if (id.isEmpty() || applied.contains(id)) return;
        T payload = stored.get(id);
        if (payload == null) return;
        applied.add(id);
        contract.read(node, new StateMap<>(ops, payload));
    }

    /**
     * Reads every opted-in node under {@code root} back out, over the top of what is held.
     *
     * <p>Over the top rather than instead of: an id with no live node keeps whatever it came in with.
     * The walk is the COMPOSED tree — a widget's parts are exactly where a divider lives, and on this
     * engine a part is inside a shadow root rather than flagged as internal.</p>
     */
    public void capture(@Nullable UINode root) {
        if (root == null) return;
        collect(root);
    }

    private void collect(UINode node) {
        captureFrom(node);
        ShadowRoot shadow = node.shadowRoot();
        if (shadow != null) {
            for (UINode child : shadow.children()) collect(child);
        }
        for (UINode child : node.children()) collect(child);
    }

    /**
     * Reads one node back out — the mirror of {@link #applyTo}, called as it <em>leaves</em> a document.
     *
     * <p>Without this, closing a panel loses everything in it. A hidden tool window is detached, so a
     * save afterwards walks a tree the widget is no longer in and writes nothing: drag the Run panel's
     * divider, close the panel, quit, and the width is gone — precisely the erosion this class exists
     * to prevent, arriving through the one door that was left open.</p>
     *
     * <p>Capturing on the way out rather than only at save time also means the value stored is the one
     * the widget had while it was alive, which is the only moment it can be read at all.</p>
     */
    public void captureFrom(@Nullable UINode node) {
        WidgetContract<UINode> contract = contractOf(node);
        if (contract == null) return;
        String id = node.id();
        if (id.isEmpty()) return;
        StateMap<T> out = new StateMap<>(ops);
        contract.write(node, out);
        stored.put(id, out.encode());
    }

    /**
     * The contract to read this node through, or null when it is not participating.
     *
     * <p>Three ways to be out: not opted in, no kind registered, or a kind whose contract carries no
     * state at all. The last is worth its own check — a node with an empty contract would otherwise
     * store an empty payload under its id and shadow a real one from a previous session.</p>
     */
    @Nullable
    @SuppressWarnings("unchecked")
    private static WidgetContract<UINode> contractOf(@Nullable UINode node) {
        if (node == null || !node.get(Attribute.SESSION_PERSISTENT)) return null;
        NodeContract contract = UINodeRegistry.contractFor(node.name());
        if (!(contract instanceof WidgetContract<?> widget) || !contract.carriesState()) return null;
        return (WidgetContract<UINode>) widget;
    }
}
