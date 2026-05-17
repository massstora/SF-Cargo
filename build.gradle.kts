plugins {
    java
}

group = "com.massstora"
version = "0.1.1"

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}
