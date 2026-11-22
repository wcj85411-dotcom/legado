package io.legado.app.ui.book.read.paragraph

/** A complete accessibility item, independent of the screen's page boundaries. */
data class ParagraphItem(
    val id: String,
    val text: CharSequence,
    val imageSrc: String? = null,
    val imageClick: String? = null,
    val chapterPosition: Int,
    val progressPosition: Int = chapterPosition,
    val isTitle: Boolean = false,
    val positionRanges: List<ParagraphPositionRange> = emptyList(),
) {
    val isImage: Boolean get() = imageSrc != null

    /** Maps a UTF-16 text offset back to the existing page/TTS chapter coordinate. */
    fun canonicalPositionAt(textOffset: Int): Int {
        val offset = textOffset.coerceIn(0, text.length)
        if (positionRanges.isEmpty()) {
            // Plain text items still need a one-to-one display-to-chapter mapping.
            // Returning only chapterPosition freezes the speech cursor at the first
            // character, so range callbacks can never advance the visible highlight.
            return (chapterPosition.toLong() + offset)
                .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                .toInt()
        }
        val range = positionRanges.firstOrNull { offset >= it.textStart && offset < it.textEnd }
            ?: if (offset >= positionRanges.last().textEnd) positionRanges.last()
            else positionRanges.firstOrNull { it.textStart >= offset } ?: positionRanges.first()
        return range.chapterStart + (offset - range.textStart).coerceIn(0, range.textEnd - range.textStart)
    }
}

/** Half-open UTF-16 text range; HTML images can make chapter ranges discontinuous. */
data class ParagraphPositionRange(
    val textStart: Int,
    val textEnd: Int,
    val chapterStart: Int,
)
