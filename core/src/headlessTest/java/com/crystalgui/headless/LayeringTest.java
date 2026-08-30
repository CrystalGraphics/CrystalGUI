package com.crystalgui.headless;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * <b>Nothing references upward.</b> The layering M6 ports into, read out of the constant pool.
 *
 * <p>{@code plan_m6.md} §2.6: the widget layer is being re-homed by KIND and by LAYER while it is
 * copied, and a layering nothing enforces is a layering that lasts until the first hurry. The rule
 * is one line — <b>engine &lt; widget &lt; chrome &lt; desktop &lt; workbench &lt; applications</b>,
 * and inside {@code widget}, {@code control}/{@code text}/{@code scroll} below
 * {@code overlay}/{@code layout}/{@code dnd} below everything else — and this is what stops a
 * {@code Button} learning about a {@code WindowFrame} again.</p>
 *
 * <h3>Why it is worth a test rather than a convention</h3>
 *
 * <p>The old tree had no layering to break: {@code ui.elements} was one directory with a
 * {@code Button} and a {@code MarkupView} at its root, {@code desktop}, {@code workbench},
 * {@code editor} and {@code chrome} were flat at 24–38 files each, and a leaf widget importing the
 * workbench would have looked like every other import. The census found the consequences rather than
 * the cause — {@code .__content__} claimed by three unrelated widgets, a selector zeroing every
 * {@code ConfiguratorGroup} in the application — and both are what a layer boundary would have
 * refused.</p>
 *
 * <h3>It passes trivially today, and that is the point</h3>
 *
 * <p>Nothing is ported yet, so every layer below is empty and every assertion is vacuous. Written now
 * because the first ported widget is the one that would otherwise establish the wrong precedent, and
 * a test added after the fact is a test somebody has to make pass.</p>
 *
 * @see EngineBoundaryTest the OTHER direction: what the new engine may not name at all
 */
public class LayeringTest {

    /** A layer, and everything it may not name. Ordered bottom-up; each may name only what precedes it. */
    private static final List<String> LAYERS = List.of(
            "com/crystalgui/widget/",
            "com/crystalgui/chrome/",
            "com/crystalgui/desktop/",
            "com/crystalgui/workbench/");

    /**
     * The tiers WITHIN {@code widget}, bottom-up.
     *
     * <p>A control does not know what a dialog is; a dialog does not know what a list is. The split
     * is what makes "a {@code Button} is general-purpose" a checkable claim rather than an intention
     * — and the reason {@code ScrollerView} and {@code ListView} are not in the same package as
     * {@code Button} despite all three being widgets.</p>
     */
    private static final List<String> WIDGET_TIERS = List.of(
            "com/crystalgui/widget/control/",
            "com/crystalgui/widget/text/",
            "com/crystalgui/widget/scroll/",
            "com/crystalgui/widget/overlay/",
            "com/crystalgui/widget/layout/",
            "com/crystalgui/widget/dnd/",
            "com/crystalgui/widget/collection/",
            "com/crystalgui/widget/form/",
            "com/crystalgui/widget/canvas/",
            "com/crystalgui/widget/graph/",
            "com/crystalgui/widget/editor/");

    /** Which tiers may name which: an index into {@link #WIDGET_TIERS}, and everything at or below it. */
    private static final int WIDGET_BOTTOM_TIER = 2; // control, text, scroll
    private static final int WIDGET_MIDDLE_TIER = 5; // + overlay, layout, dnd

    @Test
    public void aLayerNamesNothingAboveIt() throws IOException {
        Path root = ClassReferences.mainClassesRoot(getClass());
        List<String> offences = new ArrayList<>();
        for (int i = 0; i < LAYERS.size(); i++) {
            List<String> above = LAYERS.subList(i + 1, LAYERS.size());
            if (above.isEmpty()) continue;
            offences.addAll(ClassReferences.offences(root, LAYERS.get(i), above));
        }
        assertTrue("a layer reached upward:\n" + String.join("\n", offences), offences.isEmpty());
    }

    @Test
    public void aWidgetTierNamesNothingAboveItsOwn() throws IOException {
        Path root = ClassReferences.mainClassesRoot(getClass());
        List<String> offences = new ArrayList<>();
        for (int i = 0; i < WIDGET_TIERS.size(); i++) {
            int highestAllowed = i <= WIDGET_BOTTOM_TIER ? WIDGET_BOTTOM_TIER
                    : i <= WIDGET_MIDDLE_TIER ? WIDGET_MIDDLE_TIER : WIDGET_TIERS.size() - 1;
            List<String> above = WIDGET_TIERS.subList(highestAllowed + 1, WIDGET_TIERS.size());
            if (above.isEmpty()) continue;
            offences.addAll(ClassReferences.offences(root, WIDGET_TIERS.get(i), above));
        }
        assertTrue("a widget tier reached above its own:\n" + String.join("\n", offences), offences.isEmpty());
    }

    /**
     * The layering is only meaningful while the tree it describes is the tree that exists.
     *
     * <p>Every directory named above must be one M6 actually creates, or the two assertions pass by
     * describing nothing — which is exactly how a governance test rots. This fails the moment a
     * package is renamed without the plan and this file following, and it is deliberately allowed to
     * pass while the layers are all still EMPTY: 6.0 has ported nothing.</p>
     */
    @Test
    public void everyLayerNamedHereIsOneTheTreeWillHave() throws IOException {
        Path root = ClassReferences.mainClassesRoot(getClass());
        List<String> populated = new ArrayList<>();
        List<String> all = new ArrayList<>(LAYERS);
        all.addAll(WIDGET_TIERS);
        for (String layer : all) {
            if (Files.isDirectory(root.resolve(layer))) populated.add(layer);
        }
        // Before the first port every one is absent, which is legal. Once ANY exists, the widget
        // tiers and the layers are being created, and a name in this list that is not on disk is a
        // rename this file did not hear about.
        if (populated.isEmpty()) return;
        List<String> missing = new ArrayList<>();
        for (String layer : all) {
            if (!Files.isDirectory(root.resolve(layer))) missing.add(layer);
        }
        assertTrue("the port has begun but these layers do not exist -- renamed without updating "
                + "LayeringTest and plan_m6.md §2.6?\n" + String.join("\n", missing), missing.isEmpty());
    }
}
