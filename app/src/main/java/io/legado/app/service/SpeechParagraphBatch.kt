package io.legado.app.service

internal data class SpeechParagraphBatch(
    val startIndex: Int,
    val endIndex: Int,
    val text: String
)

/**
 * Combine adjacent short paragraphs into one Android TTS utterance.
 * Newline separators preserve the source text's one-character paragraph
 * boundaries, so onRangeStart offsets still map to the original chapter.
 */
internal fun buildSpeechParagraphBatches(
    paragraphs: List<String>,
    startIndex: Int,
    firstParagraphOffset: Int,
    maxShortParagraphLength: Int = 72,
    maxBatchParagraphs: Int = 5,
    maxBatchLength: Int = 240,
    isSpeakable: (String) -> Boolean
): List<SpeechParagraphBatch> {
    if (paragraphs.isEmpty() || startIndex !in paragraphs.indices) return emptyList()

    val batches = mutableListOf<SpeechParagraphBatch>()
    var index = startIndex
    while (index < paragraphs.size) {
        val original = paragraphs[index]
        val firstOffset = if (index == startIndex) {
            firstParagraphOffset.coerceIn(0, original.length)
        } else {
            0
        }
        val firstText = original.substring(firstOffset)
        if (firstText.isEmpty() || !isSpeakable(firstText)) {
            index++
            continue
        }

        val batchStart = index
        val text = StringBuilder(firstText)
        var batchEnd = index

        if (firstText.length <= maxShortParagraphLength) {
            while (batchEnd + 1 < paragraphs.size &&
                batchEnd - batchStart + 1 < maxBatchParagraphs
            ) {
                val next = paragraphs[batchEnd + 1]
                if (!isSpeakable(next) || next.length > maxShortParagraphLength) break
                if (text.length + 1 + next.length > maxBatchLength) break
                text.append('\n').append(next)
                batchEnd++
            }
        }

        batches += SpeechParagraphBatch(batchStart, batchEnd, text.toString())
        index = batchEnd + 1
    }
    return batches
}
