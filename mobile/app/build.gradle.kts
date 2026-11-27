import org.gradle.api.GradleException

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
        val keystorePath = java.io.File(releaseStoreFilePath!!)
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

tasks.configureEach {
    val releasePackagingTask = name.contains("Release", ignoreCase = true) &&
        (name.startsWith("package", ignoreCase = true) ||
            name.startsWith("bundle", ignoreCase = true) ||
            name.startsWith("sign", ignoreCase = true) ||
            name.startsWith("validateSigning", ignoreCase = true))
    if (releasePackagingTask) dependsOn(validateMeshnetReleaseSigning)
}

android {
    namespace = "com.meshnet.app"
    compileSdk = 36

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

    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.solanamobile:mobile-wallet-adapter-clientlib-ktx:2.0.7")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
