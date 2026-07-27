package com.blinkit.droiddex.utils

import android.app.ActivityManager
import android.content.Context

internal fun getMemoryInfo(applicationContext: Context, logger: Logger): ActivityManager.MemoryInfo =
	ActivityManager.MemoryInfo().also { getActivityManager(applicationContext, logger)?.getMemoryInfo(it) }

/**
 * <a href="https://stackoverflow.com/a/9428660">Detailed Explanation</a>
 *
 * Max Memory gives the actual limit for the heap of this process. NOTE: this honors android:largeHeap,
 * so it must never be used to classify device hardware; use [getMemoryClassInMB] for that instead.
 */
internal fun getApproxHeapLimitInMB(logger: Logger): Float =
	convertBytesToMB(Runtime.getRuntime().maxMemory()).also { logger.logDebug("APPROXIMATE HEAP LIMIT: $it MB") }

/**
 * Fraction of the process heap limit currently in use (0.0 to 1.0). Being a ratio, it stays
 * comparable across devices and is unaffected by android:largeHeap inflating the absolute limit.
 */
internal fun getHeapUsedRatio(logger: Logger): Float {
	val heapLimitInMB = getApproxHeapLimitInMB(logger)
	val heapUsedInMB = convertBytesToMB(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())

	if (heapLimitInMB <= 0F) return 0F

	return (heapUsedInMB / heapLimitInMB).also { logger.logDebug("HEAP USED RATIO: $it") }
}

/**
 * The standard per-app heap budget in MB, set by the OEM from the device's hardware profile.
 * Unlike [getApproxHeapLimitInMB], this is NOT affected by android:largeHeap, which makes it a
 * stable device-capability signal (same signal Telegram uses for its performance class).
 *
 * Returns 0 when ActivityManager is unavailable; callers must treat 0 as "unknown".
 */
internal fun getMemoryClassInMB(applicationContext: Context, logger: Logger): Int =
	(getActivityManager(applicationContext, logger)?.memoryClass ?: 0).also { logger.logDebug("MEMORY CLASS: $it MB") }

internal fun isLowRamDevice(applicationContext: Context, logger: Logger): Boolean =
	(getActivityManager(applicationContext, logger)?.isLowRamDevice ?: false).also {
		logger.logDebug("IS LOW RAM DEVICE: $it")
	}

internal fun getTotalRamInGB(applicationContext: Context, logger: Logger) =
	convertBytesToGB(getMemoryInfo(applicationContext, logger).totalMem).also { logger.logDebug("TOTAL RAM: $it GB") }

private fun getActivityManager(applicationContext: Context, logger: Logger): ActivityManager? =
	applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: run {
		logger.logError(Throwable("ACTIVITY MANAGER IS NULL"))
		null
	}
