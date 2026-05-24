const summaryText = document.getElementById("summaryText");
const summaryLanguage = document.getElementById("summaryLanguage");
const summaryVoice = document.getElementById("summaryVoice");
const speakSummaryButton = document.getElementById("speakSummary");
const chatForm = document.getElementById("chatForm");
const chatQuestion = document.getElementById("chatQuestion");
const chatMessages = document.getElementById("chatMessages");
const symptomForm = document.getElementById("symptomForm");
const symptomInput = document.getElementById("symptomInput");
const symptomResults = document.getElementById("symptomResults");
const reminderForm = document.getElementById("reminderForm");
const prescriptionText = document.getElementById("prescriptionText");
const reminderTable = document.getElementById("reminderTable")?.querySelector("tbody");
const summaryLanguageStatus = document.getElementById("summaryLanguageStatus");
const speechSynthesisApi = "speechSynthesis" in window ? window.speechSynthesis : null;
const SERVER_SUMMARY_AUDIO_TIMEOUT_MS = 15000;
let activeSummaryUtterance = null;
let activeSummaryAudio = null;
let activeSummaryAudioUrl = null;
let activeSummaryAbortController = null;
let summarySpeechSessionId = 0;
let availableSpeechVoices = [];
const summaryNeuralVoiceOptions = [
    { value: "Iapetus", label: "Clear" },
    { value: "Erinome", label: "Clear Plus" },
    { value: "Achernar", label: "Soft" },
    { value: "Algieba", label: "Smooth" },
    { value: "Vindemiatrix", label: "Gentle" },
    { value: "Sulafat", label: "Warm" }
];
const defaultNeuralVoiceByLanguage = {
    en: "Algieba",
    hi: "Iapetus",
    mr: "Iapetus",
    ta: "Iapetus",
    te: "Iapetus"
};
const originalSummaryHtml = summaryText?.innerHTML ?? "";
const originalSummaryLanguage = summaryLanguage?.value ?? "en";
const originalSummaryOverview = summaryText?.querySelector(".rs-overview p")?.textContent?.trim()
    || summaryText?.textContent?.trim()
    || "";

const csrfToken = document.querySelector('meta[name="_csrf"]')?.getAttribute("content");
const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.getAttribute("content");

function getHeaders() {
    return {
        "Content-Type": "application/json",
        [csrfHeader]: csrfToken
    };
}

// Render markdown in historical bot messages loaded from server
document.addEventListener("DOMContentLoaded", () => {
    if (typeof marked === "undefined") return;
    document.querySelectorAll(".chat-bubble.bot.markdown-content").forEach(el => {
        const raw = el.getAttribute("data-raw");
        if (raw) el.innerHTML = marked.parse(raw);
    });
});

function appendMessage(message, type) {
    const bubble = document.createElement("div");
    bubble.className = `chat-bubble ${type}`;
    if (type === "bot" && typeof marked !== "undefined") {
        bubble.innerHTML = marked.parse(message);
    } else {
        bubble.textContent = message;
    }
    chatMessages.appendChild(bubble);
    chatMessages.scrollTop = chatMessages.scrollHeight;
}

function setSpeakButtonState(isSpeaking) {
    if (!speakSummaryButton) {
        return;
    }

    speakSummaryButton.textContent = isSpeaking ? "Stop" : "Speak";
    speakSummaryButton.setAttribute("aria-pressed", String(isSpeaking));
    speakSummaryButton.classList.toggle("is-active", isSpeaking);
}

function setSummaryLoadingState(isLoading) {
    if (summaryLanguageStatus) {
        summaryLanguageStatus.hidden = !isLoading;
    }
    if (summaryText) {
        summaryText.classList.toggle("is-loading", isLoading);
    }
}

function stopSummarySpeech() {
    summarySpeechSessionId += 1;
    if (activeSummaryAbortController) {
        activeSummaryAbortController.abort();
        activeSummaryAbortController = null;
    }
    if (activeSummaryAudio) {
        activeSummaryAudio.pause();
        activeSummaryAudio.onended = null;
        activeSummaryAudio.onerror = null;
        activeSummaryAudio.src = "";
        activeSummaryAudio = null;
    }
    if (activeSummaryAudioUrl) {
        URL.revokeObjectURL(activeSummaryAudioUrl);
        activeSummaryAudioUrl = null;
    }
    if (speechSynthesisApi) {
        speechSynthesisApi.cancel();
    }
    activeSummaryUtterance = null;
    setSpeakButtonState(false);
}

