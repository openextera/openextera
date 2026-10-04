package com.exteragram.messenger.utils.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

import com.exteragram.messenger.ExteraConfig;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.BaseCell;
import org.telegram.ui.Cells.ChatMessageCell;

public final class ForwardsCountUiHelper {

    private final PorterDuffColorFilter[] colorFilters = new PorterDuffColorFilter[2];
    private final int[] colors = new int[2];

    private float animateFromX;
    private StaticLayout animateLayout;
    private boolean animating;
    private int count;
    private Drawable drawable;
    private StaticLayout lastLayout;
    private float lastX;
    private StaticLayout layout;
    private float textSize;
    private Typeface typeface;
    private int width;
    private boolean allowed = true;

    private int account = -1;
    private long dialogId;
    private int messageId;

    private static int getCount(MessageObject messageObject) {
        if (!ExteraConfig.getShowForwardsCount() || messageObject == null) {
            return 0;
        }
        TLRPC.Message message = messageObject.messageOwner;
        if (message == null || (message.flags & TLRPC.MESSAGE_FLAG_HAS_VIEWS) == 0 || messageObject.scheduled || messageObject.notime || messageObject.isSponsored() || messageObject.isQuickReply() || messageObject.isWelcomeMessage()) {
            return 0;
        }
        return Math.max(0, messageObject.messageOwner.forwards);
    }

    public boolean isChanged(MessageObject messageObject) {
        return count != (allowed ? getCount(messageObject) : 0);
    }

    public void bind(MessageObject messageObject) {
        if (messageObject == null) {
            return;
        }
        if (account == messageObject.currentAccount && dialogId == messageObject.getDialogId() && messageId == messageObject.getId()) {
            return;
        }
        account = messageObject.currentAccount;
        dialogId = messageObject.getDialogId();
        messageId = messageObject.getId();
        layout = null;
        lastLayout = null;
        count = 0;
        width = 0;
        lastX = 0;
        allowed = true;
        resetAnimation();
    }

    public int measure(Context context, MessageObject messageObject, boolean allowed) {
        this.allowed = allowed;
        count = allowed ? getCount(messageObject) : 0;
        if (count == 0) {
            layout = null;
            width = 0;
            return 0;
        }
        if (drawable == null) {
            drawable = context.getResources().getDrawable(R.drawable.msg_reply_small).mutate();
        }
        TextPaint paint = Theme.chat_timePaint;
        String text = LocaleController.formatShortNumber(count, null);
        if (layout == null || !TextUtils.equals(layout.getText(), text) || textSize != paint.getTextSize() || typeface != paint.getTypeface()) {
            textSize = paint.getTextSize();
            typeface = paint.getTypeface();
            layout = new StaticLayout(text, paint, Math.max(1, (int) Math.ceil(paint.measureText(text))), Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false);
        }
        width = (int) Math.ceil(layout.getWidth() + drawable.getIntrinsicWidth() * paint.getTextSize() / drawable.getIntrinsicHeight() + AndroidUtilities.dp(10));
        return width;
    }

    public void recordDrawingState() {
        lastLayout = layout;
    }

    public boolean animateChange() {
        animateFromX = lastX;
        animating = layout != lastLayout;
        animateLayout = animating ? lastLayout : null;
        return animating;
    }

    public void resetAnimation() {
        animating = false;
        animateLayout = null;
    }

    public float draw(Canvas canvas, ChatMessageCell cell, float x, float y, float offsetX, float alpha, float timeAlpha, boolean selected) {
        if (layout == null && animateLayout == null) {
            return 0;
        }
        ChatMessageCell.TransitionParams transitionParams = cell.transitionParams;
        float progress = transitionParams.animateChangeProgress;
        boolean appearing = animating && animateLayout == null;
        boolean disappearing = animating && layout == null;

        float drawX = (transitionParams.shouldAnimateTimeX ? cell.timeX : x) + offsetX;
        if (transitionParams.shouldAnimateTimeX && !appearing && (!cell.getMessageObject().isRoundVideo() || !transitionParams.animateDrawBackground)) {
            drawX = disappearing ? animateFromX : AndroidUtilities.lerp(animateFromX, drawX, progress);
        }
        MessageObject.GroupedMessages group = cell.getCurrentMessagesGroup();
        if (group != null && group.transitionParams.backgroundChangeBounds) {
            drawX += group.transitionParams.offsetRight;
        }
        if (transitionParams.animateBackgroundBoundsInner) {
            drawX += cell.getAnimationOffsetX();
        }
        lastX = drawX;

        TextPaint paint = Theme.chat_timePaint;
        int oldAlpha = paint.getAlpha();
        int textAlpha = (int) (oldAlpha * timeAlpha);

        int colorKey;
        if (cell.shouldDrawTimeOnMedia()) {
            colorKey = cell.getMessageObject().shouldDrawWithoutBackground() ? Theme.key_chat_serviceText : Theme.key_chat_mediaViews;
        } else if (cell.getMessageObject().isOutOwner()) {
            colorKey = selected ? Theme.key_chat_outViewsSelected : Theme.key_chat_outViews;
        } else {
            colorKey = selected ? Theme.key_chat_inViewsSelected : Theme.key_chat_inViews;
        }
        int color = cell.getThemedColor(colorKey);
        int index = selected ? 1 : 0;
        if (colorFilters[index] == null || colors[index] != color) {
            colors[index] = color;
            colorFilters[index] = new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);
        }
        drawable.setColorFilter(colorFilters[index]);
        float iconWidth = BaseCell.setDrawableBounds(drawable, drawX, y, paint.getTextSize());

        canvas.save();
        if (timeAlpha != 1f) {
            float scale = 0.5f + 0.5f * timeAlpha;
            StaticLayout scaleLayout = layout != null ? layout : animateLayout;
            canvas.scale(scale, scale, drawX + (iconWidth + AndroidUtilities.dp(3) + scaleLayout.getWidth()) / 2f, drawable.getBounds().centerY());
        }
        float iconAlpha = appearing ? progress : (disappearing ? 1f - progress : 1f);
        drawable.setAlpha((int) (255 * alpha * iconAlpha));
        canvas.save();
        canvas.scale(-1f, 1f, drawable.getBounds().exactCenterX(), 0);
        drawable.draw(canvas);
        canvas.restore();

        canvas.translate(drawX + iconWidth + AndroidUtilities.dp(3), y);
        if (animateLayout != null) {
            paint.setAlpha((int) (textAlpha * (1f - progress)));
            animateLayout.draw(canvas);
        }
        if (layout != null) {
            paint.setAlpha((int) (textAlpha * (animating ? progress : 1f)));
            layout.draw(canvas);
        }
        canvas.restore();
        paint.setAlpha(oldAlpha);
        return width;
    }

    public void appendAccessibilityText(SpannableStringBuilder builder) {
        if (count > 0) {
            builder.append("\n").append(String.format(LocaleController.getPluralString("Shares", count), AndroidUtilities.formatCount(count)));
        }
    }
}
