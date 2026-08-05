package com.blinkit.droiddex.factory.base

import android.content.Context
import android.os.Build
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.blinkit.droiddex.constants.PerformanceClass
import com.blinkit.droiddex.constants.PerformanceLevel
import com.blinkit.droiddex.utils.DevicePerformanceProvider
import com.blinkit.droiddex.utils.Logger
import com.blinkit.droiddex.utils.runAsyncPeriodically
import kotlin.concurrent.Volatile

internal abstract class PerformanceManager(protected val applicationContext: Context) {

	@Volatile
	var performanceLevel = PerformanceLevel.UNKNOWN
		private set

	private val _performanceLevelLd = MutableLiveData(PerformanceLevel.UNKNOWN)
	val performanceLevelLd: LiveData<PerformanceLevel>
		get() = _performanceLevelLd

	protected val logger by lazy { Logger(getPerformanceClass()) }

	fun init() {
		runAsyncPeriodically({ measureAndPublish() }, delayInSecs = getDelayInSecs())
	}

	/**
	 * Measures once and publishes the result if it changed. Shared by the periodic poller and by
	 * event-driven re-measures (e.g. the memory manager's onTrimMemory fast path), which must not
	 * interleave. A JVM lock (not a coroutine Mutex) is intentional: nothing inside suspends, so
	 * the monitor is only held across plain blocking work — keep it that way.
	 */
	@Synchronized
	protected fun measureAndPublish() {
		try {
			measurePerformanceLevel().also {
				// Compared against the field, not performanceLevelLd.value: postValue applies
				// asynchronously, so the LiveData can lag and a rapid second call would re-post
				// a duplicate.
				val hasPerformanceLevelChanged = performanceLevel != it
				if (hasPerformanceLevelChanged) {
					performanceLevel = it
					_performanceLevelLd.postValue(it)
				}
				logger.logPerformanceLevelChange(it, hasPerformanceLevelChanged)
			}
		} catch (e: Exception) {
			logger.logError(e)
		}
	}

	@PerformanceClass
	protected abstract fun getPerformanceClass(): Int

	protected abstract fun getDelayInSecs(): Float

	protected abstract fun measurePerformanceLevel(): PerformanceLevel

	/**
	 * Whether the OEM certified this device for Media Performance Class
	 * [MIN_PREMIUM_MEDIA_PERFORMANCE_CLASS]+ (flagship class). Lives in the base so every axis that
	 * needs a premium-hardware gate shares one definition.
	 *
	 * Play Services resolves the real value asynchronously; until that first resolution is
	 * persisted, this can read the framework default (usually 0) and report false on the very
	 * first launch.
	 */
	protected fun isPremiumCertified(): Boolean =
		DevicePerformanceProvider.get(applicationContext).mediaPerformanceClass >= MIN_PREMIUM_MEDIA_PERFORMANCE_CLASS

	protected fun logInfo(message: String) = logger.logInfo(message)

	protected fun logDebug(message: String) = logger.logDebug(message)

	protected fun logError(throwable: Throwable) = logger.logError(throwable)

	private companion object {

		private const val MIN_PREMIUM_MEDIA_PERFORMANCE_CLASS = Build.VERSION_CODES.TIRAMISU
	}
}
