package com.exteragram.messenger.components;

import android.app.Activity;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;

import com.exteragram.messenger.utils.JpegFingerprint;
import com.exteragram.messenger.utils.MediaUtils;
import com.exteragram.messenger.utils.chats.ChatUtils;
import com.google.zxing.Dimension;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PopupSwipeBackLayout;
import org.telegram.ui.ProfileActivity;

import java.io.File;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;

public abstract class MessageDetailsPopupWrapper {

    private static final int SET_OWNER = 0;
    private static final int FILE_PATH = 1;
    private static final int LOCATION = 2;
    private static final int BITRATE = 3;
    private static final int RESOLUTION = 4;
    private static final int PLATFORM = 5;

    private static final int MAX_HEIGHT = 380;

    public LinearLayout swipeBack;

    private final BaseFragment fragment;
    private final Theme.ResourcesProvider resourcesProvider;

    private long ownerId = 0;
    private String filePath;
    private String[] geo;
    private JpegFingerprint photoFingerprint;

    public MessageDetailsPopupWrapper(BaseFragment fragment, PopupSwipeBackLayout swipeBackLayout, MessageObject messageObject, Theme.ResourcesProvider resourcesProvider) {
        this.fragment = fragment;
        this.resourcesProvider = resourcesProvider;
        Activity activity = fragment.getParentActivity();

        swipeBack = new LinearLayout(activity);
        swipeBack.setOrientation(LinearLayout.VERTICAL);

        ScrollView scrollView = new ScrollView(activity) {
            final AnimatedFloat alphaFloat = new AnimatedFloat(this, 350, CubicBezierInterpolator.EASE_OUT_QUINT);
            private boolean wasCanScrollVertically;
            Drawable topShadowDrawable;

            @Override
            public void onNestedScroll(View target, int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed) {
                super.onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed);
                boolean canScrollVertically = canScrollVertically(-1);
                if (wasCanScrollVertically != canScrollVertically) {
                    invalidate();
                    wasCanScrollVertically = canScrollVertically;
                }
            }

            @Override
            protected void dispatchDraw(Canvas canvas) {
                super.dispatchDraw(canvas);
                float alpha = .5f * alphaFloat.set(canScrollVertically(-1) ? 1f : 0f);
                if (alpha > 0) {
                    if (topShadowDrawable == null) {
                        topShadowDrawable = ContextCompat.getDrawable(getContext(), R.drawable.header_shadow);
                    }
                    if (topShadowDrawable != null) {
                        topShadowDrawable.setBounds(0, getScrollY(), getWidth(), getScrollY() + topShadowDrawable.getIntrinsicHeight());
                        topShadowDrawable.setAlpha((int) (0xFF * alpha));
                        topShadowDrawable.draw(canvas);
                    }
                }
            }
        };
        LinearLayout linearLayout = new LinearLayout(activity);
        scrollView.addView(linearLayout);
        linearLayout.setOrientation(LinearLayout.VERTICAL);

        ActionBarMenuSubItem backItem = new ActionBarMenuSubItem(fragment.getParentActivity(), true, false, resourcesProvider);
        backItem.setItemHeight(44);
        backItem.setTextAndIcon(LocaleController.getString(R.string.Back), R.drawable.msg_arrow_back);
        backItem.getTextView().setPadding(LocaleController.isRTL ? 0 : AndroidUtilities.dp(40), 0, LocaleController.isRTL ? AndroidUtilities.dp(40) : 0, 0);
        backItem.setOnClickListener(view -> swipeBackLayout.closeForeground());
        swipeBack.addView(backItem, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        linearLayout.addView(createGap(), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8));

