package com.crystalgui.harness.scene;

import com.crystalgraphics.harness.ui.UiDocumentScene;
import com.crystalgui.style.StyleEngine;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.dom.UIElement;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import com.crystalgraphics.harness.config.HarnessContext;

/**
 * Minimal, focused harness scene for the Visual Layers feature (opacity isolation + {@code overflow: hidden}
 * mask/scissor compositing) — deliberately small and uncluttered so a screenshot is easy to read, unlike
 * {@link CgUiStylingScene}'s everything-at-once demo.
 *
 * <p>Two side-by-side comparisons, left = feature ON, right = feature OFF, so any difference is the
 * feature's actual effect, not guesswork:</p>
 * <ul>
 *   <li><b>Row 1 — mask</b>: a rounded, bordered green box with a red child deliberately positioned to
 *       overflow past its top-left corner. Left box has {@code overflow: hidden} (auto-detects
 *       {@code OverflowClip.MASK} since it has {@code border-radius}) — the red overflow should be
 *       clipped to the inner rounded region. Right box doesn't — the red square should visibly poke out
 *       past the border, unclipped, identically to how the left one looked before the mask was wired up.</li>
 *   <li><b>Row 2 — opacity</b>: two overlapping translucent squares (red, blue) inside a fractional-opacity
 *       parent (left) vs. a full-opacity parent (right). Left should show no seam at the overlap (the pair
 *       blends as one unit before fading); right should show a visible double-blend seam where they cross,
 *       since each child fades against the other independently.</li>
 *   <li><b>Row 3 — scissor</b>: same overflowing-red-child setup as row 1, but on a box with no
 *       {@code border-radius}, so {@code overflow: hidden} auto-detects {@code OverflowClip.SCISSOR}
 *       instead. Left box clips the red overflow to a hard axis-aligned rectangle (no rounding, unlike
 *       mask); right box doesn't clip at all. Exercises {@code UIElement#paintChildren}'s scissor path
 *       directly (not the mask/opacity FBO path row 1/2 exercise).</li>
 *   <li><b>Row 4 — mask override</b>: left box sets an explicit {@code mask:} (a dim, mostly-transparent
 *       white) — everything inside should visibly darken/fade, since children get multiplied by the
 *       mask's low alpha. Right box has no explicit {@code mask:} — the default mask re-renders the
 *       background fill (fully opaque green), so nothing fades, only shape-clipping applies. Exercises
 *       {@code UIElement#buildDefaultMask}'s {@code mask:} override path.</li>
 * </ul>
 *
 */
public class CgUiVisualLayersScene extends UiDocumentScene {


    private static final String STYLE_SHEET = """
            .row {
                gap: 40;
                margin-top: 10;
            }

            .mask-box:hover {
                background: sprite("crystalgui:textures/gui/gdp_styles.png", 154 165 16 16, 5 6 9 10);
                border-radius: 0px;
                border-width: 0px;
            }
            .mask-box {
                background: #33AA66;
                border-radius: 16px;
                border-width: 3px;
                border-color: #224488;
                padding: 4;
                transition: all 300ms;
            }
            .mask-on {
                overflow: hidden;
            }
            .mask-child {
                background: #FF4444;
                width: 40;
                height: 40;
                margin-left: -14;
                margin-top: -14;
            }
            .mask-child:hover {
                background: #FF444488;
            }

            .opacity-box {
                width: 60;
                height: 60;
            }
            .opacity-on {
                opacity: 0.5;
            }
            .opacity-child-a {
                background: rgba(255, 0, 0, 0.6);
                width: 36;
                height: 36;
            }
            .opacity-child-b {
                background: rgba(0, 128, 255, 0.6);
                width: 36;
                height: 36;
                margin-left: -18;
                margin-top: 18;
            }

            .scissor-box {
                background: #33AA66;
                width: 48;
                height: 48;
            }
            .scissor-on {
                overflow: hidden;
            }
            .scissor-child {
                background: #FF4444;
                width: 40;
                height: 40;
                margin-left: -14;
                margin-top: -14;
            }

            .mask-override-box {
                background: #33AA66;
                border-radius: 16px;
                border-width: 3px;
                border-color: #224488;
                padding: 4;
                overflow: hidden;
            }
            .mask-override-on {
                mask: rgba(255, 255, 255, 0.3);
            }

            """;

    @Override
    protected void style(StyleEngine styles) {
        styles.addStylesheet(StyleSheet.parse(STYLE_SHEET));
    }

    @Override
    protected UIElement build(HarnessContext ctx) {
        return createDemo();
    }

    private UIElement createDemo() {
        UIElement root = new UIElement()
                .layout(l -> l
                        .paddingAll(20)
                        .flexDirection(FlexDirection.COLUMN)
                        .alignItems(AlignItems.CENTER)
                );

        UIElement maskRow = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER));
        maskRow.addClass("row");
        root.append(maskRow);

        UIElement maskOn = new UIElement().layout(l -> l.width(48).height(48));
        maskOn.addClass("mask-box");
        maskOn.addClass("mask-on");
        UIElement maskOnChild = new UIElement();
        maskOnChild.addClass("mask-child");
        maskOn.append(maskOnChild);
        maskRow.append(maskOn);

        UIElement maskOff = new UIElement().layout(l -> l.width(48).height(48));
        maskOff.addClass("mask-box");
        UIElement maskOffChild = new UIElement();
        maskOffChild.addClass("mask-child");
        maskOff.append(maskOffChild);
        maskRow.append(maskOff);

        UIElement opacityRow = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER));
        opacityRow.addClass("row");
        root.append(opacityRow);

        UIElement opacityOn = new UIElement();
        opacityOn.addClass("opacity-box");
        opacityOn.addClass("opacity-on");
        UIElement opacityOnA = new UIElement();
        opacityOnA.addClass("opacity-child-a");
        UIElement opacityOnB = new UIElement();
        opacityOnB.addClass("opacity-child-b");
        opacityOn.append(opacityOnA);
        opacityOn.append(opacityOnB);
        opacityRow.append(opacityOn);

        UIElement opacityOff = new UIElement();
        opacityOff.addClass("opacity-box");
        UIElement opacityOffA = new UIElement();
        opacityOffA.addClass("opacity-child-a");
        UIElement opacityOffB = new UIElement();
        opacityOffB.addClass("opacity-child-b");
        opacityOff.append(opacityOffA);
        opacityOff.append(opacityOffB);
        opacityRow.append(opacityOff);

        UIElement scissorRow = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER));
        scissorRow.addClass("row");
        root.append(scissorRow);

        UIElement scissorOn = new UIElement().layout(l -> l.width(48).height(48));
        scissorOn.addClass("scissor-box");
        scissorOn.addClass("scissor-on");
        UIElement scissorOnChild = new UIElement();
        scissorOnChild.addClass("scissor-child");
        scissorOn.append(scissorOnChild);
        scissorRow.append(scissorOn);

        UIElement scissorOff = new UIElement().layout(l -> l.width(48).height(48));
        scissorOff.addClass("scissor-box");
        UIElement scissorOffChild = new UIElement();
        scissorOffChild.addClass("scissor-child");
        scissorOff.append(scissorOffChild);
        scissorRow.append(scissorOff);

        UIElement maskOverrideRow = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER));
        maskOverrideRow.addClass("row");
        root.append(maskOverrideRow);

        // Left: explicit `mask:` override (a dim, mostly-transparent white) — should visibly darken/fade
        // everything inside, unlike the default (mask = background reused, fully opaque, no fade at all).
        UIElement maskOverrideOn = new UIElement().layout(l -> l.width(48).height(48));
        maskOverrideOn.addClass("mask-override-box");
        maskOverrideOn.addClass("mask-override-on");
        UIElement maskOverrideOnChild = new UIElement();
        maskOverrideOnChild.addClass("mask-child");
        maskOverrideOn.append(maskOverrideOnChild);
        maskOverrideRow.append(maskOverrideOn);

        // Right: no explicit `mask:` — default mask re-renders the background fill (Item 5), fully
        // opaque green, so nothing inside gets faded; only shape clipping applies.
        UIElement maskOverrideOff = new UIElement().layout(l -> l.width(48).height(48));
        maskOverrideOff.addClass("mask-override-box");
        UIElement maskOverrideOffChild = new UIElement();
        maskOverrideOffChild.addClass("mask-child");
        maskOverrideOff.append(maskOverrideOffChild);
        maskOverrideRow.append(maskOverrideOff);

        return root;
    }

}
