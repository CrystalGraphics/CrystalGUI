package com.crystalgui.net.window;

import com.crystalgui.ui.contract.WidgetContracts;
import com.crystalgui.ui.dom.UINodeRegistry;
import com.crystalgui.ui.dom.NodeContract;
import com.crystalgui.ui.dom.Name;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.crystalgui.ui.dom.UINode;

/**
 * <b>A networked UI's identity</b> — the one value both sides reference, and the engine's
 * {@code customElements.define}.
 *
 * <pre>{@code
 * public static final UiType<MachinePanel, MachineModel> TYPE =
 *         UiType.of("mymod:machine", MachinePanel::new);
 * }</pre>
 *
 * <p>From there the whole of the wiring is one call site:</p>
 *
 * <pre>{@code
 * ServerWindows.of(connection).open(MachinePanel.TYPE, machine);   // server, at the trigger
 * }</pre>
 *
 * <p>The client needs nothing: the open names the panel class on the wire ({@code UiMethods.UI_CLASS}),
 * and {@code ClientWindows} initialises it — guarded — which runs this declaration and registers the
 * tag before the description arrives.</p>
 *
 * <h3>What declaring one does</h3>
 *
 * <p>Beyond naming the wire id, {@code of} registers the panel class in {@link ElementRegistry} under
 * its own tag — the lowercased simple class name, exactly what {@link UINode#tagName()} already
 * answers for an unregistered class — so a description whose root says {@code "machinepanel"} decodes
 * into a {@code MachinePanel} rather than throwing. That registration is also what makes the tag a
 * cascade identity: {@code machinepanel { }} in a stylesheet matches the panel.</p>
 *
 * <p>Two panel classes with the same simple name therefore collide, loudly, at registration — the
 * same namespace rule custom elements live with. Idempotent for the same class, so a type referenced
 * from several places registers once.</p>
 *
 * <h3>The string this replaces</h3>
 *
 * <p>The two halves of a networked UI used to be paired by a raw {@code String}, and nothing checked
 * that they matched — a typo produced a window that opened, rendered and reported every event with no
 * local behaviour and no error. Minecraft solved this with {@code MenuType<T>}: a registered object
 * both sides reference. This is that value, and being declared on the panel it is <b>loader-safe</b>:
 * every reference in the initialiser points at the panel class itself, never at a client-only type.</p>
 *
 * <h3>The two directions</h3>
 *
 * <p>{@link #build} is the server's: construct, fill and name the declared fields, then
 * {@link Networked#layout}. {@link #bind} is the client's: the decoded root <em>is</em> a bare
 * instance of the panel class, so binding is a checked cast plus resolving each declared field out of
 * the panel's own subtree by name and type. Android's View Binding and JavaFX's {@code @FXML} solve
 * the identical problem the identical way.</p>
 *
 * @param <P> the panel: an element that is {@link Networked}
 * @param <M> what it is a view of
 */
public final class UiType<P extends UINode & Networked<M>, M> {

    /** Panel class → its declared {@link UINode} fields, resolved once. */
    private static final Map<Class<?>, List<Field>> PARTS = new ConcurrentHashMap<>();

    private final String id;
    private final Supplier<P> create;
    private final Class<P> type;
    private final String tag;

    private UiType(String id, Supplier<P> create) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("a UI type needs an id");
        if (create == null) throw new IllegalArgumentException("a UI type needs a constructor");
        this.id = id;
        this.create = create;

        // One probe instance, to learn the class the supplier makes. Field initializers run and their
        // widgets are discarded -- a handful of objects, once per type per process.
        @SuppressWarnings("unchecked")
        Class<P> made = (Class<P>) create.get().getClass();
        this.type = made;
        this.tag = made.getSimpleName().toLowerCase(Locale.ROOT);

        // customElements.define: what lets a description's root say this tag and decode into this
        // class. Idempotent for the same class; a DIFFERENT class on the same tag is the registry's
        // own duplicate-key throw, which is the right answer to two mods naming panels alike.
        defineKind(tag, made, create::get);

