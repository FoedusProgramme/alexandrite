package org.unitedsoftware.alexandrite.channel.common.approval

import org.unitedsoftware.alexandrite.sdk.channel.ApprovalAnswer
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalRequest
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.tool.RuleSubject

/** How long an approval request waits for an administrator at the least, in minutes. */
public const val MIN_APPROVAL_MINUTES: Long = 1L

/** How long an approval request waits for an administrator at the most, in minutes. */
public const val MAX_APPROVAL_MINUTES: Long = 1_440L

/** Whether [minutes] is a wait an approval request may configure. */
public fun isApprovalMinutes(minutes: Long): Boolean = minutes in MIN_APPROVAL_MINUTES..MAX_APPROVAL_MINUTES

/** What a written answer's words, split on whitespace, name: a code, and whether "always" is among them. */
public class AnswerWords(public val code: String?, public val always: Boolean)

/**
 * Reads [words] as the arguments of a written `/approve` or `/deny`: "always" in any case, and the first word that
 * is not it, taken as the request's id (or the start of it) when the platform did not already carry one.
 */
public fun answerWords(words: List<String>): AnswerWords {
    val always = words.any { it.equals(ALWAYS, ignoreCase = true) }
    val code = words.firstOrNull { !it.equals(ALWAYS, ignoreCase = true) }
    return AnswerWords(code, always)
}

/** The word a written answer's arguments hold to grant every rule [ApprovalRequest.alwaysRules] names, in any case. */
public const val ALWAYS: String = "always"

/**
 * What [by] decides for [request] by a press or a written answer: [allow]ed or denied, granting every one of
 * [ApprovalRequest.alwaysRules] when [always] and the request offers any; null when [by] may not answer at all.
 */
public fun decideApproval(request: ApprovalRequest, by: ChatUser, allow: Boolean, always: Boolean): ApprovalAnswer? {
    if (!by.isOperator) return null
    return if (allow) {
        ApprovalAnswer.Allowed(by, always && request.alwaysRules.isNotEmpty())
    } else {
        ApprovalAnswer.Denied(by)
    }
}

/** Every rule [rules] names, for a line that grants all of them rather than only the first. */
public fun alwaysRulesText(rules: List<RuleSubject>): String = rules.joinToString(", ") { it.display }
