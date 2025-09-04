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

// Jackson shipped by Minecraft is too old, so we include our newer version as JAR-in-JAR
// Don't relocate - just include as-is

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
    // Only shadow the mod-specific classes, not core
    shadowBundle(project(path = ":mod", configuration = "transformProductionNeoForge")) {
        exclude(group = "org.geysermc.geyser", module = "core")
        exclude(group = "org.geysermc.geyser", module = "api")
    }
    
    // Include core via JiJ to avoid module conflicts
    include(projects.core)
    // Include API via JiJ to provide classes without module conflicts
    include(projects.api)

    // Use NeoForge's Jackson 2.13.4 (provided) and only add the YAML module
    // NeoForge provides: jackson-core, jackson-databind, jackson-annotations 2.13.4
    include("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.13.4")
    
    // Include SnakeYAML as it's required by Jackson YAML
    include("org.yaml:snakeyaml:1.30")

    // Don't include API as separate bundle - it causes module conflicts
    // The API classes should be included via core dependencies
    // shadowBundle(projects.api)

    // cannot be shaded, since neoforge will complain if floodgate-neoforge tries to provide this
    include(projects.common)

    // Include mcprotocollib explicitly since it's needed by the mod mixins
    include(libs.mcprotocollib)
    
    // Include transitive dependencies but exclude Jackson since we're including it explicitly
    includeTransitive(projects.core) {
        exclude(group = "com.fasterxml.jackson.core")
        exclude(group = "com.fasterxml.jackson.dataformat")
    }

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
        // Without this, service files are not merged properly
        mergeServiceFiles()
        
        // Exclude ALL module-related files to disable module system entirely
        exclude("**/module-info.class")
        exclude("META-INF/versions/*/module-info.class") 
        exclude("**/META-INF/services/java.lang.module.ModuleProvider")
        exclude("**/META-INF/services/org.geysermc.geyser.api.*")
        exclude("**/META-INF/versions/**")
        exclude("META-INF/versions/**")
        exclude("META-INF/MANIFEST.MF")
        
        // Add more aggressive exclusions
        exclude("**/META-INF/*.SF")
        exclude("**/META-INF/*.DSA") 
        exclude("**/META-INF/*.RSA")
        exclude("**/META-INF/*.EC")
        
        // Force complete merging - don't preserve original manifests
        append("META-INF/services/org.geysermc.geyser.api.extension.Extension")
        
        
        // Force non-modular jar with clean manifest
        manifest {
            attributes.clear()
            attributes["Main-Class"] = "org.geysermc.geyser.platform.neoforge.GeyserNeoForgeMain"
            attributes["Multi-Release"] = "false"
            // Ensure no automatic module name
            attributes.remove("Automatic-Module-Name")
        }
        
        // Force all packages into single jar without separate modules
        archiveClassifier.set("")
    }
}

modrinth {
    loaders.add("neoforge")
    uploadFile.set(tasks.getByPath("remapModrinthJar"))
}