        TLRPC.Message message = messageObject.messageOwner;
        ArrayList<Item> items = new ArrayList<>();
        if (message.views > 0) {
            items.add(new Item(R.drawable.msg_view_file, String.format(LocaleController.getPluralString("Views", message.views), AndroidUtilities.formatCount(message.views)), (String) null));
        }
        if (message.forwards > 0) {
            items.add(new Item(R.drawable.msg_forward, String.format(LocaleController.getPluralString("Shares", message.forwards), AndroidUtilities.formatCount(message.forwards)), (String) null));
        }
        if (!items.isEmpty()) {
            items.add(null);
        }
        items.add(new Item(R.drawable.msg_info, "ID", message.id));
        if (message.date > 0) {
            items.add(new Item(R.drawable.msg_calendar2, LocaleController.getString(R.string.Date), formatTime(message.date, true)));
        }
        if (message.fwd_from != null && message.fwd_from.date > 0 && message.fwd_from.date != message.date) {
            items.add(new Item(R.drawable.msg_recent, LocaleController.getString(R.string.ForwardedDate), formatTime(message.fwd_from.date, true)));
        }
        if (message.edit_date > 0 && message.edit_date != message.date && !message.edit_hide) {
            items.add(new Item(R.drawable.msg_edit, LocaleController.getString(R.string.EditedDate), formatTime(message.edit_date, true)));
        }
        items.add(null);
        if (messageObject.getSize() > 0) {
            items.add(new Item(R.drawable.msg_sendfile, LocaleController.getString(R.string.FileSize), AndroidUtilities.formatFileSize(messageObject.getSize())));
        }
        if (messageObject.getMimeType() != null && !messageObject.getMimeType().isEmpty()) {
            items.add(new Item(R.drawable.msg_media, LocaleController.getString(R.string.MimeType), messageObject.getMimeType()));
        }
        TLRPC.MessageMedia media = MessageObject.getMedia(message);
        if (media != null && media.document != null) {
            for (TLRPC.DocumentAttribute attribute : media.document.attributes) {
                if (attribute instanceof TLRPC.TL_documentAttributeFilename) {
                    items.add(new Item(R.drawable.msg_log, LocaleController.getString(R.string.FileName), attribute.file_name));
                }
                if (attribute instanceof TLRPC.TL_documentAttributeSticker && attribute.stickerset != null) {
                    ownerId = ChatUtils.extractOwnerId(attribute.stickerset.id);
                    if (ownerId > 0) {
                        items.add(new Item(SET_OWNER, R.drawable.msg_sticker, LocaleController.getString(R.string.ChannelCreator), String.valueOf(ownerId)));
                    }
                }
            }
        }
        filePath = ChatUtils.getInstance().getPathToMessage(messageObject);
        if (!TextUtils.isEmpty(filePath)) {
            items.add(new Item(FILE_PATH, R.drawable.msg_map, LocaleController.getString(R.string.FilePath), LocaleController.getString(R.string.Open)));
        }

        boolean isAudio = messageObject.isVoice() || messageObject.isMusic();
        boolean isVideo = messageObject.isVideo() || messageObject.isRoundVideo() || messageObject.isVideoSticker() || messageObject.isGif();
        boolean isPhotoAsDocument = isPhotoAsDocument(messageObject);
        boolean isPhoto = isPhotoAsDocument || messageObject.isPhoto() || messageObject.isSticker();

        if (isPhoto && !TextUtils.isEmpty(filePath)) {
            items.add(new Item(PLATFORM, R.drawable.menu_devices, LocaleController.getString(R.string.Platform), LocaleController.getString(R.string.NumberUnknown)));
        }
        if (isVideo || isPhoto) {
            items.add(new Item(RESOLUTION, R.drawable.msg_photo_crop, LocaleController.getString(R.string.Resolution), "0x0"));
        }
        if (isPhotoAsDocument && !TextUtils.isEmpty(filePath)) {
            items.add(new Item(LOCATION, R.drawable.msg_location, LocaleController.getString(R.string.ShareLocation), "0.0, 0.0"));
        }
        if (isVideo || isAudio) {
            items.add(new Item(BITRATE, R.drawable.msg_noise_on, LocaleController.getString(R.string.Bitrate), "0 Kbps"));
            int duration = (int) messageObject.getDuration();
            if (duration > 0) {
                items.add(new Item(R.drawable.msg2_animations, LocaleController.getString(R.string.Duration), AndroidUtilities.formatShortDuration(duration)));
            }
        }

        int dc = getDcId(media);
        if (dc != 0) {
            items.add(new Item(R.drawable.msg_satellite, LocaleController.getString(R.string.Datacenter), String.format(Locale.ROOT, "DC%d, %s", dc, ChatUtils.getDCName(dc))));
        }
        if (items.get(items.size() - 1) == null) {
            items.remove(items.size() - 1);
        }

