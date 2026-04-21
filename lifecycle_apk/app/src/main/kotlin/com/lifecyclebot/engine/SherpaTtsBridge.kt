package com.lifecyclebot.engine

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Method
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * V5.9.83 — Compile-safe Sherpa ONNX TTS bridge.
 *
 * Reason for reflection-only design:
 *   The official `com.k2fsa.sherpa.onnx:*` SDK is NOT published to Maven Central
 *   and must be sideloaded as an AAR/JAR. Hard `import com.k2fsa.*` statements
 *   therefore fail the GitHub Actions CI build.
 *
 * This bridge uses reflection to lazily wire up the Sherpa ONNX API at runtime:
 *   - If the user has added the Sherpa ONNX AAR to the classpath, we load
 *     the model from `modelDir` and synthesize WAV bytes exactly like before.
 *   - If the library is absent, every entry point returns `null`/`false`
 *     cleanly and VoiceManager falls back to remote TTS or Android TTS.
 *
 * This matches how VoiceManager already talks to us (via reflection), so the
 * rest of the voice pipeline needs no changes.
 */
object SherpaTtsBridge {

    private const val TAG = "SherpaTtsBridge"

    @Volatile
    private var cachedModelKey: String = ""

    @Volatile
    private var cachedTts: Any? = null

    @Volatile
    private var reflectionFailed: Boolean = false

    private data class ModelBundle(
        val modelDir: File,
        val modelFile: File,
        val tokensFile: File,
        val voicesFile: File?,
        val dataDir: File?,
        val lexiconFiles: List<File>,
        val type: Type
    ) {
        enum class Type { VITS, KOKORO, KITTEN }

        fun cacheKey(): String = buildString {
            append(type.name); append("|")
            append(modelFile.absolutePath); append("|")
            append(tokensFile.absolutePath); append("|")
            append(voicesFile?.absolutePath ?: ""); append("|")
            append(dataDir?.absolutePath ?: ""); append("|")
            append(lexiconFiles.joinToString(",") { it.absolutePath })
        }
    }

    @JvmStatic
    @Synchronized
    fun isAvailable(context: Context, modelDir: String): Boolean {
        if (modelDir.isBlank()) return false
        if (reflectionFailed) return false
        if (!hasSherpaRuntime()) {
            reflectionFailed = true
            return false
        }
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
        if (modelDir.isBlank() || text.isBlank()) return null
        if (reflectionFailed) return null
        if (!hasSherpaRuntime()) {
            reflectionFailed = true
            return null
        }

        return try {
            val bundle = resolveModelBundle(modelDir) ?: return null
            val tts = getOrCreateTts(bundle) ?: return null

            val sid = chooseSpeakerId(tts, speakerId, voiceName)
            val clampedSpeed = speed.coerceIn(0.6f, 1.4f)

            // Build GenerationConfig(silenceScale=0.15f, speed=clampedSpeed, sid=sid)
            val genCfgCls = Class.forName("com.k2fsa.sherpa.onnx.GenerationConfig")
            val genCfg = genCfgCls.getConstructor(
                java.lang.Float.TYPE, java.lang.Float.TYPE, Integer.TYPE
            ).newInstance(0.15f, clampedSpeed, sid)

            val generateMethod = findMethod(
                tts.javaClass, "generateWithConfig", String::class.java, genCfgCls
            ) ?: return null

            val audio = generateMethod.invoke(tts, text.take(4000), genCfg) ?: return null
            val samplesField = audio.javaClass.getField("samples")
            val sampleRateField = audio.javaClass.getField("sampleRate")
            val samples = samplesField.get(audio) as? FloatArray ?: return null
            val sampleRate = (sampleRateField.get(audio) as? Number)?.toInt() ?: 0

            if (samples.isEmpty() || sampleRate <= 0) {
                ErrorLogger.warn(TAG, "Generated audio is empty")
                return null
            }

            pcmFloatToWavBytes(samples, sampleRate)
        } catch (t: Throwable) {
            ErrorLogger.warn(TAG, "synthesize failed: ${t.message}")
            null
        }
    }

    @JvmStatic
    @Synchronized
    fun release() {
        try {
            cachedTts?.let { obj ->
                findMethod(obj.javaClass, "release")?.invoke(obj)
            }
        } catch (_: Throwable) {
        }
        cachedTts = null
        cachedModelKey = ""
    }

    private fun hasSherpaRuntime(): Boolean {
        return try {
            Class.forName("com.k2fsa.sherpa.onnx.OfflineTts")
            true
        } catch (_: Throwable) {
            false
        }
    }

    @Synchronized
    private fun getOrCreateTts(bundle: ModelBundle): Any? {
        val key = bundle.cacheKey()
        if (cachedTts != null && cachedModelKey == key) {
            return cachedTts
        }
        try {
            cachedTts?.let { obj ->
                findMethod(obj.javaClass, "release")?.invoke(obj)
            }
        } catch (_: Throwable) {
        }
        cachedTts = null
        cachedModelKey = ""

        return try {
            val config = buildOfflineTtsConfig(bundle) ?: return null
            val offlineTtsCls = Class.forName("com.k2fsa.sherpa.onnx.OfflineTts")

            // OfflineTts(assetManager: AssetManager? = null, config: OfflineTtsConfig)
            val ctor = offlineTtsCls.constructors.firstOrNull {
                it.parameterCount == 2
            } ?: return null

            val tts = ctor.newInstance(null, config)
            cachedTts = tts
            cachedModelKey = key

            ErrorLogger.info(
                TAG,
                "Loaded sherpa TTS model: ${bundle.type} @ ${bundle.modelDir.absolutePath}"
            )
            tts
        } catch (t: Throwable) {
            ErrorLogger.warn(TAG, "Failed to create OfflineTts: ${t.message}")
            null
        }
    }

    private fun buildOfflineTtsConfig(bundle: ModelBundle): Any? {
        val numThreads = if (bundle.type == ModelBundle.Type.VITS) 1 else 2
        val provider = "cpu"
        val debug = false

        return try {
            val modelCfgCls = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsModelConfig")
            val ttsCfgCls = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsConfig")

            val modelCfg = when (bundle.type) {
                ModelBundle.Type.KOKORO -> {
                    val lex = if (bundle.lexiconFiles.isEmpty()) "" else
                        bundle.lexiconFiles.joinToString(",") { it.absolutePath }
                    val subCls = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig")
                    val sub = subCls.constructors.first { it.parameterCount == 5 }
                        .newInstance(
                            bundle.modelFile.absolutePath,
                            bundle.voicesFile?.absolutePath ?: "",
                            bundle.tokensFile.absolutePath,
                            bundle.dataDir?.absolutePath ?: "",
                            lex
                        )
                    newModelConfig(modelCfgCls, "kokoro", sub, numThreads, debug, provider)
                }

                ModelBundle.Type.KITTEN -> {
                    val subCls = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig")
                    val sub = subCls.constructors.first { it.parameterCount == 4 }
                        .newInstance(
                            bundle.modelFile.absolutePath,
                            bundle.voicesFile?.absolutePath ?: "",
                            bundle.tokensFile.absolutePath,
                            bundle.dataDir?.absolutePath ?: ""
                        )
                    newModelConfig(modelCfgCls, "kitten", sub, numThreads, debug, provider)
                }

                ModelBundle.Type.VITS -> {
                    val lex = bundle.lexiconFiles.firstOrNull()?.absolutePath ?: ""
                    val subCls = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig")
                    val sub = subCls.constructors.first { it.parameterCount == 4 }
                        .newInstance(
                            bundle.modelFile.absolutePath,
                            lex,
                            bundle.tokensFile.absolutePath,
                            bundle.dataDir?.absolutePath ?: ""
                        )
                    newModelConfig(modelCfgCls, "vits", sub, numThreads, debug, provider)
                }
            } ?: return null

            // OfflineTtsConfig(model, ruleFsts="", ruleFars="")
            ttsCfgCls.constructors.first { it.parameterCount == 3 }
                .newInstance(modelCfg, "", "")
        } catch (t: Throwable) {
            ErrorLogger.warn(TAG, "buildOfflineTtsConfig failed: ${t.message}")
            null
        }
    }

    /**
     * Constructs OfflineTtsModelConfig by field-setting because its constructor
     * argument order and count has varied across Sherpa releases. We first try
     * a matching constructor, then fall back to setters.
     */
    private fun newModelConfig(
        modelCfgCls: Class<*>,
        subFieldName: String,
        subValue: Any,
        numThreads: Int,
        debug: Boolean,
        provider: String
    ): Any? {
        return try {
            // Try default constructor first
            val inst = try {
                modelCfgCls.getDeclaredConstructor().newInstance()
            } catch (_: Throwable) {
                null
            }

            if (inst != null) {
                setFieldIfPresent(inst, subFieldName, subValue)
                setFieldIfPresent(inst, "numThreads", numThreads)
                setFieldIfPresent(inst, "debug", debug)
                setFieldIfPresent(inst, "provider", provider)
                return inst
            }

            // Fall back to matching constructor (sub, numThreads, debug, provider)
            val ctor = modelCfgCls.constructors.firstOrNull { it.parameterCount == 4 }
                ?: return null
            ctor.newInstance(subValue, numThreads, debug, provider)
        } catch (t: Throwable) {
            ErrorLogger.warn(TAG, "newModelConfig failed: ${t.message}")
            null
        }
    }

    private fun setFieldIfPresent(obj: Any, name: String, value: Any) {
        try {
            val f = obj.javaClass.getDeclaredField(name)
            f.isAccessible = true
            f.set(obj, value)
        } catch (_: Throwable) {
            // Try setter method as fallback
            try {
                val setter = "set" + name.replaceFirstChar { it.uppercase() }
                val m = obj.javaClass.methods.firstOrNull { it.name == setter && it.parameterCount == 1 }
                m?.invoke(obj, value)
            } catch (_: Throwable) {
            }
        }
    }

    private fun findMethod(cls: Class<*>, name: String, vararg paramTypes: Class<*>): Method? {
        return try {
            cls.getMethod(name, *paramTypes)
        } catch (_: Throwable) {
            try {
                cls.methods.firstOrNull {
                    it.name == name && it.parameterCount == paramTypes.size
                }
            } catch (_: Throwable) {
                null
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
            ErrorLogger.warn(TAG, "No .onnx model found in $modelDirPath"); return null
        }
        val tokensFile = pickTokensFile(files) ?: run {
            ErrorLogger.warn(TAG, "No tokens file found in $modelDirPath"); return null
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
            it.isFile && it.name.startsWith("lexicon", true) && it.extension.equals("txt", true)
        }.sortedBy { it.name.lowercase(Locale.US) }

        val type = detectModelType(modelFile, voicesFile)

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
        val onnxFiles = files.filter { it.isFile && it.extension.equals("onnx", true) }
        if (onnxFiles.isEmpty()) return null
        val preferredNames = listOf("model.fp16.onnx", "model.int8.onnx", "model.onnx")
        for (p in preferredNames) {
            val hit = onnxFiles.firstOrNull { it.name.equals(p, true) }
            if (hit != null) return hit
        }
        return onnxFiles.sortedBy { it.name.lowercase(Locale.US) }.firstOrNull()
    }

    private fun pickTokensFile(files: List<File>): File? {
        val exact = files.firstOrNull { it.isFile && it.name.equals("tokens.txt", true) }
        if (exact != null) return exact
        return files
            .filter { it.isFile && it.extension.equals("txt", true) }
            .sortedBy { it.name.lowercase(Locale.US) }
            .firstOrNull { it.name.lowercase(Locale.US).contains("token") }
    }

    private fun detectModelType(modelFile: File, voicesFile: File?): ModelBundle.Type {
        val lower = modelFile.name.lowercase(Locale.US)
        return when {
            lower.contains("kitten") -> ModelBundle.Type.KITTEN
            voicesFile != null -> ModelBundle.Type.KOKORO
            else -> ModelBundle.Type.VITS
        }
    }

    private fun chooseSpeakerId(
        tts: Any,
        requestedSpeakerId: Int,
        voiceName: String
    ): Int {
        val total = try {
            val m = findMethod(tts.javaClass, "numSpeakers")
            (m?.invoke(tts) as? Number)?.toInt() ?: 1
        } catch (_: Throwable) {
            1
        }
        if (total <= 1) return 0

        val fromVoiceName = voiceName.trim().toIntOrNull()
        val chosen = fromVoiceName ?: requestedSpeakerId
        return chosen.coerceIn(0, max(0, total - 1))
    }

    private fun pcmFloatToWavBytes(samples: FloatArray, sampleRate: Int): ByteArray {
        val numChannels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        val dataSize = samples.size * 2
        val chunkSize = 36 + dataSize

        val out = ByteArrayOutputStream(44 + dataSize)

        fun writeString(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun writeIntLE(v: Int) {
            out.write(v and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 24) and 0xFF)
        }
        fun writeShortLE(v: Int) {
            out.write(v and 0xFF)
            out.write((v ushr 8) and 0xFF)
        }

        writeString("RIFF"); writeIntLE(chunkSize); writeString("WAVE")
        writeString("fmt "); writeIntLE(16); writeShortLE(1); writeShortLE(numChannels)
        writeIntLE(sampleRate); writeIntLE(byteRate); writeShortLE(blockAlign); writeShortLE(bitsPerSample)
        writeString("data"); writeIntLE(dataSize)

        for (s in samples) {
            val c = min(1.0f, max(-1.0f, s))
            val pcm = if (c >= 0f) (c * 32767.0f).toInt() else (c * 32768.0f).toInt()
            writeShortLE(pcm)
        }
        return out.toByteArray()
    }
}
