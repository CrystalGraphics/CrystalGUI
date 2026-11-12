package com.crystalgui.core.async;

import java.util.Objects;

/**
 * Who contributed something: the process (a module, at startup) or one application or extension activation, whose
 * contributions leave together when it ends.
 *
 * <pre>{@code
 * kinds.add(Owner.PROCESS, kind);              // lives as long as the process
 *
 * Owner mine = Owner.of("vfx-lab");            // an application's
 * kinds.add(mine, kind);
 * kinds.removeOwner(mine);                     // all of them, in one new generation
 * }</pre>
 *
 * <p>Owners are compared by identity: two {@code Owner.of("x")} are two owners that happen to share a name.</p>
 */
public final class Owner {

    /** Contributed at startup and kept for the life of the process. */
    public static final Owner PROCESS = new Owner("process");

    private final String name;

    private Owner(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public static Owner of(String name) {
        return new Owner(name);
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}
