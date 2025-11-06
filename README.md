[![Maven Central Version](https://img.shields.io/maven-central/v/com.eternal.kits/droid-dex?strategy=highestVersion)](https://mvnrepository.com/artifact/com.eternal.kits/droid-dex) [![GitHub Issues](https://img.shields.io/github/issues/grofers/droid-dex)](https://github.com/grofers/droid-dex/issues) [![GitHub Stars](https://img.shields.io/github/stars/grofers/droid-dex?style=flat)](https://github.com/grofers/droid-dex/stargazers) [![GitHub License](https://img.shields.io/github/license/grofers/droid-dex)](https://github.com/grofers/droid-dex?tab=GPL-2.0-1-ov-file#readme)

<br/>

<!--suppress HtmlDeprecatedAttribute -->
<div align="center">

![Droid Dex](./assets/logo.png)

</div>

## Introduction

Droid Dex helps you make smart, device-aware decisions like:

- Delivering high-definition images and advanced animations only to capable devices
- Optimize layouts for mid-tier phones to prevent lag and freezes
- Skip heavy video playback on lower-end hardware to avoid crashes

It is a powerful tool that classifies and analyzes Android device performance across multiple parameters:

| PARAMETER                                                                                                                  | DESCRIPTION                                         |
|----------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------|
| <div align="center">[CPU](./droid-dex/src/main/kotlin/com/blinkit/droiddex/cpu/CpuPerformanceManager.kt)</div>             | Total RAM, Core Count, CPU Frequency                |
| <div align="center">[MEMORY](./droid-dex/src/main/kotlin/com/blinkit/droiddex/memory/MemoryPerformanceManager.kt)</div>    | Heap Limit, Heap Remaining, Available RAM           |
| <div align="center">[NETWORK](./droid-dex/src/main/kotlin/com/blinkit/droiddex/network/NetworkPerformanceManager.kt)</div> | Bandwidth Strength, Download Speed, Signal Strength |
| <div align="center">[STORAGE](./droid-dex/src/main/kotlin/com/blinkit/droiddex/storage/StoragePerformanceManager.kt)</div> | Available Storage                                   |
| <div align="center">[BATTERY](./droid-dex/src/main/kotlin/com/blinkit/droiddex/battery/BatteryPerformanceManager.kt)</div> | Percentage Remaining, If Phone is Charging or Not   |

into various [levels](./droid-dex/src/main/kotlin/com/blinkit/droiddex/constants/PerformanceLevel.kt): **EXCELLENT**,
**HIGH**, **AVERAGE**, **LOW**

Droid Dex enhances your Android application's performance and elevates user experience by addressing key performance
challenges such as Janky scrolling, Out of Memory (OOM) errors, High battery consumption, and Application Not
Responding (ANR) instances.

More Info: https://lambda.blinkit.com/droid-dex-1f807901626f

## Use Cases

1. **Battery-Aware API Polling**: When background polling of an API is required, frequent requests can significantly
   drain the device's battery. Use the `BATTERY` performance level to dynamically adjust polling intervals:

   ```Kotlin
   DroidDex.getPerformanceLevelLd(PerformanceClass.BATTERY).observe(this) {
      // Adjust the polling time interval
   }
   ```

2. **Adaptive Image Quality**: When tailoring image quality based on device capabilities, both `NETWORK` and `MEMORY`
   conditions are important. Better image quality requires larger file sizes (impacting network) and generates heavier
   bitmaps (consuming memory). Optimize image quality delivery using weighted performance levels:

   ```Kotlin
   DroidDex.getWeightedPerformanceLevelLd(PerformanceClass.NETWORK to 2F, PerformanceClass.MEMORY to 1F).observe(this) {
      // Implement image quality optimization
   }
   ```

## Usage

Initialize the library in your Application class using the following code snippet:

```Kotlin
DroidDex.init(this) // Parameter: Application Context
```

1. To get performance level for single/multiple parameters:

    ```Kotlin
    DroidDex.getPerformanceLevel(params)
    ```

   For observing the changes:

    ```Kotlin
    DroidDex.getPerformanceLevelLd(params).observe(this) {
    }
    ```

   Replace `params` with a comma-separated list of `Performance Class(es)`.

   Example:
   ```Kotlin
   DroidDex.getPerformanceLevel(PerformanceClass.CPU, PerformanceClass.MEMORY)
   ```

2. To get performance level for multiple parameters with unequal weights:

    ```Kotlin
    DroidDex.getWeightedPerformanceLevel(params)
    ```

   For observing the changes:

    ```Kotlin
    DroidDex.getWeightedPerformanceLevelLd(params).observe(this) {
    }
    ```

   Replace `params` with a comma-separated list of `Performance Classes` mapped to their `Weights`.

   Example:
   ```Kotlin
   DroidDex.getWeightedPerformanceLevelLd(PerformanceClass.CPU to 2F, PerformanceClass.MEMORY to 1F).observe(this) {
   }
   ```

See [Example Project](example) for further usage

## Setup

<details open>
<summary>For versions 3.+</summary>

The latest release is available on [Maven Central](https://central.sonatype.com/artifact/com.eternal.kits/droid-dex).

```Kotlin
implementation("com.eternal.kits:droid-dex:<<latest_version>>")
```

</details>

<details>
<summary>For versions 2.x and before</summary>

Add this to your `settings.gradle[.kts]` file

```Kotlin
dependencyResolutionManagement {
	repositories {
		maven {
			url = uri("https://maven.pkg.github.com/grofers/*")
			credentials {
				username = "Blinkit"
				password = GITHUB_PERSONAL_ACCESS_TOKEN
			}
		}
	}
}
```

And add this dependency to your project level `build.gradle[.kts]` file:

```Kotlin
implementation("com.blinkit.kits:droid-dex:<<your_version>>")
```

</details>
