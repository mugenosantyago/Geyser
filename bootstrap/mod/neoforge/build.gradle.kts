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
    // Use shadowBundle for core to ensure resources are properly merged
    shadowBundle(projects.core)
    // Include API via JiJ to provide classes without module conflicts
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
    // Disable module system to prevent conflicts
    manifest.attributes.remove("Automatic-Module-Name")
    manifest.attributes["Multi-Release"] = "false"
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
        
        // Exclude ALL module-related files to disable module system entirely
        exclude("**/module-info.class")
        exclude("META-INF/versions/*/module-info.class") 
        exclude("**/META-INF/services/java.lang.module.ModuleProvider")
        exclude("**/META-INF/services/org.geysermc.geyser.api.*")
        exclude("**/META-INF/versions/**")
        
        // Add more aggressive exclusions
        exclude("**/META-INF/*.SF")
        exclude("**/META-INF/*.DSA") 
        exclude("**/META-INF/*.RSA")
        
        // Force complete merging - don't preserve original manifests
        append("META-INF/services/org.geysermc.geyser.api.extension.Extension")
        
        // Force non-modular jar with clean manifest
        manifest {
            attributes.clear()
            attributes["Main-Class"] = "org.geysermc.geyser.platform.neoforge.GeyserNeoForgeMain"
            attributes["Multi-Release"] = "false"
        }
        
        // Force all packages into single jar without separate modules
        archiveClassifier.set("")
    }
}

modrinth {
    loaders.add("neoforge")
    uploadFile.set(tasks.getByPath("remapModrinthJar"))
}
