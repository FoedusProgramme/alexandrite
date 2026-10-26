package org.unitedsoftware.alexandrite.channel.onebot.i18n

/** The keys of the OneBot channel's texts for people. */
internal object TextKeys {
    /** Takes `{id}`. */
    const val APPROVAL_TITLE: String = "approval.title"

    /** Takes `{agent}`. */
    const val APPROVAL_AGENT: String = "approval.agent"

    /** Takes `{chat}`. */
    const val APPROVAL_CHAT: String = "approval.chat"

    /** Takes `{requester}`. */
    const val APPROVAL_REQUESTER: String = "approval.requester"

    /** Takes `{reason}`. */
    const val APPROVAL_REASON: String = "approval.reason"

    /** Takes `{tool}` and `{risk}`. */
    const val APPROVAL_TOOL: String = "approval.tool"

    /** Takes `{path}`. */
    const val APPROVAL_FILE: String = "approval.file"

    /** Takes `{directory}`. */
    const val APPROVAL_DIRECTORY: String = "approval.directory"

    /** Takes `{program}`. */
    const val APPROVAL_PROGRAM: String = "approval.program"

    const val APPROVAL_PROGRAM_UNKNOWN: String = "approval.program_unknown"
    const val APPROVAL_OPAQUE: String = "approval.opaque"
    const val APPROVAL_ARGUMENTS: String = "approval.arguments"

    /** How an approver answers, which this channel takes as a command rather than as a press. */
    const val APPROVAL_ANSWER: String = "approval.answer"

    /** Takes `{rule}`. */
    const val APPROVAL_ALWAYS_RULE: String = "approval.always_rule"

    /** Takes `{rules}`, the rules joined for a request that may grant several. */
    const val APPROVAL_ALWAYS_RULES: String = "approval.always_rules"

    /** Takes `{id}` and `{name}`. */
    const val APPROVAL_OUTCOME_ALLOWED: String = "approval.outcome_allowed"

    /** Takes `{id}`, `{name}` and `{rule}`. */
    const val APPROVAL_OUTCOME_ALLOWED_ALWAYS: String = "approval.outcome_allowed_always"

    /** Takes `{id}` and `{name}`. */
    const val APPROVAL_OUTCOME_DENIED: String = "approval.outcome_denied"

    /** Takes `{id}`. */
    const val APPROVAL_OUTCOME_EXPIRED: String = "approval.outcome_expired"

    /** Takes `{id}`. */
    const val APPROVAL_OUTCOME_WITHDRAWN: String = "approval.outcome_withdrawn"

    const val APPROVAL_ADMINS_ONLY: String = "approval.admins_only"
    const val APPROVAL_NOT_PENDING: String = "approval.not_pending"
    const val APPROVAL_NOTHING_WAITS: String = "approval.nothing_waits"
    const val APPROVAL_LIST_TITLE: String = "approval.list_title"

    /** Takes `{id}`, `{tool}` and `{reason}`. */
    const val APPROVAL_LIST_ENTRY: String = "approval.list_entry"

    const val APPROVAL_LIST_HINT: String = "approval.list_hint"

    /** Takes `{ids}`. */
    const val APPROVAL_SEVERAL: String = "approval.several"

    /** Takes `{code}` and `{minutes}`. */
    const val PAIRING_CODE: String = "pairing.code"

    /** Takes `{command}`. */
    const val REFUSED_ADMIN: String = "refused.admin"

    /** Takes `{command}`. */
    const val REFUSED_ADMIN_HERE: String = "refused.admin_here"

