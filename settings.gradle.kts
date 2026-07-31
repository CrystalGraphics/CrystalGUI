rootProject.name = "CrystalGUI"

include("core")
include("gl-debug-harness")

// The tree-sitter syntax backend is included only when the local fork is present. It is consumed as
// built jars rather than from Maven Central because the official `jtreesitter` needs JDK 23+ and the
// Foreign Function & Memory API, which this project cannot use; the fork is JNI-based and Java 8.
//
// Conditional on purpose: an absolute path to a sibling checkout must not be a build requirement, and an
// editor without this module falls back to the built-in lexer rather than failing.
val treeSitterHome = (extra.properties["treeSitterHome"] as String?)
    ?: "${rootDir.parent}/tree-sitter/tree-sitter-ng-v0.26.6"
if (file("$treeSitterHome/tree-sitter/build/libs/tree-sitter-0.26.6.jar").exists()) {
    include("syntax-treesitter")
} else {
    logger.lifecycle("tree-sitter fork not found at $treeSitterHome - syntax-treesitter module skipped")
}

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