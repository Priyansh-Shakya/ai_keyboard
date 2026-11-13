package com.aikeyboard.keyboard

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
import com.aikeyboard.data.ApiClient
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

class AiKeyboardService : InputMethodService() {

    private enum class Tab { KEYBOARD, AI, NOTES }

    // ── state ──
    private var isShifted = false
    private var isSymbolMode = false
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
    private val repeatHandler = Handler(Looper.getMainLooper())
    private val api by lazy { ApiClient(this) }
    private lateinit var notesAdapter: NotesAdapter
    private val letterViews = mutableListOf<Pair<TextView, String>>()

    // ── views ──
    private lateinit var rootView: View
    private lateinit var tabGroup: View
    private lateinit var tabKeyboard: TextView
    private lateinit var tabAi: TextView
    private lateinit var tabNotes: TextView
    private lateinit var promptInput: EditText
    private lateinit var btnMode: TextView
    private lateinit var panelKeyboard: View
    private lateinit var panelSymbols: View
    private lateinit var panelAi: View
    private lateinit var panelNotes: View
    private lateinit var keyReturn: TextView
    private lateinit var symReturn: TextView
    private lateinit var aiScroll: ScrollView
    private lateinit var aiMessages: LinearLayout
    private lateinit var aiInput: EditText
    private lateinit var btnSendAi: TextView
    private lateinit var btnUseResponse: TextView
    private lateinit var aiLoading: View
    private lateinit var notesRecycler: RecyclerView
    private lateinit var notesListView: View
    private lateinit var notesEditView: View
    private lateinit var notesStatus: TextView
    private lateinit var noteTitleInput: EditText
    private lateinit var noteContentInput: EditText
    private lateinit var btnNewNote: TextView
    private lateinit var btnSaveNote: TextView
    private lateinit var btnCancelNote: TextView

    // ── lifecycle ──

    override fun onEvaluateFullscreenMode() = false   // never take over the screen in landscape

    override fun onCreateInputView(): View {
        rootView = LayoutInflater.from(this).inflate(R.layout.keyboard_view, null)
        bindViews()
        setupKeyboard()
        setupToolbar()
        setupAiPanel()
        setupNotesPanel()
        return rootView
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!::rootView.isInitialized) return
        if (!restarting) showTab(Tab.KEYBOARD)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun bindViews() {
        fun <T : View> id(i: Int): T = rootView.findViewById(i)
        tabGroup = id(R.id.tab_group)
        tabKeyboard = id(R.id.tab_keyboard); tabAi = id(R.id.tab_ai); tabNotes = id(R.id.tab_notes)
        promptInput = id(R.id.prompt_input); btnMode = id(R.id.btn_mode)
        panelKeyboard = id(R.id.panel_keyboard); panelSymbols = id(R.id.panel_symbols)
        panelAi = id(R.id.panel_ai); panelNotes = id(R.id.panel_notes)
        keyReturn = id(R.id.key_return); symReturn = id(R.id.sym_return)
        aiScroll = id(R.id.ai_scroll); aiMessages = id(R.id.ai_messages_container)
        aiInput = id(R.id.ai_input); btnSendAi = id(R.id.btn_send_ai)
        btnUseResponse = id(R.id.btn_use_response); aiLoading = id(R.id.ai_loading)
        notesRecycler = id(R.id.notes_recycler); notesListView = id(R.id.notes_list_view)
        notesEditView = id(R.id.notes_edit_view); notesStatus = id(R.id.notes_status)
        noteTitleInput = id(R.id.note_title_input); noteContentInput = id(R.id.note_content_input)
        btnNewNote = id(R.id.btn_new_note); btnSaveNote = id(R.id.btn_save_note)
        btnCancelNote = id(R.id.btn_cancel_note)
    }

    // ── toolbar, tabs & layout ──

    private fun setupToolbar() {
        tabKeyboard.setOnClickListener { showTab(Tab.KEYBOARD) }
        tabAi.setOnClickListener { showTab(Tab.AI) }
        tabNotes.setOnClickListener { showTab(Tab.NOTES); showNotesList(); loadNotes() }
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
        setLocal(if (tab == Tab.KEYBOARD && aiMode) promptInput else null)
        val on = ContextCompat.getDrawable(this, R.drawable.bg_tab_selected)
        val off = ContextCompat.getDrawable(this, R.drawable.bg_tab_unselected)
        val onColor = ContextCompat.getColor(this, R.color.white)
        val offColor = ContextCompat.getColor(this, R.color.text_secondary)
        listOf(tabKeyboard to Tab.KEYBOARD, tabAi to Tab.AI, tabNotes to Tab.NOTES).forEach { (v, t) ->
            v.background = if (t == tab) on else off
            v.setTextColor(if (t == tab) onColor else offColor)
        }
        refreshLayout()
    }

