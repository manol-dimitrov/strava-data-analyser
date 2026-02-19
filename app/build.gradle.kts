plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(project(":core-domain"))
    implementation(project(":core-llm"))
    implementation(project(":core-metrics"))
    implementation(project(":infra-data"))
    implementation(project(":infra-strava"))
    implementation(project(":infra-llm"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.core)
    implementation(libs.logback.classic)
}

application {
    mainClass = "com.endurocoach.ApplicationKt"
}
