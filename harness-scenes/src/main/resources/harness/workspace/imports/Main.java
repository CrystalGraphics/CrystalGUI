package com.example;

import com.example.util.Greeter;

import java.util.List;

/**
 * Top of the chain — the file to open first.
 *
 * <h3>What this fixture is for</h3>
 *
 * <p>Until M15 every project file was compiled <b>in isolation</b>: the analyser was handed one source
 * string and a classpath of jars, so a reference to a sibling file had exactly one possible answer —
 * <em>cannot be resolved to a type</em>. This file exists to show that it now has a better one.</p>
 *
 * <p>It sits under {@code src/main/java}, which is what makes any of it work: a source root is what turns
 * a path into a package, and the index derives every qualified name from the path rather than by reading
 * each file's {@code package} line.</p>
 *
 * <h3>What should be true, in rough order of how much it would hurt to lose</h3>
 *
 * <ol>
 *   <li><b>No errors.</b> {@code Greeter} resolves through the import, and the intermediate packages
 *       {@code com} and {@code com.example.util} resolve even though {@code com} declares nothing itself.</li>
 *   <li><b>Ctrl+B on {@code Greeter}</b> opens the other file, in the workspace rather than a viewer.</li>
 *   <li><b>{@code greeter.} completes</b> with {@code greet}, {@code greetAll} and {@code murmur} — a
 *       member list from a file that is not this one.</li>
 *   <li><b>Hover shows real types.</b> {@code greetAll} should read as returning {@code List<String>},
 *       not as an unresolved stub.</li>
 *   <li><b>Edits are seen without saving.</b> Open {@code Greeter.java}, rename {@code greet}, and this
 *       file should go red on the next analysis — the index answers from the open buffer, not the file on
 *       disk. This is the property that would be quietly missing if resolution went through a source
 *       <em>path</em> rather than through the buffer.</li>
 *   <li><b>A transitive change lands too.</b> Break {@code Formatter.shout}, which this file never names,
 *       and the error appears in {@code Greeter} — two files away from where you typed.</li>
 * </ol>
 *
 * <p>And one that should <em>not</em> happen: nothing here should report {@code Main} as declared twice.
 * The unit under analysis is deliberately excluded from the project index, because it is already in the
 * compiler's own work list.</p>
 */
public class Main {

    public static void main(String[] args) {
        Greeter greeter = Greeter.casual();
        System.out.println(greeter.greet("world"));

        List<String> everyone = greeter.greetAll(List.of("ada", "alan", "grace"));
        for (String line : everyone) {
            System.out.println(line);
        }

        // A second instance, so the constructor is exercised as well as the factory.
        Greeter polite = new Greeter("good evening");
        System.out.println(polite.murmur("world"));
    }
}
