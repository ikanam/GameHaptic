package top.jarman.gamehaptic.audio

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

data class HapticConfig(
    val noiseGate: Float = 0.035f,
    val sensitivity: Float = 1.15f,
    val transientFocus: Float = 0.7f
) {
    fun normalized(): HapticConfig = copy(
        noiseGate = noiseGate.coerceIn(0.005f, 0.18f),
        sensitivity = sensitivity.coerceIn(0.4f, 2.5f),
        transientFocus = transientFocus.coerceIn(0f, 1f)
    )
}

data class HapticEvent(
    val durationMs: Long,
    val amplitude: Int,
    val score: Float,
    val attackAmplitude: Int,
    val releaseAmplitude: Int
)

class HapticAudioAnalyzer(
    private val sampleRate: Int,
    initialConfig: HapticConfig = HapticConfig()
) {
    private var config = initialConfig.normalized()
    private var envelope = 0f
    private var noiseFloor = 0.012f
    private var loudnessCeiling = 0.08f
    private var previousIntensity = 0f
    private var previousInput = 0f
    private var previousHighPass = 0f
    private var cooldownSamples = 0

    fun updateConfig(newConfig: HapticConfig) {
        config = newConfig.normalized()
    }

    fun process(samples: ShortArray, readCount: Int): HapticEvent? {
        if (readCount <= 0) return null

        var sumSquares = 0.0
        var highPassSquares = 0.0
        var peak = 0f
        val highPassAlpha = 0.985f

        for (index in 0 until min(readCount, samples.size)) {
            val input = samples[index] / Short.MAX_VALUE.toFloat()
            val highPass = highPassAlpha * (previousHighPass + input - previousInput)
            previousInput = input
            previousHighPass = highPass

            val absolute = kotlin.math.abs(input)
            val highAbsolute = kotlin.math.abs(highPass)
            peak = max(peak, absolute)
            sumSquares += (input * input).toDouble()
            highPassSquares += (highAbsolute * highAbsolute).toDouble()
        }

        val count = min(readCount, samples.size)
        val rms = sqrt(sumSquares / count).toFloat()
        val highRms = sqrt(highPassSquares / count).toFloat()
        val crestFactor = peak / (rms + 0.0001f)

        noiseFloor = if (rms < noiseFloor) {
            noiseFloor * 0.92f + rms * 0.08f
        } else {
            noiseFloor * 0.995f + min(rms, noiseFloor * 2.5f) * 0.005f
        }.coerceIn(0.004f, 0.12f)

        val previousEnvelope = envelope
        envelope = if (rms > envelope) {
            envelope * 0.78f + rms * 0.22f
        } else {
            envelope * 0.965f + rms * 0.035f
        }

        cooldownSamples = max(0, cooldownSamples - count)
        if (cooldownSamples > 0) return null

        val gate = max(config.noiseGate, noiseFloor * (1.35f + config.transientFocus * 1.8f))
        val transient = max(0f, rms - previousEnvelope)
        val highBandLift = max(0f, highRms - noiseFloor * 0.55f)
        val crestLift = ((crestFactor - 1.15f) / 3.2f).coerceIn(0f, 1f)
        val steadyPenalty = (1f - config.transientFocus * (1f - crestLift) * 0.55f).coerceIn(0.45f, 1f)

        val impactEnergy = transient * (1.9f + config.transientFocus * 2.1f) + highBandLift * (0.65f + config.transientFocus)
        val levelEnergy = max(0f, rms - gate) * (1f - config.transientFocus * 0.65f)
        val eventEnergy = impactEnergy + levelEnergy
        val ceilingCandidate = max(rms, eventEnergy * 0.75f)
        loudnessCeiling = if (ceilingCandidate > loudnessCeiling) {
            loudnessCeiling * 0.88f + ceilingCandidate * 0.12f
        } else {
            loudnessCeiling * 0.997f + max(gate * 2.2f, ceilingCandidate) * 0.003f
        }.coerceIn(gate * 2.1f, 0.65f)

        val dynamicRange = max(0.012f, loudnessCeiling - gate)
        val levelIntensity = ((rms - gate) / dynamicRange).coerceIn(0f, 2.5f)
        val impactReference = max(gate * 1.7f, loudnessCeiling * 0.48f)
        val impactIntensity = (eventEnergy / impactReference).coerceIn(0f, 3.0f)
        val mixedIntensity = (
            impactIntensity * (0.58f + config.transientFocus * 0.32f) +
                levelIntensity * (0.42f - config.transientFocus * 0.22f)
            ) * config.sensitivity * steadyPenalty
        val intensity = (mixedIntensity / (1f + mixedIntensity * 0.55f)).coerceIn(0f, 1f)
        previousIntensity = if (intensity > previousIntensity) {
            previousIntensity * 0.25f + intensity * 0.75f
        } else {
            previousIntensity * 0.62f + intensity * 0.38f
        }
        val shapedIntensity = previousIntensity.coerceIn(0f, 1f)

        if (rms < gate || shapedIntensity < 0.18f) return null

        val amplitudeCurve = shapedIntensity.pow(1.55f)
        val amplitude = (28 + amplitudeCurve * 227).toInt().coerceIn(24, 255)
        val attackAmplitude = (amplitude * (0.55f + crestLift * 0.35f)).toInt().coerceIn(18, amplitude)
        val releaseAmplitude = (amplitude * (0.22f + levelIntensity.coerceIn(0f, 1f) * 0.32f)).toInt().coerceIn(1, amplitude)
        val duration = (10 + shapedIntensity * 52 + crestLift * 10).toLong().coerceIn(10L, 72L)
        val cooldownMs = (10 + (1f - shapedIntensity) * 22).toLong()
        cooldownSamples = (sampleRate * (duration + cooldownMs) / 1000L).toInt()

        return HapticEvent(
            durationMs = duration,
            amplitude = amplitude,
            score = shapedIntensity,
            attackAmplitude = attackAmplitude,
            releaseAmplitude = releaseAmplitude
        )
    }
}
