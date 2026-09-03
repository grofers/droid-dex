package com.blinkit.droiddex.cpu

import android.content.Context
import android.os.Build
import com.blinkit.droiddex.constants.PerformanceClass
import com.blinkit.droiddex.constants.PerformanceLevel
import com.blinkit.droiddex.cpu.utils.CpuArchitectureScorer
import com.blinkit.droiddex.cpu.utils.CpuInfoManager
import com.blinkit.droiddex.factory.base.PerformanceManager
import com.blinkit.droiddex.factory.providers.PerformanceManagerProvider
import com.blinkit.droiddex.utils.DevicePerformanceProvider
import com.blinkit.droiddex.utils.getMemoryClassInMB
import com.blinkit.droiddex.utils.getTotalRamInGB
import com.blinkit.droiddex.utils.isLowRamDevice
import java.util.Calendar

/**
 * Classifies the device's CPU into a hardware tier.
 *
 * The tier comes from a microarchitecture score ([CpuArchitectureScorer]) with three adjustments: an
 * EXCELLENT tier is capped to HIGH once its flagship silicon generation is a few years old
 * ([capOldFlagship]), so "EXCELLENT can handle every feature" keeps describing current flagships and
 * not once-flagship hardware; a Media-Performance-Class certification overrides the score and is aged
 * the same way ([premiumCertifiedTier]); and platform low-end signals ([measureHardwareCap]) cap the
 * result so a fast SoC paired with starved memory never reports a rich-experience tier.
 *
 * The hardware inputs are fixed, so they are measured once, persisted and served from cache on later
 * launches. Because the score now ages with the calendar, the cache is keyed by both
 * [CACHE_SCHEMA_VERSION] and the current year, so a persisted tier is re-derived at most once a year;
 * the certification gate stays outside the cache (see [applyPremiumGate]). Devices with no readable
 * MIDR info (x86 emulators, restricted procfs) fall back to the hardware cap alone and are not cached.
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
	 * its hardware cap and by the age of the certification ([premiumCertifiedTier]): certification
	 * vouches for the SoC, not for the memory it is paired with, and an old certification no longer
	 * implies a device that can handle every feature.
	 */
	private fun applyPremiumGate(scoredTier: PerformanceLevel, hardwareCap: PerformanceLevel): PerformanceLevel =
		if (isPremiumCertified()) minLevel(hardwareCap, premiumCertifiedTier())
		else minLevel(scoredTier, hardwareCap)

	/**
	 * Ages the certified path the same way the scored path is aged. A Media Performance Class is a
	 * certification against a given Android release, so once that release is
	 * [OLD_FLAGSHIP_MIN_AGE_YEARS]+ years old the device is no longer a current flagship and drops
	 * from EXCELLENT to HIGH. An unrecognised (newer-than-table) class yields no year and stays
	 * EXCELLENT - a just-released device must not be aged.
	 */
	private fun premiumCertifiedTier(): PerformanceLevel {
		val mediaPerformanceClass = DevicePerformanceProvider.get(applicationContext).mediaPerformanceClass
		val releaseYear = ANDROID_RELEASE_YEAR_BY_SDK_INT[mediaPerformanceClass] ?: return PerformanceLevel.EXCELLENT
		return if (currentYear() - releaseYear >= OLD_FLAGSHIP_MIN_AGE_YEARS) PerformanceLevel.HIGH
		else PerformanceLevel.EXCELLENT
	}

	private fun currentYear(): Int = Calendar.getInstance().get(Calendar.YEAR)

	private fun measureMicroarchitectureTier(): PerformanceLevel? {
		val (score, flagshipGenerationYear) = architectureScorer.computeScore(cpuInfoManager.coreMaxFreqsInKHz)
			?: return null
		val scoredTier = when {
			score < LOW_TIER_MAX_SCORE -> PerformanceLevel.LOW
			score < AVERAGE_TIER_MAX_SCORE -> PerformanceLevel.AVERAGE
			score < HIGH_TIER_MAX_SCORE -> PerformanceLevel.HIGH
			else -> PerformanceLevel.EXCELLENT
		}
		return capOldFlagship(scoredTier, flagshipGenerationYear).also {
			logInfo("MICROARCHITECTURE SCORE: $score (flagship generation $flagshipGenerationYear) -> $it")
		}
	}

	/**
	 * Ages the scored path the same way the certified path is aged ([premiumCertifiedTier]): an
	 * EXCELLENT chip whose flagship silicon generation is [OLD_FLAGSHIP_MIN_AGE_YEARS]+ years old is no
	 * longer a current flagship and drops to HIGH. Only EXCELLENT is capped, so a mid-tier chip that
	 * merely shares a core design with an old flagship (e.g. Snapdragon 7 Gen 3's A715) is never
	 * touched; a null year (uncatalogued or unreadable top core) is treated as current and left as-is.
	 */
	private fun capOldFlagship(tier: PerformanceLevel, flagshipGenerationYear: Int?): PerformanceLevel =
		if (tier == PerformanceLevel.EXCELLENT && flagshipGenerationYear != null &&
			currentYear() - flagshipGenerationYear >= OLD_FLAGSHIP_MIN_AGE_YEARS) PerformanceLevel.HIGH
		else tier

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

	/**
	 * Both halves must be present to be usable, so a partial write is treated as no cache at all. The
	 * year is part of the validity check because the scored tier is age-decayed: a tier cached last
	 * year may need to drop this year, so a stale-year cache is ignored and recomputed.
	 */
	private fun readCachedHardwareTiers(): Pair<PerformanceLevel, PerformanceLevel>? = try {
		if (cachePrefs.getInt(KEY_CACHE_SCHEMA_VERSION, 0) == CACHE_SCHEMA_VERSION &&
			cachePrefs.getInt(KEY_CACHE_YEAR, 0) == currentYear()) {
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
				.putInt(KEY_CACHE_YEAR, currentYear())
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
		// EXCELLENT >= 56: current flagship silicon (8 Gen 3 ~82, D9400 ~103, 8 Elite ~130). An
		//                  EXCELLENT tier is then age-capped to HIGH by capOldFlagship once the device's
		//                  flagship silicon generation is OLD_FLAGSHIP_MIN_AGE_YEARS+ years old, so
		//                  once-flagship chips (Tensor G3 ~57, 8 Gen 2 ~63) fall to HIGH while current
		//                  flagships stay EXCELLENT. The raw score is never modified - only the tier.
		private const val LOW_TIER_MAX_SCORE = 20F
		private const val AVERAGE_TIER_MAX_SCORE = 34F
		private const val HIGH_TIER_MAX_SCORE = 56F

		// A device is "old flagship" - no longer assumed able to handle every feature - once its
		// silicon generation (scored path) or certification (MPC path) is this many years old.
		private const val OLD_FLAGSHIP_MIN_AGE_YEARS = 3

		// Android release year per Media Performance Class (the API level it certifies against), used
		// to age the MPC short-circuit. The scored path derives its generation year from core MIDR ids
		// instead; this map covers only the certified path, where MIDR is never consulted. Certification
		// gates at TIRAMISU (see PerformanceManager.MIN_PREMIUM_MEDIA_PERFORMANCE_CLASS), so entries
		// start there; an unmapped (newer) class is treated as current and stays EXCELLENT.
		private val ANDROID_RELEASE_YEAR_BY_SDK_INT: Map<Int, Int> = mapOf(
			Build.VERSION_CODES.TIRAMISU to 2022,          // Android 13
			Build.VERSION_CODES.UPSIDE_DOWN_CAKE to 2023,  // Android 14
			Build.VERSION_CODES.VANILLA_ICE_CREAM to 2024, // Android 15
			Build.VERSION_CODES.BAKLAVA to 2025,           // Android 16
		)

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

		// The calendar year the cache was written in; a stale year invalidates it because the scored
		// tier is age-decayed (see readCachedHardwareTiers).
		private const val KEY_CACHE_YEAR = "cache_year"

		// Only the two hardware-derived inputs are cached; the premium gate is re-applied per read.
		private const val KEY_SCORED_TIER_LEVEL = "scored_tier_level"
		private const val KEY_HARDWARE_CAP_LEVEL = "hardware_cap_level"

		// Bump when the scoring algorithm, core-weight table, tier thresholds, age-decay logic or
		// hardware cap change, so tiers cached by older logic are recomputed.
		private const val CACHE_SCHEMA_VERSION = 1
	}
}
