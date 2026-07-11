// Root project — pure build coordinator. No source lives here.
//
// MC version subprojects:
//   :mc1710   — Minecraft 1.7.10 + Forge (LWJGL 2, gtnhconvention)
//   :mc1201   — Minecraft 1.20.1 scaffold (future)
//
// Platform-agnostic subprojects:
//   :core     — platform-agnostic UI engine

plugins {
    idea
}

buildscript {
    repositories {
        maven {
            name = "GTNH Maven"
            url = uri("https://nexus.gtnewhorizons.com/repository/public/")
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

repositories {
    maven {
        name = "GTNH Maven"
        url = uri("https://nexus.gtnewhorizons.com/repository/public/")
    }
    gradlePluginPortal()
    mavenCentral()
}
