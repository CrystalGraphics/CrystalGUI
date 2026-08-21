package com.crystalgui.headless;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.command.CommandProtocol;
import com.crystalgui.net.command.CommandProtocolBinding;
import com.crystalgui.net.command.RemoteCommandPolicy;
import com.crystalgui.net.command.RemoteCommands;
import com.crystalgui.net.command.ServerCommands;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.serialization.StateMap;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Phase 5 <b>5.8</b> — a server contributing commands to a client.
 *
 * <p>A contributed command is an ordinary {@code Command}: the palette enumerates it, a menu renders it,
 * the keymap resolves it, and nothing downstream knows that running it sends a packet.</p>
 *
 * <h3>Most of what is asserted here is the trust boundary</h3>
 *
 * <p>This is the first message on the protocol that changes what the client's own machinery <b>does</b>
 * rather than what it shows, and {@code CommandRegistry.register} replaces by id on purpose. So the
 * interesting tests are the ones about what a server is <em>not</em> allowed to do — above all that it
 * cannot claim {@code edit.save} and turn the client's own Save into a packet.</p>
 */
public class ServerContributedCommandsTest {

    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverSide;
    private ProtocolConnection<Object> clientSide;

    private CommandRegistry registry;
    private ServerCommands<Object> server;
    private RemoteCommands<Object> client;

