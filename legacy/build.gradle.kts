plugins {
    `java-library`
    `maven-publish`
}

group   = providers.gradleProperty("legacy.group").get()
version = providers.gradleProperty("legacy.version").get()

base { archivesName.set("synapmc") }

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
    // Shared neutral code — compiled at Java 8 level.
    implementation(project(":shared"))

    compileOnly(providers.gradleProperty("legacy.jsmacrosDep").get())
    compileOnly("com.google.guava:guava:31.1-jre")
    compileOnly("com.google.code.gson:gson:2.9.0")
    compileOnly("commons-io:commons-io:2.11.0")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.1")
}

tasks.compileJava {
    options.encoding = "UTF-8"
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
}

tasks.named<Jar>("jar") {
    dependsOn(":shared:classes")
    from(project(":shared").layout.buildDirectory.dir("classes/java/main"))
    from(project(":shared").layout.buildDirectory.dir("resources/main"))
}

tasks.test { useJUnitPlatform() }

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
