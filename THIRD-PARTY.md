# Third-party notices

Everything in this repository that was written by somebody else, what it is licensed under, and what that
obliges us to do. **This file is an obligation, not documentation** — MIT requires its copyright notice to
travel with the distribution, and Apache 2.0 requires the licence, any `NOTICE`, and a statement of
modifications. A javadoc comment naming the source is good practice and is not the same thing.

Per-directory detail lives beside the assets it covers; this is the index.

| What | Where | Licence | Notes |
|---|---|---|---|
| IntelliJ Platform icons | `core/src/main/resources/assets/crystalgui/ui/icons/filetypes/` | Apache 2.0 | © 2000–2021 JetBrains s.r.o. Verbatim. See [ATTRIBUTION.md](core/src/main/resources/assets/crystalgui/ui/icons/ATTRIBUTION.md) |
| Feather icons | `core/src/main/resources/assets/crystalgui/ui/icons/` | MIT | © 2013–2023 Cole Bemis. Verbatim |
| Minecraft fonts | `core/src/main/resources/assets/crystalgui/ui/fonts/` | Public domain | `Minecraft.otf`, `MinecraftRegular.otf` |
| Taffy | Gradle dependency `dev.vfyjxf:taffy` | — | Extracted sources checked in at `research_repos/taffy/` for reference only |
| LDLib2 | `research_repos/LDLib2/` | — | In-repo checkout, read for pattern prior art. **Not** a dependency and nothing is copied from it |
| Minecraft 1.20.1 sources | `research_repos/mc1201_sources/` | Proprietary | Decompiled reference. Not redistributed, not built |

## Ports, as opposed to assets

Code in this repository ports algorithms and module boundaries from other editors. The rule is recorded in
`AGENTS.md` § *Licences are load-bearing here*, and repeated because it is the thing most likely to be got
wrong by someone moving fast:

| Source | Licence | What is permitted |
|---|---|---|
| VS Code / Monaco, CodeMirror 6 | **MIT** | **Port the code.** Attribute in the class javadoc, naming the source file |
| **Zed** | **GPL-3.0** | **Read for shape only.** Copying it would impose GPL on this repository. `Rope`/`TextSummary` take `SumTree`'s *design*; not a line of its code |

## Trademarks

No licence here grants trademark rights — Apache 2.0 § 6 says so explicitly, and MIT is silent, which
amounts to the same. Product logos and language marks (the JetBrains and IntelliJ IDEA marks, the Java
coffee cup, the Python, Rust and Docker logos) belong to their owners regardless of the licence on the file
they arrive in.

This matters for what we *ship*, not for what we test against. The IntelliJ Platform file-type icons in
`ui/icons/filetypes/` are JetBrains' own drawings of documents and are not marks. `IntelliJ_IDEA_Icon.svg`
**is** a mark, and lives in `core/src/test/resources/` for that reason — it is the SVG renderer's torture
test (nested groups, four `userSpaceOnUse` gradients, entirely filled polygons) and is deliberately not in
the jar.
