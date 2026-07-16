rootProject.name = "CrystalGUI"

include("core")
include("gl-debug-harness")

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