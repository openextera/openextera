package com.exteragram.messenger.nowplaying.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.exteragram.messenger.ExteraConfig
import com.exteragram.messenger.badges.BadgesController
import com.exteragram.messenger.components.SupporterBottomSheet
import com.exteragram.messenger.nowplaying.ServiceEmoji
import com.exteragram.messenger.utils.ui.UIUtil
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.Emoji
import org.telegram.messenger.LocaleController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.browser.Browser
import org.telegram.messenger.utils.ViewOutlineProviderImpl
import org.telegram.ui.ActionBar.SimpleTextView
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AnimatedEmojiDrawable
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.PlayPauseDrawable
import org.telegram.ui.Components.ScaleStateListAnimator
import org.telegram.ui.LaunchActivity

@SuppressLint("ViewConstructor")
abstract class NowPlayingCard(
    context: Context,
    private val resourcesProvider: Theme.ResourcesProvider?
) : FrameLayout(context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (player?.isPlaying == true) {
                    resumeOnFocusGain = true
                    player?.pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                player?.pause()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnFocusGain) {
                    player?.play()
                    resumeOnFocusGain = false
                }
            }
        }
    }

    private val cardLayout: FrameLayout
    private val emoji: AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable
    private val imageView: BackupImageView
    private val nameView: SimpleTextView
    private val artistView: SimpleTextView
    private val albumView: SimpleTextView
    private val playPauseButton: ImageView
    private val playPauseDrawable: PlayPauseDrawable

    private var nowPlayingCardData: NowPlayingCardData? = null
    private var currentDocId = -1L
    private var currentPreviewUrl: String? = null
    private var player: ExoPlayer? = null
    private var isPlaying = false

    abstract fun onSavedMusicClick()

    private val coverCornerRadius: Float
        get() = AndroidUtilities.dpf2(maxOf(ExteraConfig.sectionRadiusDp - 12, 8).toFloat())

    init {
        isClickable = false
        setWillNotDraw(false)

        cardLayout = object : FrameLayout(context) {
            override fun dispatchDraw(canvas: Canvas) {
                val data = nowPlayingCardData
                if (data != null) {
                    emoji.setColor(data.accentColor ?: getThemedColor(Theme.key_windowBackgroundWhiteBlackText))
                    UIUtil.drawNowPlayingPattern(
                        canvas,
                        emoji,
                        width.toFloat(),
                        height.toFloat(),
                        if (data.coverBitmap == null) 0.4f else 1.0f
                    )
                }
                super.dispatchDraw(canvas)
            }
        }
        val backgroundDrawable = object : GradientDrawable() {
            override fun onBoundsChange(r: Rect) {
                super.onBoundsChange(r)
                gradientRadius = r.width() * 2.0f
            }
        }
        backgroundDrawable.cornerRadius = AndroidUtilities.dpf2(ExteraConfig.sectionRadiusDp.toFloat())
        cardLayout.background = backgroundDrawable
        cardLayout.clipToOutline = true
        cardLayout.outlineProvider = ViewOutlineProviderImpl.fromDrawable(backgroundDrawable)
        cardLayout.isClickable = true
        ScaleStateListAnimator.apply(cardLayout, 0.035f, 1.5f)
        addView(cardLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat()))

        emoji = AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(cardLayout, false, AndroidUtilities.dp(20f), AnimatedEmojiDrawable.CACHE_TYPE_ALERT_PREVIEW_STATIC)

        val contentLayout = LinearLayout(context)
        contentLayout.orientation = LinearLayout.HORIZONTAL
        cardLayout.addView(contentLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.FILL, 12, 12, 12, 12))

        imageView = BackupImageView(context)
        imageView.clipToOutline = true
        imageView.outlineProvider = ViewOutlineProviderImpl.boundsWithRoundRect(coverCornerRadius)
        contentLayout.addView(imageView, LayoutHelper.createLinear(64, 64, Gravity.LEFT or Gravity.CENTER_VERTICAL, 0, 0, 12, 0))

        val textLayout = LinearLayout(context)
        textLayout.orientation = LinearLayout.VERTICAL
        contentLayout.addView(textLayout, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1.0f, Gravity.CENTER_VERTICAL))

        nameView = SimpleTextView(context).apply {
            setGravity(Gravity.LEFT or Gravity.CENTER_VERTICAL)
            setTextColor(Color.WHITE)
            setTextSize(16)
            setTypeface(AndroidUtilities.bold())
            NotificationCenter.listenEmojiLoading(this)
        }
        textLayout.addView(nameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 22))

        artistView = createSecondaryTextView(context)
        textLayout.addView(artistView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 19, 0f, 2f, 0f, 0f))

        albumView = createSecondaryTextView(context)
        textLayout.addView(albumView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 19, 0f, 2f, 0f, 0f))

        playPauseDrawable = PlayPauseDrawable(16).apply {
            setPause(false)
            setColor(Color.WHITE)
        }
        playPauseButton = ImageView(context).apply {
            ScaleStateListAnimator.apply(this)
            scaleType = ImageView.ScaleType.CENTER
            setImageDrawable(playPauseDrawable)
            setOnClickListener { togglePlayPause() }
        }
        contentLayout.addView(playPauseButton, LayoutHelper.createLinear(32, 32, Gravity.CENTER_VERTICAL, 8, 0, 8, 0))
    }

    private fun createSecondaryTextView(context: Context) = SimpleTextView(context).apply {
        setGravity(Gravity.LEFT or Gravity.CENTER_VERTICAL)
        setTypeface(AndroidUtilities.regular())
        setTextSize(14)
        setTextColor(Color.WHITE)
        alpha = 0.6f
        NotificationCenter.listenEmojiLoading(this)
    }

    fun set(data: NowPlayingCardData) {
        nowPlayingCardData = data
        val track = data.nowPlayingDTO
        val artists = track.artists

        artistView.setText(null)
        nameView.setText(null)
        albumView.setText(null)

        artistView.setText(if (artists.isNullOrEmpty()) {
            LocaleController.getString(R.string.AudioUnknownArtist)
        } else {
            artists.joinToString(", ")
        })
        nameView.setText(Emoji.replaceEmoji(track.trackName, nameView.paint.fontMetricsInt, false))
        albumView.visibility = if (!track.albumName.isNullOrEmpty() && track.trackName != track.albumName) VISIBLE else GONE
        if (albumView.visibility == VISIBLE) {
            albumView.setText(Emoji.replaceEmoji(track.albumName, albumView.paint.fontMetricsInt, false))
        }

        setPadding(AndroidUtilities.dp(12f), 0, AndroidUtilities.dp(12f), 0)

        (cardLayout.background as? GradientDrawable)?.let { drawable ->
            drawable.mutate()
            drawable.setDither(true)
            drawable.gradientType = GradientDrawable.RADIAL_GRADIENT
            drawable.setGradientCenter(1.0f, 0.5f)
            val color = data.backgroundColor ?: getThemedColor(Theme.key_windowBackgroundWhite)
            val secondColor = if (data.backgroundColor != null) UIUtil.adjustHsl(color, 1.5f) else color
            drawable.colors = intArrayOf(color, secondColor)
        }

        playPauseButton.visibility = if (!track.previewUrl.isNullOrEmpty() && track.platform != "TELEGRAM") VISIBLE else GONE
        playPauseButton.background = Theme.createCircleDrawable(
            AndroidUtilities.dp(32f),
            data.accentColor ?: getThemedColor(Theme.key_featuredStickers_addButton)
        )

        val documentId = if (data.userEmoji > 0 && track.platform == "TELEGRAM") {
            data.userEmoji
        } else {
            ServiceEmoji.fromString(track.platform).documentId
        }
        if (documentId != currentDocId) {
            currentDocId = documentId
            emoji.set(documentId, true)
        }

        val hasBadge = BadgesController.hasBadge()
        cardLayout.setOnClickListener {
            if (track.platform == "TELEGRAM") {
                onSavedMusicClick()
                return@setOnClickListener
            }
            val fragment = LaunchActivity.getSafeLastFragment()
            if (hasBadge) {
                Browser.openUrl(cardLayout.context, data.nowPlayingDTO.songUrl)
            } else if (fragment != null) {
                SupporterBottomSheet.showAlert(fragment)
            }
        }
        cardLayout.setOnLongClickListener {
            if (track.platform == "TELEGRAM") {
                onSavedMusicClick()
            } else {
                Browser.openUrl(cardLayout.context, data.nowPlayingDTO.songUrl)
            }
            true
        }

        if (data.imageLocation != null) {
            val cover = data.coverBitmap
            imageView.setImage(data.imageLocation, null, cover?.let { BitmapDrawable(context.resources, it) }, 0, null)
            setTextColors(if (cover != null) Color.WHITE else getThemedColor(Theme.key_windowBackgroundWhiteBlackText))
        } else {
            imageView.setImageResource(R.drawable.nocover, getThemedColor(Theme.key_player_button))
            setTextColors(getThemedColor(Theme.key_windowBackgroundWhiteBlackText))
        }

        val previewUrl = data.nowPlayingDTO.previewUrl
        if (previewUrl != currentPreviewUrl) {
            currentPreviewUrl = previewUrl
            initializePlayer()
        }
        invalidate()
    }

    private fun setTextColors(color: Int) {
        artistView.setTextColor(color)
        nameView.setTextColor(color)
        albumView.setTextColor(color)
    }

    private fun initializePlayer() {
        releasePlayer()
        val previewUrl = nowPlayingCardData?.nowPlayingDTO?.previewUrl ?: return
        val exoPlayer = ExoPlayer.Builder(context).build()
        exoPlayer.setMediaSource(
            ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context))
                .createMediaSource(MediaItem.fromUri(Uri.parse(previewUrl)))
        )
        exoPlayer.prepare()
        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                updatePlayPauseButton()
                if (!playing) {
                    abandonAudioFocus()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    abandonAudioFocus()
                }
            }
        })
        player = exoPlayer
    }

    private fun togglePlayPause() {
        val exoPlayer = player ?: return
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
            abandonAudioFocus()
        } else if (requestAudioFocus()) {
            if (exoPlayer.playbackState == Player.STATE_ENDED) {
                exoPlayer.seekTo(0L)
            }
            exoPlayer.play()
        }
    }

    @Suppress("DEPRECATION")
    private fun requestAudioFocus(): Boolean {
        if (Build.VERSION.SDK_INT < 26) {
            return audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .build()
        audioFocusRequest = request
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @Suppress("DEPRECATION")
    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
                audioFocusRequest = null
            }
            return
        }
        audioManager.abandonAudioFocus(audioFocusChangeListener)
    }

    private fun updatePlayPauseButton() {
        playPauseDrawable.setPause(isPlaying)
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        isPlaying = false
        updatePlayPauseButton()
        abandonAudioFocus()
    }

    private fun getThemedColor(key: Int): Int = Theme.getColor(key, resourcesProvider)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        emoji.attach()
        if (player == null && nowPlayingCardData != null) {
            initializePlayer()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        emoji.detach()
        releasePlayer()
    }
}
