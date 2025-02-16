plugins {
    java
    application
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.minio:minio:8.5.17")
}

application {
    mainClass = "com.tinys3.S3Server"
}
tasks.named<Test>("test") {
    useJUnitPlatform()
}
