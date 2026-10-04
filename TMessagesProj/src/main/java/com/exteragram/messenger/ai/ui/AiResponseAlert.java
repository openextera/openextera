package com.exteragram.messenger.ai.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.URLSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.math.MathUtils;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.exteragram.messenger.ai.AiConfig;
import com.exteragram.messenger.ai.AiController;
import com.exteragram.messenger.ai.data.Message;
import com.exteragram.messenger.ai.data.Role;
import com.exteragram.messenger.ai.data.Service;
import com.exteragram.messenger.ai.network.Client;
import com.exteragram.messenger.ai.network.GenerationCallback;
import com.exteragram.messenger.ai.network.backend.OnDeviceAvailability;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LinkifyPort;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.RichMessageLayout;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.utils.DrawableUtils;
import org.telegram.tgnet.tl.TL_iv;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.DialogCell;
import org.telegram.ui.Cells.TextSelectionHelper;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ColoredImageSpan;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EllipsizeSpanAnimator;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LinkPath;
import org.telegram.ui.Components.LinkSpanDrawable;
import org.telegram.ui.Components.LoadingDrawable;
import org.telegram.ui.Components.MarkdownParser;
import org.telegram.ui.Components.QuoteSpan;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.TypingDotsDrawable;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;
import org.telegram.ui.iv.RichMessageConvert;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;

public abstract class AiResponseAlert extends BottomSheet implements NotificationCenter.NotificationCenterDelegate {

    private static final int OPTION_COPY = 1;
    private static final int OPTION_RETRY = 2;
    private static final int OPTION_EDIT = 3;
    private static final int OPTION_ASK_MORE = 4;

    private static final int CONTEXT_MESSAGES_LIMIT = 6;

    private final Client client;
    private final RecyclerListView listView;
    private final PaddedAdapter adapter;
    private final HeaderView headerView;
    private final ButtonWithCounterView mainButton;
    private final LoadingTextView loadingTextView;
    private final ThinkingDotsView thinkingDotsView;
    private final TextView promptTextView;
    private final RichMessageLayout.PreviewView previewView;
    private final LinearLayout contextView;
    private final View contextDivider;
    private final ResponseContainer responseContainer;
    private final AnimatedFloat sheetTopAnimated;
    private final TextSelectionHelper.ArticleTextSelectionHelper textSelectionHelper;
    private final TextSelectionHelper.TextSelectionOverlay textSelectionOverlay;
    EllipsizeSpanAnimator ellipsizeSpanAnimator;

    private BaseFragment fragment;
    private Utilities.CallbackReturn<URLSpan, Boolean> onLinkPress;
    private Utilities.Callback3<String, CharSequence, TL_iv.RichMessage> onInsertPress;

    private String prompt;
    private String imagePath;
    private boolean useHistory;
    private String currentRequestId;
    private CharSequence currentResponse;
    private TL_iv.RichMessage currentRichMessage;
    private String pendingMarkdown;
    private boolean parsingMarkdown;
    private int parseGeneration;
    private boolean generating;
    private boolean errorState;
    private boolean thinkingVisible;
    private boolean turnSaved;
    private boolean sheetTopNotAnimate;

