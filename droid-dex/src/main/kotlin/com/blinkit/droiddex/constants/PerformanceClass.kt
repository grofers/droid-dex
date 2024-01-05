package com.blinkit.droiddex.constants

import androidx.annotation.IntDef
import androidx.annotation.Keep

@Keep
@IntDef(
	PerformanceClass.CPU,
	PerformanceClass.MEMORY,
	PerformanceClass.STORAGE,
	PerformanceClass.NETWORK,
	PerformanceClass.BATTERY
)
@Retention(AnnotationRetention.SOURCE)
@Target(
	AnnotationTarget.PROPERTY,
	AnnotationTarget.VALUE_PARAMETER,
	AnnotationTarget.CLASS,
	AnnotationTarget.TYPE,
	AnnotationTarget.FUNCTION
)
annotation class PerformanceClass {

	companion object {

		const val CPU = 0
		const val MEMORY = 1
		const val STORAGE = 2
		const val NETWORK = 3
		const val BATTERY = 4

		internal fun values(): List<Int> = listOf(CPU, MEMORY, STORAGE, NETWORK, BATTERY)

		fun @PerformanceClass Int.name() = when (this) {
			CPU -> "CPU"
			MEMORY -> "MEMORY"
			STORAGE -> "STORAGE"
			NETWORK -> "NETWORK"
			BATTERY -> "BATTERY"
			else -> this.toString()
		}
	}
}
