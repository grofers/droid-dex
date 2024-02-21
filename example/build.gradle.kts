@file:Suppress("UnstableApiUsage")

plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.jetbrains.kotlin.android)
}


android {
	namespace = "com.blinkit.droiddexexample"

	buildFeatures {
		viewBinding = true
		buildConfig = true
	}

	compileSdk = libs.versions.compileSdk.get().toInt()

	defaultConfig {
		applicationId = "com.blinkit.droiddexexample"

		minSdk = 24
		targetSdk = libs.versions.targetSdk.get().toInt()

		versionCode = 1
		versionName = "1.0"
	}

	buildTypes {
		release {
			isMinifyEnabled = false
			proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
		}
	}

	compileOptions {
		sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
		targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
	}
	kotlinOptions { jvmTarget = libs.versions.java.get() }
}


dependencies {
	implementation(project(":droid-dex"))

	implementation(libs.timber)

	implementation(libs.bundles.core)

	implementation(libs.bundles.ui)
}
