package org.unitedsoftware.alexandrite.provider.common

import dev.drewhamilton.poko.Poko
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.model.ModelPrice
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.transcript.Dialect
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind

/** What one source says about a model, null where it says nothing. */
@Poko
public class ModelFacts(
    public val id: String?,
    public val displayName: String? = null,
    public val contextWindow: Int? = null,
    public val maxOutputTokens: Int? = null,
    public val nativeTools: Boolean? = null,
    public val parallelToolCalls: Boolean? = null,
    public val inputMedia: Set<MediaKind>? = null,
    public val reasoningEfforts: Set<ReasoningEffort>? = null,
    public val price: ModelPrice? = null,
)

/**
 * The model [id] as [configured], [listed] and [defaults] describe it, in that order, taking only the media kinds of
 * [sends] that its wire API carries.
 */
internal fun modelInfo(
    id: String,
    dialect: Dialect,
    configured: ModelFacts?,
    listed: ModelFacts?,
    defaults: ModelFacts,
    sends: Set<MediaKind>,
): ModelInfo {
    val sources = listOfNotNull(configured, listed, defaults)
    fun <T : Any> first(field: (ModelFacts) -> T?): T? = sources.firstNotNullOfOrNull(field)
    val nativeTools = first { it.nativeTools } ?: false
    return ModelInfo.builder(id, dialect)
        .displayName(first { it.displayName })
        .contextWindow(configured?.contextWindow ?: listed?.contextWindow)
        .maxOutputTokens(configured?.maxOutputTokens ?: listed?.maxOutputTokens)
        .nativeTools(nativeTools)
        .parallelToolCalls(nativeTools && first { it.parallelToolCalls } == true)
        .inputMedia(first { it.inputMedia }.orEmpty() intersect sends)
        .reasoningEfforts(first { it.reasoningEfforts }.orEmpty())
        .streaming(true)
        .price(first { it.price })
        .build()
}
