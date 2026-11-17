package com.aikeyboard.keyboard

import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ln

/**
 * Small offline spell engine: an 80k-word frequency list + edit-distance candidates that know
 * QWERTY geometry (neighbouring keys and swapped letters are far likelier typos than random ones).
 * No ML, ~1.3 MB. Pure Kotlin, so it can be unit-tested off-device.
 */
class SpellEngine {

    @Volatile var ready = false
        private set

    private val freq = HashMap<String, Long>(131072)
    private var sortedWords: Array<String> = emptyArray()
    private var sortedFreq: LongArray = LongArray(0)
    private val userWords: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun load(input: InputStream) {
        val words = ArrayList<String>(81000)
        val counts = ArrayList<Long>(81000)
        input.bufferedReader().useLines { lines ->
            for (line in lines) {
                val sp = line.indexOf(' ')
                if (sp <= 0) continue
                val w = line.substring(0, sp)
                val c = line.substring(sp + 1).trim().toLongOrNull() ?: continue
                if (!w.all { it in 'a'..'z' }) continue
                freq[w] = c
                words.add(w); counts.add(c)
            }
        }
        val order = words.indices.sortedBy { words[it] }
        sortedWords = Array(order.size) { words[order[it]] }
        sortedFreq = LongArray(order.size) { counts[order[it]] }
        ready = true
    }

    fun addUserWords(ws: Collection<String>) { userWords.addAll(ws) }
    fun learn(word: String) { userWords.add(word.lowercase(Locale.ROOT)) }
    fun userWordSnapshot(): Set<String> = HashSet(userWords)

    fun isKnown(lw: String): Boolean =
        freq.containsKey(lw) || lw in EXTRA_OK || lw in userWords || lw in CONTRACTION_TARGETS

    // ───────────────────────── autocorrect ─────────────────────────

    /** Contractions + well-known typos. Applied even before the dictionary has finished loading. */
    fun fixFor(word: String): String? {
        if (word == "i") return "I"
        val f = FIXES[word.lowercase(Locale.ROOT)] ?: return null
        return if (f[0] == 'I') f else matchCase(word, f)
    }

    /**
     * The correction to apply automatically when the user finishes a word, or null to leave it alone.
     * Deliberately conservative: a wrong autocorrect is worse than a missed one.
     */
    fun correction(word: String): String? {
        if (word.lowercase(Locale.ROOT) in userWords) return null          // the user undid this before: respect it
        fixFor(word)?.let { return if (it == word) null else it }
        if (!ready) return null
        if (word.length < 3 || word.length > 20) return null
        if (!word.all { it in 'a'..'z' }) return null          // names ("Ravi"), acronyms, iPhone, digits: never touch
        if (isKnown(word)) return null
        val ranked = rankEdits1(word)
        if (ranked.isEmpty()) return null
        val best = ranked[0]
        if ((freq[best.first] ?: 0L) < MIN_FREQ) return null
        if (ranked.size > 1 && best.second - ranked[1].second < MARGIN) return null   // ambiguous: don't guess
        return best.first
    }

    // ───────────────────────── suggestions strip ─────────────────────────

    /** Up to [max] alternatives for the word being typed: fixes for a misspelling, else completions. */
    fun alternatives(word: String, max: Int = 2): List<String> {
        if (!ready || word.isEmpty()) return emptyList()
        val lw = word.lowercase(Locale.ROOT)
        if (!lw.all { it in 'a'..'z' || it == '\'' }) return emptyList()
        val out = LinkedHashSet<String>()
        fixFor(word)?.let { if (it != word) out.add(it) }
        if (lw in EXTRA_OK || lw in userWords) return out.toList()      // slang / Hinglish / learned: nothing to suggest
        if (!isKnown(lw)) {
            for ((c, _) in rankEdits1(lw)) {
                if (out.size >= max) break
                if ((freq[c] ?: 0L) >= SUGGEST_MIN_FREQ) out.add(matchCase(word, c))
            }
            if (out.size < max && lw.length in 4..10) {
                for ((c, _) in rankEdits2(lw)) { if (out.size >= max) break; out.add(matchCase(word, c)) }
            }
        }
        if (out.size < max) {
            for (c in completions(lw, max + 1)) {
                val m = matchCase(word, c)
                if (!m.equals(word, ignoreCase = true)) out.add(m)
                if (out.size >= max) break
            }
        }
        return out.toList().take(max)
    }

