package top.jarman.gamehaptic.audio

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticAudioAnalyzerTest {
    @Test
    fun processMapsLouderAudioToStrongerHaptics() {
        val analyzer = HapticAudioAnalyzer(
            sampleRate = SAMPLE_RATE,
            initialConfig = HapticConfig(
                noiseGate = 0.012f,
                sensitivity = 1f,
                transientFocus = 0.25f
            )
        )

        repeat(8) {
            assertNull(analyzer.process(toneFrame(180), FRAME_SIZE))
        }

        val weak = nextEvent(analyzer, toneFrame(2_600))
        coolDown(analyzer)
        val medium = nextEvent(analyzer, toneFrame(8_500))
        coolDown(analyzer)
        val strong = nextEvent(analyzer, toneFrame(22_000))

        assertTrue("medium should be stronger than weak", medium.amplitude > weak.amplitude)
        assertTrue("strong should be stronger than medium", strong.amplitude > medium.amplitude)
        assertTrue("strong should also last longer", strong.durationMs > weak.durationMs)
    }

    @Test
    fun processDoesNotCrashWhenNoiseGateExceedsLoudnessCeilingFloor() {
        val analyzer = HapticAudioAnalyzer(
            sampleRate = SAMPLE_RATE,
            initialConfig = HapticConfig(
                noiseGate = 0.18f,
                sensitivity = 2.5f,
                transientFocus = 1f
            )
        )

        repeat(120) {
            analyzer.process(toneFrame(32_000), FRAME_SIZE)
        }

        assertTrue(true)
    }

    private fun nextEvent(analyzer: HapticAudioAnalyzer, frame: ShortArray): HapticEvent {
        repeat(24) {
            analyzer.process(frame, frame.size)?.let { return it }
        }
        error("Expected a haptic event")
    }

    private fun coolDown(analyzer: HapticAudioAnalyzer) {
        val silence = ShortArray(FRAME_SIZE)
        repeat(24) {
            analyzer.process(silence, silence.size)
        }
    }

    private fun toneFrame(amplitude: Int): ShortArray =
        ShortArray(FRAME_SIZE) { index ->
            (sin(index * 2.0 * PI / 18.0) * amplitude).toInt().toShort()
        }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val FRAME_SIZE = 1_024
    }
}
