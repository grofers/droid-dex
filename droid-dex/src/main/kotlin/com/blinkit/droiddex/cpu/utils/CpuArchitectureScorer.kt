package com.blinkit.droiddex.cpu.utils

import com.blinkit.droiddex.utils.Logger
import java.io.File

/**
 * Estimates CPU capability from the device's core microarchitectures. Every ARM core exposes its
 * design (MIDR) in /proc/cpuinfo as "CPU implementer" + "CPU part"; unlike core count or clock
 * speed, the core design cannot be faked by OEMs.
 *
 * The score is a synthetic throughput estimate: sum over all cores of (relative single-thread
 * weight of the core design) x (max frequency in GHz). A design absent from [KNOWN_CORE_WEIGHTS] is
 * newer than the table, so it scores as the newest known core of its cluster role - future SoCs
 * tier correctly without a library update.
 */
internal class CpuArchitectureScorer(
	private val logger: Logger, private val cpuInfoPath: String = CPU_INFO_PATH
) {

	/**
	 * @param coreMaxFreqsInKHz per-core cpuinfo_max_freq, index-aligned with cpu0..cpuN; <= 0 means unknown
	 * @return the synthetic throughput score, or null when no MIDR info is readable (x86 emulator,
	 * hidden procfs) - callers must then fall back to a heuristic that does not need it
	 */
	fun computeScore(coreMaxFreqsInKHz: List<Long>): Float? = try {
		val midrIdsByCore = readCpuInfoText()?.let(::parseMidrIds).orEmpty()
		if (midrIdsByCore.isEmpty()) null else computeScore(coreMaxFreqsInKHz, midrIdsByCore)
	} catch (e: Exception) {
		logger.logError(e)
		null
	}

	private fun computeScore(coreMaxFreqsInKHz: List<Long>, midrIdsByCore: Map<Int, MidrId>): Float? {
		val coreCount = maxOf(coreMaxFreqsInKHz.size, midrIdsByCore.keys.max() + 1)

		val freqsInKHz = LongArray(coreCount) { maxOf(coreMaxFreqsInKHz.getOrElse(it) { 0L }, 0L) }
		if (!repairUnknownFrequencies(freqsInKHz, midrIdsByCore)) return null

		val deviceMaxFreqInKHz = freqsInKHz.max()
		val topFreqCoreCount = freqsInKHz.count { it >= PRIME_ROLE_MIN_RELATIVE_FREQ * deviceMaxFreqInKHz }

		var score = 0F
		for (core in 0 until coreCount) {
			val midrId = midrIdsByCore[core] ?: findClusterMateMidrId(core, freqsInKHz, midrIdsByCore)
			val weight = midrId?.let { KNOWN_CORE_WEIGHTS[it] } ?: run {
				val roleWeight = roleBasedWeight(freqsInKHz[core], deviceMaxFreqInKHz, topFreqCoreCount)
				val coreDesign = midrId?.let { "UNKNOWN CORE DESIGN $it" } ?: "NO CORE DESIGN"
				logger.logDebug("$coreDesign ON CPU$core, ROLE-BASED WEIGHT: $roleWeight")
				roleWeight
			}
			score += weight * freqsInKHz[core] / KHZ_PER_GHZ
		}
		return score.also { logger.logDebug("MICROARCHITECTURE SCORE: $it") }
	}

	/**
	 * Fills frequencies of cores whose cpufreq node was unreadable from a cluster mate with the same
	 * core design, else from the device average. False when no frequency is known at all.
	 */
	private fun repairUnknownFrequencies(freqsInKHz: LongArray, midrIdsByCore: Map<Int, MidrId>): Boolean {
		val knownFreqs = freqsInKHz.filter { it > 0 }
		if (knownFreqs.isEmpty()) return false

		val averageFreq = (knownFreqs.sum() / knownFreqs.size)
		for (core in freqsInKHz.indices) {
			if (freqsInKHz[core] > 0) continue
			freqsInKHz[core] = midrIdsByCore[core]?.let { id ->
				freqsInKHz.indices.firstOrNull { freqsInKHz[it] > 0 && midrIdsByCore[it] == id }
					?.let { freqsInKHz[it] }
			} ?: averageFreq
		}
		return true
	}

	/** Cores missing from /proc/cpuinfo (offline during the read) inherit the design of a same-frequency mate. */
	private fun findClusterMateMidrId(core: Int, freqsInKHz: LongArray, midrIdsByCore: Map<Int, MidrId>): MidrId? =
		freqsInKHz.indices.firstOrNull { midrIdsByCore[it] != null && freqsInKHz[it] == freqsInKHz[core] }
			?.let { midrIdsByCore[it] }

	/** Weight for a design not in the table: assume the newest known core of the same cluster role. */
	private fun roleBasedWeight(freqInKHz: Long, deviceMaxFreqInKHz: Long, topFreqCoreCount: Int): Float {
		val relativeFreq = freqInKHz.toFloat() / deviceMaxFreqInKHz
		return when {
			relativeFreq < EFFICIENCY_ROLE_MAX_RELATIVE_FREQ -> UNKNOWN_EFFICIENCY_CORE_WEIGHT
			relativeFreq < PRIME_ROLE_MIN_RELATIVE_FREQ -> UNKNOWN_BIG_CORE_WEIGHT
			// Real designs ship at most 1-2 prime cores; a bigger same-top-frequency group is a
			// big cluster, so scoring it as primes would overrate the device.
			topFreqCoreCount <= MAX_PRIME_CORE_COUNT -> UNKNOWN_PRIME_CORE_WEIGHT
			else -> UNKNOWN_BIG_CORE_WEIGHT
		}
	}

	private fun readCpuInfoText(): String? = try {
		File(cpuInfoPath).readText()
	} catch (e: Exception) {
		logger.logDebug("COULD NOT READ $cpuInfoPath: ${e.message}")
		null
	}

	private fun parseMidrIds(cpuInfoText: String): Map<Int, MidrId> {
		val implementers = mutableMapOf<Int, Int>()
		val parts = mutableMapOf<Int, Int>()
		var currentCore = -1

		for (line in cpuInfoText.lineSequence()) {
			val separatorIndex = line.indexOf(':')
			if (separatorIndex < 0) continue
			val key = line.substring(0, separatorIndex).trim().lowercase()
			val value = line.substring(separatorIndex + 1).trim()
			when (key) {
				// Non-numeric "Processor : ARMv8..." header lines parse to null and are ignored
				"processor" -> value.toIntOrNull()?.let { currentCore = it }
				"cpu implementer" -> parseNumber(value)?.let { if (currentCore >= 0) implementers[currentCore] = it }
				"cpu part" -> parseNumber(value)?.let { if (currentCore >= 0) parts[currentCore] = it }
			}
		}

		return parts.mapNotNull { (core, part) ->
			implementers[core]?.let { implementer -> core to MidrId(implementer, part) }
		}.toMap()
	}

	private fun parseNumber(value: String): Int? =
		if (value.startsWith("0x")) value.removePrefix("0x").toIntOrNull(16) else value.toIntOrNull()

	internal data class MidrId(val implementer: Int, val part: Int)

	companion object {

		private const val CPU_INFO_PATH = "/proc/cpuinfo"

		private const val KHZ_PER_GHZ = 1_000_000F

		private const val ARM = 0x41
		private const val HISILICON = 0x48
		private const val QUALCOMM = 0x51
		private const val SAMSUNG = 0x53

		/** A core clocked below 72% of the device's fastest core is in an efficiency cluster. */
		private const val EFFICIENCY_ROLE_MAX_RELATIVE_FREQ = 0.72F

		/** A core clocked at 95%+ of the device maximum shares the prime role. */
		private const val PRIME_ROLE_MIN_RELATIVE_FREQ = 0.95F

		/** More cores than this at the top frequency means a big cluster, not prime cores. */
		private const val MAX_PRIME_CORE_COUNT = 2

		// Fallback weights for unknown (i.e. newer-than-table) designs = newest known core of that role.
		private const val UNKNOWN_EFFICIENCY_CORE_WEIGHT = 1.5F
		private const val UNKNOWN_BIG_CORE_WEIGHT = 4.1F
		private const val UNKNOWN_PRIME_CORE_WEIGHT = 5.2F

		private fun arm(part: Int) = MidrId(ARM, part)
		private fun hisilicon(part: Int) = MidrId(HISILICON, part)
		private fun qualcomm(part: Int) = MidrId(QUALCOMM, part)
		private fun samsung(part: Int) = MidrId(SAMSUNG, part)

		/**
		 * Relative single-thread capability per GHz, coarsely anchored to public Geekbench 6
		 * single-core results (unitless - only ratios and the tier thresholds matter). Keyed by core
		 * design, not SoC: ARM ships ~3 mobile ids per year.
		 */
		private val KNOWN_CORE_WEIGHTS: Map<MidrId, Float> = mapOf(
			// ARM efficiency cores
			arm(0xd03) to 1.0F, // Cortex-A53
			arm(0xd04) to 1.0F, // Cortex-A35
			arm(0xd05) to 1.2F, // Cortex-A55
			arm(0xd46) to 1.4F, // Cortex-A510
			arm(0xd80) to 1.5F, // Cortex-A520
			arm(0xd8f) to 1.5F, // Cortex-A320
			arm(0xd8a) to 1.6F, // C1-Nano
			// ARM big cores, ARMv8 2016-18
			arm(0xd07) to 1.6F, // Cortex-A57
			arm(0xd08) to 1.9F, // Cortex-A72
			arm(0xd09) to 2.0F, // Cortex-A73
			arm(0xd0a) to 2.2F, // Cortex-A75
			// ARM big cores, ARMv8 2019-21
			arm(0xd0b) to 2.8F, // Cortex-A76
			arm(0xd0d) to 3.1F, // Cortex-A77
			arm(0xd41) to 3.4F, // Cortex-A78
			arm(0xd4b) to 3.4F, // Cortex-A78C
			arm(0xd44) to 3.9F, // Cortex-X1
			arm(0xd4c) to 3.9F, // Cortex-X1C
			// ARM big cores, ARMv9 gen 1-2
			arm(0xd47) to 3.6F, // Cortex-A710
			arm(0xd4d) to 3.7F, // Cortex-A715
			arm(0xd48) to 4.1F, // Cortex-X2
			arm(0xd4e) to 4.4F, // Cortex-X3
			// ARM big cores, ARMv9 gen 3+
			arm(0xd81) to 3.9F, // Cortex-A720
			arm(0xd87) to 4.1F, // Cortex-A725
			arm(0xd82) to 4.7F, // Cortex-X4
			arm(0xd85) to 5.2F, // Cortex-X925
			arm(0xd8b) to 4.3F, // C1-Pro
			arm(0xd90) to 5.0F, // C1-Premium
			arm(0xd8c) to 5.6F, // C1-Ultra
			// HiSilicon (Kirin) relabels licensed ARM designs under its own implementer id, so the
			// same part number means a different core than under ARM: 0xd41 is A77 here, A78 there.
			hisilicon(0xd40) to 2.8F, // Cortex-A76 (Kirin 980/990)
			hisilicon(0xd41) to 3.1F, // Cortex-A77 (Kirin 9000)
			hisilicon(0xd01) to 2.2F, // TaiShan v110 (Kunpeng, server)
			hisilicon(0xd02) to 2.8F, // TaiShan v120 (Kunpeng, server)
			// Qualcomm custom + semi-custom (rebranded ARM) designs
			qualcomm(0x001) to 5.0F, // Oryon
			qualcomm(0x205) to 1.8F, // Kryo 1xx Gold (SD 820/821)
			qualcomm(0x211) to 1.6F, // Kryo 1xx Silver
			qualcomm(0x800) to 2.0F, // Kryo 2xx Gold (A73-class)
			qualcomm(0x801) to 1.0F, // Kryo 2xx Silver (A53-class)
			qualcomm(0x802) to 2.2F, // Kryo 3xx Gold (A75-class)
			qualcomm(0x803) to 1.2F, // Kryo 3xx Silver (A55-class)
			qualcomm(0x804) to 2.8F, // Kryo 4xx Gold (A76-class)
			qualcomm(0x805) to 1.2F, // Kryo 4xx Silver (A55-class)
			// Samsung custom big cores (Exynos M-series, discontinued 2020)
			samsung(0x001) to 1.7F, // Mongoose M1/M2
			samsung(0x002) to 2.2F, // Mongoose M3
			samsung(0x003) to 2.6F, // Mongoose M4
			samsung(0x004) to 2.7F, // Mongoose M5
		)
	}
}
