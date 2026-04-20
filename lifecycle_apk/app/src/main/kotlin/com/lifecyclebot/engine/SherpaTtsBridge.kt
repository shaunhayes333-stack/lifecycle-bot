package com.lifecyclebot.engine

import android.content.Context
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

object SherpaTtsBridge {

    private const val TAG = "SherpaTtsBridge"

    @Volatile
    private var cachedModelKey: String = ""

    @Volatile
    private var cachedTts: OfflineTts? = null

    private data class ModelBundle(
        val modelDir: File,
        val modelFile: File,
        val tokensFile: File,
        val voicesFile: File?,
        val dataDir: File?,
        val lexiconFiles: List<File>,
        val type: Type
    ) {
        enum class Type {
            VITS,
            KOKORO,
            KITTEN
        }

        fun cacheKey(): String {
            return buildString {
                append(type.name)
                append("|")
                append(modelFile.absolutePath)
                append("|")
                append(tokensFile.absolutePath)
                append("|")
                append(voicesFile?.absolutePath ?: "")
                append("|")
                append(dataDir?.absolutePath ?: "")
                append("|")
                append(lexiconFiles.joinToString(",") { it.absolutePath })
            }
        }
    }

    @JvmStatic
    @Synchronized
    fun isAvailable(context: Context, modelDir: String): Boolean {
        if (modelDir.isBlank()) return false

        return try {
            resolveModelBundle(modelDir) != null
        } catch (t: Throwable) {
            ErrorLogger.warn(TAG, "isAvailable failed: ${t.message}")
            false
        }
    }

    @JvmStatic
    @Synchronized
    fun synthesize(
        context: Context,
        modelDir: String,
        text: String,
        voiceName: String,
        speakerId: Int,
        speed: Float
    ): ByteArray? {
        if (modelDir.isBlank()) return null
        if (text.isBlank()) return null

        return try {
            val bundle = resolveModelBundle(modelDir) ?: return null
            val tts = getOrCreateTts(bundle) ?: return null

            val sid = chooseSpeakerId(
                tts = tts,
                requestedSpeakerId = speakerId,
                voiceName = voiceName
            )

            val generationConfig = GenerationConfig(
                silenceScale = 0.15f,
                speed = speed.coerceIn(0.6f, 1.4f),
                sid = sid
            )

            val audio = tts.generateWithConfig(
                text = text.take(4000),
                config = generationConfig
            )

            if (audio.samples.isEmpty() || audio.sampleRate <= 0) {
                ErrorLogger.warn(TAG, "Generated audio is empty")
                return null
            }

            pcmFloatToWavBytes(
                samples = audio.samples,
                sampleRate = audio.sampleRate
            )
        } catch (t: Throwable) {
            ErrorLogger.warn(TAG, "synthesize failed: ${t.message}")
            null
        }
    }

    @JvmStatic
    @Synchronized
    fun release() {
        try {
            cachedTts?.release()
        } catch (_: Throwable) {
        }
        cachedTts = null
        cachedModelKey = ""
    }

    @Synchronized
    private fun getOrCreateTts(bundle: ModelBundle): OfflineTts? {
        val key = bundle.cacheKey()
        if (cachedTts != null && cachedModelKey == key) {
            return cachedTts
        }

        try {
            cachedTts?.release()
        } catch (_: Throwable) {
        }

        cachedTts = null
        cachedModelKey = ""

        return try {
            val config = buildOfflineTtsConfig(bundle)
            val tts = OfflineTts(
                assetManager = null,
                config = config
            )
            cachedTts = tts
            cachedModelKey = key

            ErrorLogger.info(TAG, "Loaded sherpa TTS model: ${bundle.type} @ ${bundle.modelDir.absolutePath}")
            tts
        } catch (t: Throwable) {
            ErrorLogger.warn(TAG, "Failed to create OfflineTts: ${t.message}")
            null
        }
    }

    private fun buildOfflineTtsConfig(bundle: ModelBundle): OfflineTtsConfig {
        val numThreads = if (bundle.type == ModelBundle.Type.VITS) 1 else 2
        val provider = "cpu"
        val debug = false

        return when (bundle.type) {
            ModelBundle.Type.KOKORO -> {
                val lexicon = if (bundle.lexiconFiles.isEmpty()) {
                    ""
                } else {
                    bundle.lexiconFiles.joinToString(",") { it.absolutePath }
                }

                OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        kokoro = OfflineTtsKokoroModelConfig(
                            model = bundle.modelFile.absolutePath,
                            voices = bundle.voicesFile?.absolutePath ?: "",
                            tokens = bundle.tokensFile.absolutePath,
                            dataDir = bundle.dataDir?.absolutePath ?: "",
                            lexicon = lexicon
                        ),
                        numThreads = numThreads,
                        debug = debug,
                        provider = provider
                    ),
                    ruleFsts = "",
                    ruleFars = ""
                )
            }

