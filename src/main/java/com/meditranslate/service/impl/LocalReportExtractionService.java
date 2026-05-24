package com.meditranslate.service.impl;

import com.meditranslate.config.MediTranslateProperties;
import com.meditranslate.dto.ExtractionResult;
import com.meditranslate.entity.ReportSourceType;
import com.meditranslate.service.ReportExtractionService;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.color.ColorSpace;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.awt.image.RescaleOp;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
public class LocalReportExtractionService implements ReportExtractionService {

    private static final List<String> OCR_HINT_KEYWORDS = List.of(
            "test", "result", "reference", "range", "unit", "sample", "method", "collected",
            "processed", "reported", "barcode", "thyroid", "tsh", "glucose", "hemoglobin",
            "cholesterol", "creatinine", "platelet", "vitamin", "serum"
    );

    private final MediTranslateProperties properties;
    private final Tika tika = new Tika();

    public LocalReportExtractionService(MediTranslateProperties properties) {
        this.properties = properties;
    }

    @Override
    public ExtractionResult extract(String typedText, MultipartFile file) throws IOException {
        String cleanedTypedText = normalize(typedText);
        boolean hasTypedText = StringUtils.hasText(cleanedTypedText);
        boolean hasFile = file != null && !file.isEmpty();

        if (!hasTypedText && !hasFile) {
            throw new IllegalArgumentException("Enter report text or upload a report file");
        }

        if (!hasFile) {
            return new ExtractionResult(cleanedTypedText, cleanedTypedText, ReportSourceType.TEXT_INPUT, null, null);
        }

        Path storageDirectory = Path.of(properties.getStoragePath());
        Files.createDirectories(storageDirectory);

        String originalFileName = StringUtils.cleanPath(file.getOriginalFilename() == null ? "report-upload" : file.getOriginalFilename());
        String storedFileName = UUID.randomUUID() + "-" + originalFileName.replaceAll("\\s+", "-");
        Path storedFilePath = storageDirectory.resolve(storedFileName);
        Files.copy(file.getInputStream(), storedFilePath, StandardCopyOption.REPLACE_EXISTING);

        String fileText = "";
        if (!shouldDeferToGemini(file.getContentType(), originalFileName)) {
            try {
                fileText = extractTextFromFile(file, storedFilePath);
            } catch (IOException ex) {
                fileText = "";
            }
        }
        String combinedText = hasTypedText ? normalize(cleanedTypedText + "\n\n" + fileText) : fileText;

        return new ExtractionResult(
                cleanedTypedText,
                combinedText,
                determineSourceType(file.getContentType(), originalFileName),
                originalFileName,
                storedFilePath.toString()
        );
    }

    public String extractStoredFileText(String storedFilePath, ReportSourceType sourceType, String originalFileName) throws IOException {
        if (!StringUtils.hasText(storedFilePath)) {
            return "";
        }
        Path path = Path.of(storedFilePath);
        return normalize(extractTextFromStoredPath(path, sourceType, originalFileName));
    }

    private String extractTextFromFile(MultipartFile file, Path storedFilePath) throws IOException {
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);

        if (contentType.startsWith("text/") || filename.endsWith(".txt")) {
            return normalize(Files.readString(storedFilePath, StandardCharsets.UTF_8));
        }

        if (isImage(contentType, filename)) {
            return normalize(runOcr(storedFilePath));
        }

