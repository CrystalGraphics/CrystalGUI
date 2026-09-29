package com.crystalgui.harness.scene;

import com.crystalgraphics.harness.SceneRegistry;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgraphics.platform.input.CgSystemInput;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
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
 * <p>Register in {@link SceneRegistry}
 * under scene id {@code "cgui-visual-layers"}.</p>
 */
public class CgUiVisualLayersScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard, CgSystemInput.Mouse {

    /** Logical-to-surface scale, as the harness\'s other new-engine scenes use. */
    private static final float SCALE = 2f;

    private UIDocument document;
    // TEMP diagnostic (Item 4a corner hit-test) — set only by the isolated corner-test branch in createDemo().
    private UIElement cornerTestBox;
    private UIElement cornerTestMarker;
    private boolean cornerHitTestRan = false;
    // TEMP diagnostic (Item 8 crossfade mask) — set only by the isolated crossfade-mask branch.
    private UIElement crossfadeMaskBox;
    // TEMP diagnostic (Round 4 padding-box hit-test gap) — set only by the isolated branch below.
    private UIElement paddingGapTestBox;
    private UIElement paddingGapTestChild;
    private boolean paddingGapSweepRan = false;

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

            .corner-test-box {
                background: #33AA66;
                border-radius: 30px;
                padding: 10;
                width: 80;
                height: 80;
                overflow: hidden;
            }
            .corner-test-marker {
                background: #FF4444;
                width: 60;
                height: 60;
            }

            /* Sprite backgrounds no longer auto-detect OverflowClip.MASK on their own (Round 6 Item 3)
             * — a plain sprite + overflow:hidden now resolves to the cheap SCISSOR clip. These three
             * classes explicitly set `mask:` (reusing their own background) to keep opting into the
             * mask-follows-sprite-alpha behavior they were built to demonstrate. */
            .sprite-mask-box {
                background: sprite("harness:textures/9slice_frame.png", "0 0 24 24", "4 4 4 4");
                mask: sprite("harness:textures/9slice_frame.png", "0 0 24 24", "4 4 4 4");
                width: 80;
                height: 80;
                overflow: hidden;
            }
            .sprite-mask-marker {
                background: #FFEE33;
                width: 80;
                height: 80;
            }

            /* No border-radius, no explicit mask: — the new default. Marker should show through
             * EVERYWHERE inside the padding box (a plain rectangular scissor clip), not just a ring,
             * since scissor never consults the sprite's alpha. Proves the Round 6 Item 3 correction. */
            .sprite-scissor-default-box {
                background: sprite("harness:textures/9slice_frame.png", "0 0 24 24", "4 4 4 4");
                width: 80;
                height: 80;
                overflow: hidden;
            }
            .sprite-scissor-default-marker {
                background: #FFEE33;
                width: 80;
                height: 80;
            }


            .crossfade-mask-box {
                background: #33AA66;
                mask: #33AA66;
                overflow: hidden;
                width: 80;
                height: 80;
                transition: background 600ms linear, mask 600ms linear;
            }
            .crossfade-mask-box:hover {
                background: sprite("harness:textures/9slice_frame.png", "0 0 24 24", "4 4 4 4");
                mask: sprite("harness:textures/9slice_frame.png", "0 0 24 24", "4 4 4 4");
            }
            .crossfade-mask-marker {
                background: #FFEE33;
                width: 80;
                height: 80;
            }

            .zorder-back {
                background: #3355CC;
                width: 60;
                height: 60;
                z-index: 1;
            }
            .zorder-front {
                background: #FF4444AA;
                width: 60;
                height: 60;
                margin-left: -30;
                margin-top: 30;
                z-index: 2;
            }

