package com.exteragram.messenger.utils;

import android.text.SpannableString;
import android.text.TextUtils;

import com.exteragram.messenger.components.PreformattedScrollView;

import org.telegram.messenger.CodeHighlighting;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.tl.TL_iv;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public abstract class MarkdownUtils {

    private static final String[] MARKDOWN_TEXT_EXTENSIONS = {"txt", "text"};
    private static final String[] MARKDOWN_MIME_PREFIXES = {
            "text/plain", "text/x-diff", "text/x-patch", "text/csv", "text/xml", "text/yaml", "text/x-yaml",
            "text/css", "text/javascript", "application/json", "application/ld+json", "application/json5",
            "application/xml", "application/yaml", "application/x-yaml", "application/javascript",
            "application/x-javascript", "application/x-sh"
    };
    private static final HashMap<String, String> PREFORMATTED_EXTENSION_LANGUAGES = new HashMap<>();
    private static final HashMap<String, String> PREFORMATTED_FILENAMES = new HashMap<>();

    static {
        addLanguage("plain", "log");
        addLanguage("diff", "diff", "patch");
        addLanguage("json", "json", "webmanifest");
        addLanguage("json5", "json5");
        addLanguage("xml", "xml", "rss", "atom");
        addLanguage("svg", "svg");
        addLanguage("html", "html", "htm", "xhtml");
        addLanguage("css", "css");
        addLanguage("scss", "scss");
        addLanguage("sass", "sass");
        addLanguage("less", "less");
        addLanguage("javascript", "js", "mjs", "cjs");
        addLanguage("jsx", "jsx");
        addLanguage("typescript", "ts");
        addLanguage("tsx", "tsx");
        addLanguage("java", "java");
        addLanguage("kotlin", "kt", "kts");
        addLanguage("gradle", "gradle");
        addLanguage("groovy", "groovy");
        addLanguage("python", "py", "pyw", "plugin");
        addLanguage("bash", "sh", "bash", "zsh", "fish", "shell");
        addLanguage("powershell", "ps1", "psm1", "psd1");
        addLanguage("batch", "bat", "cmd");
        addLanguage("sql", "sql");
        addLanguage("yaml", "yaml", "yml");
        addLanguage("ini", "ini", "toml", "properties", "props", "conf", "cfg", "config", "env", "dotenv");
        addLanguage("csv", "csv", "tsv");
        addLanguage("docker", "dockerfile");
        addLanguage("makefile", "make", "mk", "mak");
        addLanguage("cmake", "cmake");
        addLanguage("go", "go");
        addLanguage("rust", "rs");
        addLanguage("swift", "swift");
        addLanguage("dart", "dart");
        addLanguage("php", "php", "phtml");
        addLanguage("ruby", "rb", "gemspec");
        addLanguage("c", "c");
        addLanguage("cpp", "h", "hh", "hpp", "hxx", "cpp", "cc", "cxx");
        addLanguage("csharp", "cs");
        addLanguage("fsharp", "fs", "fsx");
        addLanguage("visual-basic", "vb", "vba");
        addLanguage("lua", "lua");
        addLanguage("perl", "pl", "pm");
        addLanguage("r", "r");
        addLanguage("scala", "scala");
        addLanguage("haskell", "hs");
        addLanguage("elixir", "ex", "exs");
        addLanguage("erlang", "erl", "hrl");
        addLanguage("protobuf", "proto", "protobuf");
        addLanguage("graphql", "graphql", "gql");
        addLanguage("glsl", "glsl", "vert", "frag", "geom", "comp");
        addLanguage("http", "http");

        addFilename("docker", "Dockerfile");
        addFilename("makefile", "Makefile", "GNUmakefile");
        addFilename("cmake", "CMakeLists.txt");
        addFilename("git", ".gitignore", ".gitattributes", ".gitmodules");
        addFilename("docker", ".dockerignore");
        addFilename("ini", ".editorconfig", ".env");
    }

    public static boolean isTheme(MessageObject messageObject) {
        if (messageObject == null) {
            return false;
        }
        String fileName = FileLoader.getDocumentFileName(messageObject.getDocument());
        return !TextUtils.isEmpty(fileName) && fileName.toLowerCase(Locale.ROOT).endsWith("attheme");
    }

    public static boolean isExteraMarkdown(MessageObject messageObject) {
        if (messageObject == null || isTheme(messageObject)) {
            return false;
        }
        return isExteraMarkdownExtension(messageObject.getExtension())
                || isExteraMarkdownMime(messageObject.getMimeType())
                || !TextUtils.isEmpty(getPreformattedLanguage(FileLoader.getDocumentFileName(messageObject.getDocument()), messageObject.getExtension(), messageObject.getMimeType()));
    }

    public static boolean isExteraMarkdownExtension(String extension) {
        if (isMarkdownTextExtension(extension)) {
            return true;
        }
        return !TextUtils.isEmpty(getPreformattedLanguage(null, extension, null));
    }

    public static boolean isExteraMarkdownMime(String mime) {
        if (TextUtils.isEmpty(mime)) {
            return false;
        }
        String lowerMime = mime.toLowerCase(Locale.ROOT);
        for (String prefix : MARKDOWN_MIME_PREFIXES) {
            if (lowerMime.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public static String getPreformattedLanguage(String fileName, String extension, String mime) {
        String language = getPreformattedLanguageByFileName(fileName);
        if (!TextUtils.isEmpty(language)) {
            return language;
        }
        String ext = normalizeExtension(extension);
        if (TextUtils.isEmpty(ext)) {
            ext = getExtensionFromFileName(fileName);
        }
        language = PREFORMATTED_EXTENSION_LANGUAGES.get(ext);
        if (!TextUtils.isEmpty(language)) {
            return language;
        }
        if (TextUtils.isEmpty(mime)) {
            return "";
        }
        String m = mime.toLowerCase(Locale.ROOT);
        if (m.startsWith("text/x-diff") || m.startsWith("text/x-patch")) {
            return "diff";
        }
        if (m.startsWith("text/csv")) {
            return "csv";
        }
        if (m.startsWith("text/xml") || m.startsWith("application/xml")) {
            return "xml";
        }
        if (m.startsWith("application/json5")) {
            return "json5";
        }
        if (m.startsWith("application/json") || m.startsWith("application/ld+json")) {
            return "json";
        }
        if (m.startsWith("text/yaml") || m.startsWith("text/x-yaml") || m.startsWith("application/yaml") || m.startsWith("application/x-yaml")) {
            return "yaml";
        }
        if (m.startsWith("text/css")) {
            return "css";
        }
        if (m.startsWith("text/javascript") || m.startsWith("application/javascript") || m.startsWith("application/x-javascript")) {
            return "javascript";
        }
        if (m.startsWith("application/x-sh")) {
            return "bash";
        }
        return "";
    }

    public static void appendPreformattedBlocks(List<TL_iv.PageBlock> blocks, String text, String language, int maxChunkLength) {
        int chunkLength = Math.max(1, maxChunkLength);
        if (TextUtils.isEmpty(text)) {
            TL_iv.pageBlockPreformatted block = new TL_iv.pageBlockPreformatted();
            block.text = plain("");
            block.language = language == null ? "" : language;
            blocks.add(block);
            return;
        }
        HighlightSource source = new HighlightSource(text, language == null ? "" : language);
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + chunkLength);
            if (end < text.length()) {
                int lineBreak = text.lastIndexOf('\n', end - 1);
                if (lineBreak > start) {
                    end = lineBreak + 1;
                }
            }
            blocks.add(new PreformattedChunk(source, start, end));
            start = end;
        }
    }

    public static final class PreformattedChunk extends TL_iv.pageBlockPreformatted {
        private final HighlightSource source;
        private final int start;
        private final int end;
        private final String plainText;
        private CharSequence highlightedText;
        public final boolean joinsPrevious;
        public final boolean joinsNext;
        public final PreformattedScrollView.Group scrollGroup;

        private PreformattedChunk(HighlightSource source, int start, int end) {
            this.source = source;
            this.start = start;
            this.end = end;
            plainText = source.text.substring(start, end);
            joinsPrevious = start > 0;
            joinsNext = end < source.text.length();
            scrollGroup = source.scrollGroup;
            text = plain(plainText);
            language = source.language;
        }

        public CharSequence getHighlightedText() {
            if (highlightedText == null) {
                SpannableString highlighted = source.getHighlighted();
                if (highlighted instanceof CodeHighlighting.LockedSpannableString && !((CodeHighlighting.LockedSpannableString) highlighted).ready) {
                    return plainText;
                }
                highlightedText = highlighted.subSequence(start, end);
            }
            return highlightedText;
        }
    }

    public static final class HighlightSource {
        final String text;
        final String language;
        final PreformattedScrollView.Group scrollGroup = new PreformattedScrollView.Group();
        private SpannableString highlighted;

        public HighlightSource(String text, String language) {
            this.text = text;
            this.language = language;
        }

        public SpannableString getHighlighted() {
            if (highlighted == null) {
                highlighted = CodeHighlighting.getHighlighted(text, language);
            }
            return highlighted;
        }
    }

    private static void addLanguage(String language, String... extensions) {
        for (String extension : extensions) {
            PREFORMATTED_EXTENSION_LANGUAGES.put(extension, language);
        }
    }

    private static void addFilename(String language, String... fileNames) {
        for (String fileName : fileNames) {
            PREFORMATTED_FILENAMES.put(fileName.toLowerCase(Locale.ROOT), language);
        }
    }

    private static boolean isMarkdownTextExtension(String extension) {
        String ext = normalizeExtension(extension);
        for (String textExtension : MARKDOWN_TEXT_EXTENSIONS) {
            if (textExtension.equals(ext)) {
                return true;
            }
        }
        return false;
    }

    private static String getPreformattedLanguageByFileName(String fileName) {
        if (TextUtils.isEmpty(fileName)) {
            return "";
        }
        String name = getBaseName(fileName).toLowerCase(Locale.ROOT);
        String language = PREFORMATTED_FILENAMES.get(name);
        if (!TextUtils.isEmpty(language)) {
            return language;
        }
        if (name.startsWith("dockerfile.")) {
            return "docker";
        }
        if (name.startsWith("makefile.")) {
            return "makefile";
        }
        return name.startsWith(".env.") ? "ini" : "";
    }

    private static String getExtensionFromFileName(String fileName) {
        if (TextUtils.isEmpty(fileName)) {
            return "";
        }
        String name = getBaseName(fileName);
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            return normalizeExtension(name.substring(dot + 1));
        }
        return "";
    }

    private static String getBaseName(String path) {
        int separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return separator >= 0 ? path.substring(separator + 1) : path;
    }

    private static String normalizeExtension(String extension) {
        if (TextUtils.isEmpty(extension)) {
            return "";
        }
        String ext = extension.trim();
        while (ext.startsWith(".")) {
            ext = ext.substring(1);
        }
        return ext.toLowerCase(Locale.ROOT);
    }

    private static TL_iv.RichText plain(String text) {
        TL_iv.textPlain richText = new TL_iv.textPlain();
        richText.text = text == null ? "" : text;
        return richText;
    }
}
