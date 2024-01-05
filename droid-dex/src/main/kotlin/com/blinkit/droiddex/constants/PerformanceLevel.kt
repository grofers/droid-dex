package com.blinkit.droiddex.constants

import androidx.annotation.Keep

@Keep
enum class PerformanceLevel(val level: Int) {

	UNKNOWN(0), LOW(1), AVERAGE(2), HIGH(3), EXCELLENT(4);

	companion object {

		internal fun getPerformanceLevel(level: Int? = null): PerformanceLevel =
			PerformanceLevel.values().find { it.level == level } ?: UNKNOWN
	}
}
