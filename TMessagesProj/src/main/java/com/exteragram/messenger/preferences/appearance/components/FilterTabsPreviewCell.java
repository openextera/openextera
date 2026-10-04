package com.exteragram.messenger.preferences.appearance.components;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.FrameLayout;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.TabCounterMode;
import com.exteragram.messenger.preferences.components.CustomPreferenceCell;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.FilterTabsView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@SuppressLint("ViewConstructor")
public class FilterTabsPreviewCell extends FrameLayout implements CustomPreferenceCell, NotificationCenter.NotificationCenterDelegate {

    private static final int MAX_TABS = 6;

    private final FilterTabsView filterTabsView;
    private final Map<Integer, Integer> idsWithCounters = new HashMap<>();
    private final int counterSeed = Utilities.random.nextInt(40) + 20;

    public FilterTabsPreviewCell(Context context) {
        super(context);
        setWillNotDraw(false);

        BlurredBackgroundSourceColor sourceColor = new BlurredBackgroundSourceColor();
        sourceColor.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        BlurredBackgroundDrawableViewFactory factory = new BlurredBackgroundDrawableViewFactory(sourceColor);

        filterTabsView = new FilterTabsView(context, null);
        filterTabsView.setStaticAllChats(true);
        filterTabsView.setPadding(0, AndroidUtilities.dp(7), 0, AndroidUtilities.dp(7));
        BlurredBackgroundDrawable background = factory.create(filterTabsView, BlurredBackgroundProviderImpl.topPanel(null));
        background.setRadius(AndroidUtilities.dp(18));
        background.setPadding(AndroidUtilities.dp(6.666f));
        filterTabsView.setBlurredBackground(background);
        filterTabsView.setColors(Theme.key_actionBarTabLine, Theme.key_actionBarTabActiveText, Theme.key_actionBarTabUnactiveText, Theme.key_actionBarTabSelector, Theme.key_actionBarDefault);
        filterTabsView.setDelegate(new FilterTabsView.FilterTabsViewDelegate() {
            @Override
            public void onPageSelected(FilterTabsView.Tab tab, boolean forward) {
            }

            @Override
            public void onPageScrolled(float progress) {
            }

            @Override
            public void onSamePageSelected() {
            }

            @Override
            public int getTabCounter(int tabId) {
                if (ExteraConfig.getTabCounterMode() == TabCounterMode.HIDDEN) {
                    return 0;
                }
                return idsWithCounters.computeIfAbsent(tabId, id -> 0);
            }

            @Override
            public boolean didSelectTab(FilterTabsView.TabView tabView, boolean selected) {
                return false;
            }

            @Override
            public boolean isTabMenuVisible() {
                return false;
            }

            @Override
            public void onDeletePressed(int id) {
            }

            @Override
            public void onPageReorder(int fromId, int toId) {
            }

            @Override
            public boolean canPerformActions() {
                return false;
            }
        });
        addView(filterTabsView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 50, Gravity.CENTER, 12, 0, 12, 0));
        updateTabs(false);
    }

    @Override
    public boolean equals(Object o) {
        return this == o;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return true;
    }

    private void updateTabs(boolean animated) {
        filterTabsView.resetTabId();
        filterTabsView.removeTabs();
        idsWithCounters.clear();

        ArrayList<MessagesController.DialogFilter> filters = MessagesController.getInstance(UserConfig.selectedAccount).getDialogFilters();
        int tabsCount = 0;
        int firstTabId = -1;
        for (int i = 0; i < filters.size(); i++) {
            MessagesController.DialogFilter filter = filters.get(i);
            if (filter.isDefault() && ExteraConfig.getHideAllChats()) {
                continue;
            }
            if (i == 0 || i == 1 || i == 3) {
                idsWithCounters.put(filter.id, counterSeed / (i + 1));
            }
            if (firstTabId == -1) {
                firstTabId = filter.id;
            }
            if (filter.isDefault()) {
                filterTabsView.addTab(filter.id, 0, LocaleController.getString(R.string.FilterAllChats), "💬", null, false, true, filter.locked);
            } else {
                filterTabsView.addTab(filter.id, filter.localId, filter.name, filter.emoticon == null ? "📁" : filter.emoticon, filter.entities, filter.title_noanimate, false, filter.locked);
            }
            tabsCount++;
        }

        if (tabsCount < MAX_TABS) {
            int[] ids = {100, 101, 102, 103};
            String[] names = {
                    LocaleController.getString(R.string.FilterContacts),
                    LocaleController.getString(R.string.FilterGroups),
                    LocaleController.getString(R.string.FilterChannels),
                    LocaleController.getString(R.string.FilterBots)
            };
            String[] emoticons = {"👤", "👥", "📢", "🤖"};
            for (int i = 0; i < ids.length && tabsCount < MAX_TABS; i++) {
                int position = filters.size() + i;
                if (position == 0 || position == 1 || position == 3) {
                    idsWithCounters.put(ids[i], counterSeed / (position + 1));
                }
                if (firstTabId == -1) {
                    firstTabId = ids[i];
                }
                filterTabsView.addTab(ids[i], ids[i], names[i], emoticons[i], null, false, false, false);
                tabsCount++;
            }
        }

        filterTabsView.finishAddingTabs(animated);
        if (firstTabId == -1 && !filters.isEmpty()) {
            firstTabId = filters.get(0).id;
        }
        if (firstTabId != -1) {
            filterTabsView.selectTabWithId(firstTabId, 1f);
        } else {
            filterTabsView.selectFirstTab();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getInstance(UserConfig.selectedAccount).addObserver(this, NotificationCenter.dialogFiltersUpdated);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getInstance(UserConfig.selectedAccount).removeObserver(this, NotificationCenter.dialogFiltersUpdated);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.dialogFiltersUpdated) {
            updateTabs(true);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(74), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawLine(0, getMeasuredHeight() - 1, getMeasuredWidth(), getMeasuredHeight() - 1, Theme.dividerPaint);
    }
}