    private fun refreshLayout() {
        val kb = currentTab == Tab.KEYBOARD
        val typingLocally = localTarget != null
        val showKeys = kb || typingLocally

        tabGroup.visibility = if (kb && aiMode) View.GONE else View.VISIBLE
        promptInput.visibility = if (kb && aiMode) View.VISIBLE else View.GONE
        btnMode.visibility = if (kb) View.VISIBLE else View.GONE
        btnMode.text = if (aiMode) "↓" else "↑"
        btnMode.background = ContextCompat.getDrawable(
            this, if (aiMode) R.drawable.bg_key_accent else R.drawable.bg_key_special)

        panelKeyboard.visibility = if (showKeys && !isSymbolMode) View.VISIBLE else View.GONE
        panelSymbols.visibility = if (showKeys && isSymbolMode) View.VISIBLE else View.GONE
        panelAi.visibility = if (currentTab == Tab.AI) View.VISIBLE else View.GONE
        panelNotes.visibility = if (currentTab == Tab.NOTES) View.VISIBLE else View.GONE
        // Panels shrink while the keys sit below them, and grow back to read replies.
        setHeightDp(panelAi, if (typingLocally) 110 else 260)
        setHeightDp(panelNotes, if (typingLocally) 130 else 260)

        val glyph = if (localTarget === promptInput || localTarget === aiInput) "✦" else "↵"
        keyReturn.text = glyph
        symReturn.text = glyph
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
    }

