plugins {
    java
    `java-library`
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.minio:minio:8.5.17")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.1")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
