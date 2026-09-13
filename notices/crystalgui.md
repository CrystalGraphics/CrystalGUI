# Third-party notices — `crystalgui.jar`

This file ships **inside** `crystalgui-<version>.jar` as `META-INF/NOTICE.md`, because MIT, Apache 2.0
and the SIL OFL all require the notice to travel with the distribution rather than with the source
repository. `checkSingleJar` asserts it is present.

The scripting stack — tree-sitter, ECJ, Rhino, CFR and ASM — is **not in this jar**. It ships in
`crystalgui-language-<version>.jar`, which carries [its own notice](../notices/crystalgui-language.md).

The repository-level index, with the full reasoning behind each entry, is
[`THIRD-PARTY.md`](../THIRD-PARTY.md).

| What | Where in this jar | Licence | Form |
|---|---|---|---|
| **Taffy** (`taffy-java`) | `com/crystalgui/shadow/dev/vfyjxf/taffy/` | MIT — © 2026 vfyjxf | Vendored, **modified**, relocated. Statement of changes: `taffy/MODIFICATIONS.md` |
| **IntelliJ diff/merge algorithms** | `com/crystalgui/text/diff/` | Apache 2.0 — © 2000–2024 JetBrains s.r.o. | **Ported source, modified.** Each class names its upstream file and its modifications in its own javadoc, per § 4(b) |
| **IntelliJ Platform icons** | `assets/crystalgui/ui/icons/filetypes/` | Apache 2.0 — © 2000–2021 JetBrains s.r.o. | Verbatim. See `assets/crystalgui/ui/icons/ATTRIBUTION.md`, which ships beside them |
| **Feather icons** | `assets/crystalgui/ui/icons/` | MIT — © 2013–2023 Cole Bemis | Verbatim |
| **JetBrains Mono** | `assets/crystalgui/ui/fonts/JetBrainsMono-Regular.ttf` | SIL OFL 1.1 | Verbatim |
| **IBM Plex Sans** | `assets/crystalgui/ui/fonts/IBMPlexSans-Regular.ttf` | SIL OFL 1.1 — © 2017 IBM Corp., Reserved Font Name "Plex" | Verbatim. Licence: `IBMPlexSans-OFL.txt`, beside it |
| Minecraft fonts | `assets/crystalgui/ui/fonts/Minecraft*.otf` | Public domain | Verbatim |
| **GrapesJS** sorter | `com/crystalgui/widget/dnd/SortPlacement`, `com/crystalgui/widget/surface/EdgePan`, `com/crystalgui/app/uibuilder/canvas/DropResolver`, `com/crystalgui/app/uibuilder/document/{TreeDropRules,TreeMoves}` | BSD-3-Clause — © 2017–current Artur Arseniev | **Ported source, modified.** Licence below |
| **jvmDowngrader** runtime stubs | `com/crystalgui/shadow/xyz/wagyourtail/` | MIT — © wagyourtail | Emitted by the downgrade that makes one jar load on Java 8 and Java 17 alike |

`com/crystalgui/**` outside the rows above, and every shader, stylesheet and sprite under
`assets/crystalgui/`, is CrystalGUI's own and is LGPL-3.0-or-later.

`assets/crystalgui/sources/` is this project's own Java sources, shipped so the documentation popup
can quote a real declaration. Same licence as the classes beside them.

## GrapesJS — BSD-3-Clause

```
Copyright (c) 2017-current, Artur Arseniev
All rights reserved.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

- Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.
- Redistributions in binary form must reproduce the above copyright notice, this
  list of conditions and the following disclaimer in the documentation and/or
  other materials provided with the distribution.
- Neither the name "GrapesJS" nor the names of its contributors may be
  used to endorse or promote products derived from this software without
  specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```
