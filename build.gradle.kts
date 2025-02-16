import com.diffplug.gradle.spotless.SpotlessExtension

plugins {
    `java-library`
    id("com.diffplug.spotless")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

allprojects {
    group = "com.releases"
}



subprojects {
    apply(plugin = "com.diffplug.spotless")

    configure<SpotlessExtension> {
        if (!project.name.endsWith("-bom")) {
            java {
                googleJavaFormat()
                removeUnusedImports()
            }

        }

        kotlinGradle {
            target("*.gradle.kts")
            ktlint()
        }

    }


}