    /** What a step of work did and what it did it to, as the kinds of step the standard names. */
    const val ACTIVITY_VERB_READ_PRESENT: String = "activity.verb.read.present"
    const val ACTIVITY_VERB_READ_PAST: String = "activity.verb.read.past"
    const val ACTIVITY_VERB_LIST_PRESENT: String = "activity.verb.list.present"
    const val ACTIVITY_VERB_LIST_PAST: String = "activity.verb.list.past"
    const val ACTIVITY_VERB_SEARCH_PRESENT: String = "activity.verb.search.present"
    const val ACTIVITY_VERB_SEARCH_PAST: String = "activity.verb.search.past"
    const val ACTIVITY_VERB_EDIT_PRESENT: String = "activity.verb.edit.present"
    const val ACTIVITY_VERB_EDIT_PAST: String = "activity.verb.edit.past"
    const val ACTIVITY_VERB_DELETE_PRESENT: String = "activity.verb.delete.present"
    const val ACTIVITY_VERB_DELETE_PAST: String = "activity.verb.delete.past"
    const val ACTIVITY_VERB_MOVE_PRESENT: String = "activity.verb.move.present"
    const val ACTIVITY_VERB_MOVE_PAST: String = "activity.verb.move.past"
    const val ACTIVITY_VERB_EXECUTE_PRESENT: String = "activity.verb.execute.present"
    const val ACTIVITY_VERB_EXECUTE_PAST: String = "activity.verb.execute.past"
    const val ACTIVITY_VERB_FETCH_PRESENT: String = "activity.verb.fetch.present"
    const val ACTIVITY_VERB_FETCH_PAST: String = "activity.verb.fetch.past"
    const val ACTIVITY_VERB_THINK_PRESENT: String = "activity.verb.think.present"
    const val ACTIVITY_VERB_THINK_PAST: String = "activity.verb.think.past"
    const val ACTIVITY_VERB_OTHER_PRESENT: String = "activity.verb.other.present"
    const val ACTIVITY_VERB_OTHER_PAST: String = "activity.verb.other.past"
    const val ACTIVITY_NOUN_READ_ONE: String = "activity.noun.read.one"
    const val ACTIVITY_NOUN_READ_MANY: String = "activity.noun.read.many"
    const val ACTIVITY_NOUN_LIST_ONE: String = "activity.noun.list.one"
    const val ACTIVITY_NOUN_LIST_MANY: String = "activity.noun.list.many"
    const val ACTIVITY_NOUN_SEARCH_ONE: String = "activity.noun.search.one"
    const val ACTIVITY_NOUN_SEARCH_MANY: String = "activity.noun.search.many"
    const val ACTIVITY_NOUN_EDIT_ONE: String = "activity.noun.edit.one"
    const val ACTIVITY_NOUN_EDIT_MANY: String = "activity.noun.edit.many"
    const val ACTIVITY_NOUN_DELETE_ONE: String = "activity.noun.delete.one"
    const val ACTIVITY_NOUN_DELETE_MANY: String = "activity.noun.delete.many"
    const val ACTIVITY_NOUN_MOVE_ONE: String = "activity.noun.move.one"
    const val ACTIVITY_NOUN_MOVE_MANY: String = "activity.noun.move.many"
    const val ACTIVITY_NOUN_EXECUTE_ONE: String = "activity.noun.execute.one"
    const val ACTIVITY_NOUN_EXECUTE_MANY: String = "activity.noun.execute.many"
    const val ACTIVITY_NOUN_FETCH_ONE: String = "activity.noun.fetch.one"
    const val ACTIVITY_NOUN_FETCH_MANY: String = "activity.noun.fetch.many"
    const val ACTIVITY_NOUN_THINK_ONE: String = "activity.noun.think.one"
    const val ACTIVITY_NOUN_THINK_MANY: String = "activity.noun.think.many"
    const val ACTIVITY_NOUN_OTHER_ONE: String = "activity.noun.other.one"
    const val ACTIVITY_NOUN_OTHER_MANY: String = "activity.noun.other.many"
    const val ACTIVITY_VERB_SEND_PRESENT: String = "activity.verb.send.present"
    const val ACTIVITY_VERB_SEND_PAST: String = "activity.verb.send.past"
    const val ACTIVITY_NOUN_SEND_ONE: String = "activity.noun.send.one"
    const val ACTIVITY_NOUN_SEND_MANY: String = "activity.noun.send.many"

    /** Takes `{noun}`. */
    const val ACTIVITY_SUMMARY_NOT_RUN: String = "activity.summary.not_run"

    /** Takes `{count}`. */
    const val ACTIVITY_OMITTED_ONE: String = "activity.omitted.one"
    const val ACTIVITY_OMITTED_MANY: String = "activity.omitted.many"

    /** Takes `{tool}` and `{title}`. */
    const val ACTIVITY_CALL_WAITING_APPROVAL: String = "activity.call.waiting_approval"
    const val ACTIVITY_CALL_NOT_RUN: String = "activity.call.not_run"
    const val ACTIVITY_CALL_BACKGROUNDED: String = "activity.call.backgrounded"

    /** Takes `{code}`. */
    const val ACTIVITY_CALL_EXIT: String = "activity.call.exit"

    const val ACTIVITY_CALL_FAILED: String = "activity.call.failed"

    /** Takes `{added}` and `{removed}`. */
    const val ACTIVITY_CALL_DIFF: String = "activity.call.diff"

    /** Takes `{agent}`. */
    const val ACTIVITY_DELEGATE_PRESENT: String = "activity.delegate.present"
    const val ACTIVITY_DELEGATE_PAST: String = "activity.delegate.past"

    const val ACTIVITY_CALL_BY_AGENT: String = "activity.call.by_agent"
}
