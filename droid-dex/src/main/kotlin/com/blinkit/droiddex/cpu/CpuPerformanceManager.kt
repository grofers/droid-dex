package com.blinkit.droiddex.cpu

import android.content.Context
import android.os.Build
import com.blinkit.droiddex.constants.PerformanceClass
import com.blinkit.droiddex.constants.PerformanceLevel
import com.blinkit.droiddex.cpu.utils.CpuArchitectureScorer
import com.blinkit.droiddex.cpu.utils.CpuInfoManager
import com.blinkit.droiddex.factory.base.PerformanceManager
import com.blinkit.droiddex.factory.providers.PerformanceManagerProvider
import com.blinkit.droiddex.utils.getMemoryClassInMB
import com.blinkit.droiddex.utils.getTotalRamInGB
import com.blinkit.droiddex.utils.isLowRamDevice

/**
 * Classifies the device's CPU into a fixed hardware tier.
 *
 * The tier comes from a microarchitecture score ([CpuArchitectureScorer]) with two adjustments: a
 * Media-Performance-Class certification overrides the score, and platform low-end signals
 * ([measureHardwareCap]) cap the result so a fast SoC paired with starved memory never reports a
 * rich-experience tier.
 *
 * Both hardware inputs are fixed, so they are measured once, persisted and served from cache on
 * later launches; the cache invalidates only on a deliberate [CACHE_SCHEMA_VERSION] bump, and the
 * certification gate stays outside it (see [applyPremiumGate]). Devices with no readable MIDR info
 * (x86 emulators, restricted procfs) fall back to the hardware cap alone and are not cached.
 */
internal class CpuPerformanceManager(applicationContext: Context): PerformanceManager(applicationContext) {

	private val cpuInfoManager by lazy { CpuInfoManager(logger) }

	private val architectureScorer by lazy { CpuArchitectureScorer(logger) }

	private val cachePrefs by lazy {
		applicationContext.getSharedPreferences(CACHE_PREFS_NAME, Context.MODE_PRIVATE)
	}

	override fun getPerformanceClass() = PerformanceClass.CPU

	override fun getDelayInSecs() = DELAY_IN_SECS

	override fun measurePerformanceLevel(): PerformanceLevel {
		val (cachedScoredTier, cachedHardwareCap) = readCachedHardwareTiers() ?: return computeDeviceTier()
		return applyPremiumGate(cachedScoredTier, cachedHardwareCap).also { logInfo("DEVICE TIER (CACHED): $it") }
	}

	private fun computeDeviceTier(): PerformanceLevel {
		val hardwareCap = measureHardwareCap()

		// No MIDR info: the hardware cap is the whole answer either way, and the procfs read may
		// succeed on a later launch, so nothing is cached.
		val scoredTier = measureMicroarchitectureTier()
			?: return hardwareCap.also { logInfo("NO MICROARCHITECTURE INFO, DEVICE TIER (NOT CACHED): $it") }

		writeCachedHardwareTiers(scoredTier, hardwareCap)
		return applyPremiumGate(scoredTier, hardwareCap).also { logInfo("DEVICE TIER: $it") }
	}

	/**
	 * Applied per read, never persisted: Play Services resolves the Media Performance Class
	 * asynchronously, so a first-ever launch can read the framework default and miss a real
	 * certification - caching that would make the miss permanent. A certified device is limited by
	 * its hardware cap alone, since certification vouches for the SoC and not for the memory it is
	 * paired with.
	 */
	private fun applyPremiumGate(scoredTier: PerformanceLevel, hardwareCap: PerformanceLevel): PerformanceLevel =
		if (isPremiumCertified()) hardwareCap else minLevel(scoredTier, hardwareCap)

	private fun measureMicroarchitectureTier(): PerformanceLevel? {
		val score = architectureScorer.computeScore(cpuInfoManager.coreMaxFreqsInKHz) ?: return null
		return when {
			score < LOW_TIER_MAX_SCORE -> PerformanceLevel.LOW
			score < AVERAGE_TIER_MAX_SCORE -> PerformanceLevel.AVERAGE
			score < HIGH_TIER_MAX_SCORE -> PerformanceLevel.HIGH
			else -> PerformanceLevel.EXCELLENT
		}.also { logInfo("MICROARCHITECTURE SCORE: $score -> $it") }
	}

	/** Platform low-end signals cap the tier. Memory-class ranges start at 1 because 0 means "unknown". */
	private fun measureHardwareCap(): PerformanceLevel {
		val ramInGB = getTotalRamInGB(applicationContext, logger)
		val memoryClassInMB = getMemoryClassInMB(applicationContext, logger)
		val is64BitCapable = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()

		return when {
			isLowRamDevice(applicationContext, logger) ||
				!is64BitCapable ||
				cpuInfoManager.noOfCores <= LOW_TIER_MAX_CORE_COUNT ||
				(ramInGB > 0F && ramInGB < LOW_TIER_MAX_RAM_IN_GB) ||
				memoryClassInMB in 1..LOW_TIER_MAX_MEMORY_CLASS_IN_MB -> PerformanceLevel.LOW

			(ramInGB > 0F && ramInGB < AVERAGE_TIER_MAX_RAM_IN_GB) ||
				memoryClassInMB in 1..AVERAGE_TIER_MAX_MEMORY_CLASS_IN_MB -> PerformanceLevel.AVERAGE

			else -> PerformanceLevel.EXCELLENT
		}
	}

