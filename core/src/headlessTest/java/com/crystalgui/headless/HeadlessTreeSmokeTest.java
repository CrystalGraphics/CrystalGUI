package com.crystalgui.headless;

import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UINodeRegistry;
import com.crystalgui.style.sheet.DeclarationParser;
import com.crystalgui.ui.ElementRegistry;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.control.Checkbox;
import com.crystalgui.widget.control.Slider;
import com.crystalgui.widget.layout.SplitView;
import com.crystalgui.widget.control.Switch;
import com.crystalgui.widget.layout.Tab;
import com.crystalgui.widget.layout.TabView;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.text.UIText;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The whole point of this source set: build and mutate a real widget tree on a machine with no
 * OpenGL, no fonts, and <b>no CrystalGraphics jar at all</b>.
 *
 * <p>There are barely any assertions here on purpose. The test is that none of it throws
 * {@code NoClassDefFoundError} — every statement below is one that used to, or plausibly could,
 * reach a CrystalGraphics type. Assertions are there to stop the JIT or a future refactor from
 * eliding the calls entirely.</p>
 *
 * <p><b>No {@code UIDocument} is constructed anywhere.</b> That is the structural rule the whole
 * server design rests on: no window means no Taffy tree, no style engine, no layout pass, and so no
 * path into text measurement. It is enforced by absence rather than by a flag, because a flag is
 * only ever an assertion that something <em>else</em> is supposed to honour.</p>
 */
public class HeadlessTreeSmokeTest {

    @Test
    public void everyBuiltinWidgetConstructsWithoutGraphics() {
        UINodeRegistry.bootstrap();
        for (Name kind : UINodeRegistry.names()) {
            // A cascade-only kind is registered so a sheet can name it and has no factory at all --
            // nothing describes one over a wire, so there is nothing here to smoke-test.
            if (!UINodeRegistry.isBuildable(kind)) continue;
            UINode element = UINodeRegistry.create(kind);
            assertNotNull(kind + " failed to construct", element);
            assertEquals("the kind must survive construction", kind, element.name());
        }
    }

    /**
     * The one that used to be impossible. {@code UIText.recompute()} runs from the {@code text}
     * property's change listener, so {@code setText} on a detached tree went straight to
     * {@code FontFamilyCache} → {@code CgFont.load}.
     */
    @Test
    public void mutatingTextDoesNotReachTheFontStack() {
        UIText text = new UIText("initial");
        text.setText("changed");
        text.setText("changed again");
        assertEquals("changed again", text.getText());

        Button button = new Button("press me");
        button.setText("relabelled");
        assertEquals("relabelled", button.getText());
    }

    /** {@code TextField} measures from interaction rather than layout, so it needs its own guard. */
    @Test
    public void textFieldEditingDoesNotReachTheFontStack() {
        TextField field = new TextField();
        field.setText("hello");
        field.insert(" world");
        field.setMode(TextField.Mode.INTEGER).setRange(0, 100);
        field.setText("42");
        field.commit();

        assertEquals("42", field.getValue());
        // Caret geometry answers zero rather than throwing — there is nothing to measure with.
        assertEquals(0f, field.caretX(2), 0.0001f);
    }

    /**
     * Hit-testing was the other non-paint path into CrystalGraphics, via
     * {@code CgVertexTransformUtil.transformPosition}. A server dispatching any synthetic event
     * would have died here.
     */
    @Test
    public void coordinateTransformsWorkWithoutGraphics() {
        UINode element = new UINode();
        var local = element.toLocal(120f, 80f);
        assertNotNull(local);
        assertFalse(element.containsSurfacePoint(120f, 80f)); // no layout ⇒ zero-sized ⇒ no hit
    }

    /** Tree surgery, identity and queries — the operations a server session actually performs. */
    @Test
    public void treeMutationAndQueriesWorkWithoutGraphics() {
        UINode root = new UINode();
        root.setId("root");

        Checkbox checkbox = new Checkbox("agree");
        checkbox.addClass("row");
        root.append(checkbox);

        Switch toggle = new Switch();
        toggle.addClass("row");
        root.append(toggle);

        Slider slider = new Slider();
        root.append(slider);

        assertSame(checkbox, root.querySelector("#root .row"));
        assertEquals(2, root.querySelectorAll(".row").size());

        checkbox.setChecked(true);
        toggle.setChecked(true);
        slider.setValue(0.5f);
        assertTrue(checkbox.isChecked());

        root.remove(slider);
        assertEquals(2, root.children().size());
    }

    /** Composite widgets build their internal structure in their constructors — all of it headless. */
    @Test
    public void compositeWidgetsBuildTheirInternalsWithoutGraphics() {
        TabView tabs = new TabView();
        Tab first = tabs.addTab("one");
        Tab second = tabs.addTab("two");
        first.content().append(new UIText("pane content"));
        tabs.selectTab(second);
        assertSame(second, tabs.getSelectedTab());

        SplitView split = new SplitView();
        split.first(new UINode());
        split.setPercentage(30f);
        assertEquals(30f, split.getPercentage(), 0.001f);
    }

    /**
     * Parsing declarations must not drag in the user-agent sheet, whose static initialiser reads
     * {@code default.css} off the classpath — which is exactly the sort of I/O a server shouldn't do
     * to apply a value it was handed over a network.
     */
    @Test
    public void declarationsParseWithoutLoadingTheUserAgentSheet() {
        var declarations = DeclarationParser.parseBlock("width: 80px; color: #ff0000; padding-all: 4px");
        assertFalse(declarations.isEmpty());
        // padding-all expands to four longhands, so this is more than three declarations.
        assertTrue("shorthand expansion should still run", declarations.size() > 3);
    }
}
