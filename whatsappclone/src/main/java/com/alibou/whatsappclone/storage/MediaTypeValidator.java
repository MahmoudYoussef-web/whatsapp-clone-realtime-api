package com.alibou.whatsappclone.storage;

import lombok.Getter;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Whitelist-based media validation: allowed extensions mapped to their real MIME type,
 * with a per-category size limit. Rejects anything not explicitly allowed.
 */
public final class MediaTypeValidator {

    private MediaTypeValidator() {
    }

    private static final Map<String, String> EXTENSION_TO_MIME = Map.ofEntries(
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("png", "image/png"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("webm", "video/webm"),
            Map.entry("mov", "video/quicktime"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("wav", "audio/wav"),
            Map.entry("ogg", "audio/ogg"),
            Map.entry("weba", "audio/webm"),
            Map.entry("m4a", "audio/mp4"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("txt", "text/plain"),
            Map.entry("md", "text/markdown"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("zip", "application/zip")
    );

    private static final Map<Category, Long> CATEGORY_MAX_BYTES = Map.of(
            Category.IMAGE, 10L * 1024 * 1024,
            Category.AUDIO, 25L * 1024 * 1024,
            Category.VIDEO, 100L * 1024 * 1024,
            Category.DOCUMENT, 25L * 1024 * 1024
    );

    @Getter
    public enum Category {
        IMAGE, AUDIO, VIDEO, DOCUMENT
    }

    public static Optional<String> mimeTypeFor(String filename) {
        return extensionOf(filename).flatMap(ext -> Optional.ofNullable(EXTENSION_TO_MIME.get(ext)));
    }

    public static Optional<Category> categoryFor(String mimeType) {
        if (mimeType == null) {
            return Optional.empty();
        }
        if (mimeType.startsWith("image/")) {
            return Optional.of(Category.IMAGE);
        }
        if (mimeType.startsWith("audio/")) {
            return Optional.of(Category.AUDIO);
        }
        if (mimeType.startsWith("video/")) {
            return Optional.of(Category.VIDEO);
        }
        return Optional.of(Category.DOCUMENT);
    }

    public static long maxSizeFor(Category category) {
        return CATEGORY_MAX_BYTES.get(category);
    }

    public static Set<String> allowedExtensions() {
        return EXTENSION_TO_MIME.keySet();
    }

    private static Optional<String> extensionOf(String filename) {
        if (filename == null || filename.isBlank()) {
            return Optional.empty();
        }
        int lastDot = filename.lastIndexOf('.');
        if (lastDot == -1 || lastDot == filename.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(filename.substring(lastDot + 1).toLowerCase(Locale.ROOT));
    }
}
