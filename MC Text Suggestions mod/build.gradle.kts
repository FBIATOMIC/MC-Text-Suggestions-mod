plugins {
    alias(libs.plugins.fabric.loom)
}

group = "com.example.overlaychat"
version = "1.0.0"

repositories {
    mavenCentral()
    maven { url = uri("https://maven.fabricmc.net/") }
}

dependencies {
    minecraft(libs.minecraft)
    mappings(variantOf(libs.yarn) { classifier("v2") })
    modImplementation(libs.fabric.loader)

    // JNA for native Windows interop
    implementation("net.java.dev.jna:jna:5.13.0")
    implementation("net.java.dev.jna:jna-platform:5.13.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.processResources {
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}


