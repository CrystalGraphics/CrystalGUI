package com.crystalgui.core.command;

import com.crystalgui.core.command.when.WhenExpression;
import com.crystalgui.core.data.DataContext;
import lombok.Getter;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A named, invocable action — the thing a key binding, a menu item and the command palette all point at.
 *
 * <p>Bindings name a {@code String} id rather than holding a lambda, which is what makes them
 * <b>data</b>: a keymap can be parsed from a resource, shipped as a preset, and remapped by a user
 * without any of that touching Java. Every serious editor works this way — VS Code, Blender, Unity and
 * Unreal all agree on this point, so it is less a design choice than a consensus.</p>
 *
 * <h3>{@link #isEnabled} is the single enablement mechanism</h3>
 * <p>"Delete needs a selection" is a property of the <b>command</b>, not of the keystroke — and a
 * greyed-out menu item needs the identical answer. So enablement lives here and has three consumers: the
 * keymap (refuses to fire), a menu item (renders itself disabled), and the palette (dims the row).</p>
 *
 * <p>An earlier draft of the keymap put a predicate on the <em>binding</em> instead. That was a second
 * activation mechanism alongside scoping, and two mechanisms that can disagree about the same question is
 * the definition of the thing to avoid. One question, one mechanism.</p>
 */
public final class Command {

    @Getter private final String id;
    @Getter private final String label;

    private Consumer<CommandContext> handler = context -> { };
    private Predicate<CommandContext> enabled = context -> true;

    private Command(String id, String label) {
        this.id = id;
        this.label = label;
    }

    /**
     * @param id    dotted and stable — {@code "edit.save"}. This is what bindings, sheets and user
     *              remappings refer to, so renaming one silently breaks every keymap that named it.
     * @param label human-readable, and the reason this type exists rather than a bare {@code Runnable}:
     *              a menu item and a command palette have to render something.
     */
    public static Command of(String id, String label) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("A command needs an id");
        return new Command(id, label);
    }

    public Command run(Consumer<CommandContext> handler) {
        this.handler = handler == null ? context -> { } : handler;
        return this;
    }

    /** Convenience for the many commands that ignore their context entirely. */
    public Command run(Runnable handler) {
        return run(context -> handler.run());
    }

    /** @see #isEnabled */
    public Command enabledWhen(Predicate<CommandContext> predicate) {
        this.enabled = predicate == null ? context -> true : predicate;
        return this;
    }

    /**
     * Enablement stated over the {@link com.crystalgui.core.data.DataContext} instead.
     *
     * <p>The common case once subjects come from the context: {@code data(c -> c.has(GRAPH_VIEW))}
     * rather than {@code enabledWhen(c -> c.data().has(GRAPH_VIEW))}. Named differently rather than
     * overloaded because two lambda-taking overloads on one name are ambiguous at every call site.</p>
     */
    public Command enabledWhereData(Predicate<DataContext> predicate) {
        return enabledWhen(context -> predicate.test(context.data()));
    }

    /**
     * Enablement written as a {@code when} expression — {@code "undoStack && !readOnly"}.
     *
     * <h3>When to use this instead of a lambda</h3>
     *
     * <p>Almost never, for a command declared in Java: {@link #enabledWhereData} is clearer, typed, and
     * cannot be misspelled. This exists because a lambda is <b>not data</b>, and a serialised
     * contribution — a menu shipped in a resource pack, a command sent from a server — has no way to
     * carry one. Same argument {@link #binding} makes about naming a {@code String} id.</p>
     *
     * <p>Throws on a malformed expression, at declaration time rather than at use. See
     * {@link WhenExpression} for why this refuses where a stylesheet would degrade.</p>
     */
    public Command when(String expression) {
        return enabledWhereData(WhenExpression.parse(expression).asPredicate());
    }

    /** {@link #toggledWhen} written as a {@code when} expression. @see #when */
    public Command toggledWhenExpression(String expression) {
        return toggledWhereData(WhenExpression.parse(expression).asPredicate());
    }

    /** As {@link #run(Consumer)}, but handed the data context directly. */
    public Command runWithData(Consumer<DataContext> handler) {
        return run(context -> handler.accept(context.data()));
    }

    /**
     * Default key specs this command answers to, in {@code Keymap}'s syntax.
     *
     * <h3>Declared with the command, not beside it</h3>
     *
     * <p>A binding and the thing it invokes are one fact, and both references keep them together —
     * VS Code's {@code keybinding:} field, IntelliJ's {@code <keyboard-shortcut>}. Ours were split: the
     * command was registered in one place and {@code keymap().bind(spec, id)} was called in another,
     * usually from inside a widget and therefore only after that widget existed. That is how a command
     * ends up registered but unreachable, or a binding ends up pointing at nothing.</p>
     *
     * <p>A user's keymap still overrides these. This is the default, not the rule.</p>
     */
    public Command binding(String... keySpecs) {
        java.util.Collections.addAll(bindings, keySpecs);
        return this;
    }

    public java.util.List<String> bindings() {
        return java.util.Collections.unmodifiableList(bindings);
    }

    /**
     * Where this appears in menus. Empty means "palette only".
     *
     * <p>{@code group} then {@code order}, so unrelated contributors interleave predictably rather than
     * by whoever registered first — VS Code spells the pair {@code "navigation@1"}. This is what lets a
     * widget contribute to a menu it does not own, instead of reaching the method that builds it.</p>
     */
    public Command menu(MenuId menu, String group, int order) {
        menus.add(new MenuId.Placement(menu, group, order));
        return this;
    }

    public List<MenuId.Placement> menus() {
        return Collections.unmodifiableList(menus);
    }

    /**
     * Declares this a <b>toggle</b>, and how to read its current state — VS Code's {@code toggled},
     * IntelliJ's {@code ToggleAction}.
     *
     * <h3>Why the command and not the menu item</h3>
     *
     * <p>{@code MenuItem.setCheckable}/{@code setSelected} have always existed, so a checkmark was
     * expressible — <b>by whoever built the row</b>. That is the wrong owner: "Show Problems is currently
     * on" is a fact about the application, and a contributed command whose row is built by a menu it has
     * never heard of has no way to state it. The result was that every toggle in a menu had to be
     * hand-built outside the contribution system, which is precisely the coupling {@link MenuId} exists to
     * remove.</p>
     *
     * <p>Read at build time by whatever renders the row, and — like {@link #isEnabled} — resolved against
     * the context rather than stored, so a toggle cannot go stale between the state changing and the menu
     * next opening. There is no {@code setToggled}: the command does not own the state, it reports it.</p>
     *
     * <p>The handler is unchanged and still does the toggling. A toggle command is an ordinary command
     * that happens to be able to describe itself.</p>
     */
    public Command toggledWhen(Predicate<CommandContext> predicate) {
        this.toggled = predicate;
        return this;
    }

    /** {@link #toggledWhen} stated over the {@link DataContext}. @see #enabledWhereData */
    public Command toggledWhereData(Predicate<DataContext> predicate) {
        return toggledWhen(context -> predicate.test(context.data()));
    }

    /**
     * Whether this renders with a checkmark column at all.
     *
     * <p>Distinct from {@link #isToggled} being false: an unchecked toggle reserves the column and an
     * ordinary command does not, which is what stops a menu's labels shifting sideways as its toggles
     * change.</p>
     */
    public boolean isCheckable() {
        return toggled != null;
    }

    /** This toggle's current state, or false when it is not a toggle. */
    public boolean isToggled(CommandContext context) {
        return toggled != null && toggled.test(context);
    }

    private final List<String> bindings = new ArrayList<>();
    private final List<MenuId.Placement> menus = new ArrayList<>();

    /** Null means "not a toggle" — the distinction {@link #isCheckable} rests on. */
    @Nullable
    private Predicate<CommandContext> toggled;

    public boolean isEnabled(CommandContext context) {
        return enabled.test(context);
    }

    /**
     * Runs the command if it is enabled, and reports whether it did.
     *
     * <p>Returning false rather than throwing on a disabled command is deliberate: a keystroke reaching a
     * disabled command is an ordinary, expected state — it means the shortcut is bound but not applicable
     * right now — and the resolver needs to know so it can let the key fall through to an outer scope.</p>
     */
    public boolean execute(CommandContext context) {
        if (!isEnabled(context)) return false;
        handler.accept(context);
        return true;
    }

    @Override
    public String toString() {
        return "Command[" + id + "]";
    }
}
