plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

allprojects {
    group = "com.endurocoach"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }
}
