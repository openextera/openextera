package com.exteragram.messenger.export.api;

import android.text.TextUtils;
import android.util.Base64;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.export.ExportSettings;
import com.exteragram.messenger.export.output.FileManager;
import com.exteragram.messenger.export.output.html.HtmlContext;
import com.exteragram.messenger.export.output.html.HtmlWriter;
import com.exteragram.messenger.utils.chats.ChatUtils;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_keyboard;
import org.telegram.tgnet.tl.TL_stories;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.stream.Collectors;

public final class DataTypesUtils {

    private static final HashMap<Integer, Integer> APPLICATION_COLOR_INDICES = new HashMap<>();

    static {
        APPLICATION_COLOR_INDICES.put(1, 0);
        APPLICATION_COLOR_INDICES.put(7, 0);
        APPLICATION_COLOR_INDICES.put(6, 1);
        APPLICATION_COLOR_INDICES.put(21724, 1);
        APPLICATION_COLOR_INDICES.put(2834, 2);
        APPLICATION_COLOR_INDICES.put(2496, 3);
        APPLICATION_COLOR_INDICES.put(2040, 4);
        APPLICATION_COLOR_INDICES.put(1429, 5);
    }

    private DataTypesUtils() {
    }

    public static ApiWrap.DialogInfo.Type DialogTypeFromChat(ApiWrap.Chat chat) {
        if (chat == null) {
            return ApiWrap.DialogInfo.Type.Unknown;
        }
        if (chat.isMonoforum && !chat.isMonoforumAdmin) {
            return ApiWrap.DialogInfo.Type.Personal;
        }
        if (chat.isMonoforumAdmin && chat.isMonoforumOfPublicBroadcast) {
            return ApiWrap.DialogInfo.Type.PublicSupergroup;
        }
        if (chat.isMonoforumAdmin) {
            return ApiWrap.DialogInfo.Type.PrivateSupergroup;
        }
        if (!chat.username.isEmpty()) {
            return chat.isBroadcast ? ApiWrap.DialogInfo.Type.PublicChannel : ApiWrap.DialogInfo.Type.PublicSupergroup;
        }
        if (chat.isBroadcast) {
            return ApiWrap.DialogInfo.Type.PrivateChannel;
        }
        if (chat.isSupergroup) {
            return ApiWrap.DialogInfo.Type.PrivateSupergroup;
        }
        return ApiWrap.DialogInfo.Type.PrivateGroup;
    }

    public static ApiWrap.DialogInfo.Type DialogTypeFromUser(ApiWrap.User user) {
        if (user.isSelf) {
            return ApiWrap.DialogInfo.Type.Self;
        }
        if (user.isReplies) {
            return ApiWrap.DialogInfo.Type.Replies;
        }
        if (user.isVerifyCodes) {
            return ApiWrap.DialogInfo.Type.VerifyCodes;
        }
        if (user.isBot) {
            return ApiWrap.DialogInfo.Type.Bot;
        }
        return ApiWrap.DialogInfo.Type.Personal;
    }

    public static ArrayList<ApiWrap.TextPart> ParseText(String text, ArrayList<TLRPC.MessageEntity> entities) {
        int length = text.length();
        ArrayList<ApiWrap.TextPart> result = new ArrayList<>();
        int offset = 0;
        for (int i = 0; i < entities.size(); i++) {
            TLRPC.MessageEntity entity = entities.get(i);
            int start = entity.offset;
            int entityLength = entity.length;
            int end;
            if (start < offset || entityLength <= 0 || (end = entityLength + start) > length) {
                continue;
            }
            addTextPart(start, offset, text, result);

            ApiWrap.TextPart part = new ApiWrap.TextPart();
            ApiWrap.TextPart.Type type = ApiWrap.TextPart.Type.Unknown;
            if (entity instanceof TLRPC.TL_messageEntityMention) {
                type = ApiWrap.TextPart.Type.Mention;
            } else if (entity instanceof TLRPC.TL_messageEntityHashtag) {
                type = ApiWrap.TextPart.Type.Hashtag;
            } else if (entity instanceof TLRPC.TL_messageEntityBotCommand) {
                type = ApiWrap.TextPart.Type.BotCommand;
            } else if (entity instanceof TLRPC.TL_messageEntityUrl) {
                type = ApiWrap.TextPart.Type.Url;
            } else if (entity instanceof TLRPC.TL_messageEntityEmail) {
                type = ApiWrap.TextPart.Type.Email;
            } else if (entity instanceof TLRPC.TL_messageEntityBold) {
                type = ApiWrap.TextPart.Type.Bold;
            } else if (entity instanceof TLRPC.TL_messageEntityItalic) {
                type = ApiWrap.TextPart.Type.Italic;
            } else if (entity instanceof TLRPC.TL_messageEntityCode) {
                type = ApiWrap.TextPart.Type.Code;
            } else if (entity instanceof TLRPC.TL_messageEntityPre) {
                type = ApiWrap.TextPart.Type.Pre;
            } else if (entity instanceof TLRPC.TL_messageEntityTextUrl) {
                type = ApiWrap.TextPart.Type.TextUrl;
            } else if (entity instanceof TLRPC.TL_messageEntityMentionName || entity instanceof TLRPC.TL_inputMessageEntityMentionName) {
                type = ApiWrap.TextPart.Type.MentionName;
            } else if (entity instanceof TLRPC.TL_messageEntityPhone) {
                type = ApiWrap.TextPart.Type.Phone;
            } else if (entity instanceof TLRPC.TL_messageEntityCashtag) {
                type = ApiWrap.TextPart.Type.Cashtag;
            } else if (entity instanceof TLRPC.TL_messageEntityUnderline) {
                type = ApiWrap.TextPart.Type.Underline;
            } else if (entity instanceof TLRPC.TL_messageEntityStrike) {
                type = ApiWrap.TextPart.Type.Strike;
            } else if (entity instanceof TLRPC.TL_messageEntityBlockquote) {
                type = ApiWrap.TextPart.Type.Blockquote;
            } else if (entity instanceof TLRPC.TL_messageEntityBankCard) {
                type = ApiWrap.TextPart.Type.BankCard;
            } else if (entity instanceof TLRPC.TL_messageEntitySpoiler) {
                type = ApiWrap.TextPart.Type.Spoiler;
            } else if (entity instanceof TLRPC.TL_messageEntityCustomEmoji) {
                type = ApiWrap.TextPart.Type.CustomEmoji;
            }
            part.type = type;
            part.text = text.substring(start, end);

            String additional;
            if (entity instanceof TLRPC.TL_messageEntityPre) {
                additional = ((TLRPC.TL_messageEntityPre) entity).language;
            } else if (entity instanceof TLRPC.TL_messageEntityTextUrl) {
                additional = ((TLRPC.TL_messageEntityTextUrl) entity).url;
            } else if (entity instanceof TLRPC.TL_messageEntityMentionName) {
                additional = String.valueOf(((TLRPC.TL_messageEntityMentionName) entity).user_id);
            } else if (entity instanceof TLRPC.TL_messageEntityCustomEmoji) {
                additional = String.valueOf(((TLRPC.TL_messageEntityCustomEmoji) entity).document_id);
            } else if (entity instanceof TLRPC.TL_messageEntityBlockquote && ((TLRPC.TL_messageEntityBlockquote) entity).collapsed) {
                additional = "1";
            } else {
                additional = "";
            }
            part.additional = additional;
            result.add(part);
            offset = end;
        }
        addTextPart(length, offset, text, result);
        return result;
    }

    private static void addTextPart(int till, int offset, String text, ArrayList<ApiWrap.TextPart> result) {
        if (till > offset) {
            ApiWrap.TextPart part = new ApiWrap.TextPart();
            part.text = text.substring(offset, till);
            result.add(part);
        }
    }

    public static int SettingsFromDialogsType(ApiWrap.DialogInfo.Type type) {
        switch (type) {
            case Self:
            case Personal:
                return 32;
            case Bot:
                return 64;
            case PrivateGroup:
            case PrivateSupergroup:
                return 128;
            case PublicSupergroup:
                return 256;
            case PrivateChannel:
                return 512;
            case PublicChannel:
                return 1024;
            default:
                return 0;
        }
    }

    public static boolean AddMigrateFromSlice(ApiWrap.DialogInfo to, ApiWrap.DialogInfo from, int splitIndex, int splitsCount) {
        TLRPC.InputPeer migratedFrom = to.migratedFromInput;
        if (!(migratedFrom instanceof TLRPC.TL_inputPeerEmpty) && (!(migratedFrom instanceof TLRPC.TL_inputPeerChat) || from.peerId != ((TLRPC.TL_inputPeerChat) migratedFrom).chat_id)) {
            return false;
        }
        for (int i = 0; i != from.splits.size(); i++) {
            to.splits.add(from.splits.get(i) - splitsCount);
            to.messagesCountPerSplit.add(from.messagesCountPerSplit.get(i));
        }
        to.migratedFromInput = from.input;
        to.splits.add(splitIndex - splitsCount);
        to.messagesCountPerSplit.add(0);
        return true;
    }

