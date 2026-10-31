package rea.ajdamir.backendaisafety;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PromptInjectionSanitizer {

    private static final Pattern SENTENCE_BOUNDARY =
        Pattern.compile("(?<=[.!?。！？])\\s+|\\R+");

    private static final Pattern WORD_TOKEN =
        Pattern.compile("[\\p{L}\\p{N}_'-]+");

    private static final Pattern INSTRUCTION_HEADER =
        Pattern.compile(
            "^(?:инструкция\\s+для\\s+ии|"
                + "instruction\\s+(?:to|for)\\s+(?:the\\s+)?ai)"
                + "\\s*[:：—-]\\s*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
        );

    private static final List<String> DIRECTIVE_PREFIXES = List.of(
        "игнорируй", "игнорируйте",
        "забудь", "забудьте",
        "ответь", "ответьте",
        "скажи", "скажите",
        "выведи", "выведите",
        "покажи", "покажите",
        "раскрой", "раскройте",
        "не указывай", "не указывайте",
        "не раскрывай", "не раскрывайте",
        "не следуй", "не следуйте",
        "не выполняй", "не выполняйте",
        "не показывай", "не показывайте",
        "не выводи", "не выводите",
        "ignore", "disregard", "forget", "reveal",
        "respond only", "answer only", "return only",
        "do not follow", "don't follow",
        "do not reveal", "don't reveal"
    );

    private static final Pattern UNIVERSAL_ANSWER_OVERRIDE =
        Pattern.compile(
            "(?:правильн\\p{L}*|единственн\\p{L}*)"
                + "\\s+ответ\\p{L}*"
                + ".{0,120}?люб\\p{L}*\\s+вопрос\\p{L}*"
                + "|(?:the\\s+)?(?:correct|only|right)\\s+answer"
                + ".{0,120}?(?:any|every)\\s+question",
            Pattern.CASE_INSENSITIVE
                | Pattern.UNICODE_CASE
                | Pattern.DOTALL
        );

    private static final Pattern QUOTED_SPAN = Pattern.compile(
        "«[^»]*»|“[^”]*”|„[^“]*“|\"[^\"]*\"|'[^']*'"
    );

    private static final Pattern SOURCE_SUPPRESSION =
        Pattern.compile(
            "не\\s+(?:указывай|указывайте|сообщай|сообщайте|"
                + "раскрывай|раскрывайте)\\s+"
                + "(?:источник|источники|происхождение|"
                + "другие\\s+факты|факты)"
                + "|do\\s+not\\s+(?:cite|mention|show)\\s+"
                + "(?:the\\s+)?source"
                + "|do\\s+not\\s+provide\\s+(?:other\\s+)?facts",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
        );

    private static final Pattern AI_PROTOCOL_CONTEXT =
        Pattern.compile(
            "для\\s+системы.{0,160}"
                + "(?:документ|читает|обрабатывает|анализирует)"
                + "|дополнительн\\p{L}*\\s+протокол"
                + "|для\\s+(?:ии|модели|искусственного\\s+интеллекта)"
                + "|(?:for\\s+the\\s+)?(?:ai|model|system)"
                + ".{0,120}?(?:document|text|content)"
                + "|additional\\s+protocol",
            Pattern.CASE_INSENSITIVE
                | Pattern.UNICODE_CASE
                | Pattern.DOTALL
        );

    private static final Pattern CONTROL_ACTION =
        Pattern.compile(
            "игнорируй|игнорируйте|забудь|забудьте|"
                + "ответь|ответьте|скажи|скажите|"
                + "выведи|выведите|покажи|покажите|"
                + "ignore|disregard|respond\\s+only|"
                + "answer\\s+only|return\\s+only|"
                + "do\\s+not\\s+follow|don't\\s+follow",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
        );

    public String sanitize(String text) {
        return sanitizeWithReport(text).sanitizedText();
    }

    public SanitizationResult sanitizeWithReport(String text) {
        if (text == null) {
            return new SanitizationResult("", 0, List.of());
        }

        if (text.isBlank()) {
            return new SanitizationResult(text, 0, List.of());
        }

        String[] segments = SENTENCE_BOUNDARY.split(text);
        List<String> retainedSegments = new ArrayList<>();
        List<String> reasons = new ArrayList<>();

        for (String part : segments) {
            String segment = part.strip();

            if (segment.isEmpty()) {
                continue;
            }

            String reason = detectInjection(segment);

            if (reason != null) {
                reasons.add(reason);
            } else {
                retainedSegments.add(segment);
            }
        }

        if (reasons.isEmpty()) {
            return new SanitizationResult(text, 0, List.of());
        }

        return new SanitizationResult(
            String.join(" ", retainedSegments),
            reasons.size(),
            reasons
        );
    }

    private String detectInjection(String segment) {
        String normalized = normalizeForDetection(segment);
        String reason = detectNormalizedInjection(normalized);

        if (reason != null) {
            return reason;
        }

        // Second pass: compare a limited mixed-script confusable form with the
        // existing rules. This only changes the detector's working copy.
        String confusableNormalized =
            normalizeMixedScriptConfusables(normalized);
        if (!confusableNormalized.equals(normalized)) {
            return detectNormalizedInjection(confusableNormalized);
        }

        return null;
    }

    private String detectNormalizedInjection(String normalized) {
        String text = INSTRUCTION_HEADER.matcher(normalized)
            .replaceFirst("")
            .stripLeading();

        if (hasDirectivePrefix(text)) {
            return "DIRECTIVE_PREFIX";
        }

        if (UNIVERSAL_ANSWER_OVERRIDE.matcher(text).find()) {
            return "UNIVERSAL_ANSWER_OVERRIDE";
        }

        if (hasUnquotedMatch(SOURCE_SUPPRESSION, text)) {
            return "SOURCE_SUPPRESSION";
        }

        if (AI_PROTOCOL_CONTEXT.matcher(text).find()
            && CONTROL_ACTION.matcher(text).find()) {
            return "AI_PROTOCOL_OVERRIDE";
        }

        return null;
    }

    /**
     * Produces a detection-only representation of a document segment.
     *
     * NFKC folds compatibility variants such as full-width Latin characters;
     * Unicode format characters (including zero-width format controls) are
     * removed, and Unicode whitespace is made consistent. The original
     * segment is never rewritten by this method: if a segment is retained,
     * sanitizeWithReport returns its original text.
     *
     * This is a targeted normalization step. A separate, limited mixed-script
     * confusable pass is applied after the ordinary rules; it is not a full
     * Unicode UTS #39 implementation.
     */
    private static String normalizeForDetection(String segment) {
        String compatibilityNormalized =
            Normalizer.normalize(segment, Normalizer.Form.NFKC);
        StringBuilder visible = new StringBuilder(compatibilityNormalized.length());

        for (int offset = 0; offset < compatibilityNormalized.length();) {
            int codePoint = compatibilityNormalized.codePointAt(offset);
            offset += Character.charCount(codePoint);

            if (Character.getType(codePoint) == Character.FORMAT) {
                continue;
            }

            if (Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint)) {
                visible.append(' ');
            } else {
                visible.appendCodePoint(codePoint);
            }
        }

        return visible.toString()
            .toLowerCase(Locale.ROOT)
            .replaceAll("\\s+", " ")
            .strip();
    }

    /**
     * Rewrites only tokens that mix Latin letters with a small set of common
     * Cyrillic/Greek lookalikes. This is a narrow heuristic, not a full
     * Unicode UTS #39 confusable skeleton. It is used for detection only.
     */
    private static String normalizeMixedScriptConfusables(String text) {
        Matcher matcher = WORD_TOKEN.matcher(text);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String token = matcher.group();
            boolean hasLatin = token.codePoints().anyMatch(codePoint ->
                Character.UnicodeScript.of(codePoint)
                    == Character.UnicodeScript.LATIN);
            boolean hasLookalike = token.codePoints().anyMatch(
                PromptInjectionSanitizer::hasMappedConfusable);

            if (!hasLatin || !hasLookalike) {
                continue;
            }

            StringBuilder mapped = new StringBuilder(token.length());
            token.codePoints().forEach(codePoint ->
                mapped.appendCodePoint(mapConfusableToLatin(codePoint)));

            matcher.appendReplacement(
                result,
                Matcher.quoteReplacement(mapped.toString())
            );
        }

        matcher.appendTail(result);
        return result.toString();
    }

    private static boolean hasMappedConfusable(int codePoint) {
        return mapConfusableToLatin(codePoint) != codePoint;
    }

    private static int mapConfusableToLatin(int codePoint) {
        return switch (codePoint) {
            // Frequently confused Cyrillic letters.
            case 'а' -> 'a';
            case 'в' -> 'b';
            case 'е' -> 'e';
            case 'к' -> 'k';
            case 'м' -> 'm';
            case 'н' -> 'h';
            case 'о' -> 'o';
            case 'р' -> 'p';
            case 'с' -> 'c';
            case 'т' -> 't';
            case 'у' -> 'y';
            case 'х' -> 'x';
            case 'і' -> 'i';
            case 'ј' -> 'j';
            case 'ѕ' -> 's';
            case 'ӏ' -> 'l';
            // Frequently confused Greek letters.
            case 'α' -> 'a';
            case 'ε' -> 'e';
            case 'ι' -> 'i';
            case 'κ' -> 'k';
            case 'μ' -> 'm';
            case 'ν' -> 'v';
            case 'ο' -> 'o';
            case 'ρ' -> 'p';
            case 'τ' -> 't';
            case 'υ' -> 'y';
            case 'χ' -> 'x';
            default -> codePoint;
        };
    }

    /**
     * Returns true only when a match is outside a quoted span. Documents often
     * discuss prompt-injection phrases as examples; a quoted mention alone is
     * not an instruction to the model.
     */
    private boolean hasUnquotedMatch(Pattern pattern, String text) {
        Matcher candidate = pattern.matcher(text);
        while (candidate.find()) {
            boolean insideQuote = false;
            Matcher quotedSpan = QUOTED_SPAN.matcher(text);
            while (quotedSpan.find()) {
                if (candidate.start() >= quotedSpan.start()
                    && candidate.end() <= quotedSpan.end()) {
                    insideQuote = true;
                    break;
                }
            }
            if (!insideQuote) {
                return true;
            }
        }
        return false;
    }

    private boolean hasDirectivePrefix(String normalized) {
        for (String prefix : DIRECTIVE_PREFIXES) {
            if (!normalized.startsWith(prefix)) {
                continue;
            }

            if (normalized.length() == prefix.length()) {
                return true;
            }

            int nextCodePoint = normalized.codePointAt(prefix.length());

            if (!Character.isLetterOrDigit(nextCodePoint)
                && nextCodePoint != '_') {
                return true;
            }
        }

        return false;
    }

    public record SanitizationResult(
        String sanitizedText,
        int removedSegments,
        List<String> reasons
    ) {
        public SanitizationResult {
            reasons = List.copyOf(reasons);
        }
    }
}
