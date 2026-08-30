package com.crystalgui.ui.dom;

import com.crystalgui.core.CrystalGuiCore;
import java.util.Map;
import java.util.ServiceLoader;
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
 * {@code ElementRegistry} does — a typo must not become a styleless container. The three built-in
 * kinds are registered below; widgets register from their own class initialisers in M6, and a
 * {@code UiType} registers its panel.</p>
 */
public final class UINodeRegistry {

    /**
     * <b>Declared above the static block, and it has to be.</b> That block reads {@code UINode.NAME},
     * which initialises {@link UINode} — and a widget's own class initialiser registers itself here
     * ({@link com.crystalgui.ui.box.TextNode} is the shipped example, and every widget M6 ports will
     * be another), so the moment any of these classes gains one, initialisation re-enters this class
     * while it is still being initialised. The JVM lets a thread straight through its own in-progress
     * init rather than deadlocking, so that is safe <em>only</em> while this map already exists.
     * Moving it below the block turns every built-in registration into a
     * {@link NullPointerException} on a class that plainly declares it.
     */
    private static final Map<Name, Entry> ENTRIES = new ConcurrentHashMap<>();

    private record Entry(Supplier<? extends UINode> factory, NodeContract contract) {
    }

    /**
     * The built-ins, registered from here rather than from each class's own initialiser.
     *
     * <p>Self-registration is the pattern for a widget and the wrong one for these three, because of
     * <b>who is asked first</b>: {@link #create} is the decode path, and a client decoding a
     * description before it has constructed anything would find {@code element} unregistered — a
     * class nothing has touched has not initialised, so its static block has not run. Naming them
     * here means the registry cannot be asked before they are in it.</p>
     *
     * <p>{@code shadow-root} is deliberately absent: a shadow root is never described, so it has a
     * name for the cascade and nothing to build from the wire.</p>
     */
    static {
        register(UINode.NAME, UINode::new, plain(UINode.NAME, true));
        register(UISlot.NAME, UISlot::new, plain(UISlot.NAME, true));
        register(UIDocument.NAME, UIDocument::new, plain(UIDocument.NAME, true));
    }

    /** Whether the {@link NodeKinds} services have been run. @see #bootstrap() */
    private static volatile boolean bootstrapped;

    /**
     * Runs every {@link NodeKinds} service once — what makes the registry's contents a function of
     * the CLASSPATH rather than of what this JVM happened to touch.
     *
     * <p>Called at the top of every read below, which is the old {@code ElementRegistry}'s own
     * arrangement and the reason it is correct without a host remembering anything: a client
     * decoding {@code <crystalgui:button>} has, by construction, asked the registry a question.</p>
     *
     * <p><b>Loaded with THIS class's loader, never the context one.</b> On 1.7.10 the context
     * classloader during a network read is whatever the host left there, and LaunchWrapper's is not
     * the one that defined these classes — a {@code ServiceLoader} pointed at it finds nothing, or
     * finds a second copy of everything. The defining loader is the only one guaranteed to see the
     * jar this interface came from.</p>
     *
     * <p>A service that throws is reported and skipped rather than taking the registry down with it:
     * one mod's broken widget must not make every other kind unresolvable, and a decode that finds
     * an unknown name already throws with a message naming what IS registered.</p>
     */
    private static void bootstrap() {
        if (bootstrapped) return;
        synchronized (UINodeRegistry.class) {
            if (bootstrapped) return;
            bootstrapped = true;
            for (NodeKinds kinds : ServiceLoader.load(NodeKinds.class, UINodeRegistry.class.getClassLoader())) {
                try {
                    kinds.register();
                } catch (RuntimeException | LinkageError e) {
                    CrystalGuiCore.LOGGER.error("A NodeKinds service failed to register its kinds: {}",
                            kinds.getClass().getName(), e);
                }
            }
        }
    }

    private UINodeRegistry() {
    }

    public static void register(Name name, Supplier<? extends UINode> factory, NodeContract contract) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(contract, "contract");
        ENTRIES.put(name, new Entry(factory, contract));
    }

    public static boolean isRegistered(Name name) {
        bootstrap();
        return ENTRIES.containsKey(name);
    }

    /** A fresh node of the named kind. Throws for a name nothing registered. */
    public static UINode create(Name name) {
        bootstrap();
        Entry entry = ENTRIES.get(name);
        if (entry == null) {
            throw new IllegalArgumentException("No node kind is registered as <" + name + ">; registered: "
                    + ENTRIES.keySet());
        }
        return entry.factory().get();
    }

    /** The contract for a kind — the registered one, or a plain container's for a name nothing registered. */
    public static NodeContract contractFor(Name name) {
        bootstrap();
        Entry entry = ENTRIES.get(name);
        return entry != null ? entry.contract() : plain(name, true);
    }

    public static Set<Name> names() {
        bootstrap();
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