        int totalHeight = 0;
        for (Item item : items) {
            if (item == null) {
                linearLayout.addView(createGap(), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8));
                totalHeight += 8;
                continue;
            }
            ActionBarMenuSubItem subItem = new ActionBarMenuSubItem(fragment.getParentActivity(), false, false, resourcesProvider);
            subItem.setTextAndIcon(item.title, item.resId);
            subItem.setMinimumWidth(AndroidUtilities.dp(196));
            linearLayout.addView(subItem, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
            if (item.subtitle != null) {
                subItem.setSubtext(item.subtitle);
                subItem.subtextView.setEllipsize(TextUtils.TruncateAt.MARQUEE);
                subItem.subtextView.setMarqueeRepeatLimit(-1);
                subItem.subtextView.setSelected(true);
                subItem.setItemHeight(56);
                totalHeight += 56;
            } else {
                totalHeight += 48;
            }

            if (item.id == SET_OWNER && ownerId > 0) {
                ChatUtils.getInstance().searchUserById(ownerId, user -> {
                    if (user != null) {
                        if (!TextUtils.isEmpty(UserObject.getPublicUsername(user))) {
                            item.subtitle = "@" + UserObject.getPublicUsername(user);
                        } else {
                            item.subtitle = ContactsController.formatName(user);
                        }
                        subItem.setSubtext(item.subtitle);
                    }
                });
            } else if (item.id == LOCATION) {
                ChatUtils.utilsQueue.postRunnable(() -> {
                    geo = getLatLongFromPhoto(new File(filePath));
                    AndroidUtilities.runOnUIThread(() -> {
                        if (geo != null) {
                            item.subtitle = geo[0] + ", " + geo[1];
                            subItem.setSubtext(item.subtitle);
                        } else {
                            subItem.setVisibility(View.GONE);
                        }
                    });
                });
            } else if (item.id == BITRATE) {
                ChatUtils.utilsQueue.postRunnable(() -> {
                    int bitrate = getBitrate(messageObject, filePath);
                    AndroidUtilities.runOnUIThread(() -> {
                        if (bitrate > 0) {
                            item.subtitle = bitrate + " Kbps";
                            subItem.setSubtext(item.subtitle);
                        } else {
                            subItem.setVisibility(View.GONE);
                        }
                    });
                });
            } else if (item.id == RESOLUTION) {
                ChatUtils.utilsQueue.postRunnable(() -> {
                    Dimension resolution = isVideo ? getVideoResolution(messageObject, filePath) : getPhotoResolution(messageObject, filePath);
                    AndroidUtilities.runOnUIThread(() -> {
                        if (resolution != null) {
                            item.subtitle = resolution.toString();
                            subItem.setSubtext(item.subtitle);
                        } else {
                            subItem.setVisibility(View.GONE);
                        }
                    });
                });
            } else if (item.id == PLATFORM) {
                ChatUtils.utilsQueue.postRunnable(() -> {
                    photoFingerprint = JpegFingerprint.parse(filePath);
                    String platform = MediaUtils.getPhotoPlatform(photoFingerprint);
                    AndroidUtilities.runOnUIThread(() -> {
                        if (!TextUtils.isEmpty(platform)) {
                            item.subtitle = platform;
                            subItem.setSubtext(platform);
                        } else {
                            subItem.setVisibility(View.GONE);
                        }
                    });
                });
            }

            subItem.setTag(item);
            subItem.setOnClickListener(view -> onItemClick(item, activity, isPhoto, isVideo, messageObject, fragment));
            subItem.setOnLongClickListener(view -> {
                String text;
                if (item.id == FILE_PATH && !TextUtils.isEmpty(filePath)) {
                    text = filePath;
                } else if (item.id == SET_OWNER) {
                    text = String.valueOf(ownerId);
                } else if (item.id == PLATFORM && photoFingerprint != null) {
                    text = photoFingerprint.describe();
                } else {
                    text = item.subtitle != null ? item.subtitle : item.title;
                }
                copy(text);
                return true;
            });
        }

