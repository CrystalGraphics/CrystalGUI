# `lib/tree-sitter/` — vendored tree-sitter jars

Third-party jars checked in rather than resolved from a repository, because they are not on Maven
Central in a form this project can use.

One directory per vendored dependency, so a second one does not have to share a licence file or a
README with this.

| Jar | What | Licence |
|---|---|---|
| `tree-sitter-0.26.6.jar` | `tree-sitter-ng` — JNI bindings to the tree-sitter parsing library, with native builds for x86_64 Windows/Linux/macOS and aarch64 Linux/macOS bundled inside | MIT, see `tree-sitter-ng-LICENSE.txt` |
| `tree-sitter-java-0.23.5.jar` | The Java grammar, compiled, with the same native coverage | MIT |
| `tree-sitter-css-0.25.0.jar` | The CSS grammar | MIT |
| `tree-sitter-javascript-0.25.0.jar` | The JavaScript grammar | MIT |
| `tree-sitter-html-0.23.2.jar` | The HTML grammar. Its `injections.scm` is what makes `<style>` and `<script>` bodies highlight as CSS and JavaScript | MIT |
| `tree-sitter-glsl-0.2.0.jar` | The GLSL grammar, from `tree-sitter-grammars/tree-sitter-glsl` rather than the `tree-sitter` org | MIT |
| `tree-sitter-xml-0.7.0.jar` | The XML grammar, also from `tree-sitter-grammars`. Its repo ships **two** grammars (`xml` and `dtd`); only `xml` is built here | MIT |

**All five cover the same platforms**: x86_64 Windows/Linux/macOS and aarch64 Linux/macOS. Worth
checking rather than assuming when adding one — a jar with narrower coverage drops a platform
silently, and the omission only surfaces on hardware nobody building it has.

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
query files are vendored separately, in `language/src/main/resources/assets/crystalgui/syntax/`,
each with its grammar's own licence. They are the grammar authors' files rather than hand-written
approximations: the capture names in them are what a theme styles, so an approximation would produce
highlighting subtly unlike every other editor's.

## Adding a grammar

A new language needs two things: its grammar jar here, and its `highlights.scm` in the resources
directory above. **The recipe, proven on all four of the 2026-08-12 additions:**

1. **If the fork already has the subproject** (it ships ~31): add `include 'tree-sitter-<lang>'` to its
   `settings.gradle` and run `:tree-sitter-<lang>:jar`. The natives are usually already built.
2. **If it does not** — GLSL was the only one — generate it:
   ```
   ./gradlew gen --parser-name glsl --parser-version 0.2.0 \
       --parser-zip https://github.com/tree-sitter-grammars/tree-sitter-glsl/archive/refs/tags/v0.2.0.zip
   ./gradlew :tree-sitter-glsl:buildNative      # downloads Zig itself, ~3 min for five targets
   ./gradlew :tree-sitter-glsl:jar
   ```
   **Two things the generator gets wrong** and both fail at compile time, so neither is subtle: the
   emitted `build.gradle` carries a publishing block that wants `ossrhUsername` (delete it, leaving only
   the `downloadSource` url), and the emitted binding class says `implements TSLanguage` where this
   fork's `TSLanguage` is a *class* — copy `TreeSitterCss.java`'s shape instead.
3. **A multi-grammar repo needs its sources pointed at.** `tree-sitter-xml` ships `xml/` and `dtd/`
   side by side with no top-level `src/`, so the default glob finds no parser, links a native
   containing nothing, and fails as `undefined symbol: tree_sitter_xml`. `BuildNativeTask` exposes
   `additionalCFiles` and `additionalIncludeDirs` for exactly this — see `tree-sitter-xml/build.gradle`
   for the shape. Watch for a shared `common/` directory holding a `scanner.h` the parser includes.
4. **`buildNative` must run before `jar`.** `jar` does not depend on it, so building only the jar
   produces one with no natives inside and no error — check with
   `unzip -l <jar> | grep -E '\.so|\.dll|\.dylib'` and expect five.
4. **Get the query from the build, not by hand.** `:tree-sitter-<lang>:downloadSource` unpacks the
   grammar tarball with its `queries/` directory intact; those are the author's files, which is the
   whole point (see above). Copy `highlights.scm` — and `injections.scm` if the grammar has one.
