package com.mandios.voicecounter

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.mandios.voicecounter.databinding.ActivitySettingsBinding
import androidx.core.content.edit

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    /** Tracks whether the "Say result" text editor is currently expanded. */
    private var isSayResultExpanded = false

    private val languages = listOf(
        "English (US)" to "en-US",
        "English (UK)" to "en-GB",
        "Українська"   to "uk-UA",
        "Norsk"        to "no-NO",
        "Suomi"        to "fi-FI",
        "Русский"      to "ru-RU",
        "Hrvatski"     to "hr-HR",
        "Deutsch"      to "de-DE",
        "Español"      to "es-ES",
        "Français"     to "fr-FR",
        "Italiano"     to "it-IT",
        "Português"    to "pt-BR",
        "日本語"        to "ja-JP",
        "中文"          to "zh-CN",
        "한국어"        to "ko-KR",
        "Polski"       to "pl-PL",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        // ── Language Spinner ────────────────────────────────────────────────
        val langNames = languages.map { it.first }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, langNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerLanguage.adapter = adapter

        val savedLang = prefs.getString("language", "en-US") ?: "en-US"
        val langIndex = languages.indexOfFirst { it.second == savedLang }.coerceAtLeast(0)
        binding.spinnerLanguage.setSelection(langIndex)

        // ── Offline Switch ──────────────────────────────────────────────────
        val offlineEnabled = prefs.getBoolean("offline_mode", false)
        binding.switchOffline.isChecked = offlineEnabled

        // Apply initial overlay state
        applyConfidenceOverlay(offlineEnabled)

        binding.switchOffline.setOnCheckedChangeListener { _, isChecked ->
            applyConfidenceOverlay(isChecked)
            updateConfidenceLabel(binding.seekBarConfidence.progress)
        }

        // ── Confidence SeekBar ──────────────────────────────────────────────
        val savedConfidence = prefs.getFloat("confidence_threshold", 0.5f)
        binding.seekBarConfidence.max = 100
        binding.seekBarConfidence.progress = (savedConfidence * 100).toInt()
        updateConfidenceLabel(binding.seekBarConfidence.progress)

        binding.seekBarConfidence.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                updateConfidenceLabel(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        // ── Trigger Word ────────────────────────────────────────────────────
        binding.etTriggerWord.setText(prefs.getString("trigger_word", "click"))

        // ── Increment Value ─────────────────────────────────────────────────
        binding.etIncrementValue.setText(prefs.getInt("increment_value", 1).toString())

        // ── Say Result ──────────────────────────────────────────────────────
        val sayResultEnabled = prefs.getBoolean("say_result", false)
        binding.switchSayResult.isChecked = sayResultEnabled
        binding.etSayResultText.setText(
            prefs.getString("say_result_text", "").takeUnless { it.isNullOrEmpty() } ?: ""
        )

        // Auto-expand if already enabled, so user can see/edit the phrase
        if (sayResultEnabled) {
            expandSayResult(animate = false)
        }

        binding.btnSayResultArrow.setOnClickListener {
            if (isSayResultExpanded) collapseSayResult() else expandSayResult()
        }

        // ── SpeechR Debug Mode ──────────────────────────────────────────────
        binding.switchDebugMode.isChecked = prefs.getBoolean("debug_mode", false)

        // ── Save Button ─────────────────────────────────────────────────────
        binding.btnSave.setOnClickListener {
            val selectedLang   = languages[binding.spinnerLanguage.selectedItemPosition].second
            val offline        = binding.switchOffline.isChecked
            val confidence     = binding.seekBarConfidence.progress / 100f
            val triggerWord    = binding.etTriggerWord.text.toString().trim()
            val incrementText  = binding.etIncrementValue.text.toString().trim()
            val increment      = incrementText.toIntOrNull() ?: 1
            val sayResult      = binding.switchSayResult.isChecked
            val sayResultText  = binding.etSayResultText.text.toString().trim()
                .ifEmpty { "Clicked %s times already" }
            val debugMode      = binding.switchDebugMode.isChecked

            if (triggerWord.isEmpty()) {
                binding.etTriggerWord.error = "Enter trigger-word"
                return@setOnClickListener
            }

            prefs.edit {
                putString("language",          selectedLang)
                putBoolean("offline_mode",     offline)
                putFloat("confidence_threshold", confidence)
                putString("trigger_word",      triggerWord)
                putInt("increment_value",      increment)
                putBoolean("say_result",       sayResult)
                putString("say_result_text",   sayResultText)
                putBoolean("debug_mode",       debugMode)
            }

            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            finish()
        }

        // ── Back Button ─────────────────────────────────────────────────────
        binding.btnBack.setOnClickListener { finish() }

        supportActionBar?.hide()
    }

    // ── Helpers ────────────────────────────────────────────────────────────
    private fun applyConfidenceOverlay(offline: Boolean) {
        binding.confidenceOverlay.visibility = if (offline) View.VISIBLE else View.GONE
    }

    private fun expandSayResult(animate: Boolean = true) {
        isSayResultExpanded = true
        binding.ivSayResultChevron.animate()
            .rotation(-90f)
            .setDuration(220)
            .start()

        val container = binding.sayResultExtra

        if (!animate) {
            container.visibility = View.VISIBLE
            return
        }

        container.measure(
            View.MeasureSpec.makeMeasureSpec(container.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val targetHeight = container.measuredHeight

        container.layoutParams.height = 0
        container.clipChildren = true
        container.visibility = View.VISIBLE

        ValueAnimator.ofInt(0, targetHeight).apply {
            duration = 250
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { animator ->
                container.layoutParams.height = animator.animatedValue as Int
                container.requestLayout()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // Height back to wrap_content
                    container.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    container.requestLayout()
                }
            })
            start()
        }
    }

    private fun collapseSayResult() {
        isSayResultExpanded = false
        binding.ivSayResultChevron.animate()
            .rotation(0f)
            .setDuration(220)
            .start()

        val container = binding.sayResultExtra
        val initialHeight = container.height

        ValueAnimator.ofInt(initialHeight, 0).apply {
            duration = 220
            interpolator = android.view.animation.AccelerateInterpolator()
            addUpdateListener { animator ->
                container.layoutParams.height = animator.animatedValue as Int
                container.requestLayout()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    container.visibility = View.GONE
                    container.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
            })
            start()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateConfidenceLabel(progress: Int) {
        val value = progress / 100f
        val label = when {
            value < 0.3f -> "Low (%.0f%%)".format(value * 100)
            value < 0.6f -> "Middle (%.0f%%)".format(value * 100)
            value < 0.8f -> "High (%.0f%%)".format(value * 100)
            else         -> "Maximum (%.0f%%)".format(value * 100)
        }
        binding.tvConfidenceValue.text = label
    }
}