# runtime/mc/modern/common — Agent Knowledge Base

The `common` branch of the Stonecutter tree, built once per Minecraft version
(`:runtime:mc:modern:common:<version>`), and **empty**: CrystalGUI holds no Minecraft-facing code, so no loader
node has anything to share. Everything starts from `core`'s lifecycle listeners (`runtime/mc/CLAUDE.md`).

The branch and its wiring (`cg-modern-common.gradle.kts`, the loader nodes' dependencies on it) are kept as they
are. Code a loader would need belongs to CrystalGraphics: a service in its `platform`, answered by each era's host.