        try {
            return normalize(tika.parseToString(storedFilePath));
        } catch (Exception ex) {
            throw new IOException("Could not read the uploaded report file", ex);
        }
    }

    private String extractTextFromStoredPath(Path storedFilePath, ReportSourceType sourceType, String originalFileName) throws IOException {
        String safeName = originalFileName == null ? "" : originalFileName.toLowerCase(Locale.ROOT);

        if (sourceType == ReportSourceType.IMAGE_UPLOAD || isImage("", safeName)) {
            return runOcr(storedFilePath);
        }

        if (safeName.endsWith(".txt")) {
            return Files.readString(storedFilePath, StandardCharsets.UTF_8);
        }

        try {
            return tika.parseToString(storedFilePath);
        } catch (Exception ex) {
            throw new IOException("Could not read the uploaded report file", ex);
        }
    }

    private String runOcr(Path storedFilePath) throws IOException {
        Path tessdataPath = resolveTessdataPath();
        if (tessdataPath == null) {
            throw new IOException("Image OCR is not configured on this machine. Install Tesseract OCR with English data, or upload PDF/DOCX/TXT, or paste the report text directly.");
        }

        BufferedImage sourceImage = ImageIO.read(storedFilePath.toFile());
        if (sourceImage == null) {
            return doFileOcr(storedFilePath, tessdataPath);
        }

        OcrAttempt bestAttempt = null;
        for (BufferedImage candidate : buildOcrCandidates(sourceImage)) {
            String text = doBufferedOcr(candidate, tessdataPath);
            int score = scoreOcrText(text);
            if (bestAttempt == null || score > bestAttempt.score()) {
                bestAttempt = new OcrAttempt(text, score);
            }
        }

        if (bestAttempt != null && StringUtils.hasText(bestAttempt.text())) {
            return bestAttempt.text();
        }

        return doFileOcr(storedFilePath, tessdataPath);
    }

    private String doFileOcr(Path storedFilePath, Path tessdataPath) throws IOException {
        try {
            return newTesseract(tessdataPath).doOCR(storedFilePath.toFile());
        } catch (TesseractException ex) {
            throw new IOException("OCR could not extract text from the image", ex);
        }
    }

    private String doBufferedOcr(BufferedImage image, Path tessdataPath) throws IOException {
        try {
            return newTesseract(tessdataPath).doOCR(image);
        } catch (TesseractException ex) {
            throw new IOException("OCR could not extract text from the image", ex);
        }
    }

    private Tesseract newTesseract(Path tessdataPath) {
        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath(tessdataPath.toString());
        tesseract.setLanguage("eng");
        tesseract.setPageSegMode(1);
        return tesseract;
    }

    private List<BufferedImage> buildOcrCandidates(BufferedImage sourceImage) {
        List<BufferedImage> candidates = new ArrayList<>();
        candidates.add(enhanceForOcr(sourceImage));
        candidates.add(binarize(enhanceForOcr(sourceImage)));
        candidates.add(enhanceForOcr(rotate(sourceImage, 90)));
        candidates.add(enhanceForOcr(rotate(sourceImage, 270)));
        candidates.add(enhanceForOcr(rotate(sourceImage, 180)));
        return candidates;
    }

    private BufferedImage enhanceForOcr(BufferedImage sourceImage) {
        BufferedImage scaled = scale(sourceImage, 1.75);
        BufferedImage grayscale = new BufferedImage(scaled.getWidth(), scaled.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        new ColorConvertOp(ColorSpace.getInstance(ColorSpace.CS_GRAY), null).filter(scaled, grayscale);
        return new RescaleOp(1.2f, 12f, null).filter(grayscale, null);
    }

    private BufferedImage binarize(BufferedImage sourceImage) {
        BufferedImage binary = new BufferedImage(sourceImage.getWidth(), sourceImage.getHeight(), BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D graphics = binary.createGraphics();
        graphics.drawImage(sourceImage, 0, 0, null);
        graphics.dispose();
        return binary;
    }

    private BufferedImage scale(BufferedImage sourceImage, double factor) {
        int width = Math.max(1, (int) Math.round(sourceImage.getWidth() * factor));
        int height = Math.max(1, (int) Math.round(sourceImage.getHeight() * factor));
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.drawImage(sourceImage, 0, 0, width, height, null);
        graphics.dispose();
        return scaled;
    }

    private BufferedImage rotate(BufferedImage sourceImage, double angleDegrees) {
        double radians = Math.toRadians(angleDegrees);
        double sin = Math.abs(Math.sin(radians));
        double cos = Math.abs(Math.cos(radians));
        int width = sourceImage.getWidth();
        int height = sourceImage.getHeight();
        int rotatedWidth = (int) Math.floor(width * cos + height * sin);
        int rotatedHeight = (int) Math.floor(height * cos + width * sin);

        BufferedImage rotated = new BufferedImage(rotatedWidth, rotatedHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rotated.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.translate((rotatedWidth - width) / 2.0, (rotatedHeight - height) / 2.0);
        graphics.rotate(radians, width / 2.0, height / 2.0);
        graphics.drawImage(sourceImage, 0, 0, null);
        graphics.dispose();
        return rotated;
    }

    private int scoreOcrText(String rawText) {
        String text = normalize(rawText);
        if (!StringUtils.hasText(text)) {
            return Integer.MIN_VALUE;
        }

        int letters = 0;
        int digits = 0;
        int noisyCharacters = 0;
        for (char character : text.toCharArray()) {
            if (Character.isLetter(character)) {
                letters++;
            } else if (Character.isDigit(character)) {
                digits++;
            } else if (!Character.isWhitespace(character)
                    && ".,:/%()-|[]".indexOf(character) < 0) {
                noisyCharacters++;
            }
        }

        int lineScore = 0;
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.length() < 4) {
                continue;
            }

            int lineLetters = countLetters(trimmed);
            int lineDigits = countDigits(trimmed);
            if (lineLetters >= 4 && trimmed.length() <= 120) {
                lineScore += 8;
            }
            if (lineLetters >= 3 && lineDigits >= 1) {
                lineScore += 10;
            }
            if (containsOcrHintKeyword(trimmed)) {
                lineScore += 30;
            }
        }

        return (letters * 2) + (digits * 3) + lineScore - (noisyCharacters * 4);
    }

    private boolean containsOcrHintKeyword(String line) {
        String normalizedLine = line.toLowerCase(Locale.ROOT);
        return OCR_HINT_KEYWORDS.stream().anyMatch(normalizedLine::contains);
    }

    private int countLetters(String value) {
        int count = 0;
        for (char character : value.toCharArray()) {
            if (Character.isLetter(character)) {
                count++;
            }
        }
        return count;
    }

    private int countDigits(String value) {
        int count = 0;
        for (char character : value.toCharArray()) {
            if (Character.isDigit(character)) {
                count++;
            }
        }
        return count;
    }

    private Path resolveTessdataPath() {
        String[] candidates = {
                properties.getOcr().getTessdataPath(),
                System.getenv("TESSDATA_PREFIX"),
                "/usr/share/tesseract-ocr/5/tessdata",
                "/usr/share/tesseract-ocr/4.00/tessdata",
                "/usr/share/tessdata",
                "/usr/local/share/tessdata"
        };

        for (String candidate : candidates) {
            if (!StringUtils.hasText(candidate)) {
                continue;
            }
            Path candidatePath = Path.of(candidate);
            if (Files.isDirectory(candidatePath) && Files.exists(candidatePath.resolve("eng.traineddata"))) {
                return candidatePath;
            }
        }

        return null;
    }

    private ReportSourceType determineSourceType(String contentType, String originalFileName) {
        String safeType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String safeName = originalFileName.toLowerCase(Locale.ROOT);
        if (isImage(safeType, safeName)) {
            return ReportSourceType.IMAGE_UPLOAD;
        }
        return ReportSourceType.DOCUMENT_UPLOAD;
    }

    private boolean isImage(String contentType, String originalFileName) {
        return contentType.startsWith("image/")
                || originalFileName.endsWith(".png")
                || originalFileName.endsWith(".jpg")
                || originalFileName.endsWith(".jpeg")
                || originalFileName.endsWith(".bmp")
                || originalFileName.endsWith(".tiff");
    }

    private boolean shouldDeferToGemini(String contentType, String originalFileName) {
        if (!properties.getGemini().isEnabled() || !StringUtils.hasText(properties.getGemini().getApiKey())) {
            return false;
        }
        String safeType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String safeName = originalFileName.toLowerCase(Locale.ROOT);
        return isImage(safeType, safeName) || safeName.endsWith(".pdf");
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.replace("\r", "\n")
                .replaceAll("[\\t ]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private record OcrAttempt(String text, int score) {
    }
}
