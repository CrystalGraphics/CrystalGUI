package com.crystalgui.app.uibuilder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.crystalgui.app.uibuilder.glyph.KindGlyphs;
import com.crystalgui.render.texture.CgUiSvg;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.GlyphRole;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;
import com.crystalgui.widget.config.control.BooleanControl;
import com.crystalgui.widget.control.Button;
import com.crystalgui.widget.layout.TabView;
import com.crystalgui.widget.text.UIText;

import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import dev.vfyjxf.taffy.style.TaffyPosition;

/** The glyph a Hierarchy row draws: declared, borrowed, inherited, from layout, or the addon diamond. */
public class KindGlyphsTest extends UiDocumentTestBase {

    private static final String ICONS = "crystalgui:nodes/ui/";

    @Before
    public void registry() {
        UIElementRegistry.bootstrap();
    }

    private UIElement mount(UIElement node) {
        document.append(node);
        document.update(W, H);
        return node;
    }

    private static UIElement withChildren(UIElement node) {
        node.append(new UIElement(), new UIElement());
        return node;
    }

    @Test
    public void aPlainElementWithChildrenIsAColumnUntilASheetSaysRow() {
        UIElement group = mount(withChildren(new UIElement()));
        assertEquals("this engine's flex-direction defaults to column", ICONS + "column", KindGlyphs.of(group).icon());
        assertEquals("Column layout", KindGlyphs.of(group).words());

        group.addClass("toolbar");
        document.styleEngine().addStylesheet(StyleSheet.parse(".toolbar { flex-direction: row; }"));
        document.update(W, H);
        KindGlyphs.Glyph row = KindGlyphs.of(group);
        assertEquals(ICONS + "row", row.icon());
        assertEquals(GlyphRole.LAYOUT, row.role());
    }

    @Test
    public void wrapGridReversedAndOutOfFlowReadFromComputedStyle() {
        UIElement wrap = mount(withChildren(new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).flexWrap(FlexWrap.WRAP))));
        UIElement grid = mount(withChildren(new UIElement().layout(l -> l.display(TaffyDisplay.GRID))));
        UIElement reversed = mount(withChildren(new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW_REVERSE))));
        UIElement floating = mount(withChildren(new UIElement().layout(l -> l.positionType(TaffyPosition.ABSOLUTE))));
        assertEquals(ICONS + "wrap", KindGlyphs.of(wrap).icon());
        assertEquals(ICONS + "grid", KindGlyphs.of(grid).icon());
        assertEquals("Row layout, reversed", KindGlyphs.of(reversed).words());
        assertEquals("a container shows its children's flow and says it is out of flow",
                "Column layout, absolutely positioned", KindGlyphs.of(floating).words());
    }

    @Test
    public void aChildlessElementIsAFrameAndAnOutOfFlowLeafIsAbsolute() {
        assertEquals(ICONS + "frame", KindGlyphs.of(mount(new UIElement())).icon());
        UIElement leaf = mount(new UIElement().layout(l -> l.positionType(TaffyPosition.ABSOLUTE)));
        assertEquals(ICONS + "absolute", KindGlyphs.of(leaf).icon());
    }

    @Test
    public void aDeclaredGlyphWinsOverLayoutAndABorrowedOneTakesItsOwnersRole() {
        TabView tabs = new TabView();
        mount(tabs);
        assertEquals("a TabView takes children and still draws its own mark", ICONS + "tabview", KindGlyphs.of(tabs).icon());

        KindGlyphs.Glyph field = KindGlyphs.of(mount(new BooleanControl()));
        assertEquals(ICONS + "checkbox", field.icon());
        assertEquals(GlyphRole.CONTROL, field.role());
        assertEquals("the words still name the field", "Boolean Field", field.words());
        assertEquals(ICONS + "text", KindGlyphs.of(mount(new UIText("hi"))).icon());
    }

    /** An addon subclass that names itself, and declares nothing. */
    public static final class FancyButton extends Button {
        public static final Name NAME = Name.of("testmod", "fancybutton");

        public FancyButton() {
            super(NAME, "fancy");
        }
    }

    /** An addon widget built from scratch: its own kind, and no children. */
    public static final class MachineGauge extends UIElement {
        public static final Name NAME = Name.of("testmod", "machinegauge");

        public MachineGauge() {
            super(NAME);
            refusePublicChildren();
        }
    }

    @Test
    public void anAddonSubclassDrawsItsParentsGlyphUnderItsOwnName() {
        KindGlyphs.Glyph glyph = KindGlyphs.of(mount(new FancyButton()));
        assertEquals(ICONS + "button", glyph.icon());
        assertEquals(GlyphRole.CONTROL, glyph.role());
        assertEquals("Fancy Button", glyph.words());
    }

    @Test
    public void anAddonKindNothingDescribesIsTheAddonDiamondNamingItsMod() {
        KindGlyphs.Glyph glyph = KindGlyphs.of(mount(new MachineGauge()));
        assertEquals(ICONS + "component", glyph.icon());
        assertEquals(GlyphRole.ADDON, glyph.role());
        assertEquals("Machine Gauge · testmod", glyph.words());

        UIElement panel = mount(withChildren(new UIElement(Name.of("testmod", "panel"))));
        assertEquals("an addon container says what it is before how it lays out",
                "panel · Column layout", KindGlyphs.of(panel).words());
    }

    @Test
    public void everyGlyphAKindDeclaresAndEveryLayoutMarkHasItsFile() {
        List<String> missing = new ArrayList<>();
        for (Name kind : UIElementRegistry.names()) {
            KindGlyphs.Glyph declared = KindGlyphs.declared(kind);
            if (declared != null && CgUiSvg.ofIcon(declared.icon()) == null) missing.add(kind + " -> " + declared.icon());
        }
        for (String mark : List.of("column", "row", "wrap", "grid", "frame", "absolute", "component")) {
            if (CgUiSvg.ofIcon(ICONS + mark) == null) missing.add(ICONS + mark);
        }
        assertEquals("glyphs with no file: " + missing, List.of(), missing);
        assertNotNull(KindGlyphs.declared(Button.NAME));
    }
}
