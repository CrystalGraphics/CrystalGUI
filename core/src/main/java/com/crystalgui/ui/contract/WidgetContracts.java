package com.crystalgui.ui.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgui.ui.UIElement;

/**
 * Every widget's answer to "what do you carry, and can you be described at all". {@code M1}.
 *
 * <h3>Two ways to be listed, and no way to be absent</h3>
 *
 * <p>A widget class is either <b>contracted</b> — it has a {@link WidgetContract} — or explicitly
 * <b>local-only</b>, with a reason. There is no third state, and {@code WidgetContractCoverageTest}
 * enumerates the widget packages and fails on a class that is neither.</p>
 *
 * <p>That is the anti-rot shape {@code AGENTS.md} already prescribes for the CSS property registry,
 * applied to the question it was invented for. The failure it prevents is silent: a stateful widget
 * with nothing declared does not throw, it <b>arrives blank</b> on the far side, and a blank widget
 * reads as a rendering fault in the client rather than a missing declaration on the server.</p>
 *
 * <h3>Local-only is a claim, and it has to be defensible</h3>
 *
 * <p>{@link #localOnly} takes a reason because "this one does not travel" is a decision, not an
 * omission. Three reasons are legitimate and they are worth naming:</p>
 *
 * <ul>
 *   <li><b>It is view state.</b> Scroll offset, hover, a drag ghost's position. The same document/view
 *       boundary that keeps scroll out of the undo stack keeps it off the wire.</li>
 *   <li><b>It is an internal part.</b> A row, a renderer, a resizer — built by its owner's constructor
 *       on both sides, so describing it would duplicate the structure.</li>
 *   <li><b>It is the IDE shell.</b> The editor, the graph, the workbench chrome: things whose state is
 *       a document the workspace protocol already carries, or a layout the session record already
 *       carries. Describing them as widgets would be a second, disagreeing copy.</li>
 * </ul>
 *
 * <p>What is <em>not</em> legitimate is "nobody has got to it yet". That is what the reason string
 * makes visible.</p>
 */
public final class WidgetContracts {

    private WidgetContracts() {
    }

    private static final Map<Class<?>, WidgetContract<?>> CONTRACTS = new LinkedHashMap<>();
    private static final Map<Class<?>, String> LOCAL_ONLY = new LinkedHashMap<>();

    /**
     * Registers {@code contract} for its own class.
     *
     * <p>Called from the widget's own static initialiser, so a contract exists exactly when the class
     * does. Re-registering the same class is refused rather than silently replacing it — two contracts
     * for one widget means whichever class-initialises last decides what the widget is.</p>
     */
    public static <W> WidgetContract<W> register(WidgetContract<W> contract) {
        Class<?> type = contract.type();
        WidgetContract<?> existing = CONTRACTS.get(type);
        if (existing != null && existing != contract) {
            throw new IllegalStateException(
                    type.getName() + " already has a contract (" + existing + "). Two contracts for one "
                            + "widget means whichever class initialises last decides what it is.");
        }
        if (LOCAL_ONLY.containsKey(type)) {
            throw new IllegalStateException(
                    type.getName() + " is registered as local-only and cannot also be contracted.");
        }
        CONTRACTS.put(type, contract);
        return contract;
    }

    /**
     * Declares that {@code type} deliberately does not travel, and why.
     *
     * @param reason read by a human in a test failure. Say what KIND of not-travelling this is
     */
    public static void localOnly(Class<?> type, String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    type.getName() + " was marked local-only with no reason. The reason is the whole "
                            + "point: it is what separates a decision from an omission.");
        }
        if (CONTRACTS.containsKey(type)) {
            throw new IllegalStateException(
                    type.getName() + " has a contract and cannot also be local-only.");
        }
        LOCAL_ONLY.put(type, reason);
    }

    /** The contract for {@code type}, or null if it has none. Exact class, never a supertype's. */
    @SuppressWarnings("unchecked")
    @Nullable
    public static <W> WidgetContract<W> of(Class<?> type) {
        return (WidgetContract<W>) CONTRACTS.get(type);
    }

    /**
     * The contract for {@code widget}'s exact class, or null.
     *
     * <p><b>Exact, deliberately.</b> {@code tagName()} is an exact-class lookup for the same reason and
     * the engine has already paid for getting it wrong once — {@code ToolWindowFrame extends
     * WindowFrame} answered a tag nothing in the sheet named. A subclass that genuinely IS its parent
     * for wire purposes registers the parent's contract under its own class, which is a line of code
     * and a decision, rather than something inherited by accident.</p>
     */
    @Nullable
    public static <W extends UIElement> WidgetContract<W> of(UIElement widget) {
        return of(widget.getClass());
    }

    /** Why {@code type} does not travel, or null if it is not marked. */
    @Nullable
    public static String localOnlyReason(Class<?> type) {
        return LOCAL_ONLY.get(type);
    }

    public static boolean isLocalOnly(Class<?> type) {
        return LOCAL_ONLY.containsKey(type);
    }

    // ── What UIElement calls ─────────────────────────────────────────────────

    /**
     * Writes {@code widget}'s contracted state, or nothing if it has no contract.
     *
     * <p>Here rather than in {@code UIElement} so the unchecked cast lives in one place: a contract is
     * typed in its widget, and an element only knows it is a {@code UIElement}. The cast is sound
     * because {@link #register} keys on {@link WidgetContract#type()} and {@link #of(UIElement)} looks
     * up the exact class.</p>
     */
    @SuppressWarnings("unchecked")
    public static <T> void writeState(UIElement widget, com.crystalgui.serialization.StateMap<T> out) {
        WidgetContract<UIElement> contract = (WidgetContract<UIElement>) CONTRACTS.get(widget.getClass());
        if (contract != null) contract.write(widget, out);
    }

    /** Applies {@code widget}'s contracted state, or nothing if it has no contract. */
    @SuppressWarnings("unchecked")
    public static <T> void readState(UIElement widget, com.crystalgui.serialization.StateMap<T> in) {
        WidgetContract<UIElement> contract = (WidgetContract<UIElement>) CONTRACTS.get(widget.getClass());
        if (contract != null) contract.read(widget, in);
    }

    /** Everything contracted, in registration order. */
    public static Map<Class<?>, WidgetContract<?>> all() {
        return Collections.unmodifiableMap(CONTRACTS);
    }

    /** Everything explicitly marked local-only, with its reason. */
    public static Map<Class<?>, String> allLocalOnly() {
        return Collections.unmodifiableMap(LOCAL_ONLY);
    }
}
