package com.example.audio

// формулы ViPER-FX
object ViperAudioDsp {

    enum class BassMode(val title: String, val description: String) {
        NATURAL("Natural Bass", "Естественный бас с фильтром Бесселя"),
        PURE_BASS("Pure Bass+", "Фазовый резонансный панч"),
        SUBWOOFER("Subwoofer", "Глубокий суб-бас с гармонической накачкой")
    }

    enum class ClarityMode(val title: String, val description: String) {
        NATURAL("Natural", "Четкость речи и вокала"),
        OZONE("OZone+", "Полочный фильтр 8.25 кГц"),
        XHIFI("X-HiFi", "Высокочастотный гармонический экситер")
    }

    data class State(
        val enabled: Boolean = true,
        // бас
        val bassEnabled: Boolean = true,
        val bassMode: BassMode = BassMode.PURE_BASS,
        val bassFreq: Int = 60, // срез НЧ
        val bassGainDb: Float = 6f, // усиление
        // четкость
        val clarityEnabled: Boolean = true,
        val clarityMode: ClarityMode = ClarityMode.OZONE,
        val clarityGainDb: Float = 4f, // усиление
        // лампа
        val analogXEnabled: Boolean = false,
        // преамп
        val preampDb: Float = 0f,
        // полосы эквалайзера
        val eqBandLevels: FloatArray = FloatArray(5) { 0f },
        val activePreset: String = "Flat",
        // объем и ревер
        val surroundStrength: Short = 0,
        val reverbPreset: Short = 0
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is State) return false
            return enabled == other.enabled &&
                    bassEnabled == other.bassEnabled &&
                    bassMode == other.bassMode &&
                    bassFreq == other.bassFreq &&
                    bassGainDb == other.bassGainDb &&
                    clarityEnabled == other.clarityEnabled &&
                    clarityMode == other.clarityMode &&
                    clarityGainDb == other.clarityGainDb &&
                    analogXEnabled == other.analogXEnabled &&
                    preampDb == other.preampDb &&
                    eqBandLevels.contentEquals(other.eqBandLevels) &&
                    activePreset == other.activePreset &&
                    surroundStrength == other.surroundStrength &&
                    reverbPreset == other.reverbPreset
        }

        override fun hashCode(): Int {
            var result = enabled.hashCode()
            result = 31 * result + bassEnabled.hashCode()
            result = 31 * result + bassMode.hashCode()
            result = 31 * result + bassFreq
            result = 31 * result + bassGainDb.hashCode()
            result = 31 * result + clarityEnabled.hashCode()
            result = 31 * result + clarityMode.hashCode()
            result = 31 * result + clarityGainDb.hashCode()
            result = 31 * result + analogXEnabled.hashCode()
            result = 31 * result + preampDb.hashCode()
            result = 31 * result + eqBandLevels.contentHashCode()
            result = 31 * result + activePreset.hashCode()
            result = 31 * result + surroundStrength.toInt()
            result = 31 * result + reverbPreset.toInt()
            return result
        }
    }

    // расчет АЧХ
    fun calculateDeltaDb(freqHz: Float, state: State): Float {
        if (!state.enabled) return 0f

        var delta = state.preampDb
        val f = freqHz.coerceIn(20f, 22000f)

        // бас
        if (state.bassEnabled && state.bassGainDb > 0f) {
            val fc = state.bassFreq.toFloat()
            val factor = (state.bassGainDb / 6.0f).coerceAtLeast(0.1f)
            when (state.bassMode) {
                BassMode.NATURAL -> {
                    // фильтр Бесселя
                    val ratio = f / fc
                    val lp = 1.0f / (1.0f + ratio * ratio * ratio * ratio)
                    delta += 20.0f * kotlin.math.log10(1.0f + factor * lp)
                }
                BassMode.PURE_BASS -> {
                    // резонансный горб
                    val octDiff = (kotlin.math.ln(f.toDouble()) - kotlin.math.ln(fc.toDouble())) / kotlin.math.ln(2.0)
                    val bell = kotlin.math.exp(-0.5 * (octDiff / 0.7) * (octDiff / 0.7)).toFloat()
                    delta += state.bassGainDb * bell
                }
                BassMode.SUBWOOFER -> {
                    // сабвуфер
                    val subFc = fc * 0.85f
                    val ratio = (f / subFc).toDouble()
                    val sub = (1.0 / (1.0 + Math.pow(ratio, 3.2))).toFloat()
                    delta += state.bassGainDb * 1.25f * sub
                }
            }
        }

        // четкость
        if (state.clarityEnabled && state.clarityGainDb > 0f) {
            when (state.clarityMode) {
                ClarityMode.NATURAL -> {
                    // подъем 3.2кГц
                    val octDiff = (kotlin.math.ln(f.toDouble()) - kotlin.math.ln(3200.0)) / kotlin.math.ln(2.0)
                    val bell = kotlin.math.exp(-0.5 * octDiff * octDiff).toFloat()
                    delta += state.clarityGainDb * bell
                }
                ClarityMode.OZONE -> {
                    // полка 8.25кГц
                    val ratio = (f / 8250.0f).toDouble()
                    val shelf = (ratio / Math.sqrt(1.0 + ratio * ratio)).toFloat()
                    delta += state.clarityGainDb * shelf
                }
                ClarityMode.XHIFI -> {
                    // экситер ВЧ
                    val ratio = ((f - 5000.0f).coerceAtLeast(0f) / 10000.0f).coerceIn(0f, 1.2f)
                    delta += state.clarityGainDb * ratio
                }
            }
        }

        // лампа AnalogX
        if (state.analogXEnabled) {
            val warmOct = (kotlin.math.ln(f.toDouble()) - kotlin.math.ln(633.0)) / kotlin.math.ln(2.0)
            val peakBell = kotlin.math.exp(-0.5 * (warmOct / 1.5) * (warmOct / 1.5)).toFloat() * 1.5f
            val roll = if (f > 12000f) -((f - 12000f) / 8000f) * 1.2f else 0f
            delta += peakBell + roll
        }

        return delta
    }
}
