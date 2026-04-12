plugins { `java-library` }

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
    // Provided at runtime by the platform (jsmacros bundles these).
    compileOnly("com.google.guava:guava:31.1-jre")
    compileOnly("com.google.code.gson:gson:2.10")
}

tasks.compileJava {
    options.encoding = "UTF-8"
}
