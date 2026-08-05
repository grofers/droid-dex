package com.blinkit.droiddex.utils

import android.content.Context
import androidx.core.performance.DevicePerformance
import androidx.core.performance.play.services.PlayServicesDevicePerformance

/**
 * Process-wide [DevicePerformance] singleton. [PlayServicesDevicePerformance] opens a DataStore
 * file, and DataStore forbids two live instances over the same file — constructing one per manager
 * crashes with an [IllegalStateException], so every axis must share this instance.
 */
internal object DevicePerformanceProvider {

	@Volatile
	private var instance: DevicePerformance? = null

	fun get(applicationContext: Context): DevicePerformance =
		instance ?: synchronized(this) {
			instance ?: PlayServicesDevicePerformance(applicationContext.applicationContext).also { instance = it }
		}
}