    public static ApiWrap.ExportPersonalInfo ParsePersonalInfo(TLRPC.TL_users_userFull data) {
        ApiWrap.ExportPersonalInfo result = new ApiWrap.ExportPersonalInfo();
        result.user = ParseUser(data.users.get(0));
        TLRPC.UserFull fullUser = data.full_user;
        if (fullUser instanceof TLRPC.TL_userFull && fullUser.about != null) {
            result.bio = fullUser.about;
        }
        return result;
    }

    public static ApiWrap.DialogsInfo ParseLeftChannelsInfo(TLRPC.messages_Chats data) {
        ApiWrap.DialogsInfo result = new ApiWrap.DialogsInfo();
        for (int i = 0; i < data.chats.size(); i++) {
            ApiWrap.DialogInfo info = DialogInfoFromChat(ParseChat(data.chats.get(i)));
            info.isLeftChannel = true;
            result.left.add(info);
        }
        return result;
    }

    public static ApiWrap.DialogInfo DialogInfoFromUser(ApiWrap.User user) {
        ApiWrap.DialogInfo result = new ApiWrap.DialogInfo();
        TLRPC.TL_inputPeerUser inputPeer = new TLRPC.TL_inputPeerUser();
        result.input = inputPeer;
        inputPeer.user_id = user.input.user_id;
        inputPeer.access_hash = user.input.access_hash;
        result.name = user.info.firstName;
        result.lastName = user.info.lastName;
        result.peerId = user.id;
        result.topMessageDate = 0;
        result.topMessageId = 0;
        result.type = DialogTypeFromUser(user);
        result.isLeftChannel = false;
        return result;
    }

    public static ApiWrap.DialogInfo DialogInfoFromChat(ApiWrap.Chat chat) {
        ApiWrap.DialogInfo result = new ApiWrap.DialogInfo();
        result.input = chat.input;
        result.name = chat.title;
        result.peerId = chat.bareId;
        result.topMessageDate = 0;
        result.topMessageId = 0;
        result.type = DialogTypeFromChat(chat);
        result.migratedToChannelId = chat.migratedToChannelId;
        result.isMonoforum = chat.isMonoforum;
        if (chat.isMonoforumAdmin) {
            result.monoforumBroadcastInput = chat.monoforumBroadcastInput;
        }
        return result;
    }

    public static ApiWrap.Chat ParseChat(TLRPC.Chat data) {
        ApiWrap.Chat result = new ApiWrap.Chat();
        if (data instanceof TLRPC.TL_chat) {
            TLRPC.TL_chat chat = (TLRPC.TL_chat) data;
            result.bareId = chat.id;
            result.title = chat.title;
            TLRPC.TL_inputPeerChat inputPeer = new TLRPC.TL_inputPeerChat();
            result.input = inputPeer;
            inputPeer.chat_id = result.bareId;
            if (chat.migrated_to instanceof TLRPC.TL_inputChannel) {
                result.migratedToChannelId = ((TLRPC.TL_inputChannel) chat.migrated_to).channel_id;
            }
        } else if (data instanceof TLRPC.TL_chatEmpty) {
            TLRPC.TL_chatEmpty chat = (TLRPC.TL_chatEmpty) data;
            result.bareId = chat.id;
            TLRPC.TL_inputPeerChat inputPeer = new TLRPC.TL_inputPeerChat();
            result.input = inputPeer;
            inputPeer.chat_id = chat.id;
        } else if (data instanceof TLRPC.TL_chatForbidden) {
            TLRPC.TL_chatForbidden chat = (TLRPC.TL_chatForbidden) data;
            result.bareId = chat.id;
            result.title = chat.title;
            TLRPC.TL_inputPeerChat inputPeer = new TLRPC.TL_inputPeerChat();
            result.input = inputPeer;
            inputPeer.chat_id = chat.id;
        } else if (data instanceof TLRPC.TL_channel) {
            TLRPC.TL_channel channel = (TLRPC.TL_channel) data;
            result.bareId = channel.id;
            int colorIndex;
            if (channel.color == null || (colorIndex = channel.color.color) == 0) {
                colorIndex = PeerColorIndex(channel.id);
            }
            result.colorIndex = colorIndex;
            result.isMonoforum = channel.monoforum;
            result.isBroadcast = channel.broadcast;
            result.isSupergroup = channel.megagroup;
            result.hasMonoforumAdminRights = channel.broadcast && (data.creator || (data.admin_rights != null && data.admin_rights.manage_direct_messages));
            result.monoforumLinkId = data.linked_monoforum_id;
            result.title = channel.title;
            if (channel.username != null && !channel.username.isEmpty()) {
                result.username = channel.username;
            }
            TLRPC.TL_inputPeerChannel inputPeer = new TLRPC.TL_inputPeerChannel();
            result.input = inputPeer;
            inputPeer.channel_id = channel.id;
            inputPeer.access_hash = channel.access_hash;
        } else if (data instanceof TLRPC.TL_channelForbidden) {
            TLRPC.TL_channelForbidden channel = (TLRPC.TL_channelForbidden) data;
            result.bareId = channel.id;
            result.isBroadcast = channel.broadcast;
            result.isSupergroup = channel.megagroup;
            result.title = channel.title;
            TLRPC.TL_inputPeerChannel inputPeer = new TLRPC.TL_inputPeerChannel();
            result.input = inputPeer;
            inputPeer.channel_id = channel.id;
            inputPeer.access_hash = channel.access_hash;
        }
        return result;
    }

    public static int PeerColorIndex(long peerId) {
        return ((int) peerId) % 7;
    }

    public static long StringBarePeerId(String data) {
        long hash = 255;
        for (int i = 0; i < data.length(); i++) {
            hash = ((hash * 239) + data.charAt(i)) & 255;
        }
        return hash;
    }

    public static void FinalizeDialogsInfo(ApiWrap.DialogsInfo info, ExportSettings settings) {
        ArrayList<ApiWrap.DialogInfo> chats = info.chats;
        ArrayList<ApiWrap.DialogInfo> left = info.left;
        int digits = NumberToString(String.valueOf((chats.size() + left.size()) - 1), 0, '0').length();
        int index = 0;
        for (int i = 0; i < chats.size(); i++) {
            ApiWrap.DialogInfo dialog = chats.get(i);
            index++;
            String number = NumberToString(String.valueOf(index), digits, '0');
            dialog.relativePath = settings.onlySinglePeer() ? "" : "chats/chat_" + number + '/';
            ApiWrap.DialogInfo.Type type = dialog.type;
            dialog.onlyMyMessages = type != ApiWrap.DialogInfo.Type.Personal && (SettingsFromDialogsType(type) & 96) != SettingsFromDialogsType(dialog.type);
            Collections.sort(dialog.splits);
        }
        for (int i = 0; i < left.size(); i++) {
            ApiWrap.DialogInfo dialog = left.get(i);
            index++;
            dialog.relativePath = "chats/chat_" + NumberToString(String.valueOf(index), digits, '0') + "/";
            dialog.onlyMyMessages = true;
        }
    }

    public static String NumberToString(long value) {
        return NumberToString(value + "", 0, '0');
    }

    public static String NumberToString(int value) {
        return NumberToString(value + "", 0, '0');
    }

    public static String NumberToString(String value, int length, char filler) {
        return FillLeft(value, length, filler).replace(',', '.');
    }

    private static String FillLeft(String data, int length, char filler) {
        if (length <= data.length()) {
            return data;
        }
        StringBuilder sb = new StringBuilder();
        int count = length - data.length();
        for (int i = 0; i != count; i++) {
            sb.append(filler);
        }
        sb.append(data);
        return sb.toString();
    }

    public static String TypeString(ApiWrap.DialogInfo.Type type) {
        switch (type) {
            case Self:
            case Personal:
            case Replies:
            case VerifyCodes:
                return "private";
            case Bot:
                return "bot";
            case PrivateGroup:
            case PrivateSupergroup:
            case PublicSupergroup:
                return "group";
            case PrivateChannel:
            case PublicChannel:
                return "channel";
            case Unknown:
                return "unknown";
            default:
                throw new IncompatibleClassChangeError();
        }
    }

    public static String DeletedString(ApiWrap.DialogInfo.Type type) {
        switch (type) {
            case Self:
            case Personal:
            case Bot:
            case Unknown:
            case Replies:
            case VerifyCodes:
                return "Deleted Account";
            case PrivateGroup:
            case PrivateSupergroup:
            case PublicSupergroup:
                return "Deleted Group";
            case PrivateChannel:
            case PublicChannel:
                return "Deleted Channel";
            default:
                throw new IncompatibleClassChangeError();
        }
    }

