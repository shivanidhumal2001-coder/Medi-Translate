package com.meditranslate.service.impl;

import com.meditranslate.dto.ParsedFindingCandidate;
import com.meditranslate.service.FindingParserService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RegexFindingParserService implements FindingParserService {

    private static final Pattern NUMBER_PATTERN = Pattern.compile("(?<![A-Za-z0-9])[<>]?(\\d+(?:\\.\\d+)?)(?![A-Za-z0-9])");
    private static final Pattern RANGE_PATTERN = Pattern.compile("(?<![A-Za-z0-9])(\\d+(?:\\.\\d+)?)\\s*(?:-|to|–|—)\\s*(\\d+(?:\\.\\d+)?)(?![A-Za-z0-9])");
    private static final Pattern DESCRIPTIVE_PATTERN = Pattern.compile(
            "^([A-Za-z][A-Za-z0-9()/%+\\- ]{2,90}?)\\s*[:\\-]?\\s*(positive|negative|reactive|non reactive|non-reactive|present|absent|mild|moderate|severe|abnormal|normal)\\b.*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SHORT_UPPERCASE_LABEL = Pattern.compile("^[A-Z][A-Z0-9]{1,7}$");
    private static final List<String> MEDICAL_KEYWORDS = List.of(
            "tsh", "thyroid", "t3", "t4", "ft3", "ft4", "hemoglobin", "haemoglobin", "hb", "hba1c",
            "glucose", "sugar", "creatinine", "urea", "uric", "cholesterol", "triglyceride", "hdl", "ldl",
            "bilirubin", "albumin", "globulin", "protein", "platelet", "wbc", "rbc", "esr", "crp",
            "vitamin", "ferritin", "iron", "calcium", "sodium", "potassium", "chloride", "phosphorus",
            "testosterone", "prolactin", "cortisol", "serum", "urine", "count", "generation",
            "pcv", "hct", "mcv", "mch", "mchc", "rdw", "neutrophil", "lymphocyte", "monocyte",
            "eosinophil", "basophil", "band forms", "mpv", "smear"
    );
    private static final List<String> TABLE_SECTION_HEADERS = List.of(
            "rbc parameters", "wbc parameters", "platelet parameters",
            "cbc parameters", "complete blood count"
    );

    @Override
    public List<ParsedFindingCandidate> parseFindings(String extractedText) {
        if (!StringUtils.hasText(extractedText)) {
            return List.of();
        }

        List<String> lines = extractedText.lines()
                .map(line -> line.replaceAll("\\s+", " ").trim())
                .filter(StringUtils::hasText)
                .toList();

        Map<String, ParsedFindingCandidate> findings = new LinkedHashMap<>();
        for (ParsedFindingCandidate candidate : parseSeparatedTableFindings(lines)) {
            addCandidate(findings, candidate);
        }

        for (int index = 0; index < lines.size(); index++) {
            String cleanedLine = lines.get(index);
            if (cleanedLine.length() < 5 || shouldSkip(cleanedLine)) {
                continue;
            }

            String candidateLine = cleanedLine;
            if (index + 1 < lines.size() && shouldMergeWithNextLine(cleanedLine, lines.get(index + 1))) {
                candidateLine = cleanedLine + " " + lines.get(index + 1);
                index++;
            }

            ParsedFindingCandidate candidate = parseNumericLine(candidateLine);
            if (candidate == null) {
                candidate = parseDescriptiveLine(candidateLine);
            }

            addCandidate(findings, candidate);
        }
        return new ArrayList<>(findings.values());
    }

    private ParsedFindingCandidate parseNumericLine(String line) {
        List<NumberMatch> numbers = findNumbers(line);
        if (numbers.isEmpty()) {
            return null;
        }

        RangeMatch printedRange = findPrintedRange(line);
        NumberMatch valueMatch = chooseValueMatch(numbers, printedRange);
        if (valueMatch == null) {
            return null;
        }

        String parameter = cleanupLabel(line.substring(0, valueMatch.start()));
        if (!isLikelyParameterLabel(parameter, printedRange != null)) {
            return null;
        }

        ParsedFindingCandidate candidate = new ParsedFindingCandidate();
        candidate.setParameterName(normalizeMedicalLabel(parameter));
        candidate.setRawLine(line);
        candidate.setPatientValue(valueMatch.rawValue());
        candidate.setNumericValue(valueMatch.numericValue());

        String unit = extractUnit(line, valueMatch.end(), printedRange);
        if (StringUtils.hasText(unit)) {
            candidate.setUnit(unit);
        }

        if (printedRange != null) {
            candidate.setPrintedRangeLow(printedRange.low());
            candidate.setPrintedRangeHigh(printedRange.high());
            candidate.setPrintedRangeText(printedRange.text());
        }
        return candidate;
    }

    private ParsedFindingCandidate parseDescriptiveLine(String line) {
        Matcher matcher = DESCRIPTIVE_PATTERN.matcher(line);
        if (!matcher.matches()) {
            return null;
        }

        String parameter = cleanupLabel(matcher.group(1));
        if (!isLikelyDescriptiveParameter(parameter)) {
            return null;
        }

        ParsedFindingCandidate candidate = new ParsedFindingCandidate();
        candidate.setParameterName(normalizeMedicalLabel(parameter));
        candidate.setRawLine(line);
        candidate.setPatientValue(matcher.group(2));
        candidate.setDescriptiveValue(matcher.group(2));
        return candidate;
    }

    private List<ParsedFindingCandidate> parseSeparatedTableFindings(List<String> lines) {
        int investigationIndex = indexOfLineContaining(lines, "investigation");
        int resultHeaderIndex = indexOfResultHeader(lines, investigationIndex + 1);
        if (resultHeaderIndex < 0) {
            return List.of();
        }

        int labelStart = investigationIndex >= 0 ? investigationIndex + 1 : Math.max(0, resultHeaderIndex - 40);
        List<String> parameterLines = collectTableParameterLines(lines.subList(labelStart, resultHeaderIndex));
        List<String> resultLines = collectTableResultLines(lines, resultHeaderIndex + 1);

        if (parameterLines.size() < 3 || resultLines.isEmpty()) {
            return List.of();
        }

        int pairCount = Math.min(parameterLines.size(), resultLines.size());
        List<ParsedFindingCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < pairCount; index++) {
            ParsedFindingCandidate candidate = buildCandidateFromSeparatedColumns(parameterLines.get(index), resultLines.get(index));
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        return candidates;
    }

    private List<String> collectTableParameterLines(List<String> lines) {
        List<String> parameterLines = new ArrayList<>();
        for (String line : lines) {
            String normalized = line.toLowerCase(Locale.ROOT);
            if (normalized.startsWith("peripherial smear") || normalized.startsWith("peripheral smear")) {
                break;
            }
            if (shouldSkip(line)
                    || isSectionHeader(normalized)
                    || normalized.contains("ref range")
                    || normalized.contains("result")
                    || normalized.startsWith("morphology of")
                    || normalized.contains("on smear")) {
                continue;
            }

            if (!findNumbers(line).isEmpty()) {
                continue;
            }

            String label = cleanupParameterLine(line);
            if (!isLikelyTableParameterLabel(label)) {
                continue;
            }
            parameterLines.add(label);
        }
        return parameterLines;
    }

    private List<String> collectTableResultLines(List<String> lines, int startIndex) {
        List<String> resultLines = new ArrayList<>();
        for (int index = startIndex; index < lines.size(); index++) {
            String line = lines.get(index);
            String normalized = line.toLowerCase(Locale.ROOT);

            if (normalized.equals("unit")) {
                break;
            }
            if (normalized.startsWith("peripherial smear")
                    || normalized.startsWith("peripheral smear")
                    || normalized.startsWith("morphology of")
                    || normalized.contains("end of report")) {
                break;
            }
            if (!looksLikeTableResultRow(line)) {
                continue;
            }
            resultLines.add(line);
        }
        return resultLines;
    }

    private ParsedFindingCandidate buildCandidateFromSeparatedColumns(String rawParameter, String resultLine) {
        String parameter = normalizeMedicalLabel(cleanupParameterLine(rawParameter));
        if (!isLikelyTableParameterLabel(parameter)) {
            return null;
        }

        List<NumberMatch> numbers = findNumbers(resultLine);
        if (numbers.isEmpty()) {
            return null;
        }

        RangeMatch printedRange = findPrintedRange(resultLine);
        NumberMatch valueMatch = chooseTableValueMatch(numbers, printedRange);
        if (valueMatch == null) {
            return null;
        }

        ParsedFindingCandidate candidate = new ParsedFindingCandidate();
        candidate.setParameterName(parameter);
        candidate.setRawLine(parameter + " " + resultLine);
        candidate.setPatientValue(valueMatch.rawValue());
        candidate.setNumericValue(valueMatch.numericValue());

        if (printedRange != null) {
            candidate.setPrintedRangeLow(printedRange.low());
            candidate.setPrintedRangeHigh(printedRange.high());
            candidate.setPrintedRangeText(printedRange.text());
        }

        String unit = extractUnit(resultLine, valueMatch.end(), printedRange);
        if (StringUtils.hasText(unit)) {
            candidate.setUnit(unit);
        }
        return candidate;
    }

    private String extractUnit(String line, int valueEnd, RangeMatch printedRange) {
        int startIndex = printedRange != null ? printedRange.end() : valueEnd;
        if (startIndex >= line.length()) {
            return "";
        }

        String remainder = line.substring(startIndex).trim();
        for (String token : remainder.split("\\s+")) {
            String candidate = token.replaceAll("[,;]+$", "").trim();
            if (isLikelyUnit(candidate)) {
                return candidate;
            }
        }
        return "";
    }

    private boolean shouldSkip(String line) {
        String normalized = line.toLowerCase(Locale.ROOT);
        return normalized.startsWith("name")
                || normalized.startsWith("patient")
                || normalized.startsWith("age")
                || normalized.startsWith("sex")
                || normalized.startsWith("gender")
                || normalized.startsWith("sample")
                || normalized.startsWith("collected")
                || normalized.startsWith("reported")
                || normalized.startsWith("processed")
                || normalized.startsWith("barcode")
                || normalized.startsWith("email")
                || normalized.startsWith("website")
                || normalized.startsWith("plot no")
                || normalized.startsWith("page no")
                || normalized.startsWith("note")
                || normalized.startsWith("interpretation")
                || normalized.contains("pathkind")
                || normalized.contains("diagnostics")
                || normalized.startsWith("reference range")
                || normalized.startsWith("result ")
                || normalized.equals("result")
                || normalized.equals("unit")
                || normalized.equals("investigation")
                || isSectionHeader(normalized)
                || countLetters(line) < 3
                || symbolCount(line) > Math.max(5, line.length() / 3)
                || normalized.length() > 120;
    }

    private String cleanupLabel(String raw) {
        return raw.replaceAll("^[^A-Za-z]+", "")
                .replaceAll("[\\-:|]+$", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean isLikelyParameterLabel(String parameter, boolean hasPrintedRange) {
        if (!StringUtils.hasText(parameter) || parameter.length() > 90) {
            return false;
        }

        int letters = countLetters(parameter);
        if (letters < 2) {
            return false;
        }

        if (symbolCount(parameter) > Math.max(3, parameter.length() / 4)) {
            return false;
        }

        List<String> words = new ArrayList<>();
        for (String token : parameter.split("\\s+")) {
            String cleaned = token.replaceAll("[^A-Za-z0-9]", "");
            if (StringUtils.hasText(cleaned)) {
                words.add(cleaned);
            }
        }
        if (words.isEmpty() || words.size() > 5) {
            return false;
        }

        String compact = parameter.replaceAll("\\s+", "");
        String normalized = parameter.toLowerCase(Locale.ROOT);
        if (containsMedicalKeyword(normalized)) {
            return true;
        }
        if (SHORT_UPPERCASE_LABEL.matcher(compact).matches()) {
            return true;
        }

        long shortWordCount = words.stream().filter(word -> word.length() == 1).count();
        return hasPrintedRange && words.size() >= 2 && letters >= 6 && shortWordCount == 0 && endsWithLikelyTestWord(normalized);
    }

    private boolean isLikelyDescriptiveParameter(String parameter) {
        return isLikelyParameterLabel(parameter, false) && parameter.split("\\s+").length <= 4;
    }

    private boolean isLikelyTableParameterLabel(String parameter) {
        if (!StringUtils.hasText(parameter)) {
            return false;
        }
        String normalized = parameter.toLowerCase(Locale.ROOT);
        if (containsMedicalKeyword(normalized)) {
            return true;
        }
        String compact = parameter.replaceAll("[^A-Za-z0-9]", "");
        return SHORT_UPPERCASE_LABEL.matcher(compact).matches()
                || normalized.contains("count")
                || normalized.contains("smear")
                || normalized.contains("cell");
    }

    private List<NumberMatch> findNumbers(String line) {
        List<NumberMatch> matches = new ArrayList<>();
        Matcher matcher = NUMBER_PATTERN.matcher(line);
        while (matcher.find()) {
            matches.add(new NumberMatch(
                    matcher.start(),
                    matcher.end(),
                    matcher.group(),
                    new BigDecimal(matcher.group(1))
            ));
        }
        return matches;
    }

    private RangeMatch findPrintedRange(String line) {
        Matcher matcher = RANGE_PATTERN.matcher(line);
        if (!matcher.find()) {
            return null;
        }
        return new RangeMatch(
                matcher.start(),
                matcher.end(),
                new BigDecimal(matcher.group(1)),
                new BigDecimal(matcher.group(2)),
                matcher.group(1) + " - " + matcher.group(2)
        );
    }

    private NumberMatch chooseValueMatch(List<NumberMatch> numbers, RangeMatch printedRange) {
        if (printedRange != null) {
            NumberMatch nearestBeforeRange = null;
            for (NumberMatch number : numbers) {
                if (number.end() <= printedRange.start()) {
                    nearestBeforeRange = number;
                }
            }
            if (nearestBeforeRange != null) {
                return nearestBeforeRange;
            }
            return null;
        }

        return numbers.get(0);
    }

    private NumberMatch chooseTableValueMatch(List<NumberMatch> numbers, RangeMatch printedRange) {
        if (printedRange != null) {
            for (NumberMatch number : numbers) {
                if (number.end() <= printedRange.start()) {
                    return number;
                }
            }
        }
        return numbers.get(0);
    }

    private boolean isLikelyUnit(String token) {
        if (!StringUtils.hasText(token) || token.length() > 15) {
            return false;
        }

        String normalized = token.toLowerCase(Locale.ROOT);
        return normalized.contains("/")
                || normalized.contains("%")
                || normalized.matches("(?:[munµ]?[a-z]{1,4}/[a-z]{1,4})")
                || normalized.matches("(?:mg|g|ng|pg|iu|uiu|miu|mmol|meq|fl|dl|ml|l)(?:/[a-z]{1,4})?");
    }

    private boolean containsMedicalKeyword(String value) {
        return MEDICAL_KEYWORDS.stream().anyMatch(value::contains);
    }

    private boolean endsWithLikelyTestWord(String value) {
        return value.endsWith("count")
                || value.endsWith("level")
                || value.endsWith("generation")
                || value.endsWith("profile")
                || value.endsWith("panel")
                || value.endsWith("ratio")
                || value.endsWith("test");
    }

    private boolean shouldMergeWithNextLine(String line, String nextLine) {
        return containsMedicalKeyword(line.toLowerCase(Locale.ROOT))
                && nextLine.length() <= 30
                && findPrintedRange(nextLine) != null;
    }

    private void addCandidate(Map<String, ParsedFindingCandidate> findings, ParsedFindingCandidate candidate) {
        if (candidate == null || !StringUtils.hasText(candidate.getParameterName())) {
            return;
        }
        findings.putIfAbsent(normalizeKey(candidate.getParameterName()), candidate);
    }

    private int indexOfLineContaining(List<String> lines, String token) {
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).toLowerCase(Locale.ROOT).contains(token)) {
                return index;
            }
        }
        return -1;
    }

    private int indexOfResultHeader(List<String> lines, int startIndex) {
        for (int index = Math.max(0, startIndex); index < lines.size(); index++) {
            String normalized = lines.get(index).toLowerCase(Locale.ROOT);
            if (normalized.contains("result") && (normalized.contains("range") || normalized.contains("ref"))) {
                return index;
            }
        }
        return -1;
    }

    private boolean isSectionHeader(String normalizedLine) {
        return TABLE_SECTION_HEADERS.stream().anyMatch(normalizedLine::contains);
    }

    private boolean looksLikeTableResultRow(String line) {
        if (!StringUtils.hasText(line)) {
            return false;
        }
        if (!Character.isDigit(line.trim().charAt(0))) {
            return false;
        }
        List<NumberMatch> numbers = findNumbers(line);
        return numbers.size() >= 1 && (findPrintedRange(line) != null || numbers.size() >= 2);
    }

    private String cleanupParameterLine(String raw) {
        return cleanupLabel(raw)
                .replaceAll("^[:|]+", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String normalizeMedicalLabel(String rawLabel) {
        String label = cleanupParameterLine(rawLabel);
        String normalized = normalizeKey(label);
        return switch (normalized) {
            case "haemoglobin", "hemoglobin", "hb" -> "Hemoglobin";
            case "totalrbccount", "totalrbc", "rbccount" -> "RBC Count";
            case "pcvhct", "hctpcv" -> "PCV/HCT";
            case "mcv", "mev" -> "MCV";
            case "mch" -> "MCH";
            case "mchc" -> "MCHC";
            case "rdw" -> "RDW";
            case "totalwbccount", "wbccount", "wbc" -> "WBC Count";
            case "neutrophils" -> "Neutrophils";
            case "lymphocytes", "lymphocyte" -> "Lymphocytes";
            case "monocytes", "monocyte", "jonacytes" -> "Monocytes";
            case "eosinophils", "eosinophil" -> "Eosinophils";
            case "basophils", "basophil" -> "Basophils";
            case "bandforms" -> "Band Forms";
            case "absoluteneutrophils" -> "Absolute Neutrophils";
            case "absolutelymphocyte", "absolutelymphocytes" -> "Absolute Lymphocytes";
            case "absolutemonocytes" -> "Absolute Monocytes";
            case "absoluteeosinophils" -> "Absolute Eosinophils";
            case "absolutebasophils" -> "Absolute Basophils";
            case "plateletcount", "platelets" -> "Platelet Count";
            case "mpv" -> "MPV";
            default -> label;
        };
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

    private int symbolCount(String value) {
        int count = 0;
        for (char character : value.toCharArray()) {
            if (!Character.isLetterOrDigit(character) && !Character.isWhitespace(character) && "-:/%().".indexOf(character) < 0) {
                count++;
            }
        }
        return count;
    }

    private String normalizeKey(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private record NumberMatch(int start, int end, String rawValue, BigDecimal numericValue) {
    }

    private record RangeMatch(int start, int end, BigDecimal low, BigDecimal high, String text) {
    }
}
