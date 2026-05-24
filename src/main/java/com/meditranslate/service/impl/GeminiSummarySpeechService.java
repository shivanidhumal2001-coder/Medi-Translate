package com.meditranslate.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meditranslate.config.MediTranslateProperties;
import com.meditranslate.service.SummarySpeechService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

@Service
public class GeminiSummarySpeechService implements SummarySpeechService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GeminiSummarySpeechService.class);
    private static final int SAMPLE_RATE = 24000;
    private static final int CHANNEL_COUNT = 1;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 12000;
    private static final Set<String> SUPPORTED_VOICE_NAMES = Set.of(
            "Zephyr", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Aoede", "Callirrhoe",
            "Autonoe", "Enceladus", "Iapetus", "Umbriel", "Algieba", "Despina", "Erinome",
            "Algenib", "Rasalgethi", "Laomedeia", "Achernar", "Alnilam", "Schedar", "Gacrux",
            "Pulcherrima", "Achird", "Zubenelgenubi", "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat"
    );
    private static final Map<String, String> DEFAULT_VOICE_BY_LANGUAGE = Map.of(
            "en", "Algieba",
            "hi", "Iapetus",
            "mr", "Iapetus",
            "ta", "Iapetus",
            "te", "Iapetus"
    );

    private final MediTranslateProperties props;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public GeminiSummarySpeechService(MediTranslateProperties props) {
        this.props = props;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MS);
        this.restTemplate = new RestTemplate(requestFactory);
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public boolean isAvailable() {
        String apiKey = props.getGemini().getApiKey();
        return props.getGemini().isEnabled()
                && StringUtils.hasText(apiKey)
                && !"YOUR_GEMINI_KEY_HERE".equals(apiKey);
    }

    @Override
    public byte[] synthesizeSummaryAudio(String text, String languageCode, String requestedVoice) {
        if (!isAvailable()) {
            throw new IllegalStateException("Gemini summary speech is not configured");
        }

        String normalizedText = normalizeText(text);
        if (!StringUtils.hasText(normalizedText)) {
            throw new IllegalArgumentException("Summary text cannot be blank");
        }

        String safeLanguage = StringUtils.hasText(languageCode)
                ? languageCode.trim().toLowerCase(Locale.ROOT)
                : "en";
        String voiceName = resolveVoiceName(requestedVoice, safeLanguage);
        RuntimeException lastFailure = null;
        int maxAttempts = 1;

        for (String model : resolveTtsModels()) {
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    LOGGER.info("Generating summary audio with Gemini model {} and voice {}", model, voiceName);
                    return requestSpeechAudio(model, normalizedText, safeLanguage, voiceName);
                } catch (RuntimeException error) {
                    lastFailure = error;
                    LOGGER.warn("Gemini summary speech failed with model {} on attempt {}: {}",
                            model, attempt, error.getMessage());
                    if (attempt < maxAttempts) {
                        sleepBeforeRetry();
                    }
                }
            }
        }

        throw lastFailure != null
                ? lastFailure
                : new IllegalStateException("Unable to synthesize summary audio");
    }

    private List<String> resolveTtsModels() {
        LinkedHashSet<String> models = new LinkedHashSet<>();
        models.add(defaultIfBlank(props.getGemini().getTtsModel(), "gemini-3.1-flash-tts-preview"));
        models.add(defaultIfBlank(props.getGemini().getTtsFallbackModel(), "gemini-2.5-flash-preview-tts"));
        return List.copyOf(models);
    }

    private String resolveVoiceName(String requestedVoice, String languageCode) {
        if (StringUtils.hasText(requestedVoice)) {
            String trimmedVoice = requestedVoice.trim();
            if (SUPPORTED_VOICE_NAMES.contains(trimmedVoice)) {
                return trimmedVoice;
            }
        }
        return DEFAULT_VOICE_BY_LANGUAGE.getOrDefault(languageCode, "Achird");
    }

    private byte[] requestSpeechAudio(String model, String text, String languageCode, String voiceName) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            String requestJson = objectMapper.writeValueAsString(buildTtsRequest(text, languageCode, voiceName));
            HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    buildTtsUrl(model),
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            JsonNode root = objectMapper.readTree(response.getBody());
            String base64Audio = root.path("candidates")
                    .path(0)
                    .path("content")
                    .path("parts")
                    .path(0)
                    .path("inlineData")
                    .path("data")
                    .asText("");

            if (!StringUtils.hasText(base64Audio)) {
                throw new IllegalStateException("Gemini TTS response did not contain audio data");
            }

            byte[] pcmAudio = java.util.Base64.getDecoder().decode(base64Audio);
            return wrapPcmAsWav(pcmAudio);
        } catch (Exception error) {
            throw new RuntimeException("Gemini TTS request failed: " + error.getMessage(), error);
        }
    }

    private Map<String, Object> buildTtsRequest(String text, String languageCode, String voiceName) {
        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("text", buildSpeechPrompt(text, languageCode));

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("parts", List.of(textPart));

        Map<String, Object> prebuiltVoiceConfig = new LinkedHashMap<>();
        prebuiltVoiceConfig.put("voiceName", voiceName);

        Map<String, Object> voiceConfig = new LinkedHashMap<>();
        voiceConfig.put("prebuiltVoiceConfig", prebuiltVoiceConfig);

        Map<String, Object> speechConfig = new LinkedHashMap<>();
        speechConfig.put("voiceConfig", voiceConfig);

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("responseModalities", List.of("AUDIO"));
        generationConfig.put("speechConfig", speechConfig);

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("contents", List.of(content));
        requestBody.put("generationConfig", generationConfig);
        return requestBody;
    }

    private String buildSpeechPrompt(String text, String languageCode) {
        String styleTag = switch (languageCode) {
            case "hi", "mr", "ta", "te" -> "[calm] [very clear] [gentle] [medium pace]";
            default -> "[calm] [clear] [soft] [medium pace]";
        };

        return styleTag + " " + text;
    }

    private byte[] wrapPcmAsWav(byte[] pcmAudio) {
        int byteRate = SAMPLE_RATE * CHANNEL_COUNT * BITS_PER_SAMPLE / 8;
        int blockAlign = CHANNEL_COUNT * BITS_PER_SAMPLE / 8;
        int dataLength = pcmAudio.length;
        int riffChunkLength = 36 + dataLength;

        try (ByteArrayOutputStream output = new ByteArrayOutputStream(44 + dataLength)) {
            writeAscii(output, "RIFF");
            writeLittleEndianInt(output, riffChunkLength);
            writeAscii(output, "WAVE");
            writeAscii(output, "fmt ");
            writeLittleEndianInt(output, 16);
            writeLittleEndianShort(output, (short) 1);
            writeLittleEndianShort(output, (short) CHANNEL_COUNT);
            writeLittleEndianInt(output, SAMPLE_RATE);
            writeLittleEndianInt(output, byteRate);
            writeLittleEndianShort(output, (short) blockAlign);
            writeLittleEndianShort(output, (short) BITS_PER_SAMPLE);
            writeAscii(output, "data");
            writeLittleEndianInt(output, dataLength);
            output.write(pcmAudio);
            return output.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException("Unable to build wav audio", error);
        }
    }

    private void writeAscii(ByteArrayOutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.US_ASCII));
    }

    private void writeLittleEndianInt(ByteArrayOutputStream output, int value) throws IOException {
        output.write(value & 0xFF);
        output.write((value >> 8) & 0xFF);
        output.write((value >> 16) & 0xFF);
        output.write((value >> 24) & 0xFF);
    }

    private void writeLittleEndianShort(ByteArrayOutputStream output, short value) throws IOException {
        output.write(value & 0xFF);
        output.write((value >> 8) & 0xFF);
    }

    private String buildTtsUrl(String model) {
        String baseUrl = defaultIfBlank(props.getGemini().getBaseUrl(),
                "https://generativelanguage.googleapis.com/v1beta/models");
        return baseUrl + "/" + model + ":generateContent?key=" + props.getGemini().getApiKey();
    }

    private String defaultIfBlank(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(Math.max(0, props.getGemini().getRetryDelayMs()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private String normalizeText(String rawText) {
        return rawText == null ? "" : rawText.replaceAll("\\s+", " ").trim();
    }
}
