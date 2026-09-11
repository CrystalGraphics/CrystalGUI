# Third-party notices — `crystalgui_language.jar`

This file ships **inside** `crystalgui-language-<version>.jar` as `META-INF/NOTICE.md`. Most of this jar
by weight is somebody else's work, and EPL-2.0 and MPL-2.0 both require the notice to reach whoever
receives the binary — so it travels in the binary. `checkLanguageJar` asserts it is present.

The engine and the workbench are **not in this jar**. They ship in `crystalgui-<version>.jar`, which
carries [its own notice](../notices/crystalgui.md).

The repository-level index, with the full reasoning behind each entry, is
[`THIRD-PARTY.md`](../THIRD-PARTY.md).

| What | Where in this jar | Licence | Form |
|---|---|---|---|
| **Eclipse JDT** (`org.eclipse.jdt.core` + platform closure) | `assets/crystalgui/engines/{8,11,17}/` | **EPL-2.0** | Verbatim, as whole nested jars. The Java engine |
| **Rhino** | `assets/crystalgui/engines/{8,11,17}/` | **MPL-2.0** | Verbatim, as whole nested jars. The JavaScript engine |
| **CFR** | `assets/crystalgui/engines/{8,11,17}/` | MIT — © Lee Benfield | Verbatim, as a whole nested jar. The decompiler behind the library viewer |
| **tree-sitter** Java binding and six grammars | `org/treesitter/`, and the JNI natives beside it | MIT | Verbatim, **never relocated** — a JNI symbol is named after the mangled package. Per-jar provenance: `lib/tree-sitter/README.md` |
| **nvim-treesitter query families** (`folds.scm`, `indents.scm`, `locals.scm`) | `assets/crystalgui/syntax/*/` | Apache 2.0 — © nvim-treesitter contributors | **Modified**: each file's upstream `; inherits:` chain is resolved by concatenation at vendoring time, and every file's header names the sources it was resolved from |
| **ASM** (`asm`, `asm-commons`, `asm-tree`) | `com/crystalgui/lang/shadow/org/objectweb/asm/` | BSD-3-Clause — © INRIA, France Télécom | Verbatim, **relocated**. Unrelocated it is a split package against ModLauncher's own copy and three of the four loaders refuse to start |
| **jvmDowngrader** runtime stubs | `com/crystalgui/lang/shadow/xyz/wagyourtail/` | MIT — © wagyourtail | Emitted by the downgrade that makes one jar load on Java 8 and Java 17 alike |

The nested engine jars are copied **whole**, unmodified and unrelocated. That is a licensing
convenience and a technical requirement at once: relocating inside ECJ would rename types its own
reflection looks up by string.

**Minecraft's name mappings are not in this jar and are not redistributed.** They are fetched at
runtime into the user's own game directory — MCP `stable_12` from MinecraftForge's FML repository for
1.7.10; Mojang's `client.txt` with MinecraftForge's MCPConfig or Fabric's intermediary for 1.20.x — from
the addresses `crystalgui-<version>.jar` carries in `assets/crystalgui/download/locations.json`.

`com/crystalgui/language/**`, `com/crystalgui/mc/lang/**` and `com/crystalgui/mc/*/lang/**` are
CrystalGUI's own and are LGPL-3.0-or-later.
