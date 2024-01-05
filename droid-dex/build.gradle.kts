@file:Suppress("UnstableApiUsage")

import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import java.net.URI

plugins {
	alias(libs.plugins.android.library)
	alias(libs.plugins.jetbrains.kotlin.android)

	alias(libs.plugins.maven.publish)
}


publishing {
	repositories {
		maven {
			name = "Blinkit"
			url = URI("https://maven.pkg.github.com/grofers/droid-dex")
			credentials {
				username = "Blinkit"
				password = System.getenv("READ_ARTIFACTS_TOKEN")
			}
		}
	}
}


mavenPublishing {
	configure(AndroidSingleVariantLibrary("release", sourcesJar = true, publishJavadocJar = true))
}


android {
	namespace = "com.blinkit.droiddex"

	compileSdk = libs.versions.compileSdk.get().toInt()

	defaultConfig {
		minSdk = libs.versions.minSdk.get().toInt()

		consumerProguardFiles("consumer-rules.pro")
	}

	buildTypes { release { isMinifyEnabled = false } }

	compileOptions {
		sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
		targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
	}
	kotlinOptions { jvmTarget = libs.versions.java.get() }
}


dependencies {
	implementation(libs.timber)

	implementation(libs.bundles.core)

	implementation(libs.kotlinx.coroutines.android)

	implementation(libs.bundles.lifecycle)

	implementation(libs.androidx.core.performance)
}