function getSpeechLocales(languageCode) {
    return {
        en: ["en-IN", "en-GB", "en-US", "en"],
        hi: ["hi-IN", "hi"],
        mr: ["mr-IN", "mr", "hi-IN", "hi"],
        ta: ["ta-IN", "ta"],
        te: ["te-IN", "te"]
    }[languageCode] || ["en-IN", "en"];
}

function getSpeechLanguage(languageCode) {
    return getSpeechLocales(languageCode)[0];
}

function getSpeechSettings(languageCode) {
    return {
        en: { rate: 0.96, pitch: 1.0, volume: 1.0 },
        hi: { rate: 0.9, pitch: 1.0, volume: 1.0 },
        mr: { rate: 0.9, pitch: 1.0, volume: 1.0 },
        ta: { rate: 0.88, pitch: 1.0, volume: 1.0 },
        te: { rate: 0.88, pitch: 1.0, volume: 1.0 }
    }[languageCode] || { rate: 0.94, pitch: 1.0, volume: 1.0 };
}

function getVoiceKey(voice) {
    return `${voice.name}__${voice.lang}`;
}

function refreshSpeechVoices() {
    if (!speechSynthesisApi) {
        return;
    }

    availableSpeechVoices = speechSynthesisApi.getVoices()
        .slice()
        .sort((left, right) => left.name.localeCompare(right.name));
}

function getLanguageBaseCodes(languageCode) {
    return [...new Set(
        getSpeechLocales(languageCode)
            .map(locale => locale.toLowerCase().split("-")[0])
            .filter(Boolean)
    )];
}

function isVoiceCompatible(voice, languageCode) {
    const voiceLang = (voice.lang || "").toLowerCase();
    const preferredLocales = getSpeechLocales(languageCode).map(locale => locale.toLowerCase());
    const baseCodes = getLanguageBaseCodes(languageCode);

    return preferredLocales.some(locale => voiceLang === locale || voiceLang.startsWith(`${locale}-`))
        || baseCodes.some(baseCode => voiceLang === baseCode || voiceLang.startsWith(`${baseCode}-`));
}

function scoreVoice(voice, languageCode) {
    const voiceLang = (voice.lang || "").toLowerCase();
    const voiceName = (voice.name || "").toLowerCase();
    const preferredLocales = getSpeechLocales(languageCode).map(locale => locale.toLowerCase());
    const baseCodes = getLanguageBaseCodes(languageCode);
    let score = 0;

    preferredLocales.forEach((locale, index) => {
        if (voiceLang === locale) {
            score += 140 - (index * 10);
        } else if (voiceLang.startsWith(`${locale}-`)) {
            score += 120 - (index * 10);
        }
    });

    baseCodes.forEach((baseCode, index) => {
        if (voiceLang === baseCode || voiceLang.startsWith(`${baseCode}-`)) {
            score += 90 - (index * 6);
        }
    });

    if (voice.default) {
        score += 8;
    }
    if (voice.localService) {
        score += 6;
    }
    if (/(google|microsoft|natural|neural|online|premium|enhanced)/.test(voiceName)) {
        score += 28;
    }
    if (/india|indian/.test(voiceName)) {
        score += 10;
    }
    if (/(espeak|festival|sam)/.test(voiceName)) {
        score -= 24;
    }

    return score;
}

function getCompatibleVoices(languageCode) {
    const compatibleVoices = availableSpeechVoices.filter(voice => isVoiceCompatible(voice, languageCode));
    return compatibleVoices.sort((left, right) => {
        const scoreDifference = scoreVoice(right, languageCode) - scoreVoice(left, languageCode);
        return scoreDifference !== 0
            ? scoreDifference
            : left.name.localeCompare(right.name);
    });
}

function refreshSummaryVoiceOptions() {
    if (!summaryVoice) {
        return;
    }

    const previousValue = summaryVoice.value;
    const currentLanguage = summaryLanguage?.value || originalSummaryLanguage;
    const defaultVoice = defaultNeuralVoiceByLanguage[currentLanguage] || "Achird";

    summaryVoice.innerHTML = "";
    summaryNeuralVoiceOptions.forEach((voiceOption) => {
        const option = document.createElement("option");
        option.value = voiceOption.value;
        option.textContent = voiceOption.label;
        summaryVoice.appendChild(option);
    });

    const hasPreviousValue = previousValue && summaryNeuralVoiceOptions.some(voice => voice.value === previousValue);
    summaryVoice.value = hasPreviousValue ? previousValue : defaultVoice;
    summaryVoice.hidden = summaryNeuralVoiceOptions.length < 2;
}

