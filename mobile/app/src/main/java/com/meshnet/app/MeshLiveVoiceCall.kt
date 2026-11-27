package com.meshnet.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Best-effort full-duplex group voice over encrypted, non-durable EPHEMERAL MeshNet frames.
 * Each sender has an independent AMR-NB decoder/jitter queue so concurrent talkers do not
 * overwrite one another's packet sequence numbers. Incoming invitations never open the mic.
 */
class MeshLiveVoiceCall(
    context: Context,
    private val localNodeId: String,
    private val sendEphemeral: (String) -> Boolean,
    private val onEvent: (String) -> Unit
) {
    private class SpeakerStream {
        val packets = ConcurrentSkipListMap<Long, ByteArray>()
        @Volatile var nextSequence = -1L
        @Volatile var firstPacketAt = 0L
        @Volatile var lastRealPacketAt = 0L
        @Volatile var lastTickAt = 0L
        var decoder: MediaCodec? = null // owned exclusively by playback thread
    }

    private val app = context.applicationContext
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val active = AtomicBoolean(false)
    private val speakers = ConcurrentHashMap<String, SpeakerStream>()
    @Volatile private var callId: String? = null
    @Volatile private var incomingId: String? = null
    @Volatile private var incomingAtMs: Long = 0L
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var player: AudioTrack? = null
    @Volatile private var encoder: MediaCodec? = null
    @Volatile private var captureThread: Thread? = null
    @Volatile private var playbackThread: Thread? = null
    @Volatile private var outputSequence = 0L
    private var priorAudioMode = AudioManager.MODE_NORMAL
    private var priorSpeaker = false
    private var focusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (active.get() && (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)) {
            Thread({ end() }, "meshnet-voice-focus-cleanup").apply { isDaemon = true; start() }
        }
    }

    fun isActive(): Boolean = active.get()
    fun latestIncomingCallId(): String? = incomingId

    fun startOutgoing(): String {
        check(!active.get()) { "A live voice call is already active" }
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        startMedia(id)
        if (!sendEphemeral("~V1S|$id")) {
            stopMedia()
            throw IllegalStateException("Live call invitation could not reach a connected peer")
        }
        onEvent("Live group voice started · $id. Other group members must explicitly join.")
        return id
    }

    fun joinIncoming(): String {
        check(!active.get()) { "A live voice call is already active" }
        val id = incomingId ?: throw IllegalStateException("No incoming live voice invitation is available")
        if (SystemClock.elapsedRealtime() - incomingAtMs > INVITATION_TTL_MS) {
            incomingId = null
            incomingAtMs = 0L
            throw IllegalStateException("The incoming live voice invitation has expired")
        }
        startMedia(id)
        if (!sendEphemeral("~V1J|$id")) {
            stopMedia()
            incomingId = id
            incomingAtMs = SystemClock.elapsedRealtime()
            throw IllegalStateException("Live call join request could not reach a connected peer")
        }
        onEvent("Joined live voice call $id")
        return id
    }

    fun end() {
        val id = callId ?: return
        sendEphemeral("~V1E|$id")
        stopMedia()
        onEvent("Live group voice ended")
    }

    fun accept(origin: String, message: String): Boolean {
        if (!message.startsWith("~V1")) return false
        val fields = message.split('|')
        if (fields.size < 2 || !fields[1].matches(Regex("[A-Za-z0-9_-]{8,24}"))) return true
        val id = fields[1]
        when (fields[0]) {
            "~V1S" -> {
                if (origin == localNodeId || !origin.matches(Regex("[A-Za-z0-9_-]{1,32}"))) return true
                incomingId = id
                incomingAtMs = SystemClock.elapsedRealtime()
                onEvent("Incoming live voice call $id · tap Join to enable microphone and audio")
            }
            "~V1J" -> if (callId == id && active.get()) {
                onEvent("A group member joined live voice call $id")
            }
            "~V1E" -> {
                if (incomingId == id) {
                    incomingId = null
                    incomingAtMs = 0L
                }
                if (callId == id && active.get()) {
                    stopMedia()
                    onEvent("Live group voice call $id ended by another participant")
                }
            }
            "~V1P" -> {
                if (fields.size != 4 || !active.get() || callId != id) return true
                if (!origin.matches(Regex("[A-Za-z0-9_-]{1,32}")) || origin == localNodeId) return true
                val sequence = fields[2].toLongOrNull() ?: return true
                if (sequence !in 0..MAX_ABSOLUTE_SEQUENCE) return true
                val bytes = try {
                    Base64.decode(fields[3], Base64.URL_SAFE or Base64.NO_WRAP)
                } catch (_: Exception) { return true }
                if (bytes.isEmpty() || bytes.size > MAX_AMR_PACKET_BYTES) return true
                val stream = speakers[origin] ?: run {
                    synchronized(speakers) {
                        speakers[origin] ?: if (speakers.size < MAX_SPEAKERS) {
                            SpeakerStream().also { speakers[origin] = it }
                        } else null
                    }
                } ?: return true
                if (stream.nextSequence >= 0 &&
                    (sequence < stream.nextSequence || sequence - stream.nextSequence > MAX_SEQUENCE_AHEAD)
                ) return true
                if (stream.packets.size < MAX_JITTER_PACKETS) {
                    stream.packets.putIfAbsent(sequence, bytes)
                    val now = SystemClock.elapsedRealtime()
                    if (stream.firstPacketAt == 0L) stream.firstPacketAt = now
                    stream.lastRealPacketAt = now
                }
            }
            else -> return true
        }
        return true
    }

    private fun startMedia(id: String) {
        synchronized(this) {
            check(!active.get()) { "A live voice call is already active" }
            var localEncoder: MediaCodec? = null
            var localRecorder: AudioRecord? = null
            var localPlayer: AudioTrack? = null
            try {
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AMR_NB, SAMPLE_RATE, 1)
                format.setInteger(MediaFormat.KEY_BIT_RATE, AMR_BIT_RATE)
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PCM_FRAME_BYTES)
                localEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AMR_NB)
                localEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                localEncoder.start()

                val micMin = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                check(micMin > 0) { "8 kHz mono microphone capture is unsupported" }
                localRecorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(micMin, PCM_FRAME_BYTES * 8)
                )
                check(localRecorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }

                val speakerMin = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                check(speakerMin > 0) { "8 kHz mono playback is unsupported" }
                localPlayer = AudioTrack(
                    AudioManager.STREAM_VOICE_CALL, SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(speakerMin, PCM_FRAME_BYTES * 8),
                    AudioTrack.MODE_STREAM
                )
                check(localPlayer.state == AudioTrack.STATE_INITIALIZED) { "Voice playback initialization failed" }

                val focusResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .setOnAudioFocusChangeListener(focusListener)
                        .setAcceptsDelayedFocusGain(false)
                        .setWillPauseWhenDucked(true)
                        .build()
                    focusRequest = request
                    audioManager.requestAudioFocus(request)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.requestAudioFocus(
                        focusListener, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                    )
                }
                check(focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Audio focus is unavailable" }
                hasAudioFocus = true
                priorAudioMode = audioManager.mode
                priorSpeaker = audioManager.isSpeakerphoneOn
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.isSpeakerphoneOn = true

                callId = id
                outputSequence = 0L
                incomingId = null
                incomingAtMs = 0L
                speakers.clear()
                recorder = localRecorder
                player = localPlayer
                encoder = localEncoder
                localRecorder.startRecording()
                localPlayer.play()
                active.set(true)

                val readyMic = checkNotNull(localRecorder)
                val readyEncoder = checkNotNull(localEncoder)
                val readyPlayer = checkNotNull(localPlayer)
                captureThread = Thread({ captureLoop(id, readyMic, readyEncoder) }, "meshnet-voice-capture")
                    .apply { isDaemon = true; start() }
                playbackThread = Thread({ playbackLoop(readyPlayer) }, "meshnet-voice-playback")
                    .apply { isDaemon = true; start() }
            } catch (error: Exception) {
                active.set(false)
                runCatching { localRecorder?.release() }
                runCatching { localPlayer?.release() }
                runCatching { localEncoder?.stop() }
                runCatching { localEncoder?.release() }
                recorder = null
                player = null
                encoder = null
                callId = null
                audioManager.mode = priorAudioMode
                audioManager.isSpeakerphoneOn = priorSpeaker
                abandonAudioFocus()
                throw IllegalStateException("AMR-NB live audio is unavailable on this device: ${error.message}", error)
            }
        }
    }

    private fun captureLoop(id: String, mic: AudioRecord, codec: MediaCodec) {
        val pcm = ByteArray(PCM_FRAME_BYTES)
        val info = MediaCodec.BufferInfo()
        var inputTimestampUs = 0L
        var sequence = 0L
        while (active.get() && callId == id) {
            var filled = 0
            while (filled < pcm.size && active.get() && callId == id) {
                val count = try {
                    mic.read(pcm, filled, pcm.size - filled, AudioRecord.READ_BLOCKING)
                } catch (_: Exception) { -1 }
                if (count < 0) {
                    if (active.get()) {
                        onEvent("Live voice microphone stream stopped ($count); ending the call")
                        Thread({ stopMedia() }, "meshnet-voice-capture-cleanup")
                            .apply { isDaemon = true; start() }
                    }
                    return
                }
                if (count == 0) break
                filled += count
            }
            if (filled != pcm.size || !active.get()) continue
            val inputIndex = try { codec.dequeueInputBuffer(CODEC_TIMEOUT_US) } catch (_: Exception) { -1 }
            if (inputIndex < 0) continue
            val input = codec.getInputBuffer(inputIndex)
            if (input == null) {
                codec.queueInputBuffer(inputIndex, 0, 0, inputTimestampUs, 0)
                inputTimestampUs += FRAME_DURATION_US
                continue
            }
            input.clear()
            input.put(pcm)
            codec.queueInputBuffer(inputIndex, 0, pcm.size, inputTimestampUs, 0)
            inputTimestampUs += FRAME_DURATION_US

            while (active.get()) {
                val outputIndex = try { codec.dequeueOutputBuffer(info, 0) } catch (_: Exception) { -1 }
                if (outputIndex < 0) break
                try {
                    if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        val output = codec.getOutputBuffer(outputIndex)
                        if (output != null) {
                            val packet = ByteArray(info.size)
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            output.get(packet)
                            if (packet.size <= MAX_AMR_PACKET_BYTES) {
                                val encoded = Base64.encodeToString(
                                    packet, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                                )
                                sendEphemeral("~V1P|$id|$sequence|$encoded")
                                sequence++
                            }
                        }
                    }
                } finally {
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }
        }
    }

    private fun playbackLoop(output: AudioTrack) {
        val mixed = ByteArray(PCM_FRAME_BYTES)
        while (active.get()) {
            val tick = SystemClock.elapsedRealtime()
            val contributors = mutableListOf<ByteArray>()
            speakers.entries.toList().forEach { (origin, stream) ->
                if (stream.packets.isEmpty() && stream.lastRealPacketAt > 0L &&
                    tick - stream.lastRealPacketAt > SPEAKER_IDLE_TTL_MS
                ) {
                    if (speakers.remove(origin, stream)) releaseSpeakerDecoder(stream)
                } else {
                    val pcm = decodeNext(origin, stream, tick)
                    if (pcm != null) contributors.add(pcm)
                }
            }
            mixPcm(contributors, mixed)
            try {
                output.write(mixed, 0, mixed.size, AudioTrack.WRITE_BLOCKING)
            } catch (_: Exception) { }
            val elapsed = SystemClock.elapsedRealtime() - tick
            val sleep = FRAME_DURATION_MS - elapsed
            if (sleep > 0) Thread.sleep(sleep)
        }
        speakers.values.forEach { stream ->
            runCatching { stream.decoder?.stop() }
            runCatching { stream.decoder?.release() }
            stream.decoder = null
        }
        speakers.clear()
    }

    private fun decodeNext(origin: String, stream: SpeakerStream, now: Long): ByteArray? {
        if (stream.decoder == null) {
            if (stream.packets.isEmpty()) return null
            try {
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AMR_NB, SAMPLE_RATE, 1)
                format.setInteger(MediaFormat.KEY_BIT_RATE, AMR_BIT_RATE)
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PCM_FRAME_BYTES)
                val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AMR_NB)
                codec.configure(format, null, null, 0)
                codec.start()
                stream.decoder = codec
            } catch (error: Exception) {
                speakers.remove(origin, stream)
                onEvent("Unable to decode AMR-NB audio from peer $origin: ${error.message}")
                return null
            }
        }
        if (stream.nextSequence < 0) {
            if (stream.packets.size < STARTUP_PACKETS &&
                now - stream.firstPacketAt < STARTUP_WAIT_MS
            ) return null
            stream.nextSequence = stream.packets.firstKey()
            stream.lastTickAt = now
        }

        val packet = stream.packets.remove(stream.nextSequence)
        if (packet == null) {
            val later = stream.packets.firstEntry()
            if (later != null && later.key > stream.nextSequence &&
                now - stream.lastTickAt >= JITTER_WAIT_MS
            ) {
                stream.nextSequence++
                stream.lastTickAt = now
                return SILENCE
            }
            return null
        }

        val codec = stream.decoder ?: return null
        val inputIndex = try { codec.dequeueInputBuffer(0) } catch (_: Exception) { -1 }
        if (inputIndex < 0) {
            stream.packets.putIfAbsent(stream.nextSequence, packet)
            return null
        }
        val input = codec.getInputBuffer(inputIndex)
        if (input == null) codec.queueInputBuffer(inputIndex, 0, 0, stream.nextSequence * FRAME_DURATION_US, 0)
        else {
            input.clear()
            input.put(packet)
            codec.queueInputBuffer(inputIndex, 0, packet.size, stream.nextSequence * FRAME_DURATION_US, 0)
        }
        stream.nextSequence++
        stream.lastTickAt = now
        return drainDecoder(codec)
    }

    private fun releaseSpeakerDecoder(stream: SpeakerStream) {
        runCatching { stream.decoder?.stop() }
        runCatching { stream.decoder?.release() }
        stream.decoder = null
        stream.packets.clear()
    }

    private fun drainDecoder(codec: MediaCodec): ByteArray? {
        val info = MediaCodec.BufferInfo()
        var pcm: ByteArray? = null
        while (true) {
            val outputIndex = try { codec.dequeueOutputBuffer(info, 0) } catch (_: Exception) { -1 }
            if (outputIndex < 0) break
            try {
                if (info.size > 0) {
                    val output = codec.getOutputBuffer(outputIndex)
                    if (output != null) {
                        val bytes = ByteArray(info.size)
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        output.get(bytes)
                        pcm = bytes
                    }
                }
            } finally {
                codec.releaseOutputBuffer(outputIndex, false)
            }
        }
        return pcm
    }

    private fun mixPcm(sources: List<ByteArray>, destination: ByteArray) {
        if (sources.isEmpty()) {
            SILENCE.copyInto(destination)
            return
        }
        val sampleCount = destination.size / 2
        val mix = IntArray(sampleCount)
        sources.forEach { source ->
            val buffer = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN)
            val count = minOf(sampleCount, source.size / 2)
            for (i in 0 until count) mix[i] += buffer.short.toInt()
        }
        val out = ByteBuffer.wrap(destination).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in mix) out.putShort(sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
    }

    private fun stopMedia() {
        synchronized(this) {
            if (!active.getAndSet(false)) {
                callId = null
                speakers.clear()
                return
            }
            runCatching { recorder?.stop() }
            captureThread?.let { runCatching { it.join(700) } }
            playbackThread?.let { runCatching { it.join(700) } }
            runCatching { player?.pause() }
            runCatching { player?.flush() }
            runCatching { player?.stop() }
            runCatching { recorder?.release() }
            runCatching { player?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            recorder = null
            player = null
            encoder = null
            captureThread = null
            playbackThread = null
            callId = null
            incomingId = null
            incomingAtMs = 0L
            speakers.clear()
            audioManager.mode = priorAudioMode
            audioManager.isSpeakerphoneOn = priorSpeaker
            abandonAudioFocus()
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (hasAudioFocus) focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else if (hasAudioFocus) {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
        hasAudioFocus = false
    }

    companion object {
        private const val SAMPLE_RATE = 8_000
        private const val AMR_BIT_RATE = 12_200
        private const val PCM_FRAME_BYTES = 320
        private const val MAX_AMR_PACKET_BYTES = 64
        private const val MAX_JITTER_PACKETS = 32
        private const val MAX_SPEAKERS = 8
        private const val MAX_SEQUENCE_AHEAD = 500L
        private const val MAX_ABSOLUTE_SEQUENCE = 1_000_000L
        private const val STARTUP_PACKETS = 3
        private const val STARTUP_WAIT_MS = 120L
        private const val JITTER_WAIT_MS = 80L
        private const val SPEAKER_IDLE_TTL_MS = 15_000L
        private const val INVITATION_TTL_MS = 5 * 60_000L
        private const val FRAME_DURATION_US = 20_000L
        private const val FRAME_DURATION_MS = 20L
        private const val CODEC_TIMEOUT_US = 10_000L
        private val SILENCE = ByteArray(PCM_FRAME_BYTES)
    }
}
