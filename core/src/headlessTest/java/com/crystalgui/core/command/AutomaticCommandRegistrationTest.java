package com.crystalgui.core.command;

import com.crystalgui.ui.UIElement;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * <b>A widget's commands exist because it exists.</b>
 *
 * <p>Three shapes preceded this and each was something a caller had to remember: a static
 * {@code register(registry)} a host had to call, an instance {@code installCommands()} that needed a
 * window and so ran from a <b>frame ticker</b>, and a {@code commandsInstalled} flag consulted from the
 * layout path. All three failed the same way — silently, with the widget looking finished and its keys
 * doing nothing.</p>
 *
 * <p>What this pins is the pair of guarantees that replaced them: {@link UIElement#registerCommands} runs
 * exactly once per concrete class, and {@link UIElement#bindKeys} runs for every instance. The split is
 * load-bearing — a command is one application-wide fact, while a <em>binding on an element</em> is what
 * scopes a chord to a widget, so it has to be on each one.</p>
 */
public class AutomaticCommandRegistrationTest {

    private static final AtomicInteger REGISTRATIONS = new AtomicInteger();
    private static final AtomicInteger BINDINGS = new AtomicInteger();

    @Before
    public void setUp() {
        CommandRegistry.global().resetForTesting();
        REGISTRATIONS.set(0);
        BINDINGS.set(0);
    }

    /** A widget with commands of its own, declared the only way there is. */
    private static class Widget extends UIElement {
        @Override
        protected void registerCommands(CommandRegistry registry) {
            REGISTRATIONS.incrementAndGet();
            registry.register(Command.of("widget.doThing", "Do Thing").run(context -> {
            }));
        }

        @Override
        protected void bindKeys() {
            BINDINGS.incrementAndGet();
            keymap().bind("F8", "widget.doThing");
        }
    }

    private static class OtherWidget extends UIElement {
        @Override
        protected void registerCommands(CommandRegistry registry) {
            registry.register(Command.of("other.doThing", "Do Other Thing").run(context -> {
            }));
        }
    }

    @Test
    public void buildingAWidgetRegistersItsCommands() {
        new Widget();
        assertNotNull(CommandRegistry.global().get("widget.doThing"));
    }

    @Test
    public void registrationHappensOncePerClassHoweverManyInstances() {
        for (int i = 0; i < 50; i++) new Widget();
        assertEquals(1, REGISTRATIONS.get());
    }

    @Test
    public void everyInstanceBindsItsOwnKeys() {
        Widget first = new Widget();
        Widget second = new Widget();
        assertEquals(2, BINDINGS.get());
        // Not merely counted: each carries the binding, which is what scopes the chord to the widget.
        assertNotNull(first.keymap().chordFor("widget.doThing"));
        assertNotNull(second.keymap().chordFor("widget.doThing"));
    }

    /**
     * <b>The failure a static latch produces, and the reason once-ness lives on the registry.</b>
     *
     * <p>A {@code ClassValue} latch on {@code UIElement} outlives {@link CommandRegistry#resetForTesting()}:
     * the reset empties the registry, the next widget of an already-seen class registers nothing, and the
     * command is simply absent — no throw, no log, just a key that stopped working. Keying the record to
     * the registry means clearing one clears the other.</p>
     */
    @Test
    public void aResetRegistryIsRepopulatedByTheNextWidget() {
        new Widget();
        assertNotNull(CommandRegistry.global().get("widget.doThing"));

        CommandRegistry.global().resetForTesting();
        assertNull(CommandRegistry.global().get("widget.doThing"));

        new Widget();
        assertNotNull(CommandRegistry.global().get("widget.doThing"));
    }

    @Test
    public void oneWidgetsRegistrationDoesNotSuppressAnothers() {
        new Widget();
        new OtherWidget();
        assertNotNull(CommandRegistry.global().get("widget.doThing"));
        assertNotNull(CommandRegistry.global().get("other.doThing"));
    }

    // ── contribute() itself ──────────────────────────────────────────────────────────────────────

    @Test
    public void contributeRunsOnceAndReturnsTheRegistry() {
        CommandRegistry registry = new CommandRegistry();
        AtomicInteger runs = new AtomicInteger();
        for (int i = 0; i < 10; i++) registry.contribute(AutomaticCommandRegistrationTest.class, r -> runs.incrementAndGet());
        assertEquals(1, runs.get());
    }

    /**
     * <b>Per registry, which is what gives a window-capturing bundle per-window once-ness.</b>
     *
     * <p>{@code ChromeCommands} and friends capture their owner, so they register into that window's own
     * registry rather than the global one. Keying the record to the registry instance is what makes a
     * second window register its own copy instead of silently reusing the first window's — which is the
     * bug a sentinel id ({@code if (registry.contains(SHOW_COMMANDS)) return;}) could not distinguish
     * from correct behaviour.</p>
     */
    @Test
    public void twoRegistriesEachRunTheSameContributorOnce() {
        AtomicInteger runs = new AtomicInteger();
        new CommandRegistry().contribute(AutomaticCommandRegistrationTest.class, r -> runs.incrementAndGet());
        new CommandRegistry().contribute(AutomaticCommandRegistrationTest.class, r -> runs.incrementAndGet());
        assertEquals(2, runs.get());
    }
}
