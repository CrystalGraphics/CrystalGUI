package com.crystalgui.net.window;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.crystalgui.ui.UIElement;

/**
 * <b>A whole networked UI in one class</b> — its widgets, its layout, its server behaviour and its
 * client behaviour, with nothing in between.
 *
 * <pre>{@code
 * public final class MachinePanel extends Panel<MachineModel> {
 *
 *     public static final PanelType<MachinePanel, MachineModel> TYPE =
 *             PanelType.of("mymod:machine", MachinePanel::new);
 *
 *     public Switch power;                             // created and named for you
 *     public Button purge = new Button("Purge");       // needs a ctor argument? just write it
 *
 *     @Override protected void layout() {              // BUILD only
 *         add(row("Power", power));
 *         add(purge);
 *     }
 *
 *     @Override protected void serve(WindowScope io) { // SERVER only
 *         io.onActivate(purge, ctx -> model().purge());
 *     }
 *
 *     @Override protected void client(ClientWindowContext window) {   // CLIENT only
 *         purge.attachListener(() -> …);
 *     }
 * }
 * }</pre>
 *
 * <p>Opened with {@code ServerWindows.of(connection).open(MachinePanel.TYPE.serve(machine))} and
 * enabled on the client with {@code ClientWindows.register(MachinePanel.TYPE)}. There is no
 * {@code ServerWindow} subclass, no {@code ClientWindowBehaviour}, no {@code bindTo}, and no id
 * strings.</p>
 *
 * <h3>The field declaration is the declaration</h3>
 *
 * <p>Every non-static {@link UIElement} field is a part of this panel. On the <b>build</b> side the
 * base creates anything left null, stamps {@code setId(fieldName)} on it, and then calls
 * {@link #layout()}. On the <b>bind</b> side it resolves each field out of the tree the client rebuilt,
 * by that same name and the field's own type, and never calls {@code layout()}.</p>
 *
 * <p>So the name is written <b>once</b>, as the thing you were going to write anyway. It replaces four
 * separate touches per widget — a field, an id constant, a {@code setId}, and a lookup in a second
 * constructor — which at a hundred panels is the difference between roughly five thousand lines of
 * ceremony and thirteen hundred lines that are all actual UI.</p>
 *
 * <p>A widget whose constructor takes arguments simply gets an ordinary initializer; the base fills
 * nulls and leaves everything else alone. Nothing needs a supplier or a class token.</p>
 *
 * <h3>Methods may be side-specific; fields may not</h3>
 *
 * <p>This is the rule that lets one class hold both halves, and it is not a convention — it is how
 * class loading works. <b>A field descriptor resolves when the class loads; a method body does
 * not.</b> So {@link #serve} may name a server-only type and {@link #client} may name a client-only
 * one, because both are method bodies invoked only on their own side — while a field of either kind
 * would break the other side at load time. It is the same rule that once killed a dedicated server
 * through a {@code static KeyBinding} field while every guarded line of code was unreachable.</p>
 *
 * <p><b>Measured rather than assumed:</b> a probe naming {@code org.lwjgl.input.Keyboard} — genuinely
 * absent on a dedicated server — in both a method body and a method signature loaded and ran there
 * without incident, and {@code :mc1710:serverSmoke} still reported no client-only class loaded.</p>
 *
 * <p>{@code Panel<M>} is generic for exactly this reason: <b>a generic field erases to
 * {@code Object}</b> in the descriptor, so a server-only model type never appears in one. {@link
 * #model()} casts inside a method body, where it is lazy.</p>
 *
 * <h3>Three lifetimes, three methods</h3>
 *
 * <table>
 *   <tr><th>Method</th><th>Runs</th><th>Why there</th></tr>
 *   <tr><td>{@link #layout()}</td><td>build only</td>
 *       <td>Structure cannot be inferred from fields — which row, which container, which class</td></tr>
 *   <tr><td>{@link #serve}</td><td>server only, once, before the client is told anything</td>
 *       <td>Handlers live on the session and are keyed by method, so they run once</td></tr>
 *   <tr><td>{@link #client}</td><td>client only, at mount and after every re-describe</td>
 *       <td>Widget listeners die with the tree that carried them, so they run every time</td></tr>
 * </table>
 *
 * <p>That third row is why {@code client()} exists separately rather than being folded into a
 * constructor: mixing the two lifetimes is what used to force a behaviour to re-wire by hand, and
 * forgetting was silent — every button dead, the window otherwise perfect.</p>
 *
 * <h3>Opt-in</h3>
 *
 * <p>Nothing requires a panel to extend this. A plain holder with a {@code root} field still works
 * exactly as before, and {@link ServerWindow} remains the right shape for a window whose content is
 * not a single panel.</p>
 *
 * @param <M> whatever this panel is a view of — a machine, an inventory, a document. Erased in the
 *            field descriptor, so it may be a type the client cannot load
 */
