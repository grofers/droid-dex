package com.blinkit.droiddex.cpu

import android.content.Context
import android.os.Build
import com.blinkit.droiddex.BuildConfig
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
 * not once-flagship hardware; a Media-Performance-Class certification short-circuits the score while it
 * is current and, once aged out, caps a chip the score cannot date (Oryon) while deferring to the score
 * for one it can ([premiumCertifiedTier]); and platform low-end signals ([measureHardwareCap]) cap the
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
		val (cachedScoredTier, cachedScoredYear, cachedHardwareCap) = readCachedHardwareTiers() ?: return computeDeviceTier()
		return applyPremiumGate(cachedScoredTier, cachedScoredYear, cachedHardwareCap)
			.also { logInfo("DEVICE TIER (CACHED): $it") }
	}

	private fun computeDeviceTier(): PerformanceLevel {
		val hardwareCap = measureHardwareCap()

		// No MIDR info (x86 emulator, restricted procfs): an undatable chip (null year), so the premium
		// gate ages it via the certification alone. hardwareCap stands in for the absent scoredTier (a
		// minLevel no-op). The procfs read may succeed later, so nothing is cached.
		val (scoredTier, scoredYear) = measureMicroarchitectureTier()
			?: return applyPremiumGate(hardwareCap, null, hardwareCap)
				.also { logInfo("NO MICROARCHITECTURE INFO, DEVICE TIER (NOT CACHED): $it") }

		writeCachedHardwareTiers(scoredTier, scoredYear, hardwareCap)
		return applyPremiumGate(scoredTier, scoredYear, hardwareCap).also { logInfo("DEVICE TIER: $it") }
	}

	/**
	 * Applied per read, never persisted: Play Services resolves the Media Performance Class
	 * asynchronously, so a first-ever launch can read the framework default and miss a real
	 * certification - caching that would make the miss permanent. A current certification short-circuits
	 * the score to the hardware cap (it vouches for the SoC, not the memory). Once aged out
	 * ([premiumCertifiedTier]) it caps the tier, but only for a chip the score could not date (null
	 * [scoredGenerationYear] - Oryon, uncatalogued, or no-MIDR): a datable chip has already self-aged
	 * through [capOldFlagship], so it defers to the score, and a current flagship that merely
	 * under-declares its class is not demoted.
	 */
	private fun applyPremiumGate(
		scoredTier: PerformanceLevel, scoredGenerationYear: Int?, hardwareCap: PerformanceLevel
	): PerformanceLevel {
		if (!isPremiumCertified()) return minLevel(scoredTier, hardwareCap)
		val certifiedTier = premiumCertifiedTier()
		if (certifiedTier == PerformanceLevel.EXCELLENT) return hardwareCap
		// Certification has aged out. A datable chip trusts the score (already self-aged); an undatable
		// one (null year) has no scored age, so the aged class caps it.
		return if (scoredGenerationYear != null) minLevel(scoredTier, hardwareCap)
		else minLevel(minLevel(scoredTier, hardwareCap), certifiedTier)
	}

	/**
	 * Whether the certification is still current or has aged out. Keyed by the device-ship year the
	 * Media Performance Class maps to ([FLAGSHIP_SHIP_YEAR_BY_SDK_INT]) on the same convention as the
	 * scored path, so both paths age alike. An unrecognised (newer-than-table) class yields no year and
	 * stays EXCELLENT - a just-released device must not be aged. This only decides whether the
	 * certification short-circuit still holds; an aged-out class then caps an undatable chip and defers
	 * to the score for a datable one (see [applyPremiumGate]).
	 */
	private fun premiumCertifiedTier(): PerformanceLevel {
		val mediaPerformanceClass = DevicePerformanceProvider.get(applicationContext).mediaPerformanceClass
		val shipYear = FLAGSHIP_SHIP_YEAR_BY_SDK_INT[mediaPerformanceClass] ?: return PerformanceLevel.EXCELLENT
		return if (isOldFlagship(shipYear)) PerformanceLevel.HIGH else PerformanceLevel.EXCELLENT
	}

	private fun currentYear(): Int = Calendar.getInstance().get(Calendar.YEAR)
		.takeIf { it in LIBRARY_BUILD_YEAR..LIBRARY_BUILD_YEAR + MAX_LIBRARY_AGE_YEARS } ?: LIBRARY_BUILD_YEAR

	/** A silicon generation or certification is "old flagship" once it is [OLD_FLAGSHIP_MIN_AGE_YEARS]+ years old. */
	private fun isOldFlagship(generationYear: Int): Boolean =
		currentYear() - generationYear >= OLD_FLAGSHIP_MIN_AGE_YEARS

	private fun measureMicroarchitectureTier(): Pair<PerformanceLevel, Int?>? {
		val (score, flagshipGenerationYear) = architectureScorer.computeScore(cpuInfoManager.coreMaxFreqsInKHz)
			?: return null
		val scoredTier = when {
			score < LOW_TIER_MAX_SCORE -> PerformanceLevel.LOW
			score < AVERAGE_TIER_MAX_SCORE -> PerformanceLevel.AVERAGE
			score < HIGH_TIER_MAX_SCORE -> PerformanceLevel.HIGH
			else -> PerformanceLevel.EXCELLENT
		}
		return (capOldFlagship(scoredTier, flagshipGenerationYear) to flagshipGenerationYear).also {
			logInfo("MICROARCHITECTURE SCORE: $score (flagship generation $flagshipGenerationYear) -> ${it.first}")
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
		if (tier == PerformanceLevel.EXCELLENT && flagshipGenerationYear != null && isOldFlagship(flagshipGenerationYear))
			PerformanceLevel.HIGH
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
	 * Both tier levels must be present to be usable, so a partial write is treated as no cache at all;
	 * [KEY_SCORED_GENERATION_YEAR] is exempt - 0/absent legitimately means an undatable chip, read as
	 * null rather than guarded on. The calendar year is part of the validity check because the scored
	 * tier is age-decayed: a tier cached last year may need to drop this year, so a stale-year cache is
	 * ignored and recomputed.
	 */
	private fun readCachedHardwareTiers(): Triple<PerformanceLevel, Int?, PerformanceLevel>? = try {
		if (cachePrefs.getInt(KEY_CACHE_SCHEMA_VERSION, 0) == CACHE_SCHEMA_VERSION &&
			cachePrefs.getInt(KEY_CACHE_YEAR, 0) == currentYear()) {
			val scoredTier = readCachedLevel(KEY_SCORED_TIER_LEVEL)
			val hardwareCap = readCachedLevel(KEY_HARDWARE_CAP_LEVEL)
			val scoredYear = cachePrefs.getInt(KEY_SCORED_GENERATION_YEAR, NO_GENERATION_YEAR)
				.takeIf { it != NO_GENERATION_YEAR }
			if (scoredTier != null && hardwareCap != null) Triple(scoredTier, scoredYear, hardwareCap) else null
		} else null
	} catch (e: Exception) {
		logError(e)
		null
	}

	private fun readCachedLevel(key: String): PerformanceLevel? =
		PerformanceLevel.getPerformanceLevel(cachePrefs.getInt(key, PerformanceLevel.UNKNOWN.level))
			.takeIf { it != PerformanceLevel.UNKNOWN }

	private fun writeCachedHardwareTiers(scoredTier: PerformanceLevel, scoredYear: Int?, hardwareCap: PerformanceLevel) {
		try {
			cachePrefs.edit()
				.putInt(KEY_CACHE_SCHEMA_VERSION, CACHE_SCHEMA_VERSION)
				.putInt(KEY_CACHE_YEAR, currentYear())
				.putInt(KEY_SCORED_TIER_LEVEL, scoredTier.level)
				.putInt(KEY_SCORED_GENERATION_YEAR, scoredYear ?: NO_GENERATION_YEAR)
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

		// The device clock is untrusted (user-settable) but drives aging and the year-keyed cache, so
		// currentYear() clamps it to a plausible window: below the build year is impossible, and a
		// library more than MAX_LIBRARY_AGE_YEARS stale stops dating new cores anyway. An out-of-window
		// read falls to LIBRARY_BUILD_YEAR - the least-aging plausible value - so a bad clock never
		// wrongly demotes. LIBRARY_BUILD_YEAR is stamped at build time (BuildConfig), so it needs no
		// manual bump; even a stale value only under-ages, which is the fail-safe direction.
		private val LIBRARY_BUILD_YEAR = BuildConfig.LIBRARY_BUILD_YEAR
		private const val MAX_LIBRARY_AGE_YEARS = 3

		// Device-ship year of the flagship generation that first ships with each Media Performance Class,
		// on the SAME convention as the scored path's KNOWN_CORE_DESIGNS years (MPC 33 / Android 13 ->
		// Galaxy S23 gen, 2023) - not the Android release year, which runs a year ahead. Covers only the
		// certified path (MIDR is never consulted here). Gates at TIRAMISU (see
		// PerformanceManager.MIN_PREMIUM_MEDIA_PERFORMANCE_CLASS); an unmapped (newer) class stays EXCELLENT.
		private val FLAGSHIP_SHIP_YEAR_BY_SDK_INT: Map<Int, Int> = mapOf(
			Build.VERSION_CODES.TIRAMISU to 2023,          // Android 13 -> Galaxy S23 generation
			Build.VERSION_CODES.UPSIDE_DOWN_CAKE to 2024,  // Android 14 -> Galaxy S24 generation
			Build.VERSION_CODES.VANILLA_ICE_CREAM to 2025, // Android 15 -> 2025 flagships
			Build.VERSION_CODES.BAKLAVA to 2026,           // Android 16 -> 2026 flagships
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

		// The hardware-derived inputs are cached; the premium gate is re-applied per read.
		private const val KEY_SCORED_TIER_LEVEL = "scored_tier_level"
		private const val KEY_HARDWARE_CAP_LEVEL = "hardware_cap_level"

		// The scored flagship generation year (hardware-stable; distinct from KEY_CACHE_YEAR, the
		// calendar year the cache was written). NO_GENERATION_YEAR (0, never a real year) means an
		// undatable chip (Oryon/uncatalogued), which the premium gate reads as "age via certification".
		private const val KEY_SCORED_GENERATION_YEAR = "scored_generation_year"
		private const val NO_GENERATION_YEAR = 0

		// Bump when the scoring algorithm, core-weight table, tier thresholds, age-decay logic or
		// hardware cap change, so tiers cached by older logic are recomputed.
		private const val CACHE_SCHEMA_VERSION = 3
	}
}
