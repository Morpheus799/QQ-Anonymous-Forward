plugins {
    id("com.android.application")
}

android {
    namespace = "dev.anonforward"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "io.github.Morpheus799.AFQAux"
        minSdk = 24
        targetSdk = 36
        versionCode = rootProject.extra["anonVersionCode"] as Int
        versionName = rootProject.extra["anonVersionName"] as String
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    implementation(project(":core"))
    compileOnly(project(":qaux-api-stubs"))
}