    public static String CountString(int count, boolean outgoing) {
        if (count == 1) {
            return outgoing ? "1 outgoing message" : "1 message";
        }
        if (count == 0) {
            return outgoing ? "No outgoing messages" : "No messages";
        }
        return NumberToString(String.valueOf(count), 0, ' ') + (outgoing ? " outgoing messages" : " messages");
    }

    public static boolean SingleMessageAfter(TLRPC.messages_Messages data, int date) {
        int singleMessageDate = SingleMessageDate(data);
        return singleMessageDate > 0 && singleMessageDate > date;
    }

    public static int SingleMessageDate(TLRPC.messages_Messages data) {
        if (data instanceof TLRPC.TL_messages_messagesNotModified) {
            return 0;
        }
        ArrayList<TLRPC.Message> messages = data.messages;
        if (messages.isEmpty() || messages.get(0) instanceof TLRPC.TL_messageEmpty) {
            return 0;
        }
        return messages.get(0).date;
    }

    public static ApiWrap.Message ParseMessage(ApiWrap.ParseMediaContext context, TLRPC.Message data, String mediaFolder) {
        ApiWrap.Message result = new ApiWrap.Message();
        result.id = data.id;
        if (!(data instanceof TLRPC.TL_messageEmpty)) {
            result.date = data.date;
            result.out = data.out;
            result.selfId = context.selfPeerId;
            result.peerId = MessageObject.getPeerId(data.peer_id);
            if (data.from_id != null) {
                result.fromId = MessageObject.getPeerId(data.from_id);
            } else {
                result.fromId = result.peerId;
            }
            if (data.reply_to instanceof TLRPC.TL_messageReplyHeader) {
                TLRPC.TL_messageReplyHeader replyHeader = (TLRPC.TL_messageReplyHeader) data.reply_to;
                if (replyHeader.reply_to_msg_id != 0) {
                    result.replyToMsgId = replyHeader.reply_to_msg_id;
                    result.replyToPeerId = replyHeader.reply_to_peer_id != null ? MessageObject.getPeerId(replyHeader.reply_to_peer_id) : 0L;
                    if (result.replyToPeerId == result.peerId) {
                        result.replyToPeerId = 0L;
                    }
                }
            }
        }
        if (data instanceof TLRPC.TL_message) {
            TLRPC.TL_message message = (TLRPC.TL_message) data;
            if (message.fwd_from instanceof TLRPC.TL_messageFwdHeader) {
                TLRPC.TL_messageFwdHeader fwdHeader = (TLRPC.TL_messageFwdHeader) message.fwd_from;
                String fromName = (fwdHeader.from_name == null || fwdHeader.from_name.isEmpty()) ? "" : fwdHeader.from_name;
                boolean forwarded = MessageObject.getPeerId(fwdHeader.from_id) != 0 || !fromName.isEmpty();
                result.forwarded = forwarded;
                result.forwardedDate = fwdHeader.date;
                result.showForwardedAsOriginal = forwarded && MessageObject.getPeerId(fwdHeader.saved_from_id) != 0;
                result.savedFromChatId = MessageObject.getPeerId(fwdHeader.saved_from_id);
                result.forwardedFromName = fromName;
            }
            if (data.post_author != null) {
                result.signature = data.post_author;
            }
            if (data.reply_to instanceof TLRPC.TL_messageReplyHeader) {
                TLRPC.TL_messageReplyHeader replyHeader = (TLRPC.TL_messageReplyHeader) data.reply_to;
                if (replyHeader.reply_to_msg_id != 0) {
                    result.replyToMsgId = replyHeader.reply_to_msg_id;
                    result.replyToPeerId = replyHeader.reply_to_peer_id != null ? MessageObject.getPeerId(replyHeader.reply_to_peer_id) : 0L;
                }
            }
            if (message.via_bot_id != 0) {
                result.viaBotId = message.via_bot_id;
            }
            if (message.media != null) {
                result.media = ParseMedia(context, message.media, mediaFolder, result.date);
            }
            if (message.reply_markup instanceof TLRPC.TL_replyKeyboardMarkup) {
                result.inlineButtonRows = ButtonRowsFromTL((TLRPC.TL_replyKeyboardMarkup) message.reply_markup);
            }
            result.text = ParseText(message.message, message.entities);
            return result;
        }
        if (data instanceof TLRPC.TL_messageService) {
            TLRPC.TL_messageService service = (TLRPC.TL_messageService) data;
            TLRPC.MessageAction action = service.action;
            if (action instanceof TLRPC.TL_messageActionSuggestProfilePhoto) {
                TLRPC.Photo photo = ((TLRPC.TL_messageActionSuggestProfilePhoto) action).photo;
                String path = mediaFolder + "photos/" + PreparePhotoFileName(++context.photos, data.date);
                result.parsedAction = new ApiWrap.ActionSuggestProfilePhoto(ParsePhoto(photo, path));
            } else if (action instanceof TLRPC.TL_messageActionChatEditPhoto) {
                TLRPC.Photo photo = ((TLRPC.TL_messageActionChatEditPhoto) action).photo;
                String path = mediaFolder + "photos/" + PreparePhotoFileName(++context.photos, data.date);
                result.parsedAction = new ApiWrap.ActionChatEditPhoto(ParsePhoto(photo, path));
            }
            if (service.action != null) {
                result.action = service.action;
            }
        }
        return result;
    }

