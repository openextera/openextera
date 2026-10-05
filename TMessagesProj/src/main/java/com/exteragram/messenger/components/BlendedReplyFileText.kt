package com.exteragram.messenger.components

import android.content.Context
import android.graphics.Paint
import android.text.Spanned
import android.text.SpannableStringBuilder
import androidx.core.content.ContextCompat
import androidx.media3.common.MimeTypes
import com.exteragram.messenger.backup.PreferencesUtils
import com.exteragram.messenger.icons.IconManager
import com.exteragram.messenger.plugins.PluginsController
import com.exteragram.messenger.utils.chats.ChatUtils
import com.exteragram.messenger.utils.text.LocaleUtils
import org.telegram.messenger.Emoji
import org.telegram.messenger.ImageReceiver
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MediaDataController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R
import java.io.File
import java.util.EnumMap

class BlendedReplyFileText {

    enum class AttachType(val iconRes: Int, val stringRes: Int? = null) {
        MUSIC(R.drawable.filled_widget_music),
        GIFT(R.drawable.gift, R.string.ActionStarGift),
        VOICE(R.drawable.msg_filled_data_voice, R.string.Voice),
        ROUND_VIDEO(R.drawable.filled_profile_video_24, R.string.AttachRound),
        PHOTO(R.drawable.msg_filled_reply_image, R.string.StoryPhoto),
        VIDEO(R.drawable.filled_profile_video_24, R.string.StoryVideo),
        PLUGIN(R.drawable.plugins_filled),
        BACKUP(R.drawable.filled_profile_settings),
        ICON_PACK(R.drawable.stickers_filled),
        FILE(R.drawable.msg_filled_reply_files),
        UNKNOWN(R.drawable.msg_filled_reply_files)
    }

    private val icons = EnumMap<AttachType, BlendedReplyColorIconSpan>(AttachType::class.java)

    fun build(context: Context, messageObject: MessageObject, type: AttachType, fontMetrics: Paint.FontMetricsInt?): CharSequence {
        val icon = icons.getOrPut(type) { createIcon(context, type.iconRes) }
        val name = when {
            type == AttachType.MUSIC -> messageObject.musicAuthor + " – " + messageObject.musicTitle
            type.stringRes != null -> LocaleController.getString(type.stringRes)
            else -> messageObject.documentName.takeUnless { it.isNullOrEmpty() } ?: LocaleController.getString(R.string.AttachDocument)
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

    companion object {
        @JvmStatic
        fun getType(messageObject: MessageObject?): AttachType {
            if (messageObject == null) {
                return AttachType.UNKNOWN
            }
            val mimeType = messageObject.document?.mime_type
            val file by lazy(LazyThreadSafetyMode.NONE) {
                if (messageObject.type == MessageObject.TYPE_FILE) {
                    ChatUtils.getInstance().getPathToMessage(messageObject)?.takeIf { it.isNotEmpty() }?.let { File(it) }
                } else {
                    null
                }
            }

            fun isFile() = messageObject.type == MessageObject.TYPE_FILE
            fun isMusic() = messageObject.type == MessageObject.TYPE_MUSIC
            fun isVoice() = messageObject.type == MessageObject.TYPE_VOICE
            fun isRound() = messageObject.type == MessageObject.TYPE_ROUND_VIDEO
            fun isPhoto() = messageObject.type == MessageObject.TYPE_PHOTO || isFile() && MimeTypes.isImage(mimeType)
            fun isVideo() = messageObject.type == MessageObject.TYPE_VIDEO || isFile() && MimeTypes.isVideo(mimeType)
            fun isGif() = messageObject.document?.mime_type == "image/gif" || MimeTypes.isVideo(mimeType)
            fun isPlugin() = file?.let { PluginsController.isPlugin(it, messageObject) } == true
            fun isBackup() = file?.let { PreferencesUtils.getInstance().isBackup(it) } == true
            fun isIconPack() = file?.let { IconManager.isIconPack(it.path, messageObject) } == true

            return when {
                isMusic() -> AttachType.MUSIC
                messageObject.isAnyGift -> AttachType.GIFT
                isVoice() -> AttachType.VOICE
                isRound() -> AttachType.ROUND_VIDEO
                isPhoto() -> AttachType.PHOTO
                isVideo() -> AttachType.VIDEO
                isGif() -> AttachType.UNKNOWN
                isPlugin() -> AttachType.PLUGIN
                isBackup() -> AttachType.BACKUP
                isIconPack() -> AttachType.ICON_PACK
                isFile() -> AttachType.FILE
                else -> AttachType.UNKNOWN
            }
        }
    }
}
