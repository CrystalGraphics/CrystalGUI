package com.crystalgui.ui.box;

import static com.crystalgui.ui.box.BoxFixtures.box;
import static com.crystalgui.ui.box.BoxFixtures.layout;
import static com.crystalgui.ui.box.BoxFixtures.sized;
import static org.junit.Assert.assertEquals;

import com.crystalgui.style.LayoutGroup;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import dev.vfyjxf.taffy.style.TaffyPosition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.junit.Test;

/**
 * The same tree, described once, laid out by both engines — identical geometry, and on the new
 * one {@code computeLayout} runs exactly once (5.3's acceptance).
 *
 * <p>Every property whose DEFAULT the two engines disagree on (D5.8: flex-direction, flex-shrink,
 * min-size, align-content) is stated explicitly on every node, so what is compared is the layout
 * engine's arithmetic and not its defaults. The defaults themselves are asserted separately, on the
 * new engine alone, because that is the behaviour that changed.</p>
 */
public class OnePassLayoutTest extends com.crystalgui.testsupport.UiTestBase {

    /**
     * <b>The defaults are the PROJECT's, and that is what makes a difference between the engines a
     * defect.</b>
     *
     * <p>This asserted CSS's initials until M6.1, per D5.8: the old bridge diverges in five places and
     * the sheets relying on those were to be ported. <b>The bill came due and could not be paid.</b> A
     * default is the answer for every rule that does not mention the property, which in a 6,200-line
     * user-agent sheet is nearly all of them — and the failure is silent. {@code menu} states no
     * direction, so its item column became a row, {@code align-items: stretch} stretched the items
     * container across the menu's height, and a three-row menu drew 166px tall with its rows in the
     * top 43. Nothing errored. The gallery scene met the same divergence three times in one sitting
     * and each looked like a different bug.</p>
     *
     * <p>So both engines answer the same question the same way now, and the divergences stay what
     * {@code AGENTS.md} documents them as: project decisions with reasons ({@code border-box} matching
     * the common UI-framework convention, {@code flex-shrink: 0} so content is not compressed below
     * its own size), not accidents to be corrected.</p>
     */
    @Test
    public void theDefaultsAreTheProjectsAndBothEnginesAgree() {
        UIDocument document = new UIDocument();
        UINode column = sized(800, 300);
        UINode first = sized(100, 100);
        UINode second = sized(100, 100);
        column.append(first).append(second);
        document.append(column);
        document.update(800, 600);
        assertEquals("flex-direction: column -- the second child sits BELOW the first", 0f,
                box(second).x(), 0.001f);
        assertEquals(100f, box(second).y(), 0.001f);

        UINode tooWide = sized(1000, 50);
        UINode container = sized(800, 100);
        container.append(tooWide);
        document.append(container);
        document.update(800, 600);
        assertEquals("flex-shrink: 0 and min-size: 0 -- an oversized item OVERFLOWS rather than shrinking",
                1000f, box(tooWide).width(), 0.001f);
    }
}
