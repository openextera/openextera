package com.exteragram.messenger.appicons.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.exteragram.messenger.appicons.AppIcon;
import com.exteragram.messenger.appicons.AppIconController;
import com.exteragram.messenger.appicons.AppIconPreviewLoader;
import com.exteragram.messenger.appicons.ui.components.AppIconBulletinLayout;
import com.exteragram.messenger.appicons.ui.components.AppIconCell;
import com.exteragram.messenger.appicons.ui.components.AppIconHeroView;
import com.exteragram.messenger.utils.ui.ChatHeaderUiHelper;
import com.exteragram.messenger.utils.ui.UIUtil;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.EdgeToEdgeSupportMode;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;
import org.telegram.ui.Components.chat.ViewPositionWatcher;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AppIconsActivity extends BaseFragment {

    private ContentView contentView;
    private AppIconHeroView heroView;
    private UniversalRecyclerView listView;
    private FrameLayout buttonContainer;
    private ButtonWithCounterView button;
    private BlurredBackgroundSourceColor glassSource;
    private ValueAnimator colorAnimator;

    private AppIcon appliedIcon;
    private AppIcon previewIcon;
    private int backgroundColor;

    private Insets cutout = Insets.NONE;
    private int leftInset;
    private int rightInset;
    private int bottomInset;

    private boolean twoPane;
    private int paneWidth;
    private int heroHeight;
    private int scrolled;
    private float collapse;
    private float topFade;
    private boolean buttonFaded = true;

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public View createView(Context context) {
        appliedIcon = previewIcon = AppIconController.getSelectedIcon();
        backgroundColor = AppIconPreviewLoader.getAccentTint(previewIcon);

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(false);
        actionBar.setCastShadows(false);
        actionBar.setAddToContainer(false);
        actionBar.setItemsColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), false);
        actionBar.setItemsBackgroundColor(getThemedColor(Theme.key_listSelector), false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        contentView = new ContentView(context);

        glassSource = new BlurredBackgroundSourceColor();
        glassSource.setColor(getTopColor());
        BlurredBackgroundDrawableViewFactory glassFactory = new BlurredBackgroundDrawableViewFactory(glassSource);
        glassFactory.setSourceRootView(new ViewPositionWatcher(contentView), contentView);
        actionBar.setGlassPadding(AndroidUtilities.dp(12));
        actionBar.setupGlass(glassFactory, BlurredBackgroundProviderImpl.topPanelChatActivity(getResourceProvider()));
        ChatHeaderUiHelper.applyChatHeaderGlassStyle(actionBar, true);

        heroView = new AppIconHeroView(context, this);
        heroView.setOnPreviewReady(this::applyAccent);
        heroView.set(previewIcon);
        contentView.addView(heroView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        listView = new UniversalRecyclerView(this, this::fillItems, this::onItemClick, null);
        listView.setSpanCount(getSpanCount(getGridWidth(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y)));
        listView.adapter.setApplyBackground(false);
        listView.setDrawSelection(false);
        listView.setClipToPadding(false);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                updateCollapse(dy);
            }
        });
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        button = new ButtonWithCounterView(context, true, getResourceProvider());
        button.setRound();
        button.setOnClickListener(v -> onButtonClick());
        updateButton(false);

        buttonContainer = new FrameLayout(context);
        buttonContainer.setBackground(UIUtil.createBottomFade(getThemedColor(Theme.key_windowBackgroundWhite)));
        buttonContainer.addView(button, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.BOTTOM, 16, 12, 16, 12));
        buttonContainer.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> updateListPadding());
        contentView.addView(buttonContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        contentView.addView(actionBar);

        fragmentView = contentView;
        return contentView;
    }

    @Override
    public void onFragmentDestroy() {
        if (colorAnimator != null) {
            colorAnimator.cancel();
            colorAnimator = null;
        }
        AppIconPreviewLoader.trimAbove(AndroidUtilities.dp(68));
        super.onFragmentDestroy();
    }

    private int getPreviewSizeDp(int height) {
        if (heroView == null) {
            return 128;
        }
        int available;
        int reserved;
        if (twoPane) {
            available = height - AndroidUtilities.statusBarHeight - ActionBar.getCurrentActionBarHeight() - AndroidUtilities.dp(72);
            reserved = bottomInset;
        } else {
            available = (int) (height * 0.45f) - AndroidUtilities.statusBarHeight;
            reserved = AndroidUtilities.dp(68);
        }
        return Math.max(64, Math.min(128, (int) ((available - reserved - heroView.getTextBlockHeight()) / AndroidUtilities.density)));
    }

    private static boolean isTwoPane(int width, int height) {
        return width > height && width >= AndroidUtilities.dp(560);
    }

    private static int paneWidthFor(int width) {
        return Math.max(AndroidUtilities.dp(280), Math.min(AndroidUtilities.dp(400), (int) (width * 0.42f)));
    }

    private int getSpanCount(int gridWidth) {
        float width = gridWidth - AndroidUtilities.dp(8) * 2;
        int count = Math.max(3, Math.min(8, Math.round(width / AndroidUtilities.dp(100))));
        if (count < 8 && width / count > AndroidUtilities.dp(112)) {
            count++;
        }
        return count;
    }

    private int getGridWidth(int width, int height) {
        if (isTwoPane(width, height)) {
            return width - paneWidthFor(width) - rightInset;
        }
        return width - leftInset - rightInset;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        List<AppIcon> icons = AppIconController.getAvailableIcons();
        if (previewIcon == null || !icons.contains(previewIcon)) {
            appliedIcon = previewIcon = AppIconController.getSelectedIcon();
        }
        for (AppIcon icon : icons) {
            items.add(AppIconCell.Factory.asAppIcon(icon, icon == previewIcon));
        }
    }

    private void onItemClick(UItem item, View view, int position, float x, float y) {
        if (!(item.object instanceof AppIcon)) {
            return;
        }
        AppIcon icon = (AppIcon) item.object;
        if (icon == previewIcon) {
            return;
        }
        previewIcon = icon;
        heroView.set(icon);
        applyAccent();
        updateButton(true);
        listView.adapter.update(true);
    }

    private void applyAccent() {
        if (previewIcon == null) {
            return;
        }
        int accentTint = AppIconPreviewLoader.getAccentTint(previewIcon);
        if (accentTint == 0) {
            return;
        }
        animateBackgroundColor(accentTint);
    }

    private void onButtonClick() {
        if (previewIcon == null) {
            return;
        }
        if (previewIcon == appliedIcon) {
            finishFragment();
            return;
        }
        AppIconController.setIcon(previewIcon);
        appliedIcon = previewIcon;
        updateButton(true);
        Bulletin.make(this, new AppIconBulletinLayout(getContext(), appliedIcon, getResourceProvider()), Bulletin.DURATION_SHORT).show();
    }

    private void updateButton(boolean animated) {
        if (button == null) {
            return;
        }
        button.setText(LocaleController.getString(previewIcon == appliedIcon ? R.string.AppIconsKeep : R.string.AppIconsApply), animated);
    }

    private void animateBackgroundColor(int color) {
        if (backgroundColor == color) {
            return;
        }
        if (colorAnimator != null) {
            colorAnimator.cancel();
            colorAnimator = null;
        }
        int from = backgroundColor;
        if (from == 0) {
            backgroundColor = color;
            invalidateHeader();
            updateStatusBar();
            return;
        }
        colorAnimator = ValueAnimator.ofFloat(0f, 1f).setDuration(280);
        colorAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        colorAnimator.addUpdateListener(animation -> {
            backgroundColor = ColorUtils.blendARGB(from, color, (float) animation.getAnimatedValue());
            invalidateHeader();
        });
        colorAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                backgroundColor = color;
                updateStatusBar();
            }
        });
        colorAnimator.start();
    }

    private void invalidateHeader() {
        contentView.invalidate();
        if (glassSource != null) {
            glassSource.setColor(getTopColor());
            actionBar.invalidate();
        }
    }

    private void updateStatusBar() {
        if (getParentActivity() != null) {
            AndroidUtilities.setLightStatusBar(getParentActivity().getWindow(), isLightStatusBar());
        }
    }

    @Override
    public boolean isLightStatusBar() {
        return !AndroidUtilities.isDarkColor(getTopColor());
    }

    private int getBaseColor() {
        int color = getThemedColor(Theme.key_windowBackgroundGray);
        return backgroundColor == 0 ? color : ColorUtils.blendARGB(color, backgroundColor, 0.25f);
    }

    private int getTopColor() {
        return backgroundColor == 0 ? getBaseColor() : ColorUtils.blendARGB(getBaseColor(), backgroundColor, 0.7f);
    }

    @Override
    public EdgeToEdgeSupportMode getEdgeToEdgeSupportMode() {
        return EdgeToEdgeSupportMode.FULL;
    }

    @Override
    public WindowInsetsCompat onInsetsInternal(@NonNull View view, @NonNull WindowInsetsCompat insets) {
        cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout());
        return super.onInsetsInternal(view, insets);
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        bottomInset = bottom;
        leftInset = Math.max(left, cutout.left);
        rightInset = Math.max(right, cutout.right);
        if (contentView != null) {
            contentView.requestLayout();
        }
        updateListPadding();
    }

    private void updateListPadding() {
        if (listView == null) {
            return;
        }
        int left = AndroidUtilities.dp(8) + (twoPane ? 0 : leftInset);
        int right = AndroidUtilities.dp(8) + rightInset;
        int top;
        int bottom;
        if (twoPane) {
            top = AndroidUtilities.statusBarHeight + AndroidUtilities.dp(10);
            bottom = bottomInset + AndroidUtilities.dp(10);
        } else {
            top = heroHeight + AndroidUtilities.dp(10);
            if (buttonContainer != null && buttonContainer.getHeight() > 0) {
                bottom = buttonContainer.getHeight();
            } else {
                bottom = bottomInset + AndroidUtilities.dp(72);
            }
        }
        if (listView.getPaddingTop() != top || listView.getPaddingBottom() != bottom || listView.getPaddingLeft() != left || listView.getPaddingRight() != right) {
            listView.setPadding(left, top, right, bottom);
        }
        updateCollapse(0);
    }

    private int getCollapseRange() {
        if (twoPane) {
            return 0;
        }
        return Math.max(0, heroHeight - ActionBar.getCurrentActionBarHeight() - AndroidUtilities.statusBarHeight);
    }

    private void updateCollapse(int dy) {
        if (listView == null || heroView == null) {
            return;
        }
        int offset = scrolled + dy;
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            if (listView.getChildAdapterPosition(child) == 0) {
                offset = listView.getPaddingTop() - child.getTop();
                break;
            }
        }
        scrolled = Math.max(0, offset);
        int range = getCollapseRange();
        float newCollapse = range <= 0 ? 0f : Math.min(1f, (float) scrolled / range);
        float newTopFade = Math.min(1f, (float) scrolled / AndroidUtilities.dp(16));
        if (Math.abs(newCollapse - collapse) >= 0.0005f || Math.abs(newTopFade - topFade) >= 0.0005f) {
            collapse = newCollapse;
            topFade = newTopFade;
            heroView.setCollapse(newCollapse);
            contentView.invalidate();
        }
    }

    public class ContentView extends FrameLayout implements Theme.Colorable {

        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path panelPath = new Path();
        private final float[] panelRadii = new float[8];

        private int glowColor;
        private int glowWidth;
        private int glowHeight;
        private float glowCx;
        private float glowCy;
        private int topFadeColor;
        private Drawable topFadeDrawable;
        private boolean heroLinkPressed;

        public ContentView(Context context) {
            super(context);
            setWillNotDraw(false);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = MeasureSpec.getSize(heightMeasureSpec);
            boolean wasTwoPane = twoPane;
            twoPane = isTwoPane(width, height);
            paneWidth = twoPane ? paneWidthFor(width) : width;

            if (twoPane) {
                heroView.setPadding(0, 0, 0, 0);
            } else {
                heroView.setPadding(0, AndroidUtilities.statusBarHeight + AndroidUtilities.dp(28), 0, AndroidUtilities.dp(40));
            }
            heroView.setPreviewSizeDp(getPreviewSizeDp(height));
            buttonContainer.setPadding(twoPane ? 0 : leftInset, 0, twoPane ? 0 : rightInset, bottomInset);
            if (buttonFaded != !twoPane) {
                buttonFaded = !twoPane;
                buttonContainer.setBackground(buttonFaded ? UIUtil.createBottomFade(getThemedColor(Theme.key_windowBackgroundWhite)) : null);
            }

            int spanCount = getSpanCount(getGridWidth(width, height));
            if (listView.getSpanCount() != spanCount) {
                listView.setSpanCount(spanCount);
            }

            if (twoPane) {
                int paneSpec = MeasureSpec.makeMeasureSpec(paneWidth - leftInset, MeasureSpec.EXACTLY);
                int heightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST);
                actionBar.measure(paneSpec, heightSpec);
                buttonContainer.measure(paneSpec, heightSpec);
                heroView.measure(paneSpec, heightSpec);
                listView.measure(MeasureSpec.makeMeasureSpec(width - paneWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
                setMeasuredDimension(width, height);
            } else {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }

            int measuredHeroHeight = heroView.getMeasuredHeight();
            if (measuredHeroHeight > 0 && (measuredHeroHeight != heroHeight || wasTwoPane != twoPane)) {
                heroHeight = measuredHeroHeight;
                AndroidUtilities.runOnUIThread(AppIconsActivity.this::updateListPadding);
            }
        }

        @Override
        public void updateColors() {
            backgroundColor = AppIconPreviewLoader.getAccentTint(previewIcon);
            actionBar.setItemsColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), false);
            actionBar.setItemsBackgroundColor(getThemedColor(Theme.key_listSelector), false);
            if (buttonFaded) {
                buttonContainer.setBackground(UIUtil.createBottomFade(getThemedColor(Theme.key_windowBackgroundWhite)));
            }
            invalidateHeader();
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            if (twoPane) {
                int height = b - t;
                actionBar.layout(leftInset, 0, leftInset + actionBar.getMeasuredWidth(), actionBar.getMeasuredHeight());
                listView.layout(paneWidth, 0, r - l, height);
                int buttonTop = height - buttonContainer.getMeasuredHeight();
                buttonContainer.layout(leftInset, buttonTop, leftInset + buttonContainer.getMeasuredWidth(), height);
                int headerHeight = AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight();
                int heroTop = headerHeight + Math.max(0, (buttonTop - headerHeight - heroHeight) / 2);
                heroView.layout(leftInset, heroTop, leftInset + heroView.getMeasuredWidth(), heroTop + heroHeight);
                updateCollapse(0);
                return;
            }
            super.onLayout(changed, l, t, r, b);
            updateCollapse(0);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (heroView != null) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    heroLinkPressed = heroView.dispatchLinkTouch(event);
                    if (heroLinkPressed) {
                        return true;
                    }
                } else if (heroLinkPressed) {
                    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                        heroLinkPressed = false;
                    }
                    heroView.dispatchLinkTouch(event);
                    return true;
                }
            }
            return super.dispatchTouchEvent(event);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawColor(getBaseColor());
            if (backgroundColor == 0 || collapse >= 1f) {
                return;
            }
            float cx = (twoPane ? leftInset + paneWidth : getWidth()) / 2f;
            float cy = heroView.getTop() + heroView.getPaddingTop() + heroView.getPreviewSize() / 2f;
            if (glowColor != backgroundColor || glowWidth != getWidth() || glowHeight != getHeight() || glowCx != cx || glowCy != cy) {
                float radius = Math.max(twoPane ? paneWidth : getWidth(), heroHeight) * 0.9f;
                if (radius <= 0) {
                    return;
                }
                glowColor = backgroundColor;
                glowWidth = getWidth();
                glowHeight = getHeight();
                glowCx = cx;
                glowCy = cy;
                glowPaint.setShader(new RadialGradient(
                    cx, cy, radius,
                    new int[]{ColorUtils.setAlphaComponent(backgroundColor, 200), ColorUtils.setAlphaComponent(backgroundColor, 0)},
                    new float[]{0f, 1f},
                    Shader.TileMode.CLAMP
                ));
            }
            glowPaint.setAlpha((int) ((1f - collapse) * 255));
            canvas.drawRect(0, 0, getWidth(), getHeight(), glowPaint);
        }

        @Override
        protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
            if (child != listView) {
                return super.drawChild(canvas, child, drawingTime);
            }
            float radius = AndroidUtilities.dp(20);
            Arrays.fill(panelRadii, 0);
            RectF rect = AndroidUtilities.rectTmp;
            if (twoPane) {
                panelRadii[0] = panelRadii[1] = radius;
                panelRadii[6] = panelRadii[7] = radius;
                rect.set(paneWidth, 0, getWidth(), getHeight());
            } else {
                panelRadii[0] = panelRadii[1] = radius;
                panelRadii[2] = panelRadii[3] = radius;
                rect.set(0, listView.getY() + heroHeight - getCollapseRange() * collapse, getWidth(), getHeight());
            }
            panelPath.rewind();
            panelPath.addRoundRect(rect, panelRadii, Path.Direction.CW);
            float panelTop = rect.top;

            int panelColor = getThemedColor(Theme.key_windowBackgroundWhite);
            panelPaint.setColor(panelColor);
            canvas.drawPath(panelPath, panelPaint);

            canvas.save();
            canvas.clipPath(panelPath);
            boolean result = super.drawChild(canvas, child, drawingTime);
            if (topFade > 0) {
                if (topFadeDrawable == null || topFadeColor != panelColor) {
                    topFadeColor = panelColor;
                    topFadeDrawable = UIUtil.createTopFade(panelColor);
                }
                int top = (int) panelTop;
                topFadeDrawable.setBounds(0, top, getWidth(), top + AndroidUtilities.dp(24));
                topFadeDrawable.setAlpha((int) (topFade * 255));
                topFadeDrawable.draw(canvas);
            }
            canvas.restore();
            return result;
        }
    }
}