    private static ApiWrap.Media ParseMedia(ApiWrap.ParseMediaContext context, TLRPC.MessageMedia data, String folder, int date) {
        ApiWrap.Media result = new ApiWrap.Media();
        if (data instanceof TLRPC.TL_messageMediaPhoto) {
            TLRPC.Photo photoData = ((TLRPC.TL_messageMediaPhoto) data).photo;
            HtmlWriter.Photo photo;
            if (photoData != null) {
                photo = ParsePhoto(photoData, folder + "photos/" + PreparePhotoFileName(++context.photos, date));
            } else {
                photo = new HtmlWriter.Photo();
            }
            photo.spoilered = data.spoiler;
            if (data.ttl_seconds != 0) {
                result.ttl = data.ttl_seconds;
                photo.image.file = new ApiWrap.File();
            }
            result.content = photo;
            return result;
        }
        if (data instanceof TLRPC.TL_messageMediaGeo) {
            result.content = parseGeoPoint(data.geo);
            return result;
        }
        if (data instanceof TLRPC.TL_messageMediaContact) {
            TLRPC.TL_messageMediaContact contact = (TLRPC.TL_messageMediaContact) data;
            ApiWrap.SharedContact sharedContact = new ApiWrap.SharedContact();
            sharedContact.info.userId = contact.user_id;
            sharedContact.info.firstName = data.first_name;
            sharedContact.info.lastName = data.last_name;
            sharedContact.info.phoneNumber = data.phone_number;
            if (contact.vcard != null && !contact.vcard.isEmpty()) {
                sharedContact.vcard = new ApiWrap.File();
                sharedContact.vcard.content = contact.vcard.getBytes(StandardCharsets.UTF_8);
                sharedContact.vcard.size = contact.vcard.length();
                sharedContact.vcard.suggestedPath = folder + "contacts/contact_" + (++context.contacts) + ".vcard";
            }
            result.content = sharedContact;
            return result;
        }
        if (data instanceof TLRPC.TL_messageMediaUnsupported) {
            return result;
        }
        if (data instanceof TLRPC.TL_messageMediaDocument) {
            TLRPC.TL_messageMediaDocument mediaDocument = (TLRPC.TL_messageMediaDocument) data;
            ApiWrap.Document document;
            if (mediaDocument.document != null) {
                document = ParseDocument(context, mediaDocument.document, folder, date);
            } else {
                document = new ApiWrap.Document();
            }
            if (mediaDocument.ttl_seconds != 0) {
                result.ttl = mediaDocument.ttl_seconds;
                document.file = new ApiWrap.File();
            }
            document.spoilered = mediaDocument.spoiler;
            result.content = document;
            return result;
        }
        if (data instanceof TLRPC.TL_messageMediaVenue) {
            TLRPC.TL_messageMediaVenue venueData = (TLRPC.TL_messageMediaVenue) data;
            ApiWrap.Venue venue = new ApiWrap.Venue();
            venue.point = parseGeoPoint(venueData.geo);
            venue.title = venueData.title;
            venue.address = venueData.address;
            result.content = venue;
            return result;
        }
        if (data instanceof TLRPC.TL_messageMediaGame) {
            ApiWrap.Game game = new ApiWrap.Game();
            TLRPC.TL_game gameData = ((TLRPC.TL_messageMediaGame) data).game;
            if (gameData != null) {
                game.id = gameData.id;
                game.title = gameData.title;
                game.description = gameData.description;
                game.shortName = gameData.short_name;
                game.botId = context.botId;
            }
            result.content = game;
            return result;
        }
        if (data instanceof TLRPC.TL_messageMediaInvoice) {
            TLRPC.TL_messageMediaInvoice invoiceData = (TLRPC.TL_messageMediaInvoice) data;
            ApiWrap.Invoice invoice = new ApiWrap.Invoice();
            invoice.title = invoiceData.title;
            invoice.description = invoiceData.description;
            invoice.currency = invoiceData.currency;
            invoice.amount = invoiceData.total_amount;
            if (invoiceData.receipt_msg_id != 0) {
                invoice.receiptMsgId = invoiceData.receipt_msg_id;
            }
            result.content = invoice;
            return result;
        }
        if (data instanceof TLRPC.TL_messageMediaGeoLive) {
            TLRPC.TL_messageMediaGeoLive geoLive = (TLRPC.TL_messageMediaGeoLive) data;
            result.content = parseGeoPoint(geoLive.geo);
            result.ttl = geoLive.period;
            return result;
        }
        if (data instanceof TLRPC.TL_messageMediaPoll) {
            TLRPC.TL_messageMediaPoll pollData = (TLRPC.TL_messageMediaPoll) data;
            ApiWrap.Poll poll = new ApiWrap.Poll();
            if (pollData.poll instanceof TLRPC.TL_poll) {
                TLRPC.TL_poll tlPoll = (TLRPC.TL_poll) pollData.poll;
                poll.id = tlPoll.id;
                poll.question = tlPoll.question.text;
                poll.closed = tlPoll.closed;
                HashMap<String, TLRPC.PollAnswerVoters> votersByOption = new HashMap<>();
                if (pollData.results instanceof TLRPC.TL_pollResults) {
                    TLRPC.TL_pollResults results = (TLRPC.TL_pollResults) pollData.results;
                    if (results.results != null) {
                        poll.totalVotes = results.total_voters;
                        for (int i = 0; i < results.results.size(); i++) {
                            TLRPC.PollAnswerVoters voters = results.results.get(i);
                            votersByOption.put(Base64.encodeToString(voters.option, Base64.DEFAULT), voters);
                        }
                    }
                }
                for (int i = 0; i < tlPoll.answers.size(); i++) {
                    TLRPC.PollAnswer answer = tlPoll.answers.get(i);
                    if (answer instanceof TLRPC.TL_pollAnswer) {
                        TLRPC.TL_pollAnswer pollAnswer = (TLRPC.TL_pollAnswer) answer;
                        byte[] option = pollAnswer.option;
                        TLRPC.PollAnswerVoters voters = votersByOption.get(Base64.encodeToString(option, Base64.DEFAULT));
                        int votes = 0;
                        boolean chosen = false;
                        if (voters != null) {
                            votes = voters.voters;
                            chosen = voters.chosen;
                        }
                        poll.answers.add(new ApiWrap.Poll.Answer(pollAnswer.text.text, option, votes, chosen));
                    }
                }
            }
            result.content = poll;
            return result;
        }
        if (data instanceof TLRPC.TL_messageMediaGiveaway) {
            result.content = parseGiveaway((TLRPC.TL_messageMediaGiveaway) data);
            return result;
        }
        if (data instanceof TLRPC.TL_messageMediaGiveawayResults) {
            result.content = parseGiveaway((TLRPC.TL_messageMediaGiveawayResults) data);
            return result;
        }
        if (data instanceof TLRPC.TL_messageMediaPaidMedia) {
            TLRPC.TL_messageMediaPaidMedia paidMediaData = (TLRPC.TL_messageMediaPaidMedia) data;
            ApiWrap.PaidMedia paidMedia = new ApiWrap.PaidMedia();
            paidMedia.stars = paidMediaData.stars_amount;
            for (int i = 0; i < paidMediaData.extended_media.size(); i++) {
                TLRPC.MessageExtendedMedia extendedMedia = paidMediaData.extended_media.get(i);
                if (extendedMedia instanceof TLRPC.TL_messageExtendedMediaPreview) {
                    paidMedia.extended.add(new ApiWrap.Media());
                } else if (extendedMedia instanceof TLRPC.TL_messageExtendedMedia) {
                    paidMedia.extended.add(ParseMedia(context, ((TLRPC.TL_messageExtendedMedia) extendedMedia).media, folder, date));
                }
            }
            result.content = paidMedia;
        }
        return result;
    }

    private static ApiWrap.GiveawayStart parseGiveaway(TLRPC.TL_messageMediaGiveaway data) {
        ApiWrap.GiveawayStart result = new ApiWrap.GiveawayStart(data.until_date, data.stars, data.quantity, data.months, !data.only_new_subscribers);
        result.channels.addAll(data.channels);
        if (!data.countries_iso2.isEmpty()) {
            result.countries.addAll(data.countries_iso2);
        }
        if (data.prize_description != null && !data.prize_description.isEmpty()) {
            result.additionalPrize = data.prize_description;
        }
        return result;
    }

    private static ApiWrap.GiveawayResults parseGiveaway(TLRPC.TL_messageMediaGiveawayResults data) {
        ApiWrap.GiveawayResults result = new ApiWrap.GiveawayResults(data.channel_id, data.until_date, data.launch_msg_id, data.additional_peers_count, data.winners_count, data.unclaimed_count, data.months, data.stars, data.refunded, data.only_new_subscribers);
        result.winners.addAll(data.winners);
        if (data.prize_description != null && !data.prize_description.isEmpty()) {
            result.additionalPrize = data.prize_description;
        }
        return result;
    }

    private static ApiWrap.GeoPoint parseGeoPoint(TLRPC.GeoPoint data) {
        ApiWrap.GeoPoint result = new ApiWrap.GeoPoint();
        if (data instanceof TLRPC.TL_geoPoint) {
            TLRPC.TL_geoPoint point = (TLRPC.TL_geoPoint) data;
            result.latitude = point.lat;
            result.longitude = point._long;
            result.valid = true;
        }
        return result;
    }

    public static ApiWrap.User EmptyUser(long userId) {
        TLRPC.TL_userEmpty user = new TLRPC.TL_userEmpty();
        user.id = userId;
        return ParseUser(user);
    }

    public static ApiWrap.Chat EmptyChat(long chatId) {
        TLRPC.TL_chatEmpty chat = new TLRPC.TL_chatEmpty();
        chat.id = chatId;
        return ParseChat(chat);
    }

    public static ApiWrap.Peer EmptyPeer(TLRPC.Peer peer) {
        if (peer.user_id != 0) {
            return new ApiWrap.Peer(EmptyUser(peer.user_id));
        }
        if (peer.chat_id != 0) {
            return new ApiWrap.Peer(EmptyChat(peer.chat_id));
        }
        if (peer.channel_id != 0) {
            return new ApiWrap.Peer(EmptyChat(peer.channel_id));
        }
        throw new IllegalArgumentException("PeerId in EmptyPeer: " + ExteraConfig.getGSON().toJson(peer));
    }

    public static HashMap<Long, ApiWrap.Peer> ParsePeersLists(ArrayList<TLRPC.User> users, ArrayList<TLRPC.Chat> chats) {
        LinkedHashMap<Long, ApiWrap.Peer> result = new LinkedHashMap<>();
        for (int i = 0; i < users.size(); i++) {
            ApiWrap.User user = ParseUser(users.get(i));
            result.put(user.info.userId, new ApiWrap.Peer(user));
        }
        for (int i = 0; i < chats.size(); i++) {
            ApiWrap.Chat chat = ParseChat(chats.get(i));
            result.put(chat.bareId, new ApiWrap.Peer(chat));
        }
        for (ApiWrap.Peer peer : result.values()) {
            ApiWrap.Chat chat = peer.chat;
            if (chat == null || !chat.isMonoforum) {
                continue;
            }
            ApiWrap.Peer linked = result.get(chat.monoforumLinkId);
            if (linked != null) {
                chat.isMonoforumAdmin = linked.chat.hasMonoforumAdminRights;
                chat.isMonoforumOfPublicBroadcast = !TextUtils.isEmpty(linked.chat.username);
            }
        }
        return result;
    }

    public static HashMap<Long, ApiWrap.User> ParseUsersList(ArrayList<TLRPC.User> users) {
        LinkedHashMap<Long, ApiWrap.User> result = new LinkedHashMap<>();
        for (int i = 0; i < users.size(); i++) {
            ApiWrap.User user = ParseUser(users.get(i));
            result.put(user.info.userId, user);
        }
        return result;
    }

    public static ApiWrap.WebSession ParseWebSession(TLRPC.TL_webAuthorization data, HashMap<Long, ApiWrap.User> users) {
        String botUsername = null;
        if (users != null) {
            ApiWrap.User bot = users.get(data.bot_id);
            if (bot != null) {
                botUsername = bot.username;
            }
        }
        if (botUsername == null) {
            botUsername = "";
        }
        return new ApiWrap.WebSession(botUsername, data.domain, data.browser, data.platform, data.date_created, data.date_active, data.ip, data.region);
    }

