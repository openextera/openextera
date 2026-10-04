package com.exteragram.messenger.utils.text;

import android.content.Context;
import android.graphics.Color;
import android.text.Html;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ReplacementSpan;
import android.text.style.URLSpan;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.utils.network.RemoteUtils;
import com.exteragram.messenger.utils.ui.ColorRectSpan;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LinkifyPort;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.ColoredImageSpan;
import org.telegram.ui.Components.URLSpanNoUnderline;
import org.telegram.ui.Components.URLSpanReplacement;
import org.telegram.ui.FilterCreateActivity;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class LocaleUtils {

    private static final String CUSTOM_EMOJI_LINK_PREFIX = "tg://emoji?id=";

    private static final Pattern MARKDOWN_LINK_PATTERN = Pattern.compile("\\[([^]]+?)]\\(" + LinkifyPort.WEB_URL_REGEX + "\\)");
    private static final Pattern HEX_PATTERN = Pattern.compile("(?<![a-zA-Z0-9])#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{8})(?![a-zA-Z0-9])");

    public static String normalizeResourceLanguage(String language) {
        if (TextUtils.isEmpty(language)) {
            return null;
        }
        String lower = language.toLowerCase(Locale.US);
        if ("he".equals(lower)) {
            return "iw";
        }
        return "no".equals(lower) ? "nb" : lower;
    }

    public static String normalizeResourceRegion(String language, String region) {
        if (TextUtils.isEmpty(region)) {
            return null;
        }
        String upper = region.toUpperCase(Locale.US);
        if ("zh".equals(normalizeResourceLanguage(language))) {
            if ("HANS".equals(upper) || "CN".equals(upper) || "SG".equals(upper)) {
                return "CN";
            }
            return "TW";
        }
        return upper;
    }

    public static String getActionBarTitle() {
        return getActionBarTitle(UserConfig.selectedAccount);
    }

    public static String getActionBarTitle(int account) {
        int titleText = ExteraConfig.getTitleText();
        if (titleText == 0) {
            return LocaleController.getString(R.string.exteraAppName);
        }
        if (titleText == 3) {
            return LocaleController.getString(R.string.FilterChats);
        }
        TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
        if (titleText == 1 && !TextUtils.isEmpty(UserObject.getPublicUsername(user))) {
            return UserObject.getPublicUsername(user);
        }
        return UserObject.getFirstName(user);
    }

    public static CharSequence formatWithUsernames(CharSequence text) {
        return formatWithUsernames(text, LaunchActivity.getSafeLastFragment());
    }

    public static CharSequence formatWithUsernames(CharSequence text, BaseFragment fragment) {
        return formatWithUsernames(text, fragment, null);
    }

    public static CharSequence formatWithUsernames(CharSequence text, BaseFragment fragment, Runnable onClick) {
        if (TextUtils.isEmpty(text)) {
            return text;
        }
        SpannableStringBuilder builder = new SpannableStringBuilder(text);
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '@') {
                start = i;
                continue;
            }
            if (start == -1) {
                continue;
            }
            int end = i + 1;
            if (end != text.length() && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_')) {
                continue;
            }
            if (end - start > 1) {
                URLSpan[] existing = builder.getSpans(start, end, URLSpan.class);
                if (existing == null || existing.length == 0) {
                    final String username = text.subSequence(start, end).toString();
                    try {
                        builder.setSpan(new URLSpanNoUnderline(username) {
                            @Override
                            public void onClick(View widget) {
                                if (onClick != null) {
                                    onClick.run();
                                }
                                if (fragment == null || fragment.getMessagesController() == null) {
                                    return;
                                }
                                fragment.getMessagesController().openByUserName(username.substring(1), fragment, 1);
                            }
                        }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
            }
            start = -1;
        }
        return builder;
    }

    public static CharSequence formatWithHtmlURLs(CharSequence text) {
        if (TextUtils.isEmpty(text)) {
            return text;
        }
        SpannableString spannable = new SpannableString(text);
        URLSpan[] spans = spannable.getSpans(0, text.length(), URLSpan.class);
        SpannableStringBuilder builder = new SpannableStringBuilder(spannable);
        for (URLSpan span : spans) {
            int start = builder.getSpanStart(span);
            int end = builder.getSpanEnd(span);
            String url = span.getURL();
            builder.removeSpan(span);
            builder.setSpan(new URLSpanNoUnderline(url), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return builder;
    }

    public static CharSequence formatWithURLs(CharSequence text) {
        if (TextUtils.isEmpty(text)) {
            return text;
        }
        SpannableStringBuilder builder = new SpannableStringBuilder(text);
        Matcher matcher = LinkifyPort.WEB_URL.matcher(text);
        while (matcher.find()) {
            try {
                builder.setSpan(new URLSpanNoUnderline(ensureUrlHasHttps(matcher.group(0))), matcher.start(), matcher.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        return builder;
    }

    public static CharSequence fullyFormatText(CharSequence text) {
        return fullyFormatText(text, null, null);
    }

    public static CharSequence fullyFormatText(CharSequence text, BaseFragment fragment, Runnable onClick) {
        if (TextUtils.isEmpty(text)) {
            return text;
        }
        CharSequence[] result = {formatWithURLs(text)};
        parseMarkdownLinks(result, onClick);
        CharSequence formatted;
        if (fragment != null && onClick != null) {
            formatted = formatWithUsernames(result[0], fragment, onClick);
        } else {
            formatted = formatWithUsernames(result[0]);
        }
        return AndroidUtilities.replaceTags(formatted);
    }

    public static CharSequence fromHtml(String html) {
        return new SpannableString(Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY));
    }

    public static String getAppName() {
        try {
            return ApplicationLoader.applicationContext.getString(R.string.exteraAppName);
        } catch (Exception e) {
            return "exteraGram";
        }
    }

    public static void parseMarkdownLinks(CharSequence[] text) {
        parseMarkdownLinks(text, null);
    }

    public static void parseMarkdownLinks(CharSequence[] text, Runnable onClick) {
        if (text == null || text.length == 0 || text[0] == null) {
            return;
        }
        Spannable spannable = text[0] instanceof Spannable ? (Spannable) text[0] : Spannable.Factory.getInstance().newSpannable(text[0].toString());
        Matcher matcher = MARKDOWN_LINK_PATTERN.matcher(spannable);
        ArrayList<String> sources = new ArrayList<>();
        ArrayList<CharSequence> replacements = new ArrayList<>();
        while (matcher.find()) {
            int start = matcher.start(1);
            int end = matcher.end(1);
            if (start < 0 || end < 0 || start > end || end > spannable.length()) {
                continue;
            }
            SpannableStringBuilder link = new SpannableStringBuilder(spannable.subSequence(start, end));
            link.setSpan(new URLSpanReplacement(ensureUrlHasHttps(matcher.group(2))) {
                @Override
                public void onClick(View widget) {
                    if (onClick != null) {
                        onClick.run();
                    }
                    super.onClick(widget);
                }
            }, 0, link.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sources.add(matcher.group(0));
            replacements.add(link);
        }
        if (sources.isEmpty()) {
            return;
        }
        text[0] = TextUtils.replace(text[0], sources.toArray(new String[0]), replacements.toArray(new CharSequence[0]));
    }

    public static CharSequence applyNewSpan(CharSequence text) {
        return applyBadge(text, "NEW", Theme.key_featuredStickers_addButton, Theme.key_featuredStickers_buttonText);
    }

    public static CharSequence applyBadge(CharSequence text, CharSequence badge, int colorKey, int textColorKey) {
        if (text == null) {
            text = "";
        }
        SpannableStringBuilder builder = new SpannableStringBuilder(text);
        builder.append("  d");
        FilterCreateActivity.NewSpan span = new FilterCreateActivity.NewSpan(10);
        span.setText(badge != null ? badge.toString() : "");
        span.setTypeface(AndroidUtilities.bold());
        span.setColor(Theme.getColor(colorKey));
        span.setTextColor(Theme.getColor(textColorKey));
        builder.setSpan(span, builder.length() - 1, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return builder;
    }

    public static SpannableStringBuilder replaceArrows(Context context, CharSequence text, int arrowResId) {
        SpannableStringBuilder builder = new SpannableStringBuilder(text);
        int index = TextUtils.indexOf(builder, "->");
        while (index >= 0) {
            ColoredImageSpan span = new ColoredImageSpan(ContextCompat.getDrawable(context, arrowResId).mutate(), ColoredImageSpan.ALIGN_CENTER);
            if (LocaleController.isRTL) {
                span.rotate(180f);
            }
            builder.replace(index, index + 2, ">");
            int end = index + 1;
            builder.setSpan(span, index, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            index = TextUtils.indexOf(builder, "->", end);
        }
        return builder;
    }

    public static String ensureUrlHasHttps(String url) {
        if (url == null) {
            return null;
        }
        if (!LinkifyPort.WEB_URL.matcher(url).matches() || url.startsWith("http://") || url.startsWith("https://") || url.contains("://")) {
            return url;
        }
        return AndroidUtilities.defaultUrlScheme(url) + url;
    }

    public static boolean parseCustomEmojis(CharSequence text, ArrayList<TLRPC.MessageEntity> entities) {
        if (text == null || entities == null || entities.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (int i = 0; i < entities.size(); i++) {
            TLRPC.MessageEntity entity = entities.get(i);
            if (!(entity instanceof TLRPC.TL_messageEntityTextUrl)) {
                continue;
            }
            TLRPC.TL_messageEntityTextUrl textUrl = (TLRPC.TL_messageEntityTextUrl) entity;
            if (textUrl.url == null || !textUrl.url.startsWith(CUSTOM_EMOJI_LINK_PREFIX)
                    || textUrl.offset < 0 || textUrl.length <= 0 || textUrl.offset + textUrl.length > text.length()) {
                continue;
            }
            try {
                long documentId = Long.parseLong(textUrl.url.substring(CUSTOM_EMOJI_LINK_PREFIX.length()));
                int[] emojiOnly = new int[1];
                ArrayList<Emoji.EmojiSpanRange> emojis = Emoji.parseEmojis(text.subSequence(textUrl.offset, textUrl.offset + textUrl.length).toString(), emojiOnly);
                if (emojiOnly[0] > 0 && emojis.size() == 1) {
                    TLRPC.TL_messageEntityCustomEmoji customEmoji = new TLRPC.TL_messageEntityCustomEmoji();
                    customEmoji.document_id = documentId;
                    customEmoji.offset = textUrl.offset;
                    customEmoji.length = textUrl.length;
                    customEmoji.local = true;
                    entities.set(i, customEmoji);
                    changed = true;
                }
            } catch (NumberFormatException e) {
                FileLog.e("Failed to parse custom emoji id: " + textUrl.url, e);
            }
        }
        return changed;
    }

    public static boolean isCustomEmojiOnlyLinkMessage(TLRPC.Message message) {
        if (message == null || message.entities == null || message.entities.isEmpty() || !MessageObject.isMediaEmptyWebpage(message)) {
            return false;
        }
        boolean found = false;
        for (int i = 0; i < message.entities.size(); i++) {
            TLRPC.MessageEntity entity = message.entities.get(i);
            if (entity instanceof TLRPC.TL_messageEntityTextUrl) {
                String url = ((TLRPC.TL_messageEntityTextUrl) entity).url;
                if (url == null || !url.startsWith(CUSTOM_EMOJI_LINK_PREFIX)) {
                    return false;
                }
            } else if (entity instanceof TLRPC.TL_messageEntityUrl || entity instanceof TLRPC.TL_messageEntityEmail) {
                return false;
            }
            found = true;
        }
        return found;
    }

    public static void replaceCustomEmojis(int account, long dialogId, ArrayList<TLRPC.MessageEntity> entities) {
        if (entities == null || entities.isEmpty() || !canUseLocalPremiumEmojis(account) || dialogId == UserConfig.getInstance(account).getClientUserId()) {
            return;
        }
        HashSet<Long> groupEmojiIds = null;
        if (dialogId < 0) {
            TLRPC.ChatFull chatFull = MessagesController.getInstance(account).getChatFull(-dialogId);
            if (chatFull != null && chatFull.emojiset != null) {
                TLRPC.TL_messages_stickerSet groupSet = MediaDataController.getInstance(account).getGroupStickerSetById(chatFull.emojiset);
                if (groupSet != null && groupSet.documents != null) {
                    groupEmojiIds = new HashSet<>();
                    for (TLRPC.Document document : groupSet.documents) {
                        groupEmojiIds.add(document.id);
                    }
                }
            }
        }
        for (int i = 0; i < entities.size(); i++) {
            TLRPC.MessageEntity entity = entities.get(i);
            if (!(entity instanceof TLRPC.TL_messageEntityCustomEmoji)) {
                continue;
            }
            TLRPC.TL_messageEntityCustomEmoji customEmoji = (TLRPC.TL_messageEntityCustomEmoji) entity;
            if (groupEmojiIds != null && groupEmojiIds.contains(customEmoji.document_id)) {
                continue;
            }
            TLRPC.Document document = customEmoji.document;
            if (document == null) {
                document = AnimatedEmojiDrawable.findDocument(account, customEmoji.document_id);
            }
            if (!MessageObject.isFreeEmoji(document)) {
                entities.set(i, toCustomEmojiLink(customEmoji));
            }
        }
    }

    public static void replaceLocalCustomEmojis(ArrayList<TLRPC.MessageEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        for (int i = 0; i < entities.size(); i++) {
            TLRPC.MessageEntity entity = entities.get(i);
            if (entity instanceof TLRPC.TL_messageEntityCustomEmoji && ((TLRPC.TL_messageEntityCustomEmoji) entity).local) {
                entities.set(i, toCustomEmojiLink((TLRPC.TL_messageEntityCustomEmoji) entity));
            }
        }
    }

    public static ArrayList<TLRPC.MessageEntity> swapLocalCustomEmojis(TLRPC.Message message) {
        if (message == null || message.entities == null) {
            return null;
        }
        for (int i = 0; i < message.entities.size(); i++) {
            TLRPC.MessageEntity entity = message.entities.get(i);
            if (entity instanceof TLRPC.TL_messageEntityCustomEmoji && ((TLRPC.TL_messageEntityCustomEmoji) entity).local) {
                ArrayList<TLRPC.MessageEntity> original = message.entities;
                message.entities = new ArrayList<>(original);
                replaceLocalCustomEmojis(message.entities);
                return original;
            }
        }
        return null;
    }

    public static void restoreLocalCustomEmojis(TLRPC.Message message, ArrayList<TLRPC.MessageEntity> entities) {
        if (message == null || entities == null) {
            return;
        }
        message.entities = entities;
    }

    private static TLRPC.TL_messageEntityTextUrl toCustomEmojiLink(TLRPC.TL_messageEntityCustomEmoji customEmoji) {
        TLRPC.TL_messageEntityTextUrl textUrl = new TLRPC.TL_messageEntityTextUrl();
        textUrl.offset = customEmoji.offset;
        textUrl.length = customEmoji.length;
        textUrl.url = CUSTOM_EMOJI_LINK_PREFIX + customEmoji.document_id;
        return textUrl;
    }

    public static boolean canUseLocalPremiumEmojis() {
        return canUseLocalPremiumEmojis(UserConfig.selectedAccount);
    }

    public static boolean canUseLocalPremiumEmojis(int account) {
        return RemoteUtils.getBooleanConfigValue("local_premium_emojis", false) && !UserConfig.getInstance(account).isPremium();
    }

    public static CharSequence insertHexColorsPreview(CharSequence text) {
        if (TextUtils.isEmpty(text) || !containsHash(text)) {
            return text;
        }
        Spannable spannable = text instanceof Spannable ? (Spannable) text : new SpannableString(text);
        for (ColorRectSpan span : spannable.getSpans(0, spannable.length(), ColorRectSpan.class)) {
            spannable.removeSpan(span);
        }
        Matcher matcher = HEX_PATTERN.matcher(spannable);
        while (matcher.find()) {
            int end = matcher.end();
            int start = end - 1;
            if (hasConflictingHexPreviewSpan(spannable, start, end)) {
                continue;
            }
            try {
                spannable.setSpan(new ColorRectSpan(Color.parseColor(matcher.group())), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } catch (IllegalArgumentException e) {
                FileLog.e("Invalid HEX color: " + matcher.group());
            }
        }
        return spannable;
    }

    private static boolean hasConflictingHexPreviewSpan(Spannable spannable, int start, int end) {
        for (Object span : spannable.getSpans(start, end, Object.class)) {
            if (span instanceof ColorRectSpan) {
                continue;
            }
            int spanStart = spannable.getSpanStart(span);
            int spanEnd = spannable.getSpanEnd(span);
            if (spanStart < end && spanEnd > start && span instanceof ReplacementSpan) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsHash(CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '#') {
                return true;
            }
        }
        return false;
    }
}
