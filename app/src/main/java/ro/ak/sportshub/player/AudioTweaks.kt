package ro.ak.sportshub.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import ro.ak.sportshub.core.common.player.NightCompressor

/**
 * Reglajele de sunet ale playerului, schimbate din meniu cât rulează stream-ul (citite de pe firul audio):
 * întârzierea sunetului pe canal (B2) și modul de noapte (B4).
 */
class AudioTweaks {
    /** Pozitiv = sunetul vine mai târziu decât imaginea. */
    @Volatile var delayUs: Long = 0L

    @Volatile var night: Boolean = false
}

/**
 * Fabrica de redare cu reglajele de sunet: sunetul trece prin [NightProcessor] (doar PCM 16 biți; la ieșirea
 * directă AC3/DTS către receiver nu se poate comprima), iar ceasul audio e mutat cu întârzierea canalului.
 */
class TweakedRenderersFactory(context: Context, private val tweaks: AudioTweaks) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
        val sink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf<AudioProcessor>(NightProcessor(tweaks)))
            .build()
        return DelayedAudioSink(sink, tweaks)
    }
}

/**
 * Întârzierea sunetului: imaginea se sincronizează după ceasul audio, deci ceasul raportat cu [AudioTweaks.delayUs]
 * înainte face ca imaginea să apară mai devreme față de sunet (adică sunetul vine mai târziu). Fără niciun
 * stream sau buffer în plus.
 */
private class DelayedAudioSink(sink: AudioSink, private val tweaks: AudioTweaks) : ForwardingAudioSink(sink) {
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val p = super.getCurrentPositionUs(sourceEnded)
        if (p == AudioSink.CURRENT_POSITION_NOT_SET) return p
        return (p + tweaks.delayUs).coerceAtLeast(0L)
    }
}

/** Modul de noapte: compresia dinamicii pe PCM 16 biți; oprit, sunetul trece neatins. */
private class NightProcessor(private val tweaks: AudioTweaks) : BaseAudioProcessor() {
    private var compressor: NightCompressor? = null
    private var scratch = ShortArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        compressor = NightCompressor(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val c = compressor
        if (!tweaks.night || c == null) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val n = size / 2
        if (scratch.size < n) scratch = ShortArray(n)
        inputBuffer.slice().order(ByteOrder.nativeOrder()).asShortBuffer().get(scratch, 0, n)
        inputBuffer.position(inputBuffer.limit())
        c.process(scratch, n)
        out.asShortBuffer().put(scratch, 0, n)
        out.position(n * 2)
        out.flip()
    }

    override fun onFlush() {
        compressor?.reset()
    }

    override fun onReset() {
        compressor = null
    }
}
