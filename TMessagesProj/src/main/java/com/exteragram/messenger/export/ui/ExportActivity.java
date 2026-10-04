package com.exteragram.messenger.export.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.view.View;

import com.exteragram.messenger.export.ExportSettings;
import com.exteragram.messenger.export.controllers.ExportController;
import com.exteragram.messenger.export.output.AbstractWriter;
import com.exteragram.messenger.preferences.BasePreferencesActivity;
import com.exteragram.messenger.preferences.SwitchGroup;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.Locale;
import java.util.function.BooleanSupplier;

public class ExportActivity extends BasePreferencesActivity implements NotificationCenter.NotificationCenterDelegate {

    private static final int REQUEST_CODE_OPEN_EXPORT = 1337;

    private static final CharSequence[] formats = {"HTML", "JSON", "HTML and JSON"};

    private final TLRPC.InputPeer peer;
    private final ExportSettings settings = new ExportSettings();

    private final SwitchGroup exportSettings = SwitchGroup.of(this, ExportItem.EXPORT_SETTINGS.getId(), "Main settings")
            .add(ExportItem.ACCOUNT_INFO.getId(), "Account Info", type(1), setType(1))
            .add(ExportItem.CONTACTS_LIST.getId(), "Contacts", type(4), setType(4))
            .add(ExportItem.STORY_ARCHIVE.getId(), "Stories", type(2048), setType(2048))
            .add(ExportItem.ACTIVE_SESSIONS.getId(), "Sessions", type(8), setType(8));

    private final SwitchGroup chatsSettings = SwitchGroup.of(this, ExportItem.CHATS_SETTINGS.getId(), "Chats settings")
            .add(ExportItem.PERSONAL_CHATS.getId(), "Personal chats", type(32), setType(32))
            .add(ExportItem.BOT_CHATS.getId(), "Bots", type(64), setType(64))
            .add(ExportItem.PRIVATE_GROUPS.getId(), "Private groups", type(128), setType(128))
            .add(ExportItem.PRIVATE_CHANNELS.getId(), "Private channels", type(512), setType(512))
            .add(ExportItem.PUBLIC_GROUPS.getId(), "Public groups", type(256), setType(256))
            .add(ExportItem.PUBLIC_CHANNELS.getId(), "Public channels", type(1024), setType(1024));

    private final SwitchGroup mediaSettings = SwitchGroup.of(this, ExportItem.MEDIA_SETTINGS.getId(), "Media settings")
            .add(ExportItem.PHOTOS.getId(), "Photos", media(1), setMedia(1))
            .add(ExportItem.VIDEOS.getId(), "Videos", media(2), setMedia(2))
            .add(ExportItem.VOICE_MESSAGES.getId(), "Voice messages", media(4), setMedia(4))
            .add(ExportItem.VIDEO_MESSAGES.getId(), "Video messages", media(8), setMedia(8))
            .add(ExportItem.STICKERS.getId(), "Stickers", media(16), setMedia(16))
            .add(ExportItem.GIFS.getId(), "GIFs", media(32), setMedia(32))
            .add(ExportItem.FILES.getId(), "Files", media(64), setMedia(64));

    public enum ExportItem {
        HEADER,
        EXPORT_SETTINGS,
        ACCOUNT_INFO,
        CONTACTS_LIST,
        STORY_ARCHIVE,
        ACTIVE_SESSIONS,
        CHATS_SETTINGS,
        PERSONAL_CHATS,
        BOT_CHATS,
        PRIVATE_GROUPS,
        PRIVATE_CHANNELS,
        PUBLIC_GROUPS,
        PUBLIC_CHANNELS,
        MEDIA_SETTINGS,
        PHOTOS,
        VIDEOS,
        VOICE_MESSAGES,
        VIDEO_MESSAGES,
        STICKERS,
        GIFS,
        FILES,
        FORMAT,
        START_EXPORT,
        VIEW_JSON_EXPORT;

        public int getId() {
            return ordinal() + 1;
        }
    }

