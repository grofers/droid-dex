package com.blinkit.droiddex.thermal

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.blinkit.droiddex.constants.PerformanceClass
import com.blinkit.droiddex.constants.PerformanceLevel
import com.blinkit.droiddex.factory.base.PerformanceManager
import com.blinkit.droiddex.factory.providers.PerformanceManagerProvider
import kotlin.concurrent.Volatile

/**
 * Classifies how much sustained performance the device can currently deliver before the platform
 * throttles it for heat: the thermal status maps onto the levels, and on API 30+ it is refined with
 * `getThermalHeadroom`, which forecasts throttling early and can only pull the level down.
 *
 * A NONE status with no headroom reading is a device with no thermal HAL rather than a cool device,
 * so it reports UNKNOWN instead of EXCELLENT (see [resolveNoneStatusWithoutHeadroom]) - on API 29,
 * where no headroom API exists to disambiguate, NONE is taken at face value. Below API 29 there is
 * no public thermal API at all, so this axis reports UNKNOWN and `DroidDex` drops it from
 * aggregation.
 */
internal class ThermalPerformanceManager(applicationContext: Context): PerformanceManager(applicationContext) {

	private val powerManager: PowerManager? by lazy {
		applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: run {
			logError(Throwable("POWER MANAGER IS NULL"))
			null
		}
	}

	@Volatile
	private var lastHeadroom = Float.NaN

	/** Seeded a full interval in the past so the first reading is never suppressed. */
	@Volatile
	private var lastHeadroomAtMillis = -HEADROOM_MIN_INTERVAL_IN_MILLIS

	/** Device-provided on API 35+, approximated by the constants below otherwise. */
	@Volatile
	private var headroomThresholds = HeadroomThresholds.DEFAULTS

	override fun getPerformanceClass() = PerformanceClass.THERMAL

	override fun getDelayInSecs() = DELAY_IN_SECS

	override fun measurePerformanceLevel(): PerformanceLevel {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			logDebug("THERMAL STATUS UNAVAILABLE BELOW API 29")
			return PerformanceLevel.UNKNOWN
		}

