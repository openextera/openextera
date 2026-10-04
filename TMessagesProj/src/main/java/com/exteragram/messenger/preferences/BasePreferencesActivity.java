package com.exteragram.messenger.preferences;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.os.Build;
import android.os.Parcelable;
import android.view.View;
import android.widget.FrameLayout;

import androidx.collection.LongSparseArray;
import androidx.core.util.Consumer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.exteragram.messenger.preferences.utils.SettingsRegistry;
import com.exteragram.messenger.utils.ui.ChatHeaderUiHelper;
import com.exteragram.messenger.utils.ui.PopupUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ShareAlert;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.DownscaleScrollableNoiseSuppressor;
import org.telegram.ui.Components.blur3.ViewGroupPartRenderer;
import org.telegram.ui.Components.blur3.capture.IBlur3Capture;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;
import org.telegram.ui.Components.chat.ViewPositionWatcher;
import org.telegram.ui.Components.chat.layouts.ChatActivityFadeView;

import java.util.ArrayList;

public abstract class BasePreferencesActivity extends BaseFragment {

    protected ChatActivityFadeView fadeView;
    protected LinearLayoutManager layoutManager;
    protected UniversalRecyclerView listView;

    private float headerFadeAlpha;
    private ValueAnimator headerFadeAnimator;
    private Parcelable recyclerViewState;
    private boolean headerFadeShown;

    private IBlur3Capture iBlur3Capture;
    private final RectF iBlur3PositionActionBar = new RectF();
    private final ArrayList<RectF> iBlur3Positions = new ArrayList<>();
    private final BlurredBackgroundSourceColor iBlur3SourceColor = new BlurredBackgroundSourceColor();
    private final BlurredBackgroundSourceRenderNode iBlur3SourceGlass;
    private final DownscaleScrollableNoiseSuppressor scrollableViewNoiseSuppressor;

    public abstract void fillItems(ArrayList<UItem> items, UniversalAdapter adapter);

    public abstract String getTitle();

    public abstract void onClick(UItem item, View view, int position, float x, float y);

    public boolean hasHeaderFade() {
        return true;
    }

    public void initializeOptionStrings() {
    }

    public boolean needHideTitle() {
        return false;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    public BasePreferencesActivity() {
        iBlur3Positions.add(iBlur3PositionActionBar);
        if (Build.VERSION.SDK_INT >= 31) {
            scrollableViewNoiseSuppressor = new DownscaleScrollableNoiseSuppressor();
            iBlur3SourceGlass = new BlurredBackgroundSourceRenderNode(iBlur3SourceColor);
        } else {
            scrollableViewNoiseSuppressor = null;
            iBlur3SourceGlass = null;
        }
    }

    @Override
    public View createView(Context context) {
        initializeOptionStrings();
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(false);
        actionBar.setTitle(getTitle());
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });
        actionBar.setCastShadows(false);
        actionBar.setAddToContainer(false);

        HeaderContentView contentView = new HeaderContentView(context);
        contentView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        if (actionBar.menu == null) {
            actionBar.createMenu();
        }

