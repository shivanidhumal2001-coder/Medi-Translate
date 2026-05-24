const reportFileInput  = document.getElementById("reportFile");
const filePreview      = document.getElementById("filePreview");
const typedText        = document.getElementById("typedText");
const analyzeBtn       = document.getElementById("analyzeBtn");
const analyzeBtnLabel  = document.getElementById("analyzeBtnLabel");
const analyzeBtnSpinner= document.getElementById("analyzeBtnSpinner");
const analyzingOverlay = document.getElementById("analyzingOverlay");

// ── File preview label ────────────────────────────────────────
if (reportFileInput && filePreview) {
    reportFileInput.addEventListener("change", () => {
        const file = reportFileInput.files?.[0];
        filePreview.textContent = file
            ? `✅ Selected: ${file.name}`
            : "Supports image OCR and document text extraction.";
    });
}

// ── Auto-grow textarea ────────────────────────────────────────
if (typedText) {
    typedText.addEventListener("input", () => {
        typedText.style.height = "auto";
        typedText.style.height = Math.min(typedText.scrollHeight, 400) + "px";
    });
}

// ── Form submit → show overlay ────────────────────────────────
const uploadForm = document.querySelector(".upload-form");

if (uploadForm) {
    uploadForm.addEventListener("submit", () => {
        const hasText = typedText?.value?.trim().length > 0;
        const hasFile = reportFileInput?.files?.length > 0;
        if (!hasText && !hasFile) return;

        if (analyzeBtn)        analyzeBtn.disabled          = true;
        if (analyzeBtnLabel)   analyzeBtnLabel.textContent  = "Analyzing...";
        if (analyzeBtnSpinner) analyzeBtnSpinner.hidden      = false;
        if (analyzingOverlay)  analyzingOverlay.hidden       = false;

        animateSteps();
    });
}

// ── Animate step items one by one ────────────────────────────
function animateSteps() {
    const delays = [0, 3000, 8000, 13000];
    ["step1","step2","step3","step4"].forEach((id, i) => {
        setTimeout(() => {
            document.getElementById(id)?.classList.add("step-active");
        }, delays[i]);
    });
}