plugins {
    `java-library`
    `maven-publish`
}

group = "xyz.wagyourtail"
version = "1.0.1"

base {
    archivesName.set("synapmc")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8 //jsmacros ce move to 2_1
    targetCompatibility = JavaVersion.VERSION_1_8
}

repositories {
    mavenCentral()
    maven {
        url = uri("https://jitpack.io")
        metadataSources {
            artifact()
        }
    }
}

dependencies {
    compileOnly("com.github.jsmacros.jsmacros:jsmacros-1.19:a341a46254")
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

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
