package com.crystalgui.harness.scene;

import com.crystalgraphics.harness.ui.UiDocumentScene;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.core.property.Property;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.StyleEngine;

/**
 * Interactive harness scene exercising {@code UIText} — CrystalGUI's first concrete widget —
 * against the real current CrystalGraphics text API ({@code CgTextLayout.Request}/
 * {@code CgShapedParagraph}), not a mock. Four side-by-side cases:
 *
 * <ol>
 *   <li>Plain single-line text, default font-family/font-size, auto-sized (proves
 *       {@code measureFunc()} → Taffy intrinsic sizing works with zero explicit width/height).</li>
 *   <li>Wrapped multi-line text in a fixed-width box (proves {@code maxWidth} correctly reaches
 *       {@code CgShapedParagraph.layout} through both the measure pass and the paint pass).</li>
 *   <li>A {@code font-family} fallback case — Latin, Japanese, Korean and Arabic under IBMPlexSans:
 *       everything it cannot draw comes from the installed fonts through {@code CgSystemFonts}.</li>
 *   <li>A live {@code bindTextTo} case — press SPACE to cycle the bound {@code Property<String>}
 *       through several strings of different lengths, visually confirming re-measure/re-wrap
 *       happens automatically on every bound change.</li>
 * </ol>
 *
 * <p>{@code CgShapedParagraph}'s internal (maxWidth, maxHeight) memoization (see its own javadoc)
 * is exercised implicitly every frame here — every case's {@code paintOverlay} calls
 * {@code .layout(...)} with the same box size Taffy just measured with, so a steady frame (no
 * resize, no bound-text change) hits the memoized path on every single repaint. Proving that
 * *quantitatively* (call-count instrumentation) would require instrumenting
 * {@code CgShapedParagraph} itself, which lives in CrystalGraphics, not this harness — out of scope
 * here; this scene verifies the integration is functionally correct and visually stable across
 * repeated frames instead.</p>
 *
 * <p>Ctrl+= and Ctrl+- change the scale, which the top-left corner shows.</p>
 */
public class CgUiTextScene extends UiDocumentScene {

    private final Property<String> liveText = new Property<>("Short.");
    private int liveIndex = 0;

    private static final String[] LIVE_STRINGS = {
            "Short.",
            "A medium length sentence that should wrap across a couple of lines.",
            "One.",
            "A much longer sentence than the others, deliberately long enough to force several " +
                    "lines of wrapping inside the same fixed-width box, proving reflow keeps working " +
                    "as the bound text keeps changing length back and forth.",
    };

    private static final String STYLE_SHEET = """
            .card {
                background-color: #FF00FFFF;
                border-width: 1px;
                border-color: #555555;
                padding: 8px;
            }
            .label {
                color: #FFFFFF;
                font-size: 12;
            }
            .wrap-box {
                width: 140px;
            }
            .fallback-text {
                font-family: "crystalgui:ui/fonts/IBMPlexSans-Regular.ttf";
                font-size: 14;
            }
            """;

    @Override
    protected void style(StyleEngine styles) {
        styles.addStylesheet(StyleSheet.parse(STYLE_SHEET));
    }

    @Override
    protected UIElement build(HarnessContext ctx) {
        return createTextDemo();
    }

    @Override
    protected void paintOver(CgUiPaintContext paint) {
        paint.text().at(0, 0).text(document().boxes().uiScale() + "x").font(paint.getFont().atSize(32)).submit();
    }

    @Override
    protected boolean key(CgSystemInput.Keyboard.Event event) {
        if (!event.pressed() || event.repeat() || event.key() != CgKeyCodes.KEY_SPACE) return false;
        liveIndex = (liveIndex + 1) % LIVE_STRINGS.length;
        liveText.set(LIVE_STRINGS[liveIndex]);
        return true;
    }

    private UIElement createTextDemo() {
        UIElement root = new UIElement()
                .layout(l -> l
                        .paddingAll(16)
                        .flexDirection(FlexDirection.ROW)
                        .gapAll(16)
                        .alignItems(AlignItems.FLEX_START)
                        .flexWrap(FlexWrap.WRAP)
                ).setFocusPolicy(FocusPolicy.NONE);

        // Case 1: plain single-line, auto-sized.
        UIElement plainCard = new UIElement();
        plainCard.addClass("card");
        UIText plainText = new UIText("Plain auto-sized text.");
        plainText.addClass("label");
        plainCard.append(plainText);
        root.append(plainCard);

        // Case 2: wrapped multi-line in a fixed-width box.
        UIElement wrapCard = new UIElement();
        wrapCard.addClass("card");
        wrapCard.addClass("wrap-box");
        UIText wrapText = new UIText(
                "This is a longer sentence that must wrap across multiple lines inside a fixed-width box.");
        wrapText.addClass("label");
        wrapCard.append(wrapText);
        root.append(wrapCard);

        // Case 3: font-family fallback — Latin from IBMPlexSans; Japanese, Korean and Arabic, which it
        // does not cover, from the installed fonts.
        UIElement fallbackCard = new UIElement();
        fallbackCard.addClass("card");
        fallbackCard.addClass("wrap-box");
        UIText fallbackText = new UIText("Hello こんにちは 안녕 مرحبا fallback");
        fallbackText.addClass("fallback-text");
        fallbackCard.append(fallbackText);
        root.append(fallbackCard);

        // Case 4: live bindTextTo — press SPACE to cycle liveText through LIVE_STRINGS.
        UIElement liveCard = new UIElement();
        liveCard.addClass("card");
        liveCard.addClass("wrap-box");
        UIText liveTextElement = new UIText("");
        liveTextElement.addClass("label");
        liveTextElement.bindTextTo(liveText);
        liveCard.append(liveTextElement);
        root.append(liveCard);
        UIText rawText = new UIText("Testinggg");
        root.append(rawText);

        return root;
    }
}
