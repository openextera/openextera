package com.exteragram.messenger.utils.ui;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.debug.DebugConfig;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.ChatActivityTopPanelLayout;
import org.telegram.ui.Components.ChatAvatarContainer;
import org.telegram.ui.Components.chat.ViewPositionWatcher;
import org.telegram.ui.Components.chat.layouts.ChatActivityFadeView;

public abstract class ChatHeaderUiHelper {

    public static boolean isMaterial3ChatHeaderStyle() {
        return ExteraConfig.getNewChatHeaderStyle();
    }

    public static int getChatAvatarSizeDp() {
        return isMaterial3ChatHeaderStyle() ? 46 : 42;
    }

    public static int getAvatarInsetPx() {
        return isMaterial3ChatHeaderStyle() ? 0 : 1;
    }

    public static float getGlassPillExtraRightPaddingDp() {
        return isMaterial3ChatHeaderStyle() ? 0f : 6f;
    }

    public static int getAvatarSizePx(int sizeDp) {
        return AndroidUtilities.dp(sizeDp) - getAvatarInsetPx() * 2;
    }

    public static int getAvatarRadius(int sizeDp, boolean forum) {
        if (isMaterial3ChatHeaderStyle()) {
            return ExteraConfig.getAvatarCorners(sizeDp, false, forum);
        }
        return ExteraConfig.getAvatarCorners(getAvatarSizePx(sizeDp), true, forum);
    }

    public static int getChatFadeColorKey() {
        return DebugConfig.getChatFadeUseWhiteBackground() ? Theme.key_windowBackgroundWhite : Theme.key_windowBackgroundGray;
    }

    public static void setupGlassAvatarContainer(ChatAvatarContainer avatarContainer) {
        avatarContainer.setGlassMode();
        if (isMaterial3ChatHeaderStyle()) {
            avatarContainer.setAvatarSizeInDp(46);
        }
    }

    public static void applyChatHeaderGlassStyle(ActionBar actionBar) {
        applyChatHeaderGlassStyle(actionBar, isMaterial3ChatHeaderStyle());
    }

    public static void applyChatHeaderGlassStyle(ActionBar actionBar, boolean material3) {
        if (material3) {
            actionBar.setDrawGlassMiddlePill(false);
            actionBar.setGlassShadowAlpha(0f);
        }
    }

    public static void setupChatTopFade(ChatActivityFadeView fadeView, ActionBar actionBar, int topFadeColor, int fadeZone) {
        setupChatTopFade(fadeView, actionBar, topFadeColor, fadeZone, isMaterial3ChatHeaderStyle());
    }

    public static void setupChatTopFade(ChatActivityFadeView fadeView, ActionBar actionBar, int topFadeColor, int fadeZone, boolean material3) {
        fadeView.setFadeTopAlpha(actionBar.getVisibility() == View.VISIBLE ? 255 : 0);
        int topFadeZone = getChatTopFadeZone(fadeZone, material3);
        if (material3) {
            fadeView.setFadeZoneTop(getScaledChatTopFadeZone(actionBar, topFadeZone, true));
            fadeView.setTopFadeColor(topFadeColor);
        } else {
            fadeView.setFadeZoneTop(topFadeZone);
        }
        fadeView.setFadeHeightTop(getChatTopFadeHeight(material3));
    }

