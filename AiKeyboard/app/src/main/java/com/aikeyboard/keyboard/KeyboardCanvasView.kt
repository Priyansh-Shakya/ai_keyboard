package com.aikeyboard.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.SparseArray
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.aikeyboard.R
import kotlin.math.abs

/**
 * The whole key area as ONE view that draws every key itself and tracks every finger itself, the same
 * architecture as the AOSP keyboard that Gboard grew out of. Compared with ~90 TextViews in nested
 * layouts: no view-hierarchy touch dispatch, no per-key relayout, and every finger is tracked
 * separately, so fast two-thumb typing (rollover) can't drop or reorder keys.
 */
class KeyboardCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onText(text: String)
        fun onSpace()
        fun onBackspace(first: Boolean)
        fun onDeleteWord()
        fun onCursorMove(direction: Int)      // -1 = left, +1 = right
        fun onReturnTap()
        fun onReturnHold(): Boolean           // true if the hold was handled (e.g. inserted a newline)
    }

    var listener: Listener? = null

    /** Haptics make a synchronous system call per key, so they are off unless the user enables them. */
    var haptics = false

    private enum class Kind { CHAR, SPACE, SHIFT, DELETE, MODE, RETURN, GAP }

    private class Key(val kind: Kind, var label: String, val weight: Float, val style: Int, val sp: Float = 20f) {
        val upper: String = label.uppercase()
        val rect = RectF()
        var pressed = false
    }

    private class Touch(val key: Key, val downX: Float) {
        var lastX = downX
        var slid = false          // DELETE: swiped into word-delete mode.  SPACE: sliding the cursor.
        var done = false          // SPACE / RETURN: already acted on (forced early by the next key press)
    }

    private fun chars(s: String, sp: Float = 20f) = s.map { Key(Kind.CHAR, it.toString(), 1f, 0, sp) }
    private fun gap(w: Float) = Key(Kind.GAP, "", w, 0)
    private fun space() = Key(Kind.SPACE, "SPACE", 5f, 0, 11f)
    private fun ret() = Key(Kind.RETURN, "↵", 1.5f, 2)

    private val letters: List<List<Key>> = listOf(
        chars("1234567890", 18f),
        chars("qwertyuiop"),
        listOf(gap(0.5f)) + chars("asdfghjkl") + gap(0.5f),
        listOf(Key(Kind.SHIFT, "⇧", 1.5f, 1)) + chars("zxcvbnm") + Key(Kind.DELETE, "⌫", 1.5f, 1),
        listOf(Key(Kind.MODE, "123", 1.5f, 1, 14f), Key(Kind.CHAR, ",", 0.9f, 0), space(),
            Key(Kind.CHAR, ".", 0.9f, 0), ret())
    )

    private val symbols: List<List<Key>> = listOf(
        chars("1234567890"),
        chars("@#$%&-+()/"),
        chars("!?_=*:;'\"") + Key(Kind.DELETE, "⌫", 1f, 1),
        listOf(Key(Kind.MODE, "ABC", 1.5f, 1, 14f), Key(Kind.CHAR, ",", 0.9f, 0), space(),
            Key(Kind.CHAR, ".", 0.9f, 0), ret())
    )

    private var rows = letters
    private var shiftOn = false
    private var symbolMode = false

    // ── drawing ──
    private val density = resources.displayMetrics.density
    private val keyH = resources.getDimensionPixelSize(R.dimen.key_height)
    private val bottomPad = (4 * density).toInt()
    private val inset = 2 * density
    private val radius = resources.getDimension(R.dimen.key_corner_radius)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private val cBg = context.getColor(R.color.brand_primary)
    private val cNormal = context.getColor(R.color.key_normal)
    private val cNormalHi = context.getColor(R.color.key_pressed)
    private val cSpecial = context.getColor(R.color.key_special)
    private val cSpecialHi = context.getColor(R.color.brand_accent_dim)
    private val cAccent = context.getColor(R.color.brand_accent)
    private val cAccentHi = 0xFF8B84FF.toInt()
    private val cText = context.getColor(R.color.key_text)
    private val cDim = context.getColor(R.color.text_secondary)
    private val cWhite = context.getColor(R.color.white)

    // gesture thresholds
    private val deleteSwipePx = 40 * density
    private val spaceStartPx = 14 * density
    private val spaceStepPx = 16 * density

    /** Constant height (5 letter rows) so switching to the symbol page never resizes the keyboard. */
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), keyH * 5 + bottomPad)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutRows(letters, w)
        layoutRows(symbols, w)
    }

    private fun layoutRows(rs: List<List<Key>>, w: Int) {
        val rh = keyH * 5f / rs.size
        rs.forEachIndexed { r, row ->
            val total = row.sumOf { it.weight.toDouble() }.toFloat()
            val top = r * rh
            var x = 0f
            for (k in row) {
                val kw = w * k.weight / total
                k.rect.set(x, top, x + kw, top + rh)
                x += kw
            }
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // Keep edge swipes (the "back" gesture) from stealing taps on q / p / a / l.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val h = minOf(b - t, (200 * density).toInt())
            setSystemGestureExclusionRects(listOf(Rect(0, 0, r - l, h)))
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(cBg)
        for (row in rows) for (k in row) {
            if (k.kind == Kind.GAP) continue
            val r = k.rect
            fill.color = when (k.style) {
                2 -> if (k.pressed) cAccentHi else cAccent
                1 -> if (k.pressed) cSpecialHi else cSpecial
                else -> if (k.pressed) cNormalHi else cNormal
            }
            canvas.drawRoundRect(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset, radius, radius, fill)

            textPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, k.sp, resources.displayMetrics)
            textPaint.color = when {
                k.style == 2 -> cWhite
                k.kind == Kind.SHIFT && shiftOn -> cAccent
                k.style == 1 || k.kind == Kind.SPACE -> cDim
                else -> cText
            }
            val text = if (k.kind == Kind.CHAR && shiftOn) k.upper else k.label
            canvas.drawText(text, r.centerX(), r.centerY() - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
        }
    }

    // ── touch: one tracker per finger ──
    private val active = SparseArray<Touch>()          // pointerId -> what that finger is doing
    private val timers = Handler(Looper.getMainLooper())
    private var repeater: Runnable? = null
    private var holder: Runnable? = null
    private var returnHeld = false

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                press(ev.getPointerId(i), ev.getX(i), ev.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until ev.pointerCount) move(ev.getPointerId(i), ev.getX(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                release(ev.getPointerId(ev.actionIndex), false)
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return true
    }

    /** Nearest key in the touched row; the half-key spacers and any gaps resolve to the closest real key. */
    private fun keyAt(x: Float, y: Float): Key? {
        val rh = keyH * 5f / rows.size
        val row = rows[(y / rh).toInt().coerceIn(0, rows.size - 1)]
        var best: Key? = null
        var bestD = Float.MAX_VALUE
        for (k in row) {
            if (k.kind == Kind.GAP) continue
            val d = when {
                x < k.rect.left -> k.rect.left - x
                x > k.rect.right -> x - k.rect.right
                else -> 0f
            }
            if (d < bestD) { bestD = d; best = k }
        }
        return best
    }

    private fun tap() { if (haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }

    /**
     * Space and Return act on release (so Space can turn into a cursor slider), but if another finger
     * presses a key first they must be committed NOW, or fast typing would reorder: "hellow ".
     */
    private fun commitPendingTaps() {
        for (i in 0 until active.size()) {
            val t = active.valueAt(i)
            if (t.done || t.slid) continue
            when (t.key.kind) {
                Kind.SPACE -> { t.done = true; listener?.onSpace() }
                Kind.RETURN -> {
                    t.done = true
                    holder?.let { timers.removeCallbacks(it) }
                    holder = null
                    listener?.onReturnTap()
                }
                else -> { }
            }
        }
    }

    private fun press(id: Int, x: Float, y: Float) {
        val k = keyAt(x, y) ?: return
        commitPendingTaps()
        k.pressed = true
        val touch = Touch(k, x)
        active.put(id, touch)
        tap()
        when (k.kind) {
            Kind.CHAR -> {
                listener?.onText(if (shiftOn) k.upper else k.label)   // text goes out FIRST, drawing after
                shiftOn = false
            }
            Kind.SHIFT -> shiftOn = !shiftOn
            Kind.MODE -> { symbolMode = !symbolMode; rows = if (symbolMode) symbols else letters }
            Kind.DELETE -> { listener?.onBackspace(true); startRepeat(touch) }
            Kind.RETURN -> { returnHeld = false; startHold() }
            Kind.SPACE, Kind.GAP -> { }
        }
        invalidate()
    }

    private fun move(id: Int, x: Float) {
        val t = active.get(id) ?: return
        when (t.key.kind) {
            Kind.DELETE -> if (!t.slid && x - t.downX < -deleteSwipePx) {
                t.slid = true                                          // drag left = delete word by word
                if (haptics) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                listener?.onDeleteWord()
            }
            Kind.SPACE -> {
                if (!t.slid && abs(x - t.downX) > spaceStartPx) { t.slid = true; tap() }
                if (t.slid) {
                    while (x - t.lastX >= spaceStepPx) { t.lastX += spaceStepPx; listener?.onCursorMove(1) }
                    while (t.lastX - x >= spaceStepPx) { t.lastX -= spaceStepPx; listener?.onCursorMove(-1) }
                }
            }
            else -> { }
        }
    }

    private fun release(id: Int, cancelled: Boolean) {
        val t = active.get(id) ?: return
        active.remove(id)
        t.key.pressed = false
        when (t.key.kind) {
            Kind.DELETE -> stopRepeat()
            Kind.SPACE -> if (!cancelled && !t.done && !t.slid) listener?.onSpace()
            Kind.RETURN -> {
                holder?.let { timers.removeCallbacks(it) }
                holder = null
                if (!cancelled && !t.done && !returnHeld) listener?.onReturnTap()
            }
            else -> { }
        }
        invalidate()
    }

    private fun releaseAll() {
        for (i in active.size() - 1 downTo 0) release(active.keyAt(i), true)
    }

    private fun startRepeat(t: Touch) {
        stopRepeat()
        val r = object : Runnable {
            override fun run() {
                if (t.slid) { listener?.onDeleteWord(); timers.postDelayed(this, 120) }
                else { listener?.onBackspace(false); timers.postDelayed(this, 45) }
            }
        }
        repeater = r
        timers.postDelayed(r, 380)
    }

    private fun stopRepeat() {
        repeater?.let { timers.removeCallbacks(it) }
        repeater = null
    }

    private fun startHold() {
        holder?.let { timers.removeCallbacks(it) }
        val r = Runnable {
            returnHeld = listener?.onReturnHold() ?: false
            if (returnHeld && haptics) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
        holder = r
        timers.postDelayed(r, 400)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) releaseAll()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopRepeat()
        holder?.let { timers.removeCallbacks(it) }
    }

    // ── API for the service ──
    fun setReturnGlyph(glyph: String) {
        var changed = false
        for (row in letters + symbols) for (k in row) {
            if (k.kind == Kind.RETURN && k.label != glyph) { k.label = glyph; changed = true }
        }
        if (changed) invalidate()
    }

    fun reset() {
        shiftOn = false; symbolMode = false; rows = letters
        invalidate()
    }
}
