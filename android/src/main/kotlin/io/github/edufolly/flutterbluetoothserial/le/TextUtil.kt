package io.github.edufolly.flutterbluetoothserial.le

import android.text.Editable
import android.text.InputType
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.widget.TextView
import androidx.annotation.ColorInt
import java.io.ByteArrayOutputStream

object TextUtil {

    @ColorInt
    var caretBackground: Int = 0xff666666.toInt()

    const val NEWLINE_CRLF = "\r\n"
    const val NEWLINE_LF = "\n"

    fun fromHexString(s: CharSequence): ByteArray {
        val buf = ByteArrayOutputStream()
        var b = 0
        var nibble = 0
        for (pos in s.indices) {
            if (nibble == 2) {
                buf.write(b)
                nibble = 0
                b = 0
            }
            val c = s[pos]
            if (c in '0'..'9') { nibble++; b *= 16; b += c - '0' }
            if (c in 'A'..'F') { nibble++; b *= 16; b += c - 'A' + 10 }
            if (c in 'a'..'f') { nibble++; b *= 16; b += c - 'a' + 10 }
        }
        if (nibble > 0) buf.write(b)
        return buf.toByteArray()
    }

    fun toHexString(buf: ByteArray, begin: Int = 0, end: Int = buf.size): String {
        val sb = StringBuilder(3 * (end - begin))
        toHexString(sb, buf, begin, end)
        return sb.toString()
    }

    fun toHexString(sb: StringBuilder, buf: ByteArray, begin: Int = 0, end: Int = buf.size) {
        for (pos in begin until end) {
            if (sb.isNotEmpty()) sb.append(' ')
            val v = buf[pos].toInt() and 0xff
            sb.append(Character.forDigit(v / 16, 16).uppercaseChar())
            sb.append(Character.forDigit(v % 16, 16).uppercaseChar())
        }
    }

    /** Uses caret notation (https://en.wikipedia.org/wiki/Caret_notation) to avoid invisible control characters. */
    fun toCaretString(s: CharSequence, keepNewline: Boolean, length: Int = s.length): CharSequence {
        fun isControl(c: Char) = c.code < 32 && (!keepNewline || c != '\n')

        if ((0 until length).none { isControl(s[it]) }) return s
        val sb = SpannableStringBuilder()
        for (pos in 0 until length) {
            val c = s[pos]
            if (isControl(c)) {
                sb.append('^')
                sb.append((c.code + 64).toChar())
                sb.setSpan(
                    BackgroundColorSpan(caretBackground), sb.length - 2, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            } else {
                sb.append(c)
            }
        }
        return sb
    }

    class HexWatcher(private val view: TextView) : TextWatcher {

        private val sb = StringBuilder()
        private var self = false
        private var enabled = false

        fun enable(enable: Boolean) {
            view.inputType = if (enable) {
                InputType.TYPE_CLASS_TEXT + InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT + InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            enabled = enable
        }

        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(s: Editable) {
            if (!enabled || self) return

            sb.setLength(0)
            for (c in s) {
                if (c in '0'..'9') sb.append(c)
                if (c in 'A'..'F') sb.append(c)
                if (c in 'a'..'f') sb.append(c - ('a' - 'A'))
            }
            var i = 2
            while (i < sb.length) {
                sb.insert(i, ' ')
                i += 3
            }
            val s2 = sb.toString()

            if (s2 != s.toString()) {
                self = true
                s.replace(0, s.length, s2)
                self = false
            }
        }
    }
}
