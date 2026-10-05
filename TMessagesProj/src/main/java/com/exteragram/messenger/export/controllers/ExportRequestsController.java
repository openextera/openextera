package com.exteragram.messenger.export.controllers;

import android.util.Log;

import com.exteragram.messenger.export.ExportSettings;
import com.exteragram.messenger.export.api.ApiWrap;
import com.exteragram.messenger.export.api.DataTypesUtils;
import com.exteragram.messenger.export.api.ExportRequests;
import com.exteragram.messenger.export.output.OutputFile;
import com.exteragram.messenger.export.output.html.HtmlWriter;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_stories;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;

public class ExportRequestsController {

    private static final int ANY_CHATS_MASK = 2016;
    private static final int ANY_CHANNELS_AND_GROUPS_MASK = 1920;
    private static final int PERSONAL_CHATS_MASK = 224;

    private static final long MAX_FILE_SIZE = 4000L * 1024 * 1024;
    private static final int FILE_CHUNK_SIZE = 1024 * 1024;
    private static final int MAX_FILE_REQUESTS = 2;
    private static final int SLICE_LIMIT = 100;
    private static final int CUSTOM_EMOJI_CHUNK = 100;

    private static final ExportRequestsController[] Instance = new ExportRequestsController[16];

    private final int selectedAcc;

    private ExportSettings _settings;
    private OutputFile.Stats _stats;
    private long _takeoutId;
    private long _selfId;

    private StartProcess _startProcess;
    private ApiWrap.DialogsProcess _dialogsProcess;
    private ApiWrap.LeftChannelsProcess _leftChannelsProcess;
    private ApiWrap.ChatProcess _chatProcess;
    private ApiWrap.FileProcess _fileProcess;
    private ApiWrap.UserpicsProcess _userpicsProcess;
    private ApiWrap.StoriesProcess _storiesProcess;
    private ApiWrap.ContactsProcess _contactsProcess;
    private ApiWrap.OtherDataProcess _otherDataProcess;

    private final Set<Long> _unresolvedCustomEmoji = new HashSet<>();
    private final HashMap<Long, ApiWrap.Document> _resolvedCustomEmoji = new HashMap<>();
    private final ApiWrap.LoadedFileCache _fileCache = new ApiWrap.LoadedFileCache(100000);

    public int index = 0;
    public ArrayList<TLRPC.TL_messageRange> splits = new ArrayList<>();

    public static class StartInfo {
        public int userpicsCount = 0;
        public int storiesCount = 0;
        public int dialogsCount = 0;
    }

    public static class StartProcess {

        public enum Step {
            UserpicsCount,
            StoriesCount,
            SplitRanges,
            DialogsCount,
            LeftChannelsCount
        }

        public Utilities.Callback<StartInfo> done;
        public ArrayList<Step> steps = new ArrayList<>();
        public int splitIndex = 0;
        public StartInfo info = new StartInfo();
    }

    private ExportRequestsController(int num) {
        selectedAcc = num;
    }

