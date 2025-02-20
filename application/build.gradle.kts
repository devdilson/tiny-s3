plugins {
    java
    `java-library`
    id("com.google.cloud.tools.jib") version "3.4.0"
}

dependencies {
    implementation(project(":tiny-s3-lib"))
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.withType<JavaExec> {
    jvmArgs =
        listOf(
            "-Xms56m",
            "-Xmx256m",
            "-XX:+UseG1GC",
            "-XX:+UseCompressedOops",
        )
}

jib {
    from {
        image = "eclipse-temurin:21-jre-alpine"
    }
    to {
        image = "tinys3/server"
        tags = setOf("latest", version.toString())
    }
    container {
        ports = listOf("8080")

        jvmFlags =
            listOf(
                "-Xms512m",
                "-Xmx512m",
                "-XX:+UseContainerSupport",
                "-Xverify:none",
            )
        environment = mapOf()
    }
}