function getSelectedNeuralVoice(languageCode) {
    if (summaryVoice?.value) {
        return summaryVoice.value;
    }
    return defaultNeuralVoiceByLanguage[languageCode] || "Achird";
}

function getSelectedSummaryVoice(languageCode) {
    const compatibleVoices = getCompatibleVoices(languageCode);
    if (!compatibleVoices.length) {
        return null;
    }

    if (summaryVoice?.value) {
        const selectedVoice = compatibleVoices.find(voice => getVoiceKey(voice) === summaryVoice.value);
        if (selectedVoice) {
            return selectedVoice;
        }
    }

    return compatibleVoices[0];
}

function normalizeSpeechText(rawText) {
    return rawText
        .replace(/[•]/g, ". ")
        .replace(/[⚠️✅📋🔬❓💊🛡️💬🚨🆘ℹ️]/g, " ")
        .replace(/\s+/g, " ")
        .replace(/\s([,.;!?।])/g, "$1")
        .trim();
}

function buildSummarySpeechText() {
    if (!summaryText) {
        return "";
    }

    const overview = summaryText.querySelector(".rs-overview p")?.textContent?.trim() || "";
    const sectionParagraphs = Array.from(summaryText.querySelectorAll(".rs-block > p:last-child"))
        .map(paragraph => paragraph.textContent?.trim() || "")
        .filter(Boolean);
    const urgencyReason = summaryText.querySelector(".rs-urgency p")?.textContent?.trim() || "";

    const transcriptParts = [];
    if (overview) {
        transcriptParts.push(overview);
    }
    sectionParagraphs.slice(0, 3).forEach((paragraph) => {
        transcriptParts.push(paragraph);
    });
    if (urgencyReason) {
        transcriptParts.push(urgencyReason);
    }

    if (!transcriptParts.length) {
        return normalizeSpeechText(summaryText.textContent.trim());
    }

    return normalizeSpeechText(
        transcriptParts
            .map(part => part.endsWith(".") || part.endsWith("!") || part.endsWith("?") || part.endsWith("।")
                ? part
                : `${part}.`)
            .join(" ")
    );
}

function splitLongSpeechSegment(segment, maxLength) {
    const words = segment.split(/\s+/).filter(Boolean);
    const chunks = [];
    let currentChunk = "";

    words.forEach((word) => {
        const candidate = currentChunk ? `${currentChunk} ${word}` : word;
        if (candidate.length <= maxLength) {
            currentChunk = candidate;
            return;
        }

        if (currentChunk) {
            chunks.push(currentChunk);
        }
        currentChunk = word;
    });

    if (currentChunk) {
        chunks.push(currentChunk);
    }

    return chunks;
}

function buildSpeechChunks(textToRead, maxLength = 260) {
    const normalizedText = normalizeSpeechText(textToRead);
    if (!normalizedText) {
        return [];
    }

    const sentenceLikeParts = normalizedText
        .split(/(?<=[.!?।])\s+/u)
        .map(part => part.trim())
        .filter(Boolean);

    const chunks = [];
    let currentChunk = "";

    sentenceLikeParts.forEach((part) => {
        const candidate = currentChunk ? `${currentChunk} ${part}` : part;
        if (candidate.length <= maxLength) {
            currentChunk = candidate;
            return;
        }

        if (currentChunk) {
            chunks.push(currentChunk);
        }

        if (part.length <= maxLength) {
            currentChunk = part;
            return;
        }

        const splitParts = splitLongSpeechSegment(part, maxLength);
        chunks.push(...splitParts.slice(0, -1));
        currentChunk = splitParts[splitParts.length - 1] || "";
    });

    if (currentChunk) {
        chunks.push(currentChunk);
    }

    return chunks;
}

