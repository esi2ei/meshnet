import java.io.File
import org.gradle.api.GradleException
import org.gradle.api.tasks.Sync

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Build the React dashboard from the repository root and package it as local APK assets.
// Runtime code serves the bundled assets through AndroidX WebViewAssetLoader; no hosted website is required.
val repositoryRoot = rootProject.projectDir.parentFile
val bundledWebAssets = layout.buildDirectory.dir("generated/meshnet-web-assets")
val npmExecutable = if (System.getProperty("os.name").lowercase().contains("windows")) "npm.cmd" else "npm"

val buildWebDashboard = tasks.register<Exec>("buildWebDashboard") {
    group = "build"
    description = "Build the offline-first React dashboard for the Android WebView."
    workingDir(repositoryRoot)
    commandLine(npmExecutable, "run", "build")
    // CI may supply the exact dashboard artifact already typechecked and built by the web job.
    // Local Android builds continue to rebuild the dashboard unless this explicit flag is set.
    onlyIf {
        providers.environmentVariable("MESHNET_PREBUILT_WEB_ASSETS").orNull != "true" ||
            !repositoryRoot.resolve("dist/index.html").isFile
    }
}

val bundleWebDashboard = tasks.register<Sync>("bundleWebDashboard") {
    group = "build"
    description = "Package the Vite dashboard inside the Android application assets."
    dependsOn(buildWebDashboard)
    from(repositoryRoot.resolve("dist"))
    into(bundledWebAssets.map { it.dir("web") })
}

val releaseStoreFilePath = providers.environmentVariable("MESHNET_RELEASE_STORE_FILE").orNull?.takeIf { it.isNotBlank() }
val releaseStorePassword = providers.environmentVariable("MESHNET_RELEASE_STORE_PASSWORD").orNull?.takeIf { it.isNotBlank() }
val releaseKeyAlias = providers.environmentVariable("MESHNET_RELEASE_KEY_ALIAS").orNull?.takeIf { it.isNotBlank() }
val releaseKeyPassword = providers.environmentVariable("MESHNET_RELEASE_KEY_PASSWORD").orNull?.takeIf { it.isNotBlank() }

val validateMeshnetReleaseSigning = tasks.register("validateMeshnetReleaseSigning") {
    group = "verification"
    description = "Fail closed when a release artifact has no external signing credentials."
    doLast {
        val missing = buildList {
            if (releaseStoreFilePath == null) add("MESHNET_RELEASE_STORE_FILE")
            if (releaseStorePassword == null) add("MESHNET_RELEASE_STORE_PASSWORD")
            if (releaseKeyAlias == null) add("MESHNET_RELEASE_KEY_ALIAS")
            if (releaseKeyPassword == null) add("MESHNET_RELEASE_KEY_PASSWORD")
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release signing is not configured. Set these environment variables outside the repository: ${missing.joinToString()}"
            )
        }
        val keystorePath = File(releaseStoreFilePath!!)
        if (!keystorePath.isAbsolute) {
            throw GradleException("MESHNET_RELEASE_STORE_FILE must be an absolute path outside the repository.")
        }
        if (!keystorePath.isFile) {
            throw GradleException("MESHNET_RELEASE_STORE_FILE does not point to an existing keystore file.")
        }
        val checkoutRoot = rootProject.projectDir.canonicalFile.parentFile
            ?: rootProject.projectDir.canonicalFile
        val canonicalKeystore = keystorePath.canonicalFile
        if (canonicalKeystore.toPath().startsWith(checkoutRoot.toPath())) {
            throw GradleException(
                "Release keystore must be stored outside the repository checkout; use a private path or secrets manager."
            )
        }
    }
}

tasks.named("preBuild").configure { dependsOn(bundleWebDashboard) }

tasks.configureEach {
    val releasePackagingTask = name.contains("Release", ignoreCase = true) &&
        (name.startsWith("package", ignoreCase = true) ||
            name.startsWith("bundle", ignoreCase = true) ||
            name.startsWith("sign", ignoreCase = true) ||
            name.startsWith("validateSigning", ignoreCase = true))
    if (releasePackagingTask) dependsOn(validateMeshnetReleaseSigning)
}

android {
    sourceSets.getByName("main").assets.srcDir(bundledWebAssets)
    namespace = "com.meshnet.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.meshnet.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("meshnetRelease") {
            storeFile = releaseStoreFilePath?.let { project.file(it) }
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("meshnetRelease")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.18.0")
    implementation("com.solanamobile:mobile-wallet-adapter-clientlib-ktx:2.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
