package com.crystalgui.ui.test;

import com.crystalgui.core.event.CgUiDebug;
import com.crystalgui.core.event.UiEventType;
import com.crystalgui.core.render.CgUiRuntime;
import com.crystalgui.ui.UIContainer;
import com.crystalgui.ui.UIDocument;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.UiButton;
import com.crystalgui.ui.elements.UiLabel;
import com.crystalgui.ui.elements.UiPanel;
import com.crystalgui.ui.elements.UiTextbox;
import dev.vfyjxf.taffy.geometry.TaffySize;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.LengthPercentage;
import dev.vfyjxf.taffy.style.TaffyDimension;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import io.github.somehussar.crystalgraphics.api.font.CgFontFamily;

/**
 * Reusable test/demo UI that exercises the real CrystalGUI interaction foundation.
 *
 * <p>Builds a {@link UIContainer} with a panel, label bar, and three real
 * {@link UiButton}s with click signals. All shared rendering services are
 * obtained from {@link CgUiRuntime#get()}, so callers need zero prerequisites.</p>
 *
 * <p>Usable from both the GL debug harness and Minecraft integration.</p>
 */
public final class CgUiTest {

    private CgUiTest() {
    }

    /**
     * Builds the test UI with real buttons and labels.
     *
     * <p>Requires {@link CgUiRuntime#initialize} to have been called beforehand.</p>
     *
     * @return a fully assembled UIContainer ready for {@code computeLayout()} + {@code render()}
     */
    public static UIContainer create() {
        CgUiRuntime runtime = CgUiRuntime.get();

        UIElement root = new UIElement();
        root.setId("root");
        root.getLayoutStyle().display = TaffyDisplay.FLEX;
        root.getLayoutStyle().flexDirection = FlexDirection.COLUMN;
        root.getLayoutStyle().size = TaffySize.of(
                TaffyDimension.percent(1.0f),
                TaffyDimension.percent(1.0f));

        // Panel background
        UiPanel panel = new UiPanel(0x1E1E28EA);
        panel.setId("panel");
        panel.getLayoutStyle().display = TaffyDisplay.FLEX;
        panel.getLayoutStyle().flexDirection = FlexDirection.COLUMN;
        panel.getLayoutStyle().size = TaffySize.of(
                TaffyDimension.length(400),
                TaffyDimension.length(260));
        panel.getLayoutStyle().gap = TaffySize.of(
                LengthPercentage.percent(0),
                LengthPercentage.length(8));

        // Header bar at top
        UiPanel header = new UiPanel(0x334059FF);
        header.setId("header");
        header.getLayoutStyle().display = TaffyDisplay.FLEX;
        header.getLayoutStyle().flexDirection = FlexDirection.COLUMN;
        header.getLayoutStyle().size = TaffySize.of(
                TaffyDimension.percent(1.0f),
                TaffyDimension.length(32));

        if (runtime.hasTextServices()) {
            header.addChild(label(runtime.getDefaultFontFamily(), "CrystalGUI Interaction Test", 0xFFFFFFFF));
        }

        // Real interactive buttons (replacing fake panel-buttons)
        UiButton btn1 = button(0x265AA6FF, "btn-confirm");
        UiButton btn2 = button(0x338C4DFF, "btn-apply");
        UiButton btn3 = button(0xA63333FF, "btn-cancel");

        if (runtime.hasTextServices()) {
            CgFontFamily font = runtime.getDefaultFontFamily();
            btn1.addChild(label(font, "Confirm", 0xFFFFFFFF));
            btn2.addChild(label(font, "Apply", 0xFFFFFFFF));
            btn3.addChild(label(font, "Cancel", 0xFFFFFFFF));
        }

        // Wire button signals to debug output
        btn1.clicked.connect(() -> CgUiDebug.log("test-ui", "Confirm clicked!"));
        btn2.clicked.connect(() -> CgUiDebug.log("test-ui", "Apply clicked!"));
        btn3.clicked.connect(() -> CgUiDebug.log("test-ui", "Cancel clicked!"));

        // Text input field
        CgFontFamily textboxFont = runtime.hasTextServices() ? runtime.getDefaultFontFamily() : null;
        UiTextbox textbox = new UiTextbox(0x2A2A3AFF, 0xFFFFFFFF, 0x4488FFFF, textboxFont);
        textbox.setId("textbox-input");
        textbox.getLayoutStyle().size = TaffySize.of(
                TaffyDimension.percent(1.0f),
                TaffyDimension.length(32));
        textbox.textChanged.connect(newText ->
                CgUiDebug.log("test-ui", "Textbox changed: " + newText));

        panel.addChild(header);
        panel.addChild(textbox);
        panel.addChild(btn1);
        panel.addChild(btn2);
        panel.addChild(btn3);

        root.addChild(panel);

        return new UIContainer(UIDocument.of(root));
    }

    private static UiButton button(int color, String id) {
        UiButton btn = new UiButton(color);
        btn.setId(id);
        btn.getLayoutStyle().display = TaffyDisplay.FLEX;
        btn.getLayoutStyle().flexDirection = FlexDirection.COLUMN;
        btn.getLayoutStyle().size = TaffySize.of(
                TaffyDimension.percent(1.0f),
                TaffyDimension.length(36));
        return btn;
    }

    private static UiLabel label(CgFontFamily fontFamily,
                                 String text,
                                 int color) {
        UiLabel label = new UiLabel(fontFamily, text, color);
        label.getLayoutStyle().size = TaffySize.of(
                TaffyDimension.percent(1.0f),
                TaffyDimension.length(32));
        return label;
    }
}
