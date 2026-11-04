package com.crystalgui.style.property.visual;

import com.crystalgui.style.property.visual.border.LengthPercent;

import javax.annotation.Nullable;

/**
 * CSS {@code clip-path}, the {@code inset()} shape: what of a box and everything it hosts is drawn, cut in from
 * each edge of its border box.
 *
 * <pre>{@code
 * .panel          { clip-path: inset(0 0 0 0 round 8px); transition: clip-path 250ms ease-out; }
 * .panel.__shut__ { clip-path: inset(0 0 50% 0 round 8px); }   // the bottom half cut away, corners kept
 * }</pre>
 *
 * <p>One to four values, in {@code margin}'s order: {@code inset(4px)}, {@code inset(0 10%)},
 * {@code inset(0 0 50%)}, {@code inset(1px 2px 3px 4px)}. Percentages are of the box's own height for top and
 * bottom and its width for the sides. {@code round <radius>} rounds the clip's four corners, so a rounded box cut
 * short keeps its corners at the cut; a percentage radius is of the clip's own width and height.</p>
 *
 * <ul>
 *   <li>Transitions component by component, but only between two {@code inset()}s whose units agree (a zero
 *       agrees with either); from {@code none} it snaps, so state both ends as insets.</li>
 *   <li>Clips painting only. Hit testing still sees the whole box, unlike the web's.</li>
 *   <li>One radius for all four corners; CSS's per-corner {@code round} lists are not read.</li>
 * </ul>
 *
 * @param top   null only on {@link #NONE}
 * @param round the corner radius, zero for square
 */
public record ClipPath(@Nullable LengthPercent top, @Nullable LengthPercent right,
                       @Nullable LengthPercent bottom, @Nullable LengthPercent left, LengthPercent round) {

    /** {@code none}: nothing clipped. */
    public static final ClipPath NONE = new ClipPath(null, null, null, null, LengthPercent.ZERO);

    public static ClipPath inset(LengthPercent top, LengthPercent right, LengthPercent bottom, LengthPercent left) {
        return inset(top, right, bottom, left, LengthPercent.ZERO);
    }

    public static ClipPath inset(LengthPercent top, LengthPercent right, LengthPercent bottom, LengthPercent left,
                                 LengthPercent round) {
        return new ClipPath(top, right, bottom, left, round);
    }

    public boolean isNone() {
        return top == null;
    }

    /** {@code none} or {@code inset(<length-percentage>{1,4} [round <length-percentage>])}, or null otherwise. */
    @Nullable
    public static ClipPath parse(String raw) {
        String text = raw.trim().toLowerCase();
        if (text.equals("none")) return NONE;
        if (!text.startsWith("inset(") || !text.endsWith(")")) return null;
        String body = text.substring("inset(".length(), text.length() - 1).trim();
        LengthPercent round = LengthPercent.ZERO;
        int at = body.indexOf("round");
        if (at >= 0) {
            round = LengthPercent.parse(body.substring(at + "round".length()));
            if (round == null) return null;
            body = body.substring(0, at).trim();
        }
        String[] parts = body.split("\\s+");
        if (body.isEmpty() || parts.length > 4) return null;
        LengthPercent[] values = new LengthPercent[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = LengthPercent.parse(parts[i]);
            if (values[i] == null) return null;
        }
        LengthPercent top = values[0];
        LengthPercent right = parts.length > 1 ? values[1] : top;
        LengthPercent bottom = parts.length > 2 ? values[2] : top;
        LengthPercent left = parts.length > 3 ? values[3] : right;
        return inset(top, right, bottom, left, round);
    }

    @Override
    public String toString() {
        if (isNone()) return "none";
        String corners = round.value == 0f ? "" : " round " + round;
        return "inset(" + top + " " + right + " " + bottom + " " + left + corners + ")";
    }
}