async function playServerSummaryAudio(textToRead, languageCode, sessionId) {
    const responseBody = {
        text: textToRead,
        language: languageCode,
        voice: getSelectedNeuralVoice(languageCode)
    };
    const abortController = new AbortController();
    activeSummaryAbortController = abortController;
    const timeoutId = window.setTimeout(() => abortController.abort(), SERVER_SUMMARY_AUDIO_TIMEOUT_MS);

    try {
        const response = await fetch(`/reports/${window.reportId}/summary/audio`, {
            method: "POST",
            headers: getHeaders(),
            body: JSON.stringify(responseBody),
            signal: abortController.signal
        });

        if (!response.ok) {
            return false;
        }

        const audioBlob = await response.blob();
        if (sessionId !== summarySpeechSessionId || !audioBlob.size) {
            return false;
        }

        if (activeSummaryAudio) {
            activeSummaryAudio.pause();
            activeSummaryAudio.onended = null;
            activeSummaryAudio.onerror = null;
            activeSummaryAudio.src = "";
            activeSummaryAudio = null;
        }
        if (activeSummaryAudioUrl) {
            URL.revokeObjectURL(activeSummaryAudioUrl);
            activeSummaryAudioUrl = null;
        }

        const audioUrl = URL.createObjectURL(audioBlob);
        const audio = new Audio(audioUrl);
        activeSummaryAudio = audio;
        activeSummaryAudioUrl = audioUrl;
        activeSummaryAbortController = null;

        audio.onended = () => {
            if (sessionId !== summarySpeechSessionId) {
                return;
            }
            if (activeSummaryAudio) {
                activeSummaryAudio = null;
            }
            if (activeSummaryAudioUrl) {
                URL.revokeObjectURL(activeSummaryAudioUrl);
                activeSummaryAudioUrl = null;
            }
            setSpeakButtonState(false);
        };

        audio.onerror = () => {
            if (sessionId !== summarySpeechSessionId) {
                return;
            }
            if (activeSummaryAudio) {
                activeSummaryAudio = null;
            }
            if (activeSummaryAudioUrl) {
                URL.revokeObjectURL(activeSummaryAudioUrl);
                activeSummaryAudioUrl = null;
            }
            setSpeakButtonState(false);
        };

        try {
            await audio.play();
            return true;
        } catch (error) {
            audio.onended = null;
            audio.onerror = null;
            audio.pause();
            audio.src = "";
            if (activeSummaryAudio === audio) {
                activeSummaryAudio = null;
            }
            if (activeSummaryAudioUrl === audioUrl) {
                URL.revokeObjectURL(activeSummaryAudioUrl);
                activeSummaryAudioUrl = null;
            }
            throw error;
        }
    } finally {
        window.clearTimeout(timeoutId);
        if (activeSummaryAbortController === abortController) {
            activeSummaryAbortController = null;
        }
    }
}

function speakChunks(chunks, languageCode, sessionId, voice, settings, chunkIndex = 0) {
    if (!speechSynthesisApi || sessionId !== summarySpeechSessionId) {
        return;
    }

    if (chunkIndex >= chunks.length) {
        activeSummaryUtterance = null;
        setSpeakButtonState(false);
        return;
    }

    const utterance = new SpeechSynthesisUtterance(chunks[chunkIndex]);
    utterance.lang = voice?.lang || getSpeechLanguage(languageCode);
    utterance.rate = settings.rate;
    utterance.pitch = settings.pitch;
    utterance.volume = settings.volume;
    if (voice) {
        utterance.voice = voice;
    }

    activeSummaryUtterance = utterance;

    utterance.onend = () => {
        if (sessionId !== summarySpeechSessionId) {
            return;
        }
        speakChunks(chunks, languageCode, sessionId, voice, settings, chunkIndex + 1);
    };

    utterance.onerror = () => {
        if (sessionId !== summarySpeechSessionId) {
            return;
        }
        activeSummaryUtterance = null;
        setSpeakButtonState(false);
    };

    speechSynthesisApi.speak(utterance);
}

function getTranslatedSummary(languageCode) {
    if (languageCode === originalSummaryLanguage) {
        return originalSummaryOverview;
    }

    const translations = window.reportTranslations || {};
    return translations[languageCode]
        || translations[originalSummaryLanguage]
        || translations.en
        || originalSummaryOverview;
}

function isRichSummaryHtml(value) {
    return typeof value === "string" && value.includes("rs-overview");
}

