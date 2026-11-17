package com.aikeyboard.keyboard

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aikeyboard.R
import com.aikeyboard.clipboard.ClipboardAdapter
import com.aikeyboard.data.ApiClient
import com.aikeyboard.data.ApiException
import com.aikeyboard.data.ChatMessage
import com.aikeyboard.data.Note
import com.aikeyboard.data.PrefsManager
import com.aikeyboard.notes.NotesAdapter
import com.aikeyboard.ui.SetupActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class AiKeyboardService : InputMethodService() {

    private enum class Tab { KEYBOARD, AI, NOTES, CLIPBOARD }

    // ── state ──
    private var currentTab = Tab.KEYBOARD

    /** false = typing goes to the app (↑).  true = typing goes to the AI prompt (↓). */
    private var aiMode = false

    /** When set, our keys type into this in-keyboard field instead of the host app. */
    private var localTarget: EditText? = null
    private var localIc: InputConnection? = null

    private val chatHistory = mutableListOf<ChatMessage>()
    private var lastAiResponse = ""
    private var chatJob: Job? = null
    private var editingNoteId: String? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val api by lazy { ApiClient(this) }
    private lateinit var notesAdapter: NotesAdapter
    private lateinit var clipboardAdapter: ClipboardAdapter

    private val clipboardManager by lazy { getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        val clip = clipboardManager.primaryClip
        if (clip != null && clip.itemCount > 0) {
            // Skip anything the source app marks sensitive (password managers, Android 13+).
            val sensitive = try {
                clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true
            } catch (e: Exception) { false }
            val text = clip.getItemAt(0).text?.toString()
            if (!sensitive && !text.isNullOrBlank()) {
                PrefsManager.addClipboardClip(this, text)
                if (currentTab == Tab.CLIPBOARD) loadClipboard()
            }
        }
    }

    // ── spelling / autocorrect state ──
    private val spell = SpellEngine()
    private val spellExec = Executors.newSingleThreadExecutor()
    private val typingPrefs by lazy { getSharedPreferences("typing_prefs", Context.MODE_PRIVATE) }
    private var spellOn = true               // off in password / URL / e-mail / number fields
    private var autoCorrectOn = true
    private val word = StringBuilder()       // letters of the word being typed (local mirror: no IPC per key)
    private var wordClean = true             // false once a digit/symbol is part of this word
    @Volatile private var suggestionVersion = 0
    private var sugTexts = arrayOf("", "", "")
    private var suggestionsShowing = false
    private var lastAutoCorrectOriginal: String? = null
    private var lastAutoCorrectReplacement: String? = null
    private var lastBoundary = " "
    private var canUndoAutoCorrect = false

    // ── views ──
    private lateinit var rootView: View
    private lateinit var tabGroup: View
    private lateinit var suggestionBar: View
    private lateinit var sug1: TextView
    private lateinit var sug2: TextView
    private lateinit var sug3: TextView
    private lateinit var tabKeyboard: TextView
    private lateinit var tabAi: TextView
    private lateinit var tabNotes: TextView
    private lateinit var tabClipboard: TextView
    private lateinit var promptInput: EditText
    private lateinit var btnMode: TextView
    private lateinit var keysView: KeyboardCanvasView
    private lateinit var panelAi: View
    private lateinit var panelNotes: View
    private lateinit var panelClipboard: View
    private lateinit var aiScroll: ScrollView
    private lateinit var aiMessages: LinearLayout
    private lateinit var aiInput: EditText
    private lateinit var btnSendAi: TextView
    private lateinit var btnUseResponse: TextView
    private lateinit var aiLoading: View
    private lateinit var aiLimitBadge: TextView
    private lateinit var aiInfoCard: TextView
    private var requestLimits: Int = 10
    private var isPremium: Boolean = false
    private lateinit var notesRecycler: RecyclerView
    private lateinit var notesListView: View
    private lateinit var notesEditView: View
    private lateinit var notesStatus: TextView
    private lateinit var noteTitleInput: EditText
    private lateinit var noteContentInput: EditText
    private lateinit var btnNewNote: TextView
    private lateinit var btnSaveNote: TextView
    private lateinit var btnCancelNote: TextView
    private lateinit var clipRecycler: RecyclerView
    private lateinit var clipStatus: TextView
    private lateinit var btnClearClip: TextView

    // ── lifecycle ──

    override fun onEvaluateFullscreenMode() = false   // never take over the screen in landscape

    override fun onCreateInputView(): View {
        rootView = LayoutInflater.from(this).inflate(R.layout.keyboard_view, null)
        bindViews()
        setupKeyboard()
        setupToolbar()
        setupAiPanel()
        setupNotesPanel()
        setupClipboardPanel()
        initSpell()
        try {
            clipboardManager.addPrimaryClipChangedListener(clipListener)
        } catch (e: Exception) {}
        return rootView
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!::rootView.isInitialized) return
        keysView.haptics = PrefsManager.isVibrationEnabled(this)
        autoCorrectOn = typingPrefs.getBoolean("auto_correct", true)
        spellOn = isSpellField(info)
        canUndoAutoCorrect = false
        resetWord()
        if (!restarting) { keysView.reset(); showTab(Tab.KEYBOARD) }
        fetchUserLimits()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // User selected a range (long-press / drag): whatever word we were tracking is no longer under the caret.
        if (newSelStart != newSelEnd && ::rootView.isInitialized) resetWord()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            clipboardManager.removePrimaryClipChangedListener(clipListener)
        } catch (e: Exception) {}
        spellExec.shutdownNow()
        scope.cancel()
    }

    private fun bindViews() {
        fun <T : View> id(i: Int): T = rootView.findViewById(i)
        tabGroup = id(R.id.tab_group)
        suggestionBar = id(R.id.suggestion_bar)
        sug1 = id(R.id.sug_1); sug2 = id(R.id.sug_2); sug3 = id(R.id.sug_3)
        tabKeyboard = id(R.id.tab_keyboard); tabAi = id(R.id.tab_ai); tabNotes = id(R.id.tab_notes)
        tabClipboard = id(R.id.tab_clipboard)
        promptInput = id(R.id.prompt_input); btnMode = id(R.id.btn_mode)
        keysView = id(R.id.keys_view)
        panelAi = id(R.id.panel_ai); panelNotes = id(R.id.panel_notes)
        panelClipboard = id(R.id.panel_clipboard)
        aiScroll = id(R.id.ai_scroll); aiMessages = id(R.id.ai_messages_container)
        aiInput = id(R.id.ai_input); btnSendAi = id(R.id.btn_send_ai)
        btnUseResponse = id(R.id.btn_use_response); aiLoading = id(R.id.ai_loading)
        aiLimitBadge = id(R.id.ai_limit_badge); aiInfoCard = id(R.id.ai_info_card)
        notesRecycler = id(R.id.notes_recycler); notesListView = id(R.id.notes_list_view)
        notesEditView = id(R.id.notes_edit_view); notesStatus = id(R.id.notes_status)
        noteTitleInput = id(R.id.note_title_input); noteContentInput = id(R.id.note_content_input)
        btnNewNote = id(R.id.btn_new_note); btnSaveNote = id(R.id.btn_save_note)
        btnCancelNote = id(R.id.btn_cancel_note)
        clipRecycler = id(R.id.clip_recycler)
        clipStatus = id(R.id.clip_status)
        btnClearClip = id(R.id.btn_clear_clip)
    }

    // ── toolbar, tabs & layout ──

    private fun setupToolbar() {
        tabKeyboard.setOnClickListener { showTab(Tab.KEYBOARD) }
        tabAi.setOnClickListener { showTab(Tab.AI) }
        tabNotes.setOnClickListener { showTab(Tab.NOTES); showNotesList(); loadNotes() }
        tabClipboard.setOnClickListener { showTab(Tab.CLIPBOARD); loadClipboard() }
        btnMode.setOnClickListener { setAiMode(!aiMode) }
        makeLocalInput(promptInput)
    }

    /**
     * The one ↑/↓ button. It only decides where typing goes; the app's own Send button still sends
     * app messages. Your text travels with you when you flip it, so nothing gets lost.
     */
    private fun setAiMode(on: Boolean) {
        if (on == aiMode) return
        aiMode = on
        resetWord()
        if (on) {
            val draft = hostText()
            if (draft.isNotBlank()) {                       // move what's typed in the app into the prompt
                clearHostField()
                promptInput.setText(draft)
                promptInput.setSelection(promptInput.text.length)
            }
            setLocal(promptInput)
        } else {
            val prompt = promptInput.text.toString()
            if (prompt.isNotEmpty()) {                      // hand the text back to the app
                currentInputConnection?.commitText(prompt, 1)
                promptInput.setText("")
            }
            setLocal(null)
        }
        refreshLayout()
    }

    private fun showTab(tab: Tab) {
        currentTab = tab
        resetWord()
        setLocal(if (tab == Tab.KEYBOARD && aiMode) promptInput else null)
        val on = ContextCompat.getDrawable(this, R.drawable.bg_tab_selected)
        val off = ContextCompat.getDrawable(this, R.drawable.bg_tab_unselected)
        val onColor = ContextCompat.getColor(this, R.color.white)
        val offColor = ContextCompat.getColor(this, R.color.text_secondary)
        listOf(tabKeyboard to Tab.KEYBOARD, tabAi to Tab.AI, tabNotes to Tab.NOTES, tabClipboard to Tab.CLIPBOARD).forEach { (v, t) ->
            v.background = if (t == tab) on else off
            v.setTextColor(if (t == tab) onColor else offColor)
        }
        refreshLayout()
        if (tab == Tab.AI) fetchUserLimits()
    }

    private fun refreshLayout() {
        val kb = currentTab == Tab.KEYBOARD
        val typingLocally = localTarget != null
        val showKeys = kb || typingLocally

        applyToolbarMode()
        promptInput.visibility = if (kb && aiMode) View.VISIBLE else View.GONE
        btnMode.visibility = if (kb) View.VISIBLE else View.GONE
        btnMode.text = if (aiMode) "↓" else "↑"
        btnMode.background = ContextCompat.getDrawable(
            this, if (aiMode) R.drawable.bg_key_accent else R.drawable.bg_key_special)

        keysView.visibility = if (showKeys) View.VISIBLE else View.GONE
        panelAi.visibility = if (currentTab == Tab.AI) View.VISIBLE else View.GONE
        panelNotes.visibility = if (currentTab == Tab.NOTES) View.VISIBLE else View.GONE
        panelClipboard.visibility = if (currentTab == Tab.CLIPBOARD) View.VISIBLE else View.GONE
        // Panels shrink while the keys sit below them, and grow back to read replies.
        setHeightDp(panelAi, if (typingLocally) 110 else 260)
        setHeightDp(panelNotes, if (typingLocally) 130 else 260)
        setHeightDp(panelClipboard, if (typingLocally) 130 else 260)

        keysView.setReturnGlyph(if (localTarget === promptInput || localTarget === aiInput) "✦" else "↵")
    }

    /** Toolbar left side shows the tabs, or the suggestion strip while a word is being typed. */
    private fun applyToolbarMode() {
        val kb = currentTab == Tab.KEYBOARD
        val strip = suggestionsShowing && kb && !aiMode
        tabGroup.visibility = if ((kb && aiMode) || strip) View.GONE else View.VISIBLE
        suggestionBar.visibility = if (strip) View.VISIBLE else View.GONE
    }

    private fun setHeightDp(v: View, dp: Int) {
        val lp = v.layoutParams
        lp.height = (dp * resources.displayMetrics.density).toInt()
        v.layoutParams = lp
    }

    // ── typing target routing ──

    @SuppressLint("ClickableViewAccessibility")
    private fun makeLocalInput(field: EditText) {
        field.setShowSoftInputOnFocus(false)
        field.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_UP) activateLocal(field)
            false
        }
    }

    private fun setLocal(field: EditText?) {
        localTarget = field
        localIc = field?.onCreateInputConnection(EditorInfo())
        field?.requestFocus()
    }

    private fun activateLocal(field: EditText) { setLocal(field); refreshLayout() }

    private fun deactivateLocal() {
        setLocal(if (currentTab == Tab.KEYBOARD && aiMode) promptInput else null)
        refreshLayout()
    }

    private fun commit(text: String) {
        val ic = localIc
        if (localTarget != null && ic != null) ic.commitText(text, 1)
        else currentInputConnection?.commitText(text, 1)
        canUndoAutoCorrect = false
    }

    // ── spelling, suggestions & autocorrect ──

    /** Load the dictionary off the UI thread (it's ~1.3 MB), plus the words the user taught us. */
    private fun initSpell() {
        spellExec.execute {
            try { assets.open("en_freq.txt").use { spell.load(it) } } catch (e: Exception) { }
            spell.addUserWords(typingPrefs.getStringSet("user_words", emptySet()) ?: emptySet())
        }
    }

    private fun isSpellField(info: EditorInfo?): Boolean {
        val t = info?.inputType ?: return true
        if ((t and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false
        if ((t and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) return false
        return when (t and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> false
            else -> true
        }
    }

    private fun resetWord() {
        word.setLength(0)
        wordClean = true
        suggestionVersion++
        if (suggestionsShowing) { suggestionsShowing = false; applyToolbarMode() }
    }

    /** Every character from the keyboard comes through here. */
    private fun typeText(text: String) {
        if (localTarget != null || !spellOn) { commit(text); return }
        val c = text[0]
        when {
            text.length == 1 && (c.isLetter() || (c == '\'' && word.isNotEmpty())) -> {
                commit(text); word.append(c); updateSuggestions()
            }
            text.length == 1 && c.isDigit() -> {                 // "4u", "b2b": not a word to correct
                commit(text); word.append(c); wordClean = false; hideSuggestions()
            }
            text.length == 1 && c in ".,!?;:)" -> {
                if (!applyAutoCorrect(text)) commit(text)
                resetWord()
            }
            else -> { commit(text); resetWord() }                // @ # / _ - etc.: no correction around them
        }
    }

    private fun handleSpace() {
        if (localTarget != null || !spellOn) { commit(" "); return }
        if (!applyAutoCorrect(" ")) commit(" ")
        resetWord()
    }

    /**
     * Replaces the finished word with its correction (and appends [boundary]). Never deletes text blindly:
     * the characters before the caret must still be exactly the word we tracked.
     */
    private fun applyAutoCorrect(boundary: String): Boolean {
        if (!autoCorrectOn || !wordClean || word.isEmpty()) return false
        val typed = word.toString()
        val fix = spell.correction(typed) ?: return false
        val ic = currentInputConnection ?: return false
        ic.finishComposingText()
        val before = ic.getTextBeforeCursor(typed.length, 0)?.toString()
        if (before != typed) return false
        ic.deleteSurroundingText(typed.length, 0)
        ic.commitText(fix + boundary, 1)
        lastAutoCorrectOriginal = typed
        lastAutoCorrectReplacement = fix
        lastBoundary = boundary
        canUndoAutoCorrect = true
        return true
    }

    private fun updateSuggestions() {
        if (!spellOn || localTarget != null || word.isEmpty() || !wordClean) { hideSuggestions(); return }
        val typed = word.toString()
        val v = ++suggestionVersion
        spellExec.execute {
            if (v != suggestionVersion) return@execute            // a newer keystroke superseded this one
            val alts = spell.alternatives(typed, 2)
            if (v != suggestionVersion) return@execute
            rootView.post { if (v == suggestionVersion) showSuggestions(typed, alts) }
        }
    }

    private fun showSuggestions(typed: String, alts: List<String>) {
        sugTexts = arrayOf(typed, alts.getOrElse(0) { "" }, alts.getOrElse(1) { "" })
        sug1.text = sugTexts[0]
        sug2.text = sugTexts[1]
        sug3.text = sugTexts[2]
        if (!suggestionsShowing) { suggestionsShowing = true; applyToolbarMode() }
    }

    private fun hideSuggestions() {
        suggestionVersion++
        if (suggestionsShowing) { suggestionsShowing = false; applyToolbarMode() }
    }

    /** Tap a suggestion: swap the word you're typing for it (backspace right after undoes this). */
    private fun applySuggestion(s: String) {
        if (s.isEmpty() || word.isEmpty()) return
        val typed = word.toString()
        val ic = currentInputConnection ?: return
        ic.finishComposingText()
        if (ic.getTextBeforeCursor(typed.length, 0)?.toString() == typed) {
            ic.deleteSurroundingText(typed.length, 0)
            ic.commitText("$s ", 1)
            lastAutoCorrectOriginal = typed
            lastAutoCorrectReplacement = s
            lastBoundary = " "
            canUndoAutoCorrect = true
        }
        resetWord()
    }

    /** Tap the typed word (left slot): keep it as written, and learn it so it's never "corrected". */
    private fun keepTypedWord() {
        if (word.isEmpty()) return
        val typed = word.toString()
        if (wordClean && typed.length >= 3 && !spell.isKnown(typed.lowercase())) learnWord(typed)
        commit(" ")
        resetWord()
    }

    private fun learnWord(w: String) {
        spell.learn(w)
        val all = spell.userWordSnapshot()
        if (all.size <= 5000) typingPrefs.edit().putStringSet("user_words", all).apply()
    }

    /** Deletes the selection if there is one, otherwise one character. Right after an autocorrect: undo it. */
    private fun backspace(first: Boolean = true) {
        if (canUndoAutoCorrect) {
            val original = lastAutoCorrectOriginal
            val replacement = lastAutoCorrectReplacement
            val ic = currentInputConnection
            if (original != null && replacement != null && ic != null) {
                val tail = replacement + lastBoundary
                if (ic.getTextBeforeCursor(tail.length, 0)?.toString() == tail) {
                    ic.deleteSurroundingText(tail.length, 0)
                    ic.commitText(original, 1)
                    if (original.length >= 2 && original.all { it.isLetter() }) learnWord(original)   // "I meant that"
                    canUndoAutoCorrect = false
                    lastAutoCorrectOriginal = null
                    lastAutoCorrectReplacement = null
                    word.setLength(0); word.append(original); wordClean = true   // keep editing that word
                    updateSuggestions()
                    return
                }
            }
        }
        canUndoAutoCorrect = false
        val et = localTarget
        if (et != null) {
            val text = et.text
            val s = et.selectionStart
            val e = et.selectionEnd
            if (s != e) text.delete(minOf(s, e), maxOf(s, e))
            else if (s > 0) text.delete(s - Character.charCount(Character.codePointBefore(text, s)), s)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)       // handles selections natively in every app
            if (word.isNotEmpty()) { word.deleteCharAt(word.length - 1); if (word.isEmpty()) wordClean = true }
            updateSuggestions()
        }
    }

    private fun deleteWord() {
        resetWord()
        canUndoAutoCorrect = false
        val et = localTarget
        if (et != null) {
            val text = et.text
            val s = et.selectionStart
            val e = et.selectionEnd
            if (s != e) {
                text.delete(minOf(s, e), maxOf(s, e))
                return
            }
            if (s <= 0) return
            var i = s
            while (i > 0 && text[i - 1].isWhitespace()) {
                i--
            }
            while (i > 0 && !text[i - 1].isWhitespace()) {
                i--
            }
            text.delete(i, s)
        } else {
            val ic = currentInputConnection
            if (ic != null) {
                val before = ic.getTextBeforeCursor(100, 0)?.toString() ?: ""
                if (before.isEmpty()) {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                    return
                }
                var len = before.length
                while (len > 0 && before[len - 1].isWhitespace()) {
                    len--
                }
                while (len > 0 && !before[len - 1].isWhitespace()) {
                    len--
                }
                val charsToDelete = before.length - len
                if (charsToDelete > 0) {
                    ic.deleteSurroundingText(charsToDelete, 0)
                } else {
                    ic.deleteSurroundingText(1, 0)
                }
            } else {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            }
        }
    }

    private fun moveCursor(direction: Int) {
        resetWord()
        canUndoAutoCorrect = false
        val et = localTarget
        if (et != null) {
            val sel = et.selectionStart
            val newSel = (sel + direction).coerceIn(0, et.text.length)
            et.setSelection(newSel)
        } else {
            if (direction < 0) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
            } else {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
            }
        }
    }

    private fun enterKey() {
        when {
            localTarget === promptInput -> submitPrompt()
            localTarget === aiInput -> submitAiInput()
            localTarget === noteTitleInput -> activateLocal(noteContentInput)
            localTarget === noteContentInput -> commit("\n")
            else -> { applyAutoCorrect(""); resetWord(); hostEnter() }
        }
    }

    /** Enter / editor action of the host app (search, go, send...), or a newline in multi-line fields. */
    private fun hostEnter() {
        val ei = currentInputEditorInfo
        val opts = ei?.imeOptions ?: 0
        val action = opts and EditorInfo.IME_MASK_ACTION
        val noAction = (opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    /** Long-press on the Return/send key = a real newline while the key is acting as "send to AI". */
    private fun holdEnter(): Boolean {
        if (localTarget === promptInput || localTarget === aiInput) { commit("\n"); return true }
        return false
    }

    private fun setupKeyboard() {
        keysView.listener = object : KeyboardCanvasView.Listener {
            override fun onText(text: String) = typeText(text)
            override fun onSpace() = handleSpace()
            override fun onBackspace(first: Boolean) = backspace(first)
            override fun onDeleteWord() = deleteWord()
            override fun onCursorMove(direction: Int) = moveCursor(direction)
            override fun onReturnTap() = enterKey()
            override fun onReturnHold() = holdEnter()
        }
        sug1.setOnClickListener { keepTypedWord() }
        sug2.setOnClickListener { applySuggestion(sugTexts[1]) }
        sug3.setOnClickListener { applySuggestion(sugTexts[2]) }
    }

    private fun hostText(): String {
        val ic = currentInputConnection ?: return ""
        val before = ic.getTextBeforeCursor(5000, 0) ?: ""
        val after = ic.getTextAfterCursor(5000, 0) ?: ""
        return "$before$after"
    }

    private fun clearHostField() {
        val ic = currentInputConnection ?: return
        ic.finishComposingText()
        val before = ic.getTextBeforeCursor(100000, 0)?.length ?: 0
        val after = ic.getTextAfterCursor(100000, 0)?.length ?: 0
        ic.deleteSurroundingText(before, after)
    }

    // ── AI panel ──

    private fun fetchUserLimits() {
        if (!PrefsManager.isLoggedIn(this)) return
        scope.launch {
            api.getUserLimits().onSuccess { limits ->
                updateUserLimits(limits.requestLimits, limits.premium)
            }.onFailure {
                if (it is ApiException && it.code == 403) {
                    updateUserLimits(0, false)
                }
            }
        }
    }

    private fun updateUserLimits(rem: Int, premium: Boolean) {
        requestLimits = rem
        isPremium = premium
        aiLimitBadge.text = if (premium) "⚡ ∞" else "⚡ $rem"

        val isLimitExceeded = !premium && rem <= 0
        val enableInput = !isLimitExceeded

        aiInput.isEnabled = enableInput
        btnSendAi.isEnabled = enableInput

        if (isLimitExceeded) {
            aiInput.hint = "Request limit reached (0 remaining)"
            if (localTarget === aiInput) deactivateLocal()
        } else {
            aiInput.hint = "Ask AI anything…"
        }
    }

    /** Return key in ↓ mode: send the prompt, close the keys, show the reply. */
    private fun submitPrompt() {
        if (!isPremium && requestLimits <= 0) {
            toast("Request limit reached. Upgrade to premium for unlimited requests.")
            updateUserLimits(0, isPremium)
            return
        }
        val text = promptInput.text.toString().trim()
        if (text.isEmpty()) return
        promptInput.setText("")
        aiMode = false
        showTab(Tab.AI)
        sendToAi(text)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupAiPanel() {
        makeLocalInput(aiInput)
        aiScroll.setOnTouchListener { _, ev ->                   // tap the replies = hide keys, read in peace
            if (ev.action == MotionEvent.ACTION_DOWN && localTarget != null) deactivateLocal()
            false
        }
        aiLimitBadge.setOnClickListener {
            aiInfoCard.visibility = if (aiInfoCard.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        btnSendAi.setOnClickListener { submitAiInput() }
        btnUseResponse.setOnClickListener {                      // insert last reply into the app's field
            if (lastAiResponse.isNotEmpty()) {
                currentInputConnection?.commitText(lastAiResponse, 1)
                showTab(Tab.KEYBOARD)
            }
        }
    }

    private fun submitAiInput() {
        if (!isPremium && requestLimits <= 0) {
            toast("Request limit reached. Upgrade to premium for unlimited requests.")
            updateUserLimits(0, isPremium)
            return
        }
        val text = aiInput.text.toString().trim()
        if (text.isEmpty()) return
        aiInput.setText("")
        deactivateLocal()                                        // keys disappear, reply gets the space
        sendToAi(text)
    }

    private fun addBubble(text: String, fromUser: Boolean): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_ai_bubble, aiMessages, false)
        val label = v.findViewById<TextView>(R.id.bubble_label)
        val body = v.findViewById<TextView>(R.id.bubble_text)
        body.text = text
        if (fromUser) {
            label.text = "you"
            label.setTextColor(ContextCompat.getColor(this, R.color.brand_green))
            body.background = ContextCompat.getDrawable(this, R.drawable.bg_bubble_user)
        }
        v.findViewById<View>(R.id.bubble_copy).visibility = View.GONE   // shown once a reply is complete
        aiMessages.addView(v)
        scrollAiToBottom()
        return v
    }

    private fun sendToAi(prompt: String) {
        if (chatJob?.isActive == true) { toast("Wait for the current reply…"); return }
        if (!isPremium && requestLimits <= 0) {
            toast("Request limit reached. Upgrade to premium for unlimited requests.")
            updateUserLimits(0, isPremium)
            return
        }

        if (!isPremium) {
            updateUserLimits(maxOf(0, requestLimits - 1), isPremium)
        }

        addBubble(prompt, true)
        chatHistory.add(ChatMessage("user", prompt))
        val bubble = addBubble("", false)
        val body = bubble.findViewById<TextView>(R.id.bubble_text)
        aiLoading.visibility = View.VISIBLE

        chatJob = scope.launch {
            val sb = StringBuilder()
            val result = api.chatStream(
                messages = chatHistory.toList(),
                onRemaining = { rem ->
                    updateUserLimits(rem, isPremium)
                },
                onDelta = { d ->
                    sb.append(d); body.text = sb.toString(); scrollAiToBottom()
                }
            )
            aiLoading.visibility = View.GONE
            result.onSuccess { full ->
                if (full.isBlank()) {
                    body.text = "(empty response)"
                    chatHistory.removeAt(chatHistory.lastIndex)
                } else {
                    chatHistory.add(ChatMessage("assistant", full))
                    lastAiResponse = full
                    val copyBtn = bubble.findViewById<TextView>(R.id.bubble_copy)
                    copyBtn.visibility = View.VISIBLE
                    copyBtn.setOnClickListener { copyToClipboard(copyBtn, full) }
                }
            }.onFailure {
                body.text = "⚠ ${it.message ?: "Something went wrong"}"
                chatHistory.removeAt(chatHistory.lastIndex)      // keep user/assistant alternation valid
                if (it is ApiException && it.code == 403) {
                    updateUserLimits(0, isPremium)
                }
            }
            scrollAiToBottom()
        }
    }

    private fun scrollAiToBottom() { aiScroll.post { aiScroll.fullScroll(View.FOCUS_DOWN) } }

    private fun copyToClipboard(btn: TextView, text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("AI reply", text))
        btn.text = "✓ copied"
        btn.setTextColor(ContextCompat.getColor(this, R.color.brand_green))
        toast("Copied")
        btn.postDelayed({
            btn.text = "copy"
            btn.setTextColor(ContextCompat.getColor(this, R.color.brand_accent))
        }, 1500)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ── notes panel ──

    private fun setupNotesPanel() {
        makeLocalInput(noteTitleInput)
        makeLocalInput(noteContentInput)

        notesAdapter = NotesAdapter(
            onPaste = { note ->
                currentInputConnection?.commitText(note.content, 1)
                showTab(Tab.KEYBOARD)
            },
            onEdit = { note -> showNoteEditor(note) },
            onDelete = { note ->
                scope.launch {
                    api.deleteNote(note.id)
                        .onSuccess { notesAdapter.removeNote(note.id) }
                        .onFailure { toast(it.message ?: "Delete failed") }
                }
            }
        )
        notesRecycler.adapter = notesAdapter
        notesRecycler.layoutManager = LinearLayoutManager(this)

        notesStatus.setOnClickListener {
            if (!PrefsManager.isLoggedIn(this)) {
                startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        btnNewNote.setOnClickListener { showNoteEditor(null) }
        btnCancelNote.setOnClickListener { showNotesList() }
        btnSaveNote.setOnClickListener { saveNote() }
    }

    private fun saveNote() {
        val title = noteTitleInput.text.toString().trim().ifEmpty { "Note" }
        val content = noteContentInput.text.toString().trim()
        if (content.isEmpty()) { toast("Note is empty"); return }
        val id = editingNoteId
        scope.launch {
            val r = if (id == null) api.createNote(title, content) else api.updateNote(id, title, content)
            r.onSuccess { n ->
                if (id == null) notesAdapter.addNote(n) else notesAdapter.replaceNote(n)
                notesStatus.visibility = View.GONE
                showNotesList()
            }.onFailure { toast(it.message ?: "Save failed") }
        }
    }

    private fun loadNotes() {
        if (!PrefsManager.isLoggedIn(this)) {
            setNotesStatus("Sign in from the AI Keyboard app to use notes. Tap to open it.")
            return
        }
        setNotesStatus("Loading…")
        scope.launch {
            api.getNotes()
                .onSuccess {
                    notesAdapter.updateNotes(it)
                    setNotesStatus(if (it.isEmpty()) "No notes yet. Tap + New." else null)
                }
                .onFailure { setNotesStatus("⚠ ${it.message}") }
        }
    }

    private fun setNotesStatus(msg: String?) {
        notesStatus.text = msg ?: ""
        notesStatus.visibility = if (msg == null) View.GONE else View.VISIBLE
    }

    private fun showNoteEditor(note: Note?) {
        editingNoteId = note?.id
        notesListView.visibility = View.GONE
        notesEditView.visibility = View.VISIBLE
        noteTitleInput.setText(note?.title ?: "")
        noteContentInput.setText(note?.content ?: "")
        activateLocal(if (note == null) noteTitleInput else noteContentInput)
    }

    private fun showNotesList() {
        editingNoteId = null
        notesEditView.visibility = View.GONE
        notesListView.visibility = View.VISIBLE
        noteTitleInput.setText(""); noteContentInput.setText("")
        deactivateLocal()
    }

    // ── clipboard panel ──

    private fun setupClipboardPanel() {
        clipboardAdapter = ClipboardAdapter(
            onPaste = { text ->
                commit(text)
                showTab(Tab.KEYBOARD)
            },
            onDelete = { text ->
                PrefsManager.removeClipboardClip(this, text)
                loadClipboard()
            }
        )
        clipRecycler.adapter = clipboardAdapter
        clipRecycler.layoutManager = LinearLayoutManager(this)
        btnClearClip.setOnClickListener {
            PrefsManager.clearClipboardHistory(this)
            loadClipboard()
        }
    }

    private fun loadClipboard() {
        val history = PrefsManager.getClipboardHistory(this)
        clipboardAdapter.updateItems(history)
        clipStatus.visibility = if (history.isEmpty()) View.VISIBLE else View.GONE
    }
}
