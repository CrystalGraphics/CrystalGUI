package com.crystalgui.app.uibuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Test;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.ui.contract.State;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.WidgetContracts;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.composite.RadarChart;

/**
 * <b>L3.4 / L3.6 — the counted sections, and the order a widget's state is described in.</b>
 */
public class BuilderInspectorSectionsTest {

    private final List<Disposable> held = new ArrayList<>();

    @After
    public void releaseAll() {
        for (Disposable handle : held) handle.dispose();
        held.clear();
    }

    private Disposable activate() {
        Disposable handle = BuilderInspectorSections.register();
        held.add(handle);
        return handle;
    }

    /**
     * <b>Counted across two activations.</b>
     *
     * <p>Two editors over two documents each activate the extension. A second must not double the forms,
     * and the first one closing must not empty the inspector under the second — which is the failure the
     * shader graph hit and the reason {@code SectionSet} counts rather than sets a flag.</p>
     */
    @Test
    public void twoActivationsShareOneRegistration() {
        assertEquals("nothing held to begin with", 0, BuilderInspectorSections.holders());

        Disposable first = activate();
        assertEquals(1, BuilderInspectorSections.holders());

        Disposable second = activate();
        assertEquals("the second did not register a second set", 2,
                BuilderInspectorSections.holders());

        first.dispose();
        assertEquals("and the first closing left the second's alone", 1,
                BuilderInspectorSections.holders());

        second.dispose();
        assertEquals("the last one out clears them", 0, BuilderInspectorSections.holders());
    }

    /** Disposing twice is not disposing two holders. */
    @Test
    public void aHandleReleasedTwiceReleasesOnce() {
        Disposable first = activate();
        activate();

        first.dispose();
        first.dispose();

        assertEquals("the other holder still has its registration", 1,
                BuilderInspectorSections.holders());
    }

    /**
     * <b>A widget's state slots are described in DECLARATION order, primary first.</b>
     *
     * <p>Declaration order is the contract's own and several widgets depend on it, so an inspector that
     * sorted alphabetically would describe a widget in an order nothing else uses. {@code RadarChart}
     * declares six and names {@code values} primary.</p>
     */
    @Test
    public void aRadarChartsSixSlotsAppearInDeclarationOrderWithPrimaryFirst() {
        UIElementRegistry.bootstrap();
        WidgetContract<Object> contract = WidgetContracts.of(new RadarChart());

        List<String> keys = new ArrayList<>();
        for (State<Object, ?> state : BuilderInspectorSections.ordered(contract)) keys.add(state.key());

        assertEquals(6, keys.size());
        assertEquals(List.of("values", "labels", "colors", "details", "max", "gradient"), keys);
        assertTrue("the primary leads", keys.get(0).equals(contract.primary().key()));
    }
}
