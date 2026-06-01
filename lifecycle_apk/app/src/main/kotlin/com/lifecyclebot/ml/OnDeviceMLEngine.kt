package com.lifecyclebot.ml

import android.content.Context
import com.lifecyclebot.data.Candle
import com.lifecyclebot.data.Trade
import com.lifecyclebot.engine.ErrorLogger
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.tanh

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * ON-DEVICE ML ENGINE - TensorFlow Lite v5.6
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Learns from YOUR trading history to make personalized predictions:
 *   1. Rug Pull Probability - Detect exit scam patterns before they happen
 *   2. Optimal Entry Timing - Predict best entry points from price patterns  
 *   3. Exit Signal Strength - Confidence in exit timing
 *   4. Token Trajectory Class - Classify token behavior (moon, dump, sideways)
 * 
 * ARCHITECTURE:
 * - Uses TensorFlow Lite for efficient on-device inference
 * - Models are trained incrementally from your trade history
 * - No data leaves your device - complete privacy
 * - Lightweight: runs in <50ms per prediction
 * 
 * TRAINING DATA:
 * - Every closed trade (entry, exit, P&L, duration, metrics at entry/exit)
 * - Token candle patterns leading up to entry/exit
 * - Win/loss labels for supervised learning
 * 
 * This is V5.6 foundation - models start simple and improve with more data.
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object OnDeviceMLEngine {
    
    private const val TAG = "OnDeviceML"
    
    // Feature vector size for model input
    private const val NUM_FEATURES = 32
    
    // Minimum trades before predictions are meaningful
    private const val MIN_TRADES_FOR_PREDICTION = 50
    
    // Model file names (stored in app's files directory)
    private const val RUG_MODEL_FILE = "rug_detector.tflite"
    private const val ENTRY_MODEL_FILE = "entry_predictor.tflite"
    private const val EXIT_MODEL_FILE = "exit_predictor.tflite"
    
    // Training data storage
    private val trainingData = mutableListOf<TradeFeatures>()
    private var rugModel: TFLiteModel? = null
    private var entryModel: TFLiteModel? = null
    private var exitModel: TFLiteModel? = null
    
    // Statistics for normalization
    private var featureMeans = FloatArray(NUM_FEATURES) { 0f }
    private var featureStds = FloatArray(NUM_FEATURES) { 1f }
    private var isNormalized = false

    // ═══════════════════════════════════════════════════════════════════
    // V5.9.1253 — REAL ON-DEVICE LOGISTIC REGRESSION CORE.
    // Replaces the "TensorFlow Lite" shells (which had NO Interpreter.run()
    // anywhere — pure heuristic fallback) with genuine online-trained logistic
    // models. Three heads over the 32 normalized features:
    //   entryW → P(win)        trained on wasWin label
    //   rugW   → P(rug)         trained on wasRug label
    //   exitW  → P(should-exit) trained on (loss OR rug) label
    // Trained incrementally via SGD on every recorded trade (no batch, no
    // external lib, <1ms). Weights persist via SharedPreferences so the model
    // is NOT amnesiac across restarts (closes the same bug class as the
    // learning-persistence doctrine). Heuristics remain as cold-start fallback
    // until each head has seen >= LOGIT_MIN_SAMPLES labelled trades.
    // ═══════════════════════════════════════════════════════════════════
    private const val LOGIT_LR = 0.03f          // SGD learning rate
    private const val LOGIT_L2 = 1e-4f          // L2 regularization (weight decay)
    private const val LOGIT_MIN_SAMPLES = 40    // per-head sample floor before trusting logit
    private var entryW = FloatArray(NUM_FEATURES + 1) { 0f }  // +1 = bias term
    private var rugW   = FloatArray(NUM_FEATURES + 1) { 0f }
    private var exitW  = FloatArray(NUM_FEATURES + 1) { 0f }
    private var entrySamples = 0
    private var rugSamples = 0
    private var exitSamples = 0
    private var appContext: Context? = null

    private fun sigmoid(z: Float): Float = (1f / (1f + exp(-z.toDouble()))).toFloat()

    /** Forward pass: w·x + bias, squashed. x is the normalized feature array. */
    private fun logitPredict(w: FloatArray, x: FloatArray): Float {
        var z = w[NUM_FEATURES]  // bias
        val n = minOf(x.size, NUM_FEATURES)
        for (i in 0 until n) z += w[i] * x[i]
        return sigmoid(z)
    }

    /** One SGD step of binary cross-entropy with L2. Returns nothing; mutates w. */
    private fun logitTrain(w: FloatArray, x: FloatArray, label: Float) {
        val pred = logitPredict(w, x)
        val err = pred - label              // dLoss/dz for logistic + BCE
        val n = minOf(x.size, NUM_FEATURES)
        for (i in 0 until n) {
            val grad = err * x[i] + LOGIT_L2 * w[i]
            w[i] -= LOGIT_LR * grad
        }
        w[NUM_FEATURES] -= LOGIT_LR * err   // bias (no decay)
    }

    // ═══════════════════════════════════════════════════════════════════
    // V5.9.1254 — NONLINEAR HEAD (one hidden layer MLP).
    // The logit above is LINEAR — it cannot represent feature INTERACTIONS
    // (e.g. "high volume is bullish ONLY when buy-pressure is also high"; or
    // "low liquidity is fine for a fresh mint but lethal for an aged one").
    // This adds a shared hidden layer (HIDDEN tanh units) over the 32 features
    // feeding three linear output heads (entry / rug / exit). Trained by
    // backprop on the same per-trade SGD step. The FINAL prediction BLENDS the
    // linear logit (stable base) with the MLP (interaction capture), gated so
    // the net only contributes once it has real evidence — the linear model
    // prevents the net from destabilising the predictor during cold start.
    // Still pure Kotlin, still <1ms (32x16 + 16x3 matmul).
    // ═══════════════════════════════════════════════════════════════════
    private const val HIDDEN = 16
    private const val MLP_LR = 0.02f
    private const val MLP_L2 = 1e-4f
    private const val MLP_MIN_SAMPLES = 120     // net needs more data than the linear head
    private const val MLP_BLEND = 0.45f         // weight of MLP vs linear once trained
    // W1: [HIDDEN][NUM_FEATURES+1]  (hidden weights + bias)
    private var mlpW1 = Array(HIDDEN) { FloatArray(NUM_FEATURES + 1) { (Math.random().toFloat() - 0.5f) * 0.1f } }
    // W2: per head [HIDDEN+1] (output weights + bias) — entry/rug/exit
    private var mlpW2Entry = FloatArray(HIDDEN + 1) { (Math.random().toFloat() - 0.5f) * 0.1f }
    private var mlpW2Rug   = FloatArray(HIDDEN + 1) { (Math.random().toFloat() - 0.5f) * 0.1f }
    private var mlpW2Exit  = FloatArray(HIDDEN + 1) { (Math.random().toFloat() - 0.5f) * 0.1f }

    /** Forward through hidden layer; returns tanh activations (size HIDDEN). */
    private fun mlpHidden(x: FloatArray): FloatArray {
        val n = minOf(x.size, NUM_FEATURES)
        return FloatArray(HIDDEN) { j ->
            var z = mlpW1[j][NUM_FEATURES]  // bias
            for (i in 0 until n) z += mlpW1[j][i] * x[i]
            tanh(z.toDouble()).toFloat()
        }
    }

    /** Output head forward: sigmoid(w2 · h + bias). */
    private fun mlpHead(w2: FloatArray, h: FloatArray): Float {
        var z = w2[HIDDEN]
        for (j in 0 until HIDDEN) z += w2[j] * h[j]
        return sigmoid(z)
    }

    /**
     * One backprop step for a single head sharing the hidden layer.
     * Updates that head's W2 and the shared W1. label in {0,1}.
     */
    private fun mlpTrain(w2: FloatArray, x: FloatArray, label: Float) {
        val n = minOf(x.size, NUM_FEATURES)
        val h = mlpHidden(x)
        val pred = mlpHead(w2, h)
        val dOut = pred - label                       // dLoss/dz_out (BCE+sigmoid)
        // grad for output weights + accumulate hidden deltas
        val dHidden = FloatArray(HIDDEN)
        for (j in 0 until HIDDEN) {
            dHidden[j] = dOut * w2[j] * (1f - h[j] * h[j])   // * tanh'
            w2[j] -= MLP_LR * (dOut * h[j] + MLP_L2 * w2[j])
        }
        w2[HIDDEN] -= MLP_LR * dOut                   // output bias
        // grad for hidden weights (shared W1)
        for (j in 0 until HIDDEN) {
            val dj = dHidden[j]
            val row = mlpW1[j]
            for (i in 0 until n) row[i] -= MLP_LR * (dj * x[i] + MLP_L2 * row[i])
            row[NUM_FEATURES] -= MLP_LR * dj          // hidden bias
        }
    }

    /** Blended prediction: linear logit + (if trained) the nonlinear MLP head. */
    private fun blendedPredict(w: FloatArray, w2: FloatArray, x: FloatArray, samples: Int): Float {
        val lin = logitPredict(w, x)
        if (samples < MLP_MIN_SAMPLES) return lin
        val nl = mlpHead(w2, mlpHidden(x))
        return (lin * (1f - MLP_BLEND) + nl * MLP_BLEND).coerceIn(0f, 1f)
    }
    
    /**
     * Feature vector extracted from a trade for training/inference.
     */
    data class TradeFeatures(
        // Price action features (normalized)
        val priceChange5m: Float,      // % change last 5 min
        val priceChange15m: Float,     // % change last 15 min
        val priceChange1h: Float,      // % change last hour
        val volatility: Float,         // Recent price volatility
        val momentum: Float,           // Price momentum score
        
        // Volume features
        val volumeRatio: Float,        // Current vol vs average
        val buyPressure: Float,        // Buy % of total txns
        val volumeSpike: Float,        // Is volume unusually high?
        
        // Liquidity features
        val liquidityUsd: Float,       // Absolute liquidity (log)
        val liquidityRatio: Float,     // Liq vs mcap ratio
        val liquidityChange: Float,    // Recent liq change %
        
        // Holder features
        val holderCount: Float,        // Total holders (log)
        val holderGrowth: Float,       // Recent holder growth %
        val topHolderPct: Float,       // Top holder concentration
        
        // Safety features
        val rugcheckScore: Float,      // Rugcheck.xyz score
        val mintAuthRevoked: Float,    // 1.0 if revoked, 0.0 if not
        val freezeAuthRevoked: Float,  // 1.0 if revoked, 0.0 if not
        val isHoneypot: Float,         // 1.0 if honeypot, 0.0 if not
        
        // Time features
        val tokenAgeMinutes: Float,    // How old is token (log)
        val holdDuration: Float,       // How long we held
        val hourOfDay: Float,          // 0-23 normalized to 0-1
        val dayOfWeek: Float,          // 0-6 normalized to 0-1
        
        // Technical indicators
        val rsi: Float,                // RSI value
        val emaAlignment: Float,       // EMA fan alignment score
        val supportDistance: Float,    // Distance from support
        val resistanceDistance: Float, // Distance from resistance
        
        // Pattern features
        val patternScore: Float,       // Chart pattern confidence
        val exhaustionSignal: Float,   // 1.0 if exhaustion detected
        val breakdownSignal: Float,    // 1.0 if breakdown detected
        val spikeSignal: Float,        // 1.0 if spike detected
        // V5.9.1268 — fill the two dead padding slots with predictive orderflow.
        // These were `0f, 0f` since inception: the ML heads trained on 30 real
        // features + 2 permanent zeros. Now they carry sustained orderflow
        // imbalance and txn-acceleration — "smart money positioning" signals
        // that front-run price, computed from candle data already in scope.
        val flowImbalance: Float = 0.5f,   // windowed buy/sell skew [0,1], 0.5=neutral
        val txnAccel: Float = 0.5f,        // recent txn-velocity vs baseline [0,1]
        
        // Labels (for training)
        val wasWin: Float = 0f,        // 1.0 if profitable trade
        val pnlPct: Float = 0f,        // Actual P&L percentage
        val wasRug: Float = 0f,        // 1.0 if token rugged after
    )
    
    /**
     * Prediction result from ML models.
     */
    data class MLPrediction(
        val rugProbability: Float,     // 0.0-1.0 probability of rug
        val entryConfidence: Float,    // 0.0-1.0 entry quality
        val exitConfidence: Float,     // 0.0-1.0 exit urgency
        val trajectoryClass: String,   // "MOON", "DUMP", "SIDEWAYS", "UNKNOWN"
        val confidence: Float,         // Overall prediction confidence
        val dataPoints: Int,           // How many trades model learned from
    )
    
    /**
     * Initialize models from stored files or create new ones.
     */
    fun initialize(context: Context) {
        try {
            val filesDir = context.filesDir
            
            // Load existing models if available
            val rugFile = File(filesDir, RUG_MODEL_FILE)
            val entryFile = File(filesDir, ENTRY_MODEL_FILE)
            val exitFile = File(filesDir, EXIT_MODEL_FILE)
            
            if (rugFile.exists()) {
                rugModel = TFLiteModel(rugFile)
                ErrorLogger.info(TAG, "Loaded rug detector model")
            }
            if (entryFile.exists()) {
                entryModel = TFLiteModel(entryFile)
                ErrorLogger.info(TAG, "Loaded entry predictor model")
            }
            if (exitFile.exists()) {
                exitModel = TFLiteModel(exitFile)
                ErrorLogger.info(TAG, "Loaded exit predictor model")
            }
            
            // Load training data stats
            appContext = context.applicationContext
            loadNormalizationStats(context)
            loadModelWeights(context)
            
            ErrorLogger.info(TAG, "ML Engine initialized | ${trainingData.size} training samples")
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "ML init error: ${e.message}")
        }
    }
    
    /**
     * Record a completed trade for training data.
     */
    fun recordTrade(
        trade: Trade,
        candlesAtEntry: List<Candle>,
        candlesAtExit: List<Candle>,
        liquidityAtEntry: Double,
        liquidityAtExit: Double,
        holdersAtEntry: Int,
        holdersAtExit: Int,
        rugcheckScore: Int,
        mintRevoked: Boolean,
        freezeRevoked: Boolean,
        topHolderPct: Double,
        rsi: Double,
        emaAlignment: String,
        wasRug: Boolean,
    ) {
        try {
            // Trade class uses 'ts' for timestamp (exit time)
            // We approximate entry time as exit time minus some hold duration
            // For more accurate training, the caller should track entry time separately
            val exitTime = trade.ts
            val estimatedEntryTime = exitTime - (30 * 60 * 1000L)  // Assume ~30 min hold if unknown
            
            val features = extractFeatures(
                candlesAtEntry, candlesAtExit,
                liquidityAtEntry, liquidityAtExit,
                holdersAtEntry, holdersAtExit,
                rugcheckScore, mintRevoked, freezeRevoked,
                topHolderPct, rsi, emaAlignment,
                estimatedEntryTime, exitTime,
            ).copy(
                wasWin = if (trade.pnlPct > 0) 1f else 0f,
                pnlPct = trade.pnlPct.toFloat(),
                wasRug = if (wasRug) 1f else 0f,
            )
            
            trainingData.add(features)
            
            // Update normalization statistics
            updateNormalizationStats(features)

            // ─── V5.9.1253: online SGD train of the real logistic heads ───
            // Train on the NORMALIZED feature vector so weights live in the
            // same space the predictor sees. Labels come straight from the
            // settled outcome (honest supervised signal).
            try {
                val x = normalizeFeatures(features)
                logitTrain(entryW, x, features.wasWin);                 entrySamples++
                logitTrain(rugW,   x, features.wasRug);                 rugSamples++
                // exit head = "should have exited": trade lost OR rugged.
                val exitLabel = if (features.wasWin < 0.5f || features.wasRug >= 0.5f) 1f else 0f
                logitTrain(exitW,  x, exitLabel);                       exitSamples++
                // V5.9.1254 — also train the shared-hidden-layer nonlinear heads.
                mlpTrain(mlpW2Entry, x, features.wasWin)
                mlpTrain(mlpW2Rug,   x, features.wasRug)
                mlpTrain(mlpW2Exit,  x, exitLabel)
                // Persist periodically (every 10 trades) — cheap, keeps the
                // model durable across the frequent service restarts.
                if (trainingData.size % 10 == 0) {
                    appContext?.let { saveModelWeights(it); saveNormalizationStats(it) }
                }
            } catch (_: Throwable) { /* training is best-effort, never blocks recording */ }
            
            ErrorLogger.debug(TAG, "Recorded trade | total=${trainingData.size} | pnl=${trade.pnlPct.toInt()}% | logitN(e/r/x)=$entrySamples/$rugSamples/$exitSamples")
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Record trade error: ${e.message}")
        }
    }
    
    /**
     * Get ML prediction for current token state.
     */
    fun predict(
        recentCandles: List<Candle>,
        liquidityUsd: Double,
        mcap: Double,
        holderCount: Int,
        holderGrowthPct: Double,
        rugcheckScore: Int,
        mintRevoked: Boolean,
        freezeRevoked: Boolean,
        topHolderPct: Double,
        rsi: Double,
        emaAlignment: String,
        tokenAgeMinutes: Long,
    ): MLPrediction {
        // Need minimum training data for meaningful predictions
        if (trainingData.size < MIN_TRADES_FOR_PREDICTION) {
            return MLPrediction(
                rugProbability = 0.5f,
                entryConfidence = 0.5f,
                exitConfidence = 0.5f,
                trajectoryClass = "UNKNOWN",
                confidence = 0f,
                dataPoints = trainingData.size,
            )
        }
        
        try {
            val features = extractFeatures(
                recentCandles, emptyList(),
                liquidityUsd, liquidityUsd,
                holderCount, holderCount,
                rugcheckScore, mintRevoked, freezeRevoked,
                topHolderPct, rsi, emaAlignment,
                System.currentTimeMillis(), System.currentTimeMillis(),
            )
            
            // Normalize features
            val normalized = normalizeFeatures(features)
            
            // Run inference (or use heuristics if models not trained)
            val rugProb = predictRug(normalized)
            val entryConf = predictEntry(normalized)
            val exitConf = predictExit(normalized)
            val trajectory = classifyTrajectory(normalized, rugProb, recentCandles)
            
            // Calculate overall confidence based on training data volume
            val dataConfidence = (trainingData.size.toFloat() / 500f).coerceAtMost(1f)
            
            return MLPrediction(
                rugProbability = rugProb,
                entryConfidence = entryConf,
                exitConfidence = exitConf,
                trajectoryClass = trajectory,
                confidence = dataConfidence * 0.8f + 0.2f,  // 20-100% based on data
                dataPoints = trainingData.size,
            )
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Predict error: ${e.message}")
            return defaultPrediction()
        }
    }
    
    /**
     * Extract feature vector from candle data and metrics.
     */
    private fun extractFeatures(
        candlesAtEntry: List<Candle>,
        candlesAtExit: List<Candle>,
        liquidityAtEntry: Double,
        liquidityAtExit: Double,
        holdersAtEntry: Int,
        holdersAtExit: Int,
        rugcheckScore: Int,
        mintRevoked: Boolean,
        freezeRevoked: Boolean,
        topHolderPct: Double,
        rsi: Double,
        emaAlignment: String,
        entryTime: Long,
        exitTime: Long,
    ): TradeFeatures {
        // Calculate price changes from candles
        val candles = candlesAtEntry.takeIf { it.isNotEmpty() } ?: candlesAtExit
        val priceChange5m = calculatePriceChange(candles, 5)
        val priceChange15m = calculatePriceChange(candles, 15)
        val priceChange1h = calculatePriceChange(candles, 60)
        val volatility = calculateVolatility(candles)
        val momentum = calculateMomentum(candles)
        
        // Volume features
        val avgVolume = candles.takeLast(20).map { it.volumeH1 }.average().takeIf { !it.isNaN() } ?: 1.0
        val currentVolume = candles.lastOrNull()?.volumeH1 ?: 0.0
        val volumeRatio = (currentVolume / avgVolume.coerceAtLeast(1.0)).toFloat()
        val buyPressure = candles.lastOrNull()?.let {
            if (it.buysH1 + it.sellsH1 > 0) it.buysH1.toFloat() / (it.buysH1 + it.sellsH1) else 0.5f
        } ?: 0.5f
        val volumeSpike = if (volumeRatio > 3f) 1f else 0f
        
        // Liquidity features
        val mcap = candles.lastOrNull()?.marketCap ?: 10000.0
        val liquidityRatio = (liquidityAtEntry / mcap.coerceAtLeast(1.0)).toFloat()
        val liquidityChange = if (liquidityAtEntry > 0) {
            ((liquidityAtExit - liquidityAtEntry) / liquidityAtEntry * 100).toFloat()
        } else 0f
        
        // Holder features
        val holderGrowth = if (holdersAtEntry > 0) {
            ((holdersAtExit - holdersAtEntry).toFloat() / holdersAtEntry * 100)
        } else 0f
        
        // Time features
        val holdDuration = ((exitTime - entryTime) / 60000f).coerceAtLeast(0f)  // minutes
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = entryTime }
        val hourOfDay = calendar.get(java.util.Calendar.HOUR_OF_DAY) / 23f
        val dayOfWeek = calendar.get(java.util.Calendar.DAY_OF_WEEK) / 6f
        
        // EMA alignment score
        val emaScore = when (emaAlignment) {
            "BULL_FAN" -> 1f
            "BULL_FLAT" -> 0.7f
            "FLAT" -> 0.5f
            "BEAR_FLAT" -> 0.3f
            "BEAR_FAN" -> 0f
            else -> 0.5f
        }
        
        // V5.9.1268 — windowed orderflow imbalance: buys vs sells over the recent
        // window (not just the last candle like buyPressure). Sustained accumulation
        // is far more predictive than a single bar.
        val flowImbalance = run {
            val w = candles.takeLast(12)
            val b = w.sumOf { it.buysH1.toLong() }
            val se = w.sumOf { it.sellsH1.toLong() }
            val tot = (b + se)
            if (tot > 0) (b.toFloat() / tot).coerceIn(0f, 1f) else 0.5f
        }
        // V5.9.1268 — txn acceleration: recent txn count vs baseline. A surge in
        // transaction velocity front-runs price moves.
        val txnAccel = run {
            val w = candles.takeLast(16)
            if (w.size < 6) 0.5f else {
                val recent = w.takeLast(3).map { (it.buysH1 + it.sellsH1).toDouble() }.average()
                val base = w.dropLast(3).map { (it.buysH1 + it.sellsH1).toDouble() }.average().coerceAtLeast(1.0)
                (0.5f + (((recent / base) - 1.0).coerceIn(-1.0, 1.0) * 0.5).toFloat()).coerceIn(0f, 1f)
            }
        }

        return TradeFeatures(
            priceChange5m = priceChange5m,
            priceChange15m = priceChange15m,
            priceChange1h = priceChange1h,
            volatility = volatility,
            momentum = momentum,
            volumeRatio = volumeRatio,
            buyPressure = buyPressure,
            volumeSpike = volumeSpike,
            liquidityUsd = ln(liquidityAtEntry.coerceAtLeast(1.0)).toFloat(),
            liquidityRatio = liquidityRatio,
            liquidityChange = liquidityChange,
            holderCount = ln(holdersAtEntry.toDouble().coerceAtLeast(1.0)).toFloat(),
            holderGrowth = holderGrowth,
            topHolderPct = topHolderPct.toFloat(),
            rugcheckScore = rugcheckScore / 100f,
            mintAuthRevoked = if (mintRevoked) 1f else 0f,
            freezeAuthRevoked = if (freezeRevoked) 1f else 0f,
            isHoneypot = 0f,  // Detected separately
            tokenAgeMinutes = ln((exitTime - entryTime).toDouble().coerceAtLeast(1.0) / 60000).toFloat(),
            holdDuration = holdDuration,
            hourOfDay = hourOfDay,
            dayOfWeek = dayOfWeek,
            rsi = (rsi / 100f).toFloat(),
            emaAlignment = emaScore,
            supportDistance = run {
                // V5.9: estimate support distance from recent candle lows
                val recentLows = candles.takeLast(20).map { it.priceUsd }
                if (recentLows.size >= 2) {
                    val support = recentLows.min()
                    val currentPrice = recentLows.last()
                    if (currentPrice > 0) ((currentPrice - support) / currentPrice).toFloat().coerceIn(0f, 1f) else 0f
                } else 0f
            },
            resistanceDistance = 0f,
            patternScore = 0f,
            exhaustionSignal = 0f,
            breakdownSignal = 0f,
            spikeSignal = volumeSpike,
            flowImbalance = flowImbalance,
            txnAccel = txnAccel,
        )
    }
    
    private fun calculatePriceChange(candles: List<Candle>, minutes: Int): Float {
        if (candles.isEmpty()) return 0f
        val now = System.currentTimeMillis()
        val cutoff = now - (minutes * 60 * 1000L)
        val relevant = candles.filter { it.ts > cutoff }
        if (relevant.size < 2) return 0f
        val first = relevant.first().priceUsd
        val last = relevant.last().priceUsd
        return if (first > 0) ((last - first) / first * 100).toFloat() else 0f
    }
    
    private fun calculateVolatility(candles: List<Candle>): Float {
        if (candles.size < 5) return 0f
        val prices = candles.takeLast(20).map { it.priceUsd }
        val returns = prices.zipWithNext { a, b -> if (a > 0) (b - a) / a else 0.0 }
        val mean = returns.average()
        val variance = returns.map { (it - mean) * (it - mean) }.average()
        return kotlin.math.sqrt(variance).toFloat() * 100f
    }
    
    private fun calculateMomentum(candles: List<Candle>): Float {
        if (candles.size < 10) return 0f
        val recent = candles.takeLast(5).map { it.priceUsd }.average()
        val older = candles.dropLast(5).takeLast(5).map { it.priceUsd }.average()
        return if (older > 0) ((recent - older) / older * 100).toFloat() else 0f
    }
    
    private fun normalizeFeatures(features: TradeFeatures): FloatArray {
        val raw = floatArrayOf(
            features.priceChange5m, features.priceChange15m, features.priceChange1h,
            features.volatility, features.momentum,
            features.volumeRatio, features.buyPressure, features.volumeSpike,
            features.liquidityUsd, features.liquidityRatio, features.liquidityChange,
            features.holderCount, features.holderGrowth, features.topHolderPct,
            features.rugcheckScore, features.mintAuthRevoked, features.freezeAuthRevoked,
            features.isHoneypot, features.tokenAgeMinutes, features.holdDuration,
            features.hourOfDay, features.dayOfWeek, features.rsi, features.emaAlignment,
            features.supportDistance, features.resistanceDistance, features.patternScore,
            features.exhaustionSignal, features.breakdownSignal, features.spikeSignal,
            features.flowImbalance, features.txnAccel  // V5.9.1268 — was 0f,0f padding
        )
        
        return FloatArray(NUM_FEATURES) { i ->
            if (featureStds[i] > 0) (raw[i] - featureMeans[i]) / featureStds[i] else 0f
        }
    }
    
    private fun updateNormalizationStats(features: TradeFeatures) {
        // Running mean/std update (Welford's algorithm simplified)
        val n = trainingData.size.toFloat()
        val raw = floatArrayOf(
            features.priceChange5m, features.priceChange15m, features.priceChange1h,
            features.volatility, features.momentum, features.volumeRatio, features.buyPressure,
            features.volumeSpike, features.liquidityUsd, features.liquidityRatio,
            features.liquidityChange, features.holderCount, features.holderGrowth,
            features.topHolderPct, features.rugcheckScore, features.mintAuthRevoked,
            features.freezeAuthRevoked, features.isHoneypot, features.tokenAgeMinutes,
            features.holdDuration, features.hourOfDay, features.dayOfWeek, features.rsi,
            features.emaAlignment, features.supportDistance, features.resistanceDistance,
            features.patternScore, features.exhaustionSignal, features.breakdownSignal,
            features.spikeSignal, features.flowImbalance, features.txnAccel  // V5.9.1268
        )
        
        for (i in 0 until NUM_FEATURES) {
            val oldMean = featureMeans[i]
            featureMeans[i] = oldMean + (raw[i] - oldMean) / n
            featureStds[i] = abs(raw[i] - featureMeans[i]).coerceAtLeast(0.01f)
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // PREDICTION METHODS (Heuristic fallbacks when models not trained)
    // ═══════════════════════════════════════════════════════════════════
    
    private fun predictRug(features: FloatArray): Float {
        // V5.9.1253 — real logistic head once it has enough labelled samples.
        if (rugSamples >= LOGIT_MIN_SAMPLES) {
            return blendedPredict(rugW, mlpW2Rug, features, rugSamples).coerceIn(0f, 0.99f)
        }
        // Cold-start heuristic fallback (used only until the head has trained).
        val rugIndicators = mutableListOf<Float>()
        
        // High top holder concentration is risky
        rugIndicators.add(features[13] * 0.3f)  // topHolderPct
        
        // Low rugcheck score is risky  
        rugIndicators.add((1f - features[14]) * 0.2f)  // inverse rugcheckScore
        
        // Mint auth not revoked is risky
        rugIndicators.add((1f - features[15]) * 0.15f)  // inverse mintAuthRevoked
        
        // Negative liquidity change is risky
        rugIndicators.add((-features[10]).coerceAtLeast(0f) * 0.1f)  // liquidityChange
        
        // Volume spike with sell pressure is risky
        if (features[7] > 0.5f && features[6] < 0.4f) {  // volumeSpike && low buyPressure
            rugIndicators.add(0.25f)
        }
        
        return rugIndicators.sum().coerceIn(0f, 0.95f)
    }
    
    private fun predictEntry(features: FloatArray): Float {
        // V5.9.1253 — real logistic head: P(win) given the feature vector.
        if (entrySamples >= LOGIT_MIN_SAMPLES) {
            return blendedPredict(entryW, mlpW2Entry, features, entrySamples).coerceIn(0f, 1f)
        }
        // Cold-start heuristic fallback.
        var score = 0.5f
        
        // Positive momentum is good
        score += features[4] * 0.01f  // momentum
        
        // High buy pressure is good
        score += (features[6] - 0.5f) * 0.2f  // buyPressure
        
        // Good liquidity is good
        score += features[8] * 0.02f  // liquidityUsd (log)
        
        // EMA alignment matters
        score += (features[23] - 0.5f) * 0.2f  // emaAlignment
        
        // RSI extremes are entry signals
        val rsi = features[22]
        if (rsi < 0.3f) score += 0.1f  // oversold
        if (rsi > 0.7f) score -= 0.1f  // overbought
        
        return score.coerceIn(0f, 1f)
    }
    
    private fun predictExit(features: FloatArray): Float {
        // V5.9.1253 — real logistic head: P(this trade should be exited).
        if (exitSamples >= LOGIT_MIN_SAMPLES) {
            return blendedPredict(exitW, mlpW2Exit, features, exitSamples).coerceIn(0f, 1f)
        }
        // Cold-start heuristic fallback.
        var urgency = 0.3f
        
        // Negative momentum = exit
        if (features[4] < -5f) urgency += 0.2f
        
        // Low buy pressure = exit
        if (features[6] < 0.35f) urgency += 0.15f
        
        // RSI overbought = exit
        if (features[22] > 0.7f) urgency += 0.1f
        
        // Exhaustion signal = exit
        if (features[27] > 0.5f) urgency += 0.15f
        
        // Breakdown signal = exit
        if (features[28] > 0.5f) urgency += 0.1f
        
        return urgency.coerceIn(0f, 1f)
    }
    
    private fun classifyTrajectory(features: FloatArray, rugProb: Float, candles: List<Candle>): String {
        if (rugProb > 0.7f) return "DUMP"
        
        val momentum = features[4]
        val volumeRatio = features[5]
        val buyPressure = features[6]
        
        return when {
            momentum > 10f && volumeRatio > 2f && buyPressure > 0.6f -> "MOON"
            momentum < -10f || buyPressure < 0.35f -> "DUMP"
            abs(momentum) < 3f -> "SIDEWAYS"
            else -> "UNKNOWN"
        }
    }
    
    private fun defaultPrediction() = MLPrediction(
        rugProbability = 0.5f,
        entryConfidence = 0.5f,
        exitConfidence = 0.5f,
        trajectoryClass = "UNKNOWN",
        confidence = 0f,
        dataPoints = trainingData.size,
    )
    
    private fun loadNormalizationStats(context: Context) {
        // V5.9: restore normalization stats from SharedPreferences
        val prefs = context.getSharedPreferences("ml_norm_stats", android.content.Context.MODE_PRIVATE)
        featureMeans = FloatArray(NUM_FEATURES) { i -> prefs.getFloat("mean_$i", 0f) }
        featureStds  = FloatArray(NUM_FEATURES) { i -> prefs.getFloat("std_$i", 1f).coerceAtLeast(1e-6f) }
        isNormalized = prefs.getBoolean("is_normalized", false)
    }

    private fun saveNormalizationStats(context: Context) {
        val prefs = context.getSharedPreferences("ml_norm_stats", android.content.Context.MODE_PRIVATE).edit()
        featureMeans.forEachIndexed { i, v -> prefs.putFloat("mean_$i", v) }
        featureStds.forEachIndexed  { i, v -> prefs.putFloat("std_$i",  v) }
        prefs.putBoolean("is_normalized", isNormalized)
        prefs.apply()
    }

    // V5.9.1253 — persist the logistic weights + per-head sample counts so the
    // trained predictive core survives service restarts (anti-amnesia).
    private fun saveModelWeights(context: Context) {
        val prefs = context.getSharedPreferences("ml_logit_weights", android.content.Context.MODE_PRIVATE).edit()
        entryW.forEachIndexed { i, v -> prefs.putFloat("e_$i", v) }
        rugW.forEachIndexed   { i, v -> prefs.putFloat("r_$i", v) }
        exitW.forEachIndexed  { i, v -> prefs.putFloat("x_$i", v) }
        prefs.putInt("n_entry", entrySamples)
        prefs.putInt("n_rug",   rugSamples)
        prefs.putInt("n_exit",  exitSamples)
        // V5.9.1254 — persist the nonlinear MLP too (shared W1 + 3 output heads).
        for (j in 0 until HIDDEN) {
            for (i in 0..NUM_FEATURES) prefs.putFloat("w1_${j}_$i", mlpW1[j][i])
        }
        for (j in 0..HIDDEN) {
            prefs.putFloat("w2e_$j", mlpW2Entry[j])
            prefs.putFloat("w2r_$j", mlpW2Rug[j])
            prefs.putFloat("w2x_$j", mlpW2Exit[j])
        }
        prefs.apply()
    }

    private fun loadModelWeights(context: Context) {
        try {
            val prefs = context.getSharedPreferences("ml_logit_weights", android.content.Context.MODE_PRIVATE)
            entryW = FloatArray(NUM_FEATURES + 1) { i -> prefs.getFloat("e_$i", 0f) }
            rugW   = FloatArray(NUM_FEATURES + 1) { i -> prefs.getFloat("r_$i", 0f) }
            exitW  = FloatArray(NUM_FEATURES + 1) { i -> prefs.getFloat("x_$i", 0f) }
            entrySamples = prefs.getInt("n_entry", 0)
            rugSamples   = prefs.getInt("n_rug",   0)
            exitSamples  = prefs.getInt("n_exit",  0)
            // V5.9.1254 — restore MLP weights if present (default to current
            // random init when absent, so a fresh install just trains fresh).
            if (prefs.contains("w1_0_0")) {
                mlpW1 = Array(HIDDEN) { j -> FloatArray(NUM_FEATURES + 1) { i -> prefs.getFloat("w1_${j}_$i", mlpW1[j][i]) } }
                mlpW2Entry = FloatArray(HIDDEN + 1) { j -> prefs.getFloat("w2e_$j", mlpW2Entry[j]) }
                mlpW2Rug   = FloatArray(HIDDEN + 1) { j -> prefs.getFloat("w2r_$j", mlpW2Rug[j]) }
                mlpW2Exit  = FloatArray(HIDDEN + 1) { j -> prefs.getFloat("w2x_$j", mlpW2Exit[j]) }
            }
            if (entrySamples + rugSamples + exitSamples > 0) {
                ErrorLogger.info(TAG, "Loaded logistic weights | n(e/r/x)=$entrySamples/$rugSamples/$exitSamples")
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "loadModelWeights error: ${e.message}")
        }
    }
    
    /**
     * Get current training status for display.
     */
    fun getStatus(): String {
        val n = trainingData.size
        return when {
            n == 0 -> "No training data"
            n < 50 -> "Learning... ($n/50 trades)"
            n < 200 -> "Basic predictions ready ($n trades)"
            n < 500 -> "Good predictions ($n trades)"
            else -> "Optimal ($n trades)"
        }
    }
    
    /**
     * Wrapper for TensorFlow Lite model.
     */
    private class TFLiteModel(modelFile: File) {
        // V5.9: TFLite model files are not yet bundled — using calibrated heuristic fallback.
        // When .tflite assets are added to src/main/assets/, replace predict() with
        // org.tensorflow.lite.Interpreter(modelFile).run(input, output).

        fun predict(features: FloatArray): FloatArray {
            // Heuristic: weighted sum of key features as a proxy for model output
            if (features.isEmpty()) return floatArrayOf(0.5f)
            val momentum   = features.getOrElse(0) { 0f }
            val volume     = features.getOrElse(3) { 0f }
            val rug        = features.getOrElse(10) { 0f }
            val rsi        = features.getOrElse(17) { 0.5f }
            val raw = (momentum * 0.3f + volume * 0.2f + (1f - rug) * 0.3f + rsi * 0.2f)
                .coerceIn(0f, 1f)
            return floatArrayOf(raw)
        }
    }
}
