package com.crystalgui.gradle;

/** A Maven version range as {@code variants.json} writes one: {@code [1.7.10]}, {@code [1.20,1.20.2)}. */
final class VersionRange {

    private final String low;
    private final boolean lowInclusive;
    private final String high;
    private final boolean highInclusive;

    private VersionRange(String low, boolean lowInclusive, String high, boolean highInclusive) {
        this.low = low;
        this.lowInclusive = lowInclusive;
        this.high = high;
        this.highInclusive = highInclusive;
    }

    static VersionRange parse(String range) {
        String body = range.substring(1, range.length() - 1);
        boolean lowInclusive = range.charAt(0) == '[';
        boolean highInclusive = range.charAt(range.length() - 1) == ']';
        int comma = body.indexOf(',');
        if (comma < 0) {
            return new VersionRange(body, true, body, true);
        }
        return new VersionRange(body.substring(0, comma).trim(), lowInclusive,
            body.substring(comma + 1).trim(), highInclusive);
    }

    boolean contains(String version) {
        if (!low.isEmpty()) {
            int c = Target.compare(version, low);
            if (c < 0 || c == 0 && !lowInclusive) {
                return false;
            }
        }
        if (!high.isEmpty()) {
            int c = Target.compare(version, high);
            return c < 0 || c == 0 && highInclusive;
        }
        return true;
    }
}
