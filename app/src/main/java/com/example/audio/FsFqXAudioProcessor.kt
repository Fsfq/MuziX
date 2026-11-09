package com.example.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// DSP аудиопроцессор
@UnstableApi
class FsFqXAudioProcessor : BaseAudioProcessor() {

    private class Snapshot(
        val bypass: Boolean,
        val gainLin: Double,
        val bandGainDb: FloatArray,
        val bassEnabled: Boolean,
        val bassMode: ViperAudioDsp.BassMode,
        val bassFreq: Float,
        val bassGainDb: Float,
        val widen: Double,
        val reverbPreset: Int
    )

    @Volatile
    private var snapshot = Snapshot(
        bypass = true,
        gainLin = 1.0,
        bandGainDb = FloatArray(BAND_COUNT),
        bassEnabled = false,
        bassMode = ViperAudioDsp.BassMode.PURE_BASS,
        bassFreq = 60f,
        bassGainDb = 0f,
        widen = 1.0,
        reverbPreset = 0
    )

    @Volatile
    private var version = 0
    private var appliedVersion = -1

    @Volatile
    var fftListener: ((ByteArray) -> Unit)? = null

    private var sampleRate = 44100
    private var channels = 2

    // коэффициенты фильтров
    private val b0 = DoubleArray(BAND_COUNT)
    private val b1 = DoubleArray(BAND_COUNT)
    private val b2 = DoubleArray(BAND_COUNT)
    private val a1 = DoubleArray(BAND_COUNT)
    private val a2 = DoubleArray(BAND_COUNT)
    private val x1 = DoubleArray(BAND_COUNT * 2)
    private val x2 = DoubleArray(BAND_COUNT * 2)
    private val y1 = DoubleArray(BAND_COUNT * 2)
    private val y2 = DoubleArray(BAND_COUNT * 2)
    private var active = IntArray(0)

    // фильтр DC
    private var dcPole = 0.998
    private val dcPrevX = DoubleArray(2)
    private val dcPrevY = DoubleArray(2)

    // бас-полка
    private var useBass = false
    private var shB0 = 1.0
    private var shB1 = 0.0
    private var shB2 = 0.0
    private var shA1 = 0.0
    private var shA2 = 0.0
    private val shX1 = DoubleArray(2)
    private val shX2 = DoubleArray(2)
    private val shY1 = DoubleArray(2)
    private val shY2 = DoubleArray(2)

    // панч баса
    private var pnB0 = 1.0
    private var pnB1 = 0.0
    private var pnB2 = 0.0
    private var pnA1 = 0.0
    private var pnA2 = 0.0
    private val pnX1 = DoubleArray(2)
    private val pnX2 = DoubleArray(2)
    private val pnY1 = DoubleArray(2)
    private val pnY2 = DoubleArray(2)

    // гармоники сабвуфера
    private var subHarmAmount = 0.0
    private var subExtractPole = 0.99
    private val subEnvL = DoubleArray(2)
    private val subHarmLp = DoubleArray(2)

    // лимитер пиков
    private val lookL = DoubleArray(LOOKAHEAD_SAMPLES)
    private val lookR = DoubleArray(LOOKAHEAD_SAMPLES)
    private val lookPeak = DoubleArray(LOOKAHEAD_SAMPLES)
    private var lookPos = 0
    private var limiterGain = 1.0
    private var attackStep = 0.02
    private var releaseCoeff = 0.9995

    private var gainLin = 1.0
    private var widen = 1.0
    private var bypass = true

    private var reverb: Reverb? = null
    private var reverbWet = 0f

    private val ring = FloatArray(FFT_SIZE)
    private var ringPos = 0
    private var samplesSinceFft = 0
    private val fftRe = FloatArray(FFT_SIZE)
    private val fftIm = FloatArray(FFT_SIZE)

