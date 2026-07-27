package com.blinkit.droiddex.storage

import android.annotation.SuppressLint
import android.content.Context
import android.os.Environment
import com.blinkit.droiddex.constants.PerformanceClass
import com.blinkit.droiddex.constants.PerformanceLevel
import com.blinkit.droiddex.factory.base.PerformanceManager
import com.blinkit.droiddex.factory.providers.PerformanceManagerProvider
import com.blinkit.droiddex.utils.convertBytesToGB

/** Classifies free external-storage headroom in GB; 0 readable GB means the signal is unavailable. */
internal class StoragePerformanceManager(applicationContext: Context): PerformanceManager(applicationContext) {

	override fun getPerformanceClass() = PerformanceClass.STORAGE

	override fun getDelayInSecs() = DELAY_IN_SECS

	override fun measurePerformanceLevel() = with(getStorageLeftInGB()) {
		when {
			this > 16 -> PerformanceLevel.EXCELLENT
			this > 8 -> PerformanceLevel.HIGH
			this > 4 -> PerformanceLevel.AVERAGE
			this > 0 -> PerformanceLevel.LOW
			else -> PerformanceLevel.UNKNOWN
		}
	}

	@SuppressLint("UsableSpace")
	private fun getStorageLeftInGB(): Float {
		val totalStorageLeft = try {
			convertBytesToGB(Environment.getExternalStorageDirectory().usableSpace)
		} catch (e: SecurityException) {
			logError(e)
			0F
		}

		return totalStorageLeft.also { logDebug("TOTAL STORAGE LEFT: $it GB") }
	}

	companion object: PerformanceManagerProvider {

		override fun create(applicationContext: Context): PerformanceManager = StoragePerformanceManager(applicationContext)

		private const val DELAY_IN_SECS = 600F
	}
}
