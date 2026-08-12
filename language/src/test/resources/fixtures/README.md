# `fixtures/` — documents for looking at colours

Not test data. Nothing loads these; they exist so a human can open one in the editor and see whether
the highlighting is right, and so that the file survives in version control rather than only in
somebody's scratch directory.

| File | Exercises |
|---|---|
| `Main.java` | Every construct the Java `highlights.scm` attaches a capture to — literal forms, sealed hierarchies, generics and wildcards, lambdas and method references, switch patterns, nested and anonymous classes, annotations, text blocks |
| `RunTest.java` | **For running, not reading.** An ordinary compilation unit with a `static void main`, whose nineteen sections each log what they did — so the console is a transcript of which language features executed. Open it and press **Shift+F10**; **Mod+F2** stops a runaway. Unlike `Main.java` this one is *tested* (`RunTestFixtureTest`), because a fixture nobody runs for a month is a fixture whose next reader concludes the engine is broken |

## Why they are here and not in `src/main/java`

`Main.java` was a compiled class in the module's **production** source set, so all 504 lines of it
shipped inside the jar. Nothing referenced it — it is a document, not code — and a demo class in a
distributed artifact is the kind of thing that is never noticed because it never fails.

It is not in `src/test/java` either, for the same reason inverted: it would compile there too, and
compiling a file whose only purpose is to be *read* invites somebody to "fix" a deliberate oddity in
it. As a resource it is inert.

## The working copies live in the harness

`gl-debug-harness/workspace/src/` is where these are actually opened, alongside the css, js, html,
glsl and xml fixtures. That directory is **gitignored** — it is a scratch workspace — which is why a
tracked copy has to exist somewhere, and why this directory is that somewhere. Copy one across to
look at it:

```bash
cp language/src/test/resources/fixtures/Main.java gl-debug-harness/workspace/src/
```

If you change one to expose a new capture, change the tracked copy too, or the next person starts
from the old one.
