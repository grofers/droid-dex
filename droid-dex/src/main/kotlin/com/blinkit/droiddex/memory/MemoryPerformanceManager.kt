package com.blinkit.droiddex.memory

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import com.blinkit.droiddex.constants.PerformanceClass
import com.blinkit.droiddex.constants.PerformanceLevel
import com.blinkit.droiddex.factory.base.PerformanceManager
import com.blinkit.droiddex.factory.providers.PerformanceManagerProvider
import com.blinkit.droiddex.utils.getHeapUsedRatio
import com.blinkit.droiddex.utils.getMemoryClassInMB
import com.blinkit.droiddex.utils.getMemoryInfo
import com.blinkit.droiddex.utils.getTotalRamInGB
import com.blinkit.droiddex.utils.isLowRamDevice
import com.blinkit.droiddex.utils.isProcessStarted
import com.blinkit.droiddex.utils.pollScope
import kotlin.concurrent.Volatile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Reports memory performance as a stable, hardware-derived device tier that live pressure can
 * only downgrade, never raise.
 *
 * Tier: total RAM proposes it, the Java-heap quota ([ActivityManager.getMemoryClass]) can only
 * veto it - a low quota is enforced by ART with an [OutOfMemoryError], while a high one is just
 * an OEM config value that physical RAM may not back.
 *
 * Pressure ([Pressure]) follows the shape of Chromium's Android memory-pressure monitor: polled
 * system state is the primary signal on every API level and the platform trim callbacks only
 * shorten detection latency, since they are unreliable across OEMs and API levels. MODERATE and
 * SEVERE downgrade relative to the tier; CRITICAL - the OS is at its process-killing threshold -
 * floors to LOW outright. Downgrades apply immediately, recoveries must hold for
 * [UPGRADE_STABILITY_IN_MS] so GC churn does not flap the level.
 */
