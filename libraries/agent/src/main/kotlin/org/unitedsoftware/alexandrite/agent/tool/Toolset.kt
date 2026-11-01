package org.unitedsoftware.alexandrite.agent.tool

import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition

/** The tools a turn [offered] the model, and the [unoffered] ones that its calls may still name. */
internal class Toolset(val offered: List<Tool>, val unoffered: List<Tool>, val callableUnoffered: Set<String>) {
    /** The definitions of [offered] as [turn]'s requests carry them. */
    suspend fun definitions(turn: TurnInfo): List<ToolDefinition> = offered.definitionsFor(turn)
}

/** The definitions of these tools as [turn]'s requests carry them. */
internal suspend fun List<Tool>.definitionsFor(turn: TurnInfo): List<ToolDefinition> =
    map { (it as? RenderedTool)?.definitionFor(turn) ?: it.definition }