    private fun matchCase(orig: String, cand: String): String = when {
        orig.length > 1 && orig.all { it.isUpperCase() } -> cand.uppercase(Locale.ROOT)
        orig.isNotEmpty() && orig[0].isUpperCase() -> cand.replaceFirstChar { it.uppercaseChar() }
        else -> cand
    }

    private fun completions(prefix: String, k: Int): List<String> {
        if (sortedWords.isEmpty()) return emptyList()
        var lo = 0
        var hi = sortedWords.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sortedWords[mid] < prefix) lo = mid + 1 else hi = mid
        }
        val idx = IntArray(k) { -1 }
        var i = lo
        while (i < sortedWords.size && sortedWords[i].startsWith(prefix)) {
            if (sortedWords[i].length > prefix.length) {
                var p = k - 1
                if (idx[p] == -1 || sortedFreq[i] > sortedFreq[idx[p]]) {
                    while (p > 0 && (idx[p - 1] == -1 || sortedFreq[i] > sortedFreq[idx[p - 1]])) { idx[p] = idx[p - 1]; p-- }
                    idx[p] = i
                }
            }
            i++
        }
        return idx.filter { it >= 0 }.map { sortedWords[it] }
    }

    // ───────────────────────── candidates ─────────────────────────

    private fun rankEdits1(w: String): List<Pair<String, Double>> {
        val best = HashMap<String, Double>()
        edits1(w) { cand, penalty ->
            val f = freq[cand]
            if (f != null) {
                val s = ln(f.toDouble()) - penalty
                val old = best[cand]
                if (old == null || s > old) best[cand] = s
            }
        }
        return best.entries.map { it.key to it.value }.sortedByDescending { it.second }
    }

    /** Two edits away: slower (≈10^5 lookups), so only ever call it off the UI thread. */
    private fun rankEdits2(w: String): List<Pair<String, Double>> {
        val best = HashMap<String, Double>()
        edits1(w) { c1, p1 ->
            edits1(c1) { c2, p2 ->
                val f = freq[c2]
                if (f != null && c2 != w) {
                    val s = ln(f.toDouble()) - p1 - p2 - 1.5
                    val old = best[c2]
                    if (old == null || s > old) best[c2] = s
                }
            }
        }
        return best.entries.map { it.key to it.value }.sortedByDescending { it.second }
    }

    /** Every string one edit away from [w], with a "how unlikely is this typo" penalty (lower = likelier). */
    private fun edits1(w: String, fn: (String, Double) -> Unit) {
        val n = w.length
        for (i in 0 until n) {                                          // extra letter typed
            val dup = (i > 0 && w[i] == w[i - 1]) || (i < n - 1 && w[i] == w[i + 1])
            fn(w.removeRange(i, i + 1), if (dup) 0.3 else 1.4)
        }
        for (i in 0 until n - 1) {                                      // two letters swapped
            if (w[i] == w[i + 1]) continue
            val c = w.toCharArray()
            val t = c[i]; c[i] = c[i + 1]; c[i + 1] = t
            fn(String(c), -1.0)
        }
        for (i in 0 until n) {                                          // wrong letter (neighbour key = likely)
            val o = w[i]
            for (ch in 'a'..'z') {
                if (ch == o) continue
                val c = w.toCharArray()
                c[i] = ch
                fn(String(c), if (isAdjacent(o, ch)) 0.4 else 1.6)
            }
        }
        for (i in 0..n) {                                               // missing letter
            for (ch in 'a'..'z') {
                val dup = (i > 0 && w[i - 1] == ch) || (i < n && w[i] == ch)
                fn(w.substring(0, i) + ch + w.substring(i), if (dup) 0.5 else 1.1)
            }
        }
    }

    private fun isAdjacent(a: Char, b: Char): Boolean {
        if (a !in 'a'..'z' || b !in 'a'..'z') return false
        return ADJ[a - 'a'][b - 'a']
    }

    companion object {
        private const val MIN_FREQ = 500_000L
        private const val SUGGEST_MIN_FREQ = 50_000L
        private const val MARGIN = 1.6

        private val ADJ: Array<BooleanArray> = run {
            val rows = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
            val shift = doubleArrayOf(0.0, 0.25, 0.75)
            val xs = DoubleArray(26); val ys = IntArray(26)
            rows.forEachIndexed { r, row -> row.forEachIndexed { c, ch -> xs[ch - 'a'] = c + shift[r]; ys[ch - 'a'] = r } }
            Array(26) { a -> BooleanArray(26) { b ->
                a != b && abs(ys[a] - ys[b]) <= 1 && abs(xs[a] - xs[b]) <= 1.0 + (if (ys[a] == ys[b]) 0.0 else 0.0)
            } }
        }

        /** Contractions (typed without the apostrophe) and classic typos. Values starting with 'I' keep their case. */
        val FIXES: Map<String, String> = mapOf(
            "i'm" to "I'm", "i'll" to "I'll", "i've" to "I've", "i'd" to "I'd",
            "im" to "I'm", "ive" to "I've",
            "dont" to "don't", "cant" to "can't", "wont" to "won't", "youre" to "you're", "theyre" to "they're",
            "youve" to "you've", "weve" to "we've", "theyve" to "they've", "youll" to "you'll", "theyll" to "they'll",
            "isnt" to "isn't", "arent" to "aren't", "wasnt" to "wasn't", "werent" to "weren't", "hasnt" to "hasn't",
            "havent" to "haven't", "hadnt" to "hadn't", "doesnt" to "doesn't", "didnt" to "didn't",
            "couldnt" to "couldn't", "shouldnt" to "shouldn't", "wouldnt" to "wouldn't", "mustnt" to "mustn't",
            "thats" to "that's", "whats" to "what's", "theres" to "there's", "heres" to "here's",
            "wheres" to "where's", "whos" to "who's", "couldve" to "could've", "shouldve" to "should've",
            "wouldve" to "would've",
            "teh" to "the", "taht" to "that", "adn" to "and", "waht" to "what", "hvae" to "have", "wich" to "which",
            "recieve" to "receive", "seperate" to "separate", "definately" to "definitely", "occured" to "occurred",
            "truely" to "truly", "tomorow" to "tomorrow", "theif" to "thief", "wierd" to "weird"
        )

        private val CONTRACTION_TARGETS: Set<String> = FIXES.values.map { it.lowercase(Locale.ROOT) }.toSet()

        /** Words the dictionary lacks. Includes common romanised Hindi so Hinglish is never "corrected". */
        private val EXTRA_OK: Set<String> = (
            // chat slang & tech
            "lol lmao omg brb btw idk imo tbh fyi asap thx pls plz ty np gm gn hbd dm irl wtf smh yolo nvm bro bruh " +
            "sis fam dude wassup sup yup yep nope hmm hmmm haha hahaha ugh yay woah whatsapp instagram youtube " +
            "facebook google gmail iphone android wifi bluetooth online offline email emoji selfie vlog podcast " +
            "tiktok reels insta fb ig yt okk kk tho thru lemme gimme dunno kinda sorta ya yo heya hii hiii " +
            "gonna wanna gotta ok okay " +
            // romanised Hindi / Hinglish
            "hai hain hu hun hoon tha thi hoga hogi honge kya kyu kyun kyunki kaise kaisa kaisi kaun kab kahan " +
            "kidhar kitna kitni kitne nahi nahin nhi haan han ji accha acha achha theek thik bilkul bhai bhaiya " +
            "behen didi yaar yar dost dosto sab sabhi kuch kuchh koi kisi mera meri mere tera teri tere tumhara " +
            "tumhari tumhare uska uski uske iska iski iske apna apni apne aap aapka aapki tum tu main mai mujhe " +
            "mujhko tumhe tujhe hum humko humne maine tumne usne isne unhe unko hume wo woh vo ye yeh yahan yaha " +
            "wahan waha abhi phir fir aur lekin magar toh bhi mein mei pe per tak wala wali wale karna karo karke " +
            "kar kr krna kiya kiye kare karunga karungi karenge kal aaj parso kabhi hamesha bahut bohot bhot zyada " +
            "jyada thoda thodi bas sirf chalo chal chalte chalna aao aana aaya aayi jao jana gaya gayi gaye raha " +
            "rahi rahe rha rhi rhe bol bolo bola bolna batao bata batana dekho dekh dekhna suno sun sunna samajh " +
            "samjha samjhi samjho pata pta malum lagta lagti lagega mil milo milna mile milega mat dena diya liya " +
            "lena khana khao khaya pina piya paani pani chai roti sabzi dal ghar kaam paisa paise rupaye padhai " +
            "padhna raat subah shaam din dopahar pyaar pyar ishq dil jaan jaanu babu shona sundar mast badhiya " +
            "zabardast bakwas pagal bewakoof arre arey oye abe wah waah shukriya dhanyavaad dhanyavad namaste " +
            "namaskar salaam alvida mubarak badhai shubh papa mummy maa bhabhi chacha mama nana nani dada dadi " +
            "beta beti sir madam yaa haa hmm acha"
        ).split(' ').filter { it.isNotEmpty() }.toSet()
    }
}
