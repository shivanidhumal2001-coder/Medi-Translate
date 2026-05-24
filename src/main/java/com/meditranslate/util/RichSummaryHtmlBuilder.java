package com.meditranslate.util;

import java.util.List;

public final class RichSummaryHtmlBuilder {

    private RichSummaryHtmlBuilder() {
    }

    public static String buildHtml(String overview,
                                   String whatIsHappening,
                                   String whyDidThisHappen,
                                   String howToImprove,
                                   String urgencyLevel,
                                   String urgencyReason,
                                   Double trustScore,
                                   String trustReason,
                                   List<String> suggestedQuestions) {
        StringBuilder sb = new StringBuilder();

        if (hasText(overview)) {
            sb.append("<div class=\"rs-overview\">")
                    .append("<span class=\"rs-icon\">📋</span>")
                    .append("<p>").append(escapeHtml(overview)).append("</p>")
                    .append("</div>");
        }

        appendBlock(sb, whatIsHappening, "🔬", "What is Happening");
        appendBlock(sb, whyDidThisHappen, "❓", "Why Did This Happen");
        appendBlock(sb, howToImprove, "💊", "How to Improve");

        if (hasText(urgencyReason)) {
            String normalizedUrgency = hasText(urgencyLevel) ? urgencyLevel.trim().toUpperCase() : "URGENCY";
            String colour = "HIGH".equals(normalizedUrgency) ? "#e53e3e"
                    : "MEDIUM".equals(normalizedUrgency) ? "#dd6b20"
                    : "LOW".equals(normalizedUrgency) ? "#276749"
                    : "CRITICAL".equals(normalizedUrgency) ? "#c53030"
                    : "#0f766e";
            String emoji = "HIGH".equals(normalizedUrgency) ? "🚨"
                    : "MEDIUM".equals(normalizedUrgency) ? "⚠️"
                    : "LOW".equals(normalizedUrgency) ? "✅"
                    : "CRITICAL".equals(normalizedUrgency) ? "🆘"
                    : "ℹ️";
            sb.append("<div class=\"rs-urgency\" style=\"border-left:4px solid ").append(colour).append("\">")
                    .append("<span class=\"rs-urgency-badge\" style=\"background:").append(colour).append("\">")
                    .append(emoji).append(" ").append(escapeHtml(normalizedUrgency))
                    .append("</span>")
                    .append("<p>").append(escapeHtml(urgencyReason)).append("</p>")
                    .append("</div>");
        }

        if (trustScore != null) {
            int percent = (int) Math.round(trustScore * 100);
            String colour = percent >= 80 ? "#276749" : percent >= 50 ? "#dd6b20" : "#e53e3e";
            sb.append("<div class=\"rs-trust\">")
                    .append("<span class=\"rs-icon\">🛡️</span>")
                    .append("<div class=\"rs-trust-body\">")
                    .append("<span class=\"rs-label\">AI Confidence Score: ")
                    .append("<strong style=\"color:").append(colour).append("\">").append(percent).append("%</strong></span>");
            if (hasText(trustReason)) {
                sb.append("<p class=\"rs-trust-reason\">").append(escapeHtml(trustReason)).append("</p>");
            }
            sb.append("</div></div>");
        }

        if (suggestedQuestions != null) {
            List<String> filtered = suggestedQuestions.stream()
                    .filter(RichSummaryHtmlBuilder::hasText)
                    .map(String::trim)
                    .toList();
            if (!filtered.isEmpty()) {
                sb.append("<div class=\"rs-questions\">")
                        .append("<p class=\"rs-label\"><span class=\"rs-icon\">💬</span> Questions to ask your doctor:</p>")
                        .append("<ul>");
                for (String question : filtered) {
                    sb.append("<li>").append(escapeHtml(question)).append("</li>");
                }
                sb.append("</ul></div>");
            }
        }

        return sb.toString();
    }

    public static boolean looksLikeRichHtml(String value) {
        return hasText(value) && value.contains("rs-overview");
    }

    private static void appendBlock(StringBuilder sb, String value, String emoji, String label) {
        if (!hasText(value)) {
            return;
        }
        sb.append("<div class=\"rs-block\">")
                .append("<p class=\"rs-block-title\"><span class=\"rs-icon\">").append(emoji).append("</span>")
                .append(escapeHtml(label)).append("</p>")
                .append("<p>").append(escapeHtml(value)).append("</p>")
                .append("</div>");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
