import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    application
}

group = "com.eta.tbp.server"

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("com.eta.tbp.server.MainKt")
}

dependencies {
    implementation(project(":lib"))
    implementation(libs.org.json)

    testImplementation(libs.junit)
}

ktlint {
    version.set("1.7.0")
}