    @Before
    public void setUp() {
        Protocols.resetForTesting();
        CommandProtocolBinding.resetForTesting();

        link = InMemoryTransport.pair();
        serverSide = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "alice");
        clientSide = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);

        registry = new CommandRegistry();
        server = ServerCommands.forConnection(serverSide);
        client = RemoteCommands.install(clientSide, registry);
    }

    @After
    public void tearDown() {
        Protocols.resetForTesting();
        CommandProtocolBinding.resetForTesting();
    }

    private void settle() {
        for (int i = 0; i < 24; i++) {
            link[0].deliver();
            link[1].deliver();
            serverSide.tick();
            clientSide.tick();
        }
    }

    // ── The feature ─────────────────────────────────────────────────────────────────────────────

    /** A contributed command appears as an ordinary command, and running it reaches the server. */
    @Test
    public void aContributedCommandRunsOnTheServer() {
        AtomicInteger ran = new AtomicInteger();
        server.contribute("server.restart", "Restart Server", (args, respond) -> {
            ran.incrementAndGet();
            respond.ok(null);
        });
        settle();

        Command command = registry.get("server.restart");
        assertNotNull("the command must be in the registry", command);
        assertEquals("Restart Server", command.getLabel());

        registry.run("server.restart", CommandContext.of(null));
        settle();

        assertEquals("running it must reach the server", 1, ran.get());
    }

    /** Enablement is pushed and read at ask time, not frozen when the command was contributed. */
    @Test
    public void enablementIsPushedAndReadWhenAsked() {
        server.contribute("server.reload", "Reload Config", (args, respond) -> respond.ok(null));
        settle();
        assertTrue("contributed commands start available", client.isEnabled("server.reload"));

        server.setEnabled("server.reload", false);
        settle();

        assertFalse(client.isEnabled("server.reload"));
        assertFalse("and the Command itself must answer the same",
                registry.get("server.reload").isEnabled(CommandContext.of(null)));

        server.setEnabled("server.reload", true);
        settle();
        assertTrue("and back again", registry.get("server.reload").isEnabled(CommandContext.of(null)));
    }

    /** An id nobody has said anything about is enabled. @see RemoteCommands */
    @Test
    public void anUnknownEnablementMeansAvailable() {
        assertTrue(client.isEnabled("server.neverMentioned"));
    }

    /** Withdrawing takes it out of the registry. */
    @Test
    public void withdrawingRemovesIt() {
        server.contribute("server.restart", "Restart Server", (args, respond) -> respond.ok(null));
        settle();
        assertNotNull(registry.get("server.restart"));

        server.withdraw("server.restart");
        settle();

        assertNull("the command must be gone", registry.get("server.restart"));
        assertTrue(client.contributed().isEmpty());
    }

    /**
     * A disconnected server's commands are removed, not left looking live.
     *
     * <p>Left in the palette they still render and still respond to a click, and running one waits out
     * its call timeout before reporting a failure whose real cause happened minutes earlier.</p>
     */
    @Test
    public void withdrawAllClearsThemWhenThePeerGoesAway() {
        server.contribute("server.a", "A", (args, respond) -> respond.ok(null));
        server.contribute("server.b", "B", (args, respond) -> respond.ok(null));
        settle();
        assertEquals(2, client.contributed().size());

        client.withdrawAll();

        assertNull(registry.get("server.a"));
        assertNull(registry.get("server.b"));
        assertTrue(client.contributed().isEmpty());
    }

    /**
     * Invoking something the server has withdrawn is <b>refused</b>, not dropped.
     *
     * <p>A command withdrawn between a menu opening and the user clicking is an ordinary race. The client
     * has to be told it lost, or the call waits out its deadline and reports a timeout, which reads as a
     * slow server rather than a command that is no longer there.</p>
     */
    @Test
    public void invokingAWithdrawnCommandIsRefused() {
        AtomicReference<String> error = new AtomicReference<>();
        StateMap<Object> args = new StateMap<>(PlainOps.INSTANCE);
        args.putString(CommandProtocol.ID, "server.gone");
        clientSide.call(CommandProtocol.INVOKE, args,
                result -> fail("a command that does not exist answered"), error::set);
        settle();

        assertNotNull("the caller must be told, not left to time out", error.get());
        assertTrue(error.get(), error.get().contains("server.gone"));
    }

    // ── The trust boundary ──────────────────────────────────────────────────────────────────────

    /**
     * <b>A server cannot claim an id the client defines.</b>
     *
     * <p>The whole reason the policy exists. {@code CommandRegistry.register} replaces by id — that is how
     * a theme or a mod overrides a built-in — so without the namespace floor a server could register
     * {@code edit.save} and the client's own Save would quietly become a packet.</p>
     *
     * <p>Driven through the wire rather than through {@code ServerCommands.contribute}, which refuses it
     * up front: the check that matters is the one on the <b>receiving</b> side, since a hostile peer will
     * not be using our sender.</p>
     */
    @Test
    public void aServerCannotShadowAClientCommand() {
        AtomicInteger ours = new AtomicInteger();
        registry.register(Command.of("edit.save", "Save").run(ours::incrementAndGet));

        sendRawContribution("edit.save", "Save (hijacked)");
        settle();

        assertEquals("the client's own command must be untouched",
                "Save", registry.get("edit.save").getLabel());
        registry.run("edit.save", CommandContext.of(null));
        settle();
        assertEquals("and must still be the one that runs", 1, ours.get());
        assertTrue("nothing was contributed", client.contributed().isEmpty());
    }

    /** The floor is a namespace, so anything outside it is refused whatever it is named. */
    @Test
    public void anIdOutsideTheReservedNamespaceIsRefused() {
        sendRawContribution("myMod.doThing", "Do Thing");
        sendRawContribution("server", "Bare prefix");
        sendRawContribution("", "No id at all");
        settle();

        assertTrue("none of these may be accepted: " + client.contributed(),
                client.contributed().isEmpty());
        assertNull(registry.get("myMod.doThing"));
    }

    /** And our own sender refuses it too, where the typo was made rather than on somebody's machine. */
    @Test
    public void contributingOutsideTheNamespaceThrowsAtTheServer() {
        try {
            server.contribute("edit.save", "Save", (args, respond) -> respond.ok(null));
            fail("a server must not be able to offer an id outside the reserved namespace");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains(RemoteCommandPolicy.RESERVED_PREFIX));
        }
    }

    /** A refusal costs its own entry and not the batch around it. */
    @Test
    public void oneRefusedEntryDoesNotCostTheRest() {
        StateMap<Object> payload = new StateMap<>(PlainOps.INSTANCE);
        List<String[]> entries = new ArrayList<>();
        entries.add(new String[] {"server.first", "First"});
        entries.add(new String[] {"edit.save", "Hijack"});
        entries.add(new String[] {"server.last", "Last"});
        payload.putList(CommandProtocol.COMMANDS, entries, (entry, pair) -> {
            entry.putString(CommandProtocol.ID, pair[0]);
            entry.putString(CommandProtocol.LABEL, pair[1]);
        });
        serverSide.notify(CommandProtocol.CONTRIBUTE, payload);
        settle();

        assertEquals("the two legitimate entries must survive the one refusal",
                2, client.contributed().size());
        assertNotNull(registry.get("server.first"));
        assertNotNull(registry.get("server.last"));
        assertNull(registry.get("edit.save"));
    }

    /** A flood is capped, so a palette cannot be made unusable. */
    @Test
    public void aFloodIsCapped() {
        for (int i = 0; i < RemoteCommandPolicy.MAX_COMMANDS + 40; i++) {
            sendRawContribution("server.c" + i, "C" + i);
        }
        settle();

        assertEquals(RemoteCommandPolicy.MAX_COMMANDS, client.contributed().size());
    }

    /** A label is drawn, so what arrives in one is not what is shown. */
    @Test
    public void labelsAreSanitised() {
        // A newline and a tab, written as ESCAPES. An earlier version of this line carried the
        // real characters, and one of them was a NUL -- which made the whole source file read as
        // binary to every tool that looked at it.
        sendRawContribution("server.nasty", "Restart\nServer\tnow");
        settle();

        String label = registry.get("server.nasty").getLabel();
        assertFalse("no newline survived: " + label, label.contains("\n"));
        assertEquals("a control character becomes a SPACE rather than being stripped, so two "
                        + "words cannot be run together into a third that was never written",
                "Restart Server now", label);
    }

    /** A label longer than the cap is cut rather than the command being lost. */
    @Test
    public void anOverlongLabelIsCutRatherThanRefused() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 500; i++) huge.append('x');
        sendRawContribution("server.long", huge.toString());
        settle();

        assertNotNull("a clumsy label is better than a missing command", registry.get("server.long"));
        assertEquals(RemoteCommandPolicy.MAX_LABEL_LENGTH, registry.get("server.long").getLabel().length());
    }

    /** A host may decline the whole mechanism. */
    @Test
    public void aHostMayRefuseServerCommandsOutright() {
        CommandRegistry strict = new CommandRegistry();
        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        ProtocolConnection<Object> theirServer =
                Protocols.open(pair[0], PlainOps.INSTANCE, () -> { }, "bob");
        ProtocolConnection<Object> theirClient =
                Protocols.open(pair[1], PlainOps.INSTANCE, () -> { }, null);

        RemoteCommands<Object> refusing =
                RemoteCommands.install(theirClient, strict, RemoteCommandPolicy.REFUSE_ALL);
        ServerCommands.forConnection(theirServer)
                .contribute("server.restart", "Restart", (args, respond) -> respond.ok(null));

        for (int i = 0; i < 24; i++) {
            pair[0].deliver();
            pair[1].deliver();
            theirServer.tick();
            theirClient.tick();
        }

        assertTrue(refusing.contributed().isEmpty());
        assertNull(strict.get("server.restart"));
    }

    /** A server cannot withdraw an id it never contributed. */
    @Test
    public void aServerCannotWithdrawWhatItDidNotContribute() {
        registry.register(Command.of("edit.save", "Save").run(() -> { }));

        StateMap<Object> payload = new StateMap<>(PlainOps.INSTANCE);
        payload.putList(CommandProtocol.COMMANDS, List.of("edit.save"),
                (entry, id) -> entry.putString(CommandProtocol.ID, id));
        serverSide.notify(CommandProtocol.WITHDRAW, payload);
        settle();

        assertNotNull("the client's own command must survive", registry.get("edit.save"));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    /** Contributes straight down the wire, bypassing {@link ServerCommands}' own check. */
    private void sendRawContribution(String id, String label) {
        StateMap<Object> payload = new StateMap<>(PlainOps.INSTANCE);
        payload.putList(CommandProtocol.COMMANDS,
                Collections.singletonList(new String[] {id, label}),
                (entry, pair) -> {
                    entry.putString(CommandProtocol.ID, pair[0]);
                    entry.putString(CommandProtocol.LABEL, pair[1]);
                });
        serverSide.notify(CommandProtocol.CONTRIBUTE, payload);
    }
}
