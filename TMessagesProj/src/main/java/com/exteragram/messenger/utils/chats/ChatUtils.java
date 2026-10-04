package com.exteragram.messenger.utils.chats;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Pair;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.utils.network.RemoteUtils;
import com.exteragram.messenger.utils.system.SystemUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.WebFile;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChannelAdminLogActivity;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.ColoredImageSpan;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;
import org.telegram.ui.Components.TranscribeButton;
import org.telegram.ui.ProfileActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

public class ChatUtils {

    private static final int MESSAGE_FLAG_HAS_REACTIONS = 0x00100000;
    private static final long DEFAULT_SEARCH_BOT_ID = 7424190611L;
    private static final String DEFAULT_SEARCH_BOT_USERNAME = "tgdb_search_bot";

    public static final DispatchQueue utilsQueue = new DispatchQueue("utilsQueue");

    private static final ChatUtils[] Instance = new ChatUtils[UserConfig.MAX_ACCOUNT_COUNT];
    private static final Object[] lockObjects = new Object[UserConfig.MAX_ACCOUNT_COUNT];
    private static final CharsetDecoder textDecoder = StandardCharsets.UTF_8.newDecoder();

    private static SpannableStringBuilder editedIcon;
    private static SpannableStringBuilder channelIcon;

    static {
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            lockObjects[i] = new Object();
        }
    }

    private final int selectedAccount;

    public ChatUtils(int account) {
        this.selectedAccount = account;
    }

    public static ChatUtils getInstance() {
        return getInstance(UserConfig.selectedAccount);
    }

    public static ChatUtils getInstance(int num) {
        ChatUtils localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (lockObjects[num]) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new ChatUtils(num);
                }
            }
        }
        return localInstance;
    }

    public static long extractOwnerId(long id) {
        long ownerId = id >> 32;
        if (((id >> 16) & 0xFF) == 0x3F) {
            ownerId |= 0x80000000L;
        }
        if (((id >> 24) & 0xFF) != 0) {
            ownerId += 0x100000000L;
        }
        return ownerId;
    }

    public static CharSequence getEditedIcon() {
        if (editedIcon == null) {
            editedIcon = new SpannableStringBuilder("‍");
            ColoredImageSpan span = new ColoredImageSpan(Theme.chat_pencilIconDrawable);
            span.setTranslateX(-AndroidUtilities.dp(1));
            editedIcon.setSpan(span, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return editedIcon;
    }

    public static CharSequence getChannelIcon() {
        if (channelIcon == null) {
            channelIcon = new SpannableStringBuilder("‍");
            channelIcon.setSpan(new ColoredImageSpan(Theme.chat_channelIconDrawable), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return channelIcon;
    }

    public static boolean hasRestrictionReason(ArrayList<TLRPC.RestrictionReason> reasons, String reason) {
        if (reasons == null || TextUtils.isEmpty(reason)) {
            return false;
        }
        for (int i = 0; i < reasons.size(); i++) {
            TLRPC.RestrictionReason restrictionReason = reasons.get(i);
            if (restrictionReason != null && reason.equals(restrictionReason.reason)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isTermsRestrictedMessage(MessageObject messageObject) {
        return messageObject != null && messageObject.messageOwner != null && hasRestrictionReason(messageObject.messageOwner.restriction_reason, "terms");
    }

    public static boolean applyChannelPostContent(TLRPC.Message target, TLRPC.Message source) {
        if (target == null || source == null || target.fwd_from == null || target.fwd_from.saved_from_msg_id != source.id || !MessageObject.peersEqual(target.fwd_from.saved_from_peer, source.peer_id)) {
            return false;
        }
        boolean changed = reactionsChanged(target.reactions, source.reactions)
                || !TextUtils.equals(target.message, source.message)
                || target.edit_date != source.edit_date
                || target.edit_hide != source.edit_hide
                || mediaChanged(target.media, source.media)
                || entitiesChanged(target.entities, source.entities);
        target.reactions = source.reactions;
        target.message = source.message;
        target.media = source.media;
        target.entities = source.entities;
        target.edit_date = source.edit_date;
        target.edit_hide = source.edit_hide;
        target.flags &= ~(TLRPC.MESSAGE_FLAG_HAS_MEDIA | TLRPC.MESSAGE_FLAG_HAS_ENTITIES | TLRPC.MESSAGE_FLAG_EDITED | MESSAGE_FLAG_HAS_REACTIONS);
        if (target.media != null) {
            target.flags |= TLRPC.MESSAGE_FLAG_HAS_MEDIA;
        }
        if (target.entities != null && !target.entities.isEmpty()) {
            target.flags |= TLRPC.MESSAGE_FLAG_HAS_ENTITIES;
        }
        if (target.edit_date != 0) {
            target.flags |= TLRPC.MESSAGE_FLAG_EDITED;
        }
        if (target.reactions != null) {
            target.flags |= MESSAGE_FLAG_HAS_REACTIONS;
        }
        return changed;
    }

    private static boolean reactionsChanged(TLRPC.MessageReactions a, TLRPC.MessageReactions b) {
        if (a == b) {
            return false;
        }
        if (a == null || b == null) {
            return true;
        }
        int size = a.results == null ? 0 : a.results.size();
        if (size != (b.results == null ? 0 : b.results.size())) {
            return true;
        }
        for (int i = 0; i < size; i++) {
            TLRPC.ReactionCount countA = a.results.get(i);
            TLRPC.ReactionCount countB = b.results.get(i);
            if (countA == null || countB == null) {
                if (countA != countB) {
                    return true;
                }
            } else if (countA.count != countB.count || countA.chosen_order != countB.chosen_order || !ReactionsLayoutInBubble.equalsTLReaction(countA.reaction, countB.reaction)) {
                return true;
            }
        }
        return false;
    }

    private static boolean mediaChanged(TLRPC.MessageMedia a, TLRPC.MessageMedia b) {
        if (a == b) {
            return false;
        }
        return a == null || b == null || a.getClass() != b.getClass() || mediaIdentity(a) != mediaIdentity(b);
    }

    private static long mediaIdentity(TLRPC.MessageMedia media) {
        if (media.photo != null) {
            return media.photo.id;
        }
        if (media.document != null) {
            return media.document.id;
        }
        if (media.webpage != null) {
            return media.webpage.id;
        }
        if (media.game != null) {
            return media.game.id;
        }
        return media.id;
    }

    private static boolean entitiesChanged(ArrayList<TLRPC.MessageEntity> a, ArrayList<TLRPC.MessageEntity> b) {
        if (a == b) {
            return false;
        }
        int size = a == null ? 0 : a.size();
        if (size != (b == null ? 0 : b.size())) {
            return true;
        }
        for (int i = 0; i < size; i++) {
            TLRPC.MessageEntity entityA = a.get(i);
            TLRPC.MessageEntity entityB = b.get(i);
            if (entityA == null || entityB == null) {
                if (entityA != entityB) {
                    return true;
                }
            } else if (entityA.getClass() != entityB.getClass() || entityA.offset != entityB.offset || entityA.length != entityB.length || !TextUtils.equals(entityA.url, entityB.url)) {
                return true;
            }
        }
        return false;
    }

    public static String getDCName(int dc) {
        switch (dc) {
            case 1:
            case 3:
                return "Miami FL, USA";
            case 2:
            case 4:
                return "Amsterdam, NL";
            case 5:
                return "Singapore, SG";
            default:
                return null;
        }
    }

    private static boolean fileExists(String path) {
        if (TextUtils.isEmpty(path)) {
            return false;
        }
        File file = new File(path);
        return file.exists() && file.isFile();
    }

    public CharSequence getMessageText(MessageObject messageObject, MessageObject.GroupedMessages group) {
        CharSequence text = null;
        int type = messageObject.type;
        if (type != MessageObject.TYPE_EMOJIS && type != MessageObject.TYPE_ANIMATED_STICKER && type != MessageObject.TYPE_STICKER) {
            text = getMessageCaption(messageObject, group);
            if (text == null && messageObject.isPoll()) {
                try {
                    TLRPC.Poll poll = ((TLRPC.TL_messageMediaPoll) messageObject.messageOwner.media).poll;
                    StringBuilder sb = new StringBuilder(poll.question.text);
                    sb.append("\n");
                    for (TLRPC.PollAnswer answer : poll.answers) {
                        sb.append("\n🔘 ");
                        sb.append(answer.text == null ? "" : answer.text.text);
                    }
                    text = sb.toString();
                } catch (Exception ignore) {
                }
            }
            if (text == null && MessageObject.isMediaEmpty(messageObject.messageOwner)) {
                text = getMessageContent(messageObject);
            }
            if (text != null && Emoji.fullyConsistsOfEmojis(text)) {
                text = null;
            }
        }
        if (messageObject.translated || messageObject.isRestrictedMessage) {
            return null;
        }
        return text;
    }

    private CharSequence getMessageCaption(MessageObject messageObject, MessageObject.GroupedMessages group) {
        String restrictionReason = MessagesController.getInstance(selectedAccount).getRestrictionReason(messageObject.messageOwner.restriction_reason);
        if (!TextUtils.isEmpty(restrictionReason)) {
            return restrictionReason;
        }
        if (messageObject.isVoiceTranscriptionOpen() && !TranscribeButton.isTranscribing(messageObject)) {
            return messageObject.getVoiceTranscription();
        }
        if (messageObject.caption != null) {
            return messageObject.caption;
        }
        if (group == null) {
            return null;
        }
        CharSequence caption = null;
        for (int i = 0; i < group.messages.size(); i++) {
            CharSequence messageCaption = group.messages.get(i).caption;
            if (messageCaption != null) {
                if (caption != null) {
                    return null;
                }
                caption = messageCaption;
            }
        }
        return caption;
    }

    private CharSequence getMessageContent(MessageObject messageObject) {
        SpannableStringBuilder builder = new SpannableStringBuilder();
        String restrictionReason = MessagesController.getInstance(selectedAccount).getRestrictionReason(messageObject.messageOwner.restriction_reason);
        if (!TextUtils.isEmpty(restrictionReason)) {
            builder.append(restrictionReason);
        } else if (messageObject.caption != null) {
            builder.append(messageObject.caption);
        } else {
            builder.append(messageObject.messageText);
        }
        return builder.toString();
    }

    public ArrayList<TLRPC.MessageEntity> copyMessageEntities(ArrayList<TLRPC.MessageEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return null;
        }
        ArrayList<TLRPC.MessageEntity> result = new ArrayList<>();
        for (TLRPC.MessageEntity entity : entities) {
            if (entity instanceof TLRPC.TL_messageEntityMentionName) {
                TLRPC.TL_inputMessageEntityMentionName mention = new TLRPC.TL_inputMessageEntityMentionName();
                mention.length = entity.length;
                mention.offset = entity.offset;
                mention.user_id = getMessagesController().getInputUser(((TLRPC.TL_messageEntityMentionName) entity).user_id);
                result.add(mention);
            } else {
                result.add(entity);
            }
        }
        return result;
    }

    public MessageObject getMessageForRepeat(MessageObject messageObject, MessageObject.GroupedMessages group) {
        if (group != null && !group.isDocuments) {
            return getTargetMessageObjectFromGroup(group);
        }
        if (TextUtils.isEmpty(messageObject.messageOwner.message) && !hasMediaForRepeat(messageObject) && !messageObject.isAnyKindOfSticker()) {
            return null;
        }
        return messageObject;
    }

    public boolean hasMediaForRepeat(MessageObject messageObject) {
        TLRPC.MessageMedia media = messageObject.messageOwner.media;
        return !messageObject.isAnyKindOfSticker() && media != null && !(media instanceof TLRPC.TL_messageMediaEmpty) && !(media instanceof TLRPC.TL_messageMediaWebPage);
    }

    private MessageObject getTargetMessageObjectFromGroup(MessageObject.GroupedMessages group) {
        MessageObject target = null;
        for (MessageObject messageObject : group.messages) {
            if (!TextUtils.isEmpty(messageObject.messageOwner.message)) {
                if (target != null) {
                    return null;
                }
                target = messageObject;
            }
        }
        return target;
    }

    public String getTextFromCallback(byte[] data) {
        try {
            return textDecoder.decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            return Base64.encodeToString(data, Base64.NO_PADDING | Base64.NO_WRAP);
        }
    }

    public long getEmojiIdFrom(MessageObject messageObject, TLRPC.User user) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return 0;
        }
        MessageObject reply = messageObject.replyMessageObject;
        if (reply == null || reply.messageOwner == null || reply.messageOwner.from_id == null) {
            return 0;
        }
        if (DialogObject.isEncryptedDialog(reply.getDialogId())) {
            if (reply.isOutOwner()) {
                user = getUserConfig().getCurrentUser();
            }
            return user != null ? UserObject.getEmojiId(user) : 0;
        }
        if (reply.isFromUser()) {
            TLRPC.User fromUser = getMessagesController().getUser(reply.messageOwner.from_id.user_id);
            return fromUser != null ? UserObject.getEmojiId(fromUser) : 0;
        }
        if (reply.isFromChannel()) {
            TLRPC.Chat chat = getMessagesController().getChat(reply.messageOwner.from_id.channel_id);
            if (chat != null) {
                return ChatObject.getEmojiId(chat);
            }
        }
        return 0;
    }

    public TLRPC.InputStickerSet getSetFrom(MessageObject messageObject, TLRPC.User user) {
        return AnimatedEmojiDrawable.findStickerSet(selectedAccount, getEmojiIdFrom(messageObject, user));
    }

    public TLRPC.InputStickerSet getSetFrom(TLRPC.User user) {
        return AnimatedEmojiDrawable.findStickerSet(selectedAccount, UserObject.getProfileEmojiId(user));
    }

    public TLRPC.InputStickerSet getSetFrom(TLRPC.Chat chat) {
        return AnimatedEmojiDrawable.findStickerSet(selectedAccount, ChatObject.getProfileEmojiId(chat));
    }

    public String getDC(TLRPC.User user) {
        return getDC(user, null);
    }

    public String getDC(TLRPC.Chat chat) {
        return getDC(null, chat);
    }

    private int getUserProfileDC(TLRPC.User user) {
        TLRPC.UserFull userFull = getMessagesController().getUserFull(user.id);
        if (userFull != null && userFull.profile_photo != null && !(userFull.profile_photo instanceof TLRPC.TL_photoEmpty) && userFull.profile_photo.dc_id > 0) {
            return userFull.profile_photo.dc_id;
        }
        if (user.photo != null && !user.photo.personal) {
            return user.photo.dc_id;
        }
        return -1;
    }

    public String getDC(TLRPC.User user, TLRPC.Chat chat) {
        int dc = getConnectionsManager().getCurrentDatacenterId();
        if (user != null) {
            if (!UserObject.isUserSelf(user) || dc == -1) {
                dc = getUserProfileDC(user);
            }
        } else if (chat != null) {
            dc = chat.photo != null ? chat.photo.dc_id : -1;
        } else {
            dc = 0;
        }
        if (dc == -1 || dc == 0) {
            return getDCName(0);
        }
        return String.format(Locale.ROOT, "DC%d, %s", dc, getDCName(dc));
    }

    public String getName(long dialogId) {
        String name = null;
        if (DialogObject.isEncryptedDialog(dialogId)) {
            TLRPC.EncryptedChat encryptedChat = getMessagesController().getEncryptedChat(DialogObject.getEncryptedChatId(dialogId));
            if (encryptedChat != null) {
                TLRPC.User user = getMessagesController().getUser(encryptedChat.user_id);
                if (user != null) {
                    name = ContactsController.formatName(user.first_name, user.last_name);
                }
            }
        } else if (DialogObject.isUserDialog(dialogId)) {
            TLRPC.User user = getMessagesController().getUser(dialogId);
            if (user != null) {
                name = ContactsController.formatName(user);
            }
        } else {
            TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
            if (chat != null) {
                name = chat.title;
            }
        }
        return dialogId == getUserConfig().getClientUserId() ? LocaleController.getString(R.string.SavedMessages) : name;
    }

    public Runnable searchUserById(Long userId, Utilities.Callback<TLRPC.User> callback) {
        return searchUserById(userId, callback, null);
    }

    public Runnable searchUserById(Long userId, Utilities.Callback<TLRPC.User> callback, Utilities.Callback<Runnable> onCancelUpdated) {
        if (userId == 0) {
            return null;
        }
        TLRPC.User user = getMessagesController().getUser(userId);
        if (user != null) {
            callback.run(user);
            return null;
        }
        return searchUser(userId, true, true, found -> callback.run(found != null && found.access_hash != 0 ? found : null), onCancelUpdated);
    }

    public void sendBotRequest(String query, boolean cache, Utilities.Callback<String> callback) {
        Pair<Long, String> botInfo = ExteraConfig.getApiBotInfo();
        long botId = botInfo.first;
        TLRPC.User bot = getMessagesController().getUser(botId);
        if (bot != null) {
            sendInlineBotRequest(bot, query, cache, results -> processBotResponse(results, callback));
        } else {
            resolveUser(botInfo.second, botId, resolved -> {
                if (resolved != null) {
                    sendInlineBotRequest(resolved, query, cache, results -> processBotResponse(results, callback));
                } else {
                    callback.run(null);
                }
            });
        }
    }

    private void processBotResponse(TLRPC.messages_BotResults results, Utilities.Callback<String> callback) {
        if (results != null && !results.results.isEmpty()) {
            TLRPC.BotInlineMessage message = results.results.get(0).send_message;
            if (message != null && message.message != null) {
                callback.run(message.message);
                return;
            }
        }
        callback.run(null);
    }

    public Runnable sendInlineBotRequest(TLRPC.User bot, String query, boolean cache, Utilities.Callback<TLRPC.messages_BotResults> callback) {
        return sendInlineBotRequest(bot, query, cache, callback, null);
    }

    public Runnable sendInlineBotRequest(TLRPC.User bot, String query, boolean cache, Utilities.Callback<TLRPC.messages_BotResults> callback, Utilities.Callback<Runnable> onCancelUpdated) {
        if (bot == null) {
            callback.run(null);
            return null;
        }
        String cacheKey = "bot_inline_query_" + bot.id + "_" + query;
        RequestDelegate delegate = (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (cache && (!(response instanceof TLRPC.messages_BotResults) || ((TLRPC.messages_BotResults) response).results.isEmpty())) {
                Runnable cancel = sendInlineBotRequest(bot, query, false, callback);
                if (onCancelUpdated != null) {
                    onCancelUpdated.run(cancel);
                }
                return;
            }
            if (response instanceof TLRPC.messages_BotResults) {
                TLRPC.messages_BotResults results = (TLRPC.messages_BotResults) response;
                if (!cache && results.cache_time != 0) {
                    getMessageStorage().saveBotCache(cacheKey, results);
                }
                callback.run(results);
                return;
            }
            callback.run(null);
        });
        if (cache) {
            getMessageStorage().getBotCache(cacheKey, delegate);
            return null;
        }
        TLRPC.TL_messages_getInlineBotResults req = new TLRPC.TL_messages_getInlineBotResults();
        req.query = query;
        req.bot = getMessagesController().getInputUser(bot);
        req.offset = "";
        req.peer = new TLRPC.TL_inputPeerEmpty();
        int requestId = getConnectionsManager().sendRequest(req, delegate, ConnectionsManager.RequestFlagFailOnServerErrors);
        return () -> getConnectionsManager().cancelRequest(requestId, false);
    }

    private static Pair<Long, String> getBotInfo() {
        String value = RemoteUtils.getStringConfigValue("search_bot", DEFAULT_SEARCH_BOT_ID + ":" + DEFAULT_SEARCH_BOT_USERNAME);
        int separator = value.indexOf(':');
        if (separator != -1) {
            try {
                return new Pair<>(Long.parseLong(value.substring(0, separator)), value.substring(separator + 1));
            } catch (NumberFormatException e) {
                FileLog.e(e);
            }
        }
        return new Pair<>(DEFAULT_SEARCH_BOT_ID, DEFAULT_SEARCH_BOT_USERNAME);
    }

    private Runnable searchUser(long userId, boolean resolveBot, boolean cache, Utilities.Callback<TLRPC.User> callback) {
        return searchUser(userId, resolveBot, cache, callback, null);
    }

    private Runnable searchUser(long userId, boolean resolveBot, boolean cache, Utilities.Callback<TLRPC.User> callback, Utilities.Callback<Runnable> onCancelUpdated) {
        Pair<Long, String> botInfo = getBotInfo();
        long botId = botInfo.first;
        TLRPC.User bot = getMessagesController().getUser(botId);
        if (bot != null) {
            return sendInlineBotRequest(bot, String.valueOf(userId), cache, results -> parseUserResult(results, callback), onCancelUpdated);
        }
        if (resolveBot) {
            return resolveUser(botInfo.second, botId, resolved -> searchUser(userId, false, false, callback));
        }
        callback.run(null);
        return null;
    }

    private void parseUserResult(TLRPC.messages_BotResults results, Utilities.Callback<TLRPC.User> callback) {
        if (results == null || results.results.isEmpty()) {
            callback.run(null);
            return;
        }
        TLRPC.BotInlineResult result = results.results.get(0);
        if (result.send_message == null || TextUtils.isEmpty(result.send_message.message)) {
            callback.run(null);
            return;
        }
        String[] lines = result.send_message.message.split("\n");
        if (lines.length < 3) {
            callback.run(null);
            return;
        }
        TLRPC.TL_user user = new TLRPC.TL_user();
        for (String line : lines) {
            String trimmed = line.replaceAll("\\p{C}", "").trim();
            if (trimmed.startsWith("🆔")) {
                user.id = Utilities.parseLong(trimmed.replaceAll("\\D+", "").trim());
            } else if (trimmed.startsWith("📧")) {
                user.username = trimmed.substring(trimmed.indexOf('@') + 1).trim();
            }
        }
        if (user.id == 0) {
            callback.run(null);
            return;
        }
        if (user.username != null) {
            resolveUser(user.username, user.id, resolved -> {
                if (resolved != null) {
                    callback.run(resolved);
                } else {
                    user.username = null;
                    callback.run(user);
                }
            });
        } else {
            callback.run(user);
        }
    }

    public Runnable resolveUser(String username, long userId, Utilities.Callback<TLRPC.User> callback) {
        return getMessagesController().getUserNameResolver().resolve(username, peerId -> {
            if (peerId != null && peerId > 0 && peerId == userId) {
                callback.run(getMessagesController().getUser(userId));
            } else {
                callback.run(null);
            }
        });
    }

    public void searchChatById(long chatId, Utilities.Callback<TLRPC.Chat> callback) {
        if (chatId == 0) {
            callback.run(null);
            return;
        }
        TLRPC.Chat chat = getMessagesController().getChat(chatId);
        if (chat != null) {
            callback.run(chat);
            return;
        }
        try {
            searchChat(Long.parseLong("-100" + chatId), true, true, callback);
        } catch (NumberFormatException e) {
            callback.run(null);
        }
    }

    private void searchChat(long chatId, boolean resolveBot, boolean cache, Utilities.Callback<TLRPC.Chat> callback) {
        Pair<Long, String> botInfo = getBotInfo();
        long botId = botInfo.first;
        TLRPC.User bot = getMessagesController().getUser(botId);
        if (bot != null) {
            sendInlineBotRequest(bot, String.valueOf(chatId), cache, results -> parseChatResult(results, callback));
        } else if (resolveBot) {
            resolveUser(botInfo.second, botId, resolved -> searchChat(chatId, false, false, callback));
        } else {
            callback.run(null);
        }
    }

    private void parseChatResult(TLRPC.messages_BotResults results, Utilities.Callback<TLRPC.Chat> callback) {
        if (results == null || results.results.isEmpty()) {
            callback.run(null);
            return;
        }
        TLRPC.BotInlineResult result = results.results.get(0);
        if (result.send_message == null || TextUtils.isEmpty(result.send_message.message)) {
            callback.run(null);
            return;
        }
        String[] lines = result.send_message.message.split("\n");
        if (lines.length < 1) {
            callback.run(null);
            return;
        }
        TLRPC.TL_channel channel = new TLRPC.TL_channel();
        for (String line : lines) {
            String trimmed = line.replaceAll("\\p{C}", "").trim();
            if (trimmed.startsWith("🆔")) {
                try {
                    long id = Utilities.parseLong(trimmed.replaceAll("[^\\d-]", ""));
                    String idString = String.valueOf(id);
                    channel.id = idString.startsWith("-100") ? Long.parseLong(idString.substring(4)) : id;
                } catch (Exception ignore) {
                }
            } else if (trimmed.startsWith("📧")) {
                channel.username = trimmed.substring(trimmed.indexOf('@') + 1).trim();
            }
        }
        if (channel.id == 0) {
            callback.run(null);
            return;
        }
        if (channel.username != null) {
            resolveChannel(channel.username, resolved -> callback.run(resolved != null ? resolved : channel));
        } else {
            callback.run(channel);
        }
    }

    public MessagesController getMessagesController() {
        return MessagesController.getInstance(selectedAccount);
    }

    public MessagesStorage getMessageStorage() {
        return MessagesStorage.getInstance(selectedAccount);
    }

    public ConnectionsManager getConnectionsManager() {
        return ConnectionsManager.getInstance(selectedAccount);
    }

    public FileLoader getFileLoader() {
        return FileLoader.getInstance(selectedAccount);
    }

    public UserConfig getUserConfig() {
        return UserConfig.getInstance(selectedAccount);
    }

    public void addMessageToClipboard(MessageObject messageObject, Runnable onSuccess) {
        String path = getPathToMessage(messageObject);
        if (TextUtils.isEmpty(path)) {
            return;
        }
        SystemUtils.addFileToClipboard(new File(path), onSuccess);
    }

    public String getPathToMessage(MessageObject messageObject) {
        if (messageObject == null) {
            return null;
        }
        FileLoader fileLoader = FileLoader.getInstance(messageObject.currentAccount);
        if (messageObject.messageOwner != null && !TextUtils.isEmpty(messageObject.messageOwner.attachPath)) {
            String path = messageObject.messageOwner.attachPath;
            if (fileExists(path)) {
                return path;
            }
        }
        if (messageObject.messageOwner != null) {
            String path = fileLoader.getPathToMessage(messageObject.messageOwner).toString();
            if (fileExists(path)) {
                return path;
            }
        }
        if (messageObject.getDocument() != null) {
            String path = fileLoader.getPathToAttach(messageObject.getDocument(), false).toString();
            if (fileExists(path)) {
                return path;
            }
            path = fileLoader.getPathToAttach(messageObject.getDocument(), true).toString();
            if (fileExists(path)) {
                return path;
            }
        }
        if (messageObject.cachedQuality != null && messageObject.cachedQuality.isCached() && messageObject.cachedQuality.uri != null) {
            String path = messageObject.cachedQuality.uri.getPath();
            if (fileExists(path)) {
                return path;
            }
        }
        if (messageObject.qualityToSave != null) {
            String path = fileLoader.getPathToAttach(messageObject.qualityToSave, null, false, true).toString();
            if (fileExists(path)) {
                return path;
            }
        }
        return null;
    }

    public boolean canSaveSticker(MessageObject messageObject) {
        return MessageObject.isStickerDocument(messageObject.getDocument()) || isEmoji(messageObject.getDocument());
    }

    public boolean isEmoji(TLRPC.Document document) {
        return MessageObject.isAnimatedEmoji(document) && !MessageObject.isAnimatedStickerDocument(document, false);
    }

    public void saveStickerToGallery(Activity activity, MessageObject messageObject, Utilities.Callback<Uri> callback) {
        saveStickerToGallery(activity, getPathToMessage(messageObject), messageObject.isVideoSticker(), callback);
    }

    public void saveStickerToGallery(Activity activity, TLRPC.Document document, Utilities.Callback<Uri> callback) {
        String path = getFileLoader().getPathToAttach(document, true).toString();
        if (new File(path).exists()) {
            saveStickerToGallery(activity, path, MessageObject.isVideoSticker(document), callback);
        }
    }

    public void saveStickerToGallery(Activity activity, String path, boolean video, Utilities.Callback<Uri> callback) {
        utilsQueue.postRunnable(() -> {
            if (TextUtils.isEmpty(path)) {
                return;
            }
            try {
                FileLog.e(path);
                if (video) {
                    AndroidUtilities.runOnUIThread(() -> MediaController.saveFile(path, activity, 1, null, null, callback));
                    return;
                }
                Bitmap bitmap = BitmapFactory.decodeFile(path);
                if (bitmap != null) {
                    File file = new File(path.endsWith(".webp") ? path.replace(".webp", ".png") : path.concat(".png"));
                    try (FileOutputStream stream = new FileOutputStream(file)) {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
                    }
                    AndroidUtilities.runOnUIThread(() -> MediaController.saveFile(file.toString(), activity, 0, null, null, callback));
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public void saveGifToGallery(Activity activity, TLRPC.Document document, TLRPC.BotInlineResult result, Utilities.Callback<Uri> callback) {
        String path = getGifPath(document, result);
        if (TextUtils.isEmpty(path)) {
            return;
        }
        utilsQueue.postRunnable(() -> AndroidUtilities.runOnUIThread(() -> MediaController.saveFile(path, activity, 1, null, null, callback)));
    }

    private String getGifPath(TLRPC.Document document, TLRPC.BotInlineResult result) {
        if (document != null) {
            String path = getExistingPath(getFileLoader().getPathToAttach(document, false));
            return path != null ? path : getExistingPath(getFileLoader().getPathToAttach(document, true));
        }
        if (result == null || result.content == null) {
            return null;
        }
        WebFile webFile = WebFile.createWithWebDocument(result.content);
        String path = getExistingPath(getFileLoader().getPathToAttach(webFile, false));
        return path != null ? path : getExistingPath(getFileLoader().getPathToAttach(webFile, true));
    }

    private String getExistingPath(File file) {
        if (file == null || !file.exists()) {
            return null;
        }
        return file.toString();
    }

    public void resolveChannel(String username, Utilities.Callback<TLRPC.Chat> callback) {
        getMessagesController().getUserNameResolver().resolve(username, peerId -> {
            if (peerId != null && peerId < 0) {
                callback.run(getMessagesController().getChat(-peerId));
            } else {
                callback.run(null);
            }
        });
    }

    public boolean hasArchivedChats() {
        return getMessagesController().hasArchivedChatsActual();
    }

    public long getLikeDialog() {
        return ExteraConfig.getPreferences().getLong("channelToSave" + selectedAccount, getUserConfig().getClientUserId());
    }

    public void setLikeDialog(long dialogId) {
        ExteraConfig.getEditor().putLong("channelToSave" + selectedAccount, dialogId).apply();
    }

    private boolean isPhoneStartsWith(String prefix) {
        TLRPC.User user = UserConfig.getInstance(selectedAccount).getCurrentUser();
        if (user == null || TextUtils.isEmpty(user.phone)) {
            return false;
        }
        return user.phone.startsWith(prefix);
    }

    public boolean isRussianUser() {
        return isPhoneStartsWith("7");
    }

    public boolean isFragmentUser() {
        return isPhoneStartsWith("888");
    }

    public boolean shouldAddTimestamp(MessageObject messageObject, CharSequence text) {
        if (messageObject.messageOwner == null) {
            return false;
        }
        return (messageObject.currentEvent != null || messageObject.messageOwner.action != null) && !TextUtils.isEmpty(text);
    }

    public CharSequence addTimestamp(CharSequence text, long date, Theme.ResourcesProvider resourcesProvider) {
        String time = LocaleController.getInstance().getFormatterDay().format(date * 1000);
        SpannableStringBuilder builder = new SpannableStringBuilder(text);
        ProfileActivity.ShowDrawable drawable = ChannelAdminLogActivity.findDrawable(text);
        if (drawable == null) {
            drawable = new ProfileActivity.ShowDrawable(time);
            drawable.textDrawable.setTypeface(AndroidUtilities.bold());
            drawable.textDrawable.setTextSize(AndroidUtilities.dp(10));
            drawable.setTextColor(Theme.getThemePaint(Theme.key_paint_chatActionText, resourcesProvider).getColor());
            drawable.setBackgroundColor(0x1e000000);
        } else {
            drawable.textDrawable.setText(time, false);
        }
        drawable.setBounds(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        builder.append(" S");
        builder.setSpan(new ColoredImageSpan(drawable), builder.length() - 1, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return builder;
    }
}
