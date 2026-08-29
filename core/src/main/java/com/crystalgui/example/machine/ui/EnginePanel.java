package com.crystalgui.example.machine.ui;

import javax.annotation.Nullable;

import com.crystalgui.example.machine.EngineModel;
import com.crystalgui.example.machine.MachineTrace;
import com.crystalgui.net.window.ClientScope;
import com.crystalgui.net.window.Networked;
import com.crystalgui.net.window.ServerScope;
import com.crystalgui.net.window.UiType;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.ProgressBar;
import com.crystalgui.ui.elements.Slider;
import com.crystalgui.ui.elements.UIText;

/**
 * <b>Step 2b — a UI inside a UI.</b>
 *
 * <p>The machine's engine, as a panel: a load slider, a heat bar, a Restart button, and one wire
 * method of its own. It is a {@link Networked} element like {@link MachinePanel} is, and
 * {@code MachinePanel} holds one as an ordinary field — which is the whole of what composition costs
 * here, because <b>a panel is an element</b> and elements have always nested.</p>
 *
 * <h3>What it is handed, and what it therefore cannot reach</h3>
 *
 * <p>Every hook below takes an {@link EngineModel}. Not a {@code MachineModel} — <b>the parent passes
 * a slice</b>, {@code io.attach(engine, model.engine())}, and the child's signatures are the boundary.
 * This class could not stop the machine, rename it or read its cycle count if it wanted to: it has no
 * reference to reach them through, and the compiler is what says so. Contrast the alternative that
 * looks equivalent and is not — handing the child the whole model and trusting it — where the same
 * class compiles, works, and has quietly become a second place that knows everything.</p>
 *
 * <p>The slice is narrower still than "the engine object". {@link EngineModel}'s constructor and
 * {@code tick} are package-private and this class is one package over, so what is actually reachable
 * from here is the operator's surface: set the load, restart it, read the dials. A child UI is
 * trusted with what the user is allowed to do and not with advancing the world.</p>
 *
 * <h3>The prefix nobody types</h3>
 *
 * <p>{@code io.onCall("tune", …)} on the server and {@code io.call("tune", …)} on the client both
 * become <b>{@code engine/tune}</b> on the wire — because this panel is the parent's field named
 * {@code engine}, so its element id is {@code engine}, so both scopes derive the same prefix from the
 * same tree. The panel prints {@link ServerScope#qualify} and {@link ClientScope#qualify} side by side
 * so the agreement is on screen rather than asserted in a comment: neither half names the string, and
 * a second instance of this panel under a different field would be a different method with the same
 * source line.</p>
 *
 * <p>Note what is <em>not</em> prefixed: {@link ServerScope#on} and {@code onActivate} are keyed by
 * the <b>element</b>, and a panel's elements are its own, so two engine panels have nothing to
 * collide over. Only the method strings need a namespace, and only they get one.</p>
 *
 * <h3>Talking back to the parent</h3>
 *
 * <p>{@link #onRestarted} is a plain Java callback, and that is the rule rather than a shortcut: the
 * parent's server half and the child's server half are <b>objects in the same process on the same
 * thread</b>. Routing this through a session message would be a round trip to the room you are
 * standing in, and would also invent a wire contract for something no client ever sees.</p>
 *
 * <p>One slot, not a list — the opposite of {@code MachineModel.onChanged}, deliberately. A model has
 * as many watchers as there are open windows; a child element has exactly one parent.</p>
 *
 * <h3>Which hooks a nested panel is actually asked</h3>
 *
 * <p>{@link #layout}, {@link #serve}, {@link #tick}, {@link #bound()}, {@link #client} and
 * {@link #closed} all run for a nested panel exactly as they do for a root one. {@code title},
 * {@code key} and {@code stillValid} are <b>window</b>-level questions and are only ever asked of the
 * root, so they are left defaulted here — a nested panel that overrode them would be writing code
 * nothing calls.</p>
 */
public final class EnginePanel extends UIElement implements Networked<EngineModel> {

    /**
     * Declared for the same reason {@link MachinePanel#TYPE} is, plus one that only applies to a
     * child: {@code build} is how the parent constructs it with its slice, in {@code layout}.
     *
     * <p>The client needs no equivalent step. {@code MachinePanel.TYPE}'s own declaration walks its
     * fields and registers the tag of every nested panel it finds, so a description saying
     * {@code <enginepanel>} decodes here without this class having been touched on that side.</p>
     */
    public static final UiType<EnginePanel, EngineModel> TYPE =
            UiType.of("crystalgui:machine.engine", EnginePanel::new);

    /** 0..1, how hard the engine is driven. Reports {@code value} — wired on the SERVER. */
    public Slider load;

    /** Heat, 0..1. Server-driven only; the client never writes to it. */
    public ProgressBar heat;

    /** A line of server-written text. */
    public UIText reading = new UIText("");

    /** Clears a stall. SERVER-wired, and the one thing the parent is told about. */
    public Button restart = new Button("Restart");

    /** CLIENT-wired: calls this panel's own {@code tune} method. */
    public Button tune = new Button("Tune to full");

    /** What the SERVER's scope says this panel's {@code tune} is called. Nothing else writes it. */
    public UIText serverWire = new UIText("");

    /** What the CLIENT's scope says the same method is called. They must agree. */
    public UIText clientWire = new UIText("");

    /** The client's readout of the last {@code engine/tune}. Client-authored. */
    public UIText result = new UIText("nothing yet");

    /** Set by {@link #client}. Null on the server — which is how {@link #closed} tells the sides apart. */
    @Nullable
    private ClientScope io;

    /** The parent's server-side interest in this panel. @see the class javadoc */
    @Nullable
    private Runnable onRestarted;