		val powerManager = powerManager ?: return PerformanceLevel.UNKNOWN
		val performanceLevel = getPerformanceLevelFromStatus(powerManager)

		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			applyHeadroom(performanceLevel, powerManager)
		} else {
			performanceLevel
		}
	}

	@RequiresApi(Build.VERSION_CODES.Q)
	private fun getPerformanceLevelFromStatus(powerManager: PowerManager): PerformanceLevel {
		val thermalStatus = try {
			powerManager.currentThermalStatus
		} catch (e: Exception) {
			logError(e)
			return PerformanceLevel.UNKNOWN
		}

		logDebug("THERMAL STATUS: ${thermalStatus.thermalStatusName()}")

		// Everything at and beyond SEVERE collapses to LOW: the guidance for all of them is the
		// same - shed load now.
		return when (thermalStatus) {
			PowerManager.THERMAL_STATUS_NONE -> PerformanceLevel.EXCELLENT
			PowerManager.THERMAL_STATUS_LIGHT -> PerformanceLevel.HIGH
			PowerManager.THERMAL_STATUS_MODERATE -> PerformanceLevel.AVERAGE
			PowerManager.THERMAL_STATUS_SEVERE,
			PowerManager.THERMAL_STATUS_CRITICAL,
			PowerManager.THERMAL_STATUS_EMERGENCY,
			PowerManager.THERMAL_STATUS_SHUTDOWN -> PerformanceLevel.LOW

			else -> PerformanceLevel.UNKNOWN
		}
	}

	/**
	 * Lowers [performanceLevel] when throttling is forecast within [HEADROOM_FORECAST_IN_SECS];
	 * never raises it.
	 */
	@RequiresApi(Build.VERSION_CODES.R)
	private fun applyHeadroom(performanceLevel: PerformanceLevel, powerManager: PowerManager): PerformanceLevel {
		val headroom = getThermalHeadroom(powerManager)
		if (headroom.isNaN()) return resolveNoneStatusWithoutHeadroom(performanceLevel)

		val thresholds = headroomThresholds
		val ceiling = when {
			headroom >= thresholds.severe -> PerformanceLevel.LOW
			headroom >= thresholds.moderate -> PerformanceLevel.AVERAGE
			headroom >= thresholds.light -> PerformanceLevel.HIGH
			else -> return performanceLevel
		}

		return if (performanceLevel.level > ceiling.level) {
			logDebug("THERMAL HEADROOM CAPPED LEVEL: ${performanceLevel.name} -> ${ceiling.name}")
			ceiling
		} else {
			performanceLevel
		}
	}

	/**
	 * A device without a thermal HAL hardwires its status to NONE and its headroom to NaN, so
	 * reporting EXCELLENT would inflate every aggregate on exactly the budget hardware most prone to
	 * throttling. Any throttling status proves the HAL works and is left untouched.
	 */
	private fun resolveNoneStatusWithoutHeadroom(performanceLevel: PerformanceLevel): PerformanceLevel =
		if (performanceLevel == PerformanceLevel.EXCELLENT) {
			logDebug("THERMAL HEADROOM UNAVAILABLE (NO HAL): NONE STATUS UNTRUSTWORTHY -> UNKNOWN")
			PerformanceLevel.UNKNOWN
		} else {
			performanceLevel
		}

	/**
	 * `getThermalHeadroom` also returns NaN when over-polled, which would masquerade as an
	 * unsupported device, so the reading is cached for [HEADROOM_MIN_INTERVAL_IN_MILLIS]. The
	 * thresholds refresh on the same cadence because API 36+ may change them at runtime.
	 */
	@RequiresApi(Build.VERSION_CODES.R)
	private fun getThermalHeadroom(powerManager: PowerManager): Float {
		val now = SystemClock.elapsedRealtime()
		if (now - lastHeadroomAtMillis < HEADROOM_MIN_INTERVAL_IN_MILLIS) return lastHeadroom

		val headroom = try {
			powerManager.getThermalHeadroom(HEADROOM_FORECAST_IN_SECS)
		} catch (e: Exception) {
			logError(e)
			Float.NaN
		}

		lastHeadroom = headroom
		lastHeadroomAtMillis = now

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
			headroomThresholds = resolveHeadroomThresholds(powerManager)
		}

		logDebug("THERMAL HEADROOM (${HEADROOM_FORECAST_IN_SECS}s FORECAST): $headroom")
		return headroom
	}

	/**
	 * Prefers the device's own `getThermalHeadroomThresholds` so the forecast reacts where this
	 * hardware actually throttles. The map is legitimately partial, so each status falls back to its
	 * constant individually; an inverted merged set falls back entirely.
	 */
	@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
	private fun resolveHeadroomThresholds(powerManager: PowerManager): HeadroomThresholds {
		val deviceThresholds = try {
			powerManager.thermalHeadroomThresholds
		} catch (e: Exception) {
			logError(e)
			return HeadroomThresholds.DEFAULTS
		}

		val resolved = HeadroomThresholds(
			light = deviceThresholds[PowerManager.THERMAL_STATUS_LIGHT] ?: LIGHT_HEADROOM,
			moderate = deviceThresholds[PowerManager.THERMAL_STATUS_MODERATE] ?: MODERATE_HEADROOM,
			severe = deviceThresholds[PowerManager.THERMAL_STATUS_SEVERE] ?: SEVERE_HEADROOM
		)

		if (!resolved.isMonotonic()) {
			logDebug("THERMAL HEADROOM THRESHOLDS NOT MONOTONIC, USING DEFAULTS: $resolved")
			return HeadroomThresholds.DEFAULTS
		}

		if (resolved != headroomThresholds) logDebug("THERMAL HEADROOM THRESHOLDS: $resolved")
		return resolved
	}

	@RequiresApi(Build.VERSION_CODES.Q)
	private fun Int.thermalStatusName(): String = when (this) {
		PowerManager.THERMAL_STATUS_NONE -> "NONE"
		PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
		PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
		PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
		PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
		PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
		PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
		else -> toString()
	}

	/** Headroom values at which LIGHT / MODERATE / SEVERE throttling begins. */
	private data class HeadroomThresholds(val light: Float, val moderate: Float, val severe: Float) {

		/**
		 * Non-decreasing rather than strictly increasing: equal adjacent onsets collapse a band but
		 * keep a usable scale. NaN comparisons are false, so a NaN from a misbehaving HAL fails here.
		 */
		fun isMonotonic() = light <= moderate && moderate <= severe

		companion object {
			val DEFAULTS = HeadroomThresholds(LIGHT_HEADROOM, MODERATE_HEADROOM, SEVERE_HEADROOM)
		}
	}

	companion object: PerformanceManagerProvider {

		override fun create(applicationContext: Context): PerformanceManager =
			ThermalPerformanceManager(applicationContext)

		private const val DELAY_IN_SECS = 30F

		/** How far ahead headroom is forecast. Short windows are the accurate ones. */
		private const val HEADROOM_FORECAST_IN_SECS = 10

		/** Google's guidance: polling `getThermalHeadroom` faster than every 10s can return NaN. */
		private const val HEADROOM_MIN_INTERVAL_IN_MILLIS = 10_000L

		// Fallback headroom bands. 1.0 = onset of SEVERE is the only framework-guaranteed anchor;
		// the two bands below it approximate a curve that is really set per device by the OEM.
		private const val SEVERE_HEADROOM = 1F
		private const val MODERATE_HEADROOM = 0.95F
		private const val LIGHT_HEADROOM = 0.85F
	}
}