    public ExportActivity(TLRPC.InputPeer peer) {
        this.peer = peer;
    }

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter notificationCenter = NotificationCenter.getInstance(currentAccount);
        notificationCenter.addObserver(this, ExportController.INITIALIZATING_NOTIFICATION);
        notificationCenter.addObserver(this, ExportController.DIALOGS_LIST_NOTIFICATION);
        notificationCenter.addObserver(this, ExportController.PERSONAL_INFO_NOTIFICATION);
        notificationCenter.addObserver(this, ExportController.USERPICS_NOTIFICATION);
        notificationCenter.addObserver(this, ExportController.STORIES_NOTIFICATION);
        notificationCenter.addObserver(this, ExportController.CONTACTS_NOTIFICATION);
        notificationCenter.addObserver(this, ExportController.SESSIONS_NOTIFICATION);
        notificationCenter.addObserver(this, ExportController.OTHER_DATA_NOTIFICATION);
        notificationCenter.addObserver(this, ExportController.DIALOGS_NOTIFICATION);
        notificationCenter.addObserver(this, ExportController.FINISH_NOTIFICATION);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter notificationCenter = NotificationCenter.getInstance(currentAccount);
        notificationCenter.removeObserver(this, ExportController.INITIALIZATING_NOTIFICATION);
        notificationCenter.removeObserver(this, ExportController.DIALOGS_LIST_NOTIFICATION);
        notificationCenter.removeObserver(this, ExportController.PERSONAL_INFO_NOTIFICATION);
        notificationCenter.removeObserver(this, ExportController.USERPICS_NOTIFICATION);
        notificationCenter.removeObserver(this, ExportController.STORIES_NOTIFICATION);
        notificationCenter.removeObserver(this, ExportController.CONTACTS_NOTIFICATION);
        notificationCenter.removeObserver(this, ExportController.SESSIONS_NOTIFICATION);
        notificationCenter.removeObserver(this, ExportController.OTHER_DATA_NOTIFICATION);
        notificationCenter.removeObserver(this, ExportController.DIALOGS_NOTIFICATION);
        notificationCenter.removeObserver(this, ExportController.FINISH_NOTIFICATION);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == ExportController.FINISH_NOTIFICATION) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, "Export complete!").show();
            return;
        }
        ExportController.ProcessingState state = (ExportController.ProcessingState) args[0];
        float progress = state.substepsPassed / state.substepsTotal;
        String text;
        if (state.bytesCount > 0) {
            text = String.format(Locale.US, "Downloading %s\n%s / %s", state.bytesName, AndroidUtilities.formatFileSize(state.bytesLoaded), AndroidUtilities.formatFileSize(state.bytesCount));
        } else if (state.entityCount > 0) {
            text = String.format(Locale.US, "Exporting %s (%d / %d)\nMessage %d / %d", state.entityName, state.entityIndex + 1, state.entityCount, state.itemIndex, state.itemCount);
        } else {
            text = "Exporting " + state.step.name();
        }
        FileLog.e("[EXPORT] " + text + ", " + progress);
    }

    @Override
    public String getTitle() {
        return "Export Chats";
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader("Export settings"));
        exportSettings.fill(items);
        chatsSettings.fill(items);
        mediaSettings.fill(items);
        items.add(UItem.asShadow());
        items.add(UItem.asButton(ExportItem.FORMAT.getId(), "Select export result type", formats[getIndexOfFormat()]));
        items.add(UItem.asButton(ExportItem.START_EXPORT.getId(), "Start Export"));
        items.add(UItem.asButton(ExportItem.VIEW_JSON_EXPORT.getId(), "Open Json Export"));
        items.add(UItem.asShadow("Here you can export your chats."));
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id <= 0 || item.id > ExportItem.values().length) {
            return;
        }
        switch (ExportItem.values()[item.id - 1]) {
            case EXPORT_SETTINGS:
            case ACCOUNT_INFO:
            case CONTACTS_LIST:
            case STORY_ARCHIVE:
            case ACTIVE_SESSIONS:
                exportSettings.onClick(item);
                break;
            case CHATS_SETTINGS:
            case PERSONAL_CHATS:
            case BOT_CHATS:
            case PRIVATE_GROUPS:
            case PRIVATE_CHANNELS:
            case PUBLIC_GROUPS:
            case PUBLIC_CHANNELS:
                chatsSettings.onClick(item);
                break;
            case MEDIA_SETTINGS:
            case PHOTOS:
            case VIDEOS:
            case VOICE_MESSAGES:
            case VIDEO_MESSAGES:
            case STICKERS:
            case GIFS:
            case FILES:
                mediaSettings.onClick(item);
                break;
            case FORMAT:
                showListDialog(item, formats, "Select export result type", getIndexOfFormat(), which -> {
                    if (which == 0) {
                        settings.format = AbstractWriter.Format.Html;
                    } else if (which == 1) {
                        settings.format = AbstractWriter.Format.Json;
                    } else {
                        settings.format = AbstractWriter.Format.HtmlAndJson;
                    }
                });
                break;
            case START_EXPORT:
                if (peer != null) {
                    settings.singlePeer = peer;
                }
                BulletinFactory.of(this).createErrorBulletin("Starting export...").show();
                settings.media.sizeLimit = 2000L * 1024 * 1024;
                ExportController.getInstance(UserConfig.selectedAccount).startExport(settings);
                break;
            case VIEW_JSON_EXPORT:
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addCategory(Intent.CATEGORY_DEFAULT);
                startActivityForResult(Intent.createChooser(intent, "Choose a directory"), REQUEST_CODE_OPEN_EXPORT);
                break;
            default:
                break;
        }
        listView.adapter.update(true);
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_OPEN_EXPORT && resultCode == Activity.RESULT_OK && data != null) {
            try {
                Uri treeUri = data.getData();
                if (treeUri == null) {
                    return;
                }
                Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
                presentFragment(new DialogsView(AndroidPickerUtils.getPath(getParentActivity(), documentUri)));
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    private BooleanSupplier type(int type) {
        return () -> (settings.types & type) != 0;
    }

    private SwitchGroup.Setter setType(int type) {
        return value -> {
            if (value) {
                settings.types |= type;
            } else {
                settings.types &= ~type;
            }
        };
    }

    private BooleanSupplier media(int type) {
        return () -> (settings.media.type & type) != 0;
    }

    private SwitchGroup.Setter setMedia(int type) {
        return value -> {
            if (value) {
                settings.media.type |= type;
            } else {
                settings.media.type &= ~type;
            }
        };
    }

    private int getIndexOfFormat() {
        if (settings.format == AbstractWriter.Format.Json) {
            return 1;
        }
        return settings.format == AbstractWriter.Format.HtmlAndJson ? 2 : 0;
    }
}