async function ensureRichSummaryTranslation(languageCode) {
    if (languageCode === originalSummaryLanguage) {
        return originalSummaryHtml;
    }

    const existing = window.reportTranslations?.[languageCode];
    if (isRichSummaryHtml(existing)) {
        return existing;
    }

    try {
        const response = await fetch(`/reports/${window.reportId}/summary?language=${encodeURIComponent(languageCode)}`);
        if (!response.ok) {
            throw new Error(`Summary translation request failed with status ${response.status}`);
        }

        const payload = await response.json();
        if (!window.reportTranslations) {
            window.reportTranslations = {};
        }
        if (payload.summaryHtml) {
            window.reportTranslations[languageCode] = payload.summaryHtml;
            return payload.summaryHtml;
        }
    } catch (error) {
        console.error("Unable to load translated summary", error);
    }

    return existing;
}

function applySummaryLanguage(languageCode) {
    if (!summaryText) {
        return;
    }

    if (languageCode === originalSummaryLanguage || !originalSummaryHtml) {
        summaryText.innerHTML = originalSummaryHtml || originalSummaryOverview;
        return;
    }

    const translatedSummary = getTranslatedSummary(languageCode);
    if (isRichSummaryHtml(translatedSummary)) {
        summaryText.innerHTML = translatedSummary;
        return;
    }

    summaryText.innerHTML = originalSummaryHtml;

    const overviewParagraph = summaryText.querySelector(".rs-overview p");
    if (overviewParagraph) {
        overviewParagraph.textContent = translatedSummary;
        return;
    }

    summaryText.textContent = translatedSummary;
}

if (summaryLanguage && summaryText) {
    summaryLanguage.addEventListener("change", async () => {
        const selectedLanguage = summaryLanguage.value;
        stopSummarySpeech();
        refreshSummaryVoiceOptions();
        summaryLanguage.disabled = true;
        setSummaryLoadingState(true);
        try {
            await ensureRichSummaryTranslation(selectedLanguage);
            applySummaryLanguage(selectedLanguage);
        } finally {
            setSummaryLoadingState(false);
            summaryLanguage.disabled = false;
        }
    });
}

if (speechSynthesisApi) {
    refreshSpeechVoices();
    if (typeof speechSynthesisApi.addEventListener === "function") {
        speechSynthesisApi.addEventListener("voiceschanged", refreshSpeechVoices);
    } else if ("onvoiceschanged" in speechSynthesisApi) {
        speechSynthesisApi.onvoiceschanged = refreshSpeechVoices;
    }
}
refreshSummaryVoiceOptions();

if (speakSummaryButton && summaryText) {
    speakSummaryButton.addEventListener("click", async () => {
        if (activeSummaryAbortController || activeSummaryAudio || speechSynthesisApi?.speaking || speechSynthesisApi?.pending || activeSummaryUtterance) {
            stopSummarySpeech();
            return;
        }

        const textToRead = buildSummarySpeechText();
        if (!textToRead) {
            return;
        }

        const selectedLanguage = summaryLanguage?.value || originalSummaryLanguage;
        const sessionId = ++summarySpeechSessionId;
        setSpeakButtonState(true);

        try {
            const playedNeuralAudio = await playServerSummaryAudio(textToRead, selectedLanguage, sessionId);
            if (playedNeuralAudio || sessionId !== summarySpeechSessionId) {
                return;
            }
        } catch (error) {
            if (error?.name === "AbortError") {
                console.warn("Neural summary audio timed out, falling back to browser speech.");
            } else {
                console.error("Unable to generate neural summary audio", error);
            }
            if (sessionId !== summarySpeechSessionId) {
                return;
            }
        }

        if (!speechSynthesisApi) {
            setSpeakButtonState(false);
            alert("Natural voice is unavailable right now.");
            return;
        }

        refreshSpeechVoices();
        const selectedVoice = getSelectedSummaryVoice(selectedLanguage);
        const speechSettings = getSpeechSettings(selectedLanguage);
        const speechChunks = buildSpeechChunks(textToRead);
        if (!speechChunks.length) {
            setSpeakButtonState(false);
            return;
        }

        speakChunks(speechChunks, selectedLanguage, sessionId, selectedVoice, speechSettings);
    });
}

window.addEventListener("beforeunload", stopSummarySpeech);

const chatSendBtn = document.getElementById("chatSendBtn");
const chatBtnLabel = document.getElementById("chatBtnLabel");
const chatBtnSpinner = document.getElementById("chatBtnSpinner");
const clearChatBtn = document.getElementById("clearChat");

