package com.blinkit.droiddex.utils

import android.app.ActivityManager
import android.content.Context
import kotlin.math.min

internal fun getMemoryInfo(applicationContext: Context, logger: Logger): ActivityManager.MemoryInfo =
	ActivityManager.MemoryInfo().also { getActivityManager(applicationContext, logger)?.getMemoryInfo(it) }

/**
 * <a href="https://stackoverflow.com/a/9428660">Detailed Explanation</a>
 *
 * Max Memory gives the actual limit for the heap
 * Memory Class gives the limit we should be respecting
 *
 * In most cases for non-rooted devices, memory class is a better representation of the limit that should be made.
 * But, it can be changed in rooted devices or devices with developer options switched on. Max Memory cannot be changed.
 * So to get a better limit, we check minimum of memory class and max memory.
 */
internal fun getApproxHeapLimitInMB(applicationContext: Context, logger: Logger): Float {
	val memoryClassInMB = getActivityManager(applicationContext, logger)?.memoryClass?.toFloat() ?: Float.MAX_VALUE
	val maxMemoryInMB = convertBytesToMB(Runtime.getRuntime().maxMemory())

	logger.logDebug("MEMORY CLASS: $memoryClassInMB MB, MAX MEMORY: $maxMemoryInMB MB")

	return min(memoryClassInMB, maxMemoryInMB).also { logger.logDebug("APPROXIMATE HEAP LIMIT: $it MB") }
}

internal fun getApproxHeapRemainingInMB(applicationContext: Context, logger: Logger): Float {
	val heapLimitInMB = getApproxHeapLimitInMB(applicationContext, logger)
	val heapUsedInMB = convertBytesToMB(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())

	logger.logDebug("APPROXIMATE HEAP USED: $heapUsedInMB MB")

	return (heapLimitInMB - heapUsedInMB).also { logger.logDebug("APPROXIMATE HEAP REMAINING: $it MB") }
}

internal fun getTotalRamInGB(applicationContext: Context, logger: Logger) =
	convertBytesToGB(getMemoryInfo(applicationContext, logger).totalMem).also { logger.logDebug("TOTAL RAM: $it GB") }

internal fun getAvailableRamInGB(applicationContext: Context, logger: Logger) = convertBytesToGB(
	getMemoryInfo(applicationContext, logger).availMem
).also { logger.logDebug("AVAILABLE RAM: $it GB") }

private fun getActivityManager(applicationContext: Context, logger: Logger): ActivityManager? =
	applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: run {
		logger.logError(Throwable("ACTIVITY MANAGER IS NULL"))
		null
	}
