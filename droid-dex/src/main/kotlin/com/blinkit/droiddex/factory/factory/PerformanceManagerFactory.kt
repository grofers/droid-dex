package com.blinkit.droiddex.factory.factory

import android.content.Context
import androidx.lifecycle.LiveData
import com.blinkit.droiddex.battery.BatteryPerformanceManager
import com.blinkit.droiddex.constants.PerformanceClass
import com.blinkit.droiddex.constants.PerformanceLevel
import com.blinkit.droiddex.cpu.CpuPerformanceManager
import com.blinkit.droiddex.factory.base.PerformanceManager
import com.blinkit.droiddex.memory.MemoryPerformanceManager
import com.blinkit.droiddex.network.NetworkPerformanceManager
import com.blinkit.droiddex.storage.StoragePerformanceManager

internal class PerformanceManagerFactory(private val applicationContext: Context, private val isInDebugMode: Boolean) {

	private val performanceManagerMap = mutableMapOf<@PerformanceClass Int, PerformanceManager>()

	init {
		PerformanceClass.values().forEach { getOrPut(it) }
	}

	fun getPerformanceLevel(@PerformanceClass performanceClass: Int): PerformanceLevel =
		getOrPut(performanceClass).performanceLevel

	fun getPerformanceLevelLd(@PerformanceClass performanceClass: Int): LiveData<PerformanceLevel> =
		getOrPut(performanceClass).performanceLevelLd

	private fun getOrPut(@PerformanceClass performanceClass: Int): PerformanceManager =
		performanceManagerMap.getOrPut(performanceClass) {
			when (performanceClass) {
				PerformanceClass.CPU -> CpuPerformanceManager.create(applicationContext, isInDebugMode)
				PerformanceClass.MEMORY -> MemoryPerformanceManager.create(applicationContext, isInDebugMode)
				PerformanceClass.STORAGE -> StoragePerformanceManager.create(applicationContext, isInDebugMode)
				PerformanceClass.NETWORK -> NetworkPerformanceManager.create(applicationContext, isInDebugMode)
				PerformanceClass.BATTERY -> BatteryPerformanceManager.create(applicationContext, isInDebugMode)
				else -> throw IllegalArgumentException("NO SUCH PERFORMANCE CLASS EXISTS: $performanceClass")
			}.apply { init() }
		}
}
