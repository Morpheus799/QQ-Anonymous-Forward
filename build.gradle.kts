import com.android.build.api.dsl.ApplicationExtension

plugins {
    id("com.android.application") version "9.3.0" apply false
    id("com.android.library") version "9.3.0" apply false
}

val anonVersionCode = providers.gradleProperty("anonVersionCode").get().toInt()
val anonVersionName = providers.gradleProperty("anonVersionName").get()
extra["anonVersionCode"] = anonVersionCode
extra["anonVersionName"] = anonVersionName

val releaseKeystorePath = providers.environmentVariable("ANONFORWARD_KEYSTORE_PATH").orNull
val releaseKeystorePassword = providers.environmentVariable("ANONFORWARD_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ANONFORWARD_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANONFORWARD_KEY_PASSWORD").orNull
val releaseSigningConfigured = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

subprojects {
    plugins.withId("com.android.application") {
        extensions.configure<ApplicationExtension> {
            if (releaseSigningConfigured) {
                signingConfigs.create("anonForwardRelease") {
                    storeFile = rootProject.file(releaseKeystorePath!!)
                    storePassword = releaseKeystorePassword
                    keyAlias = releaseKeyAlias
                    keyPassword = releaseKeyPassword
                }
                buildTypes.named("release") {
                    signingConfig = signingConfigs.getByName("anonForwardRelease")
                }
            }
        }
    }
}

tasks.register<Copy>("assembleProducts") {
    group = "build"
    description = "Build and collect both QAuxiliary-plugin and standalone LSPosed APKs."
    dependsOn(":app:assembleDebug", ":standalone:assembleDebug")
    into(layout.projectDirectory.dir("outputs"))
    from(layout.projectDirectory.file("app/build/outputs/apk/debug/app-debug.apk")) {
        rename { "AnonForward-QAux-$anonVersionName-debug.apk" }
    }
    from(layout.projectDirectory.file("standalone/build/outputs/apk/debug/standalone-debug.apk")) {
        rename { "AnonForward-LSPosed-$anonVersionName-debug.apk" }
    }
}

tasks.register<Copy>("assembleReleaseProducts") {
    group = "build"
    description = "Build, sign, and collect both release APKs."
    dependsOn(":app:assembleRelease", ":standalone:assembleRelease")
    doFirst {
        check(releaseSigningConfigured) {
            "Release signing is not configured. Set all ANONFORWARD_KEYSTORE_* environment variables."
        }
    }
    into(layout.projectDirectory.dir("outputs"))
    from(layout.projectDirectory.file("app/build/outputs/apk/release/app-release.apk")) {
        rename { "AnonForward-QAux-$anonVersionName.apk" }
    }
    from(layout.projectDirectory.file("standalone/build/outputs/apk/release/standalone-release.apk")) {
        rename { "AnonForward-LSPosed-$anonVersionName.apk" }
    }
}