internal class MemoryPerformanceManager(
	applicationContext: Context
): PerformanceManager(applicationContext), ComponentCallbacks2 {

	/** Ordinal order is severity order - comparisons and [maxOf] rely on it. */
	private enum class Pressure { NONE, MODERATE, SEVERE, CRITICAL }

	/** Fixed hardware tier; latched only on a successful measurement. */
	@Volatile
	private var cachedDeviceTier: PerformanceLevel? = null

	// Hysteresis + streak state: only touched inside measurePerformanceLevel(), which the base
	// class always calls under the measureAndPublish() monitor.
	private var lastReportedLevel = PerformanceLevel.UNKNOWN
	private var upgradeCandidateLevel = PerformanceLevel.UNKNOWN
	private var upgradeCandidateSinceMs = 0L
	private var consecutiveSevereHeapSamples = 0
	private var lastCountedSevereHeapSampleAtMs = 0L

	// One timestamp per trim severity (0 = inactive); sole writer is the main thread. Readers never
	// pair two of them, so there is no write-ordering subtlety.
	@Volatile
	private var moderateTrimAtMs = 0L

	@Volatile
	private var severeTrimAtMs = 0L

	@Volatile
	private var criticalTrimAtMs = 0L

	@Volatile
	private var hasPendingImmediateRemeasure = false

	init {
		runCatching { applicationContext.registerComponentCallbacks(this) }.onFailure { logError(it) }
	}

	override fun getPerformanceClass() = PerformanceClass.MEMORY

	override fun getDelayInSecs() = DELAY_IN_SECS

	override fun measurePerformanceLevel(): PerformanceLevel {
		val tier = deviceTier()
		if (tier == PerformanceLevel.UNKNOWN) return PerformanceLevel.UNKNOWN

		val measuredLevel = tier.applyPressure(measurePressure())
		return applyUpgradeHysteresis(measuredLevel)
	}

	private fun deviceTier(): PerformanceLevel = cachedDeviceTier ?: measureDeviceTier().also {
		if (it != PerformanceLevel.UNKNOWN) cachedDeviceTier = it
	}

	private fun measureDeviceTier(): PerformanceLevel {
		val ramTier = measureRamTier()
		val heapQuotaCeiling = measureHeapQuotaCeiling()

		return (listOfNotNull(ramTier, heapQuotaCeiling).minOrNull() ?: PerformanceLevel.UNKNOWN).also {
			logInfo("DEVICE TIER: $it (RAM TIER: $ramTier, HEAP QUOTA CEILING: $heapQuotaCeiling)")
		}
	}

	/** Null when the reading is unavailable: abstain rather than classify LOW on a zeroed MemoryInfo. */
	private fun measureRamTier(): PerformanceLevel? {
		if (isLowRamDevice(applicationContext, logger)) return PerformanceLevel.LOW

		val totalRamInGB = getTotalRamInGB(applicationContext, logger)

		return when {
			totalRamInGB <= 0F -> null

			totalRamInGB < LOW_TIER_MAX_RAM_IN_GB -> PerformanceLevel.LOW

			totalRamInGB < AVERAGE_TIER_MAX_RAM_IN_GB -> PerformanceLevel.AVERAGE

			totalRamInGB >= EXCELLENT_TIER_MIN_RAM_IN_GB || isPremiumCertified() -> PerformanceLevel.EXCELLENT

			else -> PerformanceLevel.HIGH
		}
	}

	/** The heap quota caps the tier but never raises it; null (unavailable) means no veto. */
	private fun measureHeapQuotaCeiling(): PerformanceLevel? {
		val memoryClassInMB = getMemoryClassInMB(applicationContext, logger)

		return when {
			memoryClassInMB <= 0 -> null

			memoryClassInMB <= LOW_TIER_MAX_MEMORY_CLASS_IN_MB -> PerformanceLevel.LOW

			memoryClassInMB <= AVERAGE_TIER_MAX_MEMORY_CLASS_IN_MB -> PerformanceLevel.AVERAGE

			memoryClassInMB < EXCELLENT_TIER_MIN_MEMORY_CLASS_IN_MB -> PerformanceLevel.HIGH

			else -> PerformanceLevel.EXCELLENT
		}
	}

	private fun measurePressure(): Pressure {
		val memoryInfo = getMemoryInfo(applicationContext, logger)
		val heapUsedRatio = getHeapUsedRatio(logger)

		updateSevereHeapStreak(heapUsedRatio)

		val polled = when {
			memoryInfo.lowMemory -> {
				logInfo("PRESSURE: CRITICAL (SYSTEM LOW MEMORY)")
				Pressure.CRITICAL
			}

			consecutiveSevereHeapSamples >= SEVERE_HEAP_CONFIRMATION_SAMPLES -> {
				logInfo("PRESSURE: SEVERE (HEAP USED RATIO: $heapUsedRatio, CONFIRMED)")
				Pressure.SEVERE
			}

			consecutiveSevereHeapSamples > 0 -> {
				// Unconfirmed severe sample: one step now, confirm (or clear) within seconds.
				logInfo("PRESSURE: MODERATE (HEAP USED RATIO: $heapUsedRatio, AWAITING CONFIRMATION)")
				remeasureAsync(delayInMs = SEVERE_CONFIRMATION_DELAY_IN_MS)
				Pressure.MODERATE
			}

			heapUsedRatio >= MODERATE_HEAP_USED_RATIO || isSystemMemoryStrained(memoryInfo) -> {
				logInfo("PRESSURE: MODERATE")
				Pressure.MODERATE
			}

			else -> Pressure.NONE
		}

		return maxOf(polled, activeTrimPressure(memoryInfo))
	}

	/**
	 * A single heap sample can read high on not-yet-collected garbage, so SEVERE needs a confirmed
	 * streak: samples closer together than a GC can plausibly run count once, and a stale streak
	 * (backgrounded polling) restarts rather than pairing samples minutes apart.
	 */
	private fun updateSevereHeapStreak(heapUsedRatio: Float) {
		if (heapUsedRatio < SEVERE_HEAP_USED_RATIO) {
			consecutiveSevereHeapSamples = 0
			lastCountedSevereHeapSampleAtMs = 0L
			return
		}

		val now = SystemClock.elapsedRealtime()
		val sinceLastCountedMs = now - lastCountedSevereHeapSampleAtMs
		when {
			consecutiveSevereHeapSamples == 0 || sinceLastCountedMs > SEVERE_HEAP_STREAK_STALENESS_IN_MS -> {
				consecutiveSevereHeapSamples = 1
				lastCountedSevereHeapSampleAtMs = now
			}

			sinceLastCountedMs >= SEVERE_HEAP_MIN_SAMPLE_SPACING_IN_MS -> {
				consecutiveSevereHeapSamples++
				lastCountedSevereHeapSampleAtMs = now
			}
		}
	}

	/**
	 * Latest-wins downward: each trim is the OS's current assessment, so a milder signal ends any
	 * severer downgrade instead of extending it.
	 */
	override fun onTrimMemory(level: Int) {
		val severity = mapTrimLevelToPressure(level)
		if (severity == Pressure.NONE) return

		val now = SystemClock.elapsedRealtime()
		val previousSeverity = activeRawTrimPressure(now)

		if (severity < Pressure.CRITICAL) criticalTrimAtMs = 0L
		if (severity < Pressure.SEVERE) severeTrimAtMs = 0L

		when (severity) {
			Pressure.CRITICAL -> criticalTrimAtMs = now
			Pressure.SEVERE -> severeTrimAtMs = now
			Pressure.MODERATE -> moderateTrimAtMs = now
			Pressure.NONE -> Unit
		}
		logInfo("TRIM SIGNAL (LEVEL: $level) -> $severity")

		// Storm guard: a refreshed timestamp alone doesn't need an immediate publish. Backgrounded
		// trims are stamps-only: the process is being reclaimed and the main thread is already
		// starved, so even launching a coroutine here can stall past the ANR budget (seen in the
		// field on FCM-woken cold starts). A backgrounded trim therefore does not publish; it leaves a
		// hint that the next foreground measurement consumes if the process returns inside the stamp's
		// validity window (15-30s), and drops otherwise. For a process that never foregrounds - the
		// crash population - the hint always expires unused, i.e. background trim publishing is off;
		// that is fine because nothing reads the level while backgrounded, and the polled state at
		// foreground return (the primary signal for this class) decides it either way.
		if (activeRawTrimPressure(now) != previousSeverity && isProcessInteractive()) remeasureAsync()
	}

	/**
	 * Reads [isProcessStarted], a volatile flag kept by the shared process-lifecycle observer in
	 * runAsyncPeriodically - so the trim path never touches ProcessLifecycleOwner/Lifecycle on the
	 * (possibly starved) main thread, just reads a field.
	 *
	 * STARTED, not RESUMED: this gate only asks whether the main thread is healthy enough to launch
	 * on, and a visible-but-unfocused process (dialog, translucent activity) is. The poll loop's own
	 * RESUMED gate answers a different question - whether the level is worth refreshing at all. The
	 * flag defaults false until the observer registers, so an early trim is treated as backgrounded
	 * (stamp-only) - fail-safe, and the unconditional first poll seeds the level regardless.
	 */
	private fun isProcessInteractive(): Boolean = isProcessStarted

	/**
	 * Routed through [onTrimMemory] as CRITICAL, so a backgrounded onLowMemory is stamps-only too -
	 * deliberate: it is the most severe signal, which makes launching on the starved main thread the
	 * riskiest, and the stamp is still consumed at foreground return like any other trim.
	 */
	@Suppress("DEPRECATION")
	override fun onLowMemory() = onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)

	override fun onConfigurationChanged(newConfig: Configuration) = Unit

	/**
	 * UI_HIDDEN is a lifecycle event, not memory pressure. The deprecated mappings stay as
	 * defensive code for OEM ROMs that still send them.
	 */
	@Suppress("DEPRECATION")
	private fun mapTrimLevelToPressure(level: Int): Pressure = when {
		level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> Pressure.CRITICAL

		level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> Pressure.SEVERE

		level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ->
			if (IS_TRIM_BACKGROUND_LIFECYCLE_ONLY) Pressure.NONE else Pressure.MODERATE

		level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> Pressure.NONE

		level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> Pressure.CRITICAL

		level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> Pressure.MODERATE

		else -> Pressure.NONE
	}

	/** Trim signals expire per-severity rather than pinning the level down forever. */
	private fun activeRawTrimPressure(nowMs: Long = SystemClock.elapsedRealtime()): Pressure = when {
		isTrimActive(criticalTrimAtMs, CRITICAL_TRIM_VALIDITY_IN_MS, nowMs) -> Pressure.CRITICAL

		isTrimActive(severeTrimAtMs, SEVERE_TRIM_VALIDITY_IN_MS, nowMs) -> Pressure.SEVERE

		isTrimActive(moderateTrimAtMs, MODERATE_TRIM_VALIDITY_IN_MS, nowMs) -> Pressure.MODERATE

		else -> Pressure.NONE
	}

	/**
	 * A trim-originated CRITICAL floors to LOW only when the polled system state corroborates it
	 * (pre-34 OEM ROMs fire TRIM_MEMORY_COMPLETE on routine backgrounding); uncorroborated it
	 * counts as SEVERE.
	 */
	private fun activeTrimPressure(memoryInfo: ActivityManager.MemoryInfo): Pressure {
		val severity = activeRawTrimPressure()
		if (severity != Pressure.CRITICAL) return severity

		val isCorroborated =
			memoryInfo.lowMemory || (memoryInfo.threshold > 0 && memoryInfo.availMem <= memoryInfo.threshold)
		return if (isCorroborated) Pressure.CRITICAL else Pressure.SEVERE
	}

	private fun isTrimActive(stampMs: Long, validityMs: Long, nowMs: Long): Boolean =
		stampMs != 0L && nowMs - stampMs <= validityMs

	/**
	 * Publishes without waiting for the next poll tick. Immediate calls are coalesced so trim
	 * bursts cannot queue unbounded measures. Runs on the shared [pollScope] rather than the process
	 * lifecycleScope: the lifecycle-scope lookup and launch walk enough code on the caller thread to
	 * ANR when this fires from a trim callback on a memory-starved device.
	 */
	private fun remeasureAsync(delayInMs: Long = 0L) {
		if (delayInMs == 0L) {
			if (hasPendingImmediateRemeasure) return
			hasPendingImmediateRemeasure = true
		}
		pollScope.launch {
			if (delayInMs > 0L) delay(delayInMs) else hasPendingImmediateRemeasure = false
			measureAndPublish()
		}
	}

	/** Close to the point where the OS starts killing processes; threshold 0 means the signal is unavailable. */
	private fun isSystemMemoryStrained(memoryInfo: ActivityManager.MemoryInfo): Boolean =
		memoryInfo.threshold > 0 && memoryInfo.availMem <= memoryInfo.threshold * SYSTEM_STRAIN_THRESHOLD_MULTIPLIER

	private fun PerformanceLevel.applyPressure(pressure: Pressure): PerformanceLevel = when (pressure) {
		Pressure.NONE -> this

		Pressure.MODERATE -> downgradeBy(MODERATE_DOWNGRADE_STEPS)

		Pressure.SEVERE -> downgradeBy(SEVERE_DOWNGRADE_STEPS)

		// Absolute, not tier-relative: the OS is at its kill threshold and hardware is irrelevant.
		Pressure.CRITICAL -> PerformanceLevel.LOW
	}

	private fun PerformanceLevel.downgradeBy(steps: Int): PerformanceLevel =
		PerformanceLevel.getPerformanceLevel(maxOf(PerformanceLevel.LOW.level, level - steps))

	/** Time-based, not tick-based: event-driven re-measures would inflate a tick counter. */
	private fun applyUpgradeHysteresis(measuredLevel: PerformanceLevel): PerformanceLevel {
		val isFirstMeasurement = lastReportedLevel == PerformanceLevel.UNKNOWN
		if (isFirstMeasurement || measuredLevel.level <= lastReportedLevel.level) {
			resetUpgradeCandidate()
			lastReportedLevel = measuredLevel
			return measuredLevel
		}

		val now = SystemClock.elapsedRealtime()
		if (measuredLevel != upgradeCandidateLevel) {
			upgradeCandidateLevel = measuredLevel
			upgradeCandidateSinceMs = now
			return lastReportedLevel
		}

		if (now - upgradeCandidateSinceMs >= UPGRADE_STABILITY_IN_MS) {
			resetUpgradeCandidate()
			lastReportedLevel = measuredLevel
			return measuredLevel
		}

		return lastReportedLevel
	}

	private fun resetUpgradeCandidate() {
		upgradeCandidateLevel = PerformanceLevel.UNKNOWN
		upgradeCandidateSinceMs = 0L
	}

	companion object: PerformanceManagerProvider {

		override fun create(applicationContext: Context): PerformanceManager =
			MemoryPerformanceManager(applicationContext)

		private const val DELAY_IN_SECS = 10F

		private const val LOW_TIER_MAX_RAM_IN_GB = 2.8F

		// Marketing 6GB reads ~5.5 real -> AVERAGE; marketing 8GB reads ~7.2-7.5 -> HIGH.
		private const val AVERAGE_TIER_MAX_RAM_IN_GB = 6.9F

		// Marketing 12GB reads ~10.8 real (a threshold of 12 would only match 16GB devices).
		private const val EXCELLENT_TIER_MIN_RAM_IN_GB = 10.5F

		private const val LOW_TIER_MAX_MEMORY_CLASS_IN_MB = 128
		private const val AVERAGE_TIER_MAX_MEMORY_CLASS_IN_MB = 160

		// Flagships ship 256+ (Pixels: heapgrowthlimit=256m).
		private const val EXCELLENT_TIER_MIN_MEMORY_CLASS_IN_MB = 256

		private const val MODERATE_HEAP_USED_RATIO = 0.75F
		private const val SEVERE_HEAP_USED_RATIO = 0.90F
		private const val SYSTEM_STRAIN_THRESHOLD_MULTIPLIER = 1.5F

		private const val MODERATE_DOWNGRADE_STEPS = 1
		private const val SEVERE_DOWNGRADE_STEPS = 2

		private const val SEVERE_HEAP_CONFIRMATION_SAMPLES = 2
		private const val SEVERE_CONFIRMATION_DELAY_IN_MS = 3_000L

		// Must stay below SEVERE_CONFIRMATION_DELAY_IN_MS so the confirmation re-poll is countable.
		private const val SEVERE_HEAP_MIN_SAMPLE_SPACING_IN_MS = 2_000L
		private const val SEVERE_HEAP_STREAK_STALENESS_IN_MS = 30_000L

		// Per-severity validity: long enough to bridge to the polled signal, short enough to recover.
		private const val MODERATE_TRIM_VALIDITY_IN_MS = 15_000L
		private const val SEVERE_TRIM_VALIDITY_IN_MS = 30_000L
		private const val CRITICAL_TRIM_VALIDITY_IN_MS = 30_000L

		private const val UPGRADE_STABILITY_IN_MS = 20_000L

		// Android 14+: TRIM_MEMORY_BACKGROUND fires on entering the cached state, not on pressure.
		private val IS_TRIM_BACKGROUND_LIFECYCLE_ONLY = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
	}
}
