# runtime/mc/modern — one source tree, a node per Minecraft version

Every 1.20.x-and-later loader is built from this tree with [Stonecutter](https://stonecutter.kikugie.dev/),
a comment-directive preprocessor. It is **branched**:

```
runtime/mc/modern/
  stonecutter.gradle.kts            the controller: which node is ACTIVE, and nothing else
  common/  forge/  neoforge/  fabric/     a BRANCH each: the shared src/ and one build script
    <branch>/versions/<version>/          a NODE: gradle.properties (its pins), and its build/ and runs/
  build-logic/                      cg-modern-common, cg-modern-loader, cg-single-jar, ModernTree
```

A node is the Gradle project `:runtime:mc:modern:<branch>:<version>`. Its sources are the branch's,
passed through the directives for its version; its Minecraft, loader and mappings come from its own
`versions/<version>/gradle.properties`. So on a node **`project.name` is the version** — ask
`cgbuildlogic.ModernTree` for the loader (`modernLoader`) and the matching common (`commonNode`).

```bash
./gradlew checkAllTargets                                # every node, both source sets
./gradlew :runtime:mc:modern:forge:1.20.1:runClient
./gradlew :runtime:mc:modern:neoforge:1.20.4:serverSmoke
```

## Adding a Minecraft version

1. `settings.gradle.kts` — the version on the loader's branch, **and on `common`** if it is not there.
2. `<loader>/versions/<version>/gradle.properties` — copy a sibling's keys, change the pins.
3. `common/versions/<version>/gradle.properties` — `neoform.version` from 1.20.2, `forge.version` below.
4. `//? if` directives in the branch `src/` wherever that version's API differs — `checkAllTargets`
   finds every one.

The thin jars, the merge and its relocation counts, and `checkAllTargets` read the tree, so none of
them needs an edit. What still does, per version: the descriptor `Variant` in `cg-descriptors`, with
the neighbouring range narrowed; and, for a **second** node of one loader, a version-keyed thin-jar
relocation root (`cgThinRoot` in `cg-modern-loader`) — two thin jars of one loader otherwise carry one
relocated name, and the merge keeps whichever arrived first.

## What will bite

1. **A node's group is its branch's** (`useNodeCoordinates`). Every node of one version shares a
   project name, so a tree-wide group makes `forge:1.20.1` and `common:1.20.1` one coordinate, and the
   loader's dependency on common resolves to itself: `compileJava` depending on `compileJava`.
2. **Switching the active node rewrites the branches' `src/` in place.** Switch back to the controller's
   version before committing, or the diff carries directive noise rather than the change.
3. **Directives live in the branch `src/`**, never under a node's `build/generated/stonecutter/`, which
   is output. The active node compiles `src/` directly; every other node compiles the generated copy.
4. **Nothing may read `src/` relative to the project directory.** That is `versions/<version>/src` on
   a node, which does not exist, so a check reading it passes having read nothing. Read a task's
   `source` or a source set's directories instead, as the import guard and the descriptor check do.
