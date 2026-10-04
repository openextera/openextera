/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.EGLExt;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.graphics.ColorUtils;

import androidx.media3.exoplayer.ExoPlayer;
import androidx.camera.core.Preview;

import com.exteragram.messenger.CameraType;
import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.VideoMessagesCamera;
import com.exteragram.messenger.camera.CameraXSession;
import com.exteragram.messenger.camera.CameraDebugUtils;
import com.exteragram.messenger.camera.InstantCameraZoomSlider;
import com.exteragram.messenger.camera.RoundVideoEncoder;
import com.exteragram.messenger.debug.DebugConfig;
import com.exteragram.messenger.debug.DebugOverlayView;
import com.exteragram.messenger.utils.system.SystemUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.AutoDeleteMediaTask;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.VideoEditedInfo;
import org.telegram.messenger.camera.Camera2Session;
import org.telegram.messenger.camera.CameraController;
import org.telegram.messenger.camera.CameraInfo;
import org.telegram.messenger.camera.CameraSession;
import org.telegram.messenger.camera.Size;
import org.telegram.messenger.video.MP4Builder;
import org.telegram.messenger.video.Mp4Movie;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;
import org.telegram.ui.Components.voip.CellFlickerDrawable;
import org.telegram.ui.Stories.recorder.DualCameraView;
import org.telegram.ui.Stories.recorder.FlashViews;
import org.telegram.ui.Stories.recorder.SliderView;
import org.telegram.ui.Stories.recorder.StoryEntry;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

@SuppressLint("ViewConstructor")
public class InstantCameraView extends InstantCameraViewBase implements NotificationCenter.NotificationCenterDelegate {

    public boolean WRITE_TO_FILE_IN_BACKGROUND;

    private int currentAccount = UserConfig.selectedAccount;
    private InstantViewCameraContainer cameraContainer;
    private Delegate delegate;
    private Paint paint;
    private RectF rect;
    private final FlashViews.ImageViewInvertable switchCameraButton;
    private final FlashViews.ImageViewInvertable flashButton;
    private final FlashViews flashViews;
    private RLottieDrawable flashOnDrawable, flashOffDrawable;
    private RLottieDrawable switchCameraDrawable;
    private ImageView muteImageView;
    private float progress;
    private CameraInfo selectedCamera;
    private boolean isFrontface = true;
    private volatile boolean cameraReady;
    private AnimatorSet muteAnimation;
    private TLRPC.InputFile file;
    private TLRPC.InputEncryptedFile encryptedFile;
    private byte[] key;
    private byte[] iv;
    private long size;
    private boolean isSecretChat;
    @Nullable
    private VideoEditedInfo videoEditedInfo;
    private VideoPlayer videoPlayer;
    private Bitmap lastBitmap;
    private int recordingGuid;

    private volatile boolean cameraTextureAvailable;
    private final int[] position = new int[2];
    private final int[] cameraTexture = new int[] { Integer.MIN_VALUE, Integer.MIN_VALUE };
    private final int[] oldCameraTexture = new int[1];
    private float cameraTextureAlpha = 1.0f;

    private AnimatorSet animatorSet;

    private boolean deviceHasGoodCamera;
    private boolean requestingPermissions;
    private File cameraFile;
    private File previewFile;
    private long recordStartTime;
    private long recordPlusTime;
    private boolean recording;
    private long recordedTime;
    private boolean cancelled;

    private CameraGLThread cameraThread;
    private Size[] previewSize = new Size[2];
    private Size pictureSize;
    private Size aspectRatio = SharedConfig.roundCamera16to9 ? new Size(16, 9) : new Size(4, 3);
    private TextureView textureView;
    private BackupImageView textureOverlayView;
    private final boolean useCamera2 = ExteraConfig.getCameraType() == CameraType.CAMERA_2;
    private CameraSession cameraSession;
    private volatile boolean bothCameras;
    private Camera2Session[] camera2Sessions = new Camera2Session[2];
    private Camera2Session camera2SessionCurrent;
    private CameraXSession.CameraLifecycle camLifecycle;
    private volatile CameraXSession cameraXSession;
    private InstantCameraZoomSlider zoomSlider;
    private ScaleGestureDetector scaleGestureDetector;
    private float cameraZoom;
    private boolean needDrawFlickerStub;

    private boolean isCameraSessionInitiated() {
        if (useCamera2) {
            return camera2SessionCurrent != null && camera2SessionCurrent.isInitiated();
        } else {
            return cameraSession != null && cameraSession.isInitied();
        }
    }

    public boolean isCameraReady() {
        boolean sessionReady = ExteraConfig.getCameraType() == CameraType.CAMERA_X
                ? cameraXSession != null && cameraXSession.isReady()
                : isCameraSessionInitiated();
        return cameraReady && sessionReady && cameraThread != null;
    }

    public void setFrontface(boolean frontface) {
        isFrontface = frontface;
    }

    private float panTranslationY;
    private float animationTranslationY;

    private final float[] mMVPMatrix = new float[16];
    private final float[] mSTMatrix = new float[16];
    private final float[] moldSTMatrix = new float[16];
    private static final String VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;\n" +
                    "uniform mat4 uSTMatrix;\n" +
                    "attribute vec4 aPosition;\n" +
                    "attribute vec4 aTextureCoord;\n" +
                    "varying vec2 vTextureCoord;\n" +
                    "void main() {\n" +
                    "   gl_Position = uMVPMatrix * aPosition;\n" +
                    "   vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
                    "}\n";

    private static final String FRAGMENT_SCREEN_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision lowp float;\n" +
                    "varying vec2 vTextureCoord;\n" +
                    "uniform samplerExternalOES sTexture;\n" +
                    "void main() {\n" +
                    "   gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
                    "}\n";

    private FloatBuffer vertexBuffer;
    private FloatBuffer textureBuffer;
    private FloatBuffer oldTextureTextureBuffer;
    private float scaleX;
    private float scaleY;

    private Size oldTexturePreviewSize;
    private final float[] textureCoordsData = new float[8];

    private boolean flipAnimationInProgress;

    private View parentView;
    public boolean opened;

    float pinchStartDistance;

    private float initialCameraZoom;
    private boolean zoomWas;

    boolean isInPinchToZoomTouchMode;
    boolean maybePinchToZoomTouchMode;

    private int pointerId1, pointerId2;
    private int textureViewSize;
    private boolean isMessageTransition;
    private boolean updateTextureViewSize;
    private final Theme.ResourcesProvider resourcesProvider;

    private final static int audioSampleRate = 48000;

    private static final int[] ALLOW_BIG_CAMERA_WHITELIST = {
            285904780, // XIAOMI (Redmi Note 7)
            -1394191079 // samsung a31
    };
    private boolean allowSendingWhileRecording;

    private final LinearLayout buttonsLayout;
    private final int buttonsSizePx;


    @SuppressLint("ClickableViewAccessibility")
    public InstantCameraView(Context context, Delegate delegate, Theme.ResourcesProvider resourcesProvider, boolean isNewDesign) {
        super(context);
        buttonsSizePx = dp(isNewDesign ? 24 : 28);

        WRITE_TO_FILE_IN_BACKGROUND = false;//SharedConfig.deviceIsAboveAverage();
        this.resourcesProvider = resourcesProvider;
        parentView = delegate.getFragmentView();
        setWillNotDraw(false);

        this.delegate = delegate;
        recordingGuid = delegate.getClassGuid();
        isSecretChat = delegate.isSecretChat();
        paint = new Paint(Paint.ANTI_ALIAS_FLAG) {
            @Override
            public void setAlpha(int a) {
                super.setAlpha(a);
                invalidate();
            }
        };
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(3));
        paint.setColor(0xffffffff);

        rect = new RectF();