    public static ApiWrap.SessionsList ParseSessionsList(TL_account.authorizations data) {
        ApiWrap.SessionsList result = new ApiWrap.SessionsList();
        result.list.addAll(data.authorizations);
        return result;
    }

    public static ApiWrap.SessionsList ParseWebSessionsList(TL_account.webAuthorizations data) {
        ApiWrap.SessionsList result = new ApiWrap.SessionsList();
        HashMap<Long, ApiWrap.User> users = ParseUsersList(data.users);
        for (int i = 0; i < data.authorizations.size(); i++) {
            result.webList.add(ParseWebSession(data.authorizations.get(i), users));
        }
        return result;
    }

    public static ApiWrap.MessagesSlice ParseMessagesSlice(ApiWrap.ParseMediaContext context, ArrayList<TLRPC.Message> messages, ArrayList<TLRPC.User> users, ArrayList<TLRPC.Chat> chats, String mediaFolder) {
        ApiWrap.MessagesSlice result = new ApiWrap.MessagesSlice();
        for (int i = messages.size() - 1; i >= 0; i--) {
            result.list.add(ParseMessage(context, messages.get(i), mediaFolder));
        }
        result.peers = ParsePeersLists(users, chats);
        return result;
    }

    public static ApiWrap.Document ParseDocument(ApiWrap.ParseMediaContext context, TLRPC.Document data, String folder, int date) {
        ApiWrap.Document result = new ApiWrap.Document();
        if (data instanceof TLRPC.TL_document) {
            TLRPC.TL_document document = (TLRPC.TL_document) data;
            result.id = document.id;
            result.date = document.date;
            result.mime = document.mime_type;
            ParseAttributes(result, document.attributes);

            result.file = new ApiWrap.File();
            result.file.size = document.size;
            result.file.dcId = document.dc_id;

            TLRPC.TL_inputDocumentFileLocation location = new TLRPC.TL_inputDocumentFileLocation();
            location.id = document.id;
            location.access_hash = document.access_hash;
            location.file_reference = document.file_reference;
            location.thumb_size = "";
            result.file.location = new ApiWrap.FileLocation();
            result.file.location.data = location;
            result.file.location.dcId = document.dc_id;
            result.file.suggestedPath = folder + DocumentFolder(result) + "/" + FileManager.fileNameFromUserString(ComputeDocumentName(context, document, date, result.name));

            result.thumb = ParseDocumentThumb(document, result.file.suggestedPath);
            if (MessageObject.isStickerDocument(document)) {
                result.sticker = document;
            }
        } else if (data instanceof TLRPC.TL_documentEmpty) {
            result.id = data.id;
        }
        return result;
    }

    public static boolean RefreshFileReference(TLRPC.InputFileLocation to, TLRPC.InputFileLocation from) {
        if (to.getClass() != from.getClass()) {
            return false;
        }
        if (!(to instanceof TLRPC.TL_inputPhotoFileLocation) && !(to instanceof TLRPC.TL_inputDocumentFileLocation)) {
            return false;
        }
        if (to.id != from.id || !Objects.equals(to.thumb_size, from.thumb_size)) {
            return false;
        }
        to.file_reference = from.file_reference;
        return true;
    }

    public static void ParseAttributes(ApiWrap.Document result, ArrayList<TLRPC.DocumentAttribute> attributes) {
        for (int i = 0; i < attributes.size(); i++) {
            TLRPC.DocumentAttribute attribute = attributes.get(i);
            if (attribute instanceof TLRPC.TL_documentAttributeImageSize) {
                result.width = attribute.w;
                result.height = attribute.h;
            } else if (attribute instanceof TLRPC.TL_documentAttributeAnimated) {
                result.isAnimated = true;
            } else if (attribute instanceof TLRPC.TL_documentAttributeSticker) {
                result.isSticker = true;
                result.stickerEmoji = attribute.alt;
            } else if (attribute instanceof TLRPC.TL_documentAttributeCustomEmoji) {
                result.isSticker = true;
                result.stickerEmoji = attribute.alt;
            } else if (attribute instanceof TLRPC.TL_documentAttributeVideo) {
                if (attribute.round_message) {
                    result.isVideoMessage = true;
                } else {
                    result.isVideoFile = true;
                }
                result.width = attribute.w;
                result.height = attribute.h;
                result.duration = (int) attribute.duration;
            } else if (attribute instanceof TLRPC.TL_documentAttributeAudio) {
                if (attribute.voice) {
                    result.isVoiceMessage = true;
                } else {
                    result.isAudioFile = true;
                }
                if (attribute.performer != null && !attribute.performer.isEmpty()) {
                    result.songPerformer = attribute.performer;
                }
                if (attribute.title != null && !attribute.title.isEmpty()) {
                    result.songTitle = attribute.title;
                }
                result.duration = (int) attribute.duration;
            } else if (attribute instanceof TLRPC.TL_documentAttributeFilename) {
                result.name = attribute.file_name;
            }
        }
    }

    public static String ComputeDocumentName(ApiWrap.ParseMediaContext context, TLRPC.Document document, int date, String name) {
        if (document == null) {
            throw new IllegalArgumentException("trying to pass null document!!!");
        }
        if (name != null && !name.isEmpty()) {
            return name;
        }
        String extension = getExtensionFromMime(document.mime_type, document);
        if (MessageObject.isVoiceDocument(document)) {
            boolean isMp3 = document.mime_type.equalsIgnoreCase("audio/mp3");
            return "audio" + (++context.audios) + PrepareFileNameDatePart(date) + (isMp3 ? ".ogg" : ".mp3");
        }
        if (MessageObject.isVideoDocument(document)) {
            if (extension.isEmpty()) {
                extension = ".mov";
            }
            return "video_" + (++context.videos) + PrepareFileNameDatePart(date) + extension;
        }
        if (extension.isEmpty()) {
            extension = ".unknown";
        }
        return "file_" + (++context.files) + PrepareFileNameDatePart(date) + extension;
    }

    private static String getExtensionFromMime(String mime, TLRPC.Document document) {
        if (Objects.equals(mime, "image/webp")) {
            return ".webp";
        }
        if (Objects.equals(mime, "application/x-tgsticker")) {
            return ".tgs";
        }
        if (Objects.equals(mime, "application/x-tgwallpattern")) {
            return ".tgv";
        }
        if (Objects.equals(mime, "application/x-tdesktop-theme") || Objects.equals(mime, "application/x-tgtheme-tdesktop")) {
            return ".tdesktop-theme";
        }
        if (Objects.equals(mime, "application/x-tdesktop-palette")) {
            return ".tdesktop-palette";
        }
        if (Objects.equals(mime, "video/mp4")) {
            return ".mp4";
        }
        if (Objects.equals(mime, "audio/ogg")) {
            return ".ogg";
        }
        return ".unknown";
    }

    private static String DocumentFolder(ApiWrap.Document document) {
        if (document.isVideoFile) {
            return "video_files";
        }
        if (document.isAnimated) {
            return "animations";
        }
        if (document.isSticker) {
            return "stickers";
        }
        if (document.isVoiceMessage) {
            return "voice_messages";
        }
        if (document.isVideoMessage) {
            return "round_video_messages";
        }
        return "files";
    }

    private static boolean isEmptyPhotoSize(TLRPC.PhotoSize size) {
        return size instanceof TLRPC.TL_photoSizeEmpty || size instanceof TLRPC.TL_photoStrippedSize || size instanceof TLRPC.TL_photoPathSize;
    }

    private static long getArea(TLRPC.PhotoSize size) {
        if (isEmptyPhotoSize(size)) {
            return 0L;
        }
        return ((long) size.w) * ((long) size.h);
    }

    private static ApiWrap.Image ParseDocumentThumb(TLRPC.Document document, String documentPath) {
        ArrayList<TLRPC.PhotoSize> thumbs = document.thumbs;
        if (thumbs.isEmpty()) {
            return new ApiWrap.Image();
        }
        TLRPC.PhotoSize thumb = null;
        long maxArea = Long.MIN_VALUE;
        for (int i = 0; i < thumbs.size(); i++) {
            TLRPC.PhotoSize size = thumbs.get(i);
            long area = getArea(size);
            if (area > maxArea) {
                maxArea = area;
                thumb = size;
            }
        }
        if (thumb == null || isEmptyPhotoSize(thumb)) {
            return new ApiWrap.Image();
        }
        ApiWrap.Image result = new ApiWrap.Image();
        result.width = thumb.w;
        result.height = thumb.h;
        TLRPC.TL_inputDocumentFileLocation location = new TLRPC.TL_inputDocumentFileLocation();
        location.id = document.id;
        location.access_hash = document.access_hash;
        location.file_reference = document.file_reference;
        location.thumb_size = thumb.type;
        result.file.location = new ApiWrap.FileLocation();
        result.file.location.data = location;
        result.file.location.dcId = document.dc_id;
        if (thumb instanceof TLRPC.TL_photoCachedSize) {
            result.file.content = thumb.bytes;
            result.file.size = thumb.size;
        } else if (thumb instanceof TLRPC.TL_photoSizeProgressive) {
            ArrayList<Integer> sizes = ((TLRPC.TL_photoSizeProgressive) thumb).sizes;
            if (sizes.isEmpty()) {
                return new ApiWrap.Image();
            }
            result.file.content = new byte[0];
            result.file.size = sizes.get(sizes.size() - 1);
        } else {
            result.file.content = new byte[0];
            result.file.size = document.size;
        }
        result.file.suggestedPath = documentPath + "_thumb.jpg";
        return result;
    }

