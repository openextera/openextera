package com.exteragram.messenger.preferences.chats;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.exteragram.messenger.CameraType;
import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.StickerTimeMode;
import com.exteragram.messenger.VideoMessagesCamera;
import com.exteragram.messenger.ai.AiController;
import com.exteragram.messenger.ai.ui.activities.AiPreferencesActivity;
import com.exteragram.messenger.camera.CameraXSession;
import com.exteragram.messenger.preferences.BasePreferencesActivity;
import com.exteragram.messenger.preferences.SwitchGroup;
import com.exteragram.messenger.preferences.chats.components.DoubleTapCell;
import com.exteragram.messenger.preferences.chats.components.MessagesPreviewCell;
import com.exteragram.messenger.preferences.chats.components.SliderPreviewCell;
import com.exteragram.messenger.preferences.chats.components.StickerShapeCell;
import com.exteragram.messenger.speech.VoiceRecognitionController;
import com.exteragram.messenger.speech.ui.RecognitionModelDialogs;
import com.exteragram.messenger.translator.TranslatorUtils;
import com.exteragram.messenger.utils.chats.DoubleTapUtils;
import com.exteragram.messenger.utils.chats.SwipeAction;
import com.exteragram.messenger.utils.ui.PopupUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.ColoredImageSpan;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Stories.recorder.DualCameraView;
import org.telegram.ui.ThemeActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class ChatsPreferencesActivity extends BasePreferencesActivity {

    private static final float DEFAULT_STICKER_SIZE = 12.0f;

    public enum ChatsItem {
        STICKER_SIZE,
        STICKER_TIME,
        REPLY_ELEMENTS,
        REPLY_COLORS,
        REPLY_EMOJI,
        REPLY_BACKGROUND,
        STICKER_SHAPE,
        AI,
        CHAT_SETTINGS,
        UNLIMITED_RECENT_STICKERS,
        HIDE_REACTIONS,
        DOUBLE_TAP,
        DOUBLE_TAP_ACTION,
        DOUBLE_TAP_ACTION_OUT_OWNER,
        SWIPE_ACTIONS,
        BOTTOM_BUTTON,
        WIDE_POSTS,
        WIDE_POSTS_FEED,
        WIDE_POSTS_CHANNELS,
        AI_FEATURES,
        AI_FEATURES_EDITOR,
        AI_FEATURES_SUMMARIES,
        AI_FEATURES_INSTANT_VIEW_SUMMARIES,
        ADMIN_SHORTCUTS,
        QUICK_TRANSITIONS,
        QUICK_TRANSITION_FOR_CHANNELS,
        QUICK_TRANSITION_FOR_TOPICS,
        DISABLE_GREETING_STICKER,
        HIDE_KEYBOARD_ON_SCROLL,
        ADD_COMMA_AFTER_MENTION,
        INLINE_MATH_RESULT,
        HIDE_SEND_AS_PEER,
        MESSAGES_PREVIEW,
        REMOVE_MESSAGE_TAIL,
        REPLACE_EDITED_WITH_ICON,
        SHOW_ONLINE_STATUS,
        HIDE_SHARE_BUTTON,
        SHOW_RESULTS_BEFORE_VOTING,
        MESSAGE_MENU,
        COPY_PHOTO,
        SAVE,
        REPEAT,
        CLEAR,
        HISTORY,
        REPORT,
        GENERATE,
        DETAILS,
        GROUP_MESSAGE_MENU,
        GROUPS,
        CHANNELS,
        PRIVATE_CHATS,
        SPEECH_RECOGNITION_LANGUAGE,
        POST_PROCESSING_WITH_AI,
        DELETE_RECOGNITION_MODEL,
        CAMERA_TYPE,
        CAMERA_SETTINGS,
        DUAL_CAMERA,
        EXTENDED_FRAMES_PER_SECOND,
        CAMERA_STABILIZATION,
        CAMERA_MIRROR_MODE,
        VIDEO_MESSAGES_CAMERA,
        REMEMBER_LAST_USED_CAMERA,
        START_WITH_WIDE_ANGLE_CAMERA,
        ZOOM_SLIDER,
        STATIC_ZOOM,
        ALWAYS_SEND_IN_HD,
        HIDE_CAMERA_TILE,
        DOUBLE_TAP_SEEK_DURATION,
        PREFER_ORIGINAL_QUALITY,
        SWIPE_TO_PIP,
        UNMUTE_WITH_VOLUME_BUTTONS,
        PAUSE_ON_MINIMIZE,
        PAUSE_ON_MINIMIZE_VIDEO,
        PAUSE_ON_MINIMIZE_VOICE,
        PAUSE_ON_MINIMIZE_ROUND,
        SHOW_FORWARDS_COUNT;

        public int getId() {
            return ordinal() + 1;
        }
    }

    private final List<String> languageCodes = Arrays.asList(
            "none", "en", "es", "zh", "hi", "fa", "fr", "ru", "pt", "de", "ja", "ko", "it",
            "uk", "gu", "pl", "nl", "tr", "vi", "cs", "uz", "eo", "kk", "tg", "ca"
    );

    private CharSequence[] stickerTimeModes;
    private CharSequence[] doubleTapActions;
    private CharSequence[] doubleTapOutActions;
    private CharSequence[] bottomButton;
    private CharSequence[] videoMessagesCamera;
    private CharSequence[] doubleTapSeekDuration;
    private CharSequence[] cameraType;
    private CharSequence[] recognitionLanguageOptions;

    private SliderPreviewCell stickerSizeCell;
    private StickerShapeCell stickerShapeCell;
    private DoubleTapCell doubleTapCell;
    private MessagesPreviewCell messagesPreviewCell;
    private ActionBarMenuItem resetItem;

    private final SwitchGroup replyElements = SwitchGroup.of(this, ChatsItem.REPLY_ELEMENTS.getId(), R.string.RepliesTitle)
            .searchable()
            .linkAlias("replyElements")
            .onChanged(this::updateReplySettings)
            .add(ChatsItem.REPLY_COLORS.getId(), R.string.BackgroundColors, ExteraConfig::getReplyColors, ExteraConfig::setReplyColors)
            .add(ChatsItem.REPLY_EMOJI.getId(), R.string.Emoji, ExteraConfig::getReplyEmoji, ExteraConfig::setReplyEmoji)
            .add(ChatsItem.REPLY_BACKGROUND.getId(), R.string.ReplyBackground, ExteraConfig::getReplyBackground, ExteraConfig::setReplyBackground);

    private final SwitchGroup hideReactions = SwitchGroup.of(this, ChatsItem.HIDE_REACTIONS.getId(), R.string.HideReactions)
            .searchable()
            .linkAlias("hideReactions")
            .onChanged(this::updateReplySettings)
            .add(ChatsItem.CHANNELS.getId(), R.string.ChannelsTab, ExteraConfig::getHideReactionsInChannels, ExteraConfig::setHideReactionsInChannels)
            .add(ChatsItem.GROUPS.getId(), R.string.SaveToGalleryGroups, ExteraConfig::getHideReactionsInGroups, ExteraConfig::setHideReactionsInGroups)
            .add(ChatsItem.PRIVATE_CHATS.getId(), R.string.PrivateChats, ExteraConfig::getHideReactionsInPrivateChats, ExteraConfig::setHideReactionsInPrivateChats);

    private final SwitchGroup widePosts = SwitchGroup.of(this, ChatsItem.WIDE_POSTS.getId(), R.string.WidePosts)
            .searchable()
            .linkAlias("widePosts")
            .onChanged(() -> parentLayout.rebuildFragments(AndroidUtilities.isTablet() ? INavigationLayout.REBUILD_FLAG_REBUILD_LAST : 0))
            .add(ChatsItem.WIDE_POSTS_FEED.getId(), R.string.Feed, ExteraConfig::getWidePostsInFeed, ExteraConfig::setWidePostsInFeed)
            .add(ChatsItem.WIDE_POSTS_CHANNELS.getId(), R.string.ChannelsTab, ExteraConfig::getWidePostsInChannels, ExteraConfig::setWidePostsInChannels);

    private final SwitchGroup aiFeatures = SwitchGroup.of(this, ChatsItem.AI_FEATURES.getId(), R.string.AIFeatures)
            .searchable()
            .linkAlias("aiFeatures")
            .onChanged(() -> parentLayout.rebuildFragments(0))
            .add(ChatsItem.AI_FEATURES_EDITOR.getId(), R.string.AIFeaturesEditor, ExteraConfig::getTelegramAiEditor, ExteraConfig::setTelegramAiEditor)
            .add(ChatsItem.AI_FEATURES_SUMMARIES.getId(), R.string.AIFeaturesSummaries, ExteraConfig::getTelegramAiSummaries, ExteraConfig::setTelegramAiSummaries)
            .add(ChatsItem.AI_FEATURES_INSTANT_VIEW_SUMMARIES.getId(), R.string.AIFeaturesInstantViewSummaries, ExteraConfig::getTelegramAiInstantViewSummaries, ExteraConfig::setTelegramAiInstantViewSummaries);

    private final SwitchGroup quickTransitions = SwitchGroup.of(this, ChatsItem.QUICK_TRANSITIONS.getId(), R.string.QuickTransitions)
            .searchable()
            .linkAlias("quickTransitions")
            .add(ChatsItem.QUICK_TRANSITION_FOR_CHANNELS.getId(), R.string.FilterChannels, ExteraConfig::getQuickTransitionForChannels, ExteraConfig::setQuickTransitionForChannels)
            .add(ChatsItem.QUICK_TRANSITION_FOR_TOPICS.getId(), R.string.Topics, ExteraConfig::getQuickTransitionForTopics, ExteraConfig::setQuickTransitionForTopics);

    private final SwitchGroup messageMenu = SwitchGroup.of(this, ChatsItem.MESSAGE_MENU.getId(), R.string.MessageMenu)
            .searchable()
            .linkAlias("messageMenu")
            .onChanged(() -> parentLayout.rebuildFragments(0))
            .add(ChatsItem.COPY_PHOTO.getId(), R.string.CopyPhoto, ExteraConfig::getShowCopyPhotoButton, ExteraConfig::setShowCopyPhotoButton)
            .add(ChatsItem.SAVE.getId(), R.string.Save, ExteraConfig::getShowSaveMessageButton, ExteraConfig::setShowSaveMessageButton)
            .add(ChatsItem.REPEAT.getId(), R.string.Repeat, ExteraConfig::getShowRepeatMessageButton, ExteraConfig::setShowRepeatMessageButton)
            .add(ChatsItem.CLEAR.getId(), R.string.Clear, ExteraConfig::getShowClearButton, ExteraConfig::setShowClearButton)
            .add(ChatsItem.HISTORY.getId(), R.string.MessageHistory, ExteraConfig::getShowHistoryButton, ExteraConfig::setShowHistoryButton)
            .add(ChatsItem.REPORT.getId(), R.string.ReportChat, ExteraConfig::getShowReportButton, ExteraConfig::setShowReportButton)
            .addIf(AiController::canUseAI, ChatsItem.GENERATE.getId(), R.string.Generate, ExteraConfig::getShowGenerateButton, ExteraConfig::setShowGenerateButton)
            .add(ChatsItem.DETAILS.getId(), R.string.Details, ExteraConfig::getShowDetailsButton, ExteraConfig::setShowDetailsButton);

    private final SwitchGroup cameraSettings = SwitchGroup.of(this, ChatsItem.CAMERA_SETTINGS.getId(), R.string.ExtendedSettings)
            .searchable()
            .linkAlias("cameraSettings")
            .addIf(this::isSeamlessSwitchingAvailable, ChatsItem.DUAL_CAMERA.getId(), R.string.SeamlessSwitching,
                    () -> DualCameraView.roundDualAvailableStatic(getContext()),
                    value -> MessagesController.getGlobalMainSettings().edit().putBoolean("rounddual_available", value).apply())
            .add(ChatsItem.EXTENDED_FRAMES_PER_SECOND.getId(), R.string.ExtendedFramesPerSecond, ExteraConfig::getExtendedFramesPerSecond, ExteraConfig::setExtendedFramesPerSecond)
            .add(ChatsItem.CAMERA_STABILIZATION.getId(), R.string.CameraStabilization, ExteraConfig::getCameraStabilization, ExteraConfig::setCameraStabilization)
            .addIf(() -> ExteraConfig.getCameraType() != CameraType.CAMERA_2, ChatsItem.CAMERA_MIRROR_MODE.getId(), R.string.CameraMirrorMode, ExteraConfig::getCameraMirrorMode, ExteraConfig::setCameraMirrorMode)
            .addIf(() -> ExteraConfig.getCameraType() == CameraType.CAMERA_X, ChatsItem.START_WITH_WIDE_ANGLE_CAMERA.getId(), R.string.StartWithWideAngleCamera, ExteraConfig::getStartWithWideAngleCamera, ExteraConfig::setStartWithWideAngleCamera)
            .markNew("Camera-ExtendedSettings-StartWithWideAngle");

    private final SwitchGroup pauseOnMinimize = SwitchGroup.of(this, ChatsItem.PAUSE_ON_MINIMIZE.getId(), R.string.PauseOnMinimize)
            .searchable()
            .linkAlias("pauseOnMinimize")
            .add(ChatsItem.PAUSE_ON_MINIMIZE_VIDEO.getId(), R.string.PauseOnMinimizeVideo, ExteraConfig::getPauseOnMinimizeVideo, ExteraConfig::setPauseOnMinimizeVideo)
            .add(ChatsItem.PAUSE_ON_MINIMIZE_VOICE.getId(), R.string.PauseOnMinimizeVoice, ExteraConfig::getPauseOnMinimizeVoice, ExteraConfig::setPauseOnMinimizeVoice)
            .add(ChatsItem.PAUSE_ON_MINIMIZE_ROUND.getId(), R.string.PauseOnMinimizeRound, ExteraConfig::getPauseOnMinimizeRound, ExteraConfig::setPauseOnMinimizeRound);

    @Override
    public void initializeOptionStrings() {
        stickerTimeModes = new CharSequence[]{
                LocaleController.getString(R.string.Default),
                LocaleController.getString(R.string.StickerTimeSide),
                LocaleController.getString(R.string.StickerTimeHidden)
        };
        doubleTapActions = DoubleTapUtils.getDoubleTapActions(false);
        doubleTapOutActions = DoubleTapUtils.getDoubleTapActions(true);
        bottomButton = new CharSequence[]{
                LocaleController.getString(R.string.Hide),
                LocaleController.getString(R.string.ChannelMuteNoCaps),
                LocaleController.getString(R.string.ChannelDiscussNoCaps)
        };
        videoMessagesCamera = new CharSequence[]{
                LocaleController.getString(R.string.VideoMessagesCameraFront),
                LocaleController.getString(R.string.VideoMessagesCameraRear),
                LocaleController.getString(R.string.VideoMessagesCameraAsk)
        };
        doubleTapSeekDuration = new CharSequence[]{
                LocaleController.formatPluralString("Seconds", 5),
                LocaleController.formatPluralString("Seconds", 10),
                LocaleController.formatPluralString("Seconds", 15),
                LocaleController.formatPluralString("Seconds", 30)
        };
        cameraType = new CharSequence[]{"Camera 1", "Camera 2", "Camera X"};
        recognitionLanguageOptions = languageCodes.stream()
                .map(RecognitionModelDialogs::getRecognitionLanguageOption)
                .toArray(CharSequence[]::new);
    }

    @Override
    public View createView(Context context) {
        stickerSizeCell = new SliderPreviewCell(parentLayout, context, ChatsItem.STICKER_SIZE.getId(), 4, 20, ExteraConfig.getStickerSize(),
                LocaleController.getString(R.string.StickerSize),
                LocaleController.getString(R.string.StickerSizeLeft),
                LocaleController.getString(R.string.StickerSizeRight),
                false
        ).setListener(value -> {
            ExteraConfig.setStickerSize(value);
            if (resetItem != null && resetItem.getVisibility() != View.VISIBLE) {
                AndroidUtilities.updateViewVisibilityAnimated(resetItem, true, 0.5f, true);
            }
        });
        stickerShapeCell = new StickerShapeCell(context) {
            @Override
            public void updateStickerPreview() {
                parentLayout.rebuildFragments(0);
                if (stickerSizeCell != null) {
                    stickerSizeCell.invalidate();
                }
            }
        };
        doubleTapCell = new DoubleTapCell(context);
        messagesPreviewCell = new MessagesPreviewCell(context, parentLayout, MessagesPreviewCell.TYPE_MESSAGE);

        View view = super.createView(context);

        resetItem = actionBar.createMenu().addItem(0, R.drawable.msg_reset);
        resetItem.setContentDescription(LocaleController.getString(R.string.Reset));
        resetItem.setVisibility(ExteraConfig.getStickerSize() == DEFAULT_STICKER_SIZE ? View.GONE : View.VISIBLE);
        resetItem.setTag(null);
        resetItem.setOnClickListener(v -> {
            AndroidUtilities.updateViewVisibilityAnimated(resetItem, false, 0.5f, true);
            ValueAnimator animator = ValueAnimator.ofFloat(ExteraConfig.getStickerSize(), DEFAULT_STICKER_SIZE);
            animator.setDuration(200);
            animator.addUpdateListener(a -> {
                float value = (float) a.getAnimatedValue();
                ExteraConfig.setStickerSize(value);
                if (stickerSizeCell != null && stickerSizeCell.seekBar != null) {
                    stickerSizeCell.seekBar.setProgress(value);
                }
                if (stickerSizeCell != null) {
                    stickerSizeCell.invalidate();
                }
            });
            animator.start();
        });

        fragmentView = view;
        return view;
    }

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.SearchAllChatsShort);
    }

    private CharSequence swipeActionsValue() {
        List<SwipeAction> enabled = SwipeAction.enabled();
        if (enabled.isEmpty()) {
            return LocaleController.getString(R.string.Disable);
        }
        SpannableStringBuilder builder = new SpannableStringBuilder();
        int count = Math.min(enabled.size(), 5);
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(' ');
            }
            Drawable drawable = ContextCompat.getDrawable(getContext(), enabled.get(i).iconRes);
            if (drawable != null) {
                int start = builder.length();
                builder.append('*');
                ColoredImageSpan span = new ColoredImageSpan(drawable.mutate());
                span.setSize(AndroidUtilities.dp(18));
                builder.setSpan(span, start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        if (enabled.size() > count) {
            builder.append(" +").append(String.valueOf(enabled.size() - count));
        }
        return builder;
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asCustom(ChatsItem.STICKER_SIZE.getId(), stickerSizeCell).setLinkAlias("stickerSize", this));
        items.add(UItem.asButton(ChatsItem.STICKER_TIME.getId(), LocaleController.getString(R.string.StickerTimeMode), stickerTimeModes[ExteraConfig.getStickerTimeMode().ordinal()]).setSearchable(this).setLinkAlias("stickerTime", this));
        replyElements.fill(items);
        items.add(UItem.asShadow());

        items.add(UItem.asHeader(LocaleController.getString(R.string.StickerShape)));
        items.add(UItem.asCustom(ChatsItem.STICKER_SHAPE.getId(), stickerShapeCell).setLinkAlias("stickerShape", this));
        items.add(UItem.asShadow());

        items.add(UItem.asButtonWithSubtext(ChatsItem.AI.getId(), R.drawable.ai_chat, LocaleController.getString(R.string.AIChat), LocaleController.getString(R.string.AIChatInfo), 64, 60).setSearchable(this).setLinkAlias("aiChat", this));
        items.add(UItem.asButtonWithSubtext(ChatsItem.CHAT_SETTINGS.getId(), R.drawable.msg_discussion, LocaleController.getString(R.string.ChatSettings), LocaleController.getString(R.string.ChatSettingsInfo), 64, 60).setLinkAlias("chatSettings", this));
        items.add(UItem.asShadow());

        items.add(UItem.asHeader(LocaleController.getString(R.string.StickersName)));
        items.add(UItem.asCheck(ChatsItem.UNLIMITED_RECENT_STICKERS.getId(), LocaleController.getString(R.string.UnlimitedRecentStickers)).setChecked(ExteraConfig.getUnlimitedRecentStickers()).setSearchable(this).setLinkAlias("unlimitedRecentStickers", this));
        hideReactions.fill(items);
        items.add(UItem.asShadow(LocaleController.getString(R.string.HideReactionsInfo)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.Gestures)));
        items.add(UItem.asCustom(ChatsItem.DOUBLE_TAP.getId(), doubleTapCell));
        items.add(UItem.asButton(ChatsItem.DOUBLE_TAP_ACTION.getId(), LocaleController.getString(R.string.DoubleTapIncoming), DoubleTapUtils.getDoubleTapActionLabel(ExteraConfig.getDoubleTapAction(), false)).setSearchable(this).setLinkAlias("doubleTapIncoming", this));
        items.add(UItem.asButton(ChatsItem.DOUBLE_TAP_ACTION_OUT_OWNER.getId(), LocaleController.getString(R.string.DoubleTapOutgoing), DoubleTapUtils.getDoubleTapActionLabel(ExteraConfig.getDoubleTapActionOutOwner(), true)).setSearchable(this).setLinkAlias("doubleTapOutgoing", this));
        items.add(UItem.asButton(ChatsItem.SWIPE_ACTIONS.getId(), LocaleController.getString(R.string.SwipeActions), swipeActionsValue()).setSearchable(this).setLinkAlias("swipeActions", this));
        items.add(UItem.asShadow(LocaleController.getString(R.string.DoubleTapInfo)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.MainTabsChats)));
        items.add(UItem.asButton(ChatsItem.BOTTOM_BUTTON.getId(), LocaleController.getString(R.string.BottomButton), bottomButton[ExteraConfig.getBottomButton()]).setSearchable(this).setLinkAlias("bottomButton", this));
        widePosts.fill(items);
        aiFeatures.fill(items);
        items.add(UItem.asCheck(ChatsItem.ADMIN_SHORTCUTS.getId(), LocaleController.getString(R.string.AdminShortcuts)).setChecked(ExteraConfig.getQuickAdminShortcuts()).setSearchable(this).setLinkAlias("adminShortcuts", this));
        quickTransitions.fill(items);
        items.add(UItem.asCheck(ChatsItem.DISABLE_GREETING_STICKER.getId(), LocaleController.getString(R.string.DisableGreetingSticker)).setChecked(ExteraConfig.getDisableGreetingSticker()).setSearchable(this).setLinkAlias("disableGreetingSticker", this));
        items.add(UItem.asCheck(ChatsItem.HIDE_KEYBOARD_ON_SCROLL.getId(), LocaleController.getString(R.string.HideKeyboardOnScroll)).setChecked(ExteraConfig.getHideKeyboardOnScroll()).setSearchable(this).setLinkAlias("hideKeyboardOnScroll", this));
        items.add(UItem.asCheck(ChatsItem.ADD_COMMA_AFTER_MENTION.getId(), LocaleController.getString(R.string.AddCommaAfterMention)).setChecked(ExteraConfig.getAddCommaAfterMention()).setSearchable(this).setLinkAlias("addCommaAfterMention", this));
        items.add(UItem.asCheck(ChatsItem.INLINE_MATH_RESULT.getId(), LocaleController.getString(R.string.InlineMathResult), LocaleController.getString(R.string.InlineMathResultHint), true).setChecked(ExteraConfig.getInlineMathResult()).setSearchable(this).setLinkAlias("inlineMathResult", this));
        items.add(UItem.asCheck(ChatsItem.HIDE_SEND_AS_PEER.getId(), LocaleController.getString(R.string.HideSendAsPeer)).setChecked(ExteraConfig.getHideSendAsPeer()).setSearchable(this).setLinkAlias("hideSendAsPeer", this));
        items.add(UItem.asShadow(LocaleController.getString(R.string.HideSendAsPeerInfo)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.MessagesChartTitle)));
        items.add(UItem.asCustom(ChatsItem.MESSAGES_PREVIEW.getId(), messagesPreviewCell));
        items.add(UItem.asCheck(ChatsItem.REMOVE_MESSAGE_TAIL.getId(), LocaleController.getString(R.string.RemoveMessageTail)).setChecked(ExteraConfig.getRemoveMessageTail()).setSearchable(this).setLinkAlias("removeMessageTail", this));
        items.add(UItem.asCheck(ChatsItem.REPLACE_EDITED_WITH_ICON.getId(), LocaleController.formatString(R.string.ReplaceEditedWithIcon, LocaleController.getString(R.string.EditedMessage))).setChecked(ExteraConfig.getReplaceEditedWithIcon()).setSearchable(this).setLinkAlias("replaceEditedWithIcon", this));
        items.add(UItem.asCheck(ChatsItem.SHOW_ONLINE_STATUS.getId(), LocaleController.getString(R.string.ShowOnlineStatus)).setChecked(ExteraConfig.getShowOnlineStatus()).setSearchable(this).setLinkAlias("showOnlineStatus", this));
        items.add(UItem.asCheck(ChatsItem.SHOW_FORWARDS_COUNT.getId(), LocaleController.getString(R.string.ShowForwardsCount)).setChecked(ExteraConfig.getShowForwardsCount()).setSearchable(this).setLinkAlias("showForwardsCount", this));
        items.add(UItem.asCheck(ChatsItem.HIDE_SHARE_BUTTON.getId(), LocaleController.formatString(R.string.HideShareButton, LocaleController.getString(R.string.ShareFile))).setChecked(ExteraConfig.getHideShareButton()).setSearchable(this).setLinkAlias("hideShareButton", this));
        items.add(UItem.asCheck(ChatsItem.SHOW_RESULTS_BEFORE_VOTING.getId(), LocaleController.getString(R.string.ShowPollResultsBeforeVoting), LocaleController.getString(R.string.ShowPollResultsBeforeVotingHint), true).setChecked(ExteraConfig.getShowResultsBeforeVoting()).setSearchable(this).setLinkAlias("showResultsBeforeVoting", this));
        messageMenu.fill(items);
        items.add(UItem.asCheck(ChatsItem.GROUP_MESSAGE_MENU.getId(), LocaleController.getString(R.string.GroupMessageMenu)).setChecked(ExteraConfig.getGroupMessageMenu()).setSearchable(this).setLinkAlias("groupMessageMenu", this));
        items.add(UItem.asShadow(LocaleController.getString(R.string.GroupMessageMenuInfo)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.PremiumPreviewVoiceToText)));
        items.add(UItem.asButton(ChatsItem.SPEECH_RECOGNITION_LANGUAGE.getId(), LocaleController.getString(R.string.RecognitionLanguage), TranslatorUtils.getLanguageTitleSystem(ExteraConfig.getRecognitionLanguage())).setSearchable(this).setLinkAlias("recognitionLanguage", this));
        if (AiController.canUseAI() && VoiceRecognitionController.isCustomRecognitionEnabled()) {
            items.add(UItem.asCheck(ChatsItem.POST_PROCESSING_WITH_AI.getId(), LocaleController.getString(R.string.PostProcessingWithAi), LocaleController.getString(R.string.PostProcessingWithAiInfo), true).setChecked(ExteraConfig.getPostprocessingWithAi()).setSearchable(this).setLinkAlias("postprocessingWithAi", this));
        }
        if (!getDownloadedRecognitionModels().isEmpty()) {
            items.add(UItem.asButton(ChatsItem.DELETE_RECOGNITION_MODEL.getId(), R.drawable.msg_delete, LocaleController.getString(R.string.DeleteRecognitionModel)).red().setSearchable(this).setLinkAlias("deleteRecognitionModel", this));
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.RecognitionInfo)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.VoipCamera)));
        items.add(UItem.asButton(ChatsItem.CAMERA_TYPE.getId(), LocaleController.getString(R.string.CameraType), cameraType[ExteraConfig.getCameraType().ordinal()]).setSearchable(this).setLinkAlias("cameraType", this));
        if (ExteraConfig.getCameraType() != CameraType.CAMERA_1) {
            cameraSettings.fill(items);
            if (!isSeamlessSwitchingAvailable()) {
                MessagesController.getGlobalMainSettings().edit().putBoolean("rounddual_available", false).apply();
            }
        }
        items.add(UItem.asButton(ChatsItem.VIDEO_MESSAGES_CAMERA.getId(), LocaleController.getString(R.string.VideoMessagesCamera), videoMessagesCamera[ExteraConfig.getVideoMessagesCamera().ordinal()]).setSearchable(this).setLinkAlias("videoMessagesCamera", this));
        if (ExteraConfig.getVideoMessagesCamera() != VideoMessagesCamera.ASK) {
            items.add(UItem.asCheck(ChatsItem.REMEMBER_LAST_USED_CAMERA.getId(), LocaleController.getString(R.string.RememberLastUsedCamera), LocaleController.getString(R.string.RememberLastUsedCameraInfo), true).setChecked(ExteraConfig.getRememberLastUsedCamera()).setSearchable(this).setLinkAlias("rememberLastUsedCamera", this));
        }
        items.add(UItem.asCheck(ChatsItem.ZOOM_SLIDER.getId(), LocaleController.getString(R.string.ZoomSlider), LocaleController.getString(R.string.ZoomSliderInfo), true).setChecked(ExteraConfig.getZoomSlider()).setSearchable(this).setLinkAlias("zoomSlider", this));
        items.add(UItem.asCheck(ChatsItem.STATIC_ZOOM.getId(), LocaleController.getString(R.string.StaticZoom)).setChecked(ExteraConfig.getStaticZoom()).setSearchable(this).setLinkAlias("staticZoom", this));
        items.add(UItem.asShadow(LocaleController.getString(R.string.StaticZoomInfo)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.AutoDownloadPhotos)));
        items.add(UItem.asCheck(ChatsItem.ALWAYS_SEND_IN_HD.getId(), LocaleController.getString(R.string.AlwaysSendInHD)).setChecked(ExteraConfig.getAlwaysSendInHD()).setSearchable(this).setLinkAlias("alwaysSendInHD", this));
        items.add(UItem.asCheck(ChatsItem.HIDE_CAMERA_TILE.getId(), LocaleController.getString(R.string.HideCameraTile)).setChecked(ExteraConfig.getHideCameraTile()).setSearchable(this).setLinkAlias("hideCameraTile", this));
        items.add(UItem.asShadow(LocaleController.getString(R.string.HideCameraTileInfo)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.AutoDownloadVideos)));
        items.add(UItem.asButton(ChatsItem.DOUBLE_TAP_SEEK_DURATION.getId(), LocaleController.getString(R.string.DoubleTapSeekDuration), doubleTapSeekDuration[ExteraConfig.getDoubleTapSeekDuration()]).setSearchable(this).setLinkAlias("doubleTapSeekDuration", this));
        items.add(UItem.asCheck(ChatsItem.PREFER_ORIGINAL_QUALITY.getId(), LocaleController.getString(R.string.PreferOriginalQuality)).setChecked(ExteraConfig.getPreferOriginalQuality()).setSearchable(this).setLinkAlias("preferOriginalQuality", this));
        items.add(UItem.asCheck(ChatsItem.SWIPE_TO_PIP.getId(), LocaleController.getString(R.string.SwipeToPip)).setChecked(ExteraConfig.getSwipeToPip()).setSearchable(this).setLinkAlias("swipeToPip", this));
        items.add(UItem.asCheck(ChatsItem.UNMUTE_WITH_VOLUME_BUTTONS.getId(), LocaleController.getString(R.string.UnmuteWithVolumeButtons), LocaleController.getString(R.string.UnmuteWithVolumeButtonsInfo), true).setChecked(ExteraConfig.getUnmuteWithVolumeButtons()).setSearchable(this).setLinkAlias("unmuteWithVolumeButtons", this));
        pauseOnMinimize.fill(items);
        items.add(UItem.asShadow(LocaleController.getString(R.string.PauseOnMinimizeInfo)));
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id <= 0 || item.id > ChatsItem.values().length) {
            return;
        }
        switch (ChatsItem.values()[item.id - 1]) {
            case STICKER_TIME:
                showListDialog(item, stickerTimeModes, LocaleController.getString(R.string.StickerTimeMode), ExteraConfig.getStickerTimeMode().ordinal(), which -> {
                    ExteraConfig.setStickerTimeMode(StickerTimeMode.getEntries().get(which));
                    if (stickerSizeCell != null) {
                        stickerSizeCell.invalidate();
                    }
                    parentLayout.rebuildFragments(0);
                });
                break;
            case REPLY_ELEMENTS:
            case REPLY_COLORS:
            case REPLY_EMOJI:
            case REPLY_BACKGROUND:
                replyElements.onClick(item);
                break;
            case AI:
                presentFragment(new AiPreferencesActivity());
                break;
            case CHAT_SETTINGS:
                presentFragment(new ThemeActivity(ThemeActivity.THEME_TYPE_BASIC));
                break;
            case UNLIMITED_RECENT_STICKERS:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setUnlimitedRecentStickers);
                break;
            case HIDE_REACTIONS:
            case CHANNELS:
            case GROUPS:
            case PRIVATE_CHATS:
                hideReactions.onClick(item);
                break;
            case DOUBLE_TAP_ACTION:
                showListDialog(item, doubleTapActions, DoubleTapUtils.getDoubleTapIcons(false), LocaleController.getString(R.string.DoubleTapIncoming), ExteraConfig.getDoubleTapAction(), which -> {
                    ExteraConfig.setDoubleTapAction(which);
                    handleDoubleTapActionButtonClick(false);
                });
                break;
            case DOUBLE_TAP_ACTION_OUT_OWNER:
                showListDialog(item, doubleTapOutActions, DoubleTapUtils.getDoubleTapIcons(true), LocaleController.getString(R.string.DoubleTapOutgoing), ExteraConfig.getDoubleTapActionOutOwner(), which -> {
                    ExteraConfig.setDoubleTapActionOutOwner(which);
                    handleDoubleTapActionButtonClick(true);
                });
                break;
            case SWIPE_ACTIONS:
                presentFragment(new SwipeActionsPreferencesActivity());
                break;
            case BOTTOM_BUTTON:
                showListDialog(item, bottomButton, LocaleController.getString(R.string.BottomButton), ExteraConfig.getBottomButton(), which -> {
                    ExteraConfig.setBottomButton(which);
                    parentLayout.rebuildFragments(0);
                });
                break;
            case WIDE_POSTS:
            case WIDE_POSTS_FEED:
            case WIDE_POSTS_CHANNELS:
                widePosts.onClick(item);
                break;
            case AI_FEATURES:
            case AI_FEATURES_EDITOR:
            case AI_FEATURES_SUMMARIES:
            case AI_FEATURES_INSTANT_VIEW_SUMMARIES:
                aiFeatures.onClick(item);
                break;
            case ADMIN_SHORTCUTS:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setQuickAdminShortcuts);
                break;
            case QUICK_TRANSITIONS:
            case QUICK_TRANSITION_FOR_CHANNELS:
            case QUICK_TRANSITION_FOR_TOPICS:
                quickTransitions.onClick(item);
                break;
            case DISABLE_GREETING_STICKER:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setDisableGreetingSticker);
                break;
            case HIDE_KEYBOARD_ON_SCROLL:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setHideKeyboardOnScroll);
                break;
            case ADD_COMMA_AFTER_MENTION:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setAddCommaAfterMention);
                break;
            case INLINE_MATH_RESULT:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setInlineMathResult);
                break;
            case HIDE_SEND_AS_PEER:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setHideSendAsPeer);
                break;
            case REPLACE_EDITED_WITH_ICON:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setReplaceEditedWithIcon);
                if (messagesPreviewCell != null) {
                    messagesPreviewCell.refreshMessages();
                }
                break;
            case SHOW_ONLINE_STATUS:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setShowOnlineStatus);
                if (messagesPreviewCell != null) {
                    messagesPreviewCell.refreshMessages();
                }
                break;
            case SHOW_FORWARDS_COUNT:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setShowForwardsCount);
                if (messagesPreviewCell != null) {
                    messagesPreviewCell.refreshMessages();
                }
                parentLayout.rebuildFragments(AndroidUtilities.isTablet() ? INavigationLayout.REBUILD_FLAG_REBUILD_LAST : 0);
                break;
            case REMOVE_MESSAGE_TAIL:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setRemoveMessageTail);
                Theme.chat_msgInDrawable = null;
                Theme.createChatResources(getParentActivity(), false);
                if (messagesPreviewCell != null) {
                    messagesPreviewCell.refreshMessages();
                }
                break;
            case SHOW_RESULTS_BEFORE_VOTING:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setShowResultsBeforeVoting);
                parentLayout.rebuildFragments(0);
                break;
            case HIDE_SHARE_BUTTON:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setHideShareButton);
                if (messagesPreviewCell != null) {
                    messagesPreviewCell.refreshMessages();
                }
                break;
            case MESSAGE_MENU:
            case COPY_PHOTO:
            case SAVE:
            case REPEAT:
            case CLEAR:
            case HISTORY:
            case REPORT:
            case GENERATE:
            case DETAILS:
                messageMenu.onClick(item);
                break;
            case GROUP_MESSAGE_MENU:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setGroupMessageMenu);
                break;
            case SPEECH_RECOGNITION_LANGUAGE:
                handleSpeechRecognitionLanguageClick(item);
                break;
            case POST_PROCESSING_WITH_AI:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setPostprocessingWithAi);
                break;
            case DELETE_RECOGNITION_MODEL:
                handleDeleteRecognitionModelClick();
                break;
            case CAMERA_TYPE:
                showListDialog(item, cameraType, LocaleController.getString(R.string.CameraType), ExteraConfig.getCameraType().ordinal(), which -> ExteraConfig.setCameraType(CameraType.getEntries().get(which)));
                break;
            case CAMERA_SETTINGS:
            case DUAL_CAMERA:
            case EXTENDED_FRAMES_PER_SECOND:
            case CAMERA_STABILIZATION:
            case CAMERA_MIRROR_MODE:
            case START_WITH_WIDE_ANGLE_CAMERA:
                cameraSettings.onClick(item);
                break;
            case VIDEO_MESSAGES_CAMERA:
                showListDialog(item, videoMessagesCamera, LocaleController.getString(R.string.VideoMessagesCamera), ExteraConfig.getVideoMessagesCamera().ordinal(), which -> ExteraConfig.setVideoMessagesCamera(VideoMessagesCamera.getEntries().get(which)));
                break;
            case REMEMBER_LAST_USED_CAMERA:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setRememberLastUsedCamera);
                break;
            case ZOOM_SLIDER:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setZoomSlider);
                break;
            case STATIC_ZOOM:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setStaticZoom);
                break;
            case ALWAYS_SEND_IN_HD:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setAlwaysSendInHD);
                break;
            case HIDE_CAMERA_TILE:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setHideCameraTile);
                break;
            case DOUBLE_TAP_SEEK_DURATION:
                showListDialog(item, doubleTapSeekDuration, LocaleController.getString(R.string.DoubleTapSeekDuration), ExteraConfig.getDoubleTapSeekDuration(), ExteraConfig::setDoubleTapSeekDuration);
                break;
            case PREFER_ORIGINAL_QUALITY:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setPreferOriginalQuality);
                break;
            case SWIPE_TO_PIP:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setSwipeToPip);
                break;
            case UNMUTE_WITH_VOLUME_BUTTONS:
                toggleBooleanSettingAndRefresh(item, ExteraConfig::setUnmuteWithVolumeButtons);
                break;
            case PAUSE_ON_MINIMIZE:
            case PAUSE_ON_MINIMIZE_VIDEO:
            case PAUSE_ON_MINIMIZE_VOICE:
            case PAUSE_ON_MINIMIZE_ROUND:
                pauseOnMinimize.onClick(item);
                break;
            default:
                break;
        }
    }

    private void updateReplySettings() {
        if (stickerSizeCell != null) {
            stickerSizeCell.invalidate();
        }
        parentLayout.rebuildFragments(0);
    }

    private void handleDoubleTapActionButtonClick(boolean outgoing) {
        if (doubleTapCell == null) {
            return;
        }
        doubleTapCell.updateIcons(outgoing ? 2 : 1, true);
        doubleTapCell.invalidate();
    }

    private boolean isSeamlessSwitchingAvailable() {
        if (ExteraConfig.getCameraType() == CameraType.CAMERA_X && CameraXSession.isSeamlessSwitchingAvailable(getContext())) {
            return true;
        }
        return ExteraConfig.getCameraType() == CameraType.CAMERA_2 && DualCameraView.dualAvailableStatic(getContext());
    }

    private void handleSpeechRecognitionLanguageClick(UItem item) {
        PopupUtils.showDialog(recognitionLanguageOptions, null, LocaleController.getString(R.string.RecognitionLanguage), languageCodes.indexOf(ExteraConfig.getRecognitionLanguage()), getContext(), which -> {
            String language = languageCodes.get(which);
            if (Objects.equals(ExteraConfig.getRecognitionLanguage(), language)) {
                return;
            }
            Runnable apply = () -> {
                ExteraConfig.setRecognitionLanguage(language);
                View view = listView.findViewByItemId(item.id);
                if (view instanceof TextCell) {
                    ((TextCell) view).setValue(TranslatorUtils.getLanguageTitleSystem(language), true);
                }
                listView.adapter.update(true);
            };
            if (!Objects.equals(language, "none") && getDownloadedRecognitionModels().stream().noneMatch(model -> Objects.equals(model.getLanguage(), language))) {
                VoiceRecognitionController.RecognitionModel model = VoiceRecognitionController.getInstance().listAvailableModels("vosk").stream()
                        .filter(m -> m.getLanguage().equals(language))
                        .findFirst()
                        .orElse(null);
                if (model == null) {
                    return;
                }
                RecognitionModelDialogs.showDownloadDialog(this, language, model, apply);
                return;
            }
            apply.run();
        });
    }

    private List<VoiceRecognitionController.RecognitionModel> getDownloadedRecognitionModels() {
        return VoiceRecognitionController.getInstance().listDownloadedModels("vosk");
    }

    private void handleDeleteRecognitionModelClick() {
        RecognitionModelDialogs.showDeleteFlow(this, getDownloadedRecognitionModels(), model -> {
            if (Objects.equals(ExteraConfig.getRecognitionLanguage(), model.getLanguage())) {
                ExteraConfig.setRecognitionLanguage("none");
            }
            listView.adapter.update(true);
        });
    }
}
