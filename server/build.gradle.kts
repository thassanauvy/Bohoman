plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlinSerialization)
}

group = "com.thassan.bohoman"
version = "1.0.0"
application {
    mainClass = "com.thassan.bohoman.ApplicationKt"
}

dependencies {
    api(project(":core"))
    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
    implementation("io.ktor:ktor-server-auth-jvm:${libs.versions.ktor.get()}")
    implementation("io.ktor:ktor-server-auth-jwt-jvm:${libs.versions.ktor.get()}")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:${libs.versions.ktor.get()}")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:${libs.versions.ktor.get()}")
    implementation("io.ktor:ktor-server-status-pages-jvm:${libs.versions.ktor.get()}")
    implementation("io.ktor:ktor-server-cors-jvm:${libs.versions.ktor.get()}")
    implementation("io.ktor:ktor-server-call-logging-jvm:${libs.versions.ktor.get()}")
    implementation("com.auth0:jwks-rsa:0.22.1")
    implementation("com.zaxxer:HikariCP:6.3.0")
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("org.flywaydb:flyway-core:11.8.2")
    implementation("org.flywaydb:flyway-database-postgresql:11.8.2")
}

tasks.test {
    // Integration tests use an explicitly selected, disposable PostgreSQL database.
    environment("BOHOMAN_TEST_DATABASE_URL", System.getenv("BOHOMAN_TEST_DATABASE_URL") ?: "")
}