    public static boolean SkipMessageByDate(ApiWrap.Message message, ExportSettings settings) {
        boolean afterFrom = settings.singlePeerFrom <= 0 || settings.singlePeerFrom <= message.date;
        boolean beforeTill = settings.singlePeerTill <= 0 || message.date < settings.singlePeerTill;
        return !afterFrom || !beforeTill;
    }

    public static boolean SingleMessageBefore(TLRPC.messages_Messages data, int date) {
        int singleMessageDate = SingleMessageDate(data);
        return singleMessageDate > 0 && singleMessageDate < date;
    }

    public static ApiWrap.MessagesSlice AdjustMigrateMessageIds(ApiWrap.MessagesSlice slice) {
        for (int i = 0; i < slice.list.size(); i++) {
            ApiWrap.Message message = slice.list.get(i);
            message.id -= 1000000000;
            if (message.replyToMsgId != 0 && message.replyToPeerId == 0) {
                message.replyToMsgId -= 1000000000;
            }
        }
        return slice;
    }

    public static String ComposeName(HtmlWriter.UserpicData data, String empty) {
        if (data.firstName.isEmpty() && data.lastName.isEmpty()) {
            return empty;
        }
        return data.firstName + ' ' + data.lastName;
    }

    public static int ApplicationColorIndex(int applicationId) {
        Integer index = APPLICATION_COLOR_INDICES.get(applicationId);
        if (index != null) {
            return index;
        }
        return PeerColorIndex(applicationId);
    }

    public static ApiWrap.User ParseUser(TLRPC.User data) {
        ApiWrap.User result = new ApiWrap.User();
        result.info = ParseContactInfo(data);
        if (data instanceof TLRPC.TL_user) {
            TLRPC.TL_user user = (TLRPC.TL_user) data;
            result.bareId = user.id;
            int colorIndex;
            if (user.color == null || (colorIndex = user.color.color) == 0) {
                colorIndex = PeerColorIndex(user.id);
            }
            result.colorIndex = colorIndex;
            result.username = user.username;
            result.isBot = user.bot;
            result.isSelf = user.self;
            result.isReplies = user.id == UserObject.REPLY_BOT;
            result.isVerifyCodes = user.id == UserObject.VERIFY;
            TLRPC.TL_inputUser inputUser = new TLRPC.TL_inputUser();
            inputUser.user_id = user.id;
            inputUser.access_hash = user.access_hash;
            result.input = inputUser;
        } else if (data instanceof TLRPC.TL_userEmpty) {
            TLRPC.TL_inputUser inputUser = new TLRPC.TL_inputUser();
            inputUser.user_id = data.id;
            inputUser.access_hash = 0L;
            result.input = inputUser;
        }
        return result;
    }

    public static ApiWrap.ContactInfo ParseContactInfo(TLRPC.User data) {
        ApiWrap.ContactInfo result = new ApiWrap.ContactInfo();
        if (data instanceof TLRPC.TL_user) {
            TLRPC.TL_user user = (TLRPC.TL_user) data;
            result.userId = user.id;
            int colorIndex;
            if (user.color == null || (colorIndex = user.color.color) == 0) {
                colorIndex = PeerColorIndex(user.id);
            }
            result.colorIndex = colorIndex;
            result.firstName = user.first_name != null ? user.first_name : "";
            result.lastName = user.last_name != null ? user.last_name : "";
            result.phoneNumber = user.phone != null ? user.phone : "";
        } else if (data instanceof TLRPC.TL_userEmpty) {
            result.userId = data.id;
            result.colorIndex = PeerColorIndex(data.id);
        }
        return result;
    }

    public static boolean messageNeedsWrap(ApiWrap.Message message, HtmlWriter.MessageInfo previous) {
        if (previous == null || previous.type != HtmlWriter.MessageInfo.Type.Default) {
            return true;
        }
        if (message.fromId == 0 || previous.fromId != message.fromId || message.viaBotId != previous.viaBotId) {
            return true;
        }
        String previousDay = LocaleController.getInstance().getFormatterYear().format(((long) previous.date) * 1000).trim();
        String currentDay = LocaleController.getInstance().getFormatterYear().format(((long) message.date) * 1000).trim();
        if (!previousDay.equals(currentDay)
                || message.forwarded != previous.forwarded
                || message.showForwardedAsOriginal != previous.showForwardedAsOriginal
                || message.forwardedFromId != previous.forwardedFromId
                || !Objects.equals(message.forwardedFromName, previous.forwardedFromName)) {
            return true;
        }
        boolean isForwarded = message.forwardedFromId != 0 || !message.forwardedFromName.isEmpty();
        return Math.abs(message.date - previous.date) > (isForwarded ? 1 : 900);
    }

    public static boolean forwardedNeedsWrap(ApiWrap.Message message, HtmlWriter.MessageInfo previous) {
        if (messageNeedsWrap(message, previous)) {
            return true;
        }
        return message.forwardedFromId == 0
                || message.forwardedFromId != previous.forwardedFromId
                || ChatUtils.getInstance().getMessageStorage().getUser(message.forwardedFromId) == null
                || Math.abs(message.forwardedDate - previous.forwardedDate) > 900;
    }

    public static String FormatText(ArrayList<ApiWrap.TextPart> data, String internalLinksDomain, String relativeLinkBase) {
        return data.stream()
                .map(part -> formatTextPart(part, internalLinksDomain, relativeLinkBase))
                .collect(Collectors.joining());
    }

    private static String formatTextPart(ApiWrap.TextPart part, String internalLinksDomain, String relativeLinkBase) {
        String text = HtmlContext.SerializeString(part.text);
        switch (part.type) {
            case Text:
            case Unknown:
            case BankCard:
                return text;
            case Mention:
                return "<a href=\"" + internalLinksDomain + text.substring(1) + "\">" + text + "</a>";
            case Hashtag:
                return "<a href=\"\" onclick=\"return ShowHashtag(" + HtmlContext.SerializeString("\"" + text.substring(1) + '\"') + ")\">" + text + "</a>";
            case BotCommand:
                return "<a href=\"\" onclick=\"return ShowBotCommand(" + HtmlContext.SerializeString("\"" + text.substring(1) + '\"') + ")\">" + text + "</a>";
            case Url:
                return "<a href=\"" + text + "\">" + text + "</a>";
            case Email:
                return "<a href=\"mailto:" + text + "\">" + text + "</a>";
            case Bold:
                return "<strong>" + text + "</strong>";
            case Italic:
                return "<em>" + text + "</em>";
            case Code:
                return "<code>" + text + "</code>";
            case Pre:
                return "<pre>" + text + "</pre>";
            case TextUrl:
                return "<a href=\"" + HtmlContext.SerializeString(part.additional) + "\">" + text + "</a>";
            case MentionName:
                return "<a href=\"\" onclick=\"return ShowMentionName()\">" + text + "</a>";
            case Phone:
                return "<a href=\"tel:" + text + "\">" + text + "</a>";
            case Cashtag:
                return "<a href=\"\" onclick=\"return ShowCashtag(" + HtmlContext.SerializeString("\"" + text.substring(1) + '\"') + ")\">" + text + "</a>";
            case Underline:
                return "<u>" + text + "</u>";
            case Strike:
                return "<s>" + text + "</s>";
            case Blockquote:
                return "<blockquote>" + text + "</blockquote>";
            case Spoiler:
                return "<span class=\"spoiler hidden\" onclick=\"ShowSpoiler(this)\"><span aria-hidden=\"true\">" + text + "</span></span>";
            case CustomEmoji:
                return FormatCustomEmoji(part.additional, text, relativeLinkBase);
            default:
                throw new IncompatibleClassChangeError();
        }
    }

    public static String FormatCustomEmoji(String custom, String text, String relativeLinkBase) {
        String openTag;
        if (custom.isEmpty()) {
            openTag = "<a href=\"\" onclick=\"return ShowNotLoadedEmoji();\">";
        } else if (custom.equals(ApiWrap.TextPart.UnavailableEmoji())) {
            openTag = "<a href=\"\" onclick=\"return ShowNotAvailableEmoji();\">";
        } else {
            openTag = "<a href = \"" + relativeLinkBase + custom + "\">";
        }
        return openTag + text + "</a>";
    }

