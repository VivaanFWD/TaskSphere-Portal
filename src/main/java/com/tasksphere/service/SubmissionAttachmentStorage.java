package com.tasksphere.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
public class SubmissionAttachmentStorage {
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
        "csv", "doc", "docx", "jpg", "jpeg", "odt", "pdf", "png", "txt", "xlsx"
    );

    private final Path root;

    public SubmissionAttachmentStorage(@Value("${app.upload-dir:./uploads}") String uploadDirectory) {
        this.root = Path.of(uploadDirectory).toAbsolutePath().normalize();
    }

    public StoredAttachment store(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            return null;
        }
        return store(file, false);
    }

    public StoredAttachment storePdf(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            return null;
        }
        return store(file, true);
    }

    private StoredAttachment store(MultipartFile file, boolean pdfOnly) throws IOException {
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("Attachments must be no larger than 20 MB.");
        }
        String name = safeFileName(file.getOriginalFilename());
        int extensionPosition = name.lastIndexOf('.');
        String extension = extensionPosition < 0 ? "" : name.substring(extensionPosition + 1).toLowerCase(Locale.ROOT);
        if (pdfOnly && !extension.equals("pdf")) {
            throw new IllegalArgumentException("Upload a PDF file.");
        }
        if (!pdfOnly && !ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Use a PDF, text, Office document, CSV, or image attachment.");
        }
        if (pdfOnly || extension.equals("pdf")) {
            try (var input = file.getInputStream()) {
                byte[] signature = input.readNBytes(5);
                if (!java.util.Arrays.equals(signature, "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                    throw new IllegalArgumentException("The selected file is not a valid PDF.");
                }
            }
        }
        Files.createDirectories(root);
        String key = UUID.randomUUID().toString();
        Path target = resolve(key);
        try {
            try (var input = file.getInputStream()) {
                Files.copy(input, target);
            }
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        return new StoredAttachment(key, name);
    }

    public Path resolve(String storageKey) {
        UUID.fromString(storageKey);
        Path file = root.resolve(storageKey).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("Invalid attachment reference.");
        }
        return file;
    }

    public void delete(String storageKey) throws IOException {
        Files.deleteIfExists(resolve(storageKey));
    }

    private String safeFileName(String originalName) {
        String normalized = StringUtils.hasText(originalName) ? originalName.replace('\\', '/') : "submission";
        int slash = normalized.lastIndexOf('/');
        String name = normalized.substring(slash + 1).replaceAll("[\\p{Cntrl}]", "_").trim();
        if (name.isEmpty() || name.length() > 255) {
            throw new IllegalArgumentException("Attachment file name must be between 1 and 255 characters.");
        }
        return name;
    }

    public record StoredAttachment(String storageKey, String fileName) {}
}