    public static ExportRequestsController getInstance(int num) {
        ExportRequestsController localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (ExportRequestsController.class) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new ExportRequestsController(num);
                }
            }
        }
        return localInstance;
    }

    public static ApiWrap.DialogsInfo ParseDialogsInfo(TLRPC.InputPeer peer, Vector<?> users) {
        long userId;
        if (peer instanceof TLRPC.TL_inputPeerUser) {
            userId = ((TLRPC.TL_inputPeerUser) peer).user_id;
        } else if (peer instanceof TLRPC.TL_inputPeerSelf) {
            userId = 0;
        } else {
            throw new IllegalStateException("wtf is it: " + peer);
        }
        ApiWrap.DialogsInfo result = new ApiWrap.DialogsInfo();
        for (Object object : users.objects) {
            if (object instanceof TLRPC.User) {
                TLRPC.User user = (TLRPC.User) object;
                if (user.id == userId || (userId == 0 && user.self)) {
                    result.chats.add(DataTypesUtils.DialogInfoFromUser(DataTypesUtils.ParseUser(user)));
                }
            }
        }
        return result;
    }

    public static ApiWrap.DialogsInfo ParseDialogsInfo(TLRPC.InputPeer peer, TLRPC.messages_Chats chats) {
        long chatId;
        if (peer instanceof TLRPC.TL_inputPeerChat) {
            chatId = ((TLRPC.TL_inputPeerChat) peer).chat_id;
        } else if (peer instanceof TLRPC.TL_inputPeerChannel) {
            chatId = ((TLRPC.TL_inputPeerChannel) peer).channel_id;
        } else {
            throw new IllegalStateException("illegal type: " + peer);
        }
        ApiWrap.DialogsInfo result = new ApiWrap.DialogsInfo();
        for (TLRPC.Chat chat : chats.chats) {
            long id;
            if (chat instanceof TLRPC.TL_channel) {
                id = ((TLRPC.TL_channel) chat).id;
            } else if (chat instanceof TLRPC.TL_channelForbidden) {
                id = ((TLRPC.TL_channelForbidden) chat).id;
            } else {
                id = chat.id;
            }
            if (id == chatId) {
                ApiWrap.DialogInfo info = DataTypesUtils.DialogInfoFromChat(DataTypesUtils.ParseChat(chat));
                info.isLeftChannel = false;
                result.chats.add(info);
            }
        }
        return result;
    }

    public void startExport(ExportSettings settings, OutputFile.Stats stats, Utilities.Callback<StartInfo> done) {
        _settings = settings;
        _stats = stats;
        _startProcess = new StartProcess();
        _startProcess.done = done;
        if ((_settings.types & 2) != 0) {
            _startProcess.steps.add(StartProcess.Step.UserpicsCount);
        }
        if ((_settings.types & 2048) != 0) {
            _startProcess.steps.add(StartProcess.Step.StoriesCount);
        }
        if ((_settings.types & ANY_CHATS_MASK) != 0) {
            _startProcess.steps.add(StartProcess.Step.SplitRanges);
            _startProcess.steps.add(StartProcess.Step.DialogsCount);
        }
        if ((_settings.types & ANY_CHANNELS_AND_GROUPS_MASK) != 0 && !_settings.onlySinglePeer()) {
            _startProcess.steps.add(StartProcess.Step.LeftChannelsCount);
        }
        startMainSession(this::sendNextStartRequest);
    }

    private void sendNextStartRequest() {
        ArrayList<StartProcess.Step> steps = _startProcess.steps;
        if (steps.isEmpty()) {
            finishStartProcess();
            Log.d("exteraGram", "caught empty steps in sendNextStartRequest, finishing start process...");
            return;
        }
        StartProcess.Step step = steps.remove(0);
        switch (step) {
            case UserpicsCount:
                requestUserpicsCount();
                break;
            case StoriesCount:
                requestStoriesCount();
                break;
            case SplitRanges:
                requestSplitRanges();
                break;
            case DialogsCount:
                requestDialogsCount();
                break;
            case LeftChannelsCount:
                requestLeftChannelsCount();
                break;
        }
    }

    public void finishStartProcess() {
        _startProcess.done.run(_startProcess.info);
    }

    private void startMainSession(Runnable done) {
        long sizeLimit = _settings.media.sizeLimit;
        boolean hasFiles = (_settings.media.isEnabled() && sizeLimit > 0)
            || (_settings.types & 2) != 0
            || (_settings.types & 2048) != 0;

        ExportRequests.InitTakeoutSession request = new ExportRequests.InitTakeoutSession();
        if (hasFiles) {
            request.files = true;
            if (sizeLimit < MAX_FILE_SIZE) {
                request.file_max_size = sizeLimit;
            }
        }
        int types = _settings.types;
        if ((types & 4) != 0) {
            request.contacts = true;
        }
        if ((types & 32) != 0 || (types & 64) != 0) {
            request.message_users = true;
        }
        if ((types & 128) != 0) {
            request.message_megagroups = true;
            request.message_chats = true;
        }
        if ((types & 256) != 0) {
            request.message_megagroups = true;
        }
        if ((types & 512) != 0 || (types & 1024) != 0) {
            request.message_channels = true;
        }

        TLRPC.TL_users_getUsers getUsers = new TLRPC.TL_users_getUsers();
        TLRPC.TL_inputUser inputUser = new TLRPC.TL_inputUser();
        inputUser.user_id = UserConfig.getInstance(selectedAcc).clientUserId;
        getUsers.id = new ArrayList<>(Collections.singletonList(inputUser));
        ConnectionsManager.getInstance(selectedAcc).sendRequest(getUsers, (response, error) -> {
            if (!(response instanceof Vector)) {
                return;
            }
            for (Object object : ((Vector<?>) response).objects) {
                if (object instanceof TLRPC.User) {
                    TLRPC.User user = (TLRPC.User) object;
                    if (user.self) {
                        _selfId = user.id;
                    }
                }
            }
            ConnectionsManager.getInstance(selectedAcc).sendRequest(request, (takeout, takeoutError) -> {
                if (takeoutError != null && takeoutError.text != null) {
                    ExportController.showError(takeoutError);
                } else if (takeout instanceof ExportRequests.Takeout) {
                    _takeoutId = ((ExportRequests.Takeout) takeout).id;
                    done.run();
                }
            });
        });
    }

    private void requestUserpicsCount() {
        TLRPC.TL_photos_getUserPhotos req = new TLRPC.TL_photos_getUserPhotos();
        req.user_id = new TLRPC.TL_inputUserSelf();
        req.offset = 0;
        req.max_id = 0;
        req.limit = 0;
        mainRequest(req, (response, error) -> {
            if (response instanceof TLRPC.photos_Photos) {
                int count;
                if (response instanceof TLRPC.TL_photos_photos) {
                    count = ((TLRPC.TL_photos_photos) response).photos.size();
                } else if (response instanceof TLRPC.TL_photos_photosSlice) {
                    count = ((TLRPC.TL_photos_photosSlice) response).count;
                } else {
                    count = 0;
                }
                _startProcess.info.userpicsCount = count;
                sendNextStartRequest();
            }
        });
    }

    private void requestStoriesCount() {
        TL_stories.TL_stories_getStoriesArchive req = new TL_stories.TL_stories_getStoriesArchive();
        req.peer = new TLRPC.TL_inputPeerSelf();
        req.limit = 0;
        req.offset_id = 0;
        mainRequest(req, (response, error) -> {
            if (response instanceof TL_stories.TL_stories_stories) {
                _startProcess.info.storiesCount = ((TL_stories.TL_stories_stories) response).count;
            }
            sendNextStartRequest();
        });
    }

    public void vectorToRanges(TLObject response, ArrayList<TLRPC.TL_messageRange> ranges) {
        if (response instanceof Vector) {
            for (Object object : ((Vector<?>) response).objects) {
                if (object instanceof TLRPC.TL_messageRange) {
                    ranges.add((TLRPC.TL_messageRange) object);
                }
            }
        }
    }

    public void requestDialogsList(Utilities.CallbackReturn<Integer, Boolean> progress, Utilities.Callback<ApiWrap.DialogsInfo> done) {
        _dialogsProcess = new ApiWrap.DialogsProcess();
        _dialogsProcess.splitIndexPlusOne = splits.size();
        _dialogsProcess.progress = progress;
        _dialogsProcess.done = done;
        requestDialogsSlice();
    }

    public void requestMessages(
        ApiWrap.DialogInfo info,
        Utilities.CallbackReturn<ApiWrap.DialogInfo, Boolean> start,
        Utilities.CallbackReturn<ApiWrap.DownloadProgress, Boolean> progress,
        Utilities.CallbackReturn<ApiWrap.MessagesSlice, Boolean> slice,
        Runnable done
    ) {
        _chatProcess = new ApiWrap.ChatProcess();
        _chatProcess.context.selfPeerId = _selfId;
        _chatProcess.info = info;
        _chatProcess.start = start;
        _chatProcess.fileProgress = progress;
        _chatProcess.handleSlice = slice;
        _chatProcess.done = done;
        requestMessagesCount(0);
    }

    public void requestSessions(Utilities.Callback<ApiWrap.SessionsList> done) {
        mainRequest(new TL_account.getAuthorizations(), (response, error) -> {
            if (response instanceof TL_account.authorizations) {
                ApiWrap.SessionsList result = DataTypesUtils.ParseSessionsList((TL_account.authorizations) response);
                mainRequest(new TL_account.getWebAuthorizations(), (webResponse, webError) -> {
                    result.webList = DataTypesUtils.ParseWebSessionsList((TL_account.webAuthorizations) webResponse).webList;
                    done.run(result);
                });
            }
        });
    }

    private void requestLeftChannelsCount() {
        _leftChannelsProcess = new ApiWrap.LeftChannelsProcess();
        requestLeftChannelsSliceGeneric(() -> {
            _startProcess.info.dialogsCount += _leftChannelsProcess.fullCount;
            sendNextStartRequest();
        });
    }

    private void requestLeftChannelsSliceGeneric(Runnable done) {
        ExportRequests.getLeftChannels req = new ExportRequests.getLeftChannels();
        req.offset = _leftChannelsProcess.offset;
        mainRequest(req, (response, error) -> {
            if (!(response instanceof TLRPC.messages_Chats)) {
                return;
            }
            TLRPC.messages_Chats chats = (TLRPC.messages_Chats) response;
            appendChatsSlice(_leftChannelsProcess, _leftChannelsProcess.info.left, DataTypesUtils.ParseLeftChannelsInfo(chats).left, splits.size() - 1);

            ApiWrap.LeftChannelsProcess process = _leftChannelsProcess;
            process.offset += chats.chats.size();
            int fullCount;
            boolean finished;
            if (chats instanceof TLRPC.TL_messages_chats) {
                fullCount = chats.chats.size();
                finished = true;
            } else if (chats instanceof TLRPC.TL_messages_chatsSlice) {
                fullCount = ((TLRPC.TL_messages_chatsSlice) chats).count;
                finished = chats.chats.isEmpty();
            } else {
                fullCount = 0;
                finished = false;
            }
            process.fullCount = fullCount;
            process.finished = finished;
            if (process.progress == null || process.progress.run(process.info.left.size())) {
                done.run();
            }
        });
    }

    private void requestMessagesCount(int localSplitIndex) {
        requestChatMessages(_chatProcess.info.splits.get(localSplitIndex), 0, 0, 1, result -> {
            int count;
            if (result instanceof TLRPC.TL_messages_messages) {
                count = result.messages.size();
            } else if (result instanceof TLRPC.TL_messages_messagesSlice) {
                count = ((TLRPC.TL_messages_messagesSlice) result).count;
            } else if (result instanceof TLRPC.TL_messages_channelMessages) {
                count = ((TLRPC.TL_messages_channelMessages) result).count;
            } else if (result instanceof TLRPC.TL_messages_messagesNotModified) {
                count = -1;
            } else {
                count = 0;
            }
            if (count < 0) {
                throw new IllegalStateException("Unexpected messagesNotModified received");
            }
            if (!DataTypesUtils.SingleMessageAfter(result, _settings.singlePeerFrom)) {
                messagesCountLoaded(localSplitIndex, 0);
            } else {
                checkFirstMessageDate(localSplitIndex, count);
            }
        });
    }

    private void checkFirstMessageDate(int localSplitIndex, int count) {
        if (_settings.singlePeerTill <= 0) {
            messagesCountLoaded(localSplitIndex, count);
            return;
        }
        requestChatMessages(_chatProcess.info.splits.get(localSplitIndex), 1, -1, 1, result -> {
            if (DataTypesUtils.SingleMessageBefore(result, _settings.singlePeerTill)) {
                messagesCountLoaded(localSplitIndex, count);
            } else {
                messagesCountLoaded(localSplitIndex, 0);
            }
        });
    }

    private void messagesCountLoaded(int localSplitIndex, int count) {
        _chatProcess.info.messagesCountPerSplit.set(localSplitIndex, count);
        if (localSplitIndex + 1 < _chatProcess.info.splits.size()) {
            requestMessagesCount(localSplitIndex + 1);
            return;
        }
        if (_chatProcess.start.run(_chatProcess.info)) {
            requestMessagesSlice();
        }
    }

    private void requestMessagesSlice() {
        if (_chatProcess.info.messagesCountPerSplit.get(_chatProcess.localSplitIndex) == 0) {
            loadMessagesFiles(new ApiWrap.MessagesSlice());
            return;
        }
        requestChatMessages(_chatProcess.info.splits.get(_chatProcess.localSplitIndex), _chatProcess.largestIdPlusOne, -SLICE_LIMIT, SLICE_LIMIT, result -> {
            if (result instanceof TLRPC.TL_messages_messagesNotModified) {
                throw new IllegalStateException("Unexpected messagesNotModified received.");
            }
            if (result instanceof TLRPC.TL_messages_messages) {
                _chatProcess.lastSlice = true;
            }
            loadMessagesFiles(DataTypesUtils.ParseMessagesSlice(_chatProcess.context, result.messages, result.users, result.chats, _chatProcess.info.relativePath));
        });
    }

    private void loadMessagesFiles(ApiWrap.MessagesSlice slice) {
        collectMessagesCustomEmoji(slice);
        if (slice.list.isEmpty()) {
            _chatProcess.lastSlice = true;
        }
        _chatProcess.slice = slice;
        _chatProcess.fileIndex = 0;
        resolveCustomEmoji();
    }

    private void collectMessagesCustomEmoji(ApiWrap.MessagesSlice slice) {
        for (ApiWrap.Message message : slice.list) {
            for (ApiWrap.TextPart part : message.text) {
                if (part.type == ApiWrap.TextPart.Type.CustomEmoji) {
                    long id = Long.parseLong(part.additional);
                    if (id != 0 && !_resolvedCustomEmoji.containsKey(id)) {
                        _unresolvedCustomEmoji.add(id);
                    }
                }
            }
            // Custom emoji reactions are not collected: ApiWrap.Reaction carries no data in lite
            // (R8 stripped it, message.reactions is always empty).
        }
    }

    private void resolveCustomEmoji() {
        if (_unresolvedCustomEmoji.isEmpty()) {
            loadNextMessageFile();
            return;
        }
        int count = Math.min(_unresolvedCustomEmoji.size(), CUSTOM_EMOJI_CHUNK);
        ArrayList<Long> documents = new ArrayList<>(count);
        ArrayList<Long> unresolved = new ArrayList<>(_unresolvedCustomEmoji);
        for (int i = Math.max(0, unresolved.size() - count); i != unresolved.size(); i++) {
            documents.add(unresolved.get(i));
            _unresolvedCustomEmoji.remove(unresolved.get(i));
        }
        Runnable finalize = () -> {
            for (Long id : documents) {
                if (!_resolvedCustomEmoji.containsKey(id)) {
                    ApiWrap.Document document = new ApiWrap.Document();
                    document.file = new ApiWrap.File();
                    document.file.skipReason = ApiWrap.File.SkipReason.Unavailable;
                    _resolvedCustomEmoji.put(id, document);
                }
            }
            resolveCustomEmoji();
        };
        TLRPC.TL_messages_getCustomEmojiDocuments req = new TLRPC.TL_messages_getCustomEmojiDocuments();
        req.document_id = documents;
        mainRequest(req, (response, error) -> {
            if (error != null) {
                FileLog.e("Export Error: Failed to get documents for emoji.");
                finalize.run();
                return;
            }
            if (response instanceof Vector) {
                for (Object object : ((Vector<?>) response).objects) {
                    ApiWrap.Document document = DataTypesUtils.ParseDocument(_chatProcess.context, (TLRPC.Document) object, _chatProcess.info.relativePath, 0);
                    _resolvedCustomEmoji.put(document.id, document);
                }
                finalize.run();
            }
        });
    }

    private void loadNextMessageFile() {
        ArrayList<ApiWrap.Message> list = _chatProcess.slice.list;
        for (; _chatProcess.fileIndex < list.size(); _chatProcess.fileIndex++) {
            ApiWrap.Message message = list.get(_chatProcess.fileIndex);
            if (DataTypesUtils.SkipMessageByDate(message, _settings)) {
                continue;
            }
            if (!messageCustomEmojiReady(message)) {
                return;
            }
            boolean fileReady = processFileLoad(message.getFile(), currentFileMessageOrigin(), this::loadMessageFileProgress, this::loadMessageFileDone, currentFileMessage(), null);
            if (!fileReady) {
                return;
            }
            // exteraGram passes the message file here as well, not media.getThumb().file
            boolean thumbReady = processFileLoad(message.getFile(), currentFileMessageOrigin(), this::loadMessageFileProgress, this::loadMessageThumbDone, currentFileMessage(), null);
            if (!thumbReady) {
                return;
            }
        }
        finishMessagesSlice();
    }

    private ApiWrap.FileOrigin currentFileMessageOrigin() {
        int split = _chatProcess.info.splits.get(_chatProcess.localSplitIndex);
        int splitIndex = split >= 0 ? split : _chatProcess.info.splits.size() + split;
        TLRPC.InputPeer peer = split >= 0 ? _chatProcess.info.input : _chatProcess.info.migratedFromInput;
        return new ApiWrap.FileOrigin(splitIndex, peer, currentFileMessage().id, 0, 0);
    }

    private ApiWrap.Message currentFileMessage() {
        return _chatProcess.slice.list.get(_chatProcess.fileIndex);
    }

    private boolean loadMessageFileProgress(ApiWrap.FileProgress progress) {
        return _chatProcess.fileProgress.run(new ApiWrap.DownloadProgress(_fileProcess.randomId, _fileProcess.relativePath, _chatProcess.fileIndex, progress.ready(), progress.total()));
    }

    private void loadMessageThumbDone(String relativePath) {
        ApiWrap.File file = _chatProcess.slice.list.get(_chatProcess.fileIndex).media.getThumb().file;
        if (relativePath.contains("null")) {
            throw new IllegalStateException("zdes 1");
        }
        file.relativePath = relativePath;
        if (relativePath.isEmpty()) {
            file.skipReason = ApiWrap.File.SkipReason.Unavailable;
        }
        loadNextMessageFile();
    }

    private void loadFile(ApiWrap.File file, ApiWrap.FileOrigin origin, Utilities.CallbackReturn<ApiWrap.FileProgress, Boolean> progress, Utilities.Callback<String> done) {
        _fileProcess = prepareFileProcess(file, origin);
        _fileProcess.progress = progress;
        _fileProcess.done = done;
        if (progress == null || progress.run(new ApiWrap.FileProgress(_fileProcess.file.size(), _fileProcess.size))) {
            loadFilePart();
        }
    }

    private void loadMessageFileDone(String relativePath) {
        ApiWrap.File file = _chatProcess.slice.list.get(_chatProcess.fileIndex).getFile();
        file.relativePath = relativePath;
        if (relativePath.isEmpty()) {
            file.skipReason = ApiWrap.File.SkipReason.Unavailable;
        }
        loadNextMessageFile();
    }

    private void loadFilePart() {
        if (_fileProcess == null || _fileProcess.requestId != 0 || _fileProcess.requests.size() >= MAX_FILE_REQUESTS) {
            return;
        }
        if (_fileProcess.size > 0 && _fileProcess.offset >= _fileProcess.size) {
            return;
        }
        long offset = _fileProcess.offset;
        ApiWrap.FileProcess.Request request = new ApiWrap.FileProcess.Request();
        request.offset = offset;
        _fileProcess.requests.add(request);
        _fileProcess.requestId = fileRequest(_fileProcess.location, _fileProcess.offset, (response, error) -> {
            _fileProcess.requestId = 0;
            filePartDone(offset, response);
        });
        _fileProcess.offset += FILE_CHUNK_SIZE;
    }

    private void filePartRefreshReference(long offset) {
        ApiWrap.FileOrigin origin = _fileProcess.origin;
        if (origin.storyId() != 0) {
            TL_stories.TL_stories_getStoriesByID req = new TL_stories.TL_stories_getStoriesByID();
            req.peer = new TLRPC.TL_inputPeerSelf();
            req.id = new ArrayList<>(Collections.singletonList(origin.storyId()));
            _fileProcess.requestId = mainRequest(req, (response, error) -> {
                if (error != null) {
                    _fileProcess.requestId = 0;
                    _fileProcess.done.run("");
                } else if (response instanceof TL_stories.TL_stories_stories) {
                    _fileProcess.requestId = 0;
                    filePartExtractReference(offset, (TL_stories.TL_stories_stories) response);
                }
            });
            // no return here in exteraGram: execution falls through to the message refresh below
        } else if (origin.messageId() == 0) {
            Log.e("exteraGram", "FILE_REFERENCE error for non-message file.");
            return;
        }
        TLRPC.InputPeer peer = origin.peer();
        if (peer instanceof TLRPC.TL_inputPeerChannel || peer instanceof TLRPC.TL_inputPeerChannelFromMessage) {
            TLRPC.TL_channels_getMessages req = new TLRPC.TL_channels_getMessages();
            req.id = new ArrayList<>(Collections.singletonList(origin.messageId()));
            if (peer instanceof TLRPC.TL_inputPeerChannel) {
                TLRPC.TL_inputChannel inputChannel = new TLRPC.TL_inputChannel();
                inputChannel.channel_id = peer.channel_id;
                inputChannel.access_hash = peer.access_hash;
                req.channel = inputChannel;
            } else {
                TLRPC.TL_inputChannelFromMessage inputChannel = new TLRPC.TL_inputChannelFromMessage();
                inputChannel.peer = peer.peer;
                inputChannel.access_hash = peer.access_hash;
                inputChannel.channel_id = peer.channel_id;
                req.channel = inputChannel;
            }
            _fileProcess.requestId = mainRequest(req, (response, error) -> {
                if (error != null) {
                    _fileProcess.requestId = 0;
                    FileLog.w("Export Error: File unavailable.");
                    _fileProcess.done.run("");
                } else if (response instanceof TLRPC.TL_messages_messages) {
                    _fileProcess.requestId = 0;
                    filePartExtractReference(offset, (TLRPC.TL_messages_messages) response);
                }
            });
            return;
        }
        TLRPC.TL_messages_getMessages req = new TLRPC.TL_messages_getMessages();
        req.id = new ArrayList<>(Collections.singletonList(origin.messageId()));
        _fileProcess.requestId = splitRequest(origin.split(), req, (response, error) -> {
            if (error != null) {
                _fileProcess.requestId = 0;
                Log.w("exteraGram", "Export Error: File unavailable.");
                _fileProcess.done.run("");
            } else if (response instanceof TLRPC.messages_Messages) {
                _fileProcess.requestId = 0;
                filePartExtractReference(offset, (TLRPC.messages_Messages) response);
            }
        });
    }

    private void filePartExtractReference(long offset, TL_stories.TL_stories_stories result) {
        ArrayList<ApiWrap.Story> stories = DataTypesUtils.ParseStoriesSlice(result.stories, 0).list;
        for (ApiWrap.Story story : stories) {
            if (story.id == _fileProcess.origin.storyId()) {
                boolean refreshed = DataTypesUtils.RefreshFileReference(_fileProcess.location.data, story.file().location.data);
                boolean thumbRefreshed = DataTypesUtils.RefreshFileReference(_fileProcess.location.data, story.thumb().file.location.data);
                if (refreshed || thumbRefreshed) {
                    _fileProcess.requestId = fileRequest(_fileProcess.location, offset, (response, error) -> {
                        if (response instanceof TLRPC.TL_upload_file) {
                            _fileProcess.requestId = 0;
                            filePartDone(offset, response);
                        }
                    });
                    return;
                }
            }
        }
        _fileProcess.done.run("");
    }

    private void filePartExtractReference(long offset, TLRPC.messages_Messages result) {
        if (result instanceof TLRPC.TL_messages_messagesNotModified) {
            throw new IllegalStateException("wtf, TL_messages_messagesNotModified received!");
        }
        ApiWrap.ParseMediaContext context = new ApiWrap.ParseMediaContext();
        context.selfPeerId = _selfId;
        ArrayList<ApiWrap.Message> messages = DataTypesUtils.ParseMessagesSlice(context, result.messages, result.users, result.chats, _chatProcess.info.relativePath).list;
        for (ApiWrap.Message message : messages) {
            if (message.id == _fileProcess.origin.messageId()) {
                boolean refreshed = DataTypesUtils.RefreshFileReference(_fileProcess.location.data, message.getFile().location.data);
                boolean thumbRefreshed = DataTypesUtils.RefreshFileReference(_fileProcess.location.data, message.media.getThumb().file.location.data);
                if (refreshed || thumbRefreshed) {
                    _fileProcess.requestId = fileRequest(_fileProcess.location, offset, (response, error) -> {
                        _fileProcess.requestId = 0;
                        filePartDone(offset, response);
                    });
                    return;
                }
            }
        }
        FileLog.w("Export Error: File unavailable.");
        _fileProcess.done.run("");
    }

    private void filePartDone(long offset, TLObject response) {
        if (response instanceof TLRPC.TL_upload_fileCdnRedirect) {
            throw new IllegalArgumentException("TL_upload_fileCdnRedirect received! not supported.");
        }
        if (response instanceof TLRPC.TL_upload_file) {
            TLRPC.TL_upload_file result = (TLRPC.TL_upload_file) response;
            if (result.bytes.limit() == 0) {
                if (_fileProcess.size > 0) {
                    throw new IllegalStateException("received data has 0 length and fileProcess size is not 0!!!");
                }
                if (!_fileProcess.file.writeBlock("").isSuccess()) {
                    throw new IllegalStateException("writing empty block was not successful!");
                }
            } else {
                ApiWrap.FileProcess.Request request = null;
                for (ApiWrap.FileProcess.Request r : _fileProcess.requests) {
                    if (r.offset == offset) {
                        request = r;
                        break;
                    }
                }
                if (request == null) {
                    throw new IllegalStateException("req not found!");
                }
                request.bytes = result.bytes;

                while (!_fileProcess.requests.isEmpty() && _fileProcess.requests.getFirst().bytes.limit() != 0) {
                    NativeByteBuffer bytes = _fileProcess.requests.getFirst().bytes;
                    if (!_fileProcess.file.writeBlock(bytes).isSuccess()) {
                        throw new RuntimeException("wtf! tried to write: " + (int) bytes.buffer.get());
                    }
                    _fileProcess.requests.removeFirst();
                }

                if (_fileProcess.progress != null) {
                    _fileProcess.progress.run(new ApiWrap.FileProgress(_fileProcess.file.size(), _fileProcess.size));
                }

                if (!_fileProcess.requests.isEmpty() || _fileProcess.size == 0 || _fileProcess.size > _fileProcess.offset) {
                    loadFilePart();
                    return;
                }
            }
        }
        ApiWrap.FileProcess process = _fileProcess;
        _fileCache.save(process.location, process.relativePath);
        process.done.run(process.relativePath);
    }

    private ApiWrap.FileProcess prepareFileProcess(ApiWrap.File file, ApiWrap.FileOrigin origin) {
        String relativePath = OutputFile.PrepareRelativePath(_settings.path, file.suggestedPath);
        ApiWrap.FileProcess result = new ApiWrap.FileProcess(_settings.path + "/" + relativePath, _stats);
        result.relativePath = relativePath;
        result.location = file.location;
        result.size = file.size;
        result.origin = origin;
        result.randomId = Utilities.random.nextLong();
        return result;
    }

    private boolean processFileLoad(
        ApiWrap.File file,
        ApiWrap.FileOrigin origin,
        Utilities.CallbackReturn<ApiWrap.FileProgress, Boolean> progress,
        Utilities.Callback<String> done,
        ApiWrap.Message message,
        ApiWrap.Story story
    ) {
        if (!file.relativePath.isEmpty() || file.skipReason != ApiWrap.File.SkipReason.None) {
            return true;
        }
        if (file.location == null && (file.content == null || file.content.length == 0)) {
            file.skipReason = ApiWrap.File.SkipReason.Unavailable;
            return true;
        }
        if (writePreloadedFile(file, origin)) {
            return !file.relativePath.isEmpty();
        }

        Object media;
        if (message != null) {
            media = message.media;
        } else if (story != null) {
            media = story.file();
        } else {
            media = null;
        }
        int type;
        if (media instanceof ApiWrap.Media) {
            Object content = ((ApiWrap.Media) media).content;
            if (content instanceof ApiWrap.Document) {
                ApiWrap.Document document = (ApiWrap.Document) content;
                if (document.isSticker) {
                    type = 16;
                } else if (document.isVideoMessage) {
                    type = 8;
                } else if (document.isVoiceMessage) {
                    type = 4;
                } else if (document.isAnimated) {
                    type = 32;
                } else if (document.isVideoFile) {
                    type = 2;
                } else {
                    type = 64;
                }
            } else {
                type = 1;
            }
        } else {
            type = 0;
        }

        long fullSize;
        if (message != null) {
            fullSize = message.getFile().size;
        } else if (story != null) {
            fullSize = story.file().size;
        } else {
            fullSize = file.size;
        }

        if (message != null && DataTypesUtils.SkipMessageByDate(message, _settings)) {
            file.skipReason = ApiWrap.File.SkipReason.DateLimits;
            return true;
        }
        if (story == null && (_settings.media.type & type) != type) {
            file.skipReason = ApiWrap.File.SkipReason.FileType;
            return true;
        }
        if (story == null && fullSize > _settings.media.sizeLimit) {
            file.skipReason = ApiWrap.File.SkipReason.FileSize;
            return true;
        }
        loadFile(file, origin, progress, done);
        return false;
    }

    private boolean writePreloadedFile(ApiWrap.File file, ApiWrap.FileOrigin origin) {
        String cached = _fileCache.find(file.location);
        if (cached != null) {
            file.relativePath = cached;
            return true;
        }
        if (file.content == null || file.content.length == 0) {
            return false;
        }
        ApiWrap.FileProcess process = prepareFileProcess(file, origin);
        if (process.file.writeBlock(file.content).isSuccess()) {
            file.relativePath = process.relativePath;
            _fileCache.save(file.location, process.relativePath);
        }
        return true;
    }

    public void requestUserpics(
        Utilities.CallbackReturn<ApiWrap.UserpicsInfo, Boolean> start,
        Utilities.CallbackReturn<ApiWrap.DownloadProgress, Boolean> progress,
        Utilities.CallbackReturn<ArrayList<HtmlWriter.Photo>, Boolean> slice,
        Runnable finish
    ) {
        _userpicsProcess = new ApiWrap.UserpicsProcess();
        _userpicsProcess.start = start;
        _userpicsProcess.fileProgress = progress;
        _userpicsProcess.handleSlice = slice;
        _userpicsProcess.finish = finish;

        TLRPC.TL_photos_getUserPhotos req = new TLRPC.TL_photos_getUserPhotos();
        req.limit = SLICE_LIMIT;
        req.user_id = new TLRPC.TL_inputUserSelf();
        req.offset = 0;
        req.max_id = _userpicsProcess.maxId;
        mainRequest(req, (response, error) -> {
            if (!(response instanceof TLRPC.photos_Photos)) {
                return;
            }
            TLRPC.photos_Photos photos = (TLRPC.photos_Photos) response;
            ApiWrap.UserpicsInfo info;
            if (photos instanceof TLRPC.TL_photos_photos) {
                info = new ApiWrap.UserpicsInfo(photos.photos.size());
            } else if (photos instanceof TLRPC.TL_photos_photosSlice) {
                info = new ApiWrap.UserpicsInfo(((TLRPC.TL_photos_photosSlice) photos).count);
            } else {
                info = null;
            }
            if (_userpicsProcess.start.run(info)) {
                handleUserpicsSlice(photos);
            }
        });
    }

    private void handleUserpicsSlice(TLRPC.photos_Photos photos) {
        if (photos instanceof TLRPC.TL_photos_photos) {
            _userpicsProcess.lastSlice = true;
        }
        loadUserpicsFiles(DataTypesUtils.ParseUserpicsSlice(photos.photos, _userpicsProcess.processed));
    }

    public void requestStories(
        Utilities.CallbackReturn<Integer, Boolean> start,
        Utilities.CallbackReturn<ApiWrap.DownloadProgress, Boolean> progress,
        Utilities.CallbackReturn<ApiWrap.StoriesSlice, Boolean> slice,
        Runnable finish
    ) {
        _storiesProcess = new ApiWrap.StoriesProcess();
        _storiesProcess.start = start;
        _storiesProcess.fileProgress = progress;
        _storiesProcess.handleSlice = slice;
        _storiesProcess.finish = finish;

        TL_stories.TL_stories_getStoriesArchive req = new TL_stories.TL_stories_getStoriesArchive();
        req.peer = new TLRPC.TL_inputPeerSelf();
        req.limit = SLICE_LIMIT;
        req.offset_id = _storiesProcess.offsetId;
        mainRequest(req, (response, error) -> {
            if (response instanceof TL_stories.TL_stories_stories) {
                TL_stories.TL_stories_stories stories = (TL_stories.TL_stories_stories) response;
                if (_storiesProcess.start.run(stories.count)) {
                    loadStoriesFiles(DataTypesUtils.ParseStoriesSlice(stories.stories, _storiesProcess.processed));
                }
            }
        });
    }

    private void loadStoriesFiles(ApiWrap.StoriesSlice slice) {
        if (slice.lastId == 0) {
            _storiesProcess.lastSlice = true;
        }
        _storiesProcess.slice = slice;
        _storiesProcess.fileIndex = 0;
        loadNextStory();
    }

    private void loadNextStory() {
        ArrayList<ApiWrap.Story> list = _storiesProcess.slice.list;
        for (; _storiesProcess.fileIndex < list.size(); _storiesProcess.fileIndex++) {
            ApiWrap.Story story = list.get(_storiesProcess.fileIndex);
            ApiWrap.FileOrigin origin = new ApiWrap.FileOrigin(0, null, 0, story.id, 0);
            boolean fileReady = processFileLoad(story.file(), origin, this::loadStoryProgress, this::loadStoryDone, null, story);
            if (!fileReady) {
                return;
            }
            boolean thumbReady = processFileLoad(story.thumb().file, origin, this::loadStoryProgress, this::loadStoryThumbDone, null, story);
            if (!thumbReady) {
                return;
            }
        }
        finishStoriesSlice();
    }

    private void finishStoriesSlice() {
        ApiWrap.StoriesSlice slice = _storiesProcess.slice;
        if (slice.lastId != 0) {
            _storiesProcess.processed += slice.list.size();
            _storiesProcess.offsetId = slice.lastId;
            if (!_storiesProcess.handleSlice.run(slice)) {
                return;
            }
        }
        if (_storiesProcess.lastSlice) {
            _storiesProcess.finish.run();
            return;
        }
        TL_stories.TL_stories_getStoriesArchive req = new TL_stories.TL_stories_getStoriesArchive();
        req.peer = new TLRPC.TL_inputPeerSelf();
        req.limit = SLICE_LIMIT;
        req.offset_id = _storiesProcess.offsetId;
        mainRequest(req, (response, error) -> {
            if (response instanceof TL_stories.TL_stories_stories) {
                loadStoriesFiles(DataTypesUtils.ParseStoriesSlice(((TL_stories.TL_stories_stories) response).stories, _storiesProcess.processed));
            }
        });
    }

    private boolean loadStoryProgress(ApiWrap.FileProgress progress) {
        return _storiesProcess.fileProgress.run(new ApiWrap.DownloadProgress(_fileProcess.randomId, _fileProcess.relativePath, _storiesProcess.fileIndex, progress.ready(), progress.total()));
    }

    private void loadStoryThumbDone(String relativePath) {
        ApiWrap.File file = _storiesProcess.slice.list.get(_storiesProcess.fileIndex).thumb().file;
        file.relativePath = relativePath;
        if (relativePath.isEmpty()) {
            file.skipReason = ApiWrap.File.SkipReason.Unavailable;
        }
        loadNextStory();
    }

    private void loadStoryDone(String relativePath) {
        ApiWrap.File file = _storiesProcess.slice.list.get(_storiesProcess.fileIndex).file();
        file.relativePath = relativePath;
        if (relativePath.isEmpty()) {
            file.skipReason = ApiWrap.File.SkipReason.Unavailable;
        }
        loadNextStory();
    }

    public void requestContacts(Utilities.Callback<ApiWrap.ContactsList> done) {
        _contactsProcess = new ApiWrap.ContactsProcess();
        _contactsProcess.done = done;
        mainRequest(new ExportRequests.TL_contacts_getSaved(), (response, error) -> {
            if (response instanceof Vector) {
                _contactsProcess.result = DataTypesUtils.ParseContactsList((Vector) response);
                requestTopPeersSlice();
            }
        });
    }

    /**
     * Resolves user ids of saved contacts by phone number one by one. Not used in exteraGram lite.
     */
    @SuppressWarnings("unused")
    private void resolveContactPhone(int index) {
        if (index == _contactsProcess.result.list.size()) {
            requestTopPeersSlice();
            return;
        }
        TLRPC.TL_contacts_resolvePhone req = new TLRPC.TL_contacts_resolvePhone();
        req.phone = _contactsProcess.result.list.get(index).phoneNumber;
        mainRequest(req, (response, error) -> {
            if (response instanceof TLRPC.TL_contacts_resolvedPeer) {
                ApiWrap.ContactInfo contact = _contactsProcess.result.list.get(index);
                TLRPC.Peer peer = ((TLRPC.TL_contacts_resolvedPeer) response).peer;
                if (peer instanceof TLRPC.TL_peerUser) {
                    contact.userId = peer.user_id;
                } else {
                    contact.userId = 0L;
                }
                _contactsProcess.result.list.set(index, contact);
                resolveContactPhone(index + 1);
            } else if (error != null) {
                resolveContactPhone(index + 1);
            }
        });
    }

    private void requestTopPeersSlice() {
        TLRPC.TL_contacts_getTopPeers req = new TLRPC.TL_contacts_getTopPeers();
        req.correspondents = true;
        req.bots_inline = true;
        req.phone_calls = true;
        req.offset = _contactsProcess.topPeersOffset;
        req.limit = SLICE_LIMIT;
        req.hash = 0;
        mainRequest(req, (response, error) -> {
            if (!(response instanceof TLRPC.contacts_TopPeers)) {
                return;
            }
            TLRPC.contacts_TopPeers topPeers = (TLRPC.contacts_TopPeers) response;
            DataTypesUtils.AppendTopPeers(_contactsProcess.result, topPeers);
            int offset = _contactsProcess.topPeersOffset;
            boolean loaded;
            if (topPeers instanceof TLRPC.TL_contacts_topPeersNotModified || topPeers instanceof TLRPC.TL_contacts_topPeersDisabled) {
                loaded = true;
            } else if (topPeers instanceof TLRPC.TL_contacts_topPeers) {
                loaded = true;
                for (TLRPC.TL_topPeerCategoryPeers category : ((TLRPC.TL_contacts_topPeers) topPeers).categories) {
                    loaded = category.peers.size() + offset >= category.count;
                    if (!loaded) {
                        break;
                    }
                }
            } else {
                loaded = false;
            }
            if (loaded) {
                _contactsProcess.done.run(_contactsProcess.result);
            } else {
                _contactsProcess.topPeersOffset = Math.max(Math.max(
                    _contactsProcess.result.correspondents.size(),
                    _contactsProcess.result.inlineBots.size()),
                    _contactsProcess.result.phoneCalls.size());
                requestTopPeersSlice();
            }
        });
    }

    private void loadUserpicsFiles(ArrayList<HtmlWriter.Photo> slice) {
        if (slice.isEmpty()) {
            _userpicsProcess.lastSlice = true;
        }
        _userpicsProcess.slice = slice;
        _userpicsProcess.fileIndex = 0;
        loadNextUserpic();
    }

    private void loadNextUserpic() {
        ArrayList<HtmlWriter.Photo> list = _userpicsProcess.slice;
        for (; _userpicsProcess.fileIndex < list.size(); _userpicsProcess.fileIndex++) {
            boolean ready = processFileLoad(list.get(_userpicsProcess.fileIndex).image.file, new ApiWrap.FileOrigin(), this::loadUserpicProgress, this::loadUserpicDone, null, null);
            if (!ready) {
                return;
            }
        }
        finishUserpicsSlice();
    }

    private void finishUserpicsSlice() {
        ArrayList<HtmlWriter.Photo> slice = _userpicsProcess.slice;
        if (!slice.isEmpty()) {
            _userpicsProcess.processed += slice.size();
            _userpicsProcess.maxId = slice.get(slice.size() - 1).id;
            if (!_userpicsProcess.handleSlice.run(slice)) {
                return;
            }
        }
        if (_userpicsProcess.lastSlice) {
            _userpicsProcess.finish.run();
            return;
        }
        TLRPC.TL_photos_getUserPhotos req = new TLRPC.TL_photos_getUserPhotos();
        req.user_id = new TLRPC.TL_inputUserSelf();
        req.offset = 0;
        req.max_id = _userpicsProcess.maxId;
        req.limit = SLICE_LIMIT;
        mainRequest(req, (response, error) -> {
            if (response instanceof TLRPC.photos_Photos) {
                handleUserpicsSlice((TLRPC.photos_Photos) response);
            }
        });
    }

    public boolean loadUserpicProgress(ApiWrap.FileProgress progress) {
        return _userpicsProcess.fileProgress.run(new ApiWrap.DownloadProgress(_fileProcess.randomId, _fileProcess.relativePath, _userpicsProcess.fileIndex, progress.ready(), progress.total()));
    }

    private void loadUserpicDone(String relativePath) {
        ApiWrap.File file = _userpicsProcess.slice.get(_userpicsProcess.fileIndex).image.file;
        file.relativePath = relativePath;
        if (relativePath.isEmpty()) {
            file.skipReason = ApiWrap.File.SkipReason.Unavailable;
        }
        loadNextUserpic();
    }

    public boolean messageCustomEmojiReady(ApiWrap.Message message) {
        for (ApiWrap.TextPart part : message.text) {
            if (part.type == ApiWrap.TextPart.Type.CustomEmoji) {
                String path = getCustomEmoji(part.additional);
                if (path == null || path.isEmpty()) {
                    return false;
                }
                part.additional = path;
            }
        }
        // Reactions are not checked: ApiWrap.Reaction carries no data in lite (message.reactions is always empty).
        return true;
    }

    private String getCustomEmoji(String data) {
        Long id = Utilities.parseLong(data);
        if (id == 0) {
            return data;
        }
        ApiWrap.Document document = _resolvedCustomEmoji.get(id);
        if (document == null) {
            return ApiWrap.TextPart.UnavailableEmoji();
        }
        ApiWrap.File file = document.file;
        ApiWrap.FileOrigin origin = new ApiWrap.FileOrigin(0, null, 0, 0, id);
        if (!processFileLoad(file, origin, this::loadMessageFileProgress, path -> loadMessageEmojiDone(id, path), null, null)) {
            return null;
        }
        if (file.skipReason == ApiWrap.File.SkipReason.Unavailable) {
            return ApiWrap.TextPart.UnavailableEmoji();
        }
        if (file.skipReason == ApiWrap.File.SkipReason.FileType || file.skipReason == ApiWrap.File.SkipReason.FileSize) {
            return "";
        }
        return file.relativePath;
    }

    private void loadMessageEmojiDone(long id, String relativePath) {
        ApiWrap.Document document = _resolvedCustomEmoji.get(id);
        if (document != null) {
            document.file.relativePath = relativePath;
            if (relativePath.isEmpty()) {
                document.file.skipReason = ApiWrap.File.SkipReason.Unavailable;
            }
        }
        loadNextMessageFile();
    }

    private void finishMessagesSlice() {
        ApiWrap.MessagesSlice slice = _chatProcess.slice;
        if (!slice.list.isEmpty()) {
            _chatProcess.largestIdPlusOne = slice.list.get(slice.list.size() - 1).id + 1;
            if (_chatProcess.info.splits.get(_chatProcess.localSplitIndex) < 0) {
                slice = DataTypesUtils.AdjustMigrateMessageIds(slice);
            }
            if (!_chatProcess.handleSlice.run(slice)) {
                return;
            }
        }
        if (_chatProcess.lastSlice && ++_chatProcess.localSplitIndex < _chatProcess.info.splits.size()) {
            _chatProcess.lastSlice = false;
            _chatProcess.largestIdPlusOne = 1;
        }
        if (!_chatProcess.lastSlice) {
            requestMessagesSlice();
        } else {
            finishMessages();
        }
    }

    private void finishMessages() {
        _chatProcess.done.run();
    }

    private void requestChatMessages(int splitIndex, int offsetId, int addOffset, int limit, Utilities.Callback<TLRPC.messages_Messages> done) {
        _chatProcess.requestDone = done;
        TLRPC.InputPeer peer = splitIndex >= 0 ? _chatProcess.info.input : _chatProcess.info.migratedFromInput;
        int realSplitIndex = splitIndex >= 0 ? splitIndex : splits.size() + splitIndex;
        TLRPC.InputPeer fromId;
        if (_chatProcess.info.isMonoforum) {
            fromId = _chatProcess.info.monoforumBroadcastInput;
        } else {
            fromId = new TLRPC.TL_inputPeerSelf();
        }
        if (_chatProcess.info.onlyMyMessages) {
            TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
            req.flags = 1;
            req.peer = peer;
            req.q = "";
            req.from_id = fromId;
            req.saved_peer_id = new TLRPC.TL_inputPeerEmpty();
            req.top_msg_id = 0;
            req.filter = new TLRPC.TL_inputMessagesFilterEmpty();
            req.offset_id = offsetId;
            req.add_offset = addOffset;
            req.limit = limit;
            splitRequest(realSplitIndex, req, (response, error) -> _chatProcess.requestDone.run((TLRPC.messages_Messages) response));
            return;
        }
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = peer;
        req.offset_id = offsetId;
        req.add_offset = addOffset;
        req.limit = limit;
        splitRequest(realSplitIndex, req, (response, error) -> {
            if (error != null) {
                if (error.text.equals("CHANNEL_PRIVATE")) {
                    Log.d("exteraGram", "caught channel private");
                    if (peer instanceof TLRPC.TL_inputPeerChannel && !_chatProcess.info.onlyMyMessages) {
                        _chatProcess.info.onlyMyMessages = true;
                        requestChatMessages(splitIndex, offsetId, addOffset, limit, _chatProcess.requestDone);
                    }
                }
                return;
            }
            _chatProcess.requestDone.run((TLRPC.messages_Messages) response);
        });
    }

    public void requestDialogsSlice() {
        if (_settings.onlySinglePeer()) {
            requestSinglePeerDialog();
            return;
        }
        TLRPC.TL_messages_getDialogs req = new TLRPC.TL_messages_getDialogs();
        req.offset_peer = _dialogsProcess.offsetPeer;
        req.limit = SLICE_LIMIT;
        req.offset_date = _dialogsProcess.offsetDate;
        req.offset_id = _dialogsProcess.offsetId;
        splitRequest(_dialogsProcess.splitIndexPlusOne - 1, req, (response, error) -> {
            if (response instanceof TLRPC.TL_messages_dialogsNotModified) {
                return;
            }
            boolean finished = response instanceof TLRPC.TL_messages_dialogs
                || (response instanceof TLRPC.TL_messages_dialogsSlice && ((TLRPC.TL_messages_dialogsSlice) response).dialogs.size() < SLICE_LIMIT);

            ApiWrap.DialogsInfo info = ParseDialogsInfo((TLRPC.messages_Dialogs) response);
            _dialogsProcess.processedCount += info.chats.size();
            ApiWrap.DialogInfo last = info.chats.isEmpty() ? new ApiWrap.DialogInfo() : info.chats.get(info.chats.size() - 1);
            if (_dialogsProcess.info == null) {
                _dialogsProcess.info = new ApiWrap.DialogsInfo();
            }
            appendChatsSlice(_dialogsProcess, _dialogsProcess.info.chats, info.chats, _dialogsProcess.splitIndexPlusOne - 1);

            if (!finished && last.topMessageDate > 0) {
                _dialogsProcess.offsetId = last.topMessageId;
                _dialogsProcess.offsetDate = last.topMessageDate;
                _dialogsProcess.offsetPeer = last.input;
            } else {
                if (!useOnlyLastSplit() && --_dialogsProcess.splitIndexPlusOne > 0) {
                    _dialogsProcess.offsetId = 0;
                    _dialogsProcess.offsetDate = 0;
                    _dialogsProcess.offsetPeer = new TLRPC.TL_inputPeerEmpty();
                }
                requestLeftChannelsIfNeeded();
                return;
            }
            requestDialogsSlice();
        });
    }

    private void requestSinglePeerDialog() {
        Utilities.Callback<TLObject> handleResult = response -> {
            if (response instanceof Vector) {
                appendSinglePeerDialogs(ParseDialogsInfo(_settings.singlePeer, (Vector<?>) response));
            } else if (response instanceof TLRPC.messages_Chats) {
                appendSinglePeerDialogs(ParseDialogsInfo(_settings.singlePeer, (TLRPC.messages_Chats) response));
            }
        };
        Utilities.Callback<TLRPC.InputUser> requestUser = user -> {
            TLRPC.TL_users_getUsers req = new TLRPC.TL_users_getUsers();
            req.id = new ArrayList<>(Collections.singletonList(user));
            mainRequest(req, (response, error) -> handleResult.run(response));
        };

        TLRPC.InputPeer peer = _settings.singlePeer;
        if (peer instanceof TLRPC.TL_inputPeerUser) {
            TLRPC.TL_inputPeerUser peerUser = (TLRPC.TL_inputPeerUser) peer;
            TLRPC.TL_inputUser inputUser = new TLRPC.TL_inputUser();
            inputUser.access_hash = peerUser.access_hash;
            inputUser.user_id = peerUser.user_id;
            requestUser.run(inputUser);
        } else if (peer instanceof TLRPC.TL_inputPeerChat) {
            TLRPC.TL_messages_getChats req = new TLRPC.TL_messages_getChats();
            req.id = new ArrayList<>(Collections.singletonList(((TLRPC.TL_inputPeerChat) peer).chat_id));
            mainRequest(req, (response, error) -> handleResult.run(response));
        } else if (peer instanceof TLRPC.TL_inputPeerSelf) {
            requestUser.run(new TLRPC.TL_inputUserSelf());
        } else if (peer instanceof TLRPC.TL_inputPeerUserFromMessage || peer instanceof TLRPC.TL_inputPeerChannelFromMessage) {
            throw new IllegalStateException("From message peer in requestSinglePeerDialog.");
        } else if (peer instanceof TLRPC.TL_inputPeerEmpty) {
            throw new IllegalStateException("Empty peer in requestSinglePeerDialog.");
        }
    }

    private void appendSinglePeerDialogs(ApiWrap.DialogsInfo info) {
        int splitIndex = _dialogsProcess.splitIndexPlusOne - 1;
        int migratedRequestId = 0;
        for (ApiWrap.DialogInfo dialog : info.chats) {
            if (isSupergroupType(dialog.type) && migratedRequestId == 0) {
                migratedRequestId = requestSinglePeerMigrated(dialog);
            } else if (!isChannelType(dialog.type)) {
                for (int i = splitIndex; i != 0; i--) {
                    dialog.splits.add(i - 1);
                    dialog.messagesCountPerSplit.add(0);
                }
            }
        }
        if (migratedRequestId == 0) {
            _dialogsProcess.processedCount += info.chats.size();
        }
        appendChatsSlice(_dialogsProcess, _dialogsProcess.info.chats, info.chats, _dialogsProcess.splitIndexPlusOne - 1);
        if (migratedRequestId == 0 && _dialogsProcess.progress.run(_dialogsProcess.processedCount)) {
            finishDialogsList();
        }
    }

    private static boolean isSupergroupType(ApiWrap.DialogInfo.Type type) {
        return type == ApiWrap.DialogInfo.Type.PrivateSupergroup || type == ApiWrap.DialogInfo.Type.PublicSupergroup;
    }

    private static boolean isChannelType(ApiWrap.DialogInfo.Type type) {
        return type == ApiWrap.DialogInfo.Type.PrivateChannel || type == ApiWrap.DialogInfo.Type.PublicChannel;
    }

    private int requestSinglePeerMigrated(ApiWrap.DialogInfo info) {
        if (!(info.input instanceof TLRPC.TL_inputPeerChannel)) {
            throw new IllegalArgumentException("unexpected peer type: " + info.input);
        }
        TLRPC.TL_inputPeerChannel peerChannel = (TLRPC.TL_inputPeerChannel) info.input;
        TLRPC.TL_inputChannel inputChannel = new TLRPC.TL_inputChannel();
        inputChannel.channel_id = peerChannel.channel_id;
        inputChannel.access_hash = peerChannel.access_hash;
        TLRPC.TL_channels_getFullChannel req = new TLRPC.TL_channels_getFullChannel();
        req.channel = inputChannel;
        return mainRequest(req, (response, error) -> {
            if (!(response instanceof TLRPC.TL_messages_chatFull)) {
                return;
            }
            TLRPC.TL_messages_chatFull chatFull = (TLRPC.TL_messages_chatFull) response;
            long migratedFromChatId = chatFull.full_chat instanceof TLRPC.TL_channelFull ? chatFull.full_chat.migrated_from_chat_id : 0;
            if (migratedFromChatId != 0) {
                TLRPC.TL_inputPeerChat inputPeerChat = new TLRPC.TL_inputPeerChat();
                inputPeerChat.chat_id = migratedFromChatId;
                TLRPC.TL_messages_chats chats = new TLRPC.TL_messages_chats();
                chats.chats = chatFull.chats;
                appendSinglePeerDialogs(ParseDialogsInfo(inputPeerChat, chats));
            } else {
                appendSinglePeerDialogs(new ApiWrap.DialogsInfo());
            }
        });
    }

    public void requestLeftChannelsIfNeeded() {
        if ((_settings.types & ANY_CHANNELS_AND_GROUPS_MASK) != 0) {
            requestLeftChannelsList(
                count -> _dialogsProcess.progress.run(_dialogsProcess.processedCount + count),
                info -> {
                    _dialogsProcess.info.left = info.left;
                    finishDialogsList();
                }
            );
        } else {
            finishDialogsList();
        }
    }

    private void finishDialogsList() {
        DataTypesUtils.FinalizeDialogsInfo(_dialogsProcess.info, _settings);
        _dialogsProcess.done.run(_dialogsProcess.info);
    }

    private void requestLeftChannelsList(Utilities.CallbackReturn<Integer, Boolean> progress, Utilities.Callback<ApiWrap.DialogsInfo> done) {
        _leftChannelsProcess.progress = progress;
        _leftChannelsProcess.done = done;
        requestLeftChannelsSlice();
    }

    private void requestLeftChannelsSlice() {
        requestLeftChannelsSliceGeneric(() -> {
            if (_leftChannelsProcess.finished) {
                _leftChannelsProcess.done.run(_leftChannelsProcess.info);
            } else {
                requestLeftChannelsSlice();
            }
        });
    }

    private boolean goodByTypes(ApiWrap.DialogInfo info) {
        return (_settings.types & DataTypesUtils.SettingsFromDialogsType(info.type)) != 0;
    }

    private void appendChatsSlice(ApiWrap.ChatsProcess process, ArrayList<ApiWrap.DialogInfo> to, ArrayList<ApiWrap.DialogInfo> from, int splitIndex) {
        ArrayList<ApiWrap.DialogInfo> filtered = new ArrayList<>();
        for (ApiWrap.DialogInfo info : from) {
            if (goodByTypes(info)) {
                filtered.add(info);
            } else if (info.migratedToChannelId != 0 && ((_settings.types & 256) != 0 || (_settings.types & 128) != 0)) {
                filtered.add(info);
            }
        }
        to.ensureCapacity(to.size() + from.size());
        for (ApiWrap.DialogInfo info : filtered) {
            int nextIndex = to.size();
            if (info.migratedToChannelId != 0) {
                Integer toIndex = process.indexByPeer.get(info.migratedToChannelId);
                // TODO(openextera): decompile failed, verify: both continues restored after tdesktop's appendChatsSlice
                if (toIndex != null && DataTypesUtils.AddMigrateFromSlice(to.get(toIndex), info, splitIndex, splits.size())) {
                    continue;
                }
                if (!goodByTypes(info)) {
                    continue;
                }
            }
            Integer existing = process.indexByPeer.putIfAbsent(info.peerId, nextIndex);
            if (existing == null) {
                to.add(info);
            } else {
                nextIndex = existing;
            }
            to.get(nextIndex).splits.add(splitIndex);
            to.get(nextIndex).messagesCountPerSplit.add(0);
        }
    }

    private ApiWrap.DialogsInfo ParseDialogsInfo(TLRPC.messages_Dialogs dialogs) {
        ApiWrap.DialogsInfo result = new ApiWrap.DialogsInfo();
        if (dialogs == null || dialogs instanceof TLRPC.TL_messages_dialogsNotModified) {
            return result;
        }
        HashMap<Long, ApiWrap.Peer> peers = DataTypesUtils.ParsePeersLists(dialogs.users, dialogs.chats);
        HashMap<String, ApiWrap.Message> messages = ParseMessagesList(0, dialogs.messages, "");
        for (TLRPC.Dialog dialog : dialogs.dialogs) {
            ApiWrap.DialogInfo info = new ApiWrap.DialogInfo();
            info.peerId = MessageObject.getPeerId(dialog.peer);

            ApiWrap.Peer peer = peers.get(info.peerId);
            if (peer != null) {
                boolean isUser = peer.user != null;
                info.type = isUser ? DataTypesUtils.DialogTypeFromUser(peer.user) : DataTypesUtils.DialogTypeFromChat(peer.chat);
                info.name = isUser ? peer.user.info.firstName : peer.chat.title;
                if (info.name == null) {
                    info.name = "Deleted Account";
                }
                info.lastName = isUser && peer.user.info.lastName != null ? peer.user.info.lastName : "";
                info.colorIndex = peer.colorIndex();
                info.input = peer.getInput();
                info.migratedToChannelId = !isUser ? peer.chat.migratedToChannelId : 0;
                info.isMonoforum = peer.chat != null && peer.chat.isMonoforum;
                info.monoforumBroadcastInput = peer.chat != null ? peer.chat.monoforumBroadcastInput : new TLRPC.TL_inputPeerEmpty();
            }
            info.topMessageId = dialog.top_message;
            ApiWrap.Message topMessage = messages.get(info.peerId + "_" + info.topMessageId);
            if (topMessage != null) {
                info.topMessageDate = topMessage.date;
            }
            result.chats.add(info);
        }
        return result;
    }

    private HashMap<String, ApiWrap.Message> ParseMessagesList(long selfId, ArrayList<TLRPC.Message> data, String mediaFolder) {
        ApiWrap.ParseMediaContext context = new ApiWrap.ParseMediaContext();
        context.selfPeerId = selfId;
        LinkedHashMap<String, ApiWrap.Message> result = new LinkedHashMap<>();
        for (TLRPC.Message message : data) {
            ApiWrap.Message parsed = DataTypesUtils.ParseMessage(context, message, mediaFolder);
            result.put(parsed.peerId + "_" + parsed.id, parsed);
        }
        return result;
    }

    public void requestPersonalInfo(Utilities.Callback<ApiWrap.ExportPersonalInfo> done) {
        TLRPC.TL_users_getFullUser req = new TLRPC.TL_users_getFullUser();
        req.id = new TLRPC.TL_inputUserSelf();
        mainRequest(req, (response, error) -> {
            if (response instanceof TLRPC.TL_users_userFull) {
                TLRPC.TL_users_userFull userFull = (TLRPC.TL_users_userFull) response;
                if (userFull.users.isEmpty()) {
                    throw new IllegalArgumentException("got 0 users in requestPersonalInfo!");
                }
                done.run(DataTypesUtils.ParsePersonalInfo(userFull));
            }
        });
    }

    private int mainRequest(TLObject request, Utilities.Callback2<TLObject, TLRPC.TL_error> done) {
        ExportRequests.InvokeWithTakeoutWrapper wrapper = new ExportRequests.InvokeWithTakeoutWrapper();
        wrapper.query = request;
        wrapper.takeout_id = _takeoutId;
        return ConnectionsManager.getInstance(selectedAcc).sendRequest(wrapper, (response, error) ->
            ExportController.exportQueue.postRunnable(() -> done.run(response, error))
        );
    }

    public int splitRequest(int splitIndex, TLObject request, Utilities.Callback2<TLObject, TLRPC.TL_error> done) {
        ExportRequests.InvokeWithMessagesRange wrapper = new ExportRequests.InvokeWithMessagesRange();
        wrapper.query = request;
        if (splitIndex < 0) {
            wrapper.range = new TLRPC.TL_messageRange();
        } else {
            wrapper.range = splits.get(splitIndex);
        }
        return mainRequest(wrapper, done);
    }

    private int fileRequest(ApiWrap.FileLocation location, long offset, Utilities.Callback2<TLObject, TLRPC.TL_error> done) {
        TLRPC.TL_upload_getFile req = new TLRPC.TL_upload_getFile();
        req.location = location.data;
        req.offset = offset;
        req.limit = FILE_CHUNK_SIZE;
        req.cdn_supported = false;
        req.precise = false;
        req.flags = 0;
        ExportRequests.InvokeWithTakeoutWrapper wrapper = new ExportRequests.InvokeWithTakeoutWrapper();
        wrapper.query = req;
        wrapper.takeout_id = _takeoutId;
        return ConnectionsManager.getInstance(selectedAcc).sendRequestSync(wrapper, (response, error) -> {
            if (error != null) {
                _fileProcess.requestId = 0;
                if (Objects.equals(error.text, "TAKEOUT_FILE_EMPTY") && _otherDataProcess != null) {
                    TLRPC.TL_upload_file emptyFile = new TLRPC.TL_upload_file();
                    emptyFile.type = new TLRPC.TL_storage_filePartial();
                    filePartDone(0, emptyFile);
                } else if (Objects.equals(error.text, "LOCATION_INVALID") || Objects.equals(error.text, "VERSION_INVALID") || Objects.equals(error.text, "LOCATION_NOT_AVAILABLE")) {
                    Log.w("exteraGram", "Export Error: File unavailable.");
                    _fileProcess.done.run("");
                } else if (error.code == 400 && error.text.startsWith("FILE_REFERENCE")) {
                    filePartRefreshReference(offset);
                } else {
                    throw new IllegalStateException("wtf! fileRequest, response: " + response + " error: " + error.text);
                }
            }
            done.run(response, error);
        }, null, null, 0, location.dcId, ConnectionsManager.ConnectionTypeDownload2, true);
    }

    public void requestSplitRanges() {
        mainRequest(new ExportRequests.getSplitRanges(), (response, error) -> {
            vectorToRanges(response, splits);
            index = useOnlyLastSplit() ? splits.size() - 1 : 0;
            sendNextStartRequest();
        });
    }

    public void requestDialogsCount() {
        if (_settings.onlySinglePeer()) {
            _startProcess.info.dialogsCount = 1;
            sendNextStartRequest();
            return;
        }
        TLRPC.TL_messages_getDialogs req = new TLRPC.TL_messages_getDialogs();
        req.offset_peer = new TLRPC.TL_inputPeerEmpty();
        req.limit = 1;
        splitRequest(index, req, (response, error) -> {
            int count;
            if (response instanceof TLRPC.TL_messages_dialogs) {
                count = ((TLRPC.TL_messages_dialogs) response).dialogs.size();
            } else if (response instanceof TLRPC.TL_messages_dialogsSlice) {
                count = ((TLRPC.TL_messages_dialogsSlice) response).count;
            } else {
                count = -1;
            }
            if (count < 0) {
                throw new IllegalStateException("unexpected TL_messages_dialogsNotModified received");
            }
            _startProcess.info.dialogsCount += count;
            if (++_startProcess.splitIndex >= splits.size()) {
                sendNextStartRequest();
            } else {
                requestDialogsCount();
            }
        });
    }

    public void requestOtherData(String suggestedPath, Utilities.Callback<ApiWrap.File> done) {
        _otherDataProcess = new ApiWrap.OtherDataProcess();
        _otherDataProcess.done = done;
        _otherDataProcess.file.suggestedPath = suggestedPath;
        _otherDataProcess.file.location = new ApiWrap.FileLocation();
        _otherDataProcess.file.location.data = new ExportRequests.TL_inputTakeoutFileLocation();
        loadFile(_otherDataProcess.file, new ApiWrap.FileOrigin(), progress -> true, this::otherDataDone);
    }

    private void otherDataDone(String relativePath) {
        _otherDataProcess.file.relativePath = relativePath;
        if (relativePath.isEmpty()) {
            _otherDataProcess.file.skipReason = ApiWrap.File.SkipReason.Unavailable;
        }
        _otherDataProcess.done.run(_otherDataProcess.file);
    }

    public boolean useOnlyLastSplit() {
        return (_settings.types & PERSONAL_CHATS_MASK) == 0;
    }

    public void invokeFinish(boolean failed, Runnable done) {
        ExportRequests.FinishTakeoutSession req = new ExportRequests.FinishTakeoutSession();
        req.success = !failed;
        mainRequest(req, (response, error) -> {
            done.run();
            if (response instanceof TLRPC.TL_boolTrue) {
                Log.w("exteraGram", "finished successfully!!!");
                return;
            }
            Log.e("exteraGram", "failed: " + error);
        });
    }
}