    private AiResponseAlert(Context context, Client client, String prompt, String imagePath, boolean useHistory, Theme.ResourcesProvider resourcesProvider) {
        super(context, false, resourcesProvider);
        this.client = client;
        this.imagePath = imagePath;
        this.useHistory = useHistory;
        backgroundPaddingLeft = 0;
        fixNavigationBar();

        containerView = new ContainerView(context);
        sheetTopAnimated = new AnimatedFloat(containerView, 320, CubicBezierInterpolator.EASE_OUT_QUINT);

        loadingTextView = new LoadingTextView(context);
        loadingTextView.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(12), AndroidUtilities.dp(22), AndroidUtilities.dp(6));
        loadingTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, SharedConfig.fontSize);
        loadingTextView.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        loadingTextView.setTypeface(AndroidUtilities.regular());
        loadingTextView.setLinkTextColor(Theme.multAlpha(getThemedColor(Theme.key_dialogTextBlack), 0.2f));

        thinkingDotsView = new ThinkingDotsView(context, getThemedColor(Theme.key_dialogTextBlack));

        promptTextView = new TextView(context);
        promptTextView.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(12), AndroidUtilities.dp(22), 0);
        promptTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, SharedConfig.fontSize);
        promptTextView.setTypeface(AndroidUtilities.regular());
        promptTextView.setTextColor(getThemedColor(Theme.key_player_actionBarSubtitle));

        previewView = new RichMessageLayout.PreviewView(context, UserConfig.selectedAccount, createRichResourcesProvider());
        previewView.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(12), AndroidUtilities.dp(22), AndroidUtilities.dp(6));
        previewView.setOnLinkPress(span -> openUrl(span.getURL()));

        contextView = new LinearLayout(context) {
            @Override
            public boolean hasOverlappingRendering() {
                return false;
            }
        };
        contextView.setOrientation(LinearLayout.VERTICAL);
        contextView.setAlpha(0.55f);
        contextView.setVisibility(View.GONE);

        contextDivider = new View(context);
        contextDivider.setBackgroundColor(Theme.multAlpha(getThemedColor(Theme.key_divider), 0.75f));
        contextDivider.setVisibility(View.GONE);

        responseContainer = new ResponseContainer(context);
        responseContainer.addView(contextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        responseContainer.addView(contextDivider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 22, 10, 22, 0));
        responseContainer.addView(promptTextView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        responseContainer.addView(previewView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        setPrompt(prompt.trim());

        listView = new RecyclerListView(context, resourcesProvider) {
            @Override
            protected boolean onRequestFocusInDescendants(int direction, Rect previouslyFocusedRect) {
                return true;
            }

            @Override
            public void requestChildFocus(View child, View focused) {
            }

            @Override
            public boolean dispatchTouchEvent(MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN && event.getY() < getSheetTop() - getTop()) {
                    dismiss();
                    return true;
                }
                return super.dispatchTouchEvent(event);
            }
        };
        listView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        listView.setPadding(0, AndroidUtilities.statusBarHeight + AndroidUtilities.dp(56), 0, AndroidUtilities.dp(80));
        listView.setClipToPadding(true);
        LinearLayoutManager layoutManager = new LinearLayoutManager(context);
        listView.setLayoutManager(layoutManager);
        adapter = new PaddedAdapter(context, loadingTextView);
        listView.setAdapter(adapter);
        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                containerView.invalidate();
                textSelectionHelper.onParentScrolled();
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    sheetTopNotAnimate = false;
                }
                if ((newState == RecyclerView.SCROLL_STATE_IDLE || newState == RecyclerView.SCROLL_STATE_SETTLING)
                        && getSheetTop(false) > 0 && getSheetTop(false) < AndroidUtilities.dp(96)
                        && listView.canScrollVertically(1) && hasEnoughHeight()) {
                    sheetTopNotAnimate = true;
                    listView.smoothScrollBy(0, (int) getSheetTop(false));
                }
            }
        });
        DefaultItemAnimator itemAnimator = new DefaultItemAnimator() {
            @Override
            protected void onChangeAnimationUpdate(RecyclerView.ViewHolder holder) {
                containerView.invalidate();
            }

            @Override
            protected void onMoveAnimationUpdate(RecyclerView.ViewHolder holder) {
                containerView.invalidate();
            }
        };
        itemAnimator.setDurations(180);
        itemAnimator.setInterpolator(new LinearInterpolator());
        listView.setItemAnimator(itemAnimator);
        containerView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        textSelectionHelper = new TextSelectionHelper.ArticleTextSelectionHelper();
        textSelectionHelper.setParentView(listView);
        textSelectionHelper.layoutManager = layoutManager;
        textSelectionOverlay = textSelectionHelper.getOverlayView(context);
        AndroidUtilities.removeFromParent(textSelectionOverlay);
        containerView.addView(textSelectionOverlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));
        previewView.setTextSelectionHelper(textSelectionHelper);

        headerView = new HeaderView(context);
        containerView.addView(headerView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 78, Gravity.TOP | Gravity.FILL_HORIZONTAL));

        mainButton = new ButtonWithCounterView(context, resourcesProvider);
        mainButton.setRound();
        mainButton.setColor(getThemedColor(Theme.key_featuredStickers_addButton));
        mainButton.setText(LocaleController.getString(R.string.Stop), false);
        generating = true;
        mainButton.setOnClickListener(v -> {
            if (errorState) {
                regenerate();
            } else if (generating) {
                stopGeneration();
            } else {
                dismiss();
            }
        });
        containerView.addView(mainButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL, 16, 16, 16, 16));
    }

    @Override
    protected boolean canDismissWithSwipe() {
        return false;
    }

    private Theme.ResourcesProvider createRichResourcesProvider() {
        final Theme.ResourcesProvider parent = resourcesProvider;
        return new Theme.ResourcesProvider() {
            @Override
            public int getColor(int key) {
                if (key == Theme.key_chat_messageTextIn || key == Theme.key_chat_messageTextOut) {
                    key = Theme.key_dialogTextBlack;
                } else if (key == Theme.key_chat_messageLinkIn || key == Theme.key_chat_messageLinkOut) {
                    key = Theme.key_dialogTextLink;
                }
                return Theme.getColor(key, parent);
            }

            @Override
            public Drawable getDrawable(String drawableKey) {
                return parent == null ? null : parent.getDrawable(drawableKey);
            }

            @Override
            public Paint getPaint(String paintKey) {
                return parent == null ? Theme.getThemePaint(paintKey) : parent.getPaint(paintKey);
            }

            @Override
            public boolean hasGradientService() {
                return parent != null && parent.hasGradientService();
            }

            @Override
            public boolean isDark() {
                return parent == null ? Theme.isCurrentThemeDark() : parent.isDark();
            }

            @Override
            public void applyServiceShaderMatrix(int w, int h, float translationX, float translationY) {
                if (parent == null) {
                    Theme.applyServiceShaderMatrix(w, h, translationX, translationY);
                } else {
                    parent.applyServiceShaderMatrix(w, h, translationX, translationY);
                }
            }
        };
    }

    public static AiResponseAlert showAlert(BaseFragment fragment, Client client, String prompt, boolean useHistory, boolean noforwards, Utilities.CallbackReturn<URLSpan, Boolean> onLinkPress, Runnable onDismiss, Utilities.Callback3<String, CharSequence, TL_iv.RichMessage> onInsert) {
        return showAlert(fragment, client, prompt, null, useHistory, noforwards, onLinkPress, onDismiss, onInsert);
    }

    public static AiResponseAlert showAlert(BaseFragment fragment, Client client, String prompt, String imagePath, boolean useHistory, boolean noforwards, Utilities.CallbackReturn<URLSpan, Boolean> onLinkPress, Runnable onDismiss, Utilities.Callback3<String, CharSequence, TL_iv.RichMessage> onInsert) {
        AiResponseAlert alert = new AiResponseAlert(fragment.getContext(), client, prompt, imagePath, useHistory, fragment.getResourceProvider()) {
            @Override
            public void dismiss() {
                super.dismiss();
                if (onDismiss != null) {
                    onDismiss.run();
                }
            }
        };
        alert.setNoforwards(noforwards);
        alert.setFragment(fragment);
        alert.setOnLinkPress(onLinkPress);
        alert.setOnInsertPress(onInsert);
        if (fragment.getParentActivity() != null) {
            fragment.showDialog(alert);
        }
        alert.generate();
        return alert;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
        loadingTextView.setText(Emoji.replaceEmoji(prompt, loadingTextView.getPaint().getFontMetricsInt(), true));
        formatPrompt();
    }

    private void updateMainButton(boolean generating) {
        updateMainButton(generating, false);
    }

    private void updateMainButton(boolean generating, boolean error) {
        this.generating = generating;
        this.errorState = error;
        int text;
        if (generating) {
            text = R.string.Stop;
        } else {
            text = error ? R.string.Retry : R.string.Close;
        }
        mainButton.setText(LocaleController.getString(text), true);
        boolean showActions = !generating && !error && !TextUtils.isEmpty(currentResponse);
        headerView.optionsButton.setSubItemShown(OPTION_ASK_MORE, useHistory && turnSaved);
        AndroidUtilities.updateViewVisibilityAnimated(headerView.optionsButton, showActions, 0.5f, true);
        if (onInsertPress != null) {
            AndroidUtilities.updateViewVisibilityAnimated(headerView.insertButton, showActions, 0.5f, true);
        }
    }

    private void stopGeneration() {
        client.stopRequest(currentRequestId);
        currentRequestId = null;
        stopThinking();
        if (TextUtils.isEmpty(currentResponse)) {
            dismiss();
        } else {
            adapter.updateMainView(responseContainer);
            updateMainButton(false);
        }
    }

    private void regenerate() {
        dropLastTurnFromHistory();
        showLoadingView();
        generate();
    }

    private void dropLastTurnFromHistory() {
        if (turnSaved) {
            AiConfig.removeLastFromHistory();
            turnSaved = false;
        }
    }

    private void formatPrompt() {
        if (AiConfig.getShowResponseOnly() || TextUtils.isEmpty(prompt)) {
            promptTextView.setVisibility(View.GONE);
            promptTextView.setText("");
        } else {
            promptTextView.setVisibility(View.VISIBLE);
            promptTextView.setText(Emoji.replaceEmoji(formatPromptText(prompt), promptTextView.getPaint().getFontMetricsInt(), false));
        }
    }

    private CharSequence formatPromptText(String text) {
        ColoredImageSpan arrowSpan = new ColoredImageSpan(R.drawable.msg_mini_arrow_mediathin);
        arrowSpan.setColorKey(Theme.key_player_actionBarSubtitle);
        arrowSpan.setScale(1.0f, 1.0f);
        arrowSpan.setTranslateY(AndroidUtilities.dpf2(1.5f));
        arrowSpan.spaceScaleX = 0.95f;
        SpannableStringBuilder sb = new SpannableStringBuilder("→ " + text);
        sb.setSpan(arrowSpan, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new DialogCell.FixedWidthSpan(AndroidUtilities.dp(4)), 1, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new RelativeSizeSpan(0.8f), 2, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(getThemedColor(Theme.key_player_actionBarSubtitle)), 2, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private ArrayList<Service> availableServices() {
        ArrayList<Service> services = new ArrayList<>();
        for (Service service : AiController.getInstance().getAll()) {
            if (!service.isOnDevice() || OnDeviceAvailability.isReady()) {
                services.add(service);
            }
        }
        return services;
    }

    private void updateContextView() {
        contextView.removeAllViews();
        ArrayList<Message> history = !useHistory || AiConfig.getShowResponseOnly() ? null : AiConfig.getConversationHistory();
        ArrayList<Message> shown = new ArrayList<>();
        if (history != null) {
            for (int i = Math.max(0, history.size() - CONTEXT_MESSAGES_LIMIT); i < history.size(); i++) {
                Message message = history.get(i);
                if (message != null && !TextUtils.isEmpty(message.content())) {
                    shown.add(message);
                    contextView.addView(createContextTextView(message), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                }
            }
        }
        boolean hasContext = contextView.getChildCount() > 0;
        contextView.setVisibility(hasContext ? View.VISIBLE : View.GONE);
        contextDivider.setVisibility(hasContext ? View.VISIBLE : View.GONE);
        formatContextAsync(shown);
    }

    private void formatContextAsync(ArrayList<Message> messages) {
        if (messages.isEmpty()) {
            return;
        }
        int generation = parseGeneration;
        Utilities.globalQueue.postRunnable(() -> {
            ArrayList<CharSequence> formatted = new ArrayList<>(messages.size());
            for (Message message : messages) {
                formatted.add("assistant".equals(message.role()) ? MarkdownPreview.format(message.content()) : null);
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (generation != parseGeneration || contextView.getChildCount() != formatted.size()) {
                    return;
                }
                for (int i = 0; i < formatted.size(); i++) {
                    CharSequence text = formatted.get(i);
                    if (text == null) {
                        continue;
                    }
                    View child = contextView.getChildAt(i);
                    if (child instanceof TextView) {
                        TextView textView = (TextView) child;
                        textView.setText(Emoji.replaceEmoji(text, textView.getPaint().getFontMetricsInt(), false));
                    }
                }
            });
        });
    }

    private TextView createContextTextView(Message message) {
        boolean isAssistant = "assistant".equals(message.role());
        TextView textView = new TextView(getContext());
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, SharedConfig.fontSize);
        textView.setTypeface(AndroidUtilities.regular());
        textView.setMaxLines(3);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        textView.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(isAssistant ? 6 : 12), AndroidUtilities.dp(22), 0);
        textView.setTextColor(getThemedColor(isAssistant ? Theme.key_dialogTextBlack : Theme.key_player_actionBarSubtitle));
        textView.setLinkTextColor(getThemedColor(Theme.key_dialogTextLink));
        CharSequence text = isAssistant ? message.content() : formatPromptText(message.content());
        textView.setText(Emoji.replaceEmoji(text, textView.getPaint().getFontMetricsInt(), false));
        return textView;
    }

    private CharSequence getRawResponse(CharSequence response) {
        if (AiConfig.getShowResponseOnly() || TextUtils.isEmpty(prompt)) {
            return response;
        }
        return "→ " + prompt + "\n\n" + response;
    }

    private CharSequence getInsertResponse() {
        if (currentRichMessage == null) {
            return getRawResponse(currentResponse);
        }
        CharSequence text = RichMessageConvert.blocksToCharSequence(currentRichMessage.blocks);
        if (AiConfig.getInsertAsQuote()) {
            text = AndroidUtilities.removeSpans(AndroidUtilities.removeSpans(text, QuoteSpan.QuoteStyleSpan.class), QuoteSpan.class);
        }
        if (AiConfig.getShowResponseOnly() || TextUtils.isEmpty(prompt)) {
            return text;
        }
        return new SpannableStringBuilder("→ " + prompt + "\n\n").append(text);
    }

    private void generate() {
        currentResponse = null;
        currentRichMessage = null;
        pendingMarkdown = null;
        turnSaved = false;
        parseGeneration++;
        updateMainButton(true);
        updateContextView();
        textSelectionHelper.clear(true);
        stopThinking();
        if (AiController.getInstance().getSelected().isReasoningEnabled() && AiConfig.getResponseStreaming()) {
            showThinking();
        }
        currentRequestId = client.getResponse(prompt, useHistory, AiConfig.getResponseStreaming(), imagePath, new GenerationCallback() {
            @Override
            public void onThinking() {
                showThinking();
            }

            @Override
            public void onResponse(String response) {
                if (TextUtils.isEmpty(response)) {
                    return;
                }
                currentResponse = response;
                turnSaved = useHistory;
                setResponse(response);
                updateMainButton(false);
            }

            @Override
            public void onChunk(String chunk) {
                if (TextUtils.isEmpty(chunk)) {
                    return;
                }
                currentResponse = chunk;
                setResponse(chunk);
            }

            @Override
            public void onError(int code, String message) {
                stopThinking();
                Runnable onChangeModel = AiController.isOnDeviceError(code) && availableServices().size() > 1 ? headerView::openModelSelect : null;
                AiController.showErrorBulletin(containerView, resourcesProvider, code, message, onChangeModel);
                adapter.updateMainView(responseContainer);
                updateMainButton(false, true);
            }
        });
    }

    private void showThinking() {
        if (thinkingVisible || !TextUtils.isEmpty(currentResponse)) {
            return;
        }
        thinkingVisible = true;
        thinkingDotsView.start();
        adapter.updateMainView(thinkingDotsView);
    }

    private void stopThinking() {
        thinkingVisible = false;
        thinkingDotsView.stop();
    }

    private void showLoadingView() {
        textSelectionHelper.clear(true);
        adapter.updateMainView(loadingTextView);
    }

    private void setResponse(String markdown) {
        pendingMarkdown = markdown;
        parseMarkdownAsync();
    }

    private void parseMarkdownAsync() {
        if (parsingMarkdown || pendingMarkdown == null) {
            return;
        }
        String markdown = pendingMarkdown;
        int generation = parseGeneration;
        pendingMarkdown = null;
        parsingMarkdown = true;
        Utilities.globalQueue.postRunnable(() -> {
            TL_iv.RichMessage richMessage = parseMarkdown(markdown);
            AndroidUtilities.runOnUIThread(() -> {
                parsingMarkdown = false;
                if (generation != parseGeneration) {
                    return;
                }
                stopThinking();
                currentRichMessage = richMessage;
                previewView.set(richMessage);
                adapter.updateMainView(responseContainer);
                parseMarkdownAsync();
            });
        });
    }

    private static TL_iv.RichMessage parseMarkdown(String markdown) {
        TL_iv.RichMessage richMessage = new TL_iv.RichMessage();
        try {
            MarkdownParser.parse(markdown, richMessage.blocks);
        } catch (Throwable e) {
            FileLog.e(e);
            richMessage.blocks.clear();
        }
        if (richMessage.blocks.isEmpty()) {
            TL_iv.pageBlockParagraph paragraph = new TL_iv.pageBlockParagraph();
            paragraph.text = plainText(markdown);
            richMessage.blocks.add(paragraph);
        }
        linkifyBlocks(richMessage.blocks);
        return richMessage;
    }

    private static void linkifyBlocks(List<TL_iv.PageBlock> blocks) {
        for (TL_iv.PageBlock block : blocks) {
            if (block instanceof TL_iv.pageBlockPreformatted) {
                continue;
            }
            if (block instanceof TL_iv.pageBlockDetails) {
                TL_iv.pageBlockDetails details = (TL_iv.pageBlockDetails) block;
                details.title = linkify(details.title);
                linkifyBlocks(details.blocks);
            } else if (block instanceof TL_iv.pageBlockList) {
                for (TL_iv.PageListItem item : ((TL_iv.pageBlockList) block).items) {
                    if (item instanceof TL_iv.TL_pageListItemText) {
                        TL_iv.TL_pageListItemText textItem = (TL_iv.TL_pageListItemText) item;
                        textItem.text = linkify(textItem.text);
                    } else if (item instanceof TL_iv.TL_pageListItemBlocks) {
                        linkifyBlocks(((TL_iv.TL_pageListItemBlocks) item).blocks);
                    }
                }
            } else if (block instanceof TL_iv.pageBlockOrderedList) {
                for (TL_iv.PageListOrderedItem item : ((TL_iv.pageBlockOrderedList) block).items) {
                    if (item instanceof TL_iv.TL_pageListOrderedItemText) {
                        TL_iv.TL_pageListOrderedItemText textItem = (TL_iv.TL_pageListOrderedItemText) item;
                        textItem.text = linkify(textItem.text);
                    } else if (item instanceof TL_iv.TL_pageListOrderedItemBlocks) {
                        linkifyBlocks(((TL_iv.TL_pageListOrderedItemBlocks) item).blocks);
                    }
                }
            } else if (block instanceof TL_iv.pageBlockTable) {
                for (TL_iv.pageTableRow row : ((TL_iv.pageBlockTable) block).rows) {
                    for (TL_iv.pageTableCell cell : row.cells) {
                        cell.text = linkify(cell.text);
                    }
                }
            } else {
                block.text = linkify(block.text);
            }
        }
    }

    private static TL_iv.RichText linkify(TL_iv.RichText richText) {
        if (richText == null
                || richText instanceof TL_iv.textEmpty
                || richText instanceof TL_iv.textUrl
                || richText instanceof TL_iv.textEmail
                || richText instanceof TL_iv.textPhone
                || richText instanceof TL_iv.textFixed
                || richText instanceof TL_iv.textMath
                || richText instanceof TL_iv.textImage) {
            return richText;
        }
        if (richText instanceof TL_iv.textConcat) {
            richText.texts.replaceAll(AiResponseAlert::linkify);
            return richText;
        }
        if (richText instanceof TL_iv.textPlain) {
            return linkifyPlain((TL_iv.textPlain) richText);
        }
        richText.text = linkify(richText.text);
        return richText;
    }

    private static TL_iv.RichText linkifyPlain(TL_iv.textPlain plain) {
        if (LinkifyPort.WEB_URL == null || TextUtils.isEmpty(plain.text)) {
            return plain;
        }
        String text = plain.text;
        Matcher matcher = LinkifyPort.WEB_URL.matcher(text);
        TL_iv.textConcat concat = null;
        int end = 0;
        while (matcher.find()) {
            String url = matcher.group();
            if (TextUtils.isEmpty(url)) {
                continue;
            }
            if (concat == null) {
                concat = new TL_iv.textConcat();
            }
            if (matcher.start() > end) {
                concat.texts.add(plainText(text.substring(end, matcher.start())));
            }
            TL_iv.textUrl textUrl = new TL_iv.textUrl();
            textUrl.text = plainText(url);
            textUrl.url = url;
            concat.texts.add(textUrl);
            end = matcher.end();
        }
        if (concat == null) {
            return plain;
        }
        if (end < text.length()) {
            concat.texts.add(plainText(text.substring(end)));
        }
        return concat;
    }

    private static TL_iv.RichText plainText(String text) {
        TL_iv.textPlain plain = new TL_iv.textPlain();
        plain.text = text == null ? "" : text;
        return plain;
    }

    private void openUrl(String url) {
        if (TextUtils.isEmpty(url)) {
            return;
        }
        if (onLinkPress != null) {
            if (onLinkPress.run(new URLSpan(url))) {
                dismiss();
            }
        } else if (fragment != null) {
            AlertsCreator.showOpenUrlAlert(fragment, url, false, false);
        }
    }

    private boolean hasEnoughHeight() {
        float height = 0;
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            if (listView.getChildAdapterPosition(child) == 1) {
                height += child.getHeight();
            }
        }
        return height >= listView.getHeight() - listView.getPaddingTop() - listView.getPaddingBottom();
    }

    public void setFragment(BaseFragment fragment) {
        this.fragment = fragment;
    }

    public void setOnLinkPress(Utilities.CallbackReturn<URLSpan, Boolean> onLinkPress) {
        this.onLinkPress = onLinkPress;
    }

    private void setOnInsertPress(Utilities.Callback3<String, CharSequence, TL_iv.RichMessage> onInsertPress) {
        this.onInsertPress = onInsertPress;
    }

    public void setNoforwards(boolean noforwards) {
        previewView.setTextSelectionHelper(noforwards ? null : textSelectionHelper);
        if (noforwards) {
            textSelectionHelper.clear(true);
        }
        if (getWindow() != null) {
            if (noforwards) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            }
        }
    }

    private float getSheetTop() {
        return getSheetTop(true);
    }

    private float getSheetTop(boolean animated) {
        float top = listView.getTop();
        if (listView.getChildCount() >= 1) {
            top += Math.max(0, listView.getChildAt(listView.getChildCount() - 1).getTop());
        }
        top = Math.max(0, top - AndroidUtilities.dp(78));
        if (animated && sheetTopAnimated != null) {
            if (!listView.scrollingByUser && !sheetTopNotAnimate) {
                return sheetTopAnimated.set(top);
            }
            sheetTopAnimated.set(top, true);
        }
        return top;
    }

    @Override
    public void show() {
        super.show();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.emojiLoaded);
    }

    @Override
    public void dismiss() {
        super.dismiss();
        client.stopRequest(currentRequestId);
        currentRequestId = null;
        stopThinking();
        pendingMarkdown = null;
        parseGeneration++;
        textSelectionHelper.clear(true);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.emojiLoaded);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.emojiLoaded) {
            loadingTextView.invalidate();
            promptTextView.invalidate();
            previewView.invalidate();
        }
    }

    public class ResponseContainer extends LinearLayout implements TextSelectionHelper.ArticleSelectableView {

        public ResponseContainer(Context context) {
            super(context);
            setOrientation(VERTICAL);
        }

        @Override
        public void fillTextLayoutBlocks(ArrayList<TextSelectionHelper.TextLayoutBlock> blocks) {
            previewView.fillTextLayoutBlocks(blocks);
            // TODO(openextera): in the original build this loop never runs (start index is taken after filling), verify whether offsets are needed
            for (int i = blocks.size(); i < blocks.size(); i++) {
                blocks.set(i, new OffsetTextLayoutBlock(blocks.get(i), previewView.getLeft(), previewView.getTop()));
            }
        }

        @Override
        public void invalidate() {
            super.invalidate();
            if (previewView != null) {
                previewView.invalidate();
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), heightMeasureSpec);
        }
    }

    public static final class OffsetTextLayoutBlock implements TextSelectionHelper.TextLayoutBlock {

        private final TextSelectionHelper.TextLayoutBlock block;
        private final int offsetX;
        private final int offsetY;

        private OffsetTextLayoutBlock(TextSelectionHelper.TextLayoutBlock block, int offsetX, int offsetY) {
            this.block = block;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
        }

        @Override
        public Layout getLayout() {
            return block.getLayout();
        }

        @Override
        public int getX() {
            return block.getX() + offsetX;
        }

        @Override
        public int getY() {
            return block.getY() + offsetY;
        }

        @Override
        public int getRow() {
            return block.getRow();
        }

        @Override
        public CharSequence getPrefix() {
            return block.getPrefix();
        }

        @Override
        public CharSequence getText() {
            return block.getText();
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof OffsetTextLayoutBlock)) {
                return false;
            }
            OffsetTextLayoutBlock that = (OffsetTextLayoutBlock) o;
            return offsetX == that.offsetX && offsetY == that.offsetY && Objects.equals(block, that.block);
        }

        @Override
        public int hashCode() {
            return Objects.hash(block, offsetX, offsetY);
        }

        @NonNull
        @Override
        public String toString() {
            return "OffsetTextLayoutBlock[block=" + block + ", offsetX=" + offsetX + ", offsetY=" + offsetY + "]";
        }
    }

    public static class LoadingTextView extends TextView {

        private final LinkPath path = new LinkPath(true);
        private final LoadingDrawable loadingDrawable = new LoadingDrawable();

        public LoadingTextView(Context context) {
            super(context);
            loadingDrawable.usePath(path);
            loadingDrawable.setSpeed(0.65f);
            loadingDrawable.setRadiiDp(4);
            setBackground(loadingDrawable);
        }

        @Override
        public void setTextColor(int color) {
            super.setTextColor(Theme.multAlpha(color, 0.2f));
            loadingDrawable.setColors(
                    Theme.multAlpha(color, 0.03f),
                    Theme.multAlpha(color, 0.175f),
                    Theme.multAlpha(color, 0.2f),
                    Theme.multAlpha(color, 0.45f)
            );
        }

        private void updateDrawable() {
            if (path == null || loadingDrawable == null) {
                return;
            }
            path.rewind();
            Layout layout = getLayout();
            if (layout != null) {
                path.setCurrentLayout(layout, 0, getPaddingLeft(), getPaddingTop());
                layout.getSelectionPath(0, layout.getText().length(), path);
            }
            loadingDrawable.updateBounds();
        }

        @Override
        public void setText(CharSequence text, BufferType type) {
            super.setText(text, type);
            updateDrawable();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            updateDrawable();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            loadingDrawable.reset();
        }
    }

    public static class ThinkingDotsView extends View {

        private final TypingDotsDrawable drawable;

        public ThinkingDotsView(Context context, int color) {
            super(context);
            drawable = new TypingDotsDrawable(true);
            drawable.setCallback(this);
            drawable.setIgnoreAnimationLocks();
            drawable.setColor(color);
        }

        public void start() {
            if (!drawable.isStarted()) {
                drawable.start();
            }
        }

        public void stop() {
            drawable.stop();
        }

        @Override
        protected boolean verifyDrawable(@NonNull Drawable who) {
            return super.verifyDrawable(who) || who == drawable;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), AndroidUtilities.dp(42));
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (drawable.isStarted()) {
                invalidate();
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            DrawableUtils.setBounds(drawable, AndroidUtilities.dp(22), h / 2.0f, Gravity.LEFT | Gravity.CENTER_VERTICAL);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            drawable.draw(canvas);
            if (drawable.isStarted()) {
                invalidate();
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            stop();
        }
    }

    public static class PaddedAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private final Context context;
        private View mainView;
        private int mainViewType = 1;

        public PaddedAdapter(Context context, View mainView) {
            this.context = context;
            this.mainView = mainView;
        }

        public void updateMainView(View view) {
            if (mainView == view) {
                return;
            }
            mainViewType++;
            mainView = view;
            notifyItemChanged(1);
        }

        @Override
        public int getItemCount() {
            return 2;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == 0) {
                return new RecyclerListView.Holder(new View(context) {
                    @Override
                    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                        super.onMeasure(
                                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                                MeasureSpec.makeMeasureSpec((int) (AndroidUtilities.displaySize.y * 0.4f), MeasureSpec.EXACTLY)
                        );
                    }
                });
            }
            return new RecyclerListView.Holder(mainView);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        }

        @Override
        public int getItemViewType(int position) {
            return position == 0 ? 0 : mainViewType;
        }
    }

    public class HeaderView extends FrameLayout {

        private final ImageView backButton;
        public final ImageView insertButton;
        public final ActionBarMenuItem optionsButton;
        private final TextView titleTextView;
        private final LinearLayout subtitleView;
        private final AnimatedTextView modelSelector;
        private final View shadow;

        public HeaderView(Context context) {
            super(context);

            View background = new View(context);
            background.setBackgroundColor(getThemedColor(Theme.key_dialogBackground));
            addView(background, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 44, Gravity.TOP | Gravity.FILL_HORIZONTAL, 0, 12, 0, 0));

            backButton = new ImageView(context);
            backButton.setScaleType(ImageView.ScaleType.CENTER);
            backButton.setImageResource(R.drawable.ic_ab_back);
            backButton.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_dialogTextBlack), PorterDuff.Mode.MULTIPLY));
            backButton.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector)));
            backButton.setAlpha(0f);
            backButton.setOnClickListener(v -> dismiss());
            addView(backButton, LayoutHelper.createFrame(54, 54, Gravity.TOP, 1, 1, 1, 1));

            insertButton = new ImageView(context);
            ScaleStateListAnimator.apply(insertButton, 0.15f, 1.5f);
            insertButton.setScaleType(ImageView.ScaleType.CENTER);
            insertButton.setImageResource(R.drawable.msg_send);
            insertButton.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_player_actionBarSubtitle), PorterDuff.Mode.MULTIPLY));
            insertButton.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector)));
            insertButton.setVisibility(View.GONE);
            insertButton.setOnClickListener(v -> {
                onInsertPress.run(prompt, getInsertResponse(), currentRichMessage);
                dismiss();
            });
            addView(insertButton, LayoutHelper.createFrame(48, 54, Gravity.TOP | Gravity.RIGHT, 1, 1, 64, 1));

            optionsButton = new ActionBarMenuItem(context, null, 0, getThemedColor(Theme.key_player_actionBarSubtitle), false, resourcesProvider);
            optionsButton.setLongClickEnabled(false);
            optionsButton.setShowSubmenuByMove(false);
            optionsButton.setIcon(R.drawable.ic_ab_other);
            optionsButton.setSubMenuOpenSide(2);
            optionsButton.setVisibility(View.GONE);
            optionsButton.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP, AndroidUtilities.dp(18)));
            addView(optionsButton, LayoutHelper.createFrame(48, 54, Gravity.TOP | Gravity.RIGHT, 1, 1, 16, 1));
            optionsButton.addSubItem(OPTION_COPY, R.drawable.msg_copy, LocaleController.getString(R.string.Copy));
            optionsButton.addSubItem(OPTION_RETRY, R.drawable.msg_retry, LocaleController.getString(R.string.Retry));
            optionsButton.addSubItem(OPTION_EDIT, R.drawable.msg_edit, LocaleController.getString(R.string.Edit));
            optionsButton.addSubItem(OPTION_ASK_MORE, R.drawable.msg_discuss, LocaleController.getString(R.string.AIAskMore));
            optionsButton.setSubItemShown(OPTION_ASK_MORE, false);
            optionsButton.setShowedFromBottom(false);
            optionsButton.setOnClickListener(v -> optionsButton.toggleSubMenu());
            optionsButton.setDelegate(this::onOptionClick);
            optionsButton.setContentDescription(LocaleController.getString(R.string.AccDescrMoreOptions));

            titleTextView = new TextView(context) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                    if (LocaleController.isRTL) {
                        titleTextView.setPivotX(getMeasuredWidth());
                    }
                }
            };
            titleTextView.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
            titleTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
            titleTextView.setTypeface(AndroidUtilities.bold());
            titleTextView.setSingleLine(true);
            titleTextView.setEllipsize(TextUtils.TruncateAt.END);
            titleTextView.setText(AiConfig.getSelectedRole());
            titleTextView.setIncludeFontPadding(false);
            titleTextView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            titleTextView.setMaxWidth(AndroidUtilities.displaySize.x - AndroidUtilities.dp(22) - AndroidUtilities.dp(112) - AndroidUtilities.dp(2));
            Drawable arrow = ContextCompat.getDrawable(getContext(), R.drawable.ic_arrow_drop_down);
            if (arrow != null) {
                arrow = arrow.mutate();
                arrow.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_dialogTextBlack), PorterDuff.Mode.MULTIPLY));
                arrow.setBounds(0, -AndroidUtilities.dp(1), AndroidUtilities.dp(22), AndroidUtilities.dp(21));
                if (LocaleController.isRTL) {
                    titleTextView.setCompoundDrawables(arrow, null, null, null);
                } else {
                    titleTextView.setCompoundDrawables(null, null, arrow, null);
                }
            }
            titleTextView.setCompoundDrawablePadding(AndroidUtilities.dp(2));
            titleTextView.setPadding(0, 0, 0, 0);
            titleTextView.setOnClickListener(v -> openRoleSelect());
            titleTextView.setPivotX(0);
            titleTextView.setPivotY(0);
            addView(titleTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, 22, 20, 112, 0));

            subtitleView = new LinearLayout(context);
            subtitleView.setPivotX(0);
            subtitleView.setPivotY(0);

            modelSelector = new AnimatedTextView(context) {
                private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                private final LinkSpanDrawable.LinkCollector links = new LinkSpanDrawable.LinkCollector();

                @Override
                protected void onDraw(Canvas canvas) {
                    RectF rect = AndroidUtilities.rectTmp;
                    rect.set(0, (getHeight() - AndroidUtilities.dp(18)) / 2f, width(), (getHeight() + AndroidUtilities.dp(18)) / 2f);
                    bgPaint.setColor(Theme.multAlpha(getThemedColor(Theme.key_player_actionBarSubtitle), 0.1175f));
                    canvas.drawRoundRect(rect, AndroidUtilities.dp(4), AndroidUtilities.dp(4), bgPaint);
                    if (links.draw(canvas)) {
                        invalidate();
                    }
                    super.onDraw(canvas);
                }

                @SuppressLint("ClickableViewAccessibility")
                @Override
                public boolean onTouchEvent(MotionEvent event) {
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        LinkSpanDrawable<?> link = new LinkSpanDrawable<>(null, resourcesProvider, event.getX(), event.getY());
                        link.setColor(Theme.multAlpha(getThemedColor(Theme.key_player_actionBarSubtitle), 0.1175f));
                        LinkPath linkPath = link.obtainNewPath();
                        RectF rect = AndroidUtilities.rectTmp;
                        rect.set(0, (getHeight() - AndroidUtilities.dp(18)) / 2f, width(), (getHeight() + AndroidUtilities.dp(18)) / 2f);
                        linkPath.addRect(rect, Path.Direction.CW);
                        links.addLink(link);
                        invalidate();
                        return true;
                    }
                    if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                        if (event.getAction() == MotionEvent.ACTION_UP) {
                            performClick();
                        }
                        links.clear();
                        invalidate();
                    }
                    return super.onTouchEvent(event);
                }
            };
            modelSelector.setAnimationProperties(0.25f, 0, 350, CubicBezierInterpolator.EASE_OUT_QUINT);
            modelSelector.setTextColor(getThemedColor(Theme.key_player_actionBarSubtitle));
            modelSelector.setTextSize(AndroidUtilities.dp(14));
            modelSelector.setText(AiController.getInstance().getSelected().getShortModel());
            modelSelector.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(2), AndroidUtilities.dp(4), AndroidUtilities.dp(2));
            modelSelector.setOnClickListener(v -> openModelSelect());
            subtitleView.addView(modelSelector, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 3, 0));
            addView(subtitleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.FILL_HORIZONTAL, 22, 43, 22, 0));

            shadow = new View(context);
            shadow.setBackgroundColor(getThemedColor(Theme.key_divider));
            shadow.setAlpha(0);
            addView(shadow, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, AndroidUtilities.getShadowHeight() / AndroidUtilities.dpf2(1), Gravity.TOP | Gravity.FILL_HORIZONTAL, 0, 56, 0, 0));

            backButton.bringToFront();
            insertButton.bringToFront();
            optionsButton.bringToFront();
        }

        private void onOptionClick(int id) {
            if (id == OPTION_COPY) {
                if (AndroidUtilities.addToClipboard(getRawResponse(currentResponse))) {
                    BulletinFactory.of((FrameLayout) containerView, resourcesProvider).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
                }
            } else if (id == OPTION_RETRY) {
                regenerate();
            } else if (id == OPTION_EDIT) {
                new GenerateFromMessageBottomSheet(prompt, imagePath, fragment, getContext(), data -> {
                    dropLastTurnFromHistory();
                    setPrompt(data.prompt());
                    imagePath = data.imagePath();
                    useHistory = data.useHistory();
                    showLoadingView();
                    generate();
                }, useHistory).show();
            } else if (id == OPTION_ASK_MORE) {
                new GenerateFromMessageBottomSheet(fragment, getContext(), data -> {
                    setPrompt(data.prompt());
                    imagePath = null;
                    useHistory = data.useHistory();
                    showLoadingView();
                    generate();
                }, false).show();
            }
        }

        public void openModelSelect() {
            if (client.isGenerating()) {
                return;
            }
            ItemOptions options = ItemOptions.makeOptions(containerView, resourcesProvider, modelSelector, false, false, true)
                    .setDrawScrim(false)
                    .setDimAlpha(0)
                    .setMaxHeight(AndroidUtilities.dp(336))
                    .setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT)
                    .translate(LocaleController.isRTL ? AndroidUtilities.dp(8) : -AndroidUtilities.dp(8), 0);
            ArrayList<Service> services = availableServices();
            for (Service service : services) {
                ActionBarMenuSubItem item = new ActionBarMenuSubItem(getContext(), 2, false, false, resourcesProvider);
                item.setText(service.getModel());
                item.setSubtext(service.isOnDevice() ? LocaleController.getString(R.string.AIOnDevice) : service.getUrl());
                item.subtextView.setPadding(0, 0, service.isSelected() ? AndroidUtilities.dp(34) : 0, 0);
                item.setMinimumWidth(AndroidUtilities.dp(196));
                item.setItemHeight(56);
                item.setChecked(service.isSelected());
                item.setOnClickListener(v -> {
                    options.dismiss();
                    if (service.isSelected()) {
                        return;
                    }
                    dropLastTurnFromHistory();
                    AiConfig.setSelectedServices(service);
                    modelSelector.setText(service.getShortModel());
                    showLoadingView();
                    generate();
                });
                options.add(item);
            }
            options.show();
        }

        public void openRoleSelect() {
            if (client.isGenerating()) {
                return;
            }
            ItemOptions options = ItemOptions.makeOptions(containerView, resourcesProvider, titleTextView)
                    .setDrawScrim(false)
                    .setDimAlpha(0)
                    .setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT)
                    .translate(LocaleController.isRTL ? AndroidUtilities.dp(8) : -AndroidUtilities.dp(8), 0);
            ArrayList<Role> roles = new ArrayList<>(AiController.getInstance().getSuggestedRoles());
            for (Role role : AiController.getInstance().getRoles()) {
                if (!roles.contains(role)) {
                    roles.add(role);
                }
            }
            for (Role role : roles) {
                options.addChecked(role.isSelected(), role.getName(), () -> {
                    if (role.isSelected()) {
                        return;
                    }
                    dropLastTurnFromHistory();
                    titleTextView.setText(role.getName());
                    AiConfig.setSelectedRole(role.getName());
                    showLoadingView();
                    generate();
                });
            }
            options.show();
        }

        @Override
        public void setTranslationY(float translationY) {
            super.setTranslationY(translationY);
            float t = MathUtils.clamp((translationY - AndroidUtilities.statusBarHeight) / AndroidUtilities.dp(64), 0, 1);
            if (!hasEnoughHeight()) {
                t = 1f;
            }
            float progress = CubicBezierInterpolator.EASE_OUT.getInterpolation(t);
            titleTextView.setScaleX(AndroidUtilities.lerp(0.85f, 1f, progress));
            titleTextView.setScaleY(AndroidUtilities.lerp(0.85f, 1f, progress));
            titleTextView.setTranslationY(AndroidUtilities.lerp(AndroidUtilities.dpf2(-12), 0, progress));
            titleTextView.setTranslationX(AndroidUtilities.lerp(AndroidUtilities.dpf2(50), 0, progress));
            subtitleView.setTranslationX(AndroidUtilities.lerp(AndroidUtilities.dpf2(50), 0, progress));
            subtitleView.setTranslationY(AndroidUtilities.lerp(AndroidUtilities.dpf2(-22), 0, progress));
            backButton.setTranslationX(AndroidUtilities.lerp(0, AndroidUtilities.dpf2(-25), progress));
            backButton.setAlpha(1f - progress);
            int iconColor = ColorUtils.blendARGB(getThemedColor(Theme.key_dialogTextBlack), getThemedColor(Theme.key_player_actionBarSubtitle), progress);
            insertButton.setTranslationX(AndroidUtilities.lerp(AndroidUtilities.dpf2(14), AndroidUtilities.dpf2(8), progress));
            insertButton.setTranslationY(AndroidUtilities.lerp(AndroidUtilities.dpf2(0), AndroidUtilities.dpf2(16), progress));
            insertButton.setColorFilter(iconColor, PorterDuff.Mode.MULTIPLY);
            optionsButton.setTranslationX(AndroidUtilities.lerp(AndroidUtilities.dpf2(14), AndroidUtilities.dpf2(8), progress));
            optionsButton.setTranslationY(AndroidUtilities.lerp(AndroidUtilities.dpf2(0), AndroidUtilities.dpf2(16), progress));
            optionsButton.setIconColor(iconColor);
            shadow.setTranslationY(AndroidUtilities.lerp(0, AndroidUtilities.dpf2(22), progress));
            shadow.setAlpha(1f - progress);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(
                    MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(78), MeasureSpec.EXACTLY)
            );
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (ellipsizeSpanAnimator != null) {
                ellipsizeSpanAnimator.onAttachedToWindow();
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (ellipsizeSpanAnimator != null) {
                ellipsizeSpanAnimator.onDetachedFromWindow();
            }
        }
    }

    public class ContainerView extends FrameLayout {

        private final Path bgPath = new Path();
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Boolean lightStatusBarFull;

        public ContainerView(Context context) {
            super(context);
            bgPaint.setColor(getThemedColor(Theme.key_dialogBackground));
            Theme.applyDefaultShadow(bgPaint);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (textSelectionHelper.isInSelectionMode() && textSelectionOverlay.onTouchEvent(event)) {
                return true;
            }
            if (textSelectionOverlay.checkOnTap(event)) {
                event.setAction(MotionEvent.ACTION_CANCEL);
            }
            return super.dispatchTouchEvent(event);
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            float sheetTop = getSheetTop();
            float radius = AndroidUtilities.lerp(0, AndroidUtilities.dp(12), MathUtils.clamp(sheetTop / AndroidUtilities.dpf2(24), 0, 1));
            headerView.setTranslationY(Math.max(AndroidUtilities.statusBarHeight, sheetTop));
            updateLightStatusBar(sheetTop <= AndroidUtilities.statusBarHeight / 2f);
            bgPath.rewind();
            RectF rect = AndroidUtilities.rectTmp;
            rect.set(0, sheetTop, getWidth(), getHeight() + radius);
            bgPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
            canvas.drawPath(bgPath, bgPaint);
            super.dispatchDraw(canvas);
        }

        private void updateLightStatusBar(boolean full) {
            if (lightStatusBarFull != null && lightStatusBarFull == full) {
                return;
            }
            lightStatusBarFull = full;
            Window window = getWindow();
            int color;
            if (full) {
                color = getThemedColor(Theme.key_dialogBackground);
            } else {
                color = Theme.blendOver(getThemedColor(Theme.key_actionBarDefault), 0x33000000);
            }
            AndroidUtilities.setLightStatusBar(window, AndroidUtilities.computePerceivedBrightness(color) > .721f);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(heightMeasureSpec), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            Bulletin.addDelegate(this, new Bulletin.Delegate() {
                @Override
                public int getBottomOffset(int tag) {
                    return AndroidUtilities.dp(80);
                }
            });
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            Bulletin.removeDelegate(this);
        }
    }
}
