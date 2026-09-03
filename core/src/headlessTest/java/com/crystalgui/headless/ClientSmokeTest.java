package com.crystalgui.headless;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nullable;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.ui.contract.State;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.WidgetContracts;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.app.WidgetCensus;

/**
 * <b>Every contracted widget, over a loopback wire, at once.</b> {@code plan_ui_rewrite.md} M8.
 *
 * <p>One tree holding one of every widget that declares a {@link WidgetContract}, served to a client
 * over an in-memory transport. Each widget's slots are set on the server, flushed, and read back off
 * the client's own instance. What it proves is not any one widget — those have their own tests — but
 * that the SET is complete and that nothing in it is quietly inert.</p>
 *
 * <h3>Why a coverage walk rather than more hand-written cases</h3>
 *
 * <p>{@code WidgetContractRoundTripTest} names its subjects, which means it tests the widgets somebody
 * remembered. The failure this exists for is the opposite one: a slot with a <b>stub getter</b> is
 * settable by a server, never written to the wire, and declared in a way that reads as complete — so
 * the state simply never travels and there is nothing to search for. Three were written that way in
 * one sitting and all three were found by a coverage test rather than by reading the code. A walk over
 * the registry is the only instrument that sees a widget added after the walk was written.</p>
 *
 * <h3>Distinct values, not defaults</h3>
 *
 * <p>Every slot is set to something that differs from its own fallback before the round trip. A slot
 * left at its default round-trips perfectly through a codec that drops it, which is exactly the bug —
 * so the value has to be one the encoder had to carry.</p>
 */
public class ClientSmokeTest {

    private static final String NL = System.lineSeparator();

    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverEnd;
    private ProtocolConnection<Object> clientEnd;
    private ServerUiSession<UIElement, Object> server;
    private ClientUiSession<UIElement, Object> client;

    /** The served tree's root, and one widget per contracted kind under it. */
    private UIElement root;

    /** Class → the server's instance of it, in registration order. */
    private final Map<Class<?>, UIElement> served = new LinkedHashMap<>();

