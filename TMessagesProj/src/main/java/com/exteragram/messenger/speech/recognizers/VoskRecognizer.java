package com.exteragram.messenger.speech.recognizers;

import android.text.TextUtils;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.speech.VoiceRecognitionController;
import com.exteragram.messenger.speech.VoiceRecognitionController.RecognitionModel;
import com.exteragram.messenger.speech.utils.FormatConverter;
import com.exteragram.messenger.utils.network.ExteraHttpClient;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechStreamService;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class VoskRecognizer implements VoiceRecognitionController.RecognitionProvider, AutoCloseable {

    private static final String MODEL_ARCHIVE = "model.zip";

    private final OkHttpClient client = ExteraHttpClient.INSTANCE.getClient();
    private final File modelsDir = new File(ApplicationLoader.applicationContext.getExternalFilesDir(null), "Vosk Models");
    private final Map<String, Model> loadedModels = new ConcurrentHashMap<>();

    // TODO(openextera): model archives are not integrity-checked (no hash pinning)
    private final List<RecognitionModel> models = new ArrayList<RecognitionModel>() {{
        add(new RecognitionModel("ca", "https://alphacephei.com/vosk/models/vosk-model-small-ca-0.4.zip", 43405881L));
        add(new RecognitionModel("cs", "https://alphacephei.com/vosk/models/vosk-model-small-cs-0.4-rhasspy.zip", 46088666L));
        add(new RecognitionModel("de", "https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip", 46499967L));
        add(new RecognitionModel("en", "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", 41205931L));
        add(new RecognitionModel("eo", "https://alphacephei.com/vosk/models/vosk-model-small-eo-0.42.zip", 43839401L));
        add(new RecognitionModel("es", "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip", 39817833L));
        add(new RecognitionModel("fa", "https://alphacephei.com/vosk/models/vosk-model-small-fa-0.42.zip", 53431220L));
        add(new RecognitionModel("fr", "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip", 42233323L));
        add(new RecognitionModel("gu", "https://alphacephei.com/vosk/models/vosk-model-small-gu-0.42.zip", 108054987L));
        add(new RecognitionModel("hi", "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip", 44458845L));
        add(new RecognitionModel("it", "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip", 49665141L));
        add(new RecognitionModel("ja", "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip", 49704573L));
        add(new RecognitionModel("kk", "https://alphacephei.com/vosk/models/vosk-model-small-kz-0.42.zip", 59697294L));
        add(new RecognitionModel("ko", "https://alphacephei.com/vosk/models/vosk-model-small-ko-0.22.zip", 86914329L));
        add(new RecognitionModel("nl", "https://alphacephei.com/vosk/models/vosk-model-small-nl-0.22.zip", 40441176L));
        add(new RecognitionModel("pl", "https://alphacephei.com/vosk/models/vosk-model-small-pl-0.22.zip", 52979372L));
        add(new RecognitionModel("pt", "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip", 32453112L));
        add(new RecognitionModel("ru", "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip", 46236750L));
        add(new RecognitionModel("tg", "https://alphacephei.com/vosk/models/vosk-model-small-tg-0.22.zip", 51879043L));
        add(new RecognitionModel("tr", "https://alphacephei.com/vosk/models/vosk-model-small-tr-0.3.zip", 36855784L));
        add(new RecognitionModel("uk", "https://alphacephei.com/vosk/models/vosk-model-small-uk-v3-small.zip", 143914407L));
        add(new RecognitionModel("uz", "https://alphacephei.com/vosk/models/vosk-model-small-uz-0.22.zip", 51061189L));
        add(new RecognitionModel("vi", "https://alphacephei.com/vosk/models/vosk-model-small-vn-0.4.zip", 33656337L));
        add(new RecognitionModel("zh", "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip", 43898754L));
    }};

    private static void unpackZip(String zipPath, String targetPath) throws IOException {
        File targetDir = new File(targetPath);
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }
        byte[] buffer = new byte[1024];
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipPath))) {
            ZipEntry entry = zis.getNextEntry();
            // archives contain a single top-level folder (e.g. vosk-model-small-en-us-0.15/) that we strip
            String rootFolder = entry != null ? entry.getName().split("/")[0] : null;
            while (entry != null) {
                if (entry.getName().equals(rootFolder + "/")) {
                    entry = zis.getNextEntry();
                    continue;
                }
                File file = new File(targetDir, entry.getName().substring(rootFolder.length() + 1));
                // TODO(openextera): no zip-slip check on entry names
                new File(file.getParent()).mkdirs();
                if (!entry.isDirectory()) {
                    try (FileOutputStream fos = new FileOutputStream(file)) {
                        int read;
                        while ((read = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, read);
                        }
                    }
                }
                entry = zis.getNextEntry();
            }
            zis.closeEntry();
        }
    }

    private RecognitionModel findModel(String language) {
        return models.stream()
            .filter(model -> model.getLanguage().equals(language))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Model not found: " + language));
    }

    @Override
    public List<RecognitionModel> listAvailableModels() {
        return models;
    }

    @Override
    public List<RecognitionModel> listDownloadedModels() {
        return models.stream().filter(model -> {
            File dir = new File(modelsDir, model.getLanguage());
            return dir.exists() && !new File(dir, MODEL_ARCHIVE).exists() && !isDirectoryEmpty(dir);
        }).collect(Collectors.toList());
    }

    private boolean isDirectoryEmpty(File dir) {
        String[] files = dir.list();
        return files == null || files.length == 0;
    }

    @Override
    public void downloadModel(String language, VoiceRecognitionController.DownloadModelCallback callback) {
        RecognitionModel model = findModel(language);
        File modelDir = new File(modelsDir, model.getLanguage());
        if (new File(modelDir, MODEL_ARCHIVE).exists()) {
            // leftover from an interrupted download
            try {
                deleteDirectory(modelDir);
            } catch (Exception e) {
                callback.onError(new IOException("Failed to delete existing model directory", e));
                return;
            }
        }
        if (!modelDir.exists()) {
            modelDir.mkdirs();
        }
        try {
            Response response = client.newCall(new Request.Builder().url(model.getUrl()).build()).execute();
            if (!response.isSuccessful()) {
                FileLog.e("Failed to download: " + response);
            }
            File archive = new File(modelDir, MODEL_ARCHIVE);
            try (InputStream in = response.body().byteStream(); FileOutputStream out = new FileOutputStream(archive)) {
                long contentLength = response.body().contentLength();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    // TODO(openextera): decompile failed, verify (progress reporting inside the copy loop)
                    callback.onProgress((float) archive.length() / contentLength);
                }
            }
            unpackZip(archive.getAbsolutePath(), modelDir.getAbsolutePath());
            try {
                if (!archive.delete()) {
                    archive.deleteOnExit();
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            callback.onCompleted();
        } catch (Exception e) {
            callback.onError(e);
        }
    }

    @Override
    public void deleteModel(String language) {
        RecognitionModel model = findModel(language);
        Model loaded = loadedModels.remove(model.getLanguage());
        if (loaded != null) {
            loaded.close();
        }
        File modelDir = new File(modelsDir, model.getLanguage());
        if (!modelDir.exists()) {
            throw new IllegalStateException("Model is not downloaded: " + model.getLanguage());
        }
        deleteDirectory(modelDir);
    }

    private void deleteDirectory(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDirectory(child);
                }
            }
        }
        if (!file.delete()) {
            FileLog.e("Failed to delete file or directory: " + file.getAbsolutePath());
        }
    }

    @Override
    public void recognize(String path, String language, VoiceRecognitionController.RecognitionCallback callback) {
        if (models.stream().noneMatch(model -> model.getLanguage().equals(language))) {
            callback.onLanguageNotSupported(language);
            return;
        }
        if (listDownloadedModels().stream().noneMatch(model -> model.getLanguage().equals(language))) {
            callback.onLanguageNotDownloaded(language);
            return;
        }
        try {
            if (!loadedModels.containsKey(language)) {
                FileLog.d("Loading model: " + language);
                LibVosk.setLogLevel(LogLevel.INFO);
                loadedModels.put(language, new Model(modelsDir + "/" + language));
                FileLog.d("Model loaded: " + language);
            }
            Model model = loadedModels.get(language);
            InputStream pcm = FormatConverter.extractAndConvertToPcm(path, false);
            float sampleRate = FormatConverter.getSampleRate(path);
            FileLog.d("Recognizing: " + path);
            Recognizer recognizer = new Recognizer(model, sampleRate);
            new SpeechStreamService(recognizer, pcm, sampleRate).start(new RecognitionListener() {
                @Override
                public void onPartialResult(String hypothesis) {
                }

                @Override
                public void onResult(String hypothesis) {
                    FileLog.d("Result: " + hypothesis);
                    if (TextUtils.isEmpty(hypothesis)) {
                        return;
                    }
                    callback.onChunk(extractText(hypothesis));
                }

                @Override
                public void onFinalResult(String hypothesis) {
                    FileLog.d("Final result: " + hypothesis);
                    callback.onCompleted(extractText(hypothesis));
                    recognizer.close();
                }

                @Override
                public void onError(Exception e) {
                    FileLog.e("Failed to recognize", e);
                    callback.onError(e);
                    recognizer.close();
                }

                @Override
                public void onTimeout() {
                }
            });
        } catch (IOException e) {
            FileLog.e("Failed to recognize", e);
            callback.onError(e);
        }
    }

    private static String extractText(String json) {
        return (String) ExteraConfig.getGSON().fromJson(json, Map.class).get("text");
    }

    @Override
    public void unloadModels() {
        loadedModels.values().forEach(Model::close);
        loadedModels.clear();
    }

    @Override
    public boolean hasLoadedModels() {
        return !loadedModels.isEmpty();
    }

    @Override
    public void close() {
        unloadModels();
    }
}
