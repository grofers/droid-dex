package com.blinkit.droiddex.utils

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.ProcessLifecycleOwner
import com.blinkit.droiddex.constants.PerformanceLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.math.ceil
import kotlin.math.roundToInt

internal fun convertBytesToMB(value: Long): Float = (value * 1.0F) / (1024 * 1024)

internal fun convertBytesToGB(value: Long): Float = convertBytesToMB(value) / 1024

internal fun floor(value: Float): Int = kotlin.math.floor(value).roundToInt()

internal fun <T: Number> List<T>.average() = if (isNotEmpty()) ceil(sumOf { it.toDouble() } / size) else null

internal fun getPerformanceLevelWithWeights(performanceLevelWithWeights: List<Pair<PerformanceLevel, Float>>): PerformanceLevel {
	var weightedSum = 0F
	var totalWeight = 0F

	performanceLevelWithWeights.filter { it.first != PerformanceLevel.UNKNOWN }.forEach { (level, weight) ->
		weightedSum += level.level * weight
		totalWeight += weight
	}

	return PerformanceLevel.getPerformanceLevel(if (totalWeight != 0F) floor(weightedSum / totalWeight) else 0)
}

internal fun getPerformanceLevelLdWithWeights(
	performanceLevelLdWithWeights: List<Pair<LiveData<PerformanceLevel>, Float>>, onChanged: (PerformanceLevel) -> Unit
): LiveData<PerformanceLevel> {
	return MediatorLiveData<PerformanceLevel>().apply {
		performanceLevelLdWithWeights.forEach { performanceLevelLdWithWeight ->
			addSource(performanceLevelLdWithWeight.first) {
				val performanceLevel = getPerformanceLevelWithWeights(performanceLevelLdWithWeights.mapNotNull {
					it.first.value?.let { performanceLevel -> Pair(performanceLevel, it.second) }
				})
				if (value != performanceLevel) {
					value = performanceLevel
					onChanged(performanceLevel)
				}
			}
		}
	}
}

/**
 * Process-lifetime worker for every periodic measurement and every event-driven remeasure (e.g. the
 * memory manager's trim fast path); deliberately never cancelled.
 */
internal val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** True while the process is RESUMED; written only by [registerForegroundTrackerOnce]'s observer. */
private val isProcessResumed = MutableStateFlow(false)

/**
 * True while the process is at least STARTED (visible). Written only by [registerForegroundTrackerOnce]'s
 * observer; a plain volatile, not a flow, because the only reader ([runAsyncPeriodically]'s consumers'
 * trim gate) reads it synchronously and never suspends on it. Defaults false until the posted observer
 * registers and replays the lifecycle - fail-safe for the gate, which then treats an early trim as
 * backgrounded (stamp-only).
 */
@Volatile
internal var isProcessStarted = false
	private set

/**
 * One observer for all pollers, replacing a repeatOnLifecycle per manager. Always posted, never run
 * inline: init reaches here on the caller thread inside Application.onCreate, and the
 * ProcessLifecycleOwner/Lifecycle class-init it would pull in there is exactly what the startup ANR
 * stacks are parked in. addObserver replays the lifecycle up to the current state, so registering a
 * message later still seeds both the flow and [isProcessStarted] correctly.
 *
 * The post lands on the main thread, so during a stalled startup (the stall this fix targets) the
 * observer registers only after onCreate drains. Until then isProcessResumed stays false and every
 * poller parks after its seed measurement, so the first foreground re-measure can land well after
 * delayInSecs rather than one interval in. Self-healing, not a defect: the parked loops unpark on
 * the next RESUMED.
 */
private val registerForegroundTrackerOnce: Unit by lazy {
	Handler(Looper.getMainLooper()).post {
		ProcessLifecycleOwner.get().lifecycle.addObserver(object: DefaultLifecycleObserver {
			override fun onStart(owner: LifecycleOwner) {
				isProcessStarted = true
			}

			override fun onStop(owner: LifecycleOwner) {
				isProcessStarted = false
			}

			override fun onResume(owner: LifecycleOwner) {
				isProcessResumed.value = true
			}

			override fun onPause(owner: LifecycleOwner) {
				isProcessResumed.value = false
			}
		})
	}
	Unit
}

/** Forces [registerForegroundTrackerOnce]; a bare property read at the call site reads as dead code. */
private fun ensureForegroundTrackerRegistered() {
	registerForegroundTrackerOnce
}

/**
 * Runs [block] once right away (whatever the process state, so a background start still seeds a
 * level), then keeps re-running it every [delayInSecs] while the process is RESUMED. In the
 * background the loop parks at the gate; a resume from there measures at once, but a resume landing
 * mid-delay waits the remainder out - one poll period of staleness at worst.
 *
 * Everything - including the first measurement - runs on [pollScope], never on the caller thread:
 * the previous shape measured synchronously in the caller (seconds of Application.onCreate work on
 * low-tier devices) and set up its polling via the process lifecycleScope, putting per-manager
 * coroutine machinery on the main thread during startup - both showed up as startup ANRs.
 */
internal fun runAsyncPeriodically(block: () -> Unit, delayInSecs: Float) {
	ensureForegroundTrackerRegistered()
	pollScope.launch {
		block()
		while (true) {
			isProcessResumed.first { it }
			block()
			delay((delayInSecs * 1000).toLong())
		}
	}
}
