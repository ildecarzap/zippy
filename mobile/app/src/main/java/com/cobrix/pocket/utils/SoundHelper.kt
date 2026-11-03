package com.cobrix.pocket.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import com.cobrix.pocket.R
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

class SoundHelper(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    @Volatile
    private var isTtsInitialized = false
    private val pendingPhrasesQueue = ConcurrentLinkedQueue<String>()

    init {
        initTtsEngine()
    }

    private fun initTtsEngine() {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e("SoundHelper", "Error inicializando TextToSpeech", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            try {
                val audioAttrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts?.setAudioAttributes(audioAttrs)
            } catch (_: Exception) {}

            val result = tts?.setLanguage(Locale("es", "VE"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Fallback a español general
                tts?.setLanguage(Locale("es", "ES"))
            }
            isTtsInitialized = true

            // Si llegaron pagos mientras el motor de voz despertaba en segundo plano, anunciarlos ahora
            flushPendingPhrases()
        } else {
            Log.e("SoundHelper", "Fallo al inicializar TTS: status $status")
        }
    }

    private fun flushPendingPhrases() {
        if (!isTtsInitialized || tts == null) return
        var first = true
        while (pendingPhrasesQueue.isNotEmpty()) {
            val phrase = pendingPhrasesQueue.poll() ?: break
            try {
                val queueMode = if (first) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                first = false
                tts?.speak(phrase, queueMode, null, "PAYMENT_ANNOUNCE_${System.currentTimeMillis()}")
            } catch (e: Exception) {
                Log.e("SoundHelper", "Error vaciando cola TTS", e)
            }
        }
    }

    /**
     * Verifica el estado del timbre del teléfono:
     * - RINGER_MODE_VIBRATE: Solo vibración distintiva (no habla ni suena).
     * - RINGER_MODE_SILENT: Silencio total.
     * - RINGER_MODE_NORMAL: Voz / Sonido + vibración distintiva.
     */
    private fun getRingerMode(): Int {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        return audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
    }

    /**
     * Ejecuta un patrón de vibración rítmico único (3 pulsos cortos + 1 pulso largo potente: "ta-ta-ta — TAAA")
     * para diferenciar claramente un Pago Recibido de cualquier notificación común cuando el teléfono está en vibrador.
     */
    fun vibrateDistinctPaymentPattern() {
        try {
            val timings = longArrayOf(0L, 110L, 70L, 110L, 70L, 110L, 110L, 480L)
            val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255, 0, 255)

            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (vibrator == null || !vibrator.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (vibrator.hasAmplitudeControl()) {
                    VibrationEffect.createWaveform(timings, amplitudes, -1)
                } else {
                    VibrationEffect.createWaveform(timings, -1)
                }
                val audioAttrs = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, audioAttrs)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(timings, -1)
            }
        } catch (e: Exception) {
            Log.e("SoundHelper", "Error ejecutando vibración distintiva de pago", e)
        }
    }

    /**
     * Reproduce el sonido auténtico de caja registradora ("Ka-ching").
     * Si el teléfono está en modo Vibrar, ejecuta únicamente el patrón de vibración distintivo.
     */
    fun playCashRegisterSound() {
        val ringerMode = getRingerMode()
        if (ringerMode == AudioManager.RINGER_MODE_SILENT) return
        if (ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
            vibrateDistinctPaymentPattern()
            return
        }

        vibrateDistinctPaymentPattern()
        try {
            val mediaPlayer = MediaPlayer.create(context, R.raw.cash_register)
            if (mediaPlayer != null) {
                mediaPlayer.setOnCompletionListener { mp ->
                    mp.release()
                }
                mediaPlayer.start()
            } else {
                playFallbackChime()
            }
        } catch (e: Exception) {
            Log.e("SoundHelper", "Error reproduciendo sonido de caja", e)
            playFallbackChime()
        }
    }

    /**
     * Alias de retrocompatibilidad para sonido de caja.
     */
    fun playChime() {
        playCashRegisterSound()
    }

    private fun playFallbackChime() {
        try {
            val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(context, notificationUri)
            ringtone?.play()
        } catch (e: Exception) {
            Log.e("SoundHelper", "Error reproduciendo chime fallback", e)
        }
    }

    private fun speakOrQueue(phrase: String, utteranceId: String) {
        val ringerMode = getRingerMode()
        if (ringerMode == AudioManager.RINGER_MODE_SILENT) return
        if (ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
            // Si el teléfono está en vibrador, no hablar: emitir la vibración distintiva de cobro
            vibrateDistinctPaymentPattern()
            return
        }

        // En modo normal: vibración distintiva + anuncio de voz (con soporte de cola en frío)
        vibrateDistinctPaymentPattern()
        if (!isTtsInitialized || tts == null) {
            pendingPhrasesQueue.offer(phrase)
            if (tts == null) {
                initTtsEngine()
            }
            return
        }

        try {
            val res = tts?.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (res == TextToSpeech.ERROR) {
                // Si el servicio TTS del sistema se desconectó en segundo plano, reconectar y encolar
                isTtsInitialized = false
                pendingPhrasesQueue.offer(phrase)
                initTtsEngine()
            }
        } catch (e: Exception) {
            Log.e("SoundHelper", "Error anunciando voz", e)
        }
    }

    /**
     * Anuncia el pago recibido mediante voz sintética clara sin duplicar el sonido de caja.
     */
    fun announcePayment(amount: Double) {
        val formatted = String.format(Locale.US, "%.0f", amount)
        val phrase = "Pago recibido. $formatted bolívares."
        speakOrQueue(phrase, "PAYMENT_ANNOUNCE")
    }

    /**
     * Anuncia un Pago Móvil que concilió automáticamente la deuda de un cliente en la Libreta.
     */
    fun announceReconciledPayment(amount: Double, debtorName: String) {
        val formatted = String.format(Locale.US, "%.0f", amount)
        val phrase = "Pago recibido de $formatted bolívares. Abonado a la cuenta de $debtorName."
        speakOrQueue(phrase, "PAYMENT_RECONCILED_ANNOUNCE")
    }

    /**
     * Anuncia el pago recibido en cripto / USDT por voz sintética.
     */
    fun announceCryptoPayment(amount: Double, currency: String = "dólares") {
        val formatted = if (amount % 1.0 == 0.0) String.format(Locale.US, "%.0f", amount) else String.format(Locale.US, "%.2f", amount)
        val phrase = "Pago recibido por Binance Pay. $formatted $currency."
        speakOrQueue(phrase, "PAYMENT_ANNOUNCE")
    }

    /**
     * Anuncia el egreso o gasto registrado por voz.
     */
    fun announceExpense(amount: Double) {
        val formatted = String.format(Locale.US, "%.0f", amount)
        val phrase = "Gasto registrado. $formatted bolívares."
        speakOrQueue(phrase, "EXPENSE_ANNOUNCE")
    }

    fun shutdown() {
        pendingPhrasesQueue.clear()
        tts?.stop()
        tts?.shutdown()
    }
}

