plugins {
    alias(libs.plugins.kotlin.multiplatform)
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
}

ktlint {
    version.set("1.7.0")
}
