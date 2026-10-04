package com.exteragram.messenger.components

import android.content.Context
import android.graphics.Paint
import android.text.Spanned
import android.text.SpannableStringBuilder
import androidx.core.content.ContextCompat
import com.exteragram.messenger.utils.text.LocaleUtils
import org.telegram.messenger.Emoji
import org.telegram.messenger.ImageReceiver
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R

class BlendedReplyFileText {

    private var fileIcon: BlendedReplyColorIconSpan? = null
    private var musicIcon: BlendedReplyColorIconSpan? = null

    fun build(context: Context, messageObject: MessageObject, fontMetrics: Paint.FontMetricsInt?): CharSequence {
        val isMusic = messageObject.type == MessageObject.TYPE_MUSIC
        val icon = if (isMusic) {
            musicIcon ?: createIcon(context, R.drawable.filled_widget_music).also { musicIcon = it }
        } else {
            fileIcon ?: createIcon(context, R.drawable.msg_round_file_s).also { fileIcon = it }
        }
        val name = if (isMusic) {
            messageObject.musicAuthor + " – " + messageObject.musicTitle
        } else {
            messageObject.documentName.takeUnless { it.isNullOrEmpty() } ?: LocaleController.getString(R.string.AttachDocument)
        }
        val builder = SpannableStringBuilder("d ").append(Emoji.replaceEmoji(name, fontMetrics, false))
        builder.setSpan(icon, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(BlendedReplyColorSpan(), 0, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val caption = messageObject.caption
        if (!caption.isNullOrEmpty()) {
            builder.append(", ").append(formatCaption(messageObject, caption, fontMetrics))
        }
        return builder
    }

    private fun formatCaption(messageObject: MessageObject, caption: CharSequence, fontMetrics: Paint.FontMetricsInt?): CharSequence {
        val text = Emoji.replaceEmoji(caption.toString().take(ImageReceiver.DEFAULT_CROSSFADE_DURATION).replace('\n', ' '), fontMetrics, true)
        val entities = messageObject.messageOwner?.entities ?: return text
        val copy = ArrayList(entities)
        LocaleUtils.parseCustomEmojis(text, copy)
        val spannable = MessageObject.replaceAnimatedEmoji(text, copy, fontMetrics, true)
        MediaDataController.addTextStyleRuns(entities, caption, spannable)
        return spannable
    }

    private fun createIcon(context: Context, resId: Int): BlendedReplyColorIconSpan {
        val span = BlendedReplyColorIconSpan(ContextCompat.getDrawable(context, resId)!!.mutate())
        span.setScale(0.7f, 0.7f)
        return span
    }
}
