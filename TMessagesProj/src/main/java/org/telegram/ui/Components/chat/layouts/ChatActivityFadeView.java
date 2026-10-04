package org.telegram.ui.Components.chat.layouts;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.math.MathUtils;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.BlurredBackgroundWithFadeDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;

public class ChatActivityFadeView extends View implements Theme.Colorable {
    private static final Paint scrimFadeMaskPaint = new Paint();
    private static final Paint scrimFadeDimPaint = new Paint();
    private static final Paint scrimViewDimPaint = new Paint();
    private static float scrimViewDimTop = Float.NaN;
    private static float scrimViewDimBottom = Float.NaN;

    static {
        final PorterDuffXfermode srcAtop = new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP);
        scrimFadeMaskPaint.setXfermode(srcAtop);
        scrimFadeDimPaint.setXfermode(srcAtop);
        scrimFadeDimPaint.setColor(Color.BLACK);
        scrimViewDimPaint.setXfermode(srcAtop);
    }

    private BlurredBackgroundWithFadeDrawable fadeDrawableTop;
    private BlurredBackgroundWithFadeDrawable fadeDrawableBottom;
    private int fadeZoneTop, fadeZoneBottom;

    public ChatActivityFadeView(Context context) {
        super(context);
    }


    private BlurredBackgroundSourceColor sourceColor;
    private BlurredBackgroundDrawableViewFactory factory;
    private int colorKey;

    public void setupColorKey(int colorKey) {
        this.colorKey = colorKey;
        if (sourceColor == null) {
            sourceColor = new BlurredBackgroundSourceColor();
            sourceColor.setColor(Theme.getColor(colorKey));
            factory = new BlurredBackgroundDrawableViewFactory(sourceColor);
            setup(factory);
        }
    }


    public void setup(BlurredBackgroundDrawableViewFactory factory) {
        setup(factory, null);
    }

    public void setup(BlurredBackgroundDrawableViewFactory factory, BlurredBackgroundColorProvider colorProvider) {
        fadeDrawableTop = new BlurredBackgroundWithFadeDrawable(factory.create(this).setColorProvider(colorProvider));
        fadeDrawableTop.setFadeHeight(-dp(30), true);

        fadeDrawableBottom = new BlurredBackgroundWithFadeDrawable(factory.create(this).setColorProvider(colorProvider));
        fadeDrawableBottom.setFadeHeight(dp(30), true);
    }

    public void setTopFadeColor(int color) {
        fadeDrawableTop.setOverrideFadeColor(color);
        invalidate();
    }

    public void setFadeHeightTop(int height, boolean opacity) {
        fadeDrawableTop.setFadeHeight(-height, opacity);
    }

    public void setFadeHeightTop(int height) {
        fadeDrawableTop.setFadeHeight(-height, true);
    }

    public void setFadeHeightBottom(int height) {
        fadeDrawableBottom.setFadeHeight(height, true);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        checkBounds();
    }

    public void setFadeZoneTop(int height) {
        if (fadeZoneTop != height) {
            fadeZoneTop = height;
            checkBounds();
            invalidate();
        }
    }

    public int getFadeZoneTop() {
        return fadeZoneTop;
    }

    public void setFadeZoneBottom(int height) {
        if (fadeZoneBottom != height) {
            fadeZoneBottom = height;
            checkBounds();
            invalidate();
        }
    }

    public void setFadeTopAlpha(int alpha) {
        if (fadeDrawableTop.getAlpha() != alpha) {
            fadeDrawableTop.setAlpha(alpha);
            invalidate();
        }
    }
    
    private void checkBounds() {
        fadeDrawableTop.setBounds(0, 0, getMeasuredWidth(), fadeZoneTop);
        fadeDrawableBottom.setBounds(0, getMeasuredHeight() - fadeZoneBottom, getMeasuredWidth(), getMeasuredHeight());
    }

    public void setIgnoreFastWay(boolean ignoreFastWay) {
        fadeDrawableTop.setIgnoreFastWay(ignoreFastWay);
        fadeDrawableBottom.setIgnoreFastWay(ignoreFastWay);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        fadeDrawableTop.draw(canvas);
        fadeDrawableBottom.draw(canvas);
    }

    public void drawOverScrim(Canvas canvas, float left, float top, float right, float bottom, float dimAlpha, boolean dimScrimView) {
        final float fadeBottom = Math.min(bottom, fadeZoneTop);
        if (top >= fadeBottom || left >= right) {
            return;
        }
        final int alpha = (int) (MathUtils.clamp(dimAlpha, 0f, 1f) * 255);
        if (dimScrimView && alpha > 0) {
            if (scrimViewDimTop != top || scrimViewDimBottom != fadeZoneTop) {
                scrimViewDimTop = top;
                scrimViewDimBottom = fadeZoneTop;
                scrimViewDimPaint.setShader(new LinearGradient(0, top, 0, fadeZoneTop, Color.BLACK, 0, Shader.TileMode.CLAMP));
            }
            scrimViewDimPaint.setAlpha(alpha);
            canvas.drawRect(left, top, right, fadeBottom, scrimViewDimPaint);
        }
        final int restoreCount = canvas.saveLayer(left, top, right, fadeBottom, scrimFadeMaskPaint);
        fadeDrawableTop.draw(canvas);
        if (alpha > 0) {
            scrimFadeDimPaint.setAlpha(alpha);
            canvas.drawRect(left, top, right, fadeBottom, scrimFadeDimPaint);
        }
        canvas.restoreToCount(restoreCount);
    }

    @Override
    public void updateColors() {
        if (sourceColor != null && colorKey != -1) {
            sourceColor.setColor(Theme.getColor(colorKey));
            invalidate();
        }
    }
}

