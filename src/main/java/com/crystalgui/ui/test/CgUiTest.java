package com.crystalgui.ui.test;

import com.crystalgui.core.render.CgUiRuntime;
import com.crystalgui.ui.UIContainer;
import com.crystalgui.ui.UIDocument;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.UiLabel;
import com.crystalgui.ui.elements.UiPanel;
import dev.vfyjxf.taffy.geometry.TaffySize;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.LengthPercentage;
import dev.vfyjxf.taffy.style.TaffyDimension;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import io.github.somehussar.crystalgraphics.api.font.CgFontFamily;

/**
 * Reusable test/demo UI that exercises the real CrystalGUI draw-list pipeline.
 *
 * <p>Builds a {@link UIContainer} with a panel, label bar, and three button rectangles.
 * All shared rendering services (shader, draw states, text renderer, default font)
 * are obtained from {@link CgUiRuntime#get()}, so callers need zero prerequisites.</p>
 *
 * <p>Usable from both the GL debug harness and Minecraft integration.</p>
 */
public final class CgUiTest {

    private CgUiTest() {
    }

    /**
     * Builds a text-bearing test UI using shared services from {@link CgUiRuntime}.
     *
     * <p>Requires {@link CgUiRuntime#initialize} to have been called beforehand.</p>
     *
     * @return a fully assembled UIContainer ready for {@code computeLayout()} + {@code render()}
     */
    public static UIContainer create() {
        CgUiRuntime runtime = CgUiRuntime.get();

        UIElement root = new UIElement();
        root.getLayoutStyle().display = TaffyDisplay.FLEX;
        root.getLayoutStyle().flexDirection = FlexDirection.COLUMN;
        root.getLayoutStyle().size = TaffySize.of(
                TaffyDimension.percent(1.0f),
                TaffyDimension.percent(1.0f));

        // Panel background
        UiPanel panel = new UiPanel(0x1E1E28EA);
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
        header.getLayoutStyle().display = TaffyDisplay.FLEX;
        header.getLayoutStyle().flexDirection = FlexDirection.COLUMN;
        header.getLayoutStyle().size = TaffySize.of(
                TaffyDimension.percent(1.0f),
                TaffyDimension.length(32));

        if (runtime.hasTextServices()) {
            header.addChild(label(runtime.getDefaultFontFamily(), "CrystalGUI Pause Test", 0xFFFFFFFF));
        }

        // Button 1: blue
        UiPanel btn1 = new UiPanel(0x265AA6FF);
        btn1.getLayoutStyle().display = TaffyDisplay.FLEX;
        btn1.getLayoutStyle().flexDirection = FlexDirection.COLUMN;
        btn1.getLayoutStyle().size = TaffySize.of(
                TaffyDimension.percent(1.0f),
                TaffyDimension.length(36));

        // Button 2: green
        UiPanel btn2 = new UiPanel(0x338C4DFF);
        btn2.getLayoutStyle().display = TaffyDisplay.FLEX;
        btn2.getLayoutStyle().flexDirection = FlexDirection.COLUMN;
        btn2.getLayoutStyle().size = TaffySize.of(
                TaffyDimension.percent(1.0f),
                TaffyDimension.length(36));

        // Button 3: red
        UiPanel btn3 = new UiPanel(0xA63333FF);
        btn3.getLayoutStyle().display = TaffyDisplay.FLEX;
        btn3.getLayoutStyle().flexDirection = FlexDirection.COLUMN;
        btn3.getLayoutStyle().size = TaffySize.of(
                TaffyDimension.percent(1.0f),
                TaffyDimension.length(36));

        if (runtime.hasTextServices()) {
            CgFontFamily font = runtime.getDefaultFontFamily();
            btn1.addChild(label(font, "Confirm", 0xFFFFFFFF));
            btn2.addChild(label(font, "Apply", 0xFFFFFFFF));
            btn3.addChild(label(font, "Cancel", 0xFFFFFFFF));
        }

        panel.addChild(header);
        panel.addChild(btn1);
        panel.addChild(btn2);
        panel.addChild(btn3);

        root.addChild(panel);

        return new UIContainer(UIDocument.of(root));
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