public abstract class Panel<M> {

    /** Class → its {@link UIElement} fields, resolved once. Reflection is not paid per panel. */
    private static final Map<Class<?>, List<Field>> PARTS = new ConcurrentHashMap<>();

    @Nullable
    private UIElement root;

    @Nullable
    private M model;

    // ── What a panel writes ─────────────────────────────────────────────────

    /**
     * Arranges this panel's widgets. <b>Build side only.</b>
     *
     * <p>Every field is already created and named by the time this runs, so this is purely structure:
     * rows, containers, classes, and the order things appear in. That cannot be inferred from field
     * declarations, which is the whole reason it is a method rather than more reflection.</p>
     */
    protected abstract void layout();

    /**
     * Registers what this panel does <b>on the server</b>. Once, before the client is told anything.
     *
     * <p>May freely name server-only types: this is a method body. @see Panel</p>
     */
    protected void serve(WindowScope io) {
    }

    /**
     * Attaches listeners to this panel's own widgets — <b>at mount, and again after every
     * re-describe</b>.
     *
     * <p>Widget listeners belong here and nowhere else, because they die with the tree that carried
     * them: a re-describe replaces every widget this panel holds, so anything attached to the old ones
     * went with them.</p>
     */
    protected void wire() {
    }

    /**
     * Registers what this panel answers <b>on the wire, on the client</b>. Once, at mount.
     *
     * <p>Separate from {@link #wire()} because the two have different lifetimes and the difference is
     * not cosmetic: a session registration is keyed by <em>method</em> and survives a re-describe
     * untouched, so running it twice is not a duplicate listener but a
     * <b>{@code MessageRouter} refusal</b> — the same (method, window) pair registered twice throws.
     * Widget listeners are the opposite and must run every time.</p>
     *
     * <p>May freely name client-only types: this is a method body. @see Panel</p>
     */
    protected void client(ClientWindowContext window) {
    }

    /** Told when the window ends, on whichever side this instance is. */
    protected void closed(String reason) {
    }

    /**
     * One world tick while this panel's window is open. <b>Server side only.</b>
     *
     * <p>Mirror the model into widgets and stop: the host flushes whatever that dirtied, as one
     * message, after this returns. Nothing here has to know which fields moved.</p>
     */
    protected void tick() {
    }

    /** What to call the window on screen, or {@code null} to let the type's id stand in. */
    @Nullable
    protected String title() {
        return null;
    }

    /**
     * Uniqueness and persistence key, or {@code null} for "always a new window".
     *
     * <p>A key makes re-opening free: the host brings the existing window forward rather than
     * building a second one, keeping its scroll position and whatever is half-typed in it.</p>
     */
    @Nullable
    protected String key() {
        return null;
    }

    // ── What a panel reads ──────────────────────────────────────────────────

    /** This panel's tree. Built here on the server; the client's rebuilt one when bound. */
    public final UIElement root() {
        if (root == null) throw new IllegalStateException("this panel has not been built or bound yet");
        return root;
    }

    /**
     * Whatever this panel is a view of, or {@code null} on the client.
     *
     * <p>Null when bound is not an oversight: a model is server state, and the client has a
     * description of a tree rather than the thing the tree is about. {@link #client} runs on that side
     * and must not reach for it.</p>
     */
    @Nullable
    protected final M model() {
        return model;
    }

