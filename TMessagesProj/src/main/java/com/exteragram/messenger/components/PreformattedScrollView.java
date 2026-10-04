package com.exteragram.messenger.components;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;

import com.exteragram.messenger.utils.MarkdownUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.tgnet.tl.TL_iv;

import java.util.ArrayList;

public abstract class PreformattedScrollView extends HorizontalScrollView {

    private Group group;

    public PreformattedScrollView(Context context) {
        super(context);
    }

    public void setBlock(TL_iv.PageBlock block) {
        MarkdownUtils.PreformattedChunk chunk = block instanceof MarkdownUtils.PreformattedChunk ? (MarkdownUtils.PreformattedChunk) block : null;
        if (group != null) {
            group.remove(this);
        }
        group = chunk != null ? chunk.scrollGroup : null;
        if (group != null && isAttachedToWindow()) {
            group.add(this);
        }
        setHorizontalScrollBarEnabled(chunk == null);
        boolean joinsPrevious = chunk != null && chunk.joinsPrevious;
        boolean joinsNext = chunk != null && chunk.joinsNext;
        setPadding(0, joinsPrevious ? 0 : AndroidUtilities.dp(8), 0, joinsNext ? 0 : AndroidUtilities.dp(8));
        if (getChildCount() > 0) {
            ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams) getChildAt(0).getLayoutParams();
            layoutParams.topMargin = joinsPrevious ? 0 : AndroidUtilities.dp(12);
            layoutParams.bottomMargin = AndroidUtilities.dp(joinsNext ? 4 : 12);
        }
    }

    public int adjustContentWidth(int width) {
        return group != null ? group.adjustContentWidth(this, width) : width;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (group != null && ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            group.driver = this;
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        if (group == null || group.driver != this) {
            return;
        }
        group.scrollTo(this, l);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (group != null) {
            scrollTo(group.scrollX, getScrollY());
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (group != null) {
            group.add(this);
            scrollTo(group.scrollX, getScrollY());
            if (getChildCount() <= 0 || getChildAt(0).getMeasuredWidth() >= group.contentWidth) {
                return;
            }
            View child = getChildAt(0);
            AndroidUtilities.runOnUIThread(child::requestLayout);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (group != null) {
            group.remove(this);
        }
    }

    public static final class Group {
        private final ArrayList<PreformattedScrollView> views = new ArrayList<>();
        private PreformattedScrollView driver;
        private int scrollX;
        private int contentWidth;

        private void add(PreformattedScrollView view) {
            if (views.contains(view)) {
                return;
            }
            views.add(view);
        }

        private void remove(PreformattedScrollView view) {
            views.remove(view);
            if (driver == view) {
                driver = null;
            }
        }

        private void scrollTo(PreformattedScrollView source, int x) {
            scrollX = x;
            for (int i = 0; i < views.size(); i++) {
                PreformattedScrollView view = views.get(i);
                if (view != source) {
                    view.scrollTo(x, view.getScrollY());
                }
            }
        }

        private int adjustContentWidth(PreformattedScrollView source, int width) {
            if (width > contentWidth) {
                contentWidth = width;
                for (int i = 0; i < views.size(); i++) {
                    PreformattedScrollView view = views.get(i);
                    if (view != source && view.getChildCount() > 0) {
                        View child = view.getChildAt(0);
                        AndroidUtilities.runOnUIThread(child::requestLayout);
                    }
                }
            }
            return contentWidth;
        }
    }
}
