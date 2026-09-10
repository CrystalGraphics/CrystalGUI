# Third-party notices — `crystalgui.jar`

This file ships **inside** `crystalgui-<version>.jar` as `META-INF/NOTICE.md`, because MIT, Apache 2.0
and the SIL OFL all require the notice to travel with the distribution rather than with the source
repository. `checkSingleJar` asserts it is present.

The scripting stack — tree-sitter, ECJ, Rhino, CFR and ASM — is **not in this jar**. It ships in
`crystalgui_lang-<version>.jar`, which carries [its own notice](../notices/crystalgui_lang.md).

The repository-level index, with the full reasoning behind each entry, is
[`THIRD-PARTY.md`](../THIRD-PARTY.md).

| What | Where in this jar | Licence | Form |
|---|---|---|---|
| **Taffy** (`taffy-java`) | `com/crystalgui/shadow/dev/vfyjxf/taffy/` | MIT — © 2026 vfyjxf | Vendored, **modified**, relocated. Statement of changes: `taffy/MODIFICATIONS.md` |
| **IntelliJ diff/merge algorithms** | `com/crystalgui/text/diff/` | Apache 2.0 — © 2000–2024 JetBrains s.r.o. | **Ported source, modified.** Each class names its upstream file and its modifications in its own javadoc, per § 4(b) |
| **IntelliJ Platform icons** | `assets/crystalgui/ui/icons/filetypes/` | Apache 2.0 — © 2000–2021 JetBrains s.r.o. | Verbatim. See `assets/crystalgui/ui/icons/ATTRIBUTION.md`, which ships beside them |
| **Feather icons** | `assets/crystalgui/ui/icons/` | MIT — © 2013–2023 Cole Bemis | Verbatim |
| **JetBrains Mono** | `assets/crystalgui/ui/fonts/JetBrainsMono-Regular.ttf` | SIL OFL 1.1 | Verbatim |
| Minecraft fonts | `assets/crystalgui/ui/fonts/Minecraft*.otf` | Public domain | Verbatim |
| **jvmDowngrader** runtime stubs | `com/crystalgui/shadow/xyz/wagyourtail/` | MIT — © wagyourtail | Emitted by the downgrade that makes one jar load on Java 8 and Java 17 alike |

`com/crystalgui/**` outside the rows above, and every shader, stylesheet and sprite under
`assets/crystalgui/`, is CrystalGUI's own and is LGPL-3.0-or-later.

`assets/crystalgui/sources/` is this project's own Java sources, shipped so the documentation popup
can quote a real declaration. Same licence as the classes beside them.
