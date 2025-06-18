plugins {
	alias(libs.plugins.android.application).apply(false)
	alias(libs.plugins.android.library).apply(false)

	alias(libs.plugins.jetbrains.kotlin.android).apply(false)

	alias(libs.plugins.maven.publish).apply(false)
}

tasks.create<Delete>("clean") { delete(rootProject.layout.buildDirectory) }

tasks.wrapper { distributionType = Wrapper.DistributionType.ALL }
