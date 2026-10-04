package com.exteragram.messenger.ai.network.backend;

import android.text.TextUtils;
import android.util.Base64;

import androidx.annotation.NonNull;

import com.exteragram.messenger.ai.data.Message;
import com.exteragram.messenger.ai.data.Role;
import com.exteragram.messenger.ai.data.Service;
import com.exteragram.messenger.ai.network.ProxyDns;
import com.exteragram.messenger.translator.TranslatorUtils;
import com.exteragram.messenger.utils.network.ExteraHttpClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class RestBackend implements Backend {

    private static final int STREAM_SYMBOLS_LIMIT = SharedConfig.getDevicePerformanceClass() >= SharedConfig.PERFORMANCE_CLASS_AVERAGE ? 10 : 20;
    private static final String[] ERROR_MESSAGE_KEYS = {"error", "message", "detail"};

    private final OkHttpClient httpClient;

    public RestBackend() {
        httpClient = ExteraHttpClient.INSTANCE.getClient().newBuilder()
                .dns(ProxyDns.INSTANCE)
                .connectTimeout(1, TimeUnit.MINUTES)
                .readTimeout(5, TimeUnit.MINUTES)
                .build();
    }

    @Override
    public int getHistoryCharsLimit() {
        return 24000;
    }

    @Override
    public int getHistoryMessagesLimit() {
        return 32;
    }

    @Override
    public int getMaxOutputTokens() {
        return 4096;
    }

    @Override
    public float getMaxTemperature() {
        return 2.0f;
    }

    @Override
    public boolean supportsImages() {
        return true;
    }

    @Override
    public void execute(BackendRequest request, BackendSink sink) throws IOException {
        Endpoint endpoint = resolveEndpoint(request.service().getUrl());
        Request httpRequest = endpoint != null ? createRequest(request, endpoint) : null;
        if (httpRequest == null) {
            sink.onError(500, "Failed to create request body");
            return;
        }
        if (!sink.isActive()) {
            return;
        }
        Call call = httpClient.newCall(httpRequest);
        sink.onCancellable(call::cancel);
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                String errorBody = null;
                try {
                    errorBody = response.body().string();
                    FileLog.e("AI_ERROR_RESPONSE_BODY (" + response.code() + "): " + errorBody);
                } catch (IOException e) {
                    FileLog.e("AI_ERROR_READING_RESPONSE_BODY: ", e);
                }
                sink.onError(response.code(), parseErrorMessage(errorBody, response.message()));
                return;
            }
            ResponseBody body = response.body();
            if (request.stream()) {
                handleStreamResponse(body, sink, endpoint.responsesApi());
            } else {
                String json = body.string();
                String content = parseResponseContent(json, endpoint.responsesApi());
                if (TextUtils.isEmpty(content)) {
                    String errorMessage = extractErrorMessage(json);
                    if (!TextUtils.isEmpty(errorMessage)) {
                        sink.onError(500, errorMessage);
                        return;
                    }
                }
                if (content == null) {
                    sink.onError(500, "Failed to parse response");
                    return;
                }
                sink.onComplete(content);
            }
        }
    }

    private Endpoint resolveEndpoint(String url) {
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        String lowerUrl = url.toLowerCase(Locale.ROOT);
        if (isGeminiService(lowerUrl)) {
            return new Endpoint("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", false);
        }
        if (isPerplexityService(lowerUrl) && !lowerUrl.contains("/router")) {
            return new Endpoint("https://api.perplexity.ai/v1/agent", true);
        }
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        lowerUrl = url.toLowerCase(Locale.ROOT);
        if (lowerUrl.endsWith("/responses") || lowerUrl.endsWith("/agent")) {
            return new Endpoint(url, true);
        }
        if (!lowerUrl.endsWith("/chat/completions")) {
            url = url + "/chat/completions";
        }
        return new Endpoint(url, false);
    }

    private Request createRequest(BackendRequest request, Endpoint endpoint) {
        Service service = request.service();
        boolean responsesApi = endpoint.responsesApi();
        JSONObject body = new JSONObject();
        JSONArray messages = new JSONArray();
        try {
            Role role = request.role();
            if (role != null && !TextUtils.isEmpty(role.getPrompt())) {
                if (responsesApi) {
                    body.put("instructions", role.getPrompt());
                } else {
                    messages.put(new JSONObject().put("role", "system").put("content", role.getPrompt()));
                }
            }
            for (Message message : request.history()) {
                messages.put(createMessageObject(message, responsesApi));
            }
            messages.put(createMessageObject(request.currentMessage(), responsesApi));
            body.put("model", service.getModel());
            body.put(responsesApi ? "input" : "messages", messages);
            body.put("stream", request.stream());
            body.put("temperature", request.temperature());
            applyReasoningConfig(body, service, service.getUrl(), responsesApi);
            body.put(responsesApi ? "max_output_tokens" : "max_tokens", request.maxOutputTokens());
            if (responsesApi) {
                body.put("store", false);
            }

            FileLog.d("AI_REQUEST_URL: " + endpoint.url());
            FileLog.d("AI_REQUEST_MODEL: " + service.getModel());
            FileLog.d("AI_REQUEST_MESSAGES: " + messages.length() + ", stream=" + request.stream() + ", image=" + (request.currentMessage().getImageData() != null));

            return new Request.Builder()
                    .url(endpoint.url())
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Authorization", "Bearer " + service.getKey())
                    .addHeader("User-Agent", TranslatorUtils.formatUserAgent())
                    .addHeader("HTTP-Referer", "exteragram.app")
                    .addHeader("X-Title", "exteraGram")
                    .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                    .build();
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private void applyReasoningConfig(JSONObject body, Service service, String url, boolean responsesApi) throws JSONException {
        if (service.isReasoningEnabled()) {
            return;
        }
        String model = service.getModel() == null ? "" : service.getModel().toLowerCase(Locale.ROOT);
        String lowerUrl = url == null ? "" : url.toLowerCase(Locale.ROOT);
        String effort = null;
        if (lowerUrl.contains("openrouter.ai")) {
            body.put("reasoning", new JSONObject().put("effort", "none"));
        } else if (isGeminiService(lowerUrl) && isGeminiReasoningModel(model)) {
            effort = getGeminiReasoningEffort(model);
        } else if (isOpenAiService(lowerUrl) && isOpenAiReasoningModel(model)) {
            effort = getOpenAiReasoningEffort(model);
        } else if (isPerplexityService(lowerUrl) && (isGeminiReasoningModel(model) || isOpenAiReasoningModel(model))) {
            effort = "minimal";
        }
        if (effort == null) {
            return;
        }
        if (responsesApi) {
            body.put("reasoning", new JSONObject().put("effort", effort));
        } else {
            body.put("reasoning_effort", effort);
        }
    }

    private boolean isGeminiService(String url) {
        return url.contains("generativelanguage.googleapis");
    }

    private boolean isGeminiReasoningModel(String model) {
        String name = stripProviderPrefix(model);
        return name.startsWith("gemini-2.5") || name.startsWith("gemini-3") || name.contains("thinking");
    }

    private boolean isOpenAiService(String url) {
        return url.contains("api.openai.com");
    }

    private boolean isPerplexityService(String url) {
        return url.contains("api.perplexity.ai");
    }

    private String getGeminiReasoningEffort(String model) {
        if (model.contains("gemini-2.5") && !model.contains("pro")) {
            return "none";
        }
        return "minimal";
    }

    private boolean isOpenAiReasoningModel(String model) {
        String name = stripProviderPrefix(model);
        if (name.contains("gpt-5-chat")) {
            return false;
        }
        return name.startsWith("gpt-5") || name.startsWith("o1") || name.startsWith("o3") || name.startsWith("o4");
    }

    private String getOpenAiReasoningEffort(String model) {
        return supportsOpenAiNoReasoning(stripProviderPrefix(model)) ? "none" : "minimal";
    }

    private boolean supportsOpenAiNoReasoning(String model) {
        return model.startsWith("gpt-5.1") || model.startsWith("gpt-5.2") || model.startsWith("gpt-5.3") || model.startsWith("gpt-5.4") || model.startsWith("gpt-5.5");
    }

    private String stripProviderPrefix(String model) {
        int slash = model.indexOf('/');
        return slash >= 0 ? model.substring(slash + 1) : model;
    }

    private JSONObject createMessageObject(Message message, boolean responsesApi) throws JSONException {
        JSONObject object = new JSONObject();
        if (responsesApi) {
            object.put("type", "message");
        }
        object.put("role", message.role());
        if (message.getImageData() != null && !TextUtils.isEmpty(message.getMimeType())) {
            JSONArray content = new JSONArray();
            if (!TextUtils.isEmpty(message.content())) {
                content.put(new JSONObject().put("type", responsesApi ? "input_text" : "text").put("text", message.content()));
            }
            String dataUrl = "data:" + message.getMimeType() + ";base64," + Base64.encodeToString(message.getImageData(), Base64.NO_WRAP);
            if (responsesApi) {
                content.put(new JSONObject().put("type", "input_image").put("image_url", dataUrl));
            } else {
                content.put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", dataUrl)));
            }
            object.put("content", content);
            return object;
        }
        object.put("content", message.content());
        return object;
    }

    private void handleStreamResponse(ResponseBody body, BackendSink sink, boolean responsesApi) {
        StringBuilder response = new StringBuilder();
        ReasoningContentFilter filter = new ReasoningContentFilter();
        String error = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body.byteStream()))) {
            int unsentLength = 0;
            String line;
            while ((line = reader.readLine()) != null && sink.isActive()) {
                if (TextUtils.isEmpty(line) || !line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                StreamResponsePart part;
                if (data.equals("[DONE]")) {
                    part = StreamResponsePart.DONE;
                } else if (responsesApi) {
                    part = parseResponsesStreamPart(data);
                } else {
                    part = parseStreamResponsePart(data);
                }
                if (part.error() != null) {
                    error = part.error();
                    break;
                }
                if (part.hasReasoning()) {
                    sink.onThinking();
                }
                String text = filter.filter(part.content());
                if (filter.consumeReasoningSignal()) {
                    sink.onThinking();
                }
                if (!TextUtils.isEmpty(text)) {
                    response.append(text);
                    unsentLength += text.length();
                    if (unsentLength >= STREAM_SYMBOLS_LIMIT) {
                        sink.onChunk(response.toString());
                        unsentLength = 0;
                    }
                }
                if (part.done()) {
                    if (unsentLength > 0) {
                        sink.onChunk(response.toString());
                    }
                    break;
                }
            }
        } catch (Exception e) {
            if (sink.isActive()) {
                FileLog.e(e);
                error = e.getMessage() != null ? e.getMessage() : "Unknown error";
            }
        }
        if (!sink.isActive()) {
            return;
        }
        if (error != null) {
            sink.onError(500, error);
            return;
        }
        String rest = filter.flush();
        if (!TextUtils.isEmpty(rest)) {
            response.append(rest);
            sink.onChunk(response.toString());
        }
        String result = trimTrailing(response.toString());
        if (!TextUtils.isEmpty(result)) {
            sink.onComplete(result);
        } else {
            sink.onError(204, "Response body is empty");
        }
    }

    private String parseResponseContent(String json, boolean responsesApi) {
        try {
            JSONObject object = new JSONObject(json);
            if (responsesApi) {
                String output = parseResponsesOutput(object);
                return output != null ? stripReasoningMarkup(output) : null;
            }
            JSONArray choices = object.optJSONArray("choices");
            if (choices == null || choices.length() <= 0) {
                return null;
            }
            JSONObject message = choices.getJSONObject(0).optJSONObject("message");
            if (message == null || !message.has("content") || message.isNull("content")) {
                return null;
            }
            Object content = message.opt("content");
            if (content == null) {
                return null;
            }
            return stripReasoningMarkup(content.toString());
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }

    private String parseResponsesOutput(JSONObject object) {
        JSONArray output = object.optJSONArray("output");
        if (output == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.optJSONObject(i);
            JSONArray content = item != null && "message".equals(item.optString("type")) ? item.optJSONArray("content") : null;
            if (content == null) {
                continue;
            }
            for (int j = 0; j < content.length(); j++) {
                JSONObject part = content.optJSONObject(j);
                if (part != null && "output_text".equals(part.optString("type"))) {
                    sb.append(part.optString("text"));
                }
            }
        }
        return sb.toString();
    }

    private StreamResponsePart parseStreamResponsePart(String json) {
        try {
            JSONArray choices = new JSONObject(json).optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                if (delta == null) {
                    return new StreamResponsePart("", false);
                }
                Object content = delta.opt("content");
                return new StreamResponsePart(content == null || content == JSONObject.NULL ? "" : content.toString(), hasReasoning(delta));
            }
        } catch (Exception ignore) {
        }
        return new StreamResponsePart("", false);
    }

    private StreamResponsePart parseResponsesStreamPart(String json) {
        try {
            JSONObject object = new JSONObject(json);
            String type = object.optString("type");
            switch (type) {
                case "response.output_item.added": {
                    JSONObject item = object.optJSONObject("item");
                    return new StreamResponsePart("", item != null && "reasoning".equals(item.optString("type")));
                }
                case "response.output_text.delta":
                    return new StreamResponsePart(object.optString("delta"), false);
                case "response.completed":
                case "response.incomplete":
                    return StreamResponsePart.DONE;
                case "error":
                case "response.failed":
                    return new StreamResponsePart("", false, true, getResponsesStreamError(object));
                default:
                    return new StreamResponsePart("", type.startsWith("response.reasoning"));
            }
        } catch (Exception e) {
            return new StreamResponsePart("", false);
        }
    }

    private String getResponsesStreamError(JSONObject object) {
        JSONObject error = object.optJSONObject("error");
        if (error == null) {
            JSONObject response = object.optJSONObject("response");
            error = response != null ? response.optJSONObject("error") : null;
        }
        String message = error != null ? error.optString("message") : object.optString("message");
        return TextUtils.isEmpty(message) ? "Unknown error" : message;
    }

    private boolean hasReasoning(JSONObject delta) {
        return hasValue(delta, "reasoning") || hasValue(delta, "reasoning_content") || hasValue(delta, "reasoning_details");
    }

    private boolean hasValue(JSONObject object, String key) {
        if (object == null || !object.has(key) || object.isNull(key)) {
            return false;
        }
        Object value = object.opt(key);
        if (value instanceof String) {
            return !TextUtils.isEmpty((String) value);
        }
        if (value instanceof JSONArray) {
            return ((JSONArray) value).length() > 0;
        }
        return value != null && value != JSONObject.NULL;
    }

    private String stripReasoningMarkup(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        String lower = text.toLowerCase(Locale.ROOT);
        int index = 0;
        while (index < text.length()) {
            int open = lower.indexOf("<think>", index);
            if (open < 0) {
                sb.append(text, index, text.length());
                break;
            }
            sb.append(text, index, open);
            int close = lower.indexOf("</think>", open + 7);
            if (close < 0) {
                break;
            }
            index = close + 8;
        }
        return trimLeading(sb.toString());
    }

    private String trimLeading(String text) {
        int start = 0;
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        return text.substring(start);
    }

    private String trimTrailing(String text) {
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    private String parseErrorMessage(String body, String httpMessage) {
        String message = extractErrorMessage(body);
        if (TextUtils.isEmpty(message)) {
            return !TextUtils.isEmpty(httpMessage) ? httpMessage.toLowerCase(Locale.ROOT) : "";
        }
        return message;
    }

    private String extractErrorMessage(String body) {
        if (TextUtils.isEmpty(body)) {
            return null;
        }
        String trimmed = body.trim();
        try {
            if (trimmed.startsWith("[")) {
                JSONArray array = new JSONArray(trimmed);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    JSONObject error = item == null ? null : item.optJSONObject("error");
                    String message = error == null ? null : error.optString("message", null);
                    if (!TextUtils.isEmpty(message)) {
                        return message;
                    }
                }
                return null;
            }
            if (trimmed.startsWith("{")) {
                JSONObject object = new JSONObject(trimmed);
                JSONObject error = object.optJSONObject("error");
                if (error != null) {
                    return error.optString("message", null);
                }
                for (String key : ERROR_MESSAGE_KEYS) {
                    Object value = object.opt(key);
                    if (value instanceof String && !((String) value).isEmpty()) {
                        return (String) value;
                    }
                }
                return null;
            }
            if (trimmed.startsWith("<") || trimmed.length() > 200 || trimmed.indexOf('\n') >= 0) {
                return null;
            }
            return trimmed;
        } catch (Exception e) {
            return null;
        }
    }

    public static class ReasoningContentFilter {

        private static final String OPEN_TAG = "<think>";
        private static final String CLOSE_TAG = "</think>";

        private boolean inReasoning;
        private String pending = "";
        private boolean reasoningSignal;

        private ReasoningContentFilter() {
        }

        public String filter(String chunk) {
            if (TextUtils.isEmpty(chunk)) {
                return null;
            }
            String text = pending + chunk;
            pending = "";
            StringBuilder sb = new StringBuilder(text.length());
            int index = 0;
            while (index < text.length()) {
                String lower = text.toLowerCase(Locale.ROOT);
                if (inReasoning) {
                    reasoningSignal = true;
                    int close = lower.indexOf(CLOSE_TAG, index);
                    if (close < 0) {
                        pending = getCloseTagPrefixSuffix(text, index);
                        return sb.toString();
                    }
                    index = close + CLOSE_TAG.length();
                    inReasoning = false;
                } else {
                    int open = lower.indexOf(OPEN_TAG, index);
                    if (open < 0) {
                        pending = getOpenTagPrefixSuffix(text, index);
                        reasoningSignal = !pending.isEmpty();
                        int end = text.length() - pending.length();
                        if (end > index) {
                            sb.append(text, index, end);
                        }
                        return sb.toString();
                    }
                    sb.append(text, index, open);
                    index = open + OPEN_TAG.length();
                    inReasoning = true;
                    reasoningSignal = true;
                }
            }
            return sb.toString();
        }

        public boolean consumeReasoningSignal() {
            boolean signal = reasoningSignal;
            reasoningSignal = false;
            return signal;
        }

        public String flush() {
            String rest = inReasoning ? "" : pending;
            pending = "";
            return rest;
        }

        private String getOpenTagPrefixSuffix(String text, int from) {
            String lower = text.toLowerCase(Locale.ROOT);
            for (int length = Math.min(OPEN_TAG.length() - 1, text.length() - from); length > 0; length--) {
                if (OPEN_TAG.startsWith(lower.substring(text.length() - length))) {
                    return text.substring(text.length() - length);
                }
            }
            return "";
        }

        private String getCloseTagPrefixSuffix(String text, int from) {
            String lower = text.toLowerCase(Locale.ROOT);
            for (int length = Math.min(CLOSE_TAG.length() - 1, text.length() - from); length > 0; length--) {
                if (CLOSE_TAG.startsWith(lower.substring(text.length() - length))) {
                    return text.substring(text.length() - length);
                }
            }
            return "";
        }
    }

    public static final class Endpoint {

        private final String url;
        private final boolean responsesApi;

        private Endpoint(String url, boolean responsesApi) {
            this.url = url;
            this.responsesApi = responsesApi;
        }

        public String url() {
            return url;
        }

        public boolean responsesApi() {
            return responsesApi;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Endpoint)) {
                return false;
            }
            Endpoint that = (Endpoint) o;
            return responsesApi == that.responsesApi && Objects.equals(url, that.url);
        }

        @Override
        public int hashCode() {
            return Objects.hash(url, responsesApi);
        }

        @NonNull
        @Override
        public String toString() {
            return "Endpoint[url=" + url + ", responsesApi=" + responsesApi + "]";
        }
    }

    public static final class StreamResponsePart {

        static final StreamResponsePart DONE = new StreamResponsePart("", false, true, null);

        private final String content;
        private final boolean hasReasoning;
        private final boolean done;
        private final String error;

        private StreamResponsePart(String content, boolean hasReasoning, boolean done, String error) {
            this.content = content;
            this.hasReasoning = hasReasoning;
            this.done = done;
            this.error = error;
        }

        public StreamResponsePart(String content, boolean hasReasoning) {
            this(content, hasReasoning, false, null);
        }

        public String content() {
            return content;
        }

        public boolean hasReasoning() {
            return hasReasoning;
        }

        public boolean done() {
            return done;
        }

        public String error() {
            return error;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof StreamResponsePart)) {
                return false;
            }
            StreamResponsePart that = (StreamResponsePart) o;
            return hasReasoning == that.hasReasoning && done == that.done && Objects.equals(content, that.content) && Objects.equals(error, that.error);
        }

        @Override
        public int hashCode() {
            return Objects.hash(content, hasReasoning, done, error);
        }

        @NonNull
        @Override
        public String toString() {
            return "StreamResponsePart[content=" + content + ", hasReasoning=" + hasReasoning + ", done=" + done + ", error=" + error + "]";
        }
    }
}