            ModelBundle.Type.KITTEN -> {
                OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        kitten = OfflineTtsKittenModelConfig(
                            model = bundle.modelFile.absolutePath,
                            voices = bundle.voicesFile?.absolutePath ?: "",
                            tokens = bundle.tokensFile.absolutePath,
                            dataDir = bundle.dataDir?.absolutePath ?: ""
                        ),
                        numThreads = numThreads,
                        debug = debug,
                        provider = provider
                    ),
                    ruleFsts = "",
                    ruleFars = ""
                )
            }

            ModelBundle.Type.VITS -> {
                val lexicon = bundle.lexiconFiles.firstOrNull()?.absolutePath ?: ""

                OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = bundle.modelFile.absolutePath,
                            lexicon = lexicon,
                            tokens = bundle.tokensFile.absolutePath,
                            dataDir = bundle.dataDir?.absolutePath ?: ""
                        ),
                        numThreads = numThreads,
                        debug = debug,
                        provider = provider
                    ),
                    ruleFsts = "",
                    ruleFars = ""
                )
            }
        }
    }

    private fun resolveModelBundle(modelDirPath: String): ModelBundle? {
        val dir = File(modelDirPath)
        if (!dir.exists() || !dir.isDirectory) {
            ErrorLogger.warn(TAG, "Model dir does not exist: $modelDirPath")
            return null
        }

        val files = dir.listFiles()?.toList().orEmpty()
        if (files.isEmpty()) {
            ErrorLogger.warn(TAG, "Model dir is empty: $modelDirPath")
            return null
        }

        val modelFile = pickModelFile(files) ?: run {
            ErrorLogger.warn(TAG, "No .onnx model found in $modelDirPath")
            return null
        }

        val tokensFile = pickTokensFile(files) ?: run {
            ErrorLogger.warn(TAG, "No tokens file found in $modelDirPath")
            return null
        }

        val voicesFile = files.firstOrNull {
            it.isFile && it.name.equals("voices.bin", true)
        }

        val dataDir = files.firstOrNull {
            it.isDirectory && (
                it.name.equals("espeak-ng-data", true) ||
                    it.name.equals("data", true)
                )
        }

        val lexiconFiles = files.filter {
            it.isFile &&
                it.name.startsWith("lexicon", true) &&
                it.extension.equals("txt", true)
        }.sortedBy { it.name.lowercase(Locale.US) }

        val type = detectModelType(
            modelFile = modelFile,
            voicesFile = voicesFile
        )

        return ModelBundle(
            modelDir = dir,
            modelFile = modelFile,
            tokensFile = tokensFile,
            voicesFile = voicesFile,
            dataDir = dataDir,
            lexiconFiles = lexiconFiles,
            type = type
        )
    }

    private fun pickModelFile(files: List<File>): File? {
        val onnxFiles = files.filter {
            it.isFile && it.extension.equals("onnx", true)
        }

        if (onnxFiles.isEmpty()) return null

        val preferredNames = listOf(
            "model.fp16.onnx",
            "model.int8.onnx",
            "model.onnx"
        )

        for (preferred in preferredNames) {
            val hit = onnxFiles.firstOrNull { it.name.equals(preferred, true) }
            if (hit != null) return hit
        }

        val sorted = onnxFiles.sortedBy { it.name.lowercase(Locale.US) }
        return sorted.firstOrNull()
    }

    private fun pickTokensFile(files: List<File>): File? {
        val exact = files.firstOrNull {
            it.isFile && it.name.equals("tokens.txt", true)
        }
        if (exact != null) return exact

        val candidate = files
            .filter { it.isFile && it.extension.equals("txt", true) }
            .sortedBy { it.name.lowercase(Locale.US) }
            .firstOrNull { it.name.lowercase(Locale.US).contains("token") }

        return candidate
    }

    private fun detectModelType(
        modelFile: File,
        voicesFile: File?
    ): ModelBundle.Type {
        val lower = modelFile.name.lowercase(Locale.US)

        return when {
            lower.contains("kitten") -> ModelBundle.Type.KITTEN
            voicesFile != null -> ModelBundle.Type.KOKORO
            else -> ModelBundle.Type.VITS
        }
    }

    private fun chooseSpeakerId(
        tts: OfflineTts,
        requestedSpeakerId: Int,
        voiceName: String
    ): Int {
        val total = try {
            tts.numSpeakers()
        } catch (_: Throwable) {
            1
        }

        if (total <= 1) return 0

        val fromVoiceName = voiceName.trim().toIntOrNull()
        val chosen = fromVoiceName ?: requestedSpeakerId

        return chosen.coerceIn(0, max(0, total - 1))
    }

    private fun pcmFloatToWavBytes(
        samples: FloatArray,
        sampleRate: Int
    ): ByteArray {
        val numChannels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        val dataSize = samples.size * 2
        val chunkSize = 36 + dataSize

        val out = ByteArrayOutputStream(44 + dataSize)

        fun writeString(s: String) {
            out.write(s.toByteArray(Charsets.US_ASCII))
        }

        fun writeIntLE(value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
            out.write((value ushr 16) and 0xFF)
            out.write((value ushr 24) and 0xFF)
        }

        fun writeShortLE(value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
        }

        writeString("RIFF")
        writeIntLE(chunkSize)
        writeString("WAVE")

        writeString("fmt ")
        writeIntLE(16)
        writeShortLE(1)
        writeShortLE(numChannels)
        writeIntLE(sampleRate)
        writeIntLE(byteRate)
        writeShortLE(blockAlign)
        writeShortLE(bitsPerSample)

        writeString("data")
        writeIntLE(dataSize)

        for (sample in samples) {
            val clamped = min(1.0f, max(-1.0f, sample))
            val pcm = if (clamped >= 0f) {
                (clamped * 32767.0f).toInt()
            } else {
                (clamped * 32768.0f).toInt()
            }
            writeShortLE(pcm)
        }

        return out.toByteArray()
    }
}