            .padding-gap-test-box {
                background: #33AA66;
                border-width: 3px;
                border-color: #224488;
                padding: 4;
                width: 40;
                height: 40;
                overflow: hidden;
            }
            .padding-gap-test-child {
                background: #FF4444;
                width: 20;
                height: 20;
                margin-left: -8;
                margin-top: -8;
            }
            """;

    @Override
    public void init(HarnessContext ctx) {
        UIElement root = createDemo();
        this.document = new UIDocument().markFrameThread();
        this.document.boxes().setUiScale(SCALE);
        UIElement sceneRoot = root;
        // THE ROOT FILLS THE DOCUMENT. On the old engine the scene's root WAS the window's
        // root and took the window's size; here the DOCUMENT is the root and this is an
        // ordinary child, which sizes to its content -- so without this the scene lays out
        // at nothing and draws nothing. DEFAULT origin, so a scene sheet still wins.
        StyleGroup.defaultPipeline(sceneRoot.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).heightPercent(100f));
        this.document.append(sceneRoot);
        this.document.styles().addStylesheet(StyleSheet.parse(STYLE_SHEET));

        // Captures a screenshot then exits so it can be inspected directly instead of relying
        // on a human to relay one back.
//        ctx.getTaskScheduler().schedule(0.5, "capture", () -> ctx.getArtifactService().requestCapture("snapshot"));
//        ctx.getTaskScheduler().schedule(1.0, "shutdown", () -> shutdownRequested = true);
    }

    private volatile boolean shutdownRequested = false;

    private UIElement createDemo() {
        UIElement root = new UIElement()
                .layout(l -> l
                        .paddingAll(20)
                        .flexDirection(FlexDirection.COLUMN)
                        .alignItems(AlignItems.CENTER)
                );

        // TEMP diagnostic (Round 4 padding-box hit-test gap) — square corners (no border-radius), so
        // the boundary is a clean rectangle, not entangled with Item 4a's corner-rounding SDF. Border
        // 3px, padding 4px: content-box inset = 7, padding-box (real mask/scissor reveal) inset = 3.
        // Child (20x20, margin -8/-8) spans logical x/y in [-1, 19] relative to the box's outer
        // origin, covering three zones: [-1,3) always-excluded (inside the border band itself, mask
        // alpha 0 there regardless of this fix), [3,7) the bug's dead zone (visually revealed by the
        // mask, but previously ungated for hover — this is what Round 4 fixes), [7,19] already worked
        // before this fix.
        // Verified via render()'s sweep at physical y=264: box outer origin was at physical x=320
        // (uiScale=4, root centered at logical (60,35)). Padding-box boundary = 320+3*4=332, old
        // content-box boundary = 320+7*4=348. Sampled points x=336/344 (inside [332,348), the gap this
        // fix targets) resolved to CHILD after the fix. Reproduced the pre-fix bug directly by
        // temporarily reverting isMouseOverContent's inset back to border+padding: the SAME points
        // (336,264)/(344,264) resolved to "box" (child unreachable) — confirming the fix's exact effect.
        // `overflow: hidden` on this zero-radius box now auto-detects OverflowClip.SCISSOR rather than
        // the MASK it was verified under originally — hit-test-equivalent either way, since a zero
        // corner radius makes isMouseOverContent's MASK branch degenerate to the exact same plain-AABB
        // check the SCISSOR branch always does (isInsideRoundedBox short-circuits on CornerRadii.isZero()).
//        if (true) {
//            UIElement box = new UIElement().layout(l -> l.width(40).height(40));
//            box.addClass("padding-gap-test-box");
//            UIElement child = new UIElement();
//            child.addClass("padding-gap-test-child");
//            box.append(child);
//            root.append(box);
//            this.paddingGapTestBox = box;
//            this.paddingGapTestChild = child;
//            return root;
//        }

        // TEMP diagnostic — isolate to just one mask box.
//        if (true) {
//            UIElement maskOn = new UIElement().layout(l -> l.width(48).height(48));
//            maskOn.addClass("mask-box");
//            maskOn.addClass("mask-on");
//            UIElement maskOnChild = new UIElement();
//            maskOnChild.addClass("mask-child");
//            maskOn.append(maskOnChild);
//            root.append(maskOn);
//            return root;
//        }

        // TEMP diagnostic — isolate to just the mask-override row (verifying the `mask:` override path).
//        if (true) {
//            UIElement row = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER));
//            row.addClass("row");
//            root.append(row);
//
//            UIElement on = new UIElement().layout(l -> l.width(48).height(48));
//            on.addClass("mask-override-box");
//            on.addClass("mask-override-on");
//            UIElement onChild = new UIElement();
//            onChild.addClass("mask-child");
//            on.append(onChild);
//            row.append(on);
//
//            UIElement off = new UIElement().layout(l -> l.width(48).height(48));
//            off.addClass("mask-override-box");
//            UIElement offChild = new UIElement();
//            offChild.addClass("mask-child");
//            off.append(offChild);
//            row.append(off);
//
//            return root;
//        }

        // TEMP diagnostic — isolate to just the heavily-rounded corner hit-test box (Item 4a).
        // border-radius:30 + padding:10 shrinks the content-box's own corner radius to 20 (30-10),
        // still positive — so there's a real "corner gap" between the content-box's rounded shape and
        // its plain AABB, where a 60x60 marker (exactly matching content-box size) sits. Sweeping
        // getHoveredElement() near the box's outer corner (see render()) confirmed: points in that gap
        // resolve to the parent box (recursion into the marker correctly blocked there), while points
        // further into the content area correctly resolve to the marker — i.e. isMouseOverContent is
        // corner-radius-aware, not a plain rectangle test.
//        if (true) {
//            UIElement box = new UIElement().layout(l -> l.width(80).height(80));
//            box.addClass("corner-test-box");
//            UIElement marker = new UIElement();
//            marker.addClass("corner-test-marker");
//            box.append(marker);
//            root.append(box);
//            this.cornerTestBox = box;
//            this.cornerTestMarker = marker;
//            return root;
//        }

        // TEMP diagnostic — isolate to just the 9-slice sprite mask box (Items 3+5). The sprite
        // (assets/harness/textures/9slice_frame.png) has an opaque blue 4px border and a fully
        // transparent center. As of Round 6 Item 3, a plain sprite background no longer
        // auto-detects OverflowClip.MASK on its own — `.sprite-mask-box` now sets `mask:` explicitly
        // (reusing its own background) to keep opting into mask-follows-sprite-alpha, so a
        // bright-yellow marker filling the whole box should still show through only in the
        // border-band ring (mask alpha=1 there) and be fully masked out in the center square
        // (mask alpha=0 there) — a shape no synthesized solid rounded rect could produce.
        // Verified: the yellow marker shows through only as a ring in the border band (texture
        // opaque there) and is fully masked out in the center (texture transparent there) — the
        // explicit mask correctly follows the sprite's own alpha shape, not a synthesized rounded rect.
//        if (true) {
//            UIElement box = new UIElement().layout(l -> l.width(80).height(80));
//            box.addClass("sprite-mask-box");
//            UIElement marker = new UIElement();
//            marker.addClass("sprite-mask-marker");
//            box.append(marker);
//            root.append(box);
//            return root;
//        }

        // TEMP diagnostic (Round 6 Item 3) — top box: `.sprite-mask-box` (explicit mask:, should show
        // a ring). Bottom box: `.sprite-scissor-default-box`, same sprite/marker setup but WITHOUT an
        // explicit `mask:` override and no border-radius — the new default. Should resolve to
        // OverflowClip.SCISSOR (plain rectangular clip), so the yellow marker shows through
        // EVERYWHERE inside the padding box, not just a ring — scissor never consults the sprite's
        // alpha, unlike the mask path exercised by the top box.
        // Verified: top box (mask:) rendered a yellow ring; bottom box (no mask:, new default)
        // rendered the marker filling the ENTIRE padding box — confirming SCISSOR (not MASK) is now
        // the default for plain sprite backgrounds.
//        if (true) {
//            UIElement maskBox = new UIElement().layout(l -> l.width(80).height(80));
//            maskBox.addClass("sprite-mask-box");
//            UIElement maskMarker = new UIElement();
//            maskMarker.addClass("sprite-mask-marker");
//            maskBox.append(maskMarker);
//            root.append(maskBox);
//
//            UIElement box = new UIElement().layout(l -> l.width(80).height(80).marginTop(20));
//            box.addClass("sprite-scissor-default-box");
//            UIElement marker = new UIElement();
//            marker.addClass("sprite-scissor-default-marker");
//            box.append(marker);
//            root.append(box);
//            return root;
//        }

        // TEMP diagnostic (Item 7) — DOM-first child (`.zorder-front`) has the HIGHER z-index (2),
        // DOM-second (`.zorder-back`) the lower (1). Before the fix (paintChildren iterated plain DOM
        // order), the DOM-second/lower-z child would paint last and incorrectly end up visually on
        // top; after the fix (paints in z-index order, lowest first), the DOM-first/higher-z child
        // correctly ends up on top, matching what getHoveredElement() already reported as "on top".
        // Verified: the red (higher z-index) box now correctly covers the blue (lower z-index) box
        // in their overlap region.
//        if (true) {
//            UIElement front = new UIElement().layout(l -> l.width(60).height(60));
//            front.addClass("zorder-front");
//            root.append(front);
//            UIElement back = new UIElement().layout(l -> l.width(60).height(60));
//            back.addClass("zorder-back");
//            root.append(back);
//            return root;
//        }

        // TEMP diagnostic (Item 8) — a `background` transition from solid color to the 9-slice
        // sprite, with `overflow: hidden`. As of Round 6 Item 3, a sprite background no longer
        // auto-detects MASK on its own, so `.crossfade-mask-box` now transitions `mask:` explicitly
        // alongside `background:` (both 600ms linear) to keep exercising mask-follows-crossfade —
        // see UIElement#resolveOverflowClip. Simulates a hover (triggering the transition)
        // at frame 2, then captures frames 3-30 (see render()) to sample the mask mid-transition —
        // before the fix, the mask would be stuck on a solid-white fallback for the whole 600ms
        // transition, only picking up the sprite's ring shape abruptly at the very end.
        // Verified: frame 5 (early) barely differs from the pre-hover solid color, frame 10 (mid)
        // already shows a clearly-forming ring shape, frame 17 (later) shows it progressing further
        // — a continuous blend, not a solid-white lock followed by an abrupt final-frame snap.
        // Re-verified after Round 6 Item 3 (mask: now transitions explicitly alongside background:):
        // frame 5 still near-solid, frame 10 shows a forming ring, frame 17 shows it progressing
        // further — same continuous-blend behavior as originally verified for Item 8.
//        if (true) {
//            UIElement box = new UIElement().layout(l -> l.width(80).height(80));
//            box.addClass("crossfade-mask-box");
//            UIElement marker = new UIElement();
//            marker.addClass("crossfade-mask-marker");
//            box.append(marker);
//            root.append(box);
//            this.crossfadeMaskBox = box;
//            return root;
//        }

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

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        int w = ctx.getScreenWidth();
        int h = ctx.getScreenHeight();
        // SURFACE pixels in, LOGICAL units to lay out in -- the scale lives on the box
        // tree's root transform, so this is the only place the two spaces meet.
        document.frame(frame.getDeltaTime(), w / SCALE, h / SCALE);

        CgUiPaintContext paintContext = CgUiPaintContext.getInstance();
        paintContext.beginFrame(w, h);
        document.paint(paintContext);
        paintContext.endFrame();
        // TEMP diagnostic — capture the first several frames individually to inspect startup pop-in.
        if (frame.getFrameNumber() <= 10) {
            ctx.getArtifactService().requestCapture("startupframe" + frame.getFrameNumber());
        }

        // TEMP diagnostic (Item 4a) — sweep a small grid around where the corner-test box's exact
        // outer top-left corner is expected (root padding 20 logical, uiScale 2, root origin at
        // physical (0,0)), and report what getHoveredElement() resolves to at each point. Before the
        // fix, points right at the geometric corner (outside the rounded shape, inside the plain
        // AABB) would resolve to the marker; after the fix, they should not.
        if (cornerTestBox != null && !cornerHitTestRan && frame.getFrameNumber() == 5) {
            cornerHitTestRan = true;
            for (int x = 0; x < ctx.getScreenWidth(); x += 10) {
                for (int y = 0; y < ctx.getScreenHeight(); y += 10) {
                    UIElement hovered = document.input().hoverTarget();
                    if (hovered == cornerTestMarker) {
                        System.out.println("TEMPDEBUG cornerHitTest MARKER at (" + x + "," + y + ")");
                    } else if (hovered == cornerTestBox) {
                        System.out.println("TEMPDEBUG cornerHitTest box at (" + x + "," + y + ")");
                    }
                }
            }
        }

        // TEMP diagnostic (Item 8) — box confirmed at physical (240,140)-(560,460), center (400,300).
        // Simulate a hover-in at frame 2 (triggers the `background` transition to the sprite), then
        // capture frames 3-40 (~600ms of a ~60fps run) to sample the mask mid-transition.
        if (crossfadeMaskBox != null && frame.getFrameNumber() == 2) {
            document.input().consumeMouseEvent(
                    new CgSystemInput.Mouse.Event(400, 300, 0, 0, -1, false, 0f, System.currentTimeMillis()));
        }
        if (crossfadeMaskBox != null && frame.getFrameNumber() >= 3 && frame.getFrameNumber() <= 40) {
            ctx.getArtifactService().requestCapture("crossfade" + frame.getFrameNumber());
        }

        // TEMP diagnostic (Round 4 padding-box hit-test gap) — sweep along the box's top edge (fixed
        // y, varying x) to find the exact x where hover switches from the box to the child. Root's
        // auto-sized outer box is centered on screen via UIDocument's leftPos/topPos (confirmed live:
        // at 800x600/uiScale=4 the box's outer-left edge lands at physical x=320) — border 3px +
        // padding 4px means padding-box (this fix's boundary) starts at x=332, old content-box
        // (previous, too-tight boundary) started at x=348. Verified: x=336/344 (inside the [332,348)
        // gap) resolve to the child after the fix; reproducing the pre-fix inset directly (temporarily
        // reverting isMouseOverContent's border-only inset back to border+padding) showed the SAME
        // points resolving to the box instead — confirming this is the fix's exact, isolated effect.
        if (paddingGapTestBox != null && !paddingGapSweepRan && frame.getFrameNumber() == 5) {
            paddingGapSweepRan = true;
            for (int x = 300; x <= 420; x += 4) {
                UIElement hovered = document.input().hoverTarget();
                String what = hovered == paddingGapTestChild ? "CHILD" : hovered == paddingGapTestBox ? "box" : "other";
                System.out.println("TEMPDEBUG paddingGap (" + x + ",264) -> " + what);
            }
        }
    }

    @Override
    public void dispose() {
        document = null;
    }

    @Override
    public boolean isRunning() {
        return !shutdownRequested;
    }

    @Override
    public boolean uses3DCamera() {
        return false;
    }

    @Override
    public boolean shouldShutdownOnComplete() {
        return false;
    }

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        return document.input().consumeKeyboardEvent(event);
    }

    @Override
    public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
        return document.input().consumeMouseEvent(event);
    }
}