    /**
     * Lets the parent react to a restart — a plain callback, never a message.
     *
     * <p>Called from {@code MachinePanel.serve}, on the server, where both objects are ordinary Java
     * objects. Returns {@code this} so it reads as one line beside the {@code attach} that wired it.</p>
     */
    public EnginePanel onRestarted(Runnable listener) {
        this.onRestarted = listener;
        return this;
    }

    // ── Structure ───────────────────────────────────────────────────────────

    @Override
    public void layout(EngineModel engine) {
        addClass(MachineStyles.ENGINE_CLASS);

        UIText title = new UIText("Engine");
        title.addClass(MachineStyles.TITLE_CLASS);
        addChild(title);

        load.setRange(0f, 1f);
        addChild(MachineRows.row("Load", load));
        addChild(MachineRows.row("Heat", heat));

        reading.addClass(MachineStyles.STATUS_CLASS);
        addChild(reading);

        UIElement controls = new UIElement();
        controls.addClass(MachineStyles.ROW_CLASS);
        controls.addChild(restart);
        controls.addChild(tune);
        addChild(controls);

        UIText caption = new UIText("This panel's own wire method. Neither side typed the prefix:");
        caption.addClass(MachineStyles.HINT_CLASS);
        addChild(caption);

        addChild(MachineRows.authored(MachineStyles.WHO_SERVER_CLASS, "SERVER", serverWire));
        addChild(MachineRows.authored(MachineStyles.WHO_CLIENT_CLASS, "CLIENT", clientWire));

        result.addClass(MachineStyles.WIRE_CLASS);
        result.neverSelfSizeWidth();
        addChild(result);
    }

    // ── The SERVER half ─────────────────────────────────────────────────────

    @Override
    public void serve(EngineModel engine, ServerScope io) {
        // ELEMENT-KEYED, so unprefixed and unambiguous however deeply this panel is nested.
        io.on(load, Slider.VALUE_CHANGED, (ctx, value) -> {
            MachineTrace.log(MachineTrace.SERVER, String.format("engine: load -> %.2f", value));
            engine.setLoad(value);
        });

        io.on(restart, Button.ACTIVATE, ctx -> {
            MachineTrace.log(MachineTrace.SERVER, "engine: restart pressed");
            engine.restart();
            // Events UP, as an ordinary call on an ordinary object. @see the class javadoc
            if (onRestarted != null) onRestarted.run();
        });

        /*
         * METHOD-KEYED, so this one IS prefixed -- "tune" here is "engine/tune" on the wire, and the
         * scope derived that from this panel's element id. The parent registering its own "tune" would
         * be a different method, and so would a second engine panel under a different field.
         */
        io.onCall("tune", (args, respond) -> {
            float wanted = args.getFloat("load", engine.load());
            MachineTrace.log(MachineTrace.SERVER,
                    String.format("<- answering %s (load %.2f)", io.qualify("tune"), wanted));
            engine.setLoad(wanted);
            StateMap<Object> out = io.newMap();
            out.putFloat("temperature", engine.temperature());
            respond.ok(out);
        });

        // The proof, written where the fact is known. The client writes its own half in #client, and
        // the two lines must read the same thing.
        serverWire.setText(io.qualify("tune"));

        mirror(engine);
    }

    /**
     * One world tick. <b>No dirty flag</b>, unlike {@link MachinePanel} — and the difference is worth
     * a sentence, because it looks like an inconsistency.
     *
     * <p>The parent subscribes to the model so it can skip mirroring entirely on a quiet tick. This
     * panel has three setters to run and all of them are idempotent — an unchanged value writes no
     * candidate and marks nothing dirty — so the subscription would buy a handful of float
     * comparisons. <b>Both are correct; neither generates traffic.</b> Reach for the flag when the
     * mirroring itself is expensive, not by default.</p>
     */
    @Override
    public void tick(EngineModel engine) {
        mirror(engine);
    }

    private void mirror(EngineModel engine) {
        load.setValue(engine.load());
        heat.setFraction(engine.temperature());
        reading.setText(engine.isStalled()
                ? String.format("STALLED at %.0f%% heat - press Restart", engine.temperature() * 100f)
                : String.format("%.0f%% load, %.0f%% heat",
                        engine.load() * 100f, engine.temperature() * 100f));
    }

    // ── The CLIENT half ─────────────────────────────────────────────────────

    @Override
    public void client(ClientScope io) {
        this.io = io;
        clientWire.setText(io.qualify("tune"));
    }

    @Override
    public void bound() {
        tune.attachListener(this::tuneToFull);
    }

    /** ── C → S REQUEST, on this panel's own method ── */
    private void tuneToFull() {
        StateMap<Object> args = io.newMap();
        args.putFloat("load", 1f);
        MachineTrace.log(MachineTrace.CLIENT, "-> asking the server " + io.qualify("tune"));
        result.setText("REQUEST sent on " + io.qualify("tune") + " - waiting for an answer...");
        io.call("tune", args,
                answer -> {
                    float temperature = answer.getFloat("temperature", 0f);
                    MachineTrace.log(MachineTrace.CLIENT,
                            String.format("<- engine answered: %.0f%% heat", temperature * 100f));
                    result.setText(String.format("REQUEST answered - full load, %.0f%% heat",
                            temperature * 100f));
                },
                error -> {
                    MachineTrace.log(MachineTrace.CLIENT, "<- engine refused: " + error);
                    result.setText("REQUEST failed - " + error);
                });
    }

    @Override
    public void closed(String reason) {
        // A nested panel is told too, on both sides, at the same moment its parent is -- the window is
        // what ended, and everything in it went with it.
        MachineTrace.log(io == null ? MachineTrace.SERVER : MachineTrace.CLIENT,
                "engine panel closed: " + reason);
    }
}