    public static boolean scrimReachesTopFade(ChatActivityFadeView fadeView, ViewGroup listView, View scrimView, MessageObject.GroupedMessages scrimGroup, float scrimTop) {
        float fadeZoneTop = fadeView.getFadeZoneTop();
        if (scrimTop >= fadeZoneTop) {
            return false;
        }
        for (int i = 0, count = listView.getChildCount(); i < count; i++) {
            View child = listView.getChildAt(i);
            ChatMessageCell cell = child instanceof ChatMessageCell ? (ChatMessageCell) child : null;
            if (child != scrimView && (scrimGroup == null || cell == null || cell.getCurrentMessagesGroup() != scrimGroup)) {
                continue;
            }
            if (child.getAlpha() == 0f) {
                continue;
            }
            if ((cell != null && cell.getTransitionParams().animateBackgroundBoundsInner) || listView.getY() + child.getY() < fadeZoneTop) {
                return true;
            }
        }
        if (scrimGroup != null) {
            MessageObject.GroupedMessages.TransitionParams transitionParams = scrimGroup.transitionParams;
            ChatMessageCell cell = transitionParams.cell;
            if (cell != null) {
                float top = transitionParams.top + transitionParams.offsetTop;
                if (!transitionParams.backgroundChangeBounds) {
                    top += cell.getTranslationY();
                }
                if (listView.getY() + top < fadeZoneTop) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int getScaledChatTopFadeZone(ActionBar actionBar, int fadeZone, boolean material3) {
        int actionBarHeight = actionBar.getMeasuredHeight();
        int actionBarFadeZone = getChatTopFadeZone(actionBarHeight, material3);
        if (actionBarHeight == 0 || actionBarFadeZone >= fadeZone) {
            return fadeZone;
        }
        return actionBarFadeZone + Math.round((fadeZone - actionBarFadeZone) * 0.5f);
    }

    public static boolean isLightChatStatusBar(ActionBar actionBar, int color) {
        return isLightChatStatusBar(actionBar, color, isMaterial3ChatHeaderStyle());
    }

    public static boolean isLightChatStatusBar(ActionBar actionBar, int color, boolean material3) {
        if (!material3 || actionBar.isActionModeShowed()) {
            color = actionBar.getBackgroundColor();
        }
        return AndroidUtilities.computePerceivedBrightness(color) > 0.721f;
    }

    public static int getAvatarContainerLeftMargin(boolean inPreviewMode) {
        if (inPreviewMode) {
            return 4;
        }
        return isMaterial3ChatHeaderStyle() ? 57 : 52;
    }

    public static float getTopPanelActionBarGapOffset(ChatActivityTopPanelLayout topPanelLayout) {
        if (!isMaterial3ChatHeaderStyle() || topPanelLayout == null) {
            return 0f;
        }
        return AndroidUtilities.dp(4) * topPanelLayout.getMetadata().getTotalVisibility();
    }

    public static float getFinalTopPanelHeight(float height, ChatActivityTopPanelLayout topPanelLayout) {
        return height + getTopPanelActionBarGapOffset(topPanelLayout);
    }

    public static float getTopPanelTranslationY(float top, float height, float progress) {
        return top + AndroidUtilities.dp(isMaterial3ChatHeaderStyle() ? -1 : -5) - height * progress;
    }

    public static int getChatTopFadeHeight(boolean material3) {
        return AndroidUtilities.dp(material3 ? 78 : 48);
    }

    public static int getChatTopFadeZone(int fadeZone, boolean material3) {
        return material3 ? fadeZone + AndroidUtilities.dp(42) : fadeZone;
    }

    public static final class ProfileTransitionState {

        private static final float DEFAULT_AVATAR_SIZE_DP = 42f;

        private float avatarTranslation;
        private float avatarStartY = Float.NaN;
        private float avatarSizeDp = DEFAULT_AVATAR_SIZE_DP;
        private float nameTranslationX = Float.NaN;
        private float nameTranslationY = Float.NaN;
        private float onlineTranslationX = Float.NaN;
        private float onlineTranslationY = Float.NaN;

        public void reset() {
            avatarTranslation = 0f;
            avatarStartY = Float.NaN;
            avatarSizeDp = DEFAULT_AVATAR_SIZE_DP;
            nameTranslationX = Float.NaN;
            nameTranslationY = Float.NaN;
            onlineTranslationX = Float.NaN;
            onlineTranslationY = Float.NaN;
        }

        public void capture(ChatAvatarContainer avatarContainer, ViewGroup parent, View nameView, View onlineView) {
            if (avatarContainer == null || parent == null) {
                return;
            }
            BackupImageView avatarImageView = avatarContainer.getAvatarImageView();
            if (avatarImageView == null) {
                return;
            }
            avatarTranslation = ViewPositionWatcher.computeXCoordinateInParent(avatarImageView, parent);
            avatarStartY = ViewPositionWatcher.computeYCoordinateInParent(avatarImageView, parent);
            int avatarWidth = avatarImageView.getMeasuredWidth() != 0 ? avatarImageView.getMeasuredWidth() : avatarImageView.getWidth();
            if (avatarWidth > 0) {
                avatarSizeDp = avatarWidth / AndroidUtilities.density;
            }
            if (avatarContainer.getTitleTextView() != null && nameView != null) {
                nameTranslationX = ViewPositionWatcher.computeXCoordinateInParent(avatarContainer.getTitleTextView(), parent) - getTransitionLayoutLeft(nameView);
                nameTranslationY = ViewPositionWatcher.computeYCoordinateInParent(avatarContainer.getTitleTextView(), parent) - getTransitionLayoutTop(nameView);
            }
            View subtitleTextView = avatarContainer.getSubtitleTextView();
            if (subtitleTextView == null || onlineView == null) {
                return;
            }
            onlineTranslationX = ViewPositionWatcher.computeXCoordinateInParent(subtitleTextView, parent) - getTransitionLayoutLeft(onlineView);
            onlineTranslationY = ViewPositionWatcher.computeYCoordinateInParent(subtitleTextView, parent) - getTransitionLayoutTop(onlineView);
        }

        public float getAvatarTranslation() {
            return avatarTranslation;
        }

        public float getAvatarStartY(ActionBar actionBar) {
            if (!Float.isNaN(avatarStartY)) {
                return avatarStartY;
            }
            return (actionBar.getOccupyStatusBar() ? AndroidUtilities.statusBarHeight : 0)
                    + ActionBar.getCurrentActionBarHeight() / 2f
                    - AndroidUtilities.dp(avatarSizeDp / 2f)
                    + actionBar.getTranslationY();
        }

        public float getAvatarStartScale() {
            return avatarSizeDp / 100f;
        }

        public float getAvatarSizeDp() {
            return avatarSizeDp;
        }

        public float getNameTranslationX() {
            if (!Float.isNaN(nameTranslationX)) {
                return nameTranslationX;
            }
            return avatarTranslation - AndroidUtilities.dp(109) + AndroidUtilities.dp(avatarSizeDp + 6f);
        }

        public float getNameTranslationY(ActionBar actionBar) {
            if (!Float.isNaN(nameTranslationY)) {
                return nameTranslationY;
            }
            return (float) Math.floor(getAvatarStartY(actionBar)) + AndroidUtilities.dp(isMaterial3ChatHeaderStyle() ? 1.66f : 1.3f);
        }

        public float getOnlineTranslationX() {
            if (!Float.isNaN(onlineTranslationX)) {
                return onlineTranslationX;
            }
            return getNameTranslationX();
        }

        public float getOnlineTranslationY(ActionBar actionBar) {
            if (!Float.isNaN(onlineTranslationY)) {
                return onlineTranslationY;
            }
            return (float) Math.floor(getAvatarStartY(actionBar)) + AndroidUtilities.dp(isMaterial3ChatHeaderStyle() ? 26.66f : 24f);
        }

        public void updateBadgePositionsFromCollapsedAvatar(View avatarView, float scale, ImageView emojiStatus, ImageView verified, ImageView premium, ImageView badge) {
            float offset = AndroidUtilities.dp(avatarSizeDp) * (scale * 100f / avatarSizeDp) - AndroidUtilities.dp(avatarSizeDp);
            updateBadgePositions(avatarView, offset, emojiStatus, verified, premium, badge);
        }

        public void updateBadgePositionsFromExpandedAvatar(View avatarView, float scale, ImageView emojiStatus, ImageView verified, ImageView premium, ImageView badge) {
            float offset = (avatarView.getMeasuredWidth() - AndroidUtilities.dp(avatarSizeDp)) * (scale * 100f / avatarSizeDp);
            updateBadgePositions(avatarView, offset, emojiStatus, verified, premium, badge);
        }

        private void updateBadgePositions(View avatarView, float offset, ImageView first, ImageView second, ImageView third, ImageView fourth) {
            if (first != null) {
                first.setTranslationX(avatarView.getX() + AndroidUtilities.dp(avatarSizeDp - 26f) + offset);
                first.setTranslationY(avatarView.getY() + AndroidUtilities.dp(-10f) + offset);
            }
            if (second != null) {
                second.setTranslationX(avatarView.getX() + AndroidUtilities.dp(avatarSizeDp - 14f) + offset);
                second.setTranslationY(avatarView.getY() + AndroidUtilities.dp(avatarSizeDp - 15.5f) + offset);
            }
            if (third != null) {
                third.setTranslationX(avatarView.getX() + AndroidUtilities.dp(avatarSizeDp - 14f) + offset);
                third.setTranslationY(avatarView.getY() + AndroidUtilities.dp(avatarSizeDp - 18f) + offset);
            }
            if (fourth != null) {
                fourth.setTranslationX(avatarView.getX() + AndroidUtilities.dp(avatarSizeDp - 14f) + offset);
                fourth.setTranslationY(avatarView.getY() + AndroidUtilities.dp(avatarSizeDp - 18f) + offset);
            }
        }

        private static int getTransitionLayoutLeft(View view) {
            ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
            if (layoutParams instanceof ViewGroup.MarginLayoutParams) {
                return ((ViewGroup.MarginLayoutParams) layoutParams).leftMargin;
            }
            return view.getLeft();
        }

        private static int getTransitionLayoutTop(View view) {
            ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
            if (layoutParams instanceof ViewGroup.MarginLayoutParams) {
                return ((ViewGroup.MarginLayoutParams) layoutParams).topMargin;
            }
            return view.getTop();
        }
    }
}
