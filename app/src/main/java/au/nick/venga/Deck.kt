package au.nick.venga

import android.content.Context
import android.content.SharedPreferences

data class Card(
    val id: String,
    val level: Int,
    val english: String,
    val answers: List<String>,
    val tag: String,
    val note: String,
    val regional: List<String>
) {
    val primary: String get() = answers.first()
    val isSlang: Boolean get() = tag == "s"
    val isVulgar: Boolean get() = tag == "v"
}

/** Spaced-repetition state for one card (Leitner boxes 0–6). */
data class CardState(
    var box: Int = 0,
    var due: Long = 0L,
    var seen: Boolean = false,
    var reps: Int = 0,
    var lapses: Int = 0
)

object Levels {
    val all = listOf(1, 2, 3)
    fun name(level: Int) = when (level) {
        1 -> "Intermedio"
        2 -> "Avanzado"
        else -> "Callejero"
    }
    fun blurb(level: Int) = when (level) {
        1 -> "Everyday Madrid: bars, flats, the metro and the slang you'll hear daily."
        2 -> "Idioms, nights out, Madrid food and the everyday swear words."
        else -> "Street talk and old-school cheli. Strong language included."
    }
    const val UNLOCK_SHARE = 0.6
}

class Deck(context: Context, private val settings: SharedPreferences) {

    val cards: List<Card>
    private val progress: SharedPreferences =
        context.getSharedPreferences("progress", Context.MODE_PRIVATE)
    private val states = HashMap<String, CardState>()

    init {
        cards = context.assets.open("words.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.mapNotNull { parse(it) }.distinctBy { it.id }.toList()
        }
        for (c in cards) states[c.id] = load(c.id)
    }

    private fun parse(line: String): Card? {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#")) return null
        val p = t.split("|")
        if (p.size < 3) return null
        val level = p[0].trim().toIntOrNull() ?: return null
        val en = p[1].trim()
        val answers = p[2].split(";").map { it.trim() }.filter { it.isNotEmpty() }
        if (en.isEmpty() || answers.isEmpty()) return null
        val tag = p.getOrNull(3)?.trim() ?: ""
        val note = p.getOrNull(4)?.trim() ?: ""
        val regional = p.getOrNull(5)?.split(";")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        return Card("$level|$en", level, en, answers, tag, note, regional)
    }

    private fun load(id: String): CardState {
        val raw = progress.getString("c:$id", null) ?: return CardState()
        val p = raw.split(",")
        return try {
            CardState(p[0].toInt(), p[1].toLong(), p[2] == "1", p[3].toInt(), p[4].toInt())
        } catch (e: Exception) {
            CardState()
        }
    }

    private fun save(card: Card) {
        val s = state(card)
        progress.edit()
            .putString("c:${card.id}", "${s.box},${s.due},${if (s.seen) 1 else 0},${s.reps},${s.lapses}")
            .apply()
    }

    fun state(card: Card): CardState = states.getOrPut(card.id) { CardState() }

    // ---------- settings-driven filters ----------

    private val includeVulgar: Boolean get() = settings.getBoolean("include_vulgar", true)

    fun isUnlocked(level: Int): Boolean {
        if (level <= 1) return true
        if (settings.getInt("manual_unlock", 1) >= level) return true
        return isUnlocked(level - 1) && learnedShare(level - 1) >= Levels.UNLOCK_SHARE
    }

    fun unlock(level: Int) {
        val current = settings.getInt("manual_unlock", 1)
        if (level > current) settings.edit().putInt("manual_unlock", level).apply()
    }

    private fun pool(): List<Card> =
        cards.filter { isUnlocked(it.level) && (includeVulgar || !it.isVulgar) }

    // ---------- stats ----------

    fun levelCards(level: Int) = cards.filter { it.level == level && (includeVulgar || !it.isVulgar) }
    fun learnedCount(level: Int) = levelCards(level).count { state(it).box >= 2 }
    fun learnedShare(level: Int): Double {
        val all = levelCards(level)
        return if (all.isEmpty()) 1.0 else learnedCount(level).toDouble() / all.size
    }
    fun learnedTotal() = cards.count { state(it).box >= 2 && (includeVulgar || !it.isVulgar) }
    fun isDue(card: Card, now: Long): Boolean = state(card).let { it.seen && it.due <= now }
    fun dueCount(now: Long) = pool().count { isDue(it, now) }
    fun newAvailable() = pool().count { !state(it).seen }

    // ---------- sessions ----------

    /** Returns the cards for a session, and whether it is "extra practice" (nothing due or new). */
    fun buildSession(size: Int, newLimit: Int, now: Long): Pair<List<Card>, Boolean> {
        val pool = pool()
        val due = pool.filter { isDue(it, now) }.sortedBy { state(it).due }
        val fresh = pool.filter { !state(it).seen }
        val out = ArrayList<Card>()
        out.addAll(due.take(size))
        val room = size - out.size
        if (room > 0) out.addAll(fresh.take(minOf(room, newLimit)))
        if (out.isNotEmpty()) return Pair(out.shuffled(), false)
        // Nothing due and no new words: practise the cards coming up soonest.
        val ahead = pool.filter { state(it).seen }.sortedBy { state(it).due }.take(size)
        return Pair(ahead.shuffled(), true)
    }

    fun recordCorrect(card: Card, now: Long = System.currentTimeMillis()) {
        val s = state(card)
        s.seen = true
        s.reps++
        s.box = (s.box + 1).coerceAtMost(INTERVAL_DAYS.size - 1)
        s.due = now + INTERVAL_DAYS[s.box] * DAY
        save(card)
    }

    fun recordWrong(card: Card, now: Long = System.currentTimeMillis()) {
        val s = state(card)
        s.seen = true
        s.reps++
        s.lapses++
        s.box = 0
        s.due = now
        save(card)
    }

    /** Answered with a real Spanish word that isn't what Madrid uses: a gentler step back. */
    fun recordRegional(card: Card, now: Long = System.currentTimeMillis()) {
        val s = state(card)
        s.seen = true
        s.reps++
        s.box = (s.box - 1).coerceAtLeast(0)
        s.due = now
        save(card)
    }

    fun restore(card: Card, snapshot: CardState) {
        states[card.id] = snapshot.copy()
        save(card)
    }

    fun resetAll() {
        progress.edit().clear().apply()
        states.clear()
        settings.edit().remove("manual_unlock").apply()
    }

    companion object {
        /** Days until the next review for each box. */
        val INTERVAL_DAYS = longArrayOf(0, 1, 3, 7, 14, 30, 60)
        const val DAY = 86_400_000L
    }
}
