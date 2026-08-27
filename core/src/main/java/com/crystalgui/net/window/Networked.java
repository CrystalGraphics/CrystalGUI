package com.crystalgui.net.window;

import javax.annotation.Nullable;

import com.crystalgui.ui.UIElement;

/**
 * <b>A networked element</b> — one class that is the whole of a UI served over a wire: its widgets as
 * fields, its structure, its server behaviour and its client behaviour.
 *
 * <pre>{@code
 * public final class MachinePanel extends UIElement implements Networked<MachineModel> {
 *
 *     public static final UiType<MachinePanel, MachineModel> TYPE =
 *             UiType.of("mymod:machine", MachinePanel::new);
 *
 *     public Switch power;                             // created and named for you
 *     public Button purge = new Button("Purge");       // needs a ctor argument? just write it
 *
 *     @Override public void layout(MachineModel m) {   // BUILD side
 *         addChild(row("Power", power));
 *         addChild(purge);
 *     }
 *
 *     @Override public void serve(MachineModel m, ServerScope io) {   // SERVER
 *         io.onActivate(purge, ctx -> m.purge());
 *     }
 *
 *     @Override public void tick(MachineModel m) {                    // SERVER, per world tick
 *         power.setChecked(m.isRunning());
 *     }
 *
 *     @Override public void bound() {                                 // CLIENT, every bind
 *         purge.attachListener(() -> …);
 *     }
 * }
 * }</pre>
 *
 * <p>Opened with {@code ServerWindows.of(connection).open(MachinePanel.TYPE, machine)} — and that is
 * the <b>whole</b> wiring: the open names the panel class on the wire, the client initialises it
 * (guarded) and runs its client half. There is no window subclass, no behaviour class, no
 * registration call, no {@code bindTo}, and no id strings.</p>
 *
 * <h3>The panel IS an element</h3>
 *
 * <p>This is the engine's own composite-widget model — a self-building element, registered by tag,
 * constructed on the far side by class — applied to a whole UI, and it is the Web Components shape:
 * {@link UiType#of} is this engine's {@code customElements.define}. The consequences are all wins over
 * a panel that merely <em>owns</em> a tree: it nests anywhere an element does, it takes classes and
 * styles directly, {@code machinepanel { }} works in a stylesheet because the tag is its cascade
 * identity, and on the client <b>the mounted root is the panel</b> — there is no parallel object
 * standing beside the tree.</p>
 *
 * <p>The wire semantics do not move: {@link #layout} runs on the <b>server only</b>, its output is
 * serialized as ordinary described children, and the client's instance is constructed <em>bare</em>
 * and populated from the description. The client still draws a tree it did not build, so the server's
 * layout wins and version skew degrades the way a description always has.</p>
 *
 * <h3>The field declaration is the declaration</h3>
 *
 * <p>Every non-static {@link UIElement} field declared on a {@code Networked} class is a part of this
 * panel. On the <b>build</b> side {@link UiType#build} creates anything left null, stamps
 * {@code setId(fieldName)} on it, and then calls {@link #layout}. On the <b>bind</b> side
 * {@link UiType#bind} resolves each field out of the panel's own rebuilt subtree, by that same name
 * and the field's own type. So the name is written once, as the thing you were going to write anyway.
 * A widget whose constructor takes arguments simply gets an ordinary initializer; nulls are filled,
 * everything else is kept.</p>
 *
 * <p>The walk stops at the first superclass that is not itself {@code Networked} — so a panel
 * extending another panel contributes both levels' fields, while a panel extending an ordinary widget
 * never has that widget's internals claimed as parts.</p>
 *
 * <h3>Methods may be side-specific; fields may not</h3>
 *
 * <p>The rule that lets one class hold both halves, and it is how class loading works rather than a
 * convention: <b>a field descriptor resolves when the class loads; a method body does not.</b>
 * {@link #serve} may name server-only types and {@link #client} may name client-only ones, because
 * each is a method body invoked only on its own side. <b>Measured rather than assumed:</b> a probe
 * naming {@code org.lwjgl.input.Keyboard} — genuinely absent on a dedicated server — in both a method
 * body and a method signature loaded and ran there, and {@code :mc1710:serverSmoke} still reported no
 * client-only class loaded.</p>
 *
 * <h3>The model is a parameter, not a field</h3>
 *
 * <p>The framework holds the model (it was handed to {@code open}), so the framework hands it to the
 * hooks that run where it exists — and only those. A server hook takes an {@code M} because there is
 * one there; a client hook cannot take one because nothing on the client ever has it. The side
 * boundary is visible in the signatures, and the old {@code model()} accessor — which answered null on
 * the client and waited for the first panel to call it from the wrong side — cannot be spelled. A
 * panel that wants the model across its own private methods assigns a field in {@link #serve},
 * explicitly, one line. <b>{@code M} appears only in method signatures, which erase</b> — so it may be
 * a type the client cannot load.</p>
 *
 * <h3>Three lifetimes, and which hook has which</h3>
 *
 * <table>
 *   <tr><th>Hook</th><th>Runs</th><th>Why there</th></tr>
 *   <tr><td>{@link #layout}</td><td>server, once, at build</td>
 *       <td>Structure cannot be inferred from fields — which row, which container, which class</td></tr>
 *   <tr><td>{@link #serve}</td><td>server, once, before the client is told anything</td>
 *       <td>Handlers live on the session, keyed by element or method — they run once</td></tr>
 *   <tr><td>{@link #bound()}</td><td>client, at mount and after every re-describe</td>
 *       <td>Widget listeners die with the tree that carried them — they run every time</td></tr>
 *   <tr><td>{@link #client}</td><td>client, once, at mount</td>
 *       <td>A session registration is keyed by method and refused a second time</td></tr>
 * </table>
 *
 * <h3>Public hooks, acknowledged</h3>
 *
 * <p>An interface cannot have protected members, so these are callable by hand. Nothing sane calls
 * them — the framework is the driver — and every {@code Screen} and {@code Container} method in
 * Minecraft modding lives with the same exposure.</p>
 *
 * @param <M> whatever this panel is a view of — a machine, an inventory, a document. Appears only in
 *            erased method signatures, so it may be a server-only type
 */
public interface Networked<M> {

    /**
     * Arranges this panel's widgets. <b>Server side, once, at build.</b>
     *
     * <p>Every field is already created and named by the time this runs, so this is purely structure:
     * {@code addChild}, rows, containers, classes, order. The model is available for structure that
     * depends on it — a row per inventory slot — and is ignored by a panel whose shape is fixed.</p>
     */
    void layout(M model);

    /**
     * Registers what this panel does <b>on the server</b>. Once, before the client is told anything —
     * the host calls it, so the handlers-before-open rule cannot be broken from here.
     */
    default void serve(M model, ServerScope io) {
    }

    /**
     * One world tick while this panel's window is open. <b>Server side only.</b>
     *
     * <p>Mirror the model into widgets and stop: the host flushes whatever that dirtied, as one
     * message, after this returns. Nothing here has to know which fields moved.</p>
     */
    default void tick(M model) {
    }

    /**
     * Minecraft's {@code canInteractWith}: may this window go on existing? Checked by the host every
     * tick, before {@link #tick}. Answering false closes the window as {@code NOT_VALID}.
     *
     * @param viewer the connection's peer — the platform's player handle, or {@code null} in loopback
     */
    default boolean stillValid(M model, @Nullable Object viewer) {
        return true;
    }

    /** What to call the window on screen, or {@code null} to let the type's id stand in. */
    @Nullable
    default String title(M model) {
        return null;
    }

    /**
     * Uniqueness and persistence key, or {@code null} for "always a new window".
     *
     * <p>Takes the model because a key names the <em>subject</em> — "the machine at this position" —
     * and the subject is what the model is. A key makes re-opening free: the host brings the existing
     * window forward, keeping its scroll position and whatever is half-typed in it.</p>
     */
    @Nullable
    default String key(M model) {
        return null;
    }

    /**
     * The panel was just bound to a tree — <b>client side, at mount and again after every
     * re-describe</b>. Attach listeners to this panel's own widgets here.
     *
     * <p>Widget listeners belong here and nowhere else, because they die with the tree that carried
     * them: a re-describe replaces the panel wholesale, so anything attached to the old widgets went
     * with them. Nothing here touches the wire — that is {@link #client}'s job.</p>
     */
    default void bound() {
    }

    /**
     * Registers what this panel answers <b>on the wire, on the client</b>. Once, at mount.
     *
     * <p>Separate from {@link #bound()} because the two have different lifetimes and the difference is
     * not cosmetic: a session registration is keyed by <em>method</em>, so running it twice is not a
     * duplicate listener but a <b>{@code MessageRouter} refusal</b>. Widget listeners are the opposite
     * and must run every time.</p>
     *
     * <p>The scope mirrors the server's: a nested panel's methods are qualified by the same id path on
     * both sides, so the two halves of {@code "engines/save"} agree by construction. May freely name
     * client-only types: this is a method body. @see Networked</p>
     */
    default void client(ClientScope io) {
    }

    /**
     * Told when the window ends, on whichever side this instance is. On the server the reason is a
     * {@code CloseReason} name — {@code "SERVER"}, {@code "CLIENT"}, {@code "NOT_VALID"},
     * {@code "CONNECTION_LOST"}; on the client it is the detail string the wire carried.
     *
     * <p>Deliberately not split per side: most panels want the same teardown twice, and one that does
     * not can ask — only the client half was ever handed a {@link ClientWindowContext}.</p>
     */
    default void closed(String reason) {
    }
}