function setChatLoading(isLoading) {
    if (chatSendBtn) chatSendBtn.disabled = isLoading;
    if (chatQuestion) chatQuestion.disabled = isLoading;
    if (chatBtnLabel) chatBtnLabel.hidden = isLoading;
    if (chatBtnSpinner) chatBtnSpinner.hidden = !isLoading;
}

function appendTypingIndicator() {
    const el = document.createElement("div");
    el.className = "chat-bubble bot typing-indicator";
    el.id = "typingIndicator";
    el.innerHTML = "<span></span><span></span><span></span>";
    chatMessages.appendChild(el);
    chatMessages.scrollTop = chatMessages.scrollHeight;
}

function removeTypingIndicator() {
    document.getElementById("typingIndicator")?.remove();
}

async function submitChatQuestion() {
    const question = chatQuestion.value.trim();
    if (!question) return;

    appendMessage(question, "user");
    chatQuestion.value = "";
    chatQuestion.style.height = "auto";
    setChatLoading(true);
    appendTypingIndicator();

    try {
        const response = await fetch(`/bot/${window.reportId}/ask`, {
            method: "POST",
            headers: getHeaders(),
            body: JSON.stringify({ question })
        });
        const payload = await response.json();
        removeTypingIndicator();
        appendMessage(payload.answer || "MediBot could not respond right now.", "bot");
    } catch (err) {
        removeTypingIndicator();
        appendMessage("⚠️ Connection error. Please try again.", "bot");
    } finally {
        setChatLoading(false);
        chatQuestion.focus();
    }
}

if (chatForm && chatQuestion && chatMessages) {
    chatForm.addEventListener("submit", async (event) => {
        event.preventDefault();
        await submitChatQuestion();
    });

    // Enter to send, Shift+Enter for newline
    chatQuestion.addEventListener("keydown", (e) => {
        if (e.key === "Enter" && !e.shiftKey) {
            e.preventDefault();
            submitChatQuestion();
        }
    });

    // Auto-resize textarea as user types
    chatQuestion.addEventListener("input", () => {
        chatQuestion.style.height = "auto";
        chatQuestion.style.height = Math.min(chatQuestion.scrollHeight, 140) + "px";
    });
}

if (clearChatBtn && chatMessages) {
    clearChatBtn.addEventListener("click", () => {
        const bubbles = chatMessages.querySelectorAll(".chat-bubble:not(:first-child)");
        bubbles.forEach(b => b.remove());
    });
}

if (symptomForm && symptomInput && symptomResults) {
    symptomForm.addEventListener("submit", async (event) => {
        event.preventDefault();
        symptomResults.innerHTML = "";
        const symptoms = symptomInput.value.trim();
        if (!symptoms) {
            return;
        }

        const response = await fetch(`/bot/${window.reportId}/symptoms`, {
            method: "POST",
            headers: getHeaders(),
            body: JSON.stringify({ symptoms })
        });
        const payload = await response.json();

        payload.forEach((item) => {
            const card = document.createElement("div");
            card.className = "result-chip";
            const related = item.relatedFindings?.length
                ? item.relatedFindings.join("\n")
                : "No strong abnormal finding link detected.";
            card.innerHTML = `<strong>${item.symptom}</strong><p>${item.guidance}</p><pre>${related}</pre>`;
            symptomResults.appendChild(card);
        });
    });
}

if (reminderForm && prescriptionText && reminderTable) {
    reminderForm.addEventListener("submit", async (event) => {
        event.preventDefault();
        const text = prescriptionText.value.trim();
        if (!text) {
            return;
        }

        const response = await fetch(`/bot/${window.reportId}/reminders`, {
            method: "POST",
            headers: getHeaders(),
            body: JSON.stringify({ prescriptionText: text })
        });
        const payload = await response.json();

        reminderTable.innerHTML = "";
        payload.forEach((item) => {
            const row = document.createElement("tr");
            row.innerHTML = `
                <td>${item.medicineName ?? "-"}</td>
                <td>${item.dosage ?? "-"}</td>
                <td>${item.scheduleText ?? "-"}</td>
                <td>${item.mealInstruction ?? "-"}</td>
                <td>${item.durationText ?? "-"}</td>
            `;
            reminderTable.appendChild(row);
        });
    });
}
