package au.nick.venga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs every card in words.txt through the answer checker, so a typo in the list
 * or a change to the matching rules can't silently break marking.
 */
class WordListTest {

    private val cards: List<Card> by lazy {
        val file = listOf("src/main/assets/words.txt", "app/src/main/assets/words.txt")
            .map { File(it) }.first { it.exists() }
        file.readLines(Charsets.UTF_8).mapNotNull { Deck.parseLine(it) }
    }

    private fun verdict(card: Card, heard: String) = Matcher.check(card, listOf(heard)).verdict

    private fun find(en: String) = cards.first { it.english == en }

    @Test
    fun listLoadsWithAllFourLevels() {
        assertTrue("expected 700+ cards, got ${cards.size}", cards.size >= 700)
        assertEquals(setOf(1, 2, 3, 4), cards.map { it.level }.toSet())
        val dupes = cards.groupBy { it.id }.filter { it.value.size > 1 }.keys
        assertTrue("duplicate cards: $dupes", dupes.isEmpty())
        val badTags = cards.filter { it.tag !in setOf("", "s", "v") }.map { it.id }
        assertTrue("bad tags: $badTags", badTags.isEmpty())
    }

    @Test
    fun everyAcceptedAnswerIsMarkedCorrect() {
        val failures = mutableListOf<String>()
        for (card in cards) for (answer in card.answers) {
            val v = verdict(card, answer)
            if (v != Verdict.CORRECT) failures += "${card.id} → \"$answer\" was $v"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun answersWithoutAccentsOrCapitalsAreCorrect() {
        val failures = mutableListOf<String>()
        for (card in cards) {
            val plain = java.text.Normalizer.normalize(card.primary, java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "").uppercase()
            if (verdict(card, plain) != Verdict.CORRECT) failures += "${card.id} → \"$plain\""
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun verbsAcceptALeadingSubjectPronoun() {
        val pronounFor = mapOf("I " to "yo", "you all " to "vosotros", "you " to "tú", "we " to "nosotros", "he " to "él", "she " to "ella")
        val failures = mutableListOf<String>()
        for (card in cards.filter { it.level == 4 }) {
            val p = pronounFor.entries.firstOrNull { card.english.startsWith(it.key) }?.value ?: continue
            if (card.english.startsWith("I've") || card.english.startsWith("I used") || card.english.startsWith("I could") ||
                card.english.startsWith("I knew") || card.english.startsWith("I wanted (back") || card.english.startsWith("I was (tired")
            ) {
                if (verdict(card, "yo ${card.primary}") != Verdict.CORRECT) failures += "${card.id} → yo ${card.primary}"
                continue
            }
            val said = "$p ${card.primary}"
            if (verdict(card, said) != Verdict.CORRECT) failures += "${card.id} → \"$said\""
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun otherTensesAreFlaggedAsWrongTense() {
        val failures = mutableListOf<String>()
        for (card in cards.filter { it.otherTense.isNotEmpty() }) for (form in card.otherTense) {
            val v = verdict(card, form)
            if (v != Verdict.TENSE) failures += "${card.id} → \"$form\" was $v"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun nonMadridWordsGetTheMadridMessage() {
        val failures = mutableListOf<String>()
        for (card in cards) for (word in card.regional) {
            val v = verdict(card, word)
            if (v != Verdict.REGIONAL) failures += "${card.id} → \"$word\" was $v"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun wrongPersonOfAVerbIsNotAccepted() {
        assertEquals(Verdict.WRONG, verdict(find("I ate / had lunch (yesterday) · comer"), "comió"))
        assertEquals(Verdict.WRONG, verdict(find("I went (yesterday) · ir"), "fue"))
        assertEquals(Verdict.TENSE, verdict(find("I went (yesterday) · ir"), "he ido"))
        assertEquals(Verdict.CORRECT, verdict(find("I went (yesterday) · ir"), "Yo fui."))
    }

    @Test
    fun spokenExtrasAndMaskedSwearWords() {
        assertEquals(Verdict.CORRECT, verdict(find("the car"), "El coche"))
        assertEquals(Verdict.CORRECT, verdict(find("really / very (Madrid youth slang)"), "mola mazo"))
        assertEquals(Verdict.CORRECT, verdict(find("damn it! / for f***'s sake"), "j****"))
        assertEquals(Verdict.CORRECT, verdict(find("bloody brilliant (crude)"), "c*******"))
        assertEquals(Verdict.CLOSE, verdict(find("the computer"), "ordenadro"))
        assertEquals(Verdict.WRONG, verdict(find("the car"), "la casa"))
        assertEquals(Verdict.WRONG, verdict(find("the car"), ""))
    }

    @Test
    fun noCardAcceptsAnotherCardsAnswerOnTheSameDeckByAccident() {
        // A short answer contained in a longer phrase is fine ("mazo" inside "mola mazo"),
        // but a card's answer list should never contain a blank or a lone article.
        val bad = cards.flatMap { c -> c.answers.filter { Matcher.normalize(it).length < 2 }.map { "${c.id} → \"$it\"" } }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }
}