    public static ApiWrap.StoriesSlice ParseStoriesSlice(ArrayList<TL_stories.StoryItem> stories, int baseIndex) {
        ApiWrap.StoriesSlice result = new ApiWrap.StoriesSlice();
        for (int s = 0; s < stories.size(); s++) {
            TL_stories.StoryItem story = stories.get(s);
            result.lastId = story.id;
            result.skipped++;
            int date = story.date;
            ApiWrap.Media media = new ApiWrap.Media();
            TLRPC.MessageMedia mediaData = story.media;
            if (mediaData instanceof TLRPC.TL_messageMediaPhoto) {
                TLRPC.TL_messageMediaPhoto photoData = (TLRPC.TL_messageMediaPhoto) mediaData;
                String path = "stories/" + PrepareStoryFileName(++baseIndex, date, ".jpg");
                HtmlWriter.Photo photo = photoData.photo != null ? ParsePhoto(photoData.photo, path) : new HtmlWriter.Photo();
                photo.spoilered = photoData.spoiler;
                media.content = photo;
            } else if (mediaData instanceof TLRPC.TL_messageMediaDocument) {
                TLRPC.TL_messageMediaDocument documentData = (TLRPC.TL_messageMediaDocument) mediaData;
                ApiWrap.Document document;
                if (documentData.document != null) {
                    document = ParseDocument(new ApiWrap.ParseMediaContext(), documentData.document, "stories", date);
                } else {
                    document = new ApiWrap.Document();
                }
                String extension;
                if (document.mime.equals("image/jpeg")) {
                    extension = ".jpg";
                } else if (document.mime.equals("image/png")) {
                    extension = ".png";
                } else {
                    extension = getExtensionFromMime(document.mime, null);
                }
                String path = "stories/" + PrepareStoryFileName(++baseIndex, date, extension);
                document.file.suggestedPath = path;
                document.thumb.file.suggestedPath = path.concat("_thumb.jpg");
                document.spoilered = documentData.spoiler;
                media.content = document;
            } else {
                media.content = new ApiWrap.UnsupportedMedia();
            }
            if (!(media.content instanceof ApiWrap.UnsupportedMedia)) {
                ApiWrap.Story item = new ApiWrap.Story();
                item.id = story.id;
                item.date = date;
                item.expires = story.expire_date;
                item.media = media;
                item.pinned = story.pinned;
                item.caption = story.caption != null ? ParseText(story.caption, story.entities) : new ArrayList<>();
                result.list.add(item);
                result.skipped--;
            }
        }
        return result;
    }

    private static ArrayList<ArrayList<ApiWrap.HistoryMessageMarkupButton>> ButtonRowsFromTL(TLRPC.TL_replyKeyboardMarkup markup) {
        ArrayList<TL_keyboard.KeyboardButtonRow> rows = markup.rows;
        if (rows.isEmpty()) {
            return new ArrayList<>();
        }
        ArrayList<ArrayList<ApiWrap.HistoryMessageMarkupButton>> result = new ArrayList<>();
        result.ensureCapacity(rows.size());
        for (int r = 0; r < rows.size(); r++) {
            TL_keyboard.KeyboardButtonRow row = rows.get(r);
            ArrayList<ApiWrap.HistoryMessageMarkupButton> buttons = new ArrayList<>();
            buttons.ensureCapacity(row.buttons.size());
            for (int b = 0; b < row.buttons.size(); b++) {
                TL_keyboard.KeyboardButton button = row.buttons.get(b);
                String text = button.text;
                TL_keyboard.ButtonTypeProto type = button.getType();
                if (type instanceof TL_keyboard.TL_buttonTypeDefault) {
                    buttons.add(new ApiWrap.HistoryMessageMarkupButton(ApiWrap.HistoryMessageMarkupButton.Type.Default, text));
                } else if (type instanceof TL_keyboard.TL_inlineButtonTypeCallback) {
                    TL_keyboard.TL_inlineButtonTypeCallback callback = (TL_keyboard.TL_inlineButtonTypeCallback) type;
                    ApiWrap.HistoryMessageMarkupButton.Type buttonType = callback.requires_password
                            ? ApiWrap.HistoryMessageMarkupButton.Type.CallbackWithPassword
                            : ApiWrap.HistoryMessageMarkupButton.Type.Callback;
                    buttons.add(new ApiWrap.HistoryMessageMarkupButton(buttonType, text, callback.data));
                } else if (type instanceof TL_keyboard.TL_buttonTypeRequestGeoLocation) {
                    buttons.add(new ApiWrap.HistoryMessageMarkupButton(ApiWrap.HistoryMessageMarkupButton.Type.RequestLocation, text));
                } else if (type instanceof TL_keyboard.TL_buttonTypeRequestPhone) {
                    buttons.add(new ApiWrap.HistoryMessageMarkupButton(ApiWrap.HistoryMessageMarkupButton.Type.RequestPhone, text));
                } else if (type instanceof TL_keyboard.TL_buttonTypeRequestPeer) {
                    buttons.add(new ApiWrap.HistoryMessageMarkupButton(ApiWrap.HistoryMessageMarkupButton.Type.RequestPeer, text, "unsupported".getBytes(StandardCharsets.UTF_8), "", ((TL_keyboard.TL_buttonTypeRequestPeer) type).button_id));
                } else if (type instanceof TL_keyboard.TL_inlineButtonTypeUrl) {
                    buttons.add(new ApiWrap.HistoryMessageMarkupButton(ApiWrap.HistoryMessageMarkupButton.Type.Url, text, ((TL_keyboard.TL_inlineButtonTypeUrl) type).url.getBytes(StandardCharsets.UTF_8)));
                } else if (type instanceof TL_keyboard.TL_inlineButtonTypeSwitchInline) {
                    TL_keyboard.TL_inlineButtonTypeSwitchInline switchInline = (TL_keyboard.TL_inlineButtonTypeSwitchInline) type;
                    ApiWrap.HistoryMessageMarkupButton.Type buttonType = switchInline.same_peer
                            ? ApiWrap.HistoryMessageMarkupButton.Type.SwitchInlineSame
                            : ApiWrap.HistoryMessageMarkupButton.Type.SwitchInline;
                    buttons.add(new ApiWrap.HistoryMessageMarkupButton(buttonType, text, switchInline.query.getBytes(StandardCharsets.UTF_8)));
                } else if (type instanceof TL_keyboard.TL_inlineButtonTypeGame) {
                    buttons.add(new ApiWrap.HistoryMessageMarkupButton(ApiWrap.HistoryMessageMarkupButton.Type.Game, text));
                } else if (type instanceof TL_keyboard.TL_inlineButtonTypeBuy) {
                    buttons.add(new ApiWrap.HistoryMessageMarkupButton(ApiWrap.HistoryMessageMarkupButton.Type.Buy, text));
                } else if (type instanceof TL_keyboard.TL_inlineButtonTypeUrlAuth) {
                    TL_keyboard.TL_inlineButtonTypeUrlAuth urlAuth = (TL_keyboard.TL_inlineButtonTypeUrlAuth) type;
                    buttons.add(new ApiWrap.HistoryMessageMarkupButton(ApiWrap.HistoryMessageMarkupButton.Type.Auth, text, urlAuth.url.getBytes(StandardCharsets.UTF_8), urlAuth.fwd_text, urlAuth.button_id));
                } else if (type instanceof TL_keyboard.TL_buttonTypeRequestPoll) {
                    byte[] quiz = ((TL_keyboard.TL_buttonTypeRequestPoll) type).quiz ? new byte[1] : new byte[0];
                    buttons.add(new ApiWrap.HistoryMessageMarkupButton(ApiWrap.HistoryMessageMarkupButton.Type.RequestPoll, text, quiz));
                } else if (type instanceof TL_keyboard.TL_inlineButtonTypeUserProfile) {
                    buttons.add(new ApiWrap.HistoryMessageMarkupButton(ApiWrap.HistoryMessageMarkupButton.Type.UserProfile, text, String.valueOf(((TL_keyboard.TL_inlineButtonTypeUserProfile) type).user_id).getBytes(StandardCharsets.UTF_8)));
                } else if (type instanceof TL_keyboard.TL_inlineButtonTypeWebView) {
                    buttons.add(new ApiWrap.HistoryMessageMarkupButton(ApiWrap.HistoryMessageMarkupButton.Type.WebView, text, ((TL_keyboard.TL_inlineButtonTypeWebView) type).url.getBytes(StandardCharsets.UTF_8)));
                } else if (type instanceof TL_keyboard.TL_buttonTypeSimpleWebView) {
                    buttons.add(new ApiWrap.HistoryMessageMarkupButton(ApiWrap.HistoryMessageMarkupButton.Type.SimpleWebView, text, ((TL_keyboard.TL_buttonTypeSimpleWebView) type).url.getBytes(StandardCharsets.UTF_8)));
                } else if (type instanceof TL_keyboard.TL_inlineButtonTypeCopy) {
                    buttons.add(new ApiWrap.HistoryMessageMarkupButton(ApiWrap.HistoryMessageMarkupButton.Type.CopyText, text, ((TL_keyboard.TL_inlineButtonTypeCopy) type).copy_text.getBytes(StandardCharsets.UTF_8)));
                }
            }
            if (!buttons.isEmpty()) {
                result.add(buttons);
            }
        }
        return result;
    }