    @Before
    public void setUp() {
        Protocols.resetForTesting();
        // THE CENSUS FIRST: it is what registers the contracts this walk enumerates, and the kinds
        // come from the registry's own bootstrap.
        WidgetCensus.register();
        UIElementRegistry.bootstrap();

        link = InMemoryTransport.pair();
        serverEnd = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "player");
        clientEnd = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);

        root = new UIElement();
        for (Map.Entry<Class<?>, WidgetContract<?>> entry : WidgetContracts.all().entrySet()) {
            UIElement widget = build(entry.getKey());
            if (widget == null) continue;
            served.put(entry.getKey(), widget);
            root.append(widget);
        }

        server = Sessions.serveOn(1, root, serverEnd);
        client = Sessions.viewOn(clientEnd);
    }

    @After
    public void tearDown() {
        Protocols.resetForTesting();
    }

    private void settle() {
        for (int i = 0; i < 10; i++) {
            link[0].deliver();
            link[1].deliver();
            serverEnd.tick();
            clientEnd.tick();
            server.tick();
        }
    }

    /**
     * A fresh widget of this class, through the registry — or null when it has no buildable kind.
     *
     * <p>A contracted class with no factory is a real state and not a gap: the config-control kit is
     * registered for its NAMES alone, because every one of those takes a {@code ConfigDescriptor} and
     * there is no sensible no-argument form of one. Nothing decodes into them, so nothing here can
     * round-trip one — and {@link #everyContractedWidgetIsEitherBuildableOrDeliberatelyNot} is what
     * stops that becoming a quiet exemption.</p>
     */
    @Nullable
    private static UIElement build(Class<?> type) {
        Name name = nameOf(type);
        if (name == null || !UIElementRegistry.isBuildable(name)) return null;
        UIElement made = UIElementRegistry.create(name);
        // THE REGISTRY ANSWERS BY TAG, and a subclass that declares no NAME of its own inherits its
        // parent's -- so asking for `button` when the contract is a Dropdown's builds a Button. That is
        // the wrong instance to assert about, and quietly.
        return type.isInstance(made) ? made : null;
    }

    /**
     * This class's {@code NAME}, through <b>one</b> field resolution.
     *
     * <p>Never {@code getDeclaredFields()}, which resolves every field's type — a text widget retains a
     * {@code CgShapedParagraph}, and this source set has CrystalGraphics core off the classpath by
     * design. The obvious loop does not fail loudly here; it drops every text-holding widget out of the
     * coverage set, which is the class most likely to be forgotten. @see NodeKindsCoverageTest</p>
     */
    @Nullable
    private static Name nameOf(Class<?> type) {
        try {
            return (Name) MethodHandles.lookup().findStaticGetter(type, "NAME", Name.class).invoke();
        } catch (Throwable absent) {
            return null;
        }
    }

    // ── The walk ────────────────────────────────────────────────────────────────────────────────

    /** The client rebuilt one of everything, and each is the class the server sent. */
    @Test
    public void everyContractedWidgetSurvivesTheRoundTrip() {
        server.open();
        settle();

        UIElement mirrored = client.root();
        assertNotNull("the client never received the window", mirrored);
        assertEquals("one widget per contracted kind, both sides",
                served.size(), mirrored.describedChildren().size());

        List<String> wrong = new ArrayList<>();
        int i = 0;
        for (Class<?> type : served.keySet()) {
            UIElement arrived = mirrored.describedChildren().get(i++);
            if (!type.isInstance(arrived)) {
                wrong.add(type.getSimpleName() + " arrived as " + arrived.getClass().getSimpleName());
            }
        }
        assertTrue("a widget decoded into the wrong class:\n" + String.join("\n", wrong),
                wrong.isEmpty());
        assertTrue("the walk found something to check", served.size() >= 10);
    }

    /**
     * <b>Every state slot travels.</b> The headline.
     *
     * <p>Each slot is set to a value that differs from its own fallback, the change is flushed, and the
     * client's copy is asked. A slot that never arrives is a widget a server can set and a viewer will
     * never see move — which looks correct on the opening frame and then never again.</p>
     */
    @Test
    public void everyStateSlotTravels() {
        server.open();
        settle();
        UIElement mirrored = client.root();
        assertNotNull(mirrored);

        List<String> silent = new ArrayList<>();
        int i = 0;
        for (Map.Entry<Class<?>, UIElement> entry : served.entrySet()) {
            UIElement sent = entry.getValue();
            UIElement arrived = mirrored.describedChildren().get(i++);
            silent.addAll(checkSlots(entry.getKey(), sent, arrived));
        }
        assertTrue("a state slot did not reach the client:\n" + String.join("\n", silent),
                silent.isEmpty());
    }

    @SuppressWarnings("unchecked")
    private List<String> checkSlots(Class<?> type, UIElement sent, UIElement arrived) {
        WidgetContract<UIElement> contract =
                (WidgetContract<UIElement>) WidgetContracts.all().get(type);
        List<String> silent = new ArrayList<>();
        for (State<UIElement, ?> raw : contract.states()) {
            State<UIElement, Object> slot = (State<UIElement, Object>) raw;
            if (DERIVED.contains(type.getSimpleName() + "." + slot.key())) continue;
            Object distinct = distinctFrom(slot.read(sent), slot.fallback());
            if (distinct == null) continue;   // a shape this walk cannot mint a value for

            slot.set(sent, distinct);
            Object onServer = slot.read(sent);
            if (Objects.equals(onServer, slot.read(arrived))) {
                // Already equal before anything was flushed: either the widget refused the value or the
                // getter never saw it. Either way there is nothing for the wire to prove.
                continue;
            }
            settle();
            if (!Objects.equals(onServer, slot.read(arrived))) {
                silent.add(type.getSimpleName() + "." + slot.key()
                        + ": server has " + onServer + ", client has " + slot.read(arrived));
            }
        }
        return silent;
    }

    /**
     * A value of {@code current}'s own type that differs from it — or null for a shape this cannot
     * mint one for.
     *
     * <p>Deliberately small. A walk that invented values for every shape would be a second, worse
     * codec; what it needs is one value per slot that the encoder <b>had to carry</b>, and the scalars
     * below are what nearly every slot is. Anything else falls through to its own hand-written test.</p>
     */
    @Nullable
    private static Object distinctFrom(@Nullable Object current, @Nullable Object fallback) {
        Object seed = current != null ? current : fallback;
        if (seed instanceof Boolean value) return !value;
        if (seed instanceof String value) return value + " (changed)";
        if (seed instanceof Integer value) return value + 7;
        if (seed instanceof Long value) return value + 7L;
        if (seed instanceof Float value) return value + 0.25f;
        if (seed instanceof Double value) return value + 0.25d;
        if (seed instanceof Enum<?> value) {
            Object[] constants = value.getDeclaringClass().getEnumConstants();
            for (Object constant : constants) {
                if (!constant.equals(value)) return constant;
            }
        }
        return null;
    }

    /**
     * Slots whose getter deliberately does not observe a set — <b>each one a decision, with a
     * reason</b>, in the shape {@code WidgetContracts.localOnly} takes.
     *
     * <p>The set exists so that "this slot is derived" has to be written down rather than discovered as
     * a walk that quietly passes. An entry here is a claim somebody made; a slot missing from here that
     * does not travel is a defect.</p>
     */
    private static final Set<String> DERIVED = Set.of(
            // TextField.value is read-only on purpose: it is derived from the text, and applying it
            // would apply the same thing twice. Its own contract says so.
            "TextField.value");

    // ── The set itself ──────────────────────────────────────────────────────────────────────────

    /**
     * <b>Every contracted widget is either buildable or deliberately not.</b>
     *
     * <p>The counter-control for the walk above: without it, a widget whose kind stops being buildable
     * silently drops out of the coverage set and every assertion still passes. A contracted class with
     * no factory is a real state — the config-control kit is registered for its names alone — so this
     * names them rather than forbidding them.</p>
     */
    @Test
    public void everyContractedWidgetIsEitherBuildableOrDeliberatelyNot() {
        Set<String> unbuildable = new LinkedHashSet<>();
        for (Class<?> type : WidgetContracts.all().keySet()) {
            Name name = nameOf(type);
            if (name != null && UIElementRegistry.isBuildable(name)) continue;
            unbuildable.add(type.getSimpleName());
        }

        // EMPTY, and that is the finding rather than the starting assumption. `Widgets` carried a
        // comment saying the config controls had no factory "because each takes a ConfigDescriptor and
        // there is no sensible no-argument form of one" -- which stopped being true when they each
        // gained one over a NEUTRAL descriptor, and the comment stayed. An entry here is a claim that a
        // contracted widget cannot be built; today nothing makes it.
        Set<String> expected = Set.of();
        Set<String> unexpected = new LinkedHashSet<>(unbuildable);
        unexpected.removeAll(expected);
        assertTrue("a contracted widget cannot be built, and nothing says why:\n"
                + String.join("\n", unexpected), unexpected.isEmpty());

        Set<String> gone = new LinkedHashSet<>(expected);
        gone.removeAll(unbuildable);
        assertTrue("these are listed as unbuildable and are not — delete the entry:\n"
                + String.join("\n", gone), gone.isEmpty());
    }

    /**
     * <b>Every contract carries something</b> - state, an event, or described children.
     *
     * <p>A contract with none of the three is a declaration with nothing behind it: it makes the widget
     * look networked to every reader of the census and travels nothing.</p>
     *
     * <p>Described children are the third and are the easy one to leave out of a check like this.
     * {@code Menu}, {@code Popover} and {@code Dialog} declare exactly that and nothing else,
     * deliberately - a menu's items are its content, each a {@code MenuItem} with a contract of its
     * own, and a container that described its children as STATE would describe them twice.</p>
     */
    @Test
    public void noContractIsEmpty() {
        List<String> empty = new ArrayList<>();
        for (Map.Entry<Class<?>, WidgetContract<?>> entry : WidgetContracts.all().entrySet()) {
            WidgetContract<?> contract = entry.getValue();
            if (contract.carriesState() || contract.reportsAnything()
                    || contract.acceptsDescribedChildren()) {
                continue;
            }
            empty.add(entry.getKey().getSimpleName());
        }
        assertTrue("a contract that neither carries state nor reports anything:\n"
                + String.join("\n", empty), empty.isEmpty());
    }

    /**
     * <b>A contract that accepts no described children describes none</b> — the flag, made a claim.
     *
     * <p>{@code NodeContract.acceptsDescribedChildren()} says "whether a description may carry children
     * for this kind at all", and until M8 <b>nothing read it</b>: the mirror wrote
     * {@code describedChildren()} whatever the contract said. That is the write-only state slot in its
     * other direction, and it cost the whole config kit — every control's parts are built from its
     * descriptor, so a decoded one had the ones its constructor made AND the ones it was told to adopt.
     * Two labels, two fields, 2n-1 elements per control, and it renders and reports the whole time.</p>
     *
     * <p>Held as an assertion rather than enforced in the encoder deliberately. Making the mirror obey
     * the flag would also silently DROP a caller's appended child on any widget whose contract forgot
     * to declare it — which is the same class of silent loss one level over. This way each widget states
     * its own truth and the two statements have to agree.</p>
     */
    @Test
    public void aContractThatTakesNoChildrenDescribesNone() {
        List<String> disagree = new ArrayList<>();
        for (Map.Entry<Class<?>, UIElement> entry : served.entrySet()) {
            WidgetContract<?> contract = WidgetContracts.all().get(entry.getKey());
            if (contract.acceptsDescribedChildren()) continue;
            int described = entry.getValue().describedChildren().size();
            if (described > 0) {
                disagree.add(entry.getKey().getSimpleName() + " takes no described children and "
                        + "describes " + described);
            }
        }
        assertTrue("a widget describes children its contract says it cannot take:" + NL
                + String.join(NL, disagree), disagree.isEmpty());
    }

    /**
     * <b>A widget is contracted or local-only, never both and never neither.</b>
     *
     * <p>{@code WidgetCensus} is the statement that every widget has been considered; a class in both
     * maps means two answers to one question, and the one that wins is registration order.</p>
     */
    @Test
    public void noWidgetIsBothContractedAndLocalOnly() {
        Set<String> both = new LinkedHashSet<>();
        for (Class<?> type : WidgetContracts.all().keySet()) {
            if (WidgetContracts.isLocalOnly(type)) both.add(type.getSimpleName());
        }
        assertTrue("contracted AND marked local-only:\n" + String.join("\n", both), both.isEmpty());
        assertFalse("the census never ran", WidgetContracts.allLocalOnly().isEmpty());
    }
}
