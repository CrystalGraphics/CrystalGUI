package com.crystalgui.ui.dom;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * {@link Name} → how to build a node of that kind, and its {@link NodeContract}.
 *
 * <p>The engine's {@code customElements.define}: a description saying {@code <mymod:machine>} decodes
 * into the class registered under that name, and a peer asking what a kind can report is answered
 * from the contract registered beside it. Unknown names <b>throw</b> on {@link #create}, as the old
 * {@code ElementRegistry} does — a typo must not become a styleless container. The four built-in
 * kinds register themselves; widgets register in M6, and a {@code UiType} registers its panel.</p>
 */
public final class NodeRegistry {

    private static final Map<Name, Entry> ENTRIES = new ConcurrentHashMap<>();

    private record Entry(Supplier<? extends Node> factory, NodeContract contract) {
    }

    static {
        register(Name.ELEMENT, Node::new, plain(Name.ELEMENT, true));
        register(Name.SLOT, Slot::new, plain(Name.SLOT, true));
        register(Name.DOCUMENT, Document::new, plain(Name.DOCUMENT, true));
    }

    private NodeRegistry() {
    }

    public static void register(Name name, Supplier<? extends Node> factory, NodeContract contract) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(contract, "contract");
        ENTRIES.put(name, new Entry(factory, contract));
    }

    public static boolean isRegistered(Name name) {
        return ENTRIES.containsKey(name);
    }

    /** A fresh node of the named kind. Throws for a name nothing registered. */
    public static Node create(Name name) {
        Entry entry = ENTRIES.get(name);
        if (entry == null) {
            throw new IllegalArgumentException("No node kind is registered as <" + name + ">; registered: "
                    + ENTRIES.keySet());
        }
        return entry.factory().get();
    }

    /** The contract for a kind — the registered one, or a plain container's for a name nothing registered. */
    public static NodeContract contractFor(Name name) {
        Entry entry = ENTRIES.get(name);
        return entry != null ? entry.contract() : plain(name, true);
    }

    public static Set<Name> names() {
        return Set.copyOf(ENTRIES.keySet());
    }

    /** A contract that reports nothing and carries no state: the plain container's. */
    public static NodeContract plain(Name name, boolean acceptsChildren) {
        return new Plain(name.toString(), acceptsChildren);
    }

    private record Plain(String name, boolean acceptsDescribedChildren) implements NodeContract {
        @Override
        public Set<String> eventKinds() {
            return Set.of();
        }

        @Override
        public boolean carriesState() {
            return false;
        }
    }
}
