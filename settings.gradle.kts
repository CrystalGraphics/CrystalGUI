rootProject.name = "CrystalGUI"

include("core")
include("gl-debug-harness")

// The tree-sitter syntax backend. Its jars are checked in under lib/tree-sitter/, so this is an ordinary
// module rather than one conditional on a local checkout -- see that directory's README for why.
//
// It stays a SEPARATE module regardless: core/ must remain loadable on a dedicated server with no GL and
// no native libraries, so core/ owns the SyntaxTokenizer interface and nothing that needs a .dll/.so.
include("language")

includeBuild("CrystalGraphics") {
    dependencySubstitution {
        substitute(module("com.crystalgraphics:platform")).using(project(":platform"))
        substitute(module("com.crystalgraphics:core")).using(project(":core"))
        substitute(module("com.crystalgraphics:freetype-msdfgen-harfbuzz-bindings")).using(project(":freetype-msdfgen-harfbuzz-bindings"))
    }

}

//include(":CrystalGraphics")
//include(":CrystalGraphics:core")
//include(":CrystalGraphics:platform")
//include(":CrystalGraphics:freetype-msdfgen-harfbuzz-bindings")
//includeBuild("mc1710")
//includeBuild("mc1201")