        if (totalHeight > MAX_HEIGHT && Math.abs(totalHeight - MAX_HEIGHT) > 112) {
            swipeBack.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, MAX_HEIGHT));
        } else {
            swipeBack.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
    }

    public void closeMenu() {

    }

    public abstract void copy(String text);

    private void onItemClick(Item item, Activity activity, boolean isPhoto, boolean isVideo, MessageObject messageObject, BaseFragment fragment) {
        closeMenu();
        if (item.id == FILE_PATH && !TextUtils.isEmpty(filePath)) {
            try {
                Uri uri = FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", new File(filePath));
                if (isPhoto || isVideo) {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    intent.setDataAndType(uri, messageObject.getMimeType());
                    if (!activity.getPackageManager().queryIntentActivities(intent, 0).isEmpty()) {
                        activity.startActivity(intent);
                        return;
                    }
                }
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.putExtra(Intent.EXTRA_STREAM, uri);
                intent.setDataAndType(uri, messageObject.getMimeType());
                activity.startActivityForResult(Intent.createChooser(intent, LocaleController.getString(R.string.ShareFile)), 500);
            } catch (IllegalArgumentException e) {
                FileLog.e(e);
            }
            return;
        }
        if (item.id == SET_OWNER) {
            if (item.subtitle.startsWith("@")) {
                Bundle args = new Bundle();
                args.putLong("user_id", ownerId);
                fragment.presentFragment(new ProfileActivity(args));
            } else {
                copy(String.valueOf(ownerId));
            }
            return;
        }
        if (item.id == LOCATION) {
            Browser.openUrl(fragment.getParentActivity(), String.format("https://maps.google.com/?q=%s,%s", geo[0], geo[1]));
            return;
        }
        copy(item.subtitle != null ? item.subtitle : item.title);
    }

    private static int getDcId(TLRPC.MessageMedia media) {
        if (media == null) {
            return 0;
        }
        if (media.photo != null && media.photo.dc_id > 0) {
            return media.photo.dc_id;
        }
        if (media.document != null && media.document.dc_id > 0) {
            return media.document.dc_id;
        }
        if (media.webpage != null && media.webpage.photo != null && media.webpage.photo.dc_id > 0) {
            return media.webpage.photo.dc_id;
        }
        if (media.webpage != null && media.webpage.document != null && media.webpage.document.dc_id > 0) {
            return media.webpage.document.dc_id;
        }
        return 0;
    }

    public static int getBitrate(MessageObject messageObject, String path) {
        int bitrate = -1;
        if (!TextUtils.isEmpty(path)) {
            try {
                bitrate = getBitrateFromPath(path);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        if (bitrate != -1) {
            return bitrate;
        }
        try {
            return getBitrateFromAttributes(messageObject);
        } catch (Exception e) {
            FileLog.e(e);
            return bitrate;
        }
    }

    public static int getBitrateFromPath(String path) {
        int bitrate;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            Objects.requireNonNull(value);
            bitrate = Integer.parseInt(value) / 1000;
        } catch (Exception e) {
            FileLog.e(e);
            bitrate = -1;
        }
        try {
            retriever.release();
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return bitrate;
    }

    public static int getBitrateFromAttributes(MessageObject messageObject) {
        long size = MessageObject.getMessageSize(messageObject.messageOwner);
        TLRPC.MessageMedia media = MessageObject.getMedia(messageObject.messageOwner);
        if (size > 0 && media != null && media.document != null) {
            for (TLRPC.DocumentAttribute attribute : media.document.attributes) {
                if (attribute instanceof TLRPC.TL_documentAttributeAudio || attribute instanceof TLRPC.TL_documentAttributeVideo) {
                    double duration = attribute.duration;
                    if (duration > 0) {
                        return (int) (size / duration * 8 / 1000);
                    }
                }
            }
        }
        return -1;
    }

    public static Dimension getPhotoResolution(MessageObject messageObject, String path) {
        Dimension resolution = null;
        if (!TextUtils.isEmpty(path)) {
            try {
                resolution = getPhotoResolutionFromPath(path);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        if (resolution != null) {
            return resolution;
        }
        try {
            return getPhotoResolutionFromAttributes(messageObject);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    public static Dimension getPhotoResolutionFromPath(String path) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);
        return new Dimension(options.outWidth, options.outHeight);
    }

    public static Dimension getPhotoResolutionFromAttributes(MessageObject messageObject) {
        TLRPC.MessageMedia media = MessageObject.getMedia(messageObject.messageOwner);
        if (media != null && media.photo != null) {
            Dimension resolution = null;
            TLRPC.PhotoSize photoSize = FileLoader.getClosestPhotoSizeWithSize(media.photo.sizes, AndroidUtilities.getPhotoSize(), false, null, true);
            if (photoSize != null && photoSize.w > 0 && photoSize.h > 0) {
                resolution = new Dimension(photoSize.w, photoSize.h);
            }
            if (resolution == null) {
                TLRPC.VideoSize videoSize = FileLoader.getClosestVideoSizeWithSize(media.photo.video_sizes, AndroidUtilities.getPhotoSize(), false, true);
                if (videoSize != null && videoSize.w > 0 && videoSize.h > 0) {
                    resolution = new Dimension(videoSize.w, videoSize.h);
                }
            }
            return resolution;
        }
        if (media != null && media.document != null) {
            for (TLRPC.DocumentAttribute attribute : media.document.attributes) {
                if (attribute instanceof TLRPC.TL_documentAttributeImageSize && attribute.w > 0 && attribute.h > 0) {
                    return new Dimension(attribute.w, attribute.h);
                }
            }
        }
        return null;
    }

    public static Dimension getVideoResolution(MessageObject messageObject, String path) {
        Dimension resolution = null;
        if (!TextUtils.isEmpty(path)) {
            try {
                resolution = getVideoResolutionFromPath(path);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        if (resolution != null) {
            return resolution;
        }
        try {
            return getVideoResolutionFromAttributes(messageObject);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    public static Dimension getVideoResolutionFromPath(String path) {
        int width = 0;
        int height = 0;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            String widthValue = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            Objects.requireNonNull(widthValue);
            width = Integer.parseInt(widthValue);
            String heightValue = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            Objects.requireNonNull(heightValue);
            height = Integer.parseInt(heightValue);
        } catch (Exception e) {
            FileLog.e(e);
        }
        try {
            retriever.release();
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return new Dimension(width, height);
    }

    public static Dimension getVideoResolutionFromAttributes(MessageObject messageObject) {
        TLRPC.MessageMedia media = MessageObject.getMedia(messageObject.messageOwner);
        if (media == null || media.document == null) {
            return null;
        }
        for (TLRPC.DocumentAttribute attribute : media.document.attributes) {
            if (attribute instanceof TLRPC.TL_documentAttributeVideo && attribute.w > 0 && attribute.h > 0) {
                return new Dimension(attribute.w, attribute.h);
            }
        }
        return null;
    }

    public static String[] getLatLongFromPhoto(File file) {
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            String latitude = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE);
            String longitude = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE);
            String latitudeRef = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF);
            String longitudeRef = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF);
            if (latitude == null || longitude == null || latitudeRef == null || longitudeRef == null) {
                return null;
            }
            double lat = convertToDegrees(latitude);
            if ("S".equalsIgnoreCase(latitudeRef)) {
                lat = -lat;
            }
            double lon = convertToDegrees(longitude);
            if ("W".equalsIgnoreCase(longitudeRef)) {
                lon = -lon;
            }
            DecimalFormat format = new DecimalFormat("#.######");
            format.setDecimalFormatSymbols(DecimalFormatSymbols.getInstance(Locale.ENGLISH));
            return new String[]{format.format(lat), format.format(lon)};
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static double convertToDegrees(String value) {
        String[] parts = value.split(",");
        return convertToDouble(parts[0]) + convertToDouble(parts[1]) / 60 + convertToDouble(parts[2]) / 3600;
    }

    private static double convertToDouble(String value) {
        String[] parts = value.split("/");
        if (parts.length == 1) {
            return Double.parseDouble(parts[0]);
        } else if (parts.length == 2) {
            double numerator = Double.parseDouble(parts[0]);
            double denominator = Double.parseDouble(parts[1]);
            if (denominator != 0) {
                return numerator / denominator;
            }
            FileLog.e("Division by zero in GPS data");
            return 0;
        }
        FileLog.e("Invalid rational number format: " + value);
        return 0;
    }

    private boolean isPhotoAsDocument(MessageObject messageObject) {
        try {
            TLRPC.MessageMedia media = MessageObject.getMedia(messageObject.messageOwner);
            if (media != null && media.document != null) {
                for (TLRPC.DocumentAttribute attribute : media.document.attributes) {
                    if (attribute instanceof TLRPC.TL_documentAttributeImageSize && attribute.w > 0 && attribute.h > 0) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    private String formatTime(int timestamp, boolean withSeconds) {
        if (timestamp == 0x7ffffffe) {
            return LocaleController.getString(R.string.SendWhenOnline);
        }
        if (withSeconds) {
            long millis = timestamp * 1000L;
            return LocaleController.formatString("formatDateAtTime", R.string.formatDateAtTime,
                    LocaleController.getInstance().getFormatterYear().format(new Date(millis)),
                    LocaleController.getInstance().getFormatterDayWithSeconds().format(new Date(millis)));
        }
        return LocaleController.formatDateAudio(timestamp, true);
    }

    private View createGap() {
        ActionBarPopupWindow.GapView gap = new ActionBarPopupWindow.GapView(fragment.getContext(), resourcesProvider);
        gap.setDividerVisible(false);
        return gap;
    }

    public static class Item {
        int id;
        int resId;
        String title;
        String subtitle;

        public Item(int resId, String title, String subtitle) {
            this(-1, resId, title, subtitle);
        }

        public Item(int resId, String title, int value) {
            this(-1, resId, title, String.valueOf(value));
        }

        public Item(int id, int resId, String title, String subtitle) {
            this.id = id;
            this.resId = resId;
            this.title = title;
            this.subtitle = subtitle;
        }
    }
}