    fun update(
        state: ViperAudioDsp.State,
        userBandsDb: IntArray,
        surround: Int,
        reverbPreset: Int,
        extraBassDb: Float,
        replayGainDb: Float
    ) {
        val gains = FloatArray(BAND_COUNT)
        var widenValue = 1.0
        var reverbValue = 0
        var preampDb = replayGainDb
        var effectiveBassDb = 0f
        var effectiveBassEnabled = false

        if (state.enabled) {
            // каскад эквалайзера
            val clarityAndAnalogOnly = state.copy(bassEnabled = false, bassGainDb = 0f)
            for (i in 0 until BAND_COUNT) {
                val fc = BAND_FREQS[i]
                var g = interpolateUserBands(userBandsDb, fc)
                g += ViperAudioDsp.calculateDeltaDb(fc, clarityAndAnalogOnly) - state.preampDb
                g = g.coerceIn(-15f, 15f)
                gains[i] = g
            }
            // усиление баса
            val combinedBass = (if (state.bassEnabled) state.bassGainDb else 0f) + extraBassDb * 0.75f
            effectiveBassDb = combinedBass.coerceIn(0f, 20f)
            effectiveBassEnabled = effectiveBassDb > 0.15f

            preampDb += state.preampDb
            widenValue = 1.0 + (surround.coerceIn(0, 1000) / 1000.0) * 0.85
            reverbValue = reverbPreset
        }

        val isBypass = !state.enabled && abs(replayGainDb) < 0.01f
        snapshot = Snapshot(
            bypass = isBypass,
            gainLin = 10.0.pow(preampDb / 20.0),
            bandGainDb = gains,
            bassEnabled = effectiveBassEnabled,
            bassMode = state.bassMode,
            bassFreq = state.bassFreq.toFloat().coerceIn(40f, 120f),
            bassGainDb = effectiveBassDb,
            widen = widenValue,
            reverbPreset = reverbValue
        )
        version++
    }

    private fun interpolateUserBands(levels: IntArray, freq: Float): Float {
        if (levels.isEmpty()) return 0f
        val n = minOf(levels.size, USER_FREQS.size)
        if (freq <= USER_FREQS[0]) return levels[0].toFloat()
        if (freq >= USER_FREQS[n - 1]) return levels[n - 1].toFloat()
        for (i in 0 until n - 1) {
            if (freq <= USER_FREQS[i + 1]) {
                val t = (ln(freq / USER_FREQS[i]) / ln(USER_FREQS[i + 1] / USER_FREQS[i]))
                return levels[i] + (levels[i + 1] - levels[i]) * t
            }
        }
        return 0f
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val supportedEncoding = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        if (!supportedEncoding || inputAudioFormat.channelCount !in 1..2) {
            return AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        dcPole = (1.0 - (2.0 * PI * 14.0 / sampleRate)).coerceIn(0.990, 0.9995)
        subExtractPole = exp(-2.0 * PI * 95.0 / sampleRate)
        // атака и спад
        attackStep = 1.0 - exp(-1.0 / (sampleRate * 0.0022))
        releaseCoeff = exp(-1.0 / (sampleRate * 0.160))
        appliedVersion = -1
        return AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT)
    }

    override fun onFlush() {
        x1.fill(0.0); x2.fill(0.0); y1.fill(0.0); y2.fill(0.0)
        dcPrevX.fill(0.0); dcPrevY.fill(0.0)
        shX1.fill(0.0); shX2.fill(0.0); shY1.fill(0.0); shY2.fill(0.0)
        pnX1.fill(0.0); pnX2.fill(0.0); pnY1.fill(0.0); pnY2.fill(0.0)
        subEnvL.fill(0.0); subHarmLp.fill(0.0)
        lookL.fill(0.0); lookR.fill(0.0); lookPeak.fill(0.0)
        lookPos = 0
        limiterGain = 1.0
        reverb?.clear()
        ring.fill(0f)
        ringPos = 0
        samplesSinceFft = 0
        appliedVersion = -1
    }