    /** Adds a child to the root. Shorthand for the common line in {@link #layout()}. */
    protected final void add(UIElement child) {
        root().addChild(child);
    }

    // ── What the framework drives ───────────────────────────────────────────

    /** Builds a panel: create the missing parts, name them all, then let it arrange itself. */
    static <P extends Panel<M>, M> P build(java.util.function.Supplier<P> create, @Nullable M model) {
        P panel = create.get();
        // THROUGH THE BASE TYPE: a private member is not reachable through a subtype reference, even
        // from inside the declaring class.
        Panel<M> base = panel;
        base.model = model;
        base.root = new UIElement();
        for (Field part : partsOf(panel.getClass())) {
            UIElement value = read(panel, part);
            if (value == null) {
                value = instantiate(part);
                write(panel, part, value);
            }
            // ONLY IF UNNAMED, so an initializer that set its own id keeps it -- the field name is a
            // default, not a seizure.
            if (value.getId().isEmpty()) value.setId(part.getName());
        }
        panel.layout();
        return panel;
    }

    /**
     * Binds a panel to a tree the client rebuilt from a description.
     *
     * <p>The constructor still runs, so any field initializers create widgets that are then thrown
     * away. That is a handful of objects per window and buys the thing that matters: <b>one
     * constructor</b>, so a panel author never writes a second one and never has to keep two in
     * step.</p>
     */
    static <P extends Panel<M>, M> P bind(java.util.function.Supplier<P> create, UIElement rebuilt) {
        P panel = create.get();
        panel.rebind(rebuilt);
        return panel;
    }

    /**
     * Points this panel at a tree, resolving every declared part out of it.
     *
     * <p>Called again on a re-describe, <b>on the same instance</b>, which is what lets
     * {@link #client} be a once-only hook while {@link #wire()} runs every time. Rebuilding the panel
     * instead would lose anything it remembers and would re-run the session registrations that must
     * not run twice.</p>
     */
    final void rebind(UIElement rebuilt) {
        this.root = rebuilt;
        for (Field part : partsOf(getClass())) {
            write(this, part, rebuilt.require("#" + part.getName(), asElement(part.getType())));
        }
    }

    // ── Reflection, once per class ──────────────────────────────────────────

    private static List<Field> partsOf(Class<?> type) {
        return PARTS.computeIfAbsent(type, Panel::collect);
    }

    private static List<Field> collect(Class<?> type) {
        List<Field> found = new ArrayList<>();
        // UP TO Panel AND NO FURTHER: a subclass hierarchy of panels contributes each level's parts,
        // and the base itself has none to contribute.
        for (Class<?> level = type; level != null && level != Panel.class; level = level.getSuperclass()) {
            for (Field field : level.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (!UIElement.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                found.add(field);
            }
        }
        return found;
    }

    @Nullable
    private static UIElement read(Panel<?> panel, Field part) {
        try {
            return (UIElement) part.get(panel);
        } catch (IllegalAccessException blocked) {
            throw new IllegalStateException("cannot read " + describe(part), blocked);
        }
    }

    private static void write(Panel<?> panel, Field part, UIElement value) {
        try {
            part.set(panel, value);
        } catch (IllegalAccessException blocked) {
            // The commonest cause by far, and worth saying rather than leaving as a reflection error.
            throw new IllegalStateException(describe(part) + " cannot be assigned — a panel's widget "
                    + "fields must not be final, because the base sets them on both the build and the "
                    + "bind path", blocked);
        }
    }

    private static UIElement instantiate(Field part) {
        try {
            return (UIElement) part.getType().getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | RuntimeException cannot) {
            throw new IllegalStateException(describe(part) + " has no no-argument constructor, so it "
                    + "cannot be created for you — give the field an initializer "
                    + "(e.g. `= new " + part.getType().getSimpleName() + "(…)`) and it will be kept "
                    + "and named as it is", cannot);
        }
    }

    @SuppressWarnings("unchecked")
    private static Class<UIElement> asElement(Class<?> type) {
        return (Class<UIElement>) type;
    }

    private static String describe(Field part) {
        return part.getDeclaringClass().getSimpleName() + "." + part.getName();
    }
}
