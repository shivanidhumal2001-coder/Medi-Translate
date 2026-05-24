package com.meditranslate.config;

import com.meditranslate.service.AiAnalysisService;
import com.meditranslate.service.impl.ClaudeAiService;
import com.meditranslate.service.impl.GeminiAiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AiServiceConfig — THE KEY FIX.
 *
 * Previously, ClaudeAiService was hardwired as @Service, so it was always
 * used regardless of medi.translate.claude.enabled=false.
 *
 * This config class reads the enabled flags from application.properties
 * and creates exactly ONE AiAnalysisService bean: either Gemini or Claude.
 *
 * Priority:
 *   1. If claude.enabled=true  → use Claude
 *   2. If gemini.enabled=true  → use Gemini   ← YOUR CURRENT SETTING
 *   3. If neither enabled      → throw error with helpful message
 */
@Configuration
public class AiServiceConfig {

    private static final Logger log = LoggerFactory.getLogger(AiServiceConfig.class);

    @Bean
    public AiAnalysisService aiAnalysisService(MediTranslateProperties props) {

        boolean claudeEnabled = props.getClaude().isEnabled()
                && props.getClaude().getApiKey() != null
                && !props.getClaude().getApiKey().isBlank();

        boolean geminiEnabled = props.getGemini().isEnabled()
                && props.getGemini().getApiKey() != null
                && !props.getGemini().getApiKey().isBlank()
                && !props.getGemini().getApiKey().equals("YOUR_GEMINI_KEY_HERE");

        if (claudeEnabled) {
            log.info("✅ AI Service: Using CLAUDE (model: {})", props.getClaude().getModel());
            return new ClaudeAiService(props);
        }

        if (geminiEnabled) {
            log.info("✅ AI Service: Using GEMINI (model: {})", props.getGemini().getModel());
            return new GeminiAiService(props);
        }

        // Neither enabled — give a very clear error message
        throw new IllegalStateException("""
            ❌ No AI service is configured!
            
            In your application.properties, do ONE of the following:
            
            OPTION A — Use Gemini (FREE, no credit card):
              medi.translate.gemini.enabled=true
              medi.translate.gemini.api-key=YOUR_KEY_FROM_aistudio.google.com
            
            OPTION B — Use Claude (paid, needs credits):
              medi.translate.claude.enabled=true
              medi.translate.claude.api-key=sk-ant-YOUR_KEY
            """);
    }
}