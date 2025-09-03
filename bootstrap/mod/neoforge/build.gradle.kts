plugins {
    id("geyser.modded-conventions")
    id("geyser.modrinth-uploading-conventions")
}

architectury {
    platformSetupLoomIde()
    neoForge()
}

// This is provided by "org.cloudburstmc.math.mutable" too, so yeet.
// NeoForge's class loader is *really* annoying.
provided("org.cloudburstmc.math", "api")
provided("com.google.errorprone", "error_prone_annotations")

// Jackson shipped by Minecraft is too old, so we shade & relocate our newer version
relocate("com.fasterxml.jackson")

val includeTransitive: Configuration = configurations.getByName("includeTransitive")

dependencies {
    // See https://github.com/google/guava/issues/6618
    modules {
        module("com.google.guava:listenablefuture") {
          replacedBy("com.google.guava:guava", "listenablefuture is part of guava")
        }
    }

    neoForge(libs.neoforge.minecraft)

    api(project(":mod", configuration = "namedElements"))
    shadowBundle(project(path = ":mod", configuration = "transformProductionNeoForge"))
    // Include core via shadowBundle to ensure resources are packaged
    shadowBundle(projects.core)
    // Include API via JiJ to avoid module conflicts but still provide classes
    include(projects.api)

    // Minecraft (1.21.2+) includes jackson. But an old version!
    shadowBundle(libs.jackson.core)
    shadowBundle(libs.jackson.databind)
    shadowBundle(libs.jackson.dataformat.yaml)
    shadowBundle(libs.jackson.annotations)

    // Don't include API as separate bundle - it causes module conflicts
    // The API classes should be included via core dependencies
    // shadowBundle(projects.api)

    // cannot be shaded, since neoforge will complain if floodgate-neoforge tries to provide this
    include(projects.common)

    // Include all transitive deps of core via JiJ
    includeTransitive(projects.core)

    modImplementation(libs.cloud.neoforge)
    include(libs.cloud.neoforge)
}

tasks.withType<Jar> {
    manifest.attributes["Main-Class"] = "org.geysermc.geyser.platform.neoforge.GeyserNeoForgeMain"
}

tasks {
    remapJar {
        archiveBaseName.set("Geyser-NeoForge")
    }

    remapModrinthJar {
        archiveBaseName.set("geyser-neoforge")
    }

    shadowJar {
        // Without this, jackson's service files are not relocated
        mergeServiceFiles()
        
        // Exclude ALL module-related files to force single module
        exclude("**/module-info.class")
        exclude("META-INF/versions/*/module-info.class")
        exclude("**/META-INF/MANIFEST.MF")
        exclude("**/META-INF/services/java.lang.module.ModuleProvider")
        exclude("**/META-INF/services/org.geysermc.geyser.api.*")
        
        // Force all packages into single jar without separate modules
        archiveClassifier.set("")
        
        // Resources should be included automatically via shadowBundle
    }
}

modrinth {
    loaders.add("neoforge")
    uploadFile.set(tasks.getByPath("remapModrinthJar"))
}