	/** Both halves must be present to be usable, so a partial write is treated as no cache at all. */
	private fun readCachedHardwareTiers(): Pair<PerformanceLevel, PerformanceLevel>? = try {
		if (cachePrefs.getInt(KEY_CACHE_SCHEMA_VERSION, 0) == CACHE_SCHEMA_VERSION) {
			val scoredTier = readCachedLevel(KEY_SCORED_TIER_LEVEL)
			val hardwareCap = readCachedLevel(KEY_HARDWARE_CAP_LEVEL)
			if (scoredTier != null && hardwareCap != null) scoredTier to hardwareCap else null
		} else null
	} catch (e: Exception) {
		logError(e)
		null
	}

	private fun readCachedLevel(key: String): PerformanceLevel? =
		PerformanceLevel.getPerformanceLevel(cachePrefs.getInt(key, PerformanceLevel.UNKNOWN.level))
			.takeIf { it != PerformanceLevel.UNKNOWN }

	private fun writeCachedHardwareTiers(scoredTier: PerformanceLevel, hardwareCap: PerformanceLevel) {
		try {
			cachePrefs.edit()
				.putInt(KEY_CACHE_SCHEMA_VERSION, CACHE_SCHEMA_VERSION)
				.putInt(KEY_SCORED_TIER_LEVEL, scoredTier.level)
				.putInt(KEY_HARDWARE_CAP_LEVEL, hardwareCap.level)
				.apply()
		} catch (e: Exception) {
			logError(e)
		}
	}

	private fun minLevel(a: PerformanceLevel, b: PerformanceLevel): PerformanceLevel =
		PerformanceLevel.getPerformanceLevel(minOf(a.level, b.level))

	companion object: PerformanceManagerProvider {

		override fun create(applicationContext: Context): PerformanceManager = CpuPerformanceManager(applicationContext)

		// The tier is fixed hardware; the poll only exists to satisfy the periodic-manager contract.
		private const val DELAY_IN_SECS = 600F

		// Microarchitecture score boundaries (score = sum of core-weight x max-GHz over all cores),
		// calibrated against public specs; representative SoCs per band:
		// LOW < 20:        8xA53/A55 designs (Exynos 850 ~19, Unisoc T606 ~18.5)
		// AVERAGE 20-34:   Helio G85 ~22, SD 680 ~27, Helio G99 ~27, SD 695 ~28, Exynos 1280 ~31
		// HIGH 34-56:      SD 778G ~40, SD 888 ~44, Exynos 1480 ~47, 8 Gen 1 ~50, D9200 ~55
		// EXCELLENT >= 56: 2022+ flagship silicon (Tensor G3 ~57, 8 Gen 2 ~63, 8 Elite ~130)
		private const val LOW_TIER_MAX_SCORE = 20F
		private const val AVERAGE_TIER_MAX_SCORE = 34F
		private const val HIGH_TIER_MAX_SCORE = 56F

		private const val LOW_TIER_MAX_CORE_COUNT = 2

		// Real totalMem reads ~10% below marketing size; 3.6 sits between real 3GB (~2.9) and
		// real 4GB (~3.5) readings.
		private const val LOW_TIER_MAX_RAM_IN_GB = 2.8F
		private const val AVERAGE_TIER_MAX_RAM_IN_GB = 3.6F

		// Only small heap budgets are trusted (they reliably flag old/cheap hardware); deliberately
		// looser than MemoryPerformanceManager's cutoffs since that axis owns heap-quota policing.
		private const val LOW_TIER_MAX_MEMORY_CLASS_IN_MB = 100
		private const val AVERAGE_TIER_MAX_MEMORY_CLASS_IN_MB = 160

		private const val CACHE_PREFS_NAME = "com.blinkit.droiddex.cpu_tier_cache"
		private const val KEY_CACHE_SCHEMA_VERSION = "cache_schema_version"

		// Only the two hardware-derived inputs are cached; the premium gate is re-applied per read.
		private const val KEY_SCORED_TIER_LEVEL = "scored_tier_level"
		private const val KEY_HARDWARE_CAP_LEVEL = "hardware_cap_level"

		// Bump when the scoring algorithm, core-weight table, tier thresholds or hardware cap
		// change, so tiers cached by older logic are recomputed.
		private const val CACHE_SCHEMA_VERSION = 1
	}
}
