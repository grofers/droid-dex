package com.blinkit.droiddex.constants

import androidx.annotation.IntDef
import androidx.annotation.Keep

@Keep
@IntDef(
	PerformanceClass.CPU,
	PerformanceClass.MEMORY,
	PerformanceClass.STORAGE,
	PerformanceClass.NETWORK,
	PerformanceClass.BATTERY,
	PerformanceClass.THERMAL
)
@Retention(AnnotationRetention.SOURCE)
@Target(
	AnnotationTarget.PROPERTY,
	AnnotationTarget.VALUE_PARAMETER,
	AnnotationTarget.CLASS,
	AnnotationTarget.TYPE,
	AnnotationTarget.FUNCTION
)
public annotation class PerformanceClass {

	public companion object {

		public const val CPU: Int = 0
		public const val MEMORY: Int = 1
		public const val STORAGE: Int = 2
		public const val NETWORK: Int = 3
		public const val BATTERY: Int = 4
		public const val THERMAL: Int = 5

		internal fun values(): List<Int> = listOf(CPU, MEMORY, STORAGE, NETWORK, BATTERY, THERMAL)

		public fun @PerformanceClass Int.name() = when (this) {
			CPU -> "CPU"
			MEMORY -> "MEMORY"
			STORAGE -> "STORAGE"
			NETWORK -> "NETWORK"
			BATTERY -> "BATTERY"
			THERMAL -> "THERMAL"
			else -> this.toString()
		}
	}
}
