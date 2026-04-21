package com.mandios.voicecounter

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.mandios.voicecounter.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var prefs: SharedPreferences

    private var counter = 0
    private var isListening = false
    private val handler = Handler(Looper.getMainLooper())
    private val settingsLauncher = registerForActivityResult(
                                  ActivityResultContracts.StartActivityForResult()) {}

    // ── Text-to-Speech ──────────────────────────────────────────────────────
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // ── Periodic network check ──────────────────────────────────────────────
    private val networkCheckRunnable = object : Runnable {
        override fun run() {
            updateNetworkBanner()
            handler.postDelayed(this, 4_000)
        }
    }

    companion object {
        private const val PREF_COUNTER          = "counter_value"
        private const val PERMISSION_REQUEST_CODE = 100
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        counter = prefs.getInt(PREF_COUNTER, 0)
        updateCounterDisplay()

        // TTS init (async — callback sets ttsReady=true)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                val langTag = prefs.getString("language", "en-US") ?: "en-US"
                tts?.language = Locale.forLanguageTag(langTag)
            }
        }

        binding.btnListen.setOnClickListener {
            if (isListening) stopListening() else checkPermissionAndStartListening()
        }

        binding.btnReset.setOnClickListener {
            counter = 0
            updateCounterDisplay()
            prefs.edit { putInt(PREF_COUNTER, 0) }
        }

        binding.btnSettings.setOnClickListener {
            val wasListening = isListening
            if (wasListening) stopListening()
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-sync TTS language in case it was changed in Settings
        val langTag = prefs.getString("language", "en-US") ?: "en-US"
        if (ttsReady) tts?.language = Locale.forLanguageTag(langTag)

        // Start periodic network status checks
        handler.post(networkCheckRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(networkCheckRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        tts?.shutdown()
        tts = null
    }

    // ── Utils ───────────────────────────────────────────────────────────────
    private fun updateNetworkBanner() {
        val offlineMode = prefs.getBoolean("offline_mode", false)
        val hasInternet = isNetworkAvailable()
        val show = !offlineMode && !hasInternet
        binding.tvNoInternet.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    // ── Counter display ─────────────────────────────────────────────────────
    private fun updateCounterDisplay() {
        binding.tvCounter.text = counter.toString()
    }

    // ── Speech recognition ──────────────────────────────────────────────────
    private fun checkPermissionAndStartListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSION_REQUEST_CODE
            )
        } else {
            startListening()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            Toast.makeText(this, "No permission for microphone", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(
                this,
                "Google speech recognition is unavailable on this device",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        isListening = true
        binding.btnListen.text = "⏹ Stop"
        binding.listeningIndicator.animate().alpha(1f).setDuration(300).start()

        speechRecognizer = if (prefs.getBoolean("offline_mode", false) &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
        speechRecognizer.setRecognitionListener(createRecognitionListener())
        launchRecognition()
    }

    private fun launchRecognition() {
        if (!isListening) return

        val language = prefs.getString("language", "en-US") ?: "en-US"
        val offline  = prefs.getBoolean("offline_mode", false)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
            if (offline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        try {
            speechRecognizer.startListening(intent)
        } catch (_: Exception) {
            handler.postDelayed({ launchRecognition() }, 500)
        }
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {

            override fun onReadyForSpeech(params: Bundle?) {
                binding.listeningIndicator.animate().alpha(1f).setDuration(200).start()
            }

            override fun onBeginningOfSpeech() {
                binding.listeningIndicator.animate().alpha(0.4f).setDuration(200).start()
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {}

            @SuppressLint("SetTextI18n")
            override fun onResults(results: Bundle?) {
                val matches     = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val confidences = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)

                // ── Debug overlay ────────────────────────────────────────────
                if (prefs.getBoolean("debug_mode", false) && !matches.isNullOrEmpty()) {
                    val topPhrase = matches[0]
                    val topConf   = confidences?.getOrNull(0) ?: -1f
                    val confStr   = if (topConf < 0f) "n/a" else "%.2f".format(topConf)
                    binding.tvDebug.visibility = View.VISIBLE
                    binding.tvDebug.text       = "\" $topPhrase \"   conf: $confStr"
                } else {
                    binding.tvDebug.visibility = View.GONE
                }

                if (!matches.isNullOrEmpty()) {
                    checkForTrigger(matches, confidences)
                }

                // Restart for continuous listening
                if (isListening) {
                    handler.postDelayed({ launchRecognition() }, 150)
                }
            }

            override fun onError(error: Int) {
                if (!isListening) return

                // Clear debug text on silence / timeout so it doesn't stay stale
                if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ) {
                    if (!prefs.getBoolean("debug_mode", false)) {
                        binding.tvDebug.visibility = View.GONE
                    }
                }

                val delay = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH        -> 200L
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT  -> 200L
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1500L
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        Toast.makeText(
                            this@MainActivity,
                            "No permission for microphone",
                            Toast.LENGTH_SHORT
                        ).show()
                        stopListening()
                        return
                    }
                    else -> 700L
                }
                handler.postDelayed({
                    if (isListening) {
                        try { speechRecognizer.cancel() } catch (_: Exception) {}
                        launchRecognition()
                    }
                }, delay)
            }

            override fun onEndOfSpeech() {
                binding.listeningIndicator.animate().alpha(1f).setDuration(200).start()
            }
        }
    }

    // ── Trigger detection ───────────────────────────────────────────────────
    private fun checkForTrigger(matches: List<String>, confidences: FloatArray?) {
        val triggerWord = (prefs.getString("trigger_word", "click") ?: "click")
            .trim().lowercase()
        val threshold = prefs.getFloat("confidence_threshold", 0.5f)
        val increment = prefs.getInt("increment_value", 1)
        val offline   = prefs.getBoolean("offline_mode", false)

        for (i in matches.indices) {
            val match = matches[i].trim().lowercase()
            // Offline recognizers don't return reliable confidence scores — skip the check
            val confidence = confidences?.getOrNull(i)
                ?.let { if (it < 0f || offline) 1.0f else it }
                ?: 1.0f

            if (match.contains(triggerWord) && confidence >= threshold) {
                counter += increment
                updateCounterDisplay()
                prefs.edit { putInt(PREF_COUNTER, counter) }

                // Visual flash
                binding.tvCounter.animate()
                    .scaleX(1.15f).scaleY(1.15f).setDuration(100)
                    .withEndAction {
                        binding.tvCounter.animate()
                            .scaleX(1f).scaleY(1f).setDuration(100).start()
                    }.start()

                // TTS announcement
                speakResult()

                break
            }
        }
    }

    private fun speakResult() {
        if (!prefs.getBoolean("say_result", false)) return
        if (!ttsReady) return

        val template = prefs.getString("say_result_text", "")
            ?.takeUnless { it.isBlank() }
            ?: "Clicked %s times already"

        val spoken = template.replace("%s", counter.toString())
        tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "counter_result")
    }

    // ── Stop listening ──────────────────────────────────────────────────────
    @SuppressLint("SetTextI18n")
    private fun stopListening() {
        isListening = false
        binding.btnListen.text = "🎤 Listen"
        binding.listeningIndicator.animate().alpha(0f).setDuration(300).start()
        binding.tvDebug.visibility = View.GONE
        handler.removeCallbacksAndMessages(null)
        if (::speechRecognizer.isInitialized) {
            try {
                speechRecognizer.stopListening()
                speechRecognizer.destroy()
            } catch (_: Exception) {}
        }
    }
}