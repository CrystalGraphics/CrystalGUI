package com.crystalgui.core.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.crystalgraphics.job.CgHostThread;
import com.crystalgui.core.async.Owner;
import com.crystalgui.core.async.UiSequence;
import com.crystalgui.ui.input.keymap.Keymap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** What the commands' lookup, menus and keymap are made of: indices of the generation, never edited in place. */
public class CommandsGenerationTest {

    private static final MenuId MENU = MenuId.of("test/generations");

    @Test
    public void aScopeFallsThroughToTheProcessAndSeesALaterRegistration() {
        Commands process = Commands.process();
        Commands scope = new Commands();
        Owner mod = Owner.of("generation-test");
        try {
            assertNull(scope.get("gen.late"));
            process.contribute(mod, Command.of("gen.late", "Late").menu(MENU, "g", 1).binding("Mod+Alt+9"));

            assertNotNull("the scope's index follows the process list's generation", scope.get("gen.late"));
            assertEquals(1, scope.sections(MENU, CommandContext.of(null)).get(0).entries().size());
            assertNotNull(scope.declaredBindings().chordFor("gen.late"));

            process.removeOwner(mod);
            assertNull(scope.get("gen.late"));
            assertTrue(scope.sections(MENU, CommandContext.of(null)).isEmpty());
        } finally {
            process.removeOwner(mod);
        }
    }

    @Test
    public void theIndexIsSharedUntilTheListChanges() {
        Commands scope = new Commands();
        scope.register(Command.of("gen.one", "One"));
        Keymap first = scope.declaredBindings();
        assertSame(first, scope.declaredBindings());

        scope.register(Command.of("gen.two", "Two").binding("Mod+Alt+8"));
        assertFalse("a new generation builds a new keymap", first == scope.declaredBindings());
    }

    @Test
    public void aLocalCommandShadowsTheProcessOne() {
        Commands process = Commands.process();
        Commands scope = new Commands();
        Owner mod = Owner.of("shadow-test");
        try {
            process.contribute(mod, Command.of("gen.shadow", "Process"));
            Command mine = Command.of("gen.shadow", "Mine");
            scope.register(mine);
            assertSame(mine, scope.get("gen.shadow"));
            assertEquals(1, scope.all().stream().filter(c -> c.getId().equals("gen.shadow")).count());
        } finally {
            process.removeOwner(mod);
        }
    }

    /** A command with no document, asked to run from a thread that is no sequence, runs on the services sequence. */
    @Test
    public void aCommandWithNoDocumentRunsOnTheServicesSequence() throws Exception {
        Commands scope = new Commands();
        AtomicReference<UiSequence> ranOn = new AtomicReference<>();
        CountDownLatch ran = new CountDownLatch(1);
        scope.register(Command.of("gen.global", "Global").run(context -> {
            ranOn.set(UiSequence.current());
            ran.countDown();
        }));

        AtomicReference<Boolean> accepted = new AtomicReference<>();
        // The frame thread is claimed by this test's own thread, so the other is neither it nor a sequence.
        CgHostThread.drainFrames();
        Thread foreign = new Thread(() -> accepted.set(scope.run("gen.global")));
        foreign.start();
        foreign.join();

        assertTrue(accepted.get());
        assertTrue("it ran", ran.await(5, TimeUnit.SECONDS));
        assertSame(UiSequence.services(), ranOn.get());
    }
}
