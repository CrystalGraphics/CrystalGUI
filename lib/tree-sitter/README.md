# `lib/tree-sitter/` — vendored tree-sitter jars

Third-party jars checked in rather than resolved from a repository, because they are not on Maven
Central in a form this project can use.

One directory per vendored dependency, so a second one does not have to share a licence file or a
README with this.

| Jar | What | Licence |
|---|---|---|
| `tree-sitter-0.26.6.jar` | `tree-sitter-ng` — JNI bindings to the tree-sitter parsing library, with native builds for x86_64 Windows/Linux/macOS and aarch64 Linux/macOS bundled inside | MIT, see `tree-sitter-ng-LICENSE.txt` |
| `tree-sitter-java-0.23.5.jar` | The Java grammar, compiled, with the same native coverage | MIT |

## Why these are vendored and not a dependency

**The official binding cannot be used here.** `io.github.tree-sitter:jtreesitter` requires **JDK 23+** and
the Foreign Function & Memory API. CrystalGUI targets Java 8 bytecode, so that is not a version bump away
— it is impossible. These come from a **fork of `tree-sitter-ng`**, which is JNI-based and compiles to
Java 8.

They were previously read from a local checkout through a `treeSitterHome` property, which made the build
depend on one machine's directory layout. A checked-in jar is the smaller problem: 1.1 MB, and the
alternative is a build that only works for whoever produced it.

**`tree-sitter-java-0.23.5.jar` needs `tree-sitter-0.26.6.jar`** — the grammar subproject declares
`implementation project(":tree-sitter")` — so both ship together. Neither has any other dependency.

## What is deliberately *not* here

`highlights.scm`. The grammar jars carry the compiled parser and its natives and nothing else, so the
query files are vendored separately, in `syntax-treesitter/src/main/resources/assets/crystalgui/syntax/`,
each with its grammar's own licence. They are the grammar authors' files rather than hand-written
approximations: the capture names in them are what a theme styles, so an approximation would produce
highlighting subtly unlike every other editor's.

## Adding a grammar

A new language needs two things: its grammar jar here, and its `highlights.scm` in the resources
directory above. Building a grammar jar means adding a subproject to the `tree-sitter-ng` fork and
cross-compiling its native with Zig — `tree-sitter-glsl` is the outstanding one, tracked in
`CrystalGUI_P6_TODO.md` under 6.1.7 step 8.
