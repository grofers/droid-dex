import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins {
	alias(libs.plugins.android.application)

}


android {
	namespace = "com.blinkit.droiddexexample"

	buildFeatures { viewBinding = true }

	compileSdk = libs.versions.sdk.compile.get().toInt()

	defaultConfig {
		applicationId = "com.blinkit.droiddexexample"

		minSdk = libs.versions.sdk.min.get().toInt()
		targetSdk = libs.versions.sdk.target.get().toInt()

		versionCode = 1
		versionName = "1.0"
	}

	buildTypes { release { isMinifyEnabled = false } }

	compileOptions {
		sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
		targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
	}
}


dependencies {
	implementation(project(":droid-dex"))

	implementation(libs.timber)

	implementation(libs.bundles.core)

	implementation(libs.bundles.ui)
}


kotlin {
	jvmToolchain(libs.versions.java.get().toInt())
	compilerOptions { jvmTarget.set(JvmTarget.fromTarget(libs.versions.java.get())) }
}