        iBlur3SourceColor.setColor(getThemedColor(Theme.key_windowBackgroundGray));
        BlurredBackgroundDrawableViewFactory factory;
        if (Build.VERSION.SDK_INT >= 31 && iBlur3SourceGlass != null) {
            factory = new BlurredBackgroundDrawableViewFactory(iBlur3SourceGlass);
            factory.setLiquidGlassEffectAllowed(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS));
        } else {
            factory = new BlurredBackgroundDrawableViewFactory(iBlur3SourceColor);
        }
        factory.setSourceRootView(new ViewPositionWatcher(contentView), contentView);
        actionBar.setGlassPadding(AndroidUtilities.dp(12));
        actionBar.setGlassTitleTextSize(20);
        actionBar.setupGlass(factory, BlurredBackgroundProviderImpl.topPanelChatActivity(getResourceProvider()));
        ChatHeaderUiHelper.applyChatHeaderGlassStyle(actionBar, true);
        actionBar.setTitleColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        actionBar.setItemsColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), false);
        actionBar.setItemsBackgroundColor(getThemedColor(Theme.key_listSelector), false);

        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, this::onLongClick);
        listView.setSections();
        if (needHideTitle()) {
            actionBar.setAdaptiveBackground(listView, true);
        }
        listView.adapter.setApplyBackground(false);
        listView.setClipToPadding(false);
        listView.setPadding(0, getListTopPadding(AndroidUtilities.statusBarHeight), 0, 0);
        listView.setLayoutManager(layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        if (recyclerViewState != null) {
            layoutManager.onRestoreInstanceState(recyclerViewState);
            recyclerViewState = null;
        }
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                updateHeaderFade(true);
                if (Build.VERSION.SDK_INT >= 31 && scrollableViewNoiseSuppressor != null) {
                    scrollableViewNoiseSuppressor.onScrolled(dx, dy);
                    blur3_InvalidateBlur();
                }
            }
        });
        listView.addEdgeEffectListener(() -> listView.postOnAnimation(this::blur3_InvalidateBlur));
        iBlur3Capture = new ViewGroupPartRenderer(listView, contentView, listView::drawChild);
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        if (hasHeaderFade()) {
            fadeView = new ChatActivityFadeView(context);
            fadeView.setupColorKey(Theme.key_windowBackgroundGray);
            fadeView.setFadeTopAlpha(0);
            contentView.addView(fadeView, LayoutHelper.createFrameMatchParent());
        }
        contentView.addView(actionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, android.view.Gravity.TOP));

        return fragmentView = contentView;
    }

    public class HeaderContentView extends FrameLayout implements Theme.Colorable {

        public HeaderContentView(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            updateTopFade();
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            updateHeaderFade(false);
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            if (Build.VERSION.SDK_INT >= 31 && scrollableViewNoiseSuppressor != null && iBlur3SourceGlass != null) {
                blur3_InvalidateBlur();
                int width = getMeasuredWidth();
                int height = getMeasuredHeight();
                if (!iBlur3SourceGlass.inRecording()) {
                    RecordingCanvas recordingCanvas = iBlur3SourceGlass.beginRecording(width, height);
                    recordingCanvas.drawColor(getThemedColor(Theme.key_windowBackgroundGray));
                    if (SharedConfig.chatBlurEnabled()) {
                        scrollableViewNoiseSuppressor.draw(recordingCanvas, DownscaleScrollableNoiseSuppressor.DRAW_GLASS);
                    }
                    iBlur3SourceGlass.endRecording();
                }
            }
            super.dispatchDraw(canvas);
        }

        @Override
        public void updateColors() {
            int color = getThemedColor(Theme.key_windowBackgroundGray);
            setBackgroundColor(color);
            iBlur3SourceColor.setColor(color);
            updateTopFade();
        }
    }

    private void blur3_InvalidateBlur() {
        if (Build.VERSION.SDK_INT < 31 || scrollableViewNoiseSuppressor == null || iBlur3Capture == null || fragmentView == null || !SharedConfig.chatBlurEnabled()) {
            return;
        }
        int width = fragmentView.getMeasuredWidth();
        int height = fragmentView.getMeasuredHeight();
        int additionalHeight = AndroidUtilities.dp(48);
        iBlur3PositionActionBar.set(0, -additionalHeight, width, actionBar.getMeasuredHeight() + additionalHeight);
        scrollableViewNoiseSuppressor.setupRenderNodes(iBlur3Positions, 1);
        scrollableViewNoiseSuppressor.invalidateResultRenderNodes(iBlur3Capture, width, height);
    }

    private void updateTopFade() {
        if (fadeView == null) {
            return;
        }
        ChatHeaderUiHelper.setupChatTopFade(fadeView, actionBar, getThemedColor(Theme.key_windowBackgroundGray), actionBar.getMeasuredHeight(), true);
        fadeView.setFadeTopAlpha(Math.round(headerFadeAlpha * 255));
    }

    private void updateHeaderFade(boolean animated) {
        if (fadeView == null) {
            return;
        }
        boolean show = listView.canScrollVertically(-1);
        if (headerFadeShown == show) {
            return;
        }
        headerFadeShown = show;
        if (headerFadeAnimator != null) {
            headerFadeAnimator.cancel();
            headerFadeAnimator = null;
        }
        if (!animated) {
            setHeaderFadeAlpha(show ? 1f : 0f);
            return;
        }
        headerFadeAnimator = ValueAnimator.ofFloat(headerFadeAlpha, show ? 1f : 0f);
        headerFadeAnimator.addUpdateListener(animation -> setHeaderFadeAlpha((float) animation.getAnimatedValue()));
        headerFadeAnimator.setDuration(320);
        headerFadeAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        headerFadeAnimator.start();
    }

    private void setHeaderFadeAlpha(float alpha) {
        headerFadeAlpha = alpha;
        if (fadeView != null) {
            fadeView.setFadeTopAlpha(Math.round(alpha * 255));
        }
    }

    @Override
    public void clearViews() {
        if (fragmentView != null && layoutManager != null) {
            recyclerViewState = layoutManager.onSaveInstanceState();
        }
        super.clearViews();
    }

    @Override
    public void onResume() {
        super.onResume();
        listView.adapter.update(false);
        Bulletin.addDelegate(this, new Bulletin.Delegate() {
            @Override
            public int getTopOffset(int tag) {
                return AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight();
            }

            @Override
            public int getBottomOffset(int tag) {
                return getBottomInset();
            }
        });
    }

    @Override
    public void onPause() {
        super.onPause();
        Bulletin.removeDelegate(this);
    }

    public void scrollToItem(int id) {
        if (listView == null || listView.adapter == null || layoutManager == null) {
            return;
        }
        int position = listView.findPositionByItemId(id);
        if (position >= 0 && position < listView.adapter.getItemCount()) {
            layoutManager.scrollToPositionWithOffset(position, AndroidUtilities.dp(80));
            listView.highlightRow(() -> listView.findPositionByItemId(id));
        } else {
            SettingsRegistry.getInstance().onSettingNotFound(this);
        }
    }

    public void showListDialog(UItem item, CharSequence[] items, String title, int checkedItem, PopupUtils.OnItemClickListener listener) {
        showListDialog(item, items, null, title, checkedItem, listener);
    }

    public void showListDialog(UItem item, CharSequence[] items, int[] icons, String title, int checkedItem, PopupUtils.OnItemClickListener listener) {
        showListDialog(item, items, icons, title, checkedItem, listener, icons == null, true);
    }

    public void showListDialog(UItem item, CharSequence[] items, int[] icons, String title, int checkedItem, PopupUtils.OnItemClickListener listener, boolean withRadio, boolean ignoreSameItem) {
        if (getParentActivity() == null) {
            return;
        }
        PopupUtils.showDialog(items, icons, title, checkedItem, getContext(), which -> {
            if (ignoreSameItem && checkedItem == which) {
                return;
            }
            listener.onClick(which);
            View view = listView.findViewByItemId(item.id);
            if (view instanceof TextCell) {
                ((TextCell) view).setValue(items[which], true);
            }
            listView.adapter.update(true);
        }, getResourceProvider(), withRadio);
    }

    public void showRestartBulletin() {
        BulletinFactory.of(this).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.RestartRequired), LocaleController.getString(R.string.BotUnblock), () -> {
            Context context = getContext();
            Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            Intent restartIntent = Intent.makeRestartActivityTask(launchIntent == null ? null : launchIntent.getComponent());
            restartIntent.setPackage(context.getPackageName());
            context.startActivity(restartIntent);
            Runtime.getRuntime().exit(0);
        }).show();
    }

    public void toggleBooleanSettingAndRefresh(UItem item, Consumer<Boolean> setter) {
        boolean checked = !item.checked;
        setter.accept(checked);
        item.setChecked(checked);
        View view = listView.findViewByItemId(item.id);
        if (view instanceof CheckBoxCell) {
            ((CheckBoxCell) view).setChecked(checked, true);
        } else if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(checked);
        }
        listView.adapter.update(true);
    }

    @Override
    public boolean isLightStatusBar() {
        if (actionBar == null) {
            return super.isLightStatusBar();
        }
        return ChatHeaderUiHelper.isLightChatStatusBar(actionBar, getThemedColor(Theme.key_windowBackgroundGray), true);
    }

    public boolean onLongClick(UItem item, View view, int position, float x, float y) {
        String link = SettingsRegistry.getInstance().getFirstSettingLink(getClass(), item);
        if (link == null) {
            return false;
        }
        showCopyLinkOptions(view, link);
        return true;
    }

    public void showCopyLinkOptions(View view, String link) {
        ItemOptions options = ItemOptions.makeOptions(this, view);
        options
                .setLongPressSelectionEnabled(false)
                .add(R.drawable.msg_copy, LocaleController.getString(R.string.CopyLink), () -> {
                    if (AndroidUtilities.addToClipboard(link)) {
                        BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.LinkCopied)).show();
                    }
                })
                .add(R.drawable.msg_share, LocaleController.getString(R.string.ShareLink), () -> showDialog(new ShareAlert(options.getContext(), null, link, false, link, false, getResourceProvider()) {
                    @Override
                    protected void onSend(LongSparseArray<TLRPC.Dialog> dids, int count, TLRPC.TL_forumTopic topic, boolean showToast) {
                        if (!showToast) {
                            return;
                        }
                        String text;
                        if (dids != null && dids.size() == 1) {
                            long did = dids.valueAt(0).id;
                            if (did == 0 || did == getUserConfig().getClientUserId()) {
                                text = LocaleController.getString(R.string.SettingLinkToSavedMessages);
                            } else {
                                text = LocaleController.formatString(R.string.SettingLinkToUser, getMessagesController().getPeerName(did, true));
                            }
                        } else {
                            text = LocaleController.formatString(R.string.SettingLinkToChats, LocaleController.formatPluralString("Chats", count));
                        }
                        Bulletin bulletin = BulletinFactory.of(BasePreferencesActivity.this).createSimpleBulletin(R.raw.forward, text);
                        bulletin.hideAfterBottomSheet = false;
                        bulletin.show(true);
                    }
                }))
                .setScrimViewBackground(listView.getClipBackground(view))
                .show();
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        listView.setPadding(0, getListTopPadding(top), 0, bottom);
        listView.setClipToPadding(false);
    }

    public int getListTopPadding(int statusBarHeight) {
        return statusBarHeight + ActionBar.getCurrentActionBarHeight();
    }
}
