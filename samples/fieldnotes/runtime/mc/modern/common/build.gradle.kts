// The `common` branch: what every loader shares and that names Minecraft -- never a loader. One node per
// Minecraft version a loader node targets, each compiled against that version.
import cgbuildlogic.configureStubs
import cgbuildlogic.guardLoaderImports
import cgbuildlogic.useModernMinecraft

plugins { `java-library` }

apply(from = rootDir.resolve("runtime/mc/modern/node.gradle.kts"))

useModernMinecraft()        // the node's pinned toolchain; nothing in stub mode, where the stub is Minecraft

guardLoaderImports()
configureStubs()
