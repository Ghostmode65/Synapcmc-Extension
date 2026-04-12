rootProject.name = "jsmacros-synapmc-dual"

include(":shared", ":legacy", ":ce")

project(":shared").projectDir = file("shared")
project(":legacy").projectDir = file("legacy")
project(":ce").projectDir    = file("ce")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
            metadataSources { artifact() }
        }
    }
}
