package com.cobrix.pocket.utils

import android.content.Context
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.speech.tts.TextToSpeech
import android.util.Log
import com.cobrix.pocket.R
import java.util.Locale

class SoundHelper(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e("SoundHelper", "Error inicializando TextToSpeech", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("es", "VE"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Fallback a español general
                tts?.setLanguage(Locale("es", "ES"))
            }
            isTtsInitialized = true
        } else {
            Log.e("SoundHelper", "Fallo al inicializar TTS: status $status")
        }
    }

    /**
     * Reproduce el sonido auténtico de caja registradora ("Ka-ching").
     */
    fun playCashRegisterSound() {
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

    /**
     * Anuncia el pago recibido mediante voz sintética clara sin duplicar el sonido de caja.
     */
    fun announcePayment(amount: Double) {
        if (!isTtsInitialized || tts == null) return

        try {
            val formatted = String.format(Locale.US, "%.0f", amount)
            val phrase = "Pago recibido. $formatted bolívares."
            tts?.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "PAYMENT_ANNOUNCE")
        } catch (e: Exception) {
            Log.e("SoundHelper", "Error anunciando voz", e)
        }
    }

    /**
     * Anuncia el egreso o gasto registrado por voz.
     */
    fun announceExpense(amount: Double) {
        if (!isTtsInitialized || tts == null) return

        try {
            val formatted = String.format(Locale.US, "%.0f", amount)
            val phrase = "Gasto registrado. $formatted bolívares."
            tts?.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "EXPENSE_ANNOUNCE")
        } catch (e: Exception) {
            Log.e("SoundHelper", "Error anunciando gasto por voz", e)
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}

