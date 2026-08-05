package com.blinkit.droiddex.cpu.utils

import com.blinkit.droiddex.utils.Logger
import com.blinkit.droiddex.utils.average
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.lang.Integer.max
import java.util.regex.Pattern

internal class CpuInfoManager(private val logger: Logger) {

	private val coresFreqList = mutableListOf<CoreFreq>()

	val noOfCores: Int by lazy {
		max(
			runCatching {
				val files = File(CPU_INFO_PATH).listFiles { pathname -> Pattern.matches("cpu[0-9]+", pathname.name) }
				max(1, files?.size ?: 1)
			}.getOrDefault(1), Runtime.getRuntime().availableProcessors()
		)
	}

	/** Per-core cpuinfo_max_freq in kHz, index-aligned with cpu0..cpuN; 0 means unknown. */
	val coreMaxFreqsInKHz: List<Long>
		get() = coresFreqList.map { it.max }

	/** Per-core cpuinfo_min_freq in kHz, index-aligned with cpu0..cpuN; 0 means unknown. */
	val coreMinFreqsInKHz: List<Long>
		get() = coresFreqList.map { it.min }

	val currentCpuUsage: Int
		get() = (coresFreqList.map { it.currentUsagePercent }.average()?.toInt()
			?: 0).also { logger.logDebug("CURRENT USAGE: ${it}%") }

	val maxCpuFreqInMHz: Int
		get() = averageFreqInMHz(coreMaxFreqsInKHz).also { logger.logDebug("MAX CPU FREQUENCY: $it MHz") }

	val minCpuFreqInMHz: Int
		get() = averageFreqInMHz(coreMinFreqsInKHz).also { logger.logDebug("MIN CPU FREQUENCY: $it MHz") }

	init {
		for (i in 0 until noOfCores) coresFreqList.add(CoreFreq(i))
	}

	/** Mean of the per-core frequencies in MHz; [Int.MAX_VALUE] when no frequency is readable. */
	private fun averageFreqInMHz(coreFreqsInKHz: List<Long>): Int =
		coreFreqsInKHz.average()?.toLong()?.takeIf { it > 0 }?.div(KHZ_PER_MHZ)?.toInt() ?: Int.MAX_VALUE

	private class CoreFreq(private val index: Int) {

		var min = 0L
			get() = field.takeIf { it > 0L } ?: getMinFreq().also { field = it }
			private set

		var max = 0L
			get() = field.takeIf { it > 0L } ?: getMaxFreq().also { field = it }
			private set

		private var cur = 0L

		init {
			min = getMinFreq()
			max = getMaxFreq()
		}

		val currentUsagePercent: Int
			get() {
				cur = getCurFreq()

				var percent = 0
				if (max - min > 0 && max > 0 && cur > 0) {
					percent = ((cur - min) * 100 / (max - min)).toInt()
				}

				return max(percent, 0)
			}

		private fun getCurFreq() = readFile("${CPU_INFO_PATH}cpu$index/cpufreq/scaling_cur_freq")

		private fun getMinFreq() = readFile("${CPU_INFO_PATH}cpu$index/cpufreq/cpuinfo_min_freq")

		private fun getMaxFreq() = readFile("${CPU_INFO_PATH}cpu$index/cpufreq/cpuinfo_max_freq")

		private fun readFile(path: String): Long = try {
			BufferedReader(FileReader(path)).use { reader -> reader.readLine()?.toLongOrNull() }
		} catch (_: Exception) {
			null
		} ?: 0
	}

	companion object {

		private const val CPU_INFO_PATH = "/sys/devices/system/cpu/"

		private const val KHZ_PER_MHZ = 1000
	}
}
