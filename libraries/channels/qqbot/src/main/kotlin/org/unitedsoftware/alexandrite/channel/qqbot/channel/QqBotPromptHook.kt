package org.unitedsoftware.alexandrite.channel.qqbot.channel

import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.hook.Hook
import org.unitedsoftware.alexandrite.sdk.hook.HookDecision
import org.unitedsoftware.alexandrite.sdk.hook.InterceptorHook
import org.unitedsoftware.alexandrite.sdk.hook.InterceptorPoint
import org.unitedsoftware.alexandrite.sdk.model.PromptSection
import org.unitedsoftware.alexandrite.sdk.turn.PromptSections
import org.unitedsoftware.alexandrite.sdk.turn.TurnPoints

/**
 * Tells the model what the QQ client renders, so it writes what the channel can show.
 *
 * The adapter of the channel still turns what the client only shows as raw text into a form it renders; this section
 * only makes the model less likely to write it in the first place. A turn of another channel is left alone.
 */
@Singleton
@Contribute(Hook::class)
internal class QqBotPromptHook : InterceptorHook<PromptSections> {
    override val point: InterceptorPoint<PromptSections> get() = TurnPoints.PROMPT_SECTIONS

    override suspend fun intercept(payload: PromptSections): HookDecision<PromptSections> {
        if (payload.turn.chat.instance.type != QQ_BOT) return HookDecision.Continue
        if (payload.sections.any { it.id == SECTION_ID }) return HookDecision.Continue
        val section = PromptSection(SECTION_ID, PROMPT, stable = true)
        return HookDecision.Replace(payload.withSections(payload.sections + section))
    }

    private companion object {
        val QQ_BOT = ChannelType("qqbot")

        const val SECTION_ID = "qqbot.platform"

        const val PROMPT = "This chat is a QQ official bot. Write the Markdown the QQ client renders: headings, " +
            "paragraphs, emphasis, lists, blockquotes, fenced code, GFM tables, task lists and links. Do not use " +
            "HTML blocks such as <details>, <table>, <kbd> or <mark>, Mermaid diagrams, [TOC], ==highlight== or " +
            "definition lists; the client shows those as raw text. An image needs a public http(s) URL; a local path " +
            "is not shown. In a group, do not make a link the only way to say something: the platform may refuse a " +
            "message holding a URL. A command starts with a slash, such as /model."
    }
}
