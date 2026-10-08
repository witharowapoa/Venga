package au.nick.venga

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {

    // ---------- palette ----------
    // Spain's flag colours: rojo #AA151B and gualda (gold) #F1BF00. Set by applyPalette().
    private var dark = false
    private var bg = 0
    private var surface = 0
    private var ink = 0
    private var muted = 0
    private var line = 0
    private var red = 0
    private var redDark = 0
    private var redSoft = 0
    private var gold = 0
    private var green = 0
    private var greenSoft = 0
    private var amber = 0
    private var amberSoft = 0
    private var chipGrey = 0

    private fun c(hex: String) = Color.parseColor(hex)

    /** Appearance setting: 0 = follow phone, 1 = light, 2 = dark. */
    private fun applyPalette() {
        dark = when (settings.getInt("appearance", 0)) {
            1 -> false
            2 -> true
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        gold = c("#F1BF00")
        if (dark) {
            bg = c("#141110"); surface = c("#221D1B"); ink = c("#F5EFE6"); muted = c("#A99F94"); line = c("#3A322E")
            red = c("#E5373F"); redDark = c("#AA151B"); redSoft = c("#3E1517")
            green = c("#5CC48A"); greenSoft = c("#15301F"); amber = c("#F1BF00"); amberSoft = c("#3A2E05")
            chipGrey = c("#2F2826")
        } else {
            bg = c("#FFF8EA"); surface = Color.WHITE; ink = c("#1F1A17"); muted = c("#6F665E"); line = c("#ECE0C8")
            red = c("#AA151B"); redDark = c("#850F14"); redSoft = c("#FBE2E3")
            green = c("#2E7D4F"); greenSoft = c("#E2F2E8"); amber = c("#7A5900"); amberSoft = c("#FFF0B8")
            chipGrey = c("#F4ECDC")
        }
        window.statusBarColor = redDark
        window.navigationBarColor = bg
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }

    /** A thin red-gold-red band, like the flag. */
    private fun flagStripe(height: Int = 6): LinearLayout = column().apply {
        listOf(red, gold, gold, red).forEach { col ->
            addView(View(this@MainActivity).apply { setBackgroundColor(col) }, LinearLayout.LayoutParams(match, dp(height) / 2))
        }
    }

    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var settings: SharedPreferences
    private lateinit var deck: Deck
    private val handler = Handler(Looper.getMainLooper())

    // ---------- text to speech ----------
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsFailed = false
    private var spanishVoices: List<Voice> = emptyList()
    private var voiceHolder: LinearLayout? = null

    // ---------- speech recognition ----------
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    private enum class Screen { HOME, PRACTICE, SUMMARY, WORDS }
    private var screen = Screen.HOME

    // ---------- session ----------
    private var queue = mutableListOf<Card>()
    private var pos = 0
    private var extraPractice = false
    private val requeues = HashMap<String, Int>()
    private val missed = LinkedHashSet<String>()
    private val sessionSeen = LinkedHashSet<String>()
    private var answered = false
    private var snapshot: CardState? = null
    private var wasMissedBefore = false
    private var requeuedAt = -1

    // ---------- practice views ----------
    private var micButton: TextView? = null
    private var statusText: TextView? = null
    private var partialText: TextView? = null
    private var controls: LinearLayout? = null
    private var typeBox: LinearLayout? = null
    private var typeInput: EditText? = null
    private var resultBox: LinearLayout? = null

    // =====================================================================
    // Lifecycle
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = getSharedPreferences("settings", MODE_PRIVATE)
        applyPalette()
        deck = Deck(this, settings)
        tts = TextToSpeech(applicationContext) { status -> handler.post { onTtsInit(status) } }
        showHome()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val wasDark = dark
        applyPalette()
        if (dark != wasDark) redraw()
    }

    /** Rebuild the current screen in the new colours (a card already answered keeps its result until Next). */
    private fun redraw() {
        when (screen) {
            Screen.HOME -> showHome()
            Screen.WORDS -> showWords()
            Screen.SUMMARY -> showSummary()
            Screen.PRACTICE -> if (!answered) showCard()
        }
    }

    override fun onPause() {
        super.onPause()
        stopListening()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (screen == Screen.HOME) super.onBackPressed() else showHome()
    }

    // =====================================================================
    // UI helpers
    // =====================================================================

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun rounded(color: Int, radius: Int = 16, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radius).toFloat()
            setColor(color)
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun label(s: CharSequence, size: Float = 16f, color: Int = ink, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.15f)
        }

    private fun button(
        caption: String,
        fill: Int,
        fg: Int,
        stroke: Int? = null,
        size: Float = 16f,
        onClick: () -> Unit
    ): TextView = TextView(this).apply {
        text = caption
        textSize = size
        setTextColor(fg)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = RippleDrawable(ColorStateList.valueOf(0x22000000), rounded(fill, 14, stroke), null)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun chip(s: String, fill: Int, fg: Int): TextView = TextView(this).apply {
        text = s
        textSize = 11f
        setTextColor(fg)
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.06f
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = rounded(fill, 20)
    }

    private fun lp(w: Int = match, h: Int = wrap, top: Int = 0, weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top) }

    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun column(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun panel(): LinearLayout = column().apply {
        background = rounded(surface, 18, line)
        setPadding(dp(18), dp(16), dp(18), dp(16))
    }

    private fun page(): LinearLayout {
        val root = column().apply { setPadding(dp(20), dp(20), dp(20), dp(32)) }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(bg)
            addView(root, ViewGroup.LayoutParams(match, wrap))
        }
        setContentView(scroll)
        return root
    }

    private fun sectionTitle(s: String): TextView = label(s.uppercase(Locale.ROOT), 12f, muted, true).apply {
        letterSpacing = 0.1f
    }

    private fun hideKeyboard() {
        val v = currentFocus ?: return
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }

    // =====================================================================
    // Home
    // =====================================================================

    private fun showHome() {
        stopListening()
        hideKeyboard()
        screen = Screen.HOME
        val root = page()

        root.addView(label("¡Venga!", 40f, red, true))
        root.addView(flagStripe(), lp(dp(72), wrap, top = 4))
        root.addView(label("Madrid Spanish, one card at a time.", 16f, muted), lp(top = 8))

        val now = System.currentTimeMillis()
        val stats = row().apply {
            background = rounded(surface, 18, line)
            setPadding(dp(8), dp(16), dp(8), dp(16))
        }
        stats.addView(statCell(deck.dueCount(now), "due"), lp(0, wrap, weight = 1f))
        stats.addView(statCell(deck.newAvailable(), "new"), lp(0, wrap, weight = 1f))
        stats.addView(statCell(deck.learnedTotal(), "learned"), lp(0, wrap, weight = 1f))
        root.addView(stats, lp(top = 20))

        val (plan, extra) = deck.buildSession(sessionSize(), newPerSession(), now)
        val startText = when {
            plan.isEmpty() -> "Nothing to practise"
            extra -> "All caught up · practise ahead"
            else -> "Empezar · ${plan.size} cards"
        }
        root.addView(button(startText, red, Color.WHITE, size = 18f) {
            if (plan.isNotEmpty()) startSession()
        }.apply { setPadding(dp(16), dp(18), dp(16), dp(18)) }, lp(top = 16))

        // Levels
        root.addView(sectionTitle("Niveles"), lp(top = 30))
        for (level in Levels.all) root.addView(levelPanel(level), lp(top = 10))

        root.addView(button("Word list", surface, ink, line) { showWords() }, lp(top = 16))

        // Settings
        root.addView(sectionTitle("Ajustes"), lp(top = 30))
        val s = panel()
        s.addView(cycleRow("Cards per session", "session_size", listOf(10, 20, 30), 20) { "$it" })
        s.addView(cycleRow("New words per session", "new_per_session", listOf(5, 10, 15, 20), 10) { "$it" }, lp(top = 10))
        s.addView(switchRow("Include crude words", "include_vulgar", true), lp(top = 10))
        s.addView(switchRow("Say the answer after each card", "auto_play", true), lp(top = 4))
        s.addView(cycleRow("Speaking speed", "speech_rate", listOf(75, 90, 100), 90) { "$it%" }, lp(top = 10))
        s.addView(cycleRow("Appearance", "appearance", listOf(0, 1, 2), 0) {
            when (it) { 1 -> "Light"; 2 -> "Dark"; else -> "Match phone" }
        }, lp(top = 10))
        val vh = column()
        voiceHolder = vh
        s.addView(vh, lp(top = 10))
        refreshVoiceRow()
        root.addView(s, lp(top = 10))

        root.addView(button("Reset all progress", Color.TRANSPARENT, red, size = 14f) { confirmReset() }, lp(top = 16))

        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
        root.addView(label("Venga $version", 12f, muted).apply { gravity = Gravity.CENTER }, lp(top = 8))
    }

    private fun statCell(n: Int, caption: String): LinearLayout = column().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        addView(label(n.toString(), 26f, ink, true).apply { gravity = Gravity.CENTER })
        addView(label(caption, 13f, muted).apply { gravity = Gravity.CENTER })
    }

    private fun levelPanel(level: Int): LinearLayout {
        val p = panel()
        val unlocked = deck.isUnlocked(level)
        val head = row()
        head.addView(label(Levels.name(level), 18f, if (unlocked) ink else muted, true), lp(0, wrap, weight = 1f))
        head.addView(label(if (unlocked) "" else "🔒", 16f))
        p.addView(head)
        p.addView(label(Levels.blurb(level), 14f, muted), lp(top = 2))

        val total = deck.levelCards(level).size
        val learned = deck.learnedCount(level)
        val pct = if (total == 0) 0 else learned * 100 / total
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = pct
            progressTintList = ColorStateList.valueOf(if (unlocked) gold else muted)
            progressBackgroundTintList = ColorStateList.valueOf(line)
        }
        p.addView(bar, lp(top = 10))
        p.addView(label("$learned of $total learned · $pct%", 13f, muted), lp(top = 2))

        val pre = Levels.prerequisite(level)
        if (!unlocked && pre != null) {
            val need = (Levels.UNLOCK_SHARE * 100).toInt()
            p.addView(
                label("Unlocks when $need% of ${Levels.name(pre)} is learned.", 13f, muted),
                lp(top = 8)
            )
            p.addView(button("Unlock now", surface, red, red, 14f) {
                deck.unlock(level)
                showHome()
            }.apply { setPadding(dp(14), dp(8), dp(14), dp(8)) }, lp(wrap, wrap, top = 8))
        }
        return p
    }

    private fun cycleRow(title: String, key: String, options: List<Int>, default: Int, fmt: (Int) -> String): LinearLayout {
        val r = row()
        r.addView(label(title, 15f), lp(0, wrap, weight = 1f))
        val current = settings.getInt(key, default)
        val pill = button(fmt(current), chipGrey, ink, size = 14f) {}
        pill.setPadding(dp(14), dp(8), dp(14), dp(8))
        pill.setOnClickListener {
            val now = settings.getInt(key, default)
            val idx = options.indexOf(now)
            val next = options[(idx + 1).mod(options.size)]
            settings.edit().putInt(key, next).apply()
            if (key == "appearance") applyPalette()
            if (key == "session_size" || key == "new_per_session" || key == "appearance") showHome() else pill.text = fmt(next)
            if (key == "speech_rate") speak("Hola, ¿qué tal?", slow = false)
        }
        r.addView(pill)
        return r
    }

    private fun switchRow(title: String, key: String, default: Boolean): Switch = Switch(this).apply {
        text = title
        textSize = 15f
        setTextColor(ink)
        isChecked = settings.getBoolean(key, default)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(red, if (dark) muted else Color.WHITE))
        trackTintList = ColorStateList(states, intArrayOf(redSoft, line))
        setOnCheckedChangeListener { _, checked ->
            settings.edit().putBoolean(key, checked).apply()
            if (key == "include_vulgar") showHome()
        }
    }

    private fun refreshVoiceRow() {
        val holder = voiceHolder ?: return
        holder.removeAllViews()
        when {
            spanishVoices.isNotEmpty() -> {
                val r = row()
                r.addView(label("Madrid voice", 15f), lp(0, wrap, weight = 1f))
                val idx = currentVoiceIndex()
                val pill = button("Voice ${idx + 1} of ${spanishVoices.size}", chipGrey, ink, size = 14f) {
                    val next = (currentVoiceIndex() + 1) % spanishVoices.size
                    settings.edit().putString("voice", spanishVoices[next].name).apply()
                    tts?.setVoice(spanishVoices[next])
                    refreshVoiceRow()
                    speak("Hola, ¿qué tal? Soy de Madrid.", slow = false)
                }
                pill.setPadding(dp(14), dp(8), dp(14), dp(8))
                r.addView(pill)
                holder.addView(r)
                holder.addView(label("Tap to hear each Spain-Spanish voice and keep the one you like.", 12f, muted), lp(top = 4))
            }
            ttsReady -> holder.addView(label("Using your phone's Spain-Spanish voice.", 13f, muted))
            ttsFailed -> {
                holder.addView(label("No Spain-Spanish voice found on this phone.", 14f, red))
                holder.addView(button("Install Spanish (Spain) voice", surface, red, red, 14f) {
                    try {
                        startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
                    } catch (e: Exception) {
                        toast("Open Settings › Text-to-speech and add Spanish (Spain).")
                    }
                }.apply { setPadding(dp(14), dp(8), dp(14), dp(8)) }, lp(wrap, wrap, top = 6))
            }
            else -> holder.addView(label("Loading voice…", 13f, muted))
        }
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("Reset all progress?")
            .setMessage("Every word goes back to new and the harder levels lock again.")
            .setPositiveButton("Reset") { _, _ ->
                deck.resetAll()
                showHome()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()

    private fun sessionSize() = settings.getInt("session_size", 20)
    private fun newPerSession() = settings.getInt("new_per_session", 10)

    // =====================================================================
    // Practice
    // =====================================================================

    private fun startSession() {
        val (plan, extra) = deck.buildSession(sessionSize(), newPerSession(), System.currentTimeMillis())
        if (plan.isEmpty()) return
        queue = plan.toMutableList()
        extraPractice = extra
        pos = 0
        requeues.clear()
        missed.clear()
        sessionSeen.clear()
        showCard()
    }

    private fun showCard() {
        stopListening()
        hideKeyboard()
        if (pos >= queue.size) {
            showSummary()
            return
        }
        screen = Screen.PRACTICE
        answered = false
        snapshot = null
        requeuedAt = -1
        val card = queue[pos]
        val isNew = !deck.state(card).seen
        sessionSeen.add(card.id)

        val root = page()

        // top bar
        val top = row()
        top.addView(button("✕  Salir", Color.TRANSPARENT, muted, size = 14f) { showHome() }.apply {
            setPadding(dp(4), dp(8), dp(12), dp(8))
        })
        top.addView(View(this), lp(0, 1, weight = 1f))
        top.addView(label("${pos + 1} / ${queue.size}", 15f, muted, true))
        root.addView(top)

        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = queue.size
            progress = pos
            progressTintList = ColorStateList.valueOf(gold)
            progressBackgroundTintList = ColorStateList.valueOf(line)
        }
        root.addView(bar, lp(top = 4))

        // the card
        val cardView = column().apply {
            background = rounded(surface, 22, line)
            setPadding(dp(22), dp(20), dp(22), dp(26))
            elevation = dp(2).toFloat()
        }
        val chips = row()
        chips.addView(chip(Levels.name(card.level).uppercase(Locale.ROOT), chipGrey, ink))
        fun addChip(c: TextView) = chips.addView(c, lp(wrap, wrap).apply { leftMargin = dp(6) })
        if (isNew) addChip(chip("NEW", greenSoft, green))
        if (card.isSlang) addChip(chip("SLANG", amberSoft, amber))
        if (card.isVulgar) addChip(chip("CRUDE", redSoft, red))
        cardView.addView(chips)
        cardView.addView(label("How do you say…", 14f, muted), lp(top = 18))
        cardView.addView(label(card.english, 28f, ink, true), lp(top = 4))
        root.addView(cardView, lp(top = 14))

        // answer controls
        val c = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        controls = c

        val mic = TextView(this).apply {
            text = "🎤"
            textSize = 36f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(red)
                setStroke(dp(4), gold)
            }
            elevation = dp(4).toFloat()
            isClickable = true
            setOnClickListener { toggleListening() }
            contentDescription = "Speak your answer"
        }
        micButton = mic
        c.addView(mic, LinearLayout.LayoutParams(dp(104), dp(104)).apply { topMargin = dp(8) })

        val status = label("Tap the mic and say it in Spanish", 15f, muted).apply { gravity = Gravity.CENTER }
        statusText = status
        c.addView(status, lp(top = 14))

        val partial = label("", 18f, ink).apply {
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.ITALIC)
        }
        partialText = partial
        c.addView(partial, lp(top = 4))

        val alt = row()
        alt.addView(button("⌨  Type it", surface, ink, line, 14f) { showTyping() }, lp(0, wrap, weight = 1f))
        alt.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        alt.addView(button("Show answer", surface, ink, line, 14f) {
            stopListening()
            onAnswer(emptyList())
        }, lp(0, wrap, weight = 1f))
        c.addView(alt, lp(top = 18))

        val tb = column().apply { visibility = View.GONE }
        typeBox = tb
        val input = EditText(this).apply {
            hint = "Escribe en español…"
            textSize = 18f
            setTextColor(ink)
            setHintTextColor(muted)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
            background = rounded(surface, 14, line)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    submitTyped(v.text.toString()); true
                } else false
            }
        }
        typeInput = input
        tb.addView(input)
        tb.addView(button("Check", ink, bg) { submitTyped(input.text.toString()) }, lp(top = 8))
        c.addView(tb, lp(top = 14))

        root.addView(c, lp(top = 22))

        val rb = column().apply { visibility = View.GONE }
        resultBox = rb
        root.addView(rb, lp(top = 16))
    }

    private fun showTyping() {
        stopListening()
        val tb = typeBox ?: return
        tb.visibility = View.VISIBLE
        val input = typeInput ?: return
        input.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun submitTyped(text: String) {
        if (text.isBlank()) return
        hideKeyboard()
        onAnswer(listOf(text))
    }

    private fun onAnswer(heard: List<String>) {
        if (answered || pos >= queue.size) return
        answered = true
        stopListening()
        val card = queue[pos]
        val result = Matcher.check(card, heard)
        snapshot = deck.state(card).copy()
        wasMissedBefore = card.id in missed

        when (result.verdict) {
            Verdict.CORRECT, Verdict.CLOSE -> deck.recordCorrect(card)
            Verdict.WRONG, Verdict.REGIONAL, Verdict.TENSE -> {
                if (result.verdict == Verdict.WRONG) deck.recordWrong(card) else deck.recordNearMiss(card)
                missed.add(card.id)
                val n = requeues[card.id] ?: 0
                if (n < 2) {
                    val idx = minOf(pos + 4, queue.size)
                    queue.add(idx, card)
                    requeues[card.id] = n + 1
                    requeuedAt = idx
                }
            }
        }
        showResult(card, result)
    }

    private fun showResult(card: Card, result: Check) {
        controls?.visibility = View.GONE
        val box = resultBox ?: return
        box.removeAllViews()
        box.visibility = View.VISIBLE

        val heard = result.heard
        val (title, fg, fill) = when (result.verdict) {
            Verdict.CORRECT -> Triple("✓  ¡Muy bien!", green, greenSoft)
            Verdict.CLOSE -> Triple("✓  Close enough", green, greenSoft)
            Verdict.REGIONAL -> Triple("≈  Right idea, wrong city", amber, amberSoft)
            Verdict.TENSE -> Triple("≈  Right verb, wrong tense", amber, amberSoft)
            Verdict.WRONG -> if (heard.isBlank()) Triple("Here's the answer", ink, chipGrey) else Triple("✗  Not quite", red, redSoft)
        }

        val p = column().apply {
            background = rounded(fill, 18)
            setPadding(dp(18), dp(16), dp(18), dp(18))
        }
        p.addView(label(title, 20f, fg, true))
        if (heard.isNotBlank()) p.addView(label("You said: “$heard”", 15f, muted), lp(top = 4))
        val lead = when (result.verdict) {
            Verdict.REGIONAL -> "That's used outside Spain. In Madrid they say:"
            Verdict.TENSE -> "This one needs:"
            Verdict.CLOSE -> "The exact answer:"
            else -> "In Madrid:"
        }
        p.addView(label(lead, 14f, muted), lp(top = 12))
        p.addView(label(card.primary, 30f, ink, true), lp(top = 2))
        if (card.answers.size > 1) {
            p.addView(label("Also: " + card.answers.drop(1).joinToString(", "), 14f, muted), lp(top = 2))
        }

        val speakRow = row()
        speakRow.addView(button("🔊  Escuchar", red, Color.WHITE, size = 15f) { speak(card.primary, slow = false) }, lp(0, wrap, weight = 1f))
        speakRow.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        speakRow.addView(button("🐢  Despacio", surface, red, red, 15f) { speak(card.primary, slow = true) }, lp(0, wrap, weight = 1f))
        p.addView(speakRow, lp(top = 14))

        if (card.note.isNotBlank()) p.addView(label(card.note, 14f, ink), lp(top = 12))
        box.addView(p)

        val nav = row()
        val wrongish = result.verdict != Verdict.CORRECT && result.verdict != Verdict.CLOSE
        if (wrongish && heard.isNotBlank()) {
            nav.addView(button("I was right", surface, ink, line, 14f) { overrideCorrect(card) }, lp(0, wrap, weight = 1f))
            nav.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        }
        nav.addView(button("Siguiente  →", ink, bg) { next() }, lp(0, wrap, weight = 1.4f))
        box.addView(nav, lp(top = 14))

        if (settings.getBoolean("auto_play", true)) speak(card.primary, slow = false)
    }

    private fun overrideCorrect(card: Card) {
        snapshot?.let { deck.restore(card, it) }
        deck.recordCorrect(card)
        if (requeuedAt in queue.indices && queue[requeuedAt].id == card.id) {
            queue.removeAt(requeuedAt)
            requeues[card.id] = ((requeues[card.id] ?: 1) - 1).coerceAtLeast(0)
        }
        if (!wasMissedBefore) missed.remove(card.id)
        next()
    }

    private fun next() {
        tts?.stop()
        pos++
        showCard()
    }

    // =====================================================================
    // Summary
    // =====================================================================

    private fun showSummary() {
        screen = Screen.SUMMARY
        val root = page()
        val total = sessionSeen.size
        val firstTry = sessionSeen.count { it !in missed }
        root.addView(label("¡Hecho!", 40f, red, true))
        root.addView(flagStripe(), lp(dp(72), wrap, top = 4))
        root.addView(label("$firstTry of $total right first time.", 18f, ink), lp(top = 6))
        if (extraPractice) root.addView(label("Extra practice: everything due was already done.", 14f, muted), lp(top = 4))

        val missedCards = deck.cards.filter { it.id in missed }
        if (missedCards.isNotEmpty()) {
            root.addView(sectionTitle("Worth another look"), lp(top = 26))
            for (card in missedCards) root.addView(wordRow(card), lp(top = 8))
        }

        root.addView(button("Another round", red, Color.WHITE, size = 17f) { startSession() }, lp(top = 26))
        root.addView(button("Home", surface, ink, line) { showHome() }, lp(top = 10))
    }

    // =====================================================================
    // Word list
    // =====================================================================

    private fun showWords() {
        stopListening()
        screen = Screen.WORDS
        val root = page()
        val top = row()
        top.addView(button("←  Back", Color.TRANSPARENT, muted, size = 14f) { showHome() }.apply {
            setPadding(dp(4), dp(8), dp(12), dp(8))
        })
        root.addView(top)
        root.addView(label("Word list", 30f, ink, true), lp(top = 4))
        root.addView(label("Tap 🔊 to hear any word.", 14f, muted), lp(top = 2))
        val includeVulgar = settings.getBoolean("include_vulgar", true)
        for (level in Levels.all) {
            val locked = !deck.isUnlocked(level)
            root.addView(sectionTitle(Levels.name(level) + if (locked) "  🔒" else ""), lp(top = 24))
            for (card in deck.cards.filter { it.level == level && (includeVulgar || !it.isVulgar) }) {
                root.addView(wordRow(card), lp(top = 8))
            }
        }
    }

    private fun wordRow(card: Card): LinearLayout {
        val r = row().apply {
            background = rounded(surface, 14, line)
            setPadding(dp(14), dp(10), dp(8), dp(10))
        }
        val text = column()
        val head = row()
        head.addView(label(card.primary, 17f, ink, true))
        if (card.isVulgar) head.addView(chip("CRUDE", redSoft, red), lp(wrap, wrap).apply { leftMargin = dp(6) })
        else if (card.isSlang) head.addView(chip("SLANG", amberSoft, amber), lp(wrap, wrap).apply { leftMargin = dp(6) })
        text.addView(head)
        text.addView(label(card.english, 14f, muted), lp(top = 2))
        val box = deck.state(card).box
        if (deck.state(card).seen) text.addView(label("●".repeat(box) + "○".repeat(6 - box), 10f, red), lp(top = 2))
        r.addView(text, lp(0, wrap, weight = 1f))
        r.addView(button("🔊", Color.TRANSPARENT, ink, size = 20f) { speak(card.primary, slow = false) }.apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
        })
        return r
    }

    // =====================================================================
    // Text to speech (Spain Spanish)
    // =====================================================================

    private fun onTtsInit(status: Int) {
        val t = tts
        if (status != TextToSpeech.SUCCESS || t == null) {
            ttsFailed = true
            if (screen == Screen.HOME) refreshVoiceRow()
            return
        }
        val voices: List<Voice> = try {
            t.voices?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        spanishVoices = voices
            .filter { v ->
                v.locale.language == "es" && v.locale.country == "ES" &&
                    !v.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }
            .sortedWith(compareBy<Voice>({ it.isNetworkConnectionRequired }, { -it.quality }, { it.name }))

        if (spanishVoices.isNotEmpty()) {
            t.setVoice(spanishVoices[currentVoiceIndex()])
            ttsReady = true
        } else {
            val r = t.setLanguage(Locale("es", "ES"))
            ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            ttsFailed = !ttsReady
        }
        if (screen == Screen.HOME) refreshVoiceRow()
    }

    private fun currentVoiceIndex(): Int {
        val saved = settings.getString("voice", null)
        val idx = spanishVoices.indexOfFirst { it.name == saved }
        return if (idx >= 0) idx else 0
    }

    private fun speak(text: String, slow: Boolean) {
        val t = tts
        if (t == null || !ttsReady) {
            toast(if (ttsFailed) "No Spain-Spanish voice installed. See Ajustes on the home screen." else "Voice still loading…")
            return
        }
        val rate = if (slow) 0.6f else settings.getInt("speech_rate", 90) / 100f
        t.setSpeechRate(rate)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "venga")
    }

    // =====================================================================
    // Speech recognition (es-ES)
    // =====================================================================

    private fun toggleListening() {
        if (answered) return
        if (listening) recognizer?.stopListening() else startListening()
    }

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusText?.text = "Speech recognition isn't available on this phone. Type your answer instead."
            showTyping()
            return
        }
        tts?.stop()
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { recognizer = it }
        r.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        listening = true
        setMicState(true)
        partialText?.text = ""
        statusText?.text = "Getting ready…"
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            listening = false
            setMicState(false)
            statusText?.text = "Couldn't start the microphone. Try again or type it."
        }
    }

    private fun stopListening() {
        if (listening) recognizer?.cancel()
        listening = false
        setMicState(false)
    }

    private fun setMicState(on: Boolean) {
        val mic = micButton ?: return
        (mic.background as? GradientDrawable)?.setColor(if (on) redDark else red)
        if (!on) {
            mic.scaleX = 1f
            mic.scaleY = 1f
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            statusText?.text = "Listening… speak now"
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {
            val mic = micButton ?: return
            if (!listening) return
            val s = 1f + (rmsdB.coerceIn(0f, 10f) / 40f)
            mic.scaleX = s
            mic.scaleY = s
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            statusText?.text = "Checking…"
        }

        override fun onError(error: Int) {
            val wasListening = listening
            listening = false
            setMicState(false)
            recognizer?.destroy()
            recognizer = null
            if (answered || !wasListening) return
            statusText?.text = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    "Didn't catch that. Tap the mic and try again."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    "Microphone permission is needed. Tap the mic to allow it."
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                    "The speech service needs an internet connection (or Spanish offline speech installed)."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    "Speech service was busy. Tap the mic again."
                else -> "Speech error ($error). Tap the mic to try again, or type it."
            }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            setMicState(false)
            val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
            if (heard.isEmpty()) {
                statusText?.text = "Didn't catch that. Tap the mic and try again."
                return
            }
            onAnswer(heard)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!p.isNullOrBlank()) partialText?.text = p
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (screen == Screen.PRACTICE && !answered) startListening()
        } else {
            statusText?.text = "Without the microphone you can still type your answers."
            showTyping()
        }
    }

    companion object {
        private const val REQ_MIC = 42
    }
}
