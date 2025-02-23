import com.diffplug.gradle.spotless.SpotlessExtension
import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SonatypeHost


plugins {
    `java-library`
    id("com.diffplug.spotless")
    id("com.vanniktech.maven.publish") version "0.30.0"
}

allprojects {
    group = "dev.totis"
    version = "1.4.0"
}



subprojects {
    apply(plugin = "java-library")
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "com.vanniktech.maven.publish")

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
    java {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    mavenPublishing {
        publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
        configure(JavaLibrary(
            javadocJar = JavadocJar.Javadoc(),
            sourcesJar = false,
        ))
        signAllPublications()
        pom {
            name.set("tiny-s3")
            description.set("A tiny embeddable S3 compatible library")
            inceptionYear.set("2024")
            url.set("https://github.com/devdilson")
            licenses {
                license {
                    name.set("AGPL v3")
                    url.set("https://github.com/devdilson/")
                    distribution.set("https://github.com/devdilson/")
                }
            }
            developers {
                developer {
                    id.set("devdilson")
                    name.set("Adi A")
                    url.set("https://github.com/devdilson/")
                }
            }
            scm {
                url.set("https://github.com/devdilson/tiny-s3")
                connection.set("scm:git:git://github.com/devdilson/tiny-s3.git")
                developerConnection.set("scm:git:ssh://git@github.com/devdilson/tiny-s3.git")
            }
        }
    }


}
