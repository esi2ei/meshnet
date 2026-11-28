buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 9.1 built-in Kotlin defaults to KGP 2.2.10. Pin 2.4.21
        // because the Solana MWA 2.2.1 dependency publishes Kotlin 2.4 metadata.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.1.1" apply false
}
