package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechParagraphBatchTest {

    @Test
    fun combinesAdjacentShortParagraphs() {
        val batches = buildSpeechParagraphBatches(
            paragraphs = listOf("第一段。", "第二段。", "第三段。", "这是一个比较长的段落，应该单独朗读。"),
            startIndex = 0,
            firstParagraphOffset = 0,
            maxShortParagraphLength = 10,
            isSpeakable = { true }
        )

        assertEquals(2, batches.size)
        assertEquals(0, batches[0].startIndex)
        assertEquals(2, batches[0].endIndex)
        assertEquals("第一段。\n第二段。\n第三段。", batches[0].text)
        assertEquals(3, batches[1].startIndex)
        assertEquals(3, batches[1].endIndex)
    }

    @Test
    fun doesNotCombineAcrossSkippedPunctuationParagraph() {
        val batches = buildSpeechParagraphBatches(
            paragraphs = listOf("第一段。", "……", "第二段。"),
            startIndex = 0,
            firstParagraphOffset = 0,
            isSpeakable = { it != "……" }
        )

        assertEquals(listOf(0, 2), batches.map { it.startIndex })
        assertEquals(listOf(0, 2), batches.map { it.endIndex })
    }

    @Test
    fun appliesResumeOffsetOnlyToFirstParagraph() {
        val batches = buildSpeechParagraphBatches(
            paragraphs = listOf("前面已经读过，后面继续。", "下一段。"),
            startIndex = 0,
            firstParagraphOffset = 7,
            isSpeakable = { true }
        )

        assertEquals("后面继续。\n下一段。", batches.single().text)
        assertEquals(0, batches.single().startIndex)
        assertEquals(1, batches.single().endIndex)
    }

    @Test
    fun respectsBatchParagraphAndCharacterLimits() {
        val paragraphs = listOf("短一", "短二", "短三", "短四")
        val batches = buildSpeechParagraphBatches(
            paragraphs = paragraphs,
            startIndex = 0,
            firstParagraphOffset = 0,
            maxShortParagraphLength = 10,
            maxBatchParagraphs = 2,
            maxBatchLength = 20,
            isSpeakable = { true }
        )

        assertEquals(listOf(0 to 1, 2 to 3), batches.map { it.startIndex to it.endIndex })
    }
}
