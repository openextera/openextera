package com.exteragram.messenger.components

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.style.CharacterStyle
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.ColoredImageSpan

private val baseHsv = FloatArray(3)
private val nameHsv = FloatArray(3)

private fun blendedReplyColor(saturation: Float = 0.75f): Int {
    val baseColor = Theme.chat_replyTextPaint.color
    val nameColor = Theme.chat_replyNamePaint.color
    Color.colorToHSV(baseColor, baseHsv)
    Color.colorToHSV(nameColor, nameHsv)
    if (baseHsv[2] < 0.35f) {
        baseHsv[2] = 0.5f
    }
    baseHsv[0] = nameHsv[0]
    baseHsv[1] = (nameHsv[1] * saturation).coerceIn(0f, 1f)
    return Color.HSVToColor(Color.alpha(baseColor), baseHsv)
}

class BlendedReplyColorSpan : CharacterStyle() {
    override fun updateDrawState(tp: TextPaint) {
        tp.color = blendedReplyColor()
    }
}

class BlendedReplyColorIconSpan(drawable: Drawable) : ColoredImageSpan(drawable) {
    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        setOverrideColor(blendedReplyColor())
        super.draw(canvas, text, start, end, x, top, y, bottom, paint)
    }
}
