package com.example.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Middle of the chain — uses {@link Formatter} with <b>no import at all</b>.
 *
 * <p>That is the point of this one. Same-package resolution does not go through an import statement, so
 * it exercises a different path in the name environment: ECJ asks for {@code com.example.util.Formatter}
 * directly rather than resolving an import first. A build where imports worked and same-package lookup
 * did not would look completely fine in {@code Main} and fail only here.</p>
 *
 * <p><b>Things worth trying.</b> Type {@code Formatter.} anywhere below and the completion popup should
 * offer {@code shout} and {@code quiet} — members of another file, with their real signatures. Ctrl+B on
 * {@code Formatter} should open it. Hover should quote its javadoc.</p>
 */
public class Greeter {

    private final String greeting;

    public Greeter(String greeting) {
        this.greeting = greeting == null ? "hello" : greeting;
    }

    /** The one everybody calls. Reaches {@code Formatter} without naming its package. */
    public String greet(String who) {
        return Formatter.shout(greeting + ", " + who);
    }

    /** A generic signature, so the type shown in {@code Main} is worth reading. */
    public List<String> greetAll(List<String> names) {
        List<String> out = new ArrayList<>();
        for (String name : names) {
            out.add(greet(name));
        }
        return out;
    }

    /** A static factory, so {@code Main} can reach this without a constructor. */
    public static Greeter casual() {
        return new Greeter("hey");
    }

    /** Deliberately quiet — {@code Formatter.quiet} is only reachable from here. */
    public String murmur(String who) {
        return Formatter.quiet(greeting + ", " + who);
    }
}