        flashViews = new FlashViews(getContext(), null, this, null);
        flashViews.setWarmth(ExteraConfig.getFlashWarmth());
        flashViews.setIntensity(ExteraConfig.getFlashIntensity());
        addView(flashViews.backgroundView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));

        scaleGestureDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                cancelZoomAnimations();
                zoomSlider.beginPinchZoomGesture();
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (cameraXSession != null) {
                    zoomSlider.scaleCameraXZoom((float) Math.pow(detector.getScaleFactor(), 2));
                    cameraZoom = cameraXSession.getLinearZoom();
                }
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                finishZoom();
            }
        });

        cameraContainer = new InstantViewCameraContainer(context) {
            @Override
            public void setRotationY(float rotationY) {
                super.setRotationY(rotationY);
                InstantCameraView.this.invalidate();
            }

            @Override
            public void setAlpha(float alpha) {
                super.setAlpha(alpha);
                InstantCameraView.this.invalidate();
            }
        };
        cameraContainer.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, textureViewSize, textureViewSize);
            }
        });
        cameraContainer.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && cameraXSession != null) {
                cameraXSession.focusToPoint(event.getX(), event.getY(), view.getWidth(), view.getHeight());
            }
            return false;
        });
        cameraContainer.setClipToOutline(true);
        cameraContainer.setWillNotDraw(false);

        addView(cameraContainer, new LayoutParams(AndroidUtilities.roundPlayingMessageSize, AndroidUtilities.roundPlayingMessageSize, Gravity.CENTER));
        addView(flashViews.foregroundView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));


        buttonsLayout = new LinearLayout(context);
        buttonsLayout.setPadding(dp(6), dp(6), dp(6), dp(6));

        buttonsLayout.setOrientation(LinearLayout.HORIZONTAL);
        addView(buttonsLayout, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 56, Gravity.LEFT | Gravity.BOTTOM, 1, 0, 0, 0));

        switchCameraButton = new FlashViews.ImageViewInvertable(context);
        switchCameraButton.setScaleType(ImageView.ScaleType.CENTER);
        switchCameraButton.setContentDescription(LocaleController.getString(R.string.AccDescrSwitchCamera));
        buttonsLayout.addView(switchCameraButton, LayoutHelper.createLinear(44, 44));
        switchCameraButton.setOnClickListener(v -> {
            if (!isCameraReady()) {
                return;
            }
            boolean cameraXDual = ExteraConfig.getCameraType() == CameraType.CAMERA_X
                    && cameraXSession != null && cameraXSession.isDualMode();
            if (ExteraConfig.getCameraType() == CameraType.CAMERA_X && !cameraXDual) {
                switchCameraX();
            } else if (!bothCameras) {
                switchCamera();
            }
            if (switchCameraDrawable != null) {
                switchCameraDrawable.setCurrentFrame(0);
                switchCameraDrawable.start();
            }
            if (ExteraConfig.getCameraType() == CameraType.CAMERA_X && !cameraXDual) {
                return;
            }
            flipAnimationInProgress = true;
            ValueAnimator valueAnimator = ValueAnimator.ofFloat(0, 1f);
            valueAnimator.setDuration(580);
            valueAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            final boolean[] didSwap = new boolean[1];
            Runnable doSwap = () -> {
                if (bothCameras) {
                    if (ExteraConfig.getCameraType() == CameraType.CAMERA_X) {
                        switchCameraX();
                    } else {
                        switchCamera();
                    }
                }
            };
            cameraContainer.setCameraDistance(cameraContainer.getMeasuredHeight() * 8f);
            textureOverlayView.setCameraDistance(textureOverlayView.getMeasuredHeight() * 8f);
            valueAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator valueAnimator) {
                    float p = (float) valueAnimator.getAnimatedValue();
                    if (p > 0.5f && !didSwap[0]) {
                        didSwap[0] = true;
                        doSwap.run();
                    }
                    float rotation = p < 0.5f ? p : p - 1f;
                    rotation *= 180;
                    cameraContainer.setRotationY(rotation);
                    textureOverlayView.setRotationY(rotation);
                }
            });
            valueAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    if (!didSwap[0]) {
                        didSwap[0] = true;
                        doSwap.run();
                    }
                    cameraContainer.setRotationY(0f);
                    textureOverlayView.setRotationY(0f);
                    flipAnimationInProgress = false;
                    invalidate();
                }
            });
            valueAnimator.start();
        });

        flashButton = new FlashViews.ImageViewInvertable(context);
        flashButton.setScaleType(ImageView.ScaleType.CENTER);
        buttonsLayout.addView(flashButton, LayoutHelper.createLinear(44, 44));
        flashButton.setOnClickListener(v -> {
            flashing = !flashing;
            updateFlash();
        });
        flashButton.setOnLongClickListener(v -> {
            if (!isFrontface || !isCameraReady()) {
                return false;
            }
            boolean wasEnabled = flashing;
            if (!wasEnabled) {
                flashing = true;
                updateFlash();
            }
            ItemOptions.makeOptions(this, resourcesProvider, flashButton)
                    .addView(new SliderView(getContext(), 1)
                            .setValue(ExteraConfig.getFlashWarmth())
                            .setOnValueChange(value -> {
                                ExteraConfig.setFlashWarmth(value);
                                flashViews.setWarmth(value);
                            }))
                    .addSpaceGap()
                    .addView(new SliderView(getContext(), 2)
                            .setMinMax(0.5f, 1f)
                            .setValue(ExteraConfig.getFlashIntensity())
                            .setOnValueChange(value -> {
                                ExteraConfig.setFlashIntensity(value);
                                flashViews.setIntensity(value);
                            }))
                    .setOnDismiss(() -> {
                        if (!wasEnabled) {
                            flashing = false;
                            updateFlash();
                        }
                    })
                    .setDimAlpha(50)
                    .setGravity(Gravity.RIGHT)
                    .translate(dp(46), dp(4))
                    .setBackgroundColor(0xbb1b1b1b)
                    .show();
            return true;
        });
        updateFlash();

        if (!isNewDesign) {
            flashViews.add(switchCameraButton);
            flashViews.add(flashButton);
        } else if (!resourcesProvider.isDark()) {
            switchCameraButton.setInvert(0.6f);
            flashButton.setInvert(0.6f);
        }

        muteImageView = new ImageView(context);
        muteImageView.setScaleType(ImageView.ScaleType.CENTER);
        muteImageView.setImageResource(R.drawable.video_mute);
        muteImageView.setAlpha(0.0f);
        addView(muteImageView, LayoutHelper.createFrame(48, 48, Gravity.CENTER));

        Paint blackoutPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        blackoutPaint.setColor(ColorUtils.setAlphaComponent(Color.BLACK, 40));
        textureOverlayView = new BackupImageView(getContext()) {

            CellFlickerDrawable flickerDrawable = new CellFlickerDrawable();

            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                if (needDrawFlickerStub) {
                    flickerDrawable.setParentWidth(textureViewSize);
                    AndroidUtilities.rectTmp.set(0, 0, textureViewSize, textureViewSize);
                    float rad = AndroidUtilities.rectTmp.width() / 2f;
                    canvas.drawRoundRect(AndroidUtilities.rectTmp, rad, rad, blackoutPaint);
                    AndroidUtilities.rectTmp.inset(dp(1), dp(1));
                    flickerDrawable.draw(canvas, AndroidUtilities.rectTmp, rad, null);
                    invalidate();
                }
            }
        };
        addView(textureOverlayView, new LayoutParams(AndroidUtilities.roundPlayingMessageSize, AndroidUtilities.roundPlayingMessageSize, Gravity.CENTER));

        zoomSlider = new InstantCameraZoomSlider(context, resourcesProvider);
        zoomSlider.setOnCameraZoomChangeListener((zoom, fromSlider) -> {
            if (fromSlider) {
                cancelZoomAnimations();
            }
            cameraZoom = zoom;
        });
        zoomSlider.setOpenAlpha(0f);
        addView(zoomSlider, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        if (DebugConfig.getDebugCameraMetrics()) {
            DebugOverlayView overlay = new DebugOverlayView(context);
            overlay.setDataSource(this::populateCameraDebugOverlay);
            addView(overlay, DebugOverlayView.createLayoutParams());
        }

        setVisibilityFromPause = false;
        setVisibility(INVISIBLE);
    }

    public void setButtonsBackground(BlurredBackgroundDrawableViewFactory factory, BlurredBackgroundColorProvider colorProvider) {
        BlurredBackgroundDrawable drawable = factory.create(buttonsLayout, colorProvider);
        drawable.setPadding(dp(6));
        drawable.setRadius(dp(21));
        buttonsLayout.setBackground(drawable);
        zoomSlider.setBlurBackground(factory.create(zoomSlider, colorProvider));
    }

    private Boolean wasFlashing;
    private boolean flashing;
    private boolean frontFlashing;
    private void updateFlash() {
        final boolean shouldFrontFlash = flashing && recording && isFrontface;
        if (frontFlashing != shouldFrontFlash) {
            if (frontFlashing = shouldFrontFlash) {
                flashViews.flashIn(null);
            } else {
                flashViews.flashOut();
            }
        }

        if (ExteraConfig.getCameraType() == CameraType.CAMERA_X) {
            if (cameraXSession != null && isCameraReady()) {
                cameraXSession.setTorchEnabled(flashing && recording);
            }
        } else if (useCamera2) {
            if (camera2Sessions[1] != null) {
                camera2Sessions[1].setFlash(flashing && !isFrontface && recording);
            }
        } else {
            if (cameraSession != null) {
//                final String mode = (
//                    (flashing && !isFrontface && recording) ?
//                        (cameraSession.availableFlashModes != null && cameraSession.availableFlashModes.contains(Camera.Parameters.FLASH_MODE_TORCH) ? Camera.Parameters.FLASH_MODE_TORCH : Camera.Parameters.FLASH_MODE_ON) :
//                        Camera.Parameters.FLASH_MODE_OFF
//                );
//                cameraSession.setCurrentFlashMode(mode);
                cameraSession.setTorchEnabled(flashing && !isFrontface && recording);
            }
        }

        if (flashButton != null && (wasFlashing == null || wasFlashing != flashing)) {
            flashButton.setContentDescription(LocaleController.getString(flashing ? R.string.AccDescrCameraFlashOff : R.string.AccDescrCameraFlashOn));
            if (!flashing) {
                if (flashOnDrawable == null) {
                    flashOnDrawable = new RLottieDrawable(R.raw.roundcamera_flash_on, buttonsSizePx, buttonsSizePx);
                    flashOnDrawable.setCallback(flashButton);
                }
                flashButton.setImageDrawable(flashOnDrawable);
                if (wasFlashing == null) {
                    flashOnDrawable.setCurrentFrame(flashOnDrawable.getFramesCount() - 1);
                } else {
                    flashOnDrawable.setCurrentFrame(0);
                    flashOnDrawable.start();
                }
            } else {
                if (flashOffDrawable == null) {
                    flashOffDrawable = new RLottieDrawable(R.raw.roundcamera_flash_off, buttonsSizePx, buttonsSizePx);
                    flashOffDrawable.setCallback(flashButton);
                }
                flashButton.setImageDrawable(flashOffDrawable);
                if (wasFlashing == null) {
                    flashOffDrawable.setCurrentFrame(flashOffDrawable.getFramesCount() - 1);
                } else {
                    flashOffDrawable.setCurrentFrame(0);
                    flashOffDrawable.start();
                }
            }
            wasFlashing = flashing;
        }
    }

    private void populateCameraDebugOverlay(DebugOverlayView.ContentBuilder builder) {
        builder.title("InstantCamera")
                .kv("front", isFrontface).kv("recording", recording).kv("ready", cameraReady)
                .kv("dual", bothCameras).kv("surface", surfaceIndex).kv("flash", flashing)
                .kv("frontFlash", frontFlashing).kv("texture", cameraTextureAvailable)
                .line("preview0=" + CameraDebugUtils.formatCameraSize(previewSize[0]))
                .line("preview1=" + CameraDebugUtils.formatCameraSize(previewSize[1]))
                .line("zoom.stops=" + CameraDebugUtils.formatZoomStops(zoomSlider.getToggleStops()))
                .line("zoom.lenses=" + CameraDebugUtils.formatZoomStops(zoomSlider.getOpticalZoomRatios()));
        if (ExteraConfig.getCameraType() == CameraType.CAMERA_X) {
            builder.section("CameraX");
            CameraXSession session = cameraXSession;
            if (session == null) {
                builder.line("session=null");
                return;
            }
            builder.kv("cx.init", session.isInitiated()).kv("cx.ready", session.isReady())
                    .kv("cx.dual", session.isDualMode()).kv("cx.front", session.isFrontface())
                    .line("cx.zoom=" + CameraDebugUtils.safeCameraXZoomRatio(session) + " ["
                            + CameraDebugUtils.safeCameraXMinZoomRatio(session) + ".."
                            + CameraDebugUtils.safeCameraXMaxZoomRatio(session) + "]")
                    .line("cx.fpsRanges=" + CameraDebugUtils.getCameraXSupportedFpsRanges(session))
                    .line("bound=" + CameraDebugUtils.getCameraXBoundCameraList(session))
                    .line("avail=" + CameraDebugUtils.getCameraXAvailableCameraList(session))
                    .line("phys=" + CameraDebugUtils.getCameraXPhysicalCameraList(session));
        } else if (useCamera2) {
            builder.section("Camera2");
            Camera2Session session = camera2SessionCurrent;
            if (session == null) {
                builder.line("session=null");
                return;
            }
            builder.kv("c2.init", session.isInitiated()).kv("c2.flash", session.getFlash())
                    .kv("c2.both", bothCameras)
                    .line("c2.zoom=" + session.getZoom() + " [" + session.getMinZoom()
                            + ".." + session.getMaxZoom() + "]")
                    .line("c2.fpsRanges=" + CameraDebugUtils.getCamera2SupportedFpsRanges(session))
                    .line("ids=" + CameraDebugUtils.getCamera2CameraList(getContext()));
        } else {
            builder.section("Camera1")
                    .kv("c1.init", cameraSession != null && cameraSession.isInitied())
                    .kv("c1.zoom", cameraZoom)
                    .line("c1.fpsRanges=" + CameraDebugUtils.getLegacySupportedFpsRanges(cameraSession))
                    .line("ids=" + CameraDebugUtils.getLegacyCameraList());
        }
    }

    private int internalPaddingBottom;

    public void setInternalPadding(int padding) {
        internalPaddingBottom = padding;
        setPadding(0, 0, 0, padding);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (updateTextureViewSize) {
            int newSize;
            if ((MeasureSpec.getSize(heightMeasureSpec) - getPaddingBottom()) > MeasureSpec.getSize(widthMeasureSpec) * 1.3f) {
                newSize = AndroidUtilities.roundPlayingMessageSize;
            } else {
                newSize = AndroidUtilities.roundMessageSize;
            }
            if (newSize != textureViewSize) {
                textureViewSize = newSize;
                textureOverlayView.getLayoutParams().width = textureOverlayView.getLayoutParams().height = textureViewSize;
                cameraContainer.getLayoutParams().width = cameraContainer.getLayoutParams().height = textureViewSize;
                ((LayoutParams) muteImageView.getLayoutParams()).topMargin = textureViewSize / 2 - dp(24);
                textureOverlayView.setRoundRadius(textureViewSize / 2);
                zoomSlider.setTextureViewSize(textureViewSize);
                cameraContainer.invalidateOutline();
            }
            updateTextureViewSize = false;
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        final int flashWidthSpec = MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY);
        final int flashHeightSpec = MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY);
        flashViews.backgroundView.measure(flashWidthSpec, flashHeightSpec);
        flashViews.foregroundView.measure(flashWidthSpec, flashHeightSpec);
    }

    private boolean checkPointerIds(MotionEvent ev) {
        if (ev.getPointerCount() < 2) {
            return false;
        }
        if (pointerId1 == ev.getPointerId(0) && pointerId2 == ev.getPointerId(1)) {
            return true;
        }
        if (pointerId1 == ev.getPointerId(1) && pointerId2 == ev.getPointerId(0)) {
            return true;
        }
        return false;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        getParent().requestDisallowInterceptTouchEvent(true);
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (getVisibility() != VISIBLE) {
            animationTranslationY = getMeasuredHeight() / 2f;
            updateTranslationY();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.fileUploaded);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileUploaded);
        if (flashViews != null) {
            flashViews.flashOut();
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.fileUploaded) {
            final String location = (String) args[0];
            if (cameraFile != null && cameraFile.getAbsolutePath().equals(location)) {
                file = (TLRPC.InputFile) args[1];
                encryptedFile = (TLRPC.InputEncryptedFile) args[2];
                size = (Long) args[5];
                if (encryptedFile != null) {
                    key = (byte[]) args[3];
                    iv = (byte[]) args[4];
                }
            }
        }
    }

    public void destroy(boolean async) {
        cancelZoomAnimations();
        zoomSlider.unbindSession();
        if (ExteraConfig.getCameraType() == CameraType.CAMERA_X) {
            releaseCameraXSession();
        } else if (useCamera2) {
            for (int a = 0; a < camera2Sessions.length; ++a) {
                if (camera2Sessions[a] != null) {
                    camera2Sessions[a].destroy(async);
                    camera2Sessions[a] = null;
                }
            }
        } else {
            if (cameraSession != null) {
                cameraSession.destroy();
                CameraController.getInstance().close(cameraSession, !async ? new CountDownLatch(1) : null, null);
            }
        }
    }

    private void releaseCameraXSession() {
        CameraXSession session = cameraXSession;
        cameraXSession = null;
        zoomSlider.unbindSession();
        if (session != null) {
            try {
                session.closeCamera();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float x = cameraContainer.getX();
        float y = cameraContainer.getY();
        rect.set(x - dp(8), y - dp(8), x + cameraContainer.getMeasuredWidth() + dp(8), y + cameraContainer.getMeasuredHeight() + dp(8));
        if (recording) {
            recordedTime = System.currentTimeMillis() - recordStartTime + recordPlusTime;
            progress = Math.min(1f, recordedTime / (float) SystemUtils.getRoundVideoMaxDurationMs());
            invalidate();
        }

        if (progress != 0) {
            canvas.save();
            if (!flipAnimationInProgress) {
                canvas.scale(cameraContainer.getScaleX(), cameraContainer.getScaleY(), rect.centerX(), rect.centerY());
            }
            canvas.drawArc(rect, -90, 360 * progress, false, paint);
            canvas.restore();
        }
    }

    private boolean setVisibilityFromPause;
    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);

        buttonsLayout.setAlpha(0.0f);
        cameraContainer.setAlpha(0.0f);
        textureOverlayView.setAlpha(0.0f);
        zoomSlider.setOpenAlpha(0.0f);
        muteImageView.setAlpha(0.0f);
        muteImageView.setScaleX(1.0f);
        muteImageView.setScaleY(1.0f);
        cameraContainer.setScaleX(setVisibilityFromPause ? 1f : 0.1f);
        cameraContainer.setScaleY(setVisibilityFromPause ? 1f : 0.1f);
        textureOverlayView.setScaleX(setVisibilityFromPause ? 1f : 0.1f);
        textureOverlayView.setScaleY(setVisibilityFromPause ? 1f : 0.1f);
        if (cameraContainer.getMeasuredWidth() != 0) {
            cameraContainer.setPivotX(cameraContainer.getMeasuredWidth() / 2);
            cameraContainer.setPivotY(cameraContainer.getMeasuredHeight() / 2);
            textureOverlayView.setPivotX(textureOverlayView.getMeasuredWidth() / 2);
            textureOverlayView.setPivotY(textureOverlayView.getMeasuredHeight() / 2);
        }
        try {
            if (visibility == VISIBLE) {
                ((Activity) getContext()).getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                ((Activity) getContext()).getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public void togglePause() {
        if (recording) {
            cancelled = recordedTime < 800;
            recording = false;
            updateFlash();
            if (cameraThread != null) {
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.recordStopped, recordingGuid, cancelled ? 4 : 2);
                saveLastCameraBitmap();
                cameraThread.shutdown(cancelled ? 0 : 2, true, 0, 0, cancelled ? 0 : -2, 0);
                cameraThread = null;
            }
            if (cancelled) {
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.audioRecordTooShort, recordingGuid, true, (int) recordedTime);
                startAnimation(false, false);
                MediaController.getInstance().requestRecordAudioFocus(false);
            } else if (videoEncoder != null) {
                if (previewFile != null) {
                    previewFile.delete();
                }
                previewFile = StoryEntry.makeCacheFile(currentAccount, true);
                videoEncoder.pause(previewFile);
            }
        } else if (videoEncoder != null) {
            hideCamera(false);
            if (videoPlayer != null) {
                videoPlayer.releasePlayer(true);
                videoPlayer = null;
            }
            showCamera(true);
            try {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            } catch (Exception ignore) {}
            AndroidUtilities.lockOrientation(delegate.getParentActivity());
            invalidate();
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.recordResumed);
        }
    }

    public boolean isPaused() {
        return !recording;
    }

    public void showCamera(boolean fromPaused) {
        if (textureView != null) {
            return;
        }

        camLifecycle = new CameraXSession.CameraLifecycle();

        if (switchCameraDrawable == null) {
            switchCameraDrawable = new RLottieDrawable(R.raw.roundcamera_flip, buttonsSizePx, buttonsSizePx);
            switchCameraDrawable.setCurrentFrame(0);
            switchCameraDrawable.setCallback(switchCameraButton);
        }
        switchCameraButton.setImageDrawable(switchCameraDrawable);

        textureOverlayView.setAlpha(1.0f);
        textureOverlayView.invalidate();
        if (lastBitmap == null) {
            try {
                File file = new File(ApplicationLoader.getFilesDirFixed(), "icthumb.jpg");
                lastBitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
            } catch (Throwable ignore) {

            }
        }
        if (lastBitmap != null) {
            textureOverlayView.setImageBitmap(lastBitmap);
        } else {
            textureOverlayView.setImageResource(R.drawable.icplaceholder);
        }
        cameraReady = false;
        selectedCamera = null;
        if (!fromPaused) {
            if (ExteraConfig.getVideoMessagesCamera() != VideoMessagesCamera.ASK) {
                isFrontface = ExteraConfig.getVideoMessagesCamera() == VideoMessagesCamera.FRONT;
            }
            updateFlash();
            recordedTime = 0;
            progress = 0;
        }
        cancelled = false;
        file = null;
        encryptedFile = null;
        key = null;
        iv = null;
        needDrawFlickerStub = true;

        if (!initCamera()) {
            return;
        }
        if (MediaController.getInstance().getPlayingMessageObject() != null) {
            if (MediaController.getInstance().getPlayingMessageObject().isVideo() || MediaController.getInstance().getPlayingMessageObject().isRoundVideo()) {
                MediaController.getInstance().cleanupPlayer(true, true);
            } else if (SharedConfig.pauseMusicOnRecord) {
                MediaController.getInstance().pauseByRewind();
            }
        }

        if (!fromPaused) {
            cameraFile = new File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_DOCUMENT), System.currentTimeMillis() + "_" + SharedConfig.getLastLocalId() + ".mp4") {
                @Override
                public boolean delete() {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.e("delete camera file");
                    }
                    return super.delete();
                }
            };
        }

        SharedConfig.saveConfig();
        AutoDeleteMediaTask.lockFile(cameraFile);

        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("InstantCamera show round camera " + cameraFile.getAbsolutePath());
        }

        if (useCamera2) {
            bothCameras = DualCameraView.roundDualAvailableStatic(getContext());
            if (bothCameras) {
                for (int a = 0; a < 2; ++a) {
                    if (camera2Sessions[a] == null) {
                        camera2Sessions[a] = Camera2Session.create(a == 0, SystemUtils.getRoundVideoResolution(), SystemUtils.getRoundVideoResolution());
                        if (camera2Sessions[a] != null) {
                            camera2Sessions[a].setRecordingVideo(true);
                            previewSize[a] = new Size(camera2Sessions[a].getPreviewWidth(), camera2Sessions[a].getPreviewHeight());
                        }
                    }
                }
                updateFlash();
                camera2SessionCurrent = camera2Sessions[isFrontface ? 0 : 1];
                if (camera2SessionCurrent != null && camera2Sessions[isFrontface ? 1 : 0] == null) {
                    bothCameras = false;
                }
                if (camera2SessionCurrent == null) return;
            } else {
                camera2SessionCurrent = camera2Sessions[isFrontface ? 0 : 1] = Camera2Session.create(isFrontface, SystemUtils.getRoundVideoResolution(), SystemUtils.getRoundVideoResolution());
                if (camera2SessionCurrent == null) return;
                camera2SessionCurrent.setRecordingVideo(true);
                previewSize[0] = new Size(camera2SessionCurrent.getPreviewWidth(), camera2SessionCurrent.getPreviewHeight());
            }
            surfaceIndex = bothCameras && !isFrontface ? 1 : 0;
            bindCamera2ZoomSlider(camera2SessionCurrent);
        } else if (ExteraConfig.getCameraType() == CameraType.CAMERA_X) {
            bothCameras = CameraXSession.isRoundDualAvailable(getContext());
            surfaceIndex = bothCameras && !isFrontface ? 1 : 0;
            if (previewSize[0] == null) {
                int resolution = SystemUtils.getRoundVideoResolution();
                previewSize[0] = new Size(resolution, resolution);
            }
            previewSize[1] = previewSize[0];
        } else {
            bothCameras = false;
            surfaceIndex = 0;
        }
        textureView = new TextureView(getContext());
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("InstantCamera camera surface available");
                }
                if (cameraThread == null && surface != null) {
                    if (cancelled) {
                        return;
                    }
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("InstantCamera start create thread");
                    }
                    cameraThread = new CameraGLThread(surface, width, height);
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, final int width, final int height) {
                if (cameraThread != null) {
                    cameraThread.surfaceWidth = width;
                    cameraThread.surfaceHeight = height;
                    cameraThread.updateScale();
                }
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                cancelZoomAnimations();
                zoomSlider.unbindSession();
                if (cameraThread != null) {
                    cameraThread.shutdown(0, true, 0, 0, 0, 0);
                    cameraThread = null;
                }
                if (ExteraConfig.getCameraType() == CameraType.CAMERA_X) {
                    releaseCameraXSession();
                } else if (useCamera2) {
                    for (int a = 0; a < camera2Sessions.length; ++a) {
                        if (camera2Sessions[a] != null) {
                            camera2Sessions[a].destroy(false);
                            camera2Sessions[a] = null;
                        }
                    }
                } else {
                    if (cameraSession != null) {
                        CameraController.getInstance().close(cameraSession, null, null);
                    }
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {

            }
        });
        cameraContainer.addView(textureView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        updateTextureViewSize = true;
        setVisibilityFromPause = fromPaused;
        setVisibility(VISIBLE);

        startAnimation(true, fromPaused);
        MediaController.getInstance().requestRecordAudioFocus(true);
    }

    public InstantViewCameraContainer getCameraContainer() {
        return cameraContainer;
    }

    public void startAnimation(boolean open, boolean fromPaused) {
        dispatchAnimationState(open, fromPaused);
        if (animatorSet != null) {
            animatorSet.removeAllListeners();
            animatorSet.cancel();
        }
        PipRoundVideoView pipRoundVideoView = PipRoundVideoView.getInstance();
        if (pipRoundVideoView != null) {
            pipRoundVideoView.showTemporary(!open);
        }
        if (open && !opened) {
            cameraContainer.setTranslationX(0);
            textureOverlayView.setTranslationX(0);

            animationTranslationY = fromPaused ? 0 : getMeasuredHeight() / 2f;
            updateTranslationY();
        }
        opened = open;
        if (parentView != null) {
            parentView.invalidate();
        }
        animatorSet = new AnimatorSet();
        float toX = 0;
        if (!open) {
            toX = recordedTime > 300 ? dp(24) - getMeasuredWidth() / 2f : 0;
        }
        ValueAnimator translationYAnimator = ValueAnimator.ofFloat(open ? 1f : 0f, open ? 0 : 1f);
        translationYAnimator.addUpdateListener(animation -> {
            animationTranslationY = fromPaused ? 0 : (getMeasuredHeight() / 2f) * (float) animation.getAnimatedValue();
            updateTranslationY();
        });
        animatorSet.playTogether(
                ObjectAnimator.ofFloat(buttonsLayout, View.ALPHA, open ? 1.0f : 0.0f),
                ObjectAnimator.ofFloat(zoomSlider, InstantCameraZoomSlider.OPEN_ALPHA, open ? 1.0f : 0.0f),
                ObjectAnimator.ofFloat(muteImageView, View.ALPHA, 0.0f),
                ObjectAnimator.ofInt(paint, AnimationProperties.PAINT_ALPHA, open ? 255 : 0),
                ObjectAnimator.ofFloat(cameraContainer, View.ALPHA, open ? 1.0f : 0.0f),
                ObjectAnimator.ofFloat(cameraContainer, View.SCALE_X, open ? 1.0f : 0.1f),
                ObjectAnimator.ofFloat(cameraContainer, View.SCALE_Y, open ? 1.0f : 0.1f),
                ObjectAnimator.ofFloat(cameraContainer, View.TRANSLATION_X, toX),
                ObjectAnimator.ofFloat(textureOverlayView, View.ALPHA, open ? 1.0f : 0.0f),
                ObjectAnimator.ofFloat(textureOverlayView, View.SCALE_X, open ? 1.0f : 0.1f),
                ObjectAnimator.ofFloat(textureOverlayView, View.SCALE_Y, open ? 1.0f : 0.1f),
                ObjectAnimator.ofFloat(textureOverlayView, View.TRANSLATION_X, toX),
                translationYAnimator
        );
        if (!open) {
            animatorSet.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (animation.equals(animatorSet)) {
                        hideCamera(true);
                        setVisibilityFromPause = false;
                        setVisibility(INVISIBLE);
                    }
                }
            });
        } else {
            setTranslationX(0);
        }
        animatorSet.setDuration(180);
        animatorSet.setInterpolator(new DecelerateInterpolator());
        animatorSet.start();
    }

    private void updateTranslationY() {
        textureOverlayView.setTranslationY(animationTranslationY + panTranslationY);
        cameraContainer.setTranslationY(animationTranslationY + panTranslationY);
        zoomSlider.setBaseTranslationY(animationTranslationY + panTranslationY);
    }

    public RectF getCameraRect() {
        cameraContainer.getLocationOnScreen(position);
        return new RectF(
                position[0],
                position[1],
                position[0] + cameraContainer.getWidth(),
                position[1] + cameraContainer.getHeight()
        );
    }

    public void changeVideoPreviewState(int state, float progress) {
        if (videoPlayer == null) {
            return;
        }
        if (state == 0) {
            startProgressTimer();
            videoPlayer.play();
        } else if (state == 1) {
            stopProgressTimer();
            videoPlayer.pause();
        } else if (state == 2) {
            videoPlayer.seekTo((long) (progress * videoPlayer.getDuration()));
        }
    }

    public void send(int state, boolean notify, int scheduleDate, int scheduleRepeatPeriod, int ttl, long effectId, long stars) {
        if (textureView == null) {
            return;
        }
        stopProgressTimer();
        if (videoPlayer != null) {
            videoPlayer.releasePlayer(true);
            videoPlayer = null;
        }
        if (state == 4) {
            if (videoEncoder != null && recordedTime > 800) {
                requestStopRecording(1, new SendOptions(notify, scheduleDate, scheduleRepeatPeriod, ttl, effectId, stars));
                return;
            }
            if (BuildVars.DEBUG_VERSION && !cameraFile.exists()) {
                FileLog.e(new RuntimeException("file not found :( round video"));
            }
            if (videoEditedInfo == null) {
                videoEditedInfo = new VideoEditedInfo();
                videoEditedInfo.startTime = -1;
                videoEditedInfo.endTime = -1;
            }
            if (videoEditedInfo.needConvert()) {
                file = null;
                encryptedFile = null;
                key = null;
                iv = null;
                double totalDuration = videoEditedInfo.estimatedDuration;
                long startTime = videoEditedInfo.startTime >= 0 ? videoEditedInfo.startTime : 0;
                long endTime = videoEditedInfo.endTime >= 0 ? videoEditedInfo.endTime : videoEditedInfo.estimatedDuration;
                videoEditedInfo.estimatedDuration = endTime - startTime;
                videoEditedInfo.estimatedSize = Math.max(1, (long) (size * (videoEditedInfo.estimatedDuration / totalDuration)));
                videoEditedInfo.bitrate = SystemUtils.getRoundVideoBitrate() * 1024;
                if (videoEditedInfo.startTime > 0) {
                    videoEditedInfo.startTime *= 1000;
                }
                if (videoEditedInfo.endTime > 0) {
                    videoEditedInfo.endTime *= 1000;
                }
                FileLoader.getInstance(currentAccount).cancelFileUpload(cameraFile.getAbsolutePath(), false);
            } else {
                videoEditedInfo.estimatedSize = Math.max(1, size);
            }
            videoEditedInfo.file = file;
            videoEditedInfo.encryptedFile = encryptedFile;
            videoEditedInfo.key = key;
            videoEditedInfo.iv = iv;
            MediaController.PhotoEntry entry = new MediaController.PhotoEntry(0, 0, 0, cameraFile.getAbsolutePath(), 0, true, 0, 0, 0);
            entry.ttl = ttl;
            entry.effectId = effectId;
            delegate.sendMedia(entry, videoEditedInfo, notify, scheduleDate, scheduleRepeatPeriod, false, stars);
            if (scheduleDate != 0) {
                startAnimation(false, false);
            }
            MediaController.getInstance().requestRecordAudioFocus(false);
        } else {
            cancelled = recordedTime < 800;
            recording = false;
            flashing = false;
            updateFlash();
            int reason;
            if (cancelled) {
                reason = 4;
            } else {
                reason = state == 3 ? 2 : 5;
            }
            if (cameraThread != null) {
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.recordStopped, recordingGuid, reason);
                int send;
                if (cancelled) {
                    send = 0;
                } else if (state == 3) {
                    send = 2;
                } else {
                    send = 1;
                }
                saveLastCameraBitmap();
                cameraThread.shutdown(send, notify, scheduleDate, scheduleRepeatPeriod, ttl, effectId);
                cameraThread = null;
            }
            if (cancelled) {
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.audioRecordTooShort, recordingGuid, true, (int) recordedTime);
                startAnimation(false, false);
                MediaController.getInstance().requestRecordAudioFocus(false);
            }
        }
    }

    private void saveLastCameraBitmap() {
        Bitmap bitmap = textureView.getBitmap();
        if (bitmap == null || bitmap.getPixel(0, 0) == 0) {
            return;
        }
        final Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, 50, 50, true);
        lastBitmap = scaledBitmap;
        Utilities.blurBitmap(scaledBitmap, 7);
        Utilities.globalQueue.postRunnable(() -> {
            try {
                File file = new File(ApplicationLoader.getFilesDirFixed(), "icthumb.jpg");
                FileOutputStream stream = new FileOutputStream(file);
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 87, stream);
                stream.close();
            } catch (Throwable ignore) {

            }
        });
    }

    public void cancel(boolean byGesture) {
        stopProgressTimer();
        if (videoPlayer != null) {
            videoPlayer.releasePlayer(true);
            videoPlayer = null;
        }
        if (textureView == null) {
            return;
        }
        cancelled = true;
        recording = false;
        flashing = false;
        updateFlash();
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.recordStopped, recordingGuid, byGesture ? 0 : 6);
        if (cameraThread != null) {
            saveLastCameraBitmap();
            cameraThread.shutdown(0, true, 0, 0, 0, 0);
            cameraThread = null;
        } else if (videoEncoder != null) {
            requestStopRecording(0, new SendOptions(true, 0, 0, 0, 0, 0));
        }
        if (cameraFile != null) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.e("delete camera file by cancel");
            }
            cameraFile.delete();
            AutoDeleteMediaTask.unlockFile(cameraFile);
            cameraFile = null;
        }
        MediaController.getInstance().requestRecordAudioFocus(false);
        startAnimation(false, false);
        invalidate();
    }

    public View getButtonsLayout() {
        return buttonsLayout;
    }

    public InstantCameraZoomSlider getZoomSlider() {
        return zoomSlider;
    }

    public View getMuteImageView() {
        return muteImageView;
    }

    public Paint getPaint() {
        return paint;
    }

    public void hideCamera(boolean async) {
        destroy(async);
        cameraContainer.setTranslationX(0);
        textureOverlayView.setTranslationX(0);
        animationTranslationY = 0;
        updateTranslationY();
        MediaController.getInstance().resumeByRewind();

        if (textureView != null) {
            ViewGroup parent = (ViewGroup) textureView.getParent();
            if (parent != null) {
                parent.removeView(textureView);
            }
        }
        textureView = null;
        cameraContainer.setImageReceiver(null);
    }

    private void switchCamera() {
        if (cameraThread == null) {
            return;
        }
        cancelZoomAnimations();
        zoomSlider.beginCameraSwitch();
        if (!(useCamera2 && bothCameras)) {
            saveLastCameraBitmap();
            if (lastBitmap != null) {
                needDrawFlickerStub = false;
                textureOverlayView.setImageBitmap(lastBitmap);
                textureOverlayView.setAlpha(1f);
            }
        }
        isFrontface = !isFrontface;
        rememberCameraSelection();
        updateFlash();
        if (useCamera2) {
            if (bothCameras) {
                camera2SessionCurrent = camera2Sessions[isFrontface ? 0 : 1];
                bindCamera2ZoomSlider(camera2SessionCurrent);
                cameraThread.flipSurfaces();
                return;
            } else {
                if (camera2SessionCurrent != null) {
                    camera2SessionCurrent.destroy(false);
                    camera2SessionCurrent = null;
                    camera2Sessions[isFrontface ? 1 : 0] = null;
                }
                camera2SessionCurrent = camera2Sessions[isFrontface ? 0 : 1] = Camera2Session.create(isFrontface, SystemUtils.getRoundVideoResolution(), SystemUtils.getRoundVideoResolution());
                if (camera2SessionCurrent == null) return;
                camera2SessionCurrent.setRecordingVideo(true);
                previewSize[0] = new Size(camera2SessionCurrent.getPreviewWidth(), camera2SessionCurrent.getPreviewHeight());
                cameraThread.setCurrentSession(camera2SessionCurrent);
                bindCamera2ZoomSlider(camera2SessionCurrent);
            }
        } else {
            if (cameraSession != null) {
                cameraSession.destroy();
                CameraController.getInstance().close(cameraSession, null, null);
                cameraSession = null;
            }
        }
        initCamera();
        cameraReady = false;
        cameraZoom = useCamera2 && camera2SessionCurrent != null ? camera2SessionCurrent.getZoom() : 0f;
        cameraThread.reinitForNewCamera();
    }

    private void switchCameraX() {
        if (cameraThread == null || ExteraConfig.getCameraType() != CameraType.CAMERA_X) {
            return;
        }
        cancelZoomAnimations();
        zoomSlider.beginCameraSwitch();
        boolean dual = cameraXSession != null && cameraXSession.isDualMode();
        isFrontface = !isFrontface;
        rememberCameraSelection();
        updateFlash();
        if (dual) {
            cameraThread.flipSurfaces();
        } else {
            cameraReady = false;
        }
        if (cameraXSession != null) {
            cameraXSession.switchCamera();
            zoomSlider.bindSession(cameraXSession);
            cameraThread.setOrientation();
        } else {
            cameraThread.reinitForNewCamera();
        }
    }

    private void rememberCameraSelection() {
        if (ExteraConfig.getRememberLastUsedCamera()
                && ExteraConfig.getVideoMessagesCamera() != VideoMessagesCamera.ASK) {
            ExteraConfig.setVideoMessagesCamera(isFrontface
                    ? VideoMessagesCamera.FRONT : VideoMessagesCamera.REAR);
        }
    }

    private void bindCamera2ZoomSlider(Camera2Session session) {
        if (session == null) {
            zoomSlider.unbindSession();
            return;
        }
        cameraZoom = session.getZoom();
        zoomSlider.bindSession(session);
        session.whenDone(() -> {
            if (camera2SessionCurrent == session && ExteraConfig.getCameraType() == CameraType.CAMERA_2) {
                cameraZoom = session.getZoom();
                zoomSlider.bindSession(session);
            }
        });
    }

    // Old Camera1 API
    @Deprecated
    private boolean initCamera() {
        if (useCamera2 || ExteraConfig.getCameraType() == CameraType.CAMERA_X) {
            return true;
        }
        ArrayList<CameraInfo> cameraInfos = CameraController.getInstance().getCameras();
        if (cameraInfos == null) {
            return false;
        }
        CameraInfo notFrontface = null;
        for (int a = 0; a < cameraInfos.size(); a++) {
            CameraInfo cameraInfo = cameraInfos.get(a);
            if (!cameraInfo.isFrontface()) {
                notFrontface = cameraInfo;
            }
            if (isFrontface && cameraInfo.isFrontface() || !isFrontface && !cameraInfo.isFrontface()) {
                selectedCamera = cameraInfo;
                break;
            } else {
                notFrontface = cameraInfo;
            }
        }
        if (selectedCamera == null) {
            selectedCamera = notFrontface;
        }
        if (selectedCamera == null) {
            return false;
        }

        ArrayList<Size> previewSizes = selectedCamera.getPreviewSizes();
        ArrayList<Size> pictureSizes = selectedCamera.getPictureSizes();
        previewSize[0] = chooseOptimalSize(previewSizes);
        pictureSize = chooseOptimalSize(pictureSizes);
        if (previewSize[0].mWidth != pictureSize.mWidth) {
            boolean found = false;
            for (int a = previewSizes.size() - 1; a >= 0; a--) {
                Size preview = previewSizes.get(a);
                for (int b = pictureSizes.size() - 1; b >= 0; b--) {
                    Size picture = pictureSizes.get(b);
                    if (preview.mWidth >= pictureSize.mWidth && preview.mHeight >= pictureSize.mHeight && preview.mWidth == picture.mWidth && preview.mHeight == picture.mHeight) {
                        previewSize[0] = preview;
                        pictureSize = picture;
                        found = true;
                        break;
                    }
                }
                if (found) {
                    break;
                }
            }

            if (!found) {
                for (int a = previewSizes.size() - 1; a >= 0; a--) {
                    Size preview = previewSizes.get(a);
                    for (int b = pictureSizes.size() - 1; b >= 0; b--) {
                        Size picture = pictureSizes.get(b);
                        if (preview.mWidth >= 360 && preview.mHeight >= 360 && preview.mWidth == picture.mWidth && preview.mHeight == picture.mHeight) {
                            previewSize[0] = preview;
                            pictureSize = picture;
                            found = true;
                            break;
                        }
                    }
                    if (found) {
                        break;
                    }
                }
            }
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("InstantCamera preview w = " + previewSize[0].mWidth + " h = " + previewSize[0].mHeight);
        }
        return true;
    }

    @Deprecated // used for old Camera1 API only
    private Size chooseOptimalSize(ArrayList<Size> previewSizes) {
        ArrayList<Size> sortedSizes = new ArrayList<>();
        boolean allowBigSizeCamera = allowBigSizeCamera();
        int maxVideoSize = allowBigSizeCamera ? 1440 : 1200;
        if (Build.MANUFACTURER.equalsIgnoreCase("Samsung")) {
            //1440 lead to gl crashes on samsung s9
            maxVideoSize = 1200;
        }
        for (int i = 0; i < previewSizes.size(); i++) {
            if (Math.max(previewSizes.get(i).mHeight, previewSizes.get(i).mWidth) <= maxVideoSize && Math.min(previewSizes.get(i).mHeight, previewSizes.get(i).mWidth) >= 320) {
                sortedSizes.add(previewSizes.get(i));
            }
        }
        if (sortedSizes.isEmpty() || !allowBigSizeCamera()) {
            ArrayList<Size> sizes = sortedSizes;
            if (!sortedSizes.isEmpty()) {
                sizes = sortedSizes;
            } else {
                sizes = previewSizes;
            }
            if (Build.MANUFACTURER.equalsIgnoreCase("Xiaomi")) {
                return CameraController.chooseOptimalSize(sizes, 640, 480, aspectRatio, false);
            } else {
                return CameraController.chooseOptimalSize(sizes, 480, 270, aspectRatio, false);
            }
        }
        Collections.sort(sortedSizes, (o1, o2) -> {
            float a1 = Math.abs(1f - Math.min(o1.mHeight, o1.mWidth) / (float) Math.max(o1.mHeight, o1.mWidth));
            float a2 = Math.abs(1f - Math.min(o2.mHeight, o2.mWidth) / (float) Math.max(o2.mHeight, o2.mWidth));

            if (a1 < a2) {
                return -1;
            } else if (a1 > a2) {
                return 1;
            }
            return 0;
        });
        return sortedSizes.get(0);
    }

    @Deprecated // used for old Camera1 API only
    private boolean allowBigSizeCamera() {
        if (SharedConfig.bigCameraForRound) {
            return true;
        }
        if (SharedConfig.deviceIsAboveAverage()) {
            return true;
        }
        int devicePerformanceClass = Math.max(SharedConfig.getDevicePerformanceClass(), SharedConfig.getLegacyDevicePerformanceClass());
        if (devicePerformanceClass == SharedConfig.PERFORMANCE_CLASS_HIGH) {
            return true;
        }
        int hash = (Build.MANUFACTURER + " " + Build.DEVICE).toUpperCase().hashCode();
        for (int i = 0; i < ALLOW_BIG_CAMERA_WHITELIST.length; ++i) {
            if (ALLOW_BIG_CAMERA_WHITELIST[i] == hash) {
                return true;
            }
        }
        return false;
    }

    @Deprecated // used for old Camera1 API only
    public static boolean allowBigSizeCameraDebug() {
        int devicePerformanceClass = Math.max(SharedConfig.getDevicePerformanceClass(), SharedConfig.getLegacyDevicePerformanceClass());
        if (devicePerformanceClass == SharedConfig.PERFORMANCE_CLASS_HIGH) {
            return true;
        }
        int hash = (Build.MANUFACTURER + " " + Build.DEVICE).toUpperCase().hashCode();
        for (int i = 0; i < ALLOW_BIG_CAMERA_WHITELIST.length; ++i) {
            if (ALLOW_BIG_CAMERA_WHITELIST[i] == hash) {
                return true;
            }
        }
        return false;
    }

    private void createCamera(final int index, final SurfaceTexture surfaceTexture) {
        AndroidUtilities.runOnUIThread(() -> {
            if (cameraThread == null) {
                return;
            }
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("InstantCamera create camera session " + index);
            }

            if (ExteraConfig.getCameraType() == CameraType.CAMERA_X) {
                Preview.SurfaceProvider provider = CameraXSession.createSurfaceProvider(
                        getContext(), surfaceTexture, (width, height) -> {
                            CameraGLThread thread = cameraThread;
                            if (thread != null) {
                                thread.setCameraXPreviewSize(index, width, height);
                            }
                        });
                if (index == 0) {
                    CameraXSession session = new CameraXSession(camLifecycle, provider);
                    cameraXSession = session;
                    session.initCamera(getContext(), isFrontface, bothCameras, () -> {
                        if (cameraXSession != session) {
                            return;
                        }
                        boolean dual = session.isDualMode();
                        if (!dual && bothCameras && surfaceIndex != 0 && cameraThread != null) {
                            cameraThread.flipSurfaces();
                        }
                        bothCameras = dual;
                        if (cameraThread != null) {
                            cameraThread.setOrientation();
                        }
                        zoomSlider.bindSession(session);
                        updateFlash();
                    });
                } else if (bothCameras && cameraXSession != null) {
                    cameraXSession.setSecondSurfaceProvider(provider);
                }
            } else if (useCamera2) {
                if (bothCameras) {
                    if (camera2Sessions[index] != null) {
                        camera2Sessions[index].open(surfaceTexture);
                    }
                } else {
                    if (index == 1) return;
                    cameraThread.setCurrentSession(camera2SessionCurrent);
                    camera2SessionCurrent.open(surfaceTexture);
                }
            } else {
                if (index == 1) return;
                surfaceTexture.setDefaultBufferSize(previewSize[0].getWidth(), previewSize[0].getHeight());
                cameraSession = new CameraSession(selectedCamera, previewSize[0], pictureSize, ImageFormat.JPEG, true);
                updateFlash();
                cameraThread.setCurrentSession(cameraSession);
                CameraController.getInstance().openRound(cameraSession, surfaceTexture, () -> {
                    if (cameraSession != null) {
                        updateFlash();

                        boolean updateScale = false;
                        try {
                            Camera.Size size = cameraSession.getCurrentPreviewSize();
                            if (size.width != previewSize[0].getWidth() || size.height != previewSize[0].getHeight()) {
                                previewSize[0] = new Size(size.width, size.height);
                                FileLog.d("InstantCamera change preview size to w = " + previewSize[0].getWidth() + " h = " + previewSize[0].getHeight());
                            }
                        } catch (Exception e) {
                            FileLog.e(e);
                        }

                        try {
                            Camera.Size size = cameraSession.getCurrentPictureSize();
                            if (size.width != pictureSize.getWidth() || size.height != pictureSize.getHeight()) {
                                pictureSize = new Size(size.width, size.height);
                                FileLog.d("InstantCamera change picture size to w = " + pictureSize.getWidth() + " h = " + pictureSize.getHeight());
                                updateScale = true;
                            }
                        } catch (Exception e) {
                            FileLog.e(e);
                        }
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("InstantCamera camera initied");
                        }
                        cameraSession.setInitied();
                        zoomSlider.bindSession(cameraSession, cameraZoom);
                        if (updateScale) {
                            if (cameraThread != null) {
                                cameraThread.reinitForNewCamera();
                            }
                        }
                    }
                }, () -> {
                    if (cameraThread != null) {
                        cameraThread.setCurrentSession(cameraSession);
                    }
                });
            }
        });
    }

    private int loadShader(int type, String shaderCode) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);
        int[] compileStatus = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);
        if (compileStatus[0] == 0) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.e(GLES20.glGetShaderInfoLog(shader));
            }
            GLES20.glDeleteShader(shader);
            shader = 0;
        }
        return shader;
    }

    private Timer progressTimer;

    private void startProgressTimer() {
        if (progressTimer != null) {
            try {
                progressTimer.cancel();
                progressTimer = null;
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        progressTimer = new Timer();
        progressTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        if (videoPlayer != null && videoEditedInfo != null && videoEditedInfo.endTime > 0 && videoPlayer.getCurrentPosition() >= videoEditedInfo.endTime) {
                            videoPlayer.seekTo(videoEditedInfo.startTime > 0 ? videoEditedInfo.startTime : 0);
                        }
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                });
            }
        }, 0, 17);
    }

    private void stopProgressTimer() {
        if (progressTimer != null) {
            try {
                progressTimer.cancel();
                progressTimer = null;
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    public void onPanTranslationUpdate(float y) {
        panTranslationY = y / 2f;
        updateTranslationY();
    }

    public TextureView getTextureView() {
        return textureView;
    }

    public void setIsMessageTransition(boolean isMessageTransition) {
        this.isMessageTransition = isMessageTransition;
    }

    public void resetCameraFile() {
        cameraFile = null;
    }

    private int resolveEncoderFrameRate() {
        if (!ExteraConfig.getExtendedFramesPerSecond()) {
            return 30;
        }
        if (useCamera2 && camera2SessionCurrent != null) {
            return camera2SessionCurrent.getRecordingFrameRate();
        }
        if (ExteraConfig.getCameraType() == CameraType.CAMERA_X && cameraXSession != null) {
            return cameraXSession.getRecordingFrameRate();
        }
        return 30;
    }

    private int encoderFrameRate = 30;
    private RoundVideoEncoder videoEncoder;
    private File encoderFile;
    private int encoderSend;
    private SendOptions encoderSendOptions;
    private boolean encoderFinishRequested;
    private boolean sentMedia;
    private boolean videoConvertFirstWrite;
    private final ArrayList<Bitmap> keyframeThumbs = new ArrayList<>();
    private DispatchQueue generateKeyframeThumbsQueue;

    private final RoundVideoEncoder.Callback encoderCallback = new RoundVideoEncoder.Callback() {
        @Override
        public void onRecordingStarted(boolean resumed) {
            if (cancelled) {
                return;
            }
            try {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            } catch (Exception ignore) {
            }
            AndroidUtilities.lockOrientation(delegate.getParentActivity());
            recordPlusTime = resumed ? recordedTime : 0;
            recordStartTime = System.currentTimeMillis();
            recording = true;
            updateFlash();
            invalidate();
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.recordStarted, recordingGuid, false);
        }

        @Override
        public void onAudioAmplitude(double amplitude) {
            AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.recordProgressChanged, recordingGuid, amplitude));
        }

        @Override
        public void onWriteData(long availableSize) {
            File output = encoderFile;
            if (output != null) {
                didWriteData(output, availableSize, false);
            }
        }

        @Override
        public void onPaused(File preview) {
            handleEncoderPaused(preview);
        }

        @Override
        public void onFinished(RoundVideoEncoder.FinishReason reason) {
            handleEncoderFinished(reason);
        }
    };

    private void requestStopRecording(int send, SendOptions sendOptions) {
        RoundVideoEncoder encoder = videoEncoder;
        if (encoder == null) {
            return;
        }
        if (!encoderFinishRequested) {
            encoderFinishRequested = true;
            encoderSend = send;
            encoderSendOptions = sendOptions;
            if (send == 1) {
                AndroidUtilities.runOnUIThread(() -> sendMediaBeforeDone(sendOptions));
            }
        }
        if (send == 0) {
            encoder.cancel();
        } else {
            encoder.stop();
        }
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.startAllHeavyOperations, 512));
    }

    private void sendMediaBeforeDone(SendOptions sendOptions) {
        File output = encoderFile;
        if (sentMedia || output == null) {
            return;
        }
        if ((videoEditedInfo == null || !videoEditedInfo.needConvert()) && !delegate.isInScheduleMode()) {
            sentMedia = true;
            videoEditedInfo = new VideoEditedInfo();
            videoEditedInfo.startTime = -1;
            videoEditedInfo.endTime = -1;
            videoEditedInfo.estimatedSize = Math.max(1, size);
            videoEditedInfo.roundVideo = true;
            videoEditedInfo.file = file;
            videoEditedInfo.encryptedFile = encryptedFile;
            videoEditedInfo.key = key;
            videoEditedInfo.iv = iv;
            videoEditedInfo.framerate = encoderFrameRate;
            videoEditedInfo.resultWidth = videoEditedInfo.originalWidth = SystemUtils.getRoundVideoResolution();
            videoEditedInfo.resultHeight = videoEditedInfo.originalHeight = SystemUtils.getRoundVideoResolution();
            videoEditedInfo.originalPath = output.getAbsolutePath();
            videoEditedInfo.notReadyYet = true;
            videoEditedInfo.thumb = firstFrameThumb;
            videoEditedInfo.estimatedDuration = recordedTime;
            firstFrameThumb = null;
            MediaController.PhotoEntry photoEntry = new MediaController.PhotoEntry(0, 0, 0, output.getAbsolutePath(), 0, true, 0, 0, 0);
            if (sendOptions != null) {
                photoEntry.ttl = sendOptions.ttl;
                photoEntry.effectId = sendOptions.effectId;
            }
            delegate.sendMedia(photoEntry, videoEditedInfo, sendOptions == null || sendOptions.notify, sendOptions != null ? sendOptions.scheduleDate : 0, sendOptions != null ? sendOptions.scheduleRepeatPeriod : 0, false, sendOptions != null ? sendOptions.stars : 0);
        }
    }

    private void handleEncoderPaused(File preview) {
        if (preview == null || cancelled) {
            return;
        }
        videoEditedInfo = new VideoEditedInfo();
        videoEditedInfo.roundVideo = true;
        videoEditedInfo.startTime = -1;
        videoEditedInfo.endTime = -1;
        videoEditedInfo.file = file;
        videoEditedInfo.encryptedFile = encryptedFile;
        videoEditedInfo.key = key;
        videoEditedInfo.iv = iv;
        videoEditedInfo.estimatedSize = Math.max(1, size);
        videoEditedInfo.framerate = encoderFrameRate;
        videoEditedInfo.resultWidth = videoEditedInfo.originalWidth = SystemUtils.getRoundVideoResolution();
        videoEditedInfo.resultHeight = videoEditedInfo.originalHeight = SystemUtils.getRoundVideoResolution();
        videoEditedInfo.originalPath = preview.getAbsolutePath();
        setupVideoPlayer(preview);
        videoEditedInfo.estimatedDuration = recordedTime;
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.audioDidSent, recordingGuid, videoEditedInfo, preview.getAbsolutePath(), keyframeThumbs);
    }

    private void handleEncoderFinished(RoundVideoEncoder.FinishReason reason) {
        videoEncoder = null;
        int send = reason == RoundVideoEncoder.FinishReason.COMPLETED ? encoderSend : 0;
        final SendOptions sendOptions = encoderSendOptions;
        encoderSendOptions = null;
        if (previewFile != null) {
            previewFile.delete();
            previewFile = null;
        }
        if (send != 2 && generateKeyframeThumbsQueue != null) {
            generateKeyframeThumbsQueue.cleanupQueue();
            generateKeyframeThumbsQueue.recycle();
            generateKeyframeThumbsQueue = null;
        }
        FileLog.d("InstantCamera encoder finished send " + send);
        final File output = encoderFile;
        if (send == 0 || output == null) {
            if (output != null) {
                FileLoader.getInstance(currentAccount).cancelFileUpload(output.getAbsolutePath(), false);
            }
            MediaController.getInstance().requestRecordAudioFocus(false);
            if (reason == RoundVideoEncoder.FinishReason.FAILED && !cancelled) {
                handleEncoderFailure();
            }
            return;
        }
        if (!sentMedia) {
            sentMedia = true;
            if (videoEditedInfo == null) {
                videoEditedInfo = new VideoEditedInfo();
                videoEditedInfo.startTime = -1;
                videoEditedInfo.endTime = -1;
            }
            if (videoEditedInfo.needConvert()) {
                file = null;
                encryptedFile = null;
                key = null;
                iv = null;
                double totalDuration = videoEditedInfo.estimatedDuration;
                long startTime = videoEditedInfo.startTime >= 0 ? videoEditedInfo.startTime : 0;
                long endTime = videoEditedInfo.endTime >= 0 ? videoEditedInfo.endTime : videoEditedInfo.estimatedDuration;
                videoEditedInfo.estimatedDuration = endTime - startTime;
                videoEditedInfo.estimatedSize = Math.max(1, (long) (size * (videoEditedInfo.estimatedDuration / totalDuration)));
                videoEditedInfo.bitrate = SystemUtils.getRoundVideoBitrate() * 1024;
                if (videoEditedInfo.startTime > 0) {
                    videoEditedInfo.startTime *= 1000;
                }
                if (videoEditedInfo.endTime > 0) {
                    videoEditedInfo.endTime *= 1000;
                }
                FileLoader.getInstance(currentAccount).cancelFileUpload(output.getAbsolutePath(), false);
            } else {
                videoEditedInfo.estimatedSize = Math.max(1, size);
            }
            videoEditedInfo.roundVideo = true;
            videoEditedInfo.file = file;
            videoEditedInfo.encryptedFile = encryptedFile;
            videoEditedInfo.key = key;
            videoEditedInfo.iv = iv;
            videoEditedInfo.framerate = encoderFrameRate;
            videoEditedInfo.resultWidth = videoEditedInfo.originalWidth = SystemUtils.getRoundVideoResolution();
            videoEditedInfo.resultHeight = videoEditedInfo.originalHeight = SystemUtils.getRoundVideoResolution();
            videoEditedInfo.originalPath = output.getAbsolutePath();
            final VideoEditedInfo info = videoEditedInfo;
            if (send == 1) {
                if (delegate.isInScheduleMode()) {
                    AlertsCreator.createScheduleDatePickerDialog(delegate.getParentActivity(), delegate.getDialogId(), (notify, scheduleDate, scheduleRepeatPeriod) -> {
                        MediaController.PhotoEntry photoEntry = new MediaController.PhotoEntry(0, 0, 0, output.getAbsolutePath(), 0, true, 0, 0, 0);
                        if (sendOptions != null) {
                            photoEntry.ttl = sendOptions.ttl;
                            photoEntry.effectId = sendOptions.effectId;
                        }
                        delegate.sendMedia(photoEntry, info,
                                notify || sendOptions == null || sendOptions.notify,
                                scheduleDate != 0 ? scheduleDate : (sendOptions != null ? sendOptions.scheduleDate : 0),
                                scheduleRepeatPeriod != 0 ? scheduleRepeatPeriod : (sendOptions != null ? sendOptions.scheduleRepeatPeriod : 0),
                                false, sendOptions != null ? sendOptions.stars : 0);
                        startAnimation(false, false);
                    }, () -> startAnimation(false, false), resourcesProvider);
                } else {
                    MediaController.PhotoEntry photoEntry = new MediaController.PhotoEntry(0, 0, 0, output.getAbsolutePath(), 0, true, 0, 0, 0);
                    if (sendOptions != null) {
                        photoEntry.ttl = sendOptions.ttl;
                        photoEntry.effectId = sendOptions.effectId;
                    }
                    delegate.sendMedia(photoEntry, info, sendOptions == null || sendOptions.notify, sendOptions != null ? sendOptions.scheduleDate : 0, sendOptions != null ? sendOptions.scheduleRepeatPeriod : 0, false, sendOptions != null ? sendOptions.stars : 0);
                }
                videoEditedInfo = null;
            } else {
                setupVideoPlayer(output);
                info.estimatedDuration = recordedTime;
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.audioDidSent, recordingGuid, info, output.getAbsolutePath(), keyframeThumbs);
            }
        } else if (videoEditedInfo != null) {
            videoEditedInfo.notReadyYet = false;
        }
        didWriteData(output, 0, true);
        MediaController.getInstance().requestRecordAudioFocus(false);
    }

    private void handleEncoderFailure() {
        boolean wasRecording = recording || cameraThread != null;
        cancelled = true;
        recording = false;
        flashing = false;
        updateFlash();
        stopProgressTimer();
        if (videoPlayer != null) {
            videoPlayer.releasePlayer(true);
            videoPlayer = null;
        }
        if (wasRecording) {
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.recordStopped, recordingGuid, 6);
        }
        if (cameraThread != null) {
            cameraThread.shutdown(0, true, 0, 0, 0, 0);
            cameraThread = null;
        }
        if (cameraFile != null) {
            cameraFile.delete();
            AutoDeleteMediaTask.unlockFile(cameraFile);
            cameraFile = null;
        }
        encoderFile = null;
        startAnimation(false, false);
        invalidate();
    }

    private void didWriteData(File output, long availableSize, boolean last) {
        if (videoConvertFirstWrite) {
            FileLoader.getInstance(currentAccount).uploadFile(output.toString(), isSecretChat, false, 1, ConnectionsManager.FileTypeVideo, false);
            videoConvertFirstWrite = false;
            if (last) {
                FileLoader.getInstance(currentAccount).checkUploadNewDataAvailable(output.toString(), isSecretChat, availableSize, last ? output.length() : 0);
            }
        } else {
            FileLoader.getInstance(currentAccount).checkUploadNewDataAvailable(output.toString(), isSecretChat, availableSize, last ? output.length() : 0);
        }
    }

    private void setupVideoPlayer(File output) {
        videoPlayer = new VideoPlayer();
        videoPlayer.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
            @Override
            public void onStateChanged(boolean playWhenReady, int playbackState) {
                if (videoPlayer != null && videoPlayer.isPlaying() && playbackState == ExoPlayer.STATE_ENDED && videoEditedInfo != null) {
                    videoPlayer.seekTo(videoEditedInfo.startTime > 0 ? videoEditedInfo.startTime : 0);
                }
            }

            @Override
            public void onError(VideoPlayer player, Exception error) {
                FileLog.e(error);
            }

            @Override
            public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
            }

            @Override
            public void onRenderedFirstFrame() {
            }
        });
        releaseCameraXSession();
        videoPlayer.setTextureView(textureView);
        videoPlayer.preparePlayer(Uri.fromFile(output), "other");
        videoPlayer.play();
        videoPlayer.setMute(true);
        startProgressTimer();
        AnimatorSet animation = new AnimatorSet();
        animation.playTogether(
                ObjectAnimator.ofFloat(buttonsLayout, View.ALPHA, 0),
                ObjectAnimator.ofFloat(zoomSlider, InstantCameraZoomSlider.OPEN_ALPHA, 0),
                ObjectAnimator.ofInt(paint, AnimationProperties.PAINT_ALPHA, 0),
                ObjectAnimator.ofFloat(muteImageView, View.ALPHA, 1));
        animation.setDuration(180);
        animation.setInterpolator(new DecelerateInterpolator());
        animation.start();
    }

    private Bitmap firstFrameThumb;
    private volatile int surfaceIndex;

    public class CameraGLThread extends DispatchQueue {

        private final static int EGL_CONTEXT_CLIENT_VERSION = 0x3098;
        private final static int EGL_OPENGL_ES2_BIT = 4;
        private SurfaceTexture surfaceTexture;
        private EGL10 egl10;
        private EGLDisplay eglDisplay;
        private EGLContext eglContext;
        private EGLSurface eglSurface;
        private boolean initied;

        private Object currentSession;

        private final SurfaceTexture[] cameraSurface = new SurfaceTexture[2];

        private final int DO_RENDER_MESSAGE = 0;
        private final int DO_SHUTDOWN_MESSAGE = 1;
        private final int DO_REINIT_MESSAGE = 2;
        private final int DO_SETSESSION_MESSAGE = 3;
        private final int DO_FLIP = 4;
        private final int DO_SETORIENTATION_MESSAGE = 5;
        private final int DO_SET_CAMERAX_PREVIEW_SIZE = 6;

        private int drawProgram;
        private int vertexMatrixHandle;
        private int textureMatrixHandle;
        private int positionHandle;
        private int textureHandle;

        private boolean recording;

        private Integer cameraId = 0;

        private int surfaceWidth;
        private int surfaceHeight;

        private volatile boolean running = true;
        private final AtomicInteger pendingRenderMask = new AtomicInteger(0);
        private final int[] surfaceGeneration = new int[2];
        private final RoundVideoEncoder.FrameSnapshot frameSnapshotScratch = new RoundVideoEncoder.FrameSnapshot();

        public CameraGLThread(SurfaceTexture surface, int surfaceWidth, int surfaceHeight) {
            super("CameraGLThread", false);
            surfaceTexture = surface;

            this.surfaceWidth = surfaceWidth;
            this.surfaceHeight = surfaceHeight;
            start();
            postRunnable(() -> initied = initGL());
        }

        private void updateScale() {
            int width, height;
            if (previewSize[surfaceIndex] != null) {
                width = previewSize[surfaceIndex].getWidth();
                height = previewSize[surfaceIndex].getHeight();
            } else {
                return;
            }

            float scale = surfaceWidth / (float) Math.min(width, height);

            width *= scale;
            height *= scale;

            if (width == height) {
                scaleX = 1f;
                scaleY = 1f;
            } else if (width > height) {
                scaleX = 1.0f;
                scaleY = width / (float) surfaceHeight;
            } else {
                scaleX = height / (float) surfaceWidth;
                scaleY = 1.0f;
            }
            FileLog.d("InstantCamera camera scaleX = " + scaleX + " scaleY = " + scaleY);
        }

        private void updateTextureBuffer() {
            updateScale();

            float tX = 1.0f / scaleX / 2.0f;
            float tY = 1.0f / scaleY / 2.0f;
            float[] texData = {
                    0.5f - tX, 0.5f - tY,
                    0.5f + tX, 0.5f - tY,
                    0.5f - tX, 0.5f + tY,
                    0.5f + tX, 0.5f + tY
            };
            System.arraycopy(texData, 0, textureCoordsData, 0, 8);
            textureBuffer = ByteBuffer.allocateDirect(texData.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            textureBuffer.put(texData).position(0);
        }

        private void attachFrameListener(int index) {
            final SurfaceTexture surface = cameraSurface[index];
            final int generation = surfaceGeneration[index];
            surface.setOnFrameAvailableListener(st -> {
                if (!running || cameraSurface[index] != st || surfaceGeneration[index] != generation) {
                    return;
                }
                cameraTextureAvailable = true;
                requestRender(index == 0, index == 1);
            }, getHandler());
        }

        private void fillFrameSnapshot(RoundVideoEncoder.FrameSnapshot snapshot, int index, int cameraId) {
            SurfaceTexture surface = cameraSurface[index];
            snapshot.sourceTimestampNs = surface.getTimestamp();
            snapshot.arrivalTimeNs = System.nanoTime();
            snapshot.cameraId = cameraId;
            snapshot.surfaceIndex = index;
            snapshot.textureId = cameraTexture[index];
            surface.getTransformMatrix(snapshot.stMatrix);
            System.arraycopy(mMVPMatrix, 0, snapshot.mvpMatrix, 0, 16);
            System.arraycopy(textureCoordsData, 0, snapshot.textureCoords, 0, 8);
            Size size = previewSize[index];
            snapshot.previewWidth = size != null ? size.getWidth() : 0;
            snapshot.previewHeight = size != null ? size.getHeight() : 0;
        }

        private boolean initGL() {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("InstantCamera start init gl");
            }
            egl10 = (EGL10) EGLContext.getEGL();

            eglDisplay = egl10.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
            if (eglDisplay == EGL10.EGL_NO_DISPLAY) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("InstantCamera eglGetDisplay failed " + GLUtils.getEGLErrorString(egl10.eglGetError()));
                }
                finish();
                return false;
            }

            int[] version = new int[2];
            if (!egl10.eglInitialize(eglDisplay, version)) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("InstantCamera eglInitialize failed " + GLUtils.getEGLErrorString(egl10.eglGetError()));
                }
                finish();
                return false;
            }

            int[] configsCount = new int[1];
            EGLConfig[] configs = new EGLConfig[1];
            int[] configSpec = new int[]{
                    EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                    EGL10.EGL_RED_SIZE, 8,
                    EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_ALPHA_SIZE, 0,
                    EGL10.EGL_DEPTH_SIZE, 0,
                    EGL10.EGL_STENCIL_SIZE, 0,
                    EGL10.EGL_NONE
            };
            EGLConfig eglConfig;
            if (!egl10.eglChooseConfig(eglDisplay, configSpec, configs, 1, configsCount)) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("InstantCamera eglChooseConfig failed " + GLUtils.getEGLErrorString(egl10.eglGetError()));
                }
                finish();
                return false;
            } else if (configsCount[0] > 0) {
                eglConfig = configs[0];
            } else {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("InstantCamera eglConfig not initialized");
                }
                finish();
                return false;
            }

            int[] attrib_list = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE};
            eglContext = egl10.eglCreateContext(eglDisplay, eglConfig, EGL10.EGL_NO_CONTEXT, attrib_list);
            if (eglContext == null) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("InstantCamera eglCreateContext failed " + GLUtils.getEGLErrorString(egl10.eglGetError()));
                }
                finish();
                return false;
            }

            if (surfaceTexture != null) {
                eglSurface = egl10.eglCreateWindowSurface(eglDisplay, eglConfig, surfaceTexture, null);
            } else {
                finish();
                return false;
            }

            if (eglSurface == null || eglSurface == EGL10.EGL_NO_SURFACE) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("InstantCamera createWindowSurface failed " + GLUtils.getEGLErrorString(egl10.eglGetError()));
                }
                finish();
                return false;
            }
            if (!egl10.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("InstantCamera eglMakeCurrent failed " + GLUtils.getEGLErrorString(egl10.eglGetError()));
                }
                finish();
                return false;
            }

            float[] verticesData = {
                    -1.0f, -1.0f, 0,
                    1.0f, -1.0f, 0,
                    -1.0f, 1.0f, 0,
                    1.0f, 1.0f, 0
            };
            vertexBuffer = ByteBuffer.allocateDirect(verticesData.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            vertexBuffer.put(verticesData).position(0);

            updateTextureBuffer();

            android.opengl.Matrix.setIdentityM(mSTMatrix, 0);

            int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
            int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SCREEN_SHADER);
            if (vertexShader != 0 && fragmentShader != 0) {
                drawProgram = GLES20.glCreateProgram();
                GLES20.glAttachShader(drawProgram, vertexShader);
                GLES20.glAttachShader(drawProgram, fragmentShader);
                GLES20.glLinkProgram(drawProgram);
                int[] linkStatus = new int[1];
                GLES20.glGetProgramiv(drawProgram, GLES20.GL_LINK_STATUS, linkStatus, 0);
                if (linkStatus[0] == 0) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.e("InstantCamera failed link shader");
                    }
                    GLES20.glDeleteProgram(drawProgram);
                    drawProgram = 0;
                } else {
                    positionHandle = GLES20.glGetAttribLocation(drawProgram, "aPosition");
                    textureHandle = GLES20.glGetAttribLocation(drawProgram, "aTextureCoord");
                    vertexMatrixHandle = GLES20.glGetUniformLocation(drawProgram, "uMVPMatrix");
                    textureMatrixHandle = GLES20.glGetUniformLocation(drawProgram, "uSTMatrix");
                }
            } else {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("InstantCamera failed creating shader");
                }
                finish();
                return false;
            }

            android.opengl.Matrix.setIdentityM(mMVPMatrix, 0);

            GLES20.glGenTextures(2, cameraTexture, 0);
            for (int a = 0; a < 2; ++a) {
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture[a]);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

                cameraSurface[a] = new SurfaceTexture(cameraTexture[a]);
                attachFrameListener(a);
                if (ExteraConfig.getCameraType() != CameraType.CAMERA_X || a == 0 || bothCameras) {
                    createCamera(a, cameraSurface[a]);
                }
            }

            if (BuildVars.LOGS_ENABLED) {
                FileLog.e("InstantCamera gl initied");
            }

            return true;
        }

        public void reinitForNewCamera() {
            Handler handler = getHandler();
            if (handler != null) {
                sendMessage(handler.obtainMessage(DO_REINIT_MESSAGE), 0);
            }
        }

        public void finish() {
            surfaceGeneration[0]++;
            surfaceGeneration[1]++;
            pendingRenderMask.set(0);
            if (cameraSurface != null) {
                for (int a = 0; a < 2; ++a) {
                    if (cameraSurface[a] != null) {
                        cameraSurface[a].release();
                        cameraSurface[a] = null;
                    }
                }
            }
            cameraTextureAvailable = false;
            if (eglSurface != null && eglContext != null) {
                if (!eglContext.equals(egl10.eglGetCurrentContext()) || !eglSurface.equals(egl10.eglGetCurrentSurface(EGL10.EGL_DRAW))) {
                    egl10.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
                }
                if (cameraTexture != null && cameraTexture[0] != Integer.MIN_VALUE) {
                    GLES20.glDeleteTextures(1, cameraTexture, 0);
                    cameraTexture[0] = Integer.MIN_VALUE;
                }
                if (cameraTexture != null && cameraTexture[1] != Integer.MIN_VALUE) {
                    GLES20.glDeleteTextures(1, cameraTexture, 1);
                    cameraTexture[1] = Integer.MIN_VALUE;
                }
            }
            if (eglSurface != null) {
                egl10.eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
                egl10.eglDestroySurface(eglDisplay, eglSurface);
                eglSurface = null;
            }
            if (eglContext != null) {
                egl10.eglDestroyContext(eglDisplay, eglContext);
                eglContext = null;
            }
            if (eglDisplay != null) {
                egl10.eglTerminate(eglDisplay);
                eglDisplay = null;
            }
        }

        public void setCurrentSession(CameraSession session) {
            Handler handler = getHandler();
            if (handler != null) {
                sendMessage(handler.obtainMessage(DO_SETSESSION_MESSAGE, session), 0);
            }
        }

        public void setCurrentSession(Camera2Session session) {
            Handler handler = getHandler();
            if (handler != null) {
                sendMessage(handler.obtainMessage(DO_SETSESSION_MESSAGE, session), 0);
            }
        }

        public void setOrientation() {
            Handler handler = getHandler();
            if (handler != null) {
                sendMessage(handler.obtainMessage(DO_SETORIENTATION_MESSAGE), 0);
            }
        }

        public void setCameraXPreviewSize(int index, int width, int height) {
            if (index < 0 || index >= previewSize.length || width <= 0 || height <= 0) {
                return;
            }
            Handler handler = getHandler();
            if (handler != null) {
                sendMessage(handler.obtainMessage(DO_SET_CAMERAX_PREVIEW_SIZE, index, 0, new Size(width, height)), 0);
            }
        }

        public void flipSurfaces() {
            Handler handler = getHandler();
            if (handler != null) {
                sendMessage(handler.obtainMessage(DO_FLIP), 0);
            }
        }

        private void onDraw(Integer cameraId, boolean updateTexImage1, boolean updateTexImage2) {
            if (!initied) {
                return;
            }
            if (this.cameraId != null && !this.cameraId.equals(cameraId)) {
                return;
            }

            if (!eglContext.equals(egl10.eglGetCurrentContext()) || !eglSurface.equals(egl10.eglGetCurrentSurface(EGL10.EGL_DRAW))) {
                if (!egl10.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.e("eglMakeCurrent failed " + GLUtils.getEGLErrorString(egl10.eglGetError()));
                    }
                    return;
                }
            }
            if (updateTexImage1) {
                try {
                    cameraSurface[0].updateTexImage();
                } catch (Throwable e) {
                    FileLog.e(e);
                    return;
                }
            }
            if (updateTexImage2) {
                try {
                    cameraSurface[1].updateTexImage();
                } catch (Throwable e) {
                    FileLog.e(e);
                    return;
                }
            }
            boolean currentSurfaceUpdated = surfaceIndex == 0 ? updateTexImage1 : updateTexImage2;
            if (ExteraConfig.getCameraType() == CameraType.CAMERA_X && bothCameras && !currentSurfaceUpdated) {
                return;
            }

            boolean shouldRenderFirstFrameThumb = false;
            if (!recording) {
                if (videoEncoder == null) {
                    sentMedia = false;
                    videoConvertFirstWrite = true;
                    encoderFinishRequested = false;
                    encoderSend = 0;
                    encoderSendOptions = null;
                    keyframeThumbs.clear();
                    if (generateKeyframeThumbsQueue != null) {
                        generateKeyframeThumbsQueue.cleanupQueue();
                        generateKeyframeThumbsQueue.recycle();
                    }
                    generateKeyframeThumbsQueue = new DispatchQueue("keyframes_thumb_queue");
                    videoEncoder = new RoundVideoEncoder(new EncoderRenderer(), encoderCallback, isSecretChat);
                    encoderFrameRate = resolveEncoderFrameRate();
                }
                if (videoEncoder.isStarted()) {
                    if (!cameraReady) {
                        cameraReady = true;
                        AndroidUtilities.runOnUIThread(() -> textureOverlayView.animate().setDuration(120).alpha(0.0f).setInterpolator(new DecelerateInterpolator()).start());
                    }
                } else {
                    shouldRenderFirstFrameThumb = true;
                }
                AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.stopAllHeavyOperations, 512));
                encoderFile = cameraFile;
                videoEncoder.startRecording(cameraFile, EGL14.eglGetCurrentContext(), encoderFrameRate);
                recording = true;
                int orientation;
                if (ExteraConfig.getCameraType() != CameraType.CAMERA_X) {
                    if (currentSession instanceof CameraSession) {
                        orientation = ((CameraSession) currentSession).getCurrentOrientation();
                    } else if (currentSession instanceof Camera2Session) {
                        orientation = ((Camera2Session) currentSession).getCurrentOrientation();
                    } else {
                        orientation = 0;
                    }
                } else {
                    CameraXSession session = cameraXSession;
                    orientation = session != null ? session.getDisplayOrientation() : 0;
                }
                if (orientation == 90 || orientation == 270) {
                    float temp = scaleX;
                    scaleX = scaleY;
                    scaleY = temp;
                }
                recording = true;
                updateFlash();
            }

            if (videoEncoder != null && (surfaceIndex == 0 && updateTexImage1 || surfaceIndex == 1 && updateTexImage2)) {
                fillFrameSnapshot(frameSnapshotScratch, surfaceIndex, bothCameras ? surfaceIndex : cameraId);
                videoEncoder.frameAvailable(frameSnapshotScratch);
            }

            cameraSurface[surfaceIndex].getTransformMatrix(mSTMatrix);

            GLES20.glUseProgram(drawProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture[surfaceIndex]);

            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer);
            GLES20.glEnableVertexAttribArray(positionHandle);

            GLES20.glVertexAttribPointer(textureHandle, 2, GLES20.GL_FLOAT, false, 8, textureBuffer);
            GLES20.glEnableVertexAttribArray(textureHandle);

            GLES20.glUniformMatrix4fv(textureMatrixHandle, 1, false, mSTMatrix, 0);
            GLES20.glUniformMatrix4fv(vertexMatrixHandle, 1, false, mMVPMatrix, 0);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            GLES20.glDisableVertexAttribArray(positionHandle);
            GLES20.glDisableVertexAttribArray(textureHandle);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
            GLES20.glUseProgram(0);

            egl10.eglSwapBuffers(eglDisplay, eglSurface);

            if (shouldRenderFirstFrameThumb) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (textureView == null) {
                        return;
                    }
                    if (firstFrameThumb != null) {
                        firstFrameThumb.recycle();
                        firstFrameThumb = null;
                    }
                    firstFrameThumb = textureView.getBitmap();
                });
            }
        }

        @Override
        public void handleMessage(Message inputMessage) {
            int what = inputMessage.what;

            switch (what) {
                case DO_RENDER_MESSAGE: {
                    int mask = pendingRenderMask.getAndSet(0);
                    if (mask != 0) {
                        onDraw(inputMessage.arg1, (mask & 1) != 0, (mask & 2) != 0);
                    }
                    break;
                }
                case DO_SHUTDOWN_MESSAGE: {
                    synchronized (this) {
                        finish();
                    }
                    if (recording) {
                        Object obj = inputMessage.obj;
                        if ((!(obj instanceof SendOptions) || ((SendOptions) obj).ttl != -2) && videoEncoder != null) {
                            requestStopRecording(inputMessage.arg1, obj instanceof SendOptions ? (SendOptions) obj : null);
                        }
                    }
                    Looper looper = Looper.myLooper();
                    if (looper != null) {
                        looper.quitSafely();
                    }
                    break;
                }
                case DO_REINIT_MESSAGE: {
                    if (!egl10.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("InstantCamera eglMakeCurrent failed " + GLUtils.getEGLErrorString(egl10.eglGetError()));
                        }
                        return;
                    }

                    surfaceGeneration[0]++;
                    Handler handler = getHandler();
                    if (handler != null) {
                        handler.removeMessages(DO_RENDER_MESSAGE);
                    }
                    int pendingMask = pendingRenderMask.getAndSet(0);

                    if (cameraSurface[0] != null) {
                        cameraSurface[0].getTransformMatrix(moldSTMatrix);
                        cameraSurface[0].setOnFrameAvailableListener(null);
                        cameraSurface[0].release();
                        oldCameraTexture[0] = cameraTexture[0];
                        cameraTextureAlpha = 0.0f;
                        cameraTexture[0] = 0;
                        oldTextureTextureBuffer = textureBuffer.duplicate();
                        oldTexturePreviewSize = previewSize[0];
                    }
                    cameraId++;
                    cameraReady = false;

                    GLES20.glGenTextures(1, cameraTexture, 0);
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture[0]);
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

                    cameraSurface[0] = new SurfaceTexture(cameraTexture[0]);
                    attachFrameListener(0);
                    createCamera(0, cameraSurface[0]);

                    updateTextureBuffer();
                    if ((pendingMask & 2) != 0 && cameraSurface[1] != null) {
                        requestRender(false, true);
                    }
                    break;
                }
                case DO_SETSESSION_MESSAGE: {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("InstantCamera set gl renderer session");
                    }
                    Object newSession = inputMessage.obj;
                    if (currentSession == newSession) {
                        int rotationAngle;
                        if (currentSession instanceof CameraSession) {
                            rotationAngle = ((CameraSession) currentSession).getWorldAngle();
                        } else if (currentSession instanceof Camera2Session) {
                            rotationAngle = ((Camera2Session) currentSession).getWorldAngle();
                        } else {
                            rotationAngle = 0;
                        }
                        android.opengl.Matrix.setIdentityM(mMVPMatrix, 0);
                        if (rotationAngle != 0) {
                            android.opengl.Matrix.rotateM(mMVPMatrix, 0, rotationAngle, 0, 0, 1);
                        }
                    } else {
                        currentSession = newSession;
                    }
                    break;
                }
                case DO_FLIP: {
                    surfaceIndex = 1 - surfaceIndex;
                    updateTextureBuffer();
                    requestRender(true, true);
                    break;
                }
                case DO_SETORIENTATION_MESSAGE: {
                    CameraXSession session = cameraXSession;
                    int orientation = session != null ? session.getDisplayOrientation() : 0;
                    android.opengl.Matrix.setIdentityM(mMVPMatrix, 0);
                    if (orientation != 0) {
                        android.opengl.Matrix.rotateM(mMVPMatrix, 0, orientation, 0, 0, 1);
                    }
                    break;
                }
                case DO_SET_CAMERAX_PREVIEW_SIZE: {
                    int index = inputMessage.arg1;
                    previewSize[index] = (Size) inputMessage.obj;
                    if (index == surfaceIndex) {
                        updateTextureBuffer();
                    }
                    break;
                }
            }
        }

        public void shutdown(int send, boolean notify, int scheduleDate, int scheduleRepeatPeriod, int ttl, long effectId) {
            Handler handler = getHandler();
            if (handler != null) {
                synchronized (this) {
                    running = false;
                    for (SurfaceTexture surface : cameraSurface) {
                        if (surface != null) {
                            surface.setOnFrameAvailableListener(null);
                        }
                    }
                    handler.removeMessages(DO_RENDER_MESSAGE);
                    pendingRenderMask.set(0);
                    sendMessage(handler.obtainMessage(DO_SHUTDOWN_MESSAGE, send, 0, new SendOptions(notify, scheduleDate, scheduleRepeatPeriod, ttl, effectId, 0)), 0);
                }
            }
        }

        public void requestRender(boolean updateTexImage1, boolean updateTexImage2) {
            int mask = (updateTexImage1 ? 1 : 0) | (updateTexImage2 ? 2 : 0);
            if (mask == 0) {
                return;
            }
            Handler handler = getHandler();
            if (handler == null) {
                return;
            }
            synchronized (this) {
                if (!running) {
                    return;
                }
                int previous;
                do {
                    previous = pendingRenderMask.get();
                } while (!pendingRenderMask.compareAndSet(previous, previous | mask));
                if (previous == 0) {
                    sendMessage(handler.obtainMessage(DO_RENDER_MESSAGE, cameraId, 0), 0);
                }
            }
        }
    }

    public static class SendOptions {
        boolean notify;
        int scheduleDate;
        int scheduleRepeatPeriod;
        int ttl;
        long effectId;
        long stars;

        public SendOptions(boolean notify, int scheduleDate, int scheduleRepeatPeriod, int ttl, long effectId, long stars) {
            this.notify = notify;
            this.scheduleDate = scheduleDate;
            this.scheduleRepeatPeriod = scheduleRepeatPeriod;
            this.ttl = ttl;
            this.effectId = effectId;
            this.stars = stars;
        }
    }

    public static class AudioBufferInfo {
        public final static int MAX_SAMPLES = 10;
        public ByteBuffer[] buffer = new ByteBuffer[MAX_SAMPLES];
        public long[] offset = new long[MAX_SAMPLES];
        public int[] read = new int[MAX_SAMPLES];
        public int results;
        public int lastWroteBuffer;
        public boolean last;

        public AudioBufferInfo() {
            for (int i = 0; i < MAX_SAMPLES; i++) {
                buffer[i] = ByteBuffer.allocateDirect(2048);
                buffer[i].order(ByteOrder.nativeOrder());
            }
        }
    }

    private class GenerateKeyframeThumbTask implements Runnable {
        private final AtomicBoolean inFlight;

        public GenerateKeyframeThumbTask(AtomicBoolean inFlight) {
            this.inFlight = inFlight;
        }

        @Override
        public void run() {
            try {
                final TextureView textureView = InstantCameraView.this.textureView;
                if (textureView != null) {
                    try {
                        final Bitmap bitmap = textureView.getBitmap(dp(56), dp(56));
                        AndroidUtilities.runOnUIThread(() -> {
                            if ((bitmap == null || bitmap.getPixel(0, 0) == 0) && keyframeThumbs.size() > 1) {
                                keyframeThumbs.add(keyframeThumbs.get(keyframeThumbs.size() - 1));
                            } else {
                                keyframeThumbs.add(bitmap);
                            }
                        });
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
            } finally {
                inFlight.set(false);
            }
        }
    }

    private class EncoderRenderer implements RoundVideoEncoder.Renderer {
        private final FloatBuffer frameTextureBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        private InstantCameraVideoEncoderOverlayHelper overlayHelper;
        private int videoWidth;
        private int videoHeight;
        private boolean blendEnabled;

        private int drawProgram;
        private int vertexMatrixHandle;
        private int textureMatrixHandle;
        private int positionHandle;
        private int textureHandle;
        private int resolutionHandle;
        private int previewSizeHandle;
        private int texelSizeHandle;
        private int alphaHandle;

        private boolean firstThumbPending = true;
        private long thumbActiveTimeNs;
        private long nextThumbActiveTimeNs;
        private final AtomicBoolean thumbTaskInFlight = new AtomicBoolean();

        @Override
        public void onEncoderSurfaceCreated(int width, int height) {
            videoWidth = width;
            videoHeight = height;
            blendEnabled = false;
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            if (overlayHelper != null) {
                overlayHelper.destroy();
            }
            overlayHelper = new InstantCameraVideoEncoderOverlayHelper(videoWidth, videoHeight);

            int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
            String fragmentShaderSource;
            if (ExteraConfig.getCameraType() == CameraType.CAMERA_X) {
                fragmentShaderSource = "#extension GL_OES_EGL_image_external : require\n" +
                        "precision highp float;\n" +
                        "varying vec2 vTextureCoord;\n" +
                        "uniform float alpha;\n" +
                        "uniform samplerExternalOES sTexture;\n" +
                        "void main() {\n" +
                        "   vec4 color = texture2D(sTexture, vTextureCoord);\n" +
                        "   gl_FragColor = vec4(color.rgb * alpha, alpha);\n" +
                        "}\n";
            } else {
                fragmentShaderSource = createFragmentShaderV2(previewSize[0]);
            }
            int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderSource);
            if (vertexShader == 0 || fragmentShader == 0) {
                return;
            }
            drawProgram = GLES20.glCreateProgram();
            GLES20.glAttachShader(drawProgram, vertexShader);
            GLES20.glAttachShader(drawProgram, fragmentShader);
            GLES20.glLinkProgram(drawProgram);
            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(drawProgram, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] == 0) {
                GLES20.glDeleteProgram(drawProgram);
                drawProgram = 0;
                return;
            }
            positionHandle = GLES20.glGetAttribLocation(drawProgram, "aPosition");
            textureHandle = GLES20.glGetAttribLocation(drawProgram, "aTextureCoord");
            previewSizeHandle = GLES20.glGetUniformLocation(drawProgram, "preview");
            resolutionHandle = GLES20.glGetUniformLocation(drawProgram, "resolution");
            alphaHandle = GLES20.glGetUniformLocation(drawProgram, "alpha");
            vertexMatrixHandle = GLES20.glGetUniformLocation(drawProgram, "uMVPMatrix");
            textureMatrixHandle = GLES20.glGetUniformLocation(drawProgram, "uSTMatrix");
            texelSizeHandle = GLES20.glGetUniformLocation(drawProgram, "texelSize");
        }

        @Override
        public boolean onDrawEncoderFrame(long frameDeltaNs, RoundVideoEncoder.FrameSnapshot frame) {
            if (!cameraTextureAvailable || drawProgram == 0) {
                return false;
            }
            FloatBuffer vertices = vertexBuffer;
            FloatBuffer oldTextureBuffer = oldTextureTextureBuffer;
            if (vertices == null) {
                FileLog.d("InstantCamera encoder skip frame, no vertex buffer");
                return false;
            }
            frameTextureBuffer.clear();
            frameTextureBuffer.put(frame.textureCoords);
            frameTextureBuffer.position(0);

            if (overlayHelper != null) {
                overlayHelper.bind();
            }

            GLES20.glUseProgram(drawProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);

            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 12, vertices);
            GLES20.glEnableVertexAttribArray(positionHandle);
            GLES20.glVertexAttribPointer(textureHandle, 2, GLES20.GL_FLOAT, false, 8, frameTextureBuffer);
            GLES20.glEnableVertexAttribArray(textureHandle);
            GLES20.glUniformMatrix4fv(vertexMatrixHandle, 1, false, frame.mvpMatrix, 0);
            GLES20.glUniform2f(resolutionHandle, videoWidth, videoHeight);

            if (oldCameraTexture[0] != 0 && oldTextureBuffer != null && !bothCameras) {
                if (!blendEnabled) {
                    GLES20.glEnable(GLES20.GL_BLEND);
                    blendEnabled = true;
                }
                if (oldTexturePreviewSize != null) {
                    GLES20.glUniform2f(previewSizeHandle, oldTexturePreviewSize.getWidth(), oldTexturePreviewSize.getHeight());
                }
                GLES20.glVertexAttribPointer(textureHandle, 2, GLES20.GL_FLOAT, false, 8, oldTextureBuffer);
                GLES20.glUniformMatrix4fv(textureMatrixHandle, 1, false, moldSTMatrix, 0);
                GLES20.glUniform1f(alphaHandle, 1.0f);
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oldCameraTexture[0]);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            }

            if (frame.previewWidth > 0 && frame.previewHeight > 0) {
                GLES20.glUniform2f(previewSizeHandle, frame.previewWidth, frame.previewHeight);
                GLES20.glUniform2f(texelSizeHandle, 1.0f / frame.previewWidth / 2.0f, 1.0f / frame.previewHeight / 2.0f);
            }

            if (frame.textureId != Integer.MIN_VALUE) {
                GLES20.glUniformMatrix4fv(textureMatrixHandle, 1, false, frame.stMatrix, 0);
                GLES20.glUniform1f(alphaHandle, cameraTextureAlpha);
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, frame.textureId);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            }

            GLES20.glDisableVertexAttribArray(positionHandle);
            GLES20.glDisableVertexAttribArray(textureHandle);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
            GLES20.glUseProgram(0);

            if (overlayHelper != null) {
                overlayHelper.render(frameDeltaNs);
                if (blendEnabled) {
                    GLES20.glEnable(GLES20.GL_BLEND);
                }
            }

            thumbActiveTimeNs += frameDeltaNs;
            maybeScheduleKeyframeThumb();

            if (oldCameraTexture[0] != 0 && cameraTextureAlpha < 1.0f && !bothCameras) {
                cameraTextureAlpha += frameDeltaNs / 200000000.0f;
                if (cameraTextureAlpha > 1) {
                    GLES20.glDisable(GLES20.GL_BLEND);
                    blendEnabled = false;
                    cameraTextureAlpha = 1;
                    GLES20.glDeleteTextures(1, oldCameraTexture, 0);
                    oldCameraTexture[0] = 0;
                    if (!cameraReady) {
                        cameraReady = true;
                        AndroidUtilities.runOnUIThread(() -> textureOverlayView.animate().setDuration(120).alpha(0.0f).setInterpolator(new DecelerateInterpolator()).start());
                    }
                }
            } else if (!cameraReady) {
                cameraReady = true;
                AndroidUtilities.runOnUIThread(() -> textureOverlayView.animate().setDuration(120).alpha(0.0f).setInterpolator(new DecelerateInterpolator()).start());
            }
            return true;
        }

        @Override
        public void onEncoderSurfaceDestroyed() {
            if (overlayHelper != null) {
                overlayHelper.destroy();
                overlayHelper = null;
            }
            if (drawProgram != 0) {
                GLES20.glDeleteProgram(drawProgram);
                drawProgram = 0;
            }
        }

        private void maybeScheduleKeyframeThumb() {
            if (generateKeyframeThumbsQueue == null || SharedConfig.getDevicePerformanceClass() != SharedConfig.PERFORMANCE_CLASS_HIGH) {
                return;
            }
            if ((firstThumbPending || thumbActiveTimeNs >= nextThumbActiveTimeNs) && thumbTaskInFlight.compareAndSet(false, true)) {
                firstThumbPending = false;
                nextThumbActiveTimeNs = thumbActiveTimeNs + 1100000000L;
                generateKeyframeThumbsQueue.postRunnable(new GenerateKeyframeThumbTask(thumbTaskInFlight));
            }
        }
    }

    private String createFragmentShaderV2(Size previewSize) {
        if (SharedConfig.deviceIsLow() || !allowBigSizeCamera() || previewSize != null && Math.max(previewSize.getHeight(), previewSize.getWidth()) * 0.7f < MessagesController.getInstance(currentAccount).roundVideoSize) {
            return "#extension GL_OES_EGL_image_external : require\n" +
                    "precision highp float;\n" +
                    "varying vec2 vTextureCoord;\n" +
                    "uniform float alpha;\n" +
                    "uniform vec2 preview;\n" +
                    "uniform vec2 resolution;\n" +
                    "uniform samplerExternalOES sTexture;\n" +
                    "void main() {\n" +
                    "   vec4 textColor = texture2D(sTexture, vTextureCoord);\n" +
                    "   gl_FragColor = vec4(textColor.rgb * alpha, alpha);\n" +
                    "}\n";
        }
        return "#extension GL_OES_EGL_image_external : require\n" +
                "precision highp float;\n" +
                "varying vec2 vTextureCoord;\n" + //uv
                "uniform vec2 resolution;\n" + //rendering texture
                "uniform vec2 preview;\n" + //original texture size
                "uniform float alpha;\n" +

                "uniform samplerExternalOES sTexture;\n" +
                "void main() {\n" +
                "   vec2 c_textureSize = preview;\n" +
                "   vec2 c_onePixel = (1.0 / c_textureSize);\n" +
                "   vec2 uv = vTextureCoord;\n" +
                "   vec2 pixel = uv * c_textureSize + 0.5;\n" +
                "   vec2 frac = fract(pixel);\n" +
                "   pixel = (floor(pixel) / c_textureSize) - vec2(c_onePixel);\n" +
                "   vec4 tl = texture2D(sTexture, pixel + vec2(0.0         , 0.0));\n" +
                "   vec4 tr = texture2D(sTexture, pixel + vec2(c_onePixel.x, 0.0));\n" +
                "   vec4 bl = texture2D(sTexture, pixel + vec2(0.0         , c_onePixel.y));\n" +
                "   vec4 br = texture2D(sTexture, pixel + vec2(c_onePixel.x, c_onePixel.y));\n" +
                "   vec4 x1 = mix(tl, tr, frac.x);\n" +
                "   vec4 x2 = mix(bl, br, frac.x);\n" +
                "   gl_FragColor = mix(x1, x2, frac.y) * alpha;\n" +
                "}\n";
    }

    public class InstantViewCameraContainer extends InstantCameraViewBase.InstantViewCameraContainer {

        ImageReceiver imageReceiver;
        float imageProgress;

        public InstantViewCameraContainer(Context context) {
            super(context);
            InstantCameraView.this.setWillNotDraw(false);
        }

        @Override
        public void setImageReceiver(ImageReceiver imageReceiver) {
            if (this.imageReceiver == null) {
                imageProgress = 0;
            }
            this.imageReceiver = imageReceiver;
            invalidate();
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            if (imageProgress != 1f) {
                imageProgress += 16 / 250.0f;
                if (imageProgress > 1f) {
                    imageProgress = 1f;
                }
                invalidate();
            }
            if (imageReceiver != null) {
                canvas.save();
                if (imageReceiver.getImageWidth() != textureViewSize) {
                    float s = textureViewSize / imageReceiver.getImageWidth();
                    canvas.scale(s, s);
                }
                canvas.translate(-imageReceiver.getImageX(), -imageReceiver.getImageY());
                float oldAlpha = imageReceiver.getAlpha();
                imageReceiver.setAlpha(imageProgress);
                imageReceiver.draw(canvas);
                imageReceiver.setAlpha(oldAlpha);
                canvas.restore();
            }
        }
    }


    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN && ev.getY() > getMeasuredHeight() - getPaddingBottom()) {
            return false;
        }

        if (ev.getAction() == MotionEvent.ACTION_DOWN && delegate != null) {
            if (videoPlayer != null) {
                boolean mute = !videoPlayer.isMuted();
                videoPlayer.setMute(mute);
                if (muteAnimation != null) {
                    muteAnimation.cancel();
                }
                muteAnimation = new AnimatorSet();
                muteAnimation.playTogether(
                        ObjectAnimator.ofFloat(muteImageView, View.ALPHA, mute ? 1.0f : 0.0f),
                        ObjectAnimator.ofFloat(muteImageView, View.SCALE_X, mute ? 1.0f : 0.5f),
                        ObjectAnimator.ofFloat(muteImageView, View.SCALE_Y, mute ? 1.0f : 0.5f));
                muteAnimation.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (animation.equals(muteAnimation)) {
                            muteAnimation = null;
                        }
                    }
                });
                muteAnimation.setDuration(180);
                muteAnimation.setInterpolator(new DecelerateInterpolator());
                muteAnimation.start();
            } else {
                //baseFragment.checkRecordLocked(false);
            }
        }

        if (ExteraConfig.getCameraType() == CameraType.CAMERA_X) {
            getParent().requestDisallowInterceptTouchEvent(true);
            scaleGestureDetector.onTouchEvent(ev);
            return true;
        }

        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN || ev.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
            if (maybePinchToZoomTouchMode && !isInPinchToZoomTouchMode && ev.getPointerCount() == 2 && finishZoomTransition == null && recording) {
                pinchStartDistance = (float) Math.hypot(ev.getX(1) - ev.getX(0), ev.getY(1) - ev.getY(0));
                pointerId1 = ev.getPointerId(0);
                pointerId2 = ev.getPointerId(1);
                isInPinchToZoomTouchMode = true;
                zoomSlider.beginPinchZoomGesture();
                zoomWas = false;
                initialCameraZoom = cameraZoom;
            }
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                AndroidUtilities.rectTmp.set(cameraContainer.getX(), cameraContainer.getY(), cameraContainer.getX() + cameraContainer.getMeasuredWidth(), cameraContainer.getY() + cameraContainer.getMeasuredHeight());
                maybePinchToZoomTouchMode = AndroidUtilities.rectTmp.contains(ev.getX(), ev.getY());
            }
            return true;
        } else if (ev.getActionMasked() == MotionEvent.ACTION_MOVE && isInPinchToZoomTouchMode) {
            int index1 = -1;
            int index2 = -1;
            for (int i = 0; i < ev.getPointerCount(); i++) {
                if (pointerId1 == ev.getPointerId(i)) {
                    index1 = i;
                }
                if (pointerId2 == ev.getPointerId(i)) {
                    index2 = i;
                }
            }
            if (index1 == -1 || index2 == -1) {
                isInPinchToZoomTouchMode = false;

                finishZoom();
                return false;
            }
            final float distance = (float) Math.hypot(ev.getX(index2) - ev.getX(index1), ev.getY(index2) - ev.getY(index1));
            float zoomPerPixel = 0.002f;
            if (useCamera2 && camera2SessionCurrent != null) {
                zoomPerPixel *= camera2SessionCurrent.getMaxZoom() - camera2SessionCurrent.getMinZoom();
            }
            cameraZoom = initialCameraZoom + (distance - pinchStartDistance) * zoomPerPixel;
            if (useCamera2) {
                if (camera2SessionCurrent != null) {
                    cameraZoom = Utilities.clamp(cameraZoom, camera2SessionCurrent.getMaxZoom(), camera2SessionCurrent.getMinZoom());
                    camera2SessionCurrent.setZoom(cameraZoom);
                }
            } else {
                cameraZoom = Utilities.clamp(cameraZoom, 1f, 0f);
                if (cameraSession != null) {
                    cameraSession.setZoom(cameraZoom);
                }
            }
            zoomSlider.syncZoom(cameraZoom);
            zoomWas = true;
        } else if ((ev.getActionMasked() == MotionEvent.ACTION_UP || (ev.getActionMasked() == MotionEvent.ACTION_POINTER_UP && checkPointerIds(ev)) || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) && isInPinchToZoomTouchMode) {
            isInPinchToZoomTouchMode = false;
            if (zoomWas) {
                finishZoom();
            } else {
                zoomSlider.endPinchZoomGesture();
            }
        }
        return true;
    }

    ValueAnimator finishZoomTransition;
    private ValueAnimator zoomAnimator;

    private void cancelZoomAnimations() {
        if (zoomAnimator != null) {
            zoomAnimator.cancel();
            zoomAnimator = null;
        }
        if (finishZoomTransition != null) {
            finishZoomTransition.cancel();
            finishZoomTransition = null;
        }
    }

    public void finishZoom() {
        zoomSlider.endPinchZoomGesture();
        if (finishZoomTransition != null || ExteraConfig.getStaticZoom()) {
            return;
        }

        if (ExteraConfig.getCameraType() == CameraType.CAMERA_X) {
            if (cameraXSession == null) {
                return;
            }
            float zoom = zoomSlider.getZoom();
            float resetZoom = Utilities.clamp(zoomSlider.getCameraXResetZoom(),
                    zoomSlider.getMaximumZoom(), zoomSlider.getMinimumZoom());
            float oneZoom = zoomSlider.getDisplayOneZoom();
            if ((zoom >= oneZoom || resetZoom < oneZoom || zoomSlider.getMinimumZoom() >= oneZoom)
                    && Math.abs(zoom - resetZoom) > 0.001f) {
                finishZoomTransition = ValueAnimator.ofFloat(zoom, resetZoom);
                finishZoomTransition.addUpdateListener(animation -> {
                    if (cameraXSession != null) {
                        zoomSlider.setCameraXZoomRatio((float) animation.getAnimatedValue());
                        cameraZoom = cameraXSession.getLinearZoom();
                    }
                });
                finishZoomTransition.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        finishZoomTransition = null;
                    }
                });
                finishZoomTransition.setDuration(350);
                finishZoomTransition.setInterpolator(CubicBezierInterpolator.DEFAULT);
                finishZoomTransition.start();
            }
            return;
        }

        if (cameraZoom > 0f) {
            finishZoomTransition = ValueAnimator.ofFloat(cameraZoom, useCamera2 ? 1f : 0f);
            finishZoomTransition.addUpdateListener(valueAnimator -> {
                cameraZoom = (float) valueAnimator.getAnimatedValue();
                if (useCamera2) {
                    if (camera2SessionCurrent != null) {
                        camera2SessionCurrent.setZoom(cameraZoom);
                    }
                } else {
                    if (cameraSession != null) {
                        cameraSession.setZoom(cameraZoom);
                    }
                }
                zoomSlider.syncZoom(cameraZoom);
            });
            finishZoomTransition.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (finishZoomTransition != null) {
                        finishZoomTransition = null;
                    }
                }
            });

            finishZoomTransition.setDuration(350);
            finishZoomTransition.setInterpolator(CubicBezierInterpolator.DEFAULT);
            finishZoomTransition.start();
        }
    }

    private void adjustZoom(boolean zoomIn) {
        if (!isCameraReady()) {
            return;
        }
        if (zoomAnimator != null && zoomAnimator.isRunning()) {
            return;
        }
        cancelZoomAnimations();
        zoomSlider.beginSteppedZoomGesture();

        if (ExteraConfig.getCameraType() == CameraType.CAMERA_X) {
            float zoom = zoomSlider.getZoom();
            float minZoom = zoomSlider.getMinimumZoom();
            float maxZoom = zoomSlider.getMaximumZoom();
            float oneZoom = Utilities.clamp(zoomSlider.getDisplayOneZoom(), maxZoom, minZoom);
            double range = maxZoom / oneZoom;
            int steps = (int) Math.round(Math.log(range) / Math.log(1.75));
            if (steps < 1) {
                steps = 1;
            }
            float step = (float) Math.pow(range, 1.0 / steps);
            float targetZoom;
            if (zoomIn) {
                targetZoom = zoom < oneZoom ? oneZoom : zoom * step;
            } else if (zoom > oneZoom) {
                targetZoom = zoom / step;
                if (targetZoom < oneZoom) {
                    targetZoom = oneZoom;
                }
            } else {
                targetZoom = minZoom;
            }
            targetZoom = Utilities.clamp(targetZoom, maxZoom, minZoom);
            zoomAnimator = ValueAnimator.ofFloat(zoom, targetZoom);
            zoomAnimator.setDuration(175);
            zoomAnimator.setInterpolator(CubicBezierInterpolator.DEFAULT);
            zoomAnimator.addUpdateListener(animation -> {
                if (cameraXSession != null) {
                    zoomSlider.setCameraXZoomRatio((float) animation.getAnimatedValue());
                    cameraZoom = cameraXSession.getLinearZoom();
                }
            });
            zoomAnimator.start();
            return;
        }

        float targetZoom;
        if (useCamera2) {
            if (camera2SessionCurrent == null) {
                return;
            }
            float minZoom = camera2SessionCurrent.getMinZoom();
            float maxZoom = camera2SessionCurrent.getMaxZoom();
            int steps = (int) Math.round(Math.log(maxZoom) / Math.log(1.75));
            if (steps < 1) {
                steps = 1;
            }
            float step = (float) Math.pow(maxZoom, 1.0 / steps);
            if (zoomIn) {
                targetZoom = cameraZoom * step;
            } else {
                targetZoom = cameraZoom / step;
                if (targetZoom < 1.0f) {
                    targetZoom = 1.0f;
                }
            }
            targetZoom = Utilities.clamp(targetZoom, maxZoom, minZoom);
        } else {
            targetZoom = Utilities.clamp(cameraZoom + (zoomIn ? 1 : -1) * 0.125f, 1.0f, 0.0f);
        }
        if (cameraZoom == targetZoom) {
            return;
        }
        zoomAnimator = ValueAnimator.ofFloat(cameraZoom, targetZoom);
        zoomAnimator.setDuration(175);
        zoomAnimator.setInterpolator(CubicBezierInterpolator.DEFAULT);
        zoomAnimator.addUpdateListener(animation -> {
            cameraZoom = (float) animation.getAnimatedValue();
            if (useCamera2) {
                if (camera2SessionCurrent != null) {
                    camera2SessionCurrent.setZoom(cameraZoom);
                }
            } else {
                if (cameraSession != null) {
                    cameraSession.setZoom(cameraZoom);
                }
            }
            zoomSlider.syncZoom(cameraZoom);
        });
        zoomAnimator.start();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            adjustZoom(true);
            return true;
        } else if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            adjustZoom(false);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    public interface Delegate {

        View getFragmentView();
        void sendMedia(MediaController.PhotoEntry entry, VideoEditedInfo videoEditedInfo, boolean notify, int scheduleDate, int scheduleRepeatPeriod, boolean b1, long stars);
        Activity getParentActivity();
        int getClassGuid();
        long getDialogId();

        default boolean isSecretChat() {
            return false;
        }

        default boolean isInScheduleMode() {
            return false;
        }
    }
}
