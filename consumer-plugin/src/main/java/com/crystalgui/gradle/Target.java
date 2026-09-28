package com.crystalgui.gradle;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.gradle.api.InvalidUserDataException;

/**
 * The Minecraft version and loader a mod builds for, as a build declares it:
 * {@code crystalgui { minecraft("1.20.1", "forge") }}.
 *
 * <p>{@code forge} covers every Forge, 1.7.10 included; the jar's own variant table names older Forge
 * {@code fml1710} and {@code fml1122}, which {@link #servedBy} maps back.</p>
 */
public final class Target {

    static final List<String> LOADERS = Arrays.asList("forge", "neoforge", "fabric");

    private final String minecraft;
    private final String loader;

    private Target(String minecraft, String loader) {
        this.minecraft = minecraft;
        this.loader = loader;
    }

    static Target of(String minecraft, String loader) {
        if (minecraft == null || !minecraft.matches("\\d+(\\.\\d+)+")) {
            throw new InvalidUserDataException("crystalgui: minecraft(\"" + minecraft + "\", ...) is not a release"
                + " version such as \"1.20.1\"");
        }
        if (!LOADERS.contains(loader)) {
            throw new InvalidUserDataException("crystalgui: loader \"" + loader + "\" is not one of " + LOADERS);
        }
        return new Target(minecraft, loader);
    }

    /** The inverse of {@link #encode()}. */
    static Target parse(String encoded) {
        int colon = encoded.indexOf(':');
        return of(encoded.substring(colon + 1), encoded.substring(0, colon));
    }

    /** {@code loader:version}, the form a checkout reads its target in. */
    String encode() {
        return loader + ":" + minecraft;
    }

    public String minecraft() {
        return minecraft;
    }

    public String loader() {
        return loader;
    }

    boolean below(String version) {
        return compare(minecraft, version) < 0;
    }

    /** Whether one row of a jar's {@code variants.json} serves this target. */
    boolean servedBy(String variantLoader, String range) {
        boolean sameLoader = variantLoader.equals(loader)
            || loader.equals("forge") && variantLoader.startsWith("fml");
        return sameLoader && VersionRange.parse(range).contains(minecraft);
    }

    /** Release versions by numeric component, so 1.21.10 sorts after 1.21.9. */
    static int compare(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int left = i < x.length ? Integer.parseInt(x[i]) : 0;
            int right = i < y.length ? Integer.parseInt(y[i]) : 0;
            if (left != right) {
                return Integer.compare(left, right);
            }
        }
        return 0;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Target && ((Target) other).encode().equals(encode());
    }

    @Override
    public int hashCode() {
        return Objects.hash(minecraft, loader);
    }

    @Override
    public String toString() {
        return loader + " " + minecraft;
    }
}
