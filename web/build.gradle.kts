plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
}

group = "com.eta.tbp.web"

kotlin {
    js {
        browser {
            commonWebpackConfig {
                outputFileName = "webapp.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        jsMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.html.js)
        }
    }
}

ktlint {
    version.set("1.7.0")
}
