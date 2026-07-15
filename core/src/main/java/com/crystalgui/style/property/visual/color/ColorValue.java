package com.crystalgui.style.property.visual.color;

import com.crystalgui.style.property.StyleValue;

public class ColorValue extends StyleValue<Integer> {

    public ColorValue(String rawValue) {
        super(rawValue);
    }

    @Override
    protected Integer doCompute(String rawValue) {
        return parseColor(rawValue);
    }

    public static Integer parseColor(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }

        value = value.trim().toLowerCase();

        try {
            if (value.startsWith("#")) {
                String hex = value.substring(1);
                switch (hex.length()) {
                    case 3: // #RGB
                        int r = Integer.parseInt(hex.substring(0, 1), 16) * 17; // F -> FF
                        int g = Integer.parseInt(hex.substring(1, 2), 16) * 17;
                        int b = Integer.parseInt(hex.substring(2, 3), 16) * 17;
                        return 0xFF000000 | (r << 16) | (g << 8) | b;
                    case 6: // #RRGGBB
                        return 0xFF000000 | Integer.parseInt(hex, 16);
                    case 8: // #AARRGGBB
                        return (int) (Long.parseLong(hex, 16) & 0xFFFFFFFFL);
                    default:
                        return null;
                }
            } else if (value.startsWith("rgb(") && value.endsWith(")")) {
                String[] parts = value.substring(4, value.length() - 1).split(",");
                if (parts.length != 3) return null;

                int r = parseColorComponent(parts[0].trim());
                int g = parseColorComponent(parts[1].trim());
                int b = parseColorComponent(parts[2].trim());

                if (r < 0 || g < 0 || b < 0) return null;

                return 0xFF000000 | (r << 16) | (g << 8) | b;
            } else if (value.startsWith("rgba(") && value.endsWith(")")) {
                String[] parts = value.substring(5, value.length() - 1).split(",");
                if (parts.length != 4) return null;

                int r = parseColorComponent(parts[0].trim());
                int g = parseColorComponent(parts[1].trim());
                int b = parseColorComponent(parts[2].trim());
                float alpha = Float.parseFloat(parts[3].trim());

                if (r < 0 || g < 0 || b < 0 || alpha < 0.0f || alpha > 1.0f) return null;

                int a = Math.round(alpha * 255);
                return (a << 24) | (r << 16) | (g << 8) | b;
            }
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    public static int parseColorComponent(String component) {
        try {
            int value = Integer.parseInt(component);
            return (value >= 0 && value <= 255) ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}