    /** Deletes the selection if there is one, otherwise one character. */
    private fun backspace(first: Boolean = true) {
        val et = localTarget
        if (et != null) {
            val text = et.text
            val s = et.selectionStart
            val e = et.selectionEnd
            if (s != e) text.delete(minOf(s, e), maxOf(s, e))
            else if (s > 0) text.delete(s - Character.charCount(Character.codePointBefore(text, s)), s)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)       // handles selections natively in every app
        }
    }

    /** Press = act once; hold = repeat. */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeRepeatable(key: View, action: (first: Boolean) -> Unit) {
        var repeater: Runnable? = null
        key.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    action(true)
                    val r = object : Runnable {
                        override fun run() { action(false); repeatHandler.postDelayed(this, 45) }
                    }
                    repeater = r
                    repeatHandler.postDelayed(r, 380)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    repeater?.let { repeatHandler.removeCallbacks(it) }
                    repeater = null
                }
            }
            true
        }
    }

    private fun enterKey() {
        when {
            localTarget === promptInput -> submitPrompt()
            localTarget === aiInput -> submitAiInput()
            localTarget === noteTitleInput -> activateLocal(noteContentInput)
            localTarget === noteContentInput -> commit("\n")
            else -> hostEnter()
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

    // ── keys ──

    /** Fires on finger-DOWN (like real keyboards): no lost taps from release-slop, lower latency. */
    @SuppressLint("ClickableViewAccessibility")
    private fun onKeyDown(key: View, action: () -> Unit) {
        key.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    action()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.isPressed = false
            }
            true
        }
    }

    /** Tap acts on release; holding for 0.4s runs [onHold] instead (if it returns true it was handled). */
    @SuppressLint("ClickableViewAccessibility")
    private fun onTapOrHold(key: View, onTap: () -> Unit, onHold: () -> Boolean) {
        var holder: Runnable? = null
        var held = false
        key.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    held = false
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    val r = Runnable {
                        held = onHold()
                        if (held) v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                    holder = r
                    repeatHandler.postDelayed(r, 400)
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    holder?.let { repeatHandler.removeCallbacks(it) }
                    holder = null
                    if (!held) onTap()
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    holder?.let { repeatHandler.removeCallbacks(it) }
                    holder = null
                }
            }
            true
        }
    }

    /** Long-press on the Return/send key = a real newline while the key is acting as "send to AI". */
    private fun holdEnter(): Boolean {
        if (localTarget === promptInput || localTarget === aiInput) { commit("\n"); return true }
        return false
    }

    private fun setupKeyboard() {
        "qwertyuiopasdfghjklzxcvbnm".forEach { c ->
            val v = rootView.findViewById<TextView>(resources.getIdentifier("key_$c", "id", packageName))
            letterViews.add(v to c.toString())
            onKeyDown(v) {
                commit(if (isShifted) c.toString().uppercase() else c.toString())
                if (isShifted) { isShifted = false; updateShift() }
            }
        }
        listOf(R.id.key_comma to ",", R.id.key_period to ".", R.id.key_space to " ").forEach { (id, s) ->
            onKeyDown(rootView.findViewById(id)) { commit(s) }
        }
        makeRepeatable(rootView.findViewById(R.id.key_delete)) { first -> backspace(first) }
        onTapOrHold(keyReturn, onTap = { enterKey() }, onHold = { holdEnter() })
        onKeyDown(rootView.findViewById(R.id.key_shift)) { isShifted = !isShifted; updateShift() }
        rootView.findViewById<TextView>(R.id.key_symbols).setOnClickListener { isSymbolMode = true; refreshLayout() }

        // Symbol panel: every single-character key types its own label.
        val special = setOf(R.id.sym_del2, R.id.sym_abc, R.id.sym_space, R.id.sym_return)
        forEachLeaf(panelSymbols) { v ->
            if (v is TextView && v.id != View.NO_ID && v.id !in special) {
                onKeyDown(v) { commit(v.text.toString()) }
            }
        }
        onKeyDown(rootView.findViewById(R.id.sym_space)) { commit(" ") }
        makeRepeatable(rootView.findViewById(R.id.sym_del2)) { first -> backspace(first) }
        onTapOrHold(symReturn, onTap = { enterKey() }, onHold = { holdEnter() })
        rootView.findViewById<TextView>(R.id.sym_abc).setOnClickListener { isSymbolMode = false; refreshLayout() }
    }

    private fun forEachLeaf(v: View, fn: (View) -> Unit) {
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) forEachLeaf(v.getChildAt(i), fn)
        } else {
            fn(v)
        }
    }

    private fun updateShift() {
        letterViews.forEach { (v, c) -> v.text = if (isShifted) c.uppercase() else c }
        val key = rootView.findViewById<TextView>(R.id.key_shift)
        key.setTextColor(ContextCompat.getColor(this, if (isShifted) R.color.brand_accent else R.color.text_secondary))
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

    /** Return key in ↓ mode: send the prompt, close the keys, show the reply. */
    private fun submitPrompt() {
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
        btnSendAi.setOnClickListener { submitAiInput() }
        btnUseResponse.setOnClickListener {                      // insert last reply into the app's field
            if (lastAiResponse.isNotEmpty()) {
                currentInputConnection?.commitText(lastAiResponse, 1)
                showTab(Tab.KEYBOARD)
            }
        }
    }

    private fun submitAiInput() {
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
        addBubble(prompt, true)
        chatHistory.add(ChatMessage("user", prompt))
        val bubble = addBubble("", false)
        val body = bubble.findViewById<TextView>(R.id.bubble_text)
        aiLoading.visibility = View.VISIBLE

        chatJob = scope.launch {
            val sb = StringBuilder()
            val result = api.chatStream(chatHistory.toList()) { d ->
                sb.append(d); body.text = sb.toString(); scrollAiToBottom()
            }
            aiLoading.visibility = View.GONE
            result.onSuccess { full ->
                if (full.isBlank()) {
                    body.text = "(empty response)"
                    chatHistory.removeAt(chatHistory.lastIndex)
                } else {
                    chatHistory.add(ChatMessage("assistant", full))
                    lastAiResponse = full
                    bubble.findViewById<TextView>(R.id.bubble_copy).apply {
                        visibility = View.VISIBLE
                        setOnClickListener { copyToClipboard(full) }
                    }
                }
            }.onFailure {
                body.text = "⚠ ${it.message ?: "Something went wrong"}"
                chatHistory.removeAt(chatHistory.lastIndex)      // keep user/assistant alternation valid
            }
            scrollAiToBottom()
        }
    }

    private fun scrollAiToBottom() { aiScroll.post { aiScroll.fullScroll(View.FOCUS_DOWN) } }

    private fun copyToClipboard(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("AI reply", text))
        toast("Copied")
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
}
