# The era spike — one source tree, many Minecraft versions

This tree exists to answer one question: **can a multi-version preprocessor build this project under
ModDevGradle *and* Loom, in this repository's Gradle?** It can. Stonecutter 0.9.8 configures and
compiles five nodes across two Minecraft versions and both toolkits on Gradle 9.5.1, beside
RetroFuturaGradle and the CrystalGraphics composite.

It is **not shipping code**, and reading it as such is the one way to be misled by it.

## What it is

```
runtime/mc/spike/
  stonecutter.gradle.kts     the controller — says which node is ACTIVE
  common/  src/  versions/{1.20.1, 1.19.4}/gradle.properties
  forge/   src/  versions/{1.20.1}/gradle.properties
  fabric/  src/  versions/{1.20.1, 1.19.4}/gradle.properties
```

A **branch** (`common`, `forge`, `fabric`) holds the shared `src/` and one build script. A **node**
(`versions/1.20.1`) is a Gradle project that builds that shared source for one version. The build
scripts are per loader on purpose: ModDevGradle and Loom cannot sanely share one, and Stonecutter's
own multi-loader guide recommends the split.

```bash
./gradlew checkAllTargets          # every node, one task — the rule this tree exists to make possible
./gradlew :runtime:mc:spike:fabric:1.19.4:compileJava
./gradlew help -PcgNoSpike         # the whole tree gone, for one invocation
```

## What it deliberately leaves out

No thin jar, no relocation, no jvmdg downgrade, no reobfuscation or remapping, no `serverSmoke` — in
short, none of `cg-mc1201-loader`. That is not an oversight: a failure in any of them would have read
as a failure of the preprocessor, which is the one thing the spike was built to measure. `runClient`
is wired on both loader nodes and has never been driven, because the tree registers no CrystalGraphics
and a launch would fail on a backend known in advance to be absent.

So this tree **cannot produce an installable mod**, and nothing here is on `assemble`.

## Three things that will bite

1. **Directives live in the branch's `src/`, never in `versions/*/build/generated/`.** The generated
   copy is output; editing it is editing a build artefact.
2. **Switching the active node rewrites the shared `src/` in place.** Before committing, put the tree
   back on its VCS version (`stonecutter active "1.20.1"` in the controller is the current one), or
   the diff carries preprocessor noise — comments added and removed — rather than your change.
3. **`common` has to be versioned.** A loader node cannot depend on `:runtime:mc:modern:common`: that
   module is compiled against 1.20.1 alone, so a 1.19.4 target built on it would be testing nothing.
   Each loader node depends on the matching common node.

## The syntax, by the one example that is in here

`CgUiScreen` carries the whole `GuiGraphics` break — an import, an override's parameter, and a method
on that parameter, each of which only became visible once the previous was fixed:

```java
//? if >=1.20 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
/*import com.mojang.blaze3d.vertex.PoseStack;
*///?}
```

An **override** cannot be absorbed by reflection or by naming a member two ways, which is why it forces
a compiled variant per era rather than a runtime lookup.

## Where the findings are

`plan/crystalgui/platform-single-jar/experiments.md` (E-A1, days 1–3) has the measurements: what was
green, the three break sites, disk and configuration cost per node, and what the spike cannot answer.
**That repository is private and absent from an ordinary clone**, which is why the essentials are
repeated here rather than cited.

## The sources here are COPIES, and they will drift

`common/src`, `forge/src` and `fabric/src` were copied from `runtime/mc/modern/` and nothing keeps
them in step — edit the shipping host and this tree silently describes an older one. That is
tolerable for a spike answering one question, and it is *not* tolerable for a target anyone ships
from: J11's first row **moves** the shipping sources into a tree like this rather than copying them
again. Until then, treat everything under `*/src/` here as a snapshot, not a fork.

## Deleting it

Remove the `stonecutter { }` block from `settings.gradle.kts`, the `checkAllTargets` block from the
root `build.gradle.kts`, and this directory. Nothing else refers to it. Keep it while J11 is adding
real version targets, since this is the shape they start from.
