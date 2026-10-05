package com.harshpahurkar.rag.ai;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailResult;

/**
 * Masks personal data in the whole outgoing user message (question and retrieved sources) before it leaves for
 * the LLM provider. The API response still shows the caller their own unmasked sources; only the third-party
 * request is masked.
 */
public class PiiMaskingGuardrail implements InputGuardrail {

	private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

	/** 13 to 19 digits, optionally grouped by single spaces or dashes, not part of a longer run. Luhn decides. */
	private static final Pattern CARD = Pattern.compile("(?<!\\w)(?<!\\d[ -])\\d(?:[ -]?\\d){12,18}(?!\\w|[ -]\\d)");

	/** Canadian SIN (###-###-### or ### ### ###) and US SSN (###-##-####). */
	private static final Pattern GOV_ID = Pattern
		.compile("(?<![\\w-])(?:\\d{3}([- ])\\d{3}\\1\\d{3}|\\d{3}-\\d{2}-\\d{4})(?![\\w-])");

	/** North American: 555-0177, 416-555-0199, (416) 555-0199, +1 416 555 0199. */
	private static final Pattern PHONE = Pattern
		.compile("(?<![\\w+-])(?:\\+?1[ .-])?(?:(?:\\(\\d{3}\\)|\\d{3})[ .-]?)?\\d{3}[ .-]\\d{4}(?![\\w-])");

	public static String mask(String text) {
		String masked = EMAIL.matcher(text).replaceAll("[EMAIL]");
		masked = CARD.matcher(masked)
			.replaceAll(m -> luhn(m.group()) ? "[CARD]" : Matcher.quoteReplacement(m.group()));
		masked = GOV_ID.matcher(masked).replaceAll("[GOV_ID]");
		return PHONE.matcher(masked).replaceAll("[PHONE]");
	}

	static boolean luhn(String number) {
		int sum = 0;
		boolean doubleIt = false;
		for (int i = number.length() - 1; i >= 0; i--) {
			char c = number.charAt(i);
			if (!Character.isDigit(c)) {
				continue;
			}
			int d = c - '0';
			if (doubleIt && (d *= 2) > 9) {
				d -= 9;
			}
			sum += d;
			doubleIt = !doubleIt;
		}
		return sum % 10 == 0;
	}

	@Override
	public InputGuardrailResult validate(UserMessage userMessage) {
		String text = userMessage.singleText();
		String masked = mask(text);
		return masked.equals(text) ? success() : successWith(masked);
	}

}