    public static HtmlWriter.Photo ParsePhoto(TLRPC.Photo data, String suggestedPath) {
        HtmlWriter.Photo result = new HtmlWriter.Photo();
        if (data instanceof TLRPC.TL_photoEmpty) {
            result.id = data.id;
        } else if (data instanceof TLRPC.TL_photo) {
            result.id = data.id;
            result.date = data.date;
            result.image = ParseMaxImage(data, suggestedPath);
        }
        return result;
    }

    private static ApiWrap.Image ParseMaxImage(TLRPC.Photo photo, String suggestedPath) {
        if (photo == null) {
            return null;
        }
        ApiWrap.Image result = new ApiWrap.Image();
        result.file.suggestedPath = suggestedPath;
        long maxArea = 0;
        for (int i = 0; i < photo.sizes.size(); i++) {
            TLRPC.PhotoSize size = photo.sizes.get(i);
            if (isEmptyPhotoSize(size)) {
                continue;
            }
            long area = size.w * size.h;
            if (area <= maxArea) {
                continue;
            }
            result.width = size.w;
            result.height = size.h;
            TLRPC.TL_inputPhotoFileLocation location = new TLRPC.TL_inputPhotoFileLocation();
            location.id = photo.id;
            location.access_hash = photo.access_hash;
            location.file_reference = photo.file_reference;
            location.thumb_size = size.type;
            result.file.location = new ApiWrap.FileLocation();
            result.file.location.data = location;
            result.file.location.dcId = photo.dc_id;
            if (size instanceof TLRPC.TL_photoCachedSize) {
                result.file.content = size.bytes;
                result.file.size = size.bytes.length;
            } else if (size instanceof TLRPC.TL_photoSizeProgressive) {
                ArrayList<Integer> sizes = ((TLRPC.TL_photoSizeProgressive) size).sizes;
                if (!sizes.isEmpty()) {
                    result.file.content = new byte[0];
                    result.file.size = sizes.get(sizes.size() - 1);
                }
            } else {
                result.file.content = new byte[0];
                result.file.size = size.size;
            }
            maxArea = area;
        }
        return result;
    }

    private static String PrepareStoryFileName(int index, int date, String extension) {
        return "story_" + index + PrepareFileNameDatePart(date) + extension;
    }

    private static String PreparePhotoFileName(int index, int date) {
        return "photo_" + index + PrepareFileNameDatePart(date) + ".jpg";
    }

    private static String PrepareFileNameDatePart(int date) {
        if (date != 0) {
            return "@" + LocaleController.getInstance().getExportFileFormatter().format(((long) date) * 1000);
        }
        return "";
    }

    public static void FillUserpicNames(HtmlWriter.UserpicData data, ApiWrap.Peer peer) {
        if (peer == null) {
            return;
        }
        if (peer.user != null) {
            ApiWrap.ContactInfo info = peer.user.info;
            data.firstName = info.firstName != null ? info.firstName : "";
            data.lastName = info.lastName != null ? info.lastName : "";
        } else if (peer.chat != null) {
            data.firstName = peer.name() != null ? peer.name() : "";
        }
    }

    public static void FillUserpicNames(HtmlWriter.UserpicData data, String name) {
        String[] parts = name.split(" ");
        data.firstName = parts[0];
        for (int i = 1; i != parts.length; i++) {
            if (!parts[i].isEmpty()) {
                StringBuilder sb = new StringBuilder();
                if (!data.lastName.isEmpty()) {
                    sb.append(" ");
                }
                sb.append(parts[i]);
                data.lastName = sb.toString();
            }
        }
    }

    public static String ComputeLocationKey(ApiWrap.FileLocation location) {
        String prefix = location.dcId + "_";
        TLRPC.InputFileLocation data = location.data;
        if (data instanceof TLRPC.TL_inputDocumentFileLocation) {
            return prefix + "doc_" + data.id;
        }
        if (data instanceof TLRPC.TL_inputPhotoFileLocation) {
            return prefix + "photo_" + data.id;
        }
        if (data instanceof ExportRequests.TL_inputTakeoutFileLocation) {
            return prefix.concat("takeout");
        }
        FileLog.e("wtf! File location type in Export::ComputeLocationKey. " + location);
        return prefix;
    }

    public static boolean DisplayDate(int date, int previousDate) {
        if (previousDate == 0) {
            return true;
        }
        return !Objects.equals(LocaleController.formatDate(date), LocaleController.formatDate(previousDate));
    }

    public static ArrayList<HtmlWriter.Photo> ParseUserpicsSlice(ArrayList<TLRPC.Photo> photos, int baseIndex) {
        ArrayList<HtmlWriter.Photo> result = new ArrayList<>(photos.size());
        for (int i = 0; i < photos.size(); i++) {
            TLRPC.Photo photo = photos.get(i);
            String path = "profile_pictures/" + PreparePhotoFileName(++baseIndex, photo.date);
            result.add(ParsePhoto(photo, path));
        }
        return result;
    }

    public static String NoFileDescription(ApiWrap.File.SkipReason reason) {
        switch (reason) {
            case Unavailable:
                return "Unavailable, please try again later.";
            case FileSize:
                return "Exceeds maximum size, change data exporting settings to download.";
            case FileType:
                return "Not included, change data exporting settings to download.";
            case None:
                return "";
            default:
                throw new RuntimeException("Skip reason in NoFileDescription.");
        }
    }

    public static ApiWrap.ContactsList ParseContactsList(Vector<ExportRequests.SavedContact> data) {
        ApiWrap.ContactsList result = new ApiWrap.ContactsList();
        result.list.ensureCapacity(data.objects.size());
        for (int i = 0; i < data.objects.size(); i++) {
            ExportRequests.SavedContact contact = data.objects.get(i);
            ApiWrap.ContactInfo info = new ApiWrap.ContactInfo();
            info.firstName = contact.first_name;
            info.lastName = contact.last_name;
            info.phoneNumber = contact.phone;
            info.date = contact.date;
            info.colorIndex = PeerColorIndex(StringBarePeerId(contact.phone));
            result.list.add(info);
        }
        return result;
    }

    public static boolean AppendTopPeers(ApiWrap.ContactsList to, TLRPC.contacts_TopPeers data) {
        if (data instanceof TLRPC.TL_contacts_topPeersNotModified) {
            return false;
        }
        if (data instanceof TLRPC.TL_contacts_topPeersDisabled) {
            return true;
        }
        if (data instanceof TLRPC.TL_contacts_topPeers) {
            TLRPC.TL_contacts_topPeers topPeers = (TLRPC.TL_contacts_topPeers) data;
            HashMap<Long, ApiWrap.Peer> peers = ParsePeersLists(topPeers.users, topPeers.chats);
            for (int i = 0; i < topPeers.categories.size(); i++) {
                TLRPC.TL_topPeerCategoryPeers category = topPeers.categories.get(i);
                if (category.category instanceof TLRPC.TL_topPeerCategoryCorrespondents) {
                    appendTopPeers(peers, to.correspondents, category.peers);
                } else if (category.category instanceof TLRPC.TL_topPeerCategoryBotsInline) {
                    appendTopPeers(peers, to.inlineBots, category.peers);
                } else if (category.category instanceof TLRPC.TL_topPeerCategoryPhoneCalls) {
                    appendTopPeers(peers, to.phoneCalls, category.peers);
                } else {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static void appendTopPeers(HashMap<Long, ApiWrap.Peer> peers, ArrayList<ApiWrap.TopPeer> to, ArrayList<TLRPC.TL_topPeer> list) {
        for (int i = 0; i < list.size(); i++) {
            TLRPC.TL_topPeer topPeer = list.get(i);
            ApiWrap.TopPeer result = new ApiWrap.TopPeer();
            ApiWrap.Peer peer = peers.get(MessageObject.getPeerId(topPeer.peer));
            if (peer == null) {
                peer = EmptyPeer(topPeer.peer);
            }
            result.peer = peer;
            result.rating = topPeer.rating;
            to.add(result);
        }
    }

    public static ArrayList<Integer> SortedContactsIndices(ApiWrap.ContactsList data) {
        int size = data.list.size();
        ArrayList<String> names = new ArrayList<>(size);
        for (int i = 0; i < data.list.size(); i++) {
            ApiWrap.ContactInfo info = data.list.get(i);
            names.add((info.firstName + " " + info.lastName).toLowerCase());
        }
        ArrayList<Integer> indices = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            indices.add(i);
        }
        Collections.sort(indices, Comparator.comparing(names::get));
        return indices;
    }
}