    override fun onReset() {
        reverb = null
    }

    private fun configureLowShelf(fcHz: Double, gainDb: Double, slope: Double) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * fcHz / sampleRate
        val cosW = cos(w0)
        val sinW = sin(w0)
        val beta = sqrt(((a * a + 1.0) / slope - (a - 1.0) * (a - 1.0)).coerceAtLeast(0.01))
        val twoSqrtAAlpha = sinW * beta

        val a0 = (a + 1.0) + (a - 1.0) * cosW + twoSqrtAAlpha
        shB0 = (a * ((a + 1.0) - (a - 1.0) * cosW + twoSqrtAAlpha)) / a0
        shB1 = (2.0 * a * ((a - 1.0) - (a + 1.0) * cosW)) / a0
        shB2 = (a * ((a + 1.0) - (a - 1.0) * cosW - twoSqrtAAlpha)) / a0
        shA1 = (-2.0 * ((a - 1.0) + (a + 1.0) * cosW)) / a0
        shA2 = ((a + 1.0) + (a - 1.0) * cosW - twoSqrtAAlpha) / a0
    }

    private fun configurePunchPeaking(fcHz: Double, gainDb: Double, q: Double) {
        if (abs(gainDb) < 0.05) {
            pnB0 = 1.0; pnB1 = 0.0; pnB2 = 0.0; pnA1 = 0.0; pnA2 = 0.0
            return
        }
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * fcHz / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val cosW = cos(w0)
        val a0 = 1.0 + alpha / a
        pnB0 = (1.0 + alpha * a) / a0
        pnB1 = (-2.0 * cosW) / a0
        pnB2 = (1.0 - alpha * a) / a0
        pnA1 = (-2.0 * cosW) / a0
        pnA2 = (1.0 - alpha / a) / a0
    }

    private fun applySnapshot() {
        val snap = snapshot
        appliedVersion = version
        bypass = snap.bypass
        gainLin = snap.gainLin
        widen = snap.widen

        // настройка баса
        useBass = snap.bassEnabled
        if (useBass) {
            val baseFc = snap.bassFreq.toDouble()
            val db = snap.bassGainDb.toDouble()
            when (snap.bassMode) {
                ViperAudioDsp.BassMode.NATURAL -> {
                    configureLowShelf(fcHz = baseFc * 1.15, gainDb = db * 0.85, slope = 0.82)
                    configurePunchPeaking(fcHz = baseFc * 0.92, gainDb = db * 0.35, q = 0.90)
                    subHarmAmount = 0.0
                }
                ViperAudioDsp.BassMode.PURE_BASS -> {
                    configureLowShelf(fcHz = baseFc * 1.10, gainDb = db * 0.95, slope = 0.95)
                    configurePunchPeaking(fcHz = baseFc.coerceIn(48.0, 75.0), gainDb = db * 0.52, q = 1.15)
                    subHarmAmount = 0.04 * (db / 18.0)
                }
                ViperAudioDsp.BassMode.SUBWOOFER -> {
                    configureLowShelf(fcHz = (baseFc * 1.05).coerceAtLeast(52.0), gainDb = db * 1.08, slope = 1.05)
                    configurePunchPeaking(fcHz = (baseFc * 0.88).coerceIn(42.0, 68.0), gainDb = db * 0.58, q = 1.02)
                    subHarmAmount = 0.10 * (db / 18.0)
                }
            }
        } else {
            subHarmAmount = 0.0
        }

        // настройка эквалайзера
        val list = ArrayList<Int>(BAND_COUNT)
        for (i in 0 until BAND_COUNT) {
            val db = snap.bandGainDb[i].toDouble()
            val fc = BAND_FREQS[i].toDouble()
            if (abs(db) < 0.05 || fc >= sampleRate * 0.45) continue
            val a = 10.0.pow(db / 40.0)
            val w0 = 2.0 * PI * fc / sampleRate
            val alpha = sin(w0) / (2.0 * BAND_Q)
            val cosW = cos(w0)
            val a0 = 1.0 + alpha / a
            b0[i] = (1.0 + alpha * a) / a0
            b1[i] = (-2.0 * cosW) / a0
            b2[i] = (1.0 - alpha * a) / a0
            a1[i] = (-2.0 * cosW) / a0
            a2[i] = (1.0 - alpha / a) / a0
            list.add(i)
        }
        active = list.toIntArray()

        if (snap.reverbPreset > 0) {
            val params = REVERB_PRESETS[(snap.reverbPreset - 1).coerceIn(0, REVERB_PRESETS.size - 1)]
            val r = reverb ?: Reverb(sampleRate).also { reverb = it }
            r.feedback = params.first
            reverbWet = params.second
        } else {
            reverbWet = 0f
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (appliedVersion != version) applySnapshot()

        val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val frameBytes = bytesPerSample * channels
        val frames = inputBuffer.remaining() / frameBytes
        if (frames == 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val out = replaceOutputBuffer(frames * channels * 2)
        val stereo = channels == 2
        val useReverb = reverbWet > 0f && reverb != null
        val bassOn = useBass
        val harmAmt = subHarmAmount
        val bandList = active

        for (f in 0 until frames) {
            var l: Double
            var r: Double
            if (isFloat) {
                l = inputBuffer.getFloat().toDouble()
                r = if (stereo) inputBuffer.getFloat().toDouble() else l
            } else {
                l = inputBuffer.getShort() / 32768.0
                r = if (stereo) inputBuffer.getShort() / 32768.0 else l
            }

            if (!bypass) {
                // срез DC
                val dcl = l - dcPrevX[0] + dcPole * dcPrevY[0]
                dcPrevX[0] = l; dcPrevY[0] = dcl
                l = dcl
                if (stereo) {
                    val dcr = r - dcPrevX[1] + dcPole * dcPrevY[1]
                    dcPrevX[1] = r; dcPrevY[1] = dcr
                    r = dcr
                } else {
                    r = l
                }

                // расчет баса
                if (bassOn) {
                    val sl = shB0 * l + shB1 * shX1[0] + shB2 * shX2[0] - shA1 * shY1[0] - shA2 * shY2[0]
                    shX2[0] = shX1[0]; shX1[0] = l; shY2[0] = shY1[0]; shY1[0] = sl
                    val pl = pnB0 * sl + pnB1 * pnX1[0] + pnB2 * pnX2[0] - pnA1 * pnY1[0] - pnA2 * pnY2[0]
                    pnX2[0] = pnX1[0]; pnX1[0] = sl; pnY2[0] = pnY1[0]; pnY1[0] = pl
                    l = pl

                    if (harmAmt > 0.0005) {
                        subEnvL[0] = subExtractPole * subEnvL[0] + (1.0 - subExtractPole) * l
                        val rawHarm = subEnvL[0] * abs(subEnvL[0])
                        subHarmLp[0] = subExtractPole * subHarmLp[0] + (1.0 - subExtractPole) * rawHarm
                        l += subHarmLp[0] * harmAmt
                    }

                    if (stereo) {
                        val sr = shB0 * r + shB1 * shX1[1] + shB2 * shX2[1] - shA1 * shY1[1] - shA2 * shY2[1]
                        shX2[1] = shX1[1]; shX1[1] = r; shY2[1] = shY1[1]; shY1[1] = sr
                        val pr = pnB0 * sr + pnB1 * pnX1[1] + pnB2 * pnX2[1] - pnA1 * pnY1[1] - pnA2 * pnY2[1]
                        pnX2[1] = pnX1[1]; pnX1[1] = sr; pnY2[1] = pnY1[1]; pnY1[1] = pr
                        r = pr

                        if (harmAmt > 0.0005) {
                            subEnvL[1] = subExtractPole * subEnvL[1] + (1.0 - subExtractPole) * r
                            val rawHarmR = subEnvL[1] * abs(subEnvL[1])
                            subHarmLp[1] = subExtractPole * subHarmLp[1] + (1.0 - subExtractPole) * rawHarmR
                            r += subHarmLp[1] * harmAmt
                        }
                    } else {
                        r = l
                    }
                }

                // преамп
                l *= gainLin
                r *= gainLin

                // каскад фильтров
                for (idx in bandList.indices) {
                    val b = bandList[idx]
                    val sl = b * 2
                    val yl = b0[b] * l + b1[b] * x1[sl] + b2[b] * x2[sl] - a1[b] * y1[sl] - a2[b] * y2[sl]
                    x2[sl] = x1[sl]; x1[sl] = l; y2[sl] = y1[sl]; y1[sl] = yl
                    l = yl
                    if (stereo) {
                        val sr = sl + 1
                        val yr = b0[b] * r + b1[b] * x1[sr] + b2[b] * x2[sr] - a1[b] * y1[sr] - a2[b] * y2[sr]
                        x2[sr] = x1[sr]; x1[sr] = r; y2[sr] = y1[sr]; y1[sr] = yr
                        r = yr
                    } else {
                        r = l
                    }
                }

                // 3D стерео
                if (stereo && widen != 1.0) {
                    val m = (l + r) * 0.5
                    val s = (l - r) * 0.5 * widen
                    l = m + s
                    r = m - s
                }

                // реверберация
                if (useReverb) {
                    val rv = reverb!!
                    val wetL = rv.processLeft(l.toFloat()).toDouble()
                    val wetR = if (stereo) rv.processRight(r.toFloat()).toDouble() else wetL
                    l += wetL * reverbWet
                    r += wetR * reverbWet
                }

                // лимитер
                val inPeak = maxOf(abs(l), abs(r))
                val outL = lookL[lookPos]
                val outR = lookR[lookPos]
                lookL[lookPos] = l
                lookR[lookPos] = r
                lookPeak[lookPos] = inPeak
                lookPos = (lookPos + 1) and (LOOKAHEAD_SAMPLES - 1)

                // поиск пика
                var horizonPeak = inPeak
                val p1 = lookPeak[(lookPos + 32) and (LOOKAHEAD_SAMPLES - 1)]
                val p2 = lookPeak[(lookPos + 64) and (LOOKAHEAD_SAMPLES - 1)]
                val p3 = lookPeak[(lookPos + 96) and (LOOKAHEAD_SAMPLES - 1)]
                val p4 = lookPeak[lookPos]
                if (p1 > horizonPeak) horizonPeak = p1
                if (p2 > horizonPeak) horizonPeak = p2
                if (p3 > horizonPeak) horizonPeak = p3
                if (p4 > horizonPeak) horizonPeak = p4

                val targetGain = if (horizonPeak > LIMIT_CEILING) LIMIT_CEILING / horizonPeak else 1.0
                limiterGain = if (targetGain < limiterGain) {
                    limiterGain + (targetGain - limiterGain) * attackStep
                } else {
                    releaseCoeff * limiterGain + (1.0 - releaseCoeff) * targetGain
                }

                l = outL * limiterGain
                r = outR * limiterGain

                // мягкое колено
                if (abs(l) > 0.975) {
                    val sign = if (l >= 0.0) 1.0 else -1.0
                    val excess = abs(l) - 0.975
                    l = sign * (0.975 + 0.023 * (excess / (excess + 0.023)))
                }
                if (abs(r) > 0.975) {
                    val sign = if (r >= 0.0) 1.0 else -1.0
                    val excess = abs(r) - 0.975
                    r = sign * (0.975 + 0.023 * (excess / (excess + 0.023)))
                }
            }

            pushVisualizerSample(((l + r) * 0.5).toFloat())

            out.putShort(toPcm16(l))
            if (stereo) out.putShort(toPcm16(r))
        }
        out.flip()
    }

    private fun toPcm16(x: Double): Short {
        val v = (x * 32767.0).toInt()
        return when {
            v > 32767 -> 32767
            v < -32768 -> -32768
            else -> v
        }.toShort()
    }

    private val fftOutputBytes = ByteArray(FFT_SIZE)

    private fun pushVisualizerSample(sample: Float) {
        val listener = fftListener ?: return
        ring[ringPos] = sample
        ringPos = (ringPos + 1) and (FFT_SIZE - 1)
        samplesSinceFft++
        if (samplesSinceFft >= sampleRate / FFT_RATE_HZ) {
            samplesSinceFft = 0
            runFft(listener)
        }
    }

    private fun runFft(listener: (ByteArray) -> Unit) {
        for (i in 0 until FFT_SIZE) {
            fftRe[i] = ring[(ringPos + i) and (FFT_SIZE - 1)] * WINDOW[i]
            fftIm[i] = 0f
        }
        for (i in 0 until FFT_SIZE) {
            val j = BIT_REVERSE[i]
            if (j > i) {
                val t = fftRe[i]; fftRe[i] = fftRe[j]; fftRe[j] = t
            }
        }
        var size = 2
        while (size <= FFT_SIZE) {
            val half = size / 2
            val step = FFT_SIZE / size
            var start = 0
            while (start < FFT_SIZE) {
                var k = 0
                for (j in start until start + half) {
                    val wr = COS_TABLE[k]
                    val wi = -SIN_TABLE[k]
                    val tr = fftRe[j + half] * wr - fftIm[j + half] * wi
                    val ti = fftRe[j + half] * wi + fftIm[j + half] * wr
                    fftRe[j + half] = fftRe[j] - tr
                    fftIm[j + half] = fftIm[j] - ti
                    fftRe[j] += tr
                    fftIm[j] += ti
                    k += step
                }
                start += size
            }
            size *= 2
        }
        for (k in 1 until FFT_SIZE / 2) {
            fftOutputBytes[2 * k] = (fftRe[k] * VIS_SCALE).toInt().coerceIn(-128, 127).toByte()
            fftOutputBytes[2 * k + 1] = (fftIm[k] * VIS_SCALE).toInt().coerceIn(-128, 127).toByte()
        }
        var e0 = 0f; var e1 = 0f; var e2 = 0f; var e3 = 0f
        for (k in 1..4) { e0 += abs(fftRe[k]) + abs(fftIm[k]) }
        for (k in 5..14) { e1 += abs(fftRe[k]) + abs(fftIm[k]) }
        for (k in 15..40) { e2 += abs(fftRe[k]) + abs(fftIm[k]) }
        for (k in 41..90) { e3 += abs(fftRe[k]) + abs(fftIm[k]) }
        val b0 = (e0 / 4f * 0.18f).coerceIn(0.1f, 1.0f)
        val b1 = (e1 / 10f * 0.22f).coerceIn(0.1f, 1.0f)
        val b2 = (e2 / 26f * 0.28f).coerceIn(0.1f, 1.0f)
        val b3 = (e3 / 50f * 0.35f).coerceIn(0.1f, 1.0f)
        PlayerHub.islandFft.value = floatArrayOf(b0, b1, b2, b3)
        listener(fftOutputBytes)
    }

    private class Reverb(sampleRate: Int) {
        var feedback = 0.8f
        private val damp = 0.3f
        private val scale = sampleRate / 44100f

        private val combL = Array(4) { CombFilter((COMB_DELAYS[it] * scale).toInt().coerceAtLeast(8)) }
        private val combR = Array(4) { CombFilter(((COMB_DELAYS[it] + 23) * scale).toInt().coerceAtLeast(8)) }
        private val allL = Array(2) { AllPass((ALLPASS_DELAYS[it] * scale).toInt().coerceAtLeast(8)) }
        private val allR = Array(2) { AllPass(((ALLPASS_DELAYS[it] + 23) * scale).toInt().coerceAtLeast(8)) }

        fun processLeft(x: Float): Float = run(x, combL, allL)
        fun processRight(x: Float): Float = run(x, combR, allR)

        private fun run(x: Float, combs: Array<CombFilter>, alls: Array<AllPass>): Float {
            val input = x * 0.2f
            var sum = 0f
            for (c in combs) sum += c.process(input, feedback, damp)
            for (a in alls) sum = a.process(sum)
            return sum
        }

        fun clear() {
            combL.forEach { it.clear() }
            combR.forEach { it.clear() }
            allL.forEach { it.clear() }
            allR.forEach { it.clear() }
        }
    }

    private class CombFilter(size: Int) {
        private val buffer = FloatArray(size)
        private var index = 0
        private var store = 0f

        fun process(input: Float, feedback: Float, damp: Float): Float {
            val output = buffer[index]
            store = output * (1f - damp) + store * damp
            buffer[index] = input + store * feedback
            index++
            if (index >= buffer.size) index = 0
            return output
        }

        fun clear() {
            buffer.fill(0f)
            store = 0f
        }
    }

    private class AllPass(size: Int) {
        private val buffer = FloatArray(size)
        private var index = 0

        fun process(input: Float): Float {
            val buffered = buffer[index]
            val output = -input + buffered
            buffer[index] = input + buffered * 0.5f
            index++
            if (index >= buffer.size) index = 0
            return output
        }

        fun clear() = buffer.fill(0f)
    }

    companion object {
        const val FFT_SIZE = 256
        private const val LOOKAHEAD_SAMPLES = 128
        private const val FFT_RATE_HZ = 35
        private const val VIS_SCALE = 5.2f
        private const val LIMIT_CEILING = 0.955
        private const val BAND_Q = 1.41

        val BAND_FREQS = floatArrayOf(
            25f, 40f, 63f, 100f, 160f, 250f, 400f, 630f,
            1000f, 1600f, 2500f, 4000f, 6300f, 10000f, 16000f
        )
        const val BAND_COUNT = 15

        private val USER_FREQS = floatArrayOf(60f, 230f, 910f, 4000f, 14000f)

        private val COMB_DELAYS = intArrayOf(1116, 1188, 1277, 1356)
        private val ALLPASS_DELAYS = intArrayOf(556, 441)

        // Ð¿ÑÐµÑÐµÑÑ ÑÐµÐ²ÐµÑÐ±ÐµÑÐ°ÑÐ¸Ð¸
        private val REVERB_PRESETS = arrayOf(
            0.70f to 0.12f,
            0.78f to 0.16f,
            0.84f to 0.20f,
            0.86f to 0.22f,
            0.90f to 0.26f,
            0.80f to 0.22f
        )

        private val WINDOW = FloatArray(FFT_SIZE) {
            (0.5 - 0.5 * cos(2.0 * PI * it / (FFT_SIZE - 1))).toFloat()
        }
        private val COS_TABLE = FloatArray(FFT_SIZE / 2) { cos(2.0 * PI * it / FFT_SIZE).toFloat() }
        private val SIN_TABLE = FloatArray(FFT_SIZE / 2) { sin(2.0 * PI * it / FFT_SIZE).toFloat() }
        private val BIT_REVERSE = IntArray(FFT_SIZE).also { table ->
            val bits = Integer.numberOfTrailingZeros(FFT_SIZE)
            for (i in 0 until FFT_SIZE) {
                var rev = 0
                var x = i
                for (b in 0 until bits) {
                    rev = (rev shl 1) or (x and 1)
                    x = x shr 1
                }
                table[i] = rev
            }
        }
    }
}