        // ...and the same for every nested panel this one declares as a field, recursively — or the
        // CLIENT cannot decode a description whose subtree names their tags, because nothing on that
        // side ever touches the child class before the description arrives. A panel built only
        // DYNAMICALLY (in layout, never a field) is the one case this cannot see: give it a TYPE of
        // its own and reference it at client init.
        registerNested(made);
    }

    private static void registerNested(Class<?> owner) {
        for (Field part : partsOf(owner)) {
            if (!Networked.class.isAssignableFrom(part.getType())) continue;
            @SuppressWarnings("unchecked")
            Class<? extends UINode> nested = (Class<? extends UINode>) part.getType();
            String tag = nested.getSimpleName().toLowerCase(Locale.ROOT);
            if (!defineKind(tag, nested, () -> bareInstance(nested))) continue;
            registerNested(nested);
        }
    }

    /**
     * {@code customElements.define}, on this engine's registry.
     *
     * <p>Answers whether it registered, so the nested walk stops where it has been before rather than
     * recursing over a tree of fields it has already seen.</p>
     *
     * <p><b>The contract comes from the class, not from the tag.</b> A panel that declares a
     * {@link WidgetContract} gets it; one that does not gets a plain contract that accepts described
     * children, which is what a panel is — a container whose subtree IS the description.</p>
     */
    private static boolean defineKind(String tag, Class<?> type, Supplier<? extends UINode> factory) {
        Name kind = declaredKind(type, tag);
        if (UINodeRegistry.isRegistered(kind)) return false;
        NodeContract contract = WidgetContracts.of(type);
        UINodeRegistry.register(kind, factory, contract != null ? contract : UINodeRegistry.plain(kind, true));
        return true;
    }

    /**
     * The kind the panel's own class declares.
     *
     * <p>READ, never invented. A node answers the {@code NAME} its class declares and nothing else,
     * so a tag derived here from the class's simple name would register a factory under one name
     * while every instance described itself under another -- and the failure is silent in the worst
     * way: the description encodes, sends, and decodes into a plain node, so the client rebuilds a
     * tree of the right shape whose root is not the panel. The first symptom is a
     * {@code ClassCastException} in the host's own bind, pointing at the host.</p>
     *
     * <p>One field, by handle -- never {@code getDeclaredFields()}, which resolves the type of every
     * field and throws on a classpath CrystalGraphics core is deliberately absent from.</p>
     */
    private static Name declaredKind(Class<?> type, String tag) {
        try {
            return (Name) MethodHandles.lookup()
                    .findStaticGetter(type, "NAME", Name.class).invoke();
        } catch (NoSuchFieldException | IllegalAccessException absent) {
            throw new IllegalStateException(type.getSimpleName() + " must declare "
                    + "`public static final Name NAME = Name.of(\"" + tag + "\")` and pass it to "
                    + "super(NAME). A panel is a kind of node, and a node answers the name its class "
                    + "declares -- without one it answers `element`, and its description decodes into "
                    + "a plain node rather than into this class.", absent);
        } catch (Throwable failed) {
            throw new IllegalStateException("reading " + type.getSimpleName() + ".NAME failed", failed);
        }
    }

    /** The decode factory for a nested panel that declared no {@code UiType} of its own. */
    private static UINode bareInstance(Class<? extends UINode> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | RuntimeException cannot) {
            throw new IllegalStateException("a nested " + type.getSimpleName() + " needs a "
                    + "no-argument constructor so the client can rebuild one from a description", cannot);
        }
    }

    /**
     * @param id     namespaced, and the only thing that crosses the wire — {@code "mymod:machine"}
     * @param create the panel's no-argument constructor, used for building, binding and decoding
     */
    public static <P extends UINode & Networked<M>, M> UiType<P, M> of(String id, Supplier<P> create) {
        return new UiType<>(id, create);
    }

    /** What crosses the wire, and what both hosts key on. */
    public String id() {
        return id;
    }

    /** The element tag this panel decodes under — its cascade identity. */
    public String tag() {
        return tag;
    }

    /** The panel class. */
    public Class<P> uiClass() {
        return type;
    }

    // ── The server direction ────────────────────────────────────────────────

    /**
     * A fresh, built panel: parts created, named, and arranged. What {@code ServerWindows.open} serves,
     * and what a test holds directly.
     */
    public P build(@Nullable M model) {
        P panel = create.get();
        for (Field part : partsOf(type)) {
            UINode value = read(panel, part);
            if (value == null) {
                // A nested PANEL is deliberately not auto-created: building one needs its slice of
                // the model, and only the parent's layout knows which slice that is --
                // `engines = EnginePanel.TYPE.build(m.engines())`. Plain widgets have no such input.
                if (Networked.class.isAssignableFrom(part.getType())) continue;
                value = instantiate(part);
                write(panel, part, value);
            }
            // ONLY IF UNNAMED, so an initializer that set its own id keeps it -- the field name is a
            // default, not a seizure.
            if (value.id().isEmpty()) value.setId(part.getName());
        }
        panel.build(model);
        // Nested panels the layout just built get the same rule, after the fact -- the id is what
        // scopes their methods and what the client resolves the field by.
        for (Field part : partsOf(type)) {
            if (!Networked.class.isAssignableFrom(part.getType())) continue;
            UINode value = read(panel, part);
            if (value != null && value.id().isEmpty()) value.setId(part.getName());
        }
        return panel;
    }

    // ── The client direction ────────────────────────────────────────────────

    /**
     * Typed hold of a tree the client rebuilt — <b>the root is the panel</b>, so this checks the
     * class, resolves every declared field out of the panel's own subtree, and hands it back.
     *
     * <p>Throws when the root is not this type's class or a field cannot be found — which is the
     * point: a binding that quietly returned a panel with null fields would put the silent-skip
     * failure one level down, where it is harder to see. @see UINode#require</p>
     */
    public P bind(UINode root) {
        if (root == null) throw new IllegalArgumentException("nothing to bind <" + id + "> to");
        if (!type.isInstance(root)) {
            throw new IllegalStateException("<" + id + "> arrived as <" + root.tagName() + "> ("
                    + root.getClass().getSimpleName() + "), not a " + type.getSimpleName()
                    + " — is the type registered under a different id on the other side?");
        }
        P panel = type.cast(root);
        bindFields(panel);
        return panel;
    }

    /**
     * Resolves the declared fields of any {@link Networked} element out of its own subtree — the
     * nested half of {@link #bind}, used by the client walk for panels found <em>inside</em> another
     * panel's rebuilt tree, where the class is already correct by construction (decode built it from
     * its registered tag).
     */
    public static void bindFields(UINode panel) {
        for (Field part : partsOf(panel.getClass())) {
            write(panel, part, panel.require("#" + part.getName(), asElement(part.getType())));
        }
    }

    /**
     * Identity, deliberately — <b>two types with one id are two types</b>. A duplicate id is a
     * collision to be reported, not resolved.
     */
    @Override
    public boolean equals(Object other) {
        return this == other;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public String toString() {
        return id;
    }

    // ── Reflection, once per class ──────────────────────────────────────────

    private static List<Field> partsOf(Class<?> type) {
        return PARTS.computeIfAbsent(type, UiType::collect);
    }

    private static List<Field> collect(Class<?> type) {
        List<Field> found = new ArrayList<>();
        // UP THE Networked LEVELS AND NO FURTHER: a panel extending a panel contributes each level's
        // parts; a panel extending an ordinary widget must not have that widget's internal fields
        // claimed as parts, and "does this level implement Networked" is exactly that line.
        for (Class<?> level = type; level != null && Networked.class.isAssignableFrom(level);
                level = level.getSuperclass()) {
            for (Field field : level.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (!UINode.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                found.add(field);
            }
        }
        return found;
    }

    @Nullable
    private static UINode read(UINode panel, Field part) {
        try {
            return (UINode) part.get(panel);
        } catch (IllegalAccessException blocked) {
            throw new IllegalStateException("cannot read " + describe(part), blocked);
        }
    }

    private static void write(UINode panel, Field part, UINode value) {
        try {
            part.set(panel, value);
        } catch (IllegalAccessException blocked) {
            // The commonest cause by far, and worth saying rather than leaving as a reflection error.
            throw new IllegalStateException(describe(part) + " cannot be assigned — a panel's widget "
                    + "fields must not be final, because the framework sets them on both the build and "
                    + "the bind path", blocked);
        }
    }

    private static UINode instantiate(Field part) {
        try {
            return (UINode) part.getType().getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | RuntimeException cannot) {
            throw new IllegalStateException(describe(part) + " has no no-argument constructor, so it "
                    + "cannot be created for you — give the field an initializer "
                    + "(e.g. `= new " + part.getType().getSimpleName() + "(…)`) and it will be kept "
                    + "and named as it is", cannot);
        }
    }

    @SuppressWarnings("unchecked")
    private static Class<UINode> asElement(Class<?> type) {
        return (Class<UINode>) type;
    }

    private static String describe(Field part) {
        return part.getDeclaringClass().getSimpleName() + "." + part.getName();
    }
}
