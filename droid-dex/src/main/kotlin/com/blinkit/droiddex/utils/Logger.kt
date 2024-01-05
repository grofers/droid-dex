package com.blinkit.droiddex.utils

import com.blinkit.droiddex.constants.PerformanceClass
import com.blinkit.droiddex.constants.PerformanceClass.Companion.name
import com.blinkit.droiddex.constants.PerformanceLevel
import timber.log.Timber

internal class Logger(var isInDebugMode: Boolean = false, @PerformanceClass private val performanceClass: Int? = null) {

	fun logPerformanceLevelChange(level: PerformanceLevel, hasPerformanceLevelChanged: Boolean) {
		if (hasPerformanceLevelChanged || isInDebugMode) logInfo("PERFORMANCE LEVEL: ${level.name}")
	}

	fun logPerformanceLevelResult(
		vararg classes: Pair<@PerformanceClass Int, Float>, performanceLevel: PerformanceLevel
	) {
		logDebug("PERFORMANCE CLASSES: ${
			classes.joinToString(", ") { it.first.name() + if (it.second != 1F) "(WEIGHT: ${it.second})" else "" }
		} | PERFORMANCE LEVEL: $performanceLevel")
	}

	fun logInfo(message: String) {
		getTimber().i(beautifyMessage(message))
	}

	fun logDebug(message: String) {
		getTimber().d(beautifyMessage(message))
	}

	fun logError(exception: Throwable) {
		getTimber().e(exception)
	}

	private fun getTimber() = Timber.tag("DROID-DEX")

	private fun beautifyMessage(message: String): String =
		performanceClass?.let { "${it.name()} | $message" } ?: message
}
