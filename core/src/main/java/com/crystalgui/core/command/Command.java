package com.crystalgui.core.command;

import com.crystalgui.core.data.DataContext;
import lombok.Getter;

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

    private final List<String> bindings = new ArrayList<>();
    private final List<MenuId.Placement> menus = new ArrayList<>();

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
