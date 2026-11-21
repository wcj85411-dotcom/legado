package io.legado.app.service

import android.app.PendingIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.MediaHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.buildMainHandler
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicLong

/**
 * 本地朗读
 */
class TTSReadAloudService : BaseReadAloudService(), TextToSpeech.OnInitListener {

    private var textToSpeech: TextToSpeech? = null
    private var ttsInitFinish = false
    private val ttsUtteranceListener = TTSUtteranceListener()
    private var speakJob: Coroutine<*>? = null
    private val playbackGeneration = AtomicLong()
    private var retryBatchStartIndex = -1
    private var retryBatchCount = 0
    private val TAG = "TTSReadAloudService"
    private val callbackHandler by lazy { buildMainHandler() }

    override fun onCreate() {
        super.onCreate()
        initTts()
    }

    override fun onDestroy() {
        super.onDestroy()
        clearTTS()
    }

    @Synchronized
    private fun initTts() {
        ttsInitFinish = false
        val engine = GSON.fromJsonObject<SelectItem<String>>(ReadAloud.ttsEngine).getOrNull()?.value
        LogUtils.d(TAG, "initTts engine:$engine")
        textToSpeech = if (engine.isNullOrBlank()) {
            TextToSpeech(this, this)
        } else {
            TextToSpeech(this, this, engine)
        }
        upSpeechRate()
    }

    @Synchronized
    fun clearTTS() {
        playbackGeneration.incrementAndGet()
        textToSpeech?.runCatching {
            stop()
            shutdown()
        }
        textToSpeech = null
        ttsInitFinish = false
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.let {
                it.setOnUtteranceProgressListener(ttsUtteranceListener)
                ttsInitFinish = true
                play()
            }
        } else {
            toastOnUi(R.string.tts_init_failed)
        }
    }

    @Synchronized
    override fun play() {
        if (!ttsInitFinish) return
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            ReadBook.readAloud()
            return
        }
        super.play()
        MediaHelp.playSilentSound(this@TTSReadAloudService)
        val generation = playbackGeneration.incrementAndGet()
        speakJob?.cancel()
        speakJob = execute {
            LogUtils.d(TAG, "朗读列表大小 ${contentList.size}")
            LogUtils.d(TAG, "朗读页数 ${textChapter?.pageSize}")
            val tts = textToSpeech ?: throw NoStackTraceException("tts is null")
            val batches = buildSpeechParagraphBatches(
                paragraphs = contentList,
                startIndex = nowSpeak,
                firstParagraphOffset = paragraphStartPos,
                // Keep each original paragraph as its own utterance. Some third-party
                // engines do not synthesize newline-joined paragraph batches reliably.
                maxShortParagraphLength = 0,
                isSpeakable = { !it.matches(AppPattern.notReadAloudRegex) }
            )
            if (batches.isEmpty()) {
                nextChapter()
                return@execute
            }

            var isAddedText = false
            for (batch in batches) {
                ensureActive()
                if (generation != playbackGeneration.get()) return@execute
                val utteranceId = "${AppConst.APP_TAG}${generation}:${batch.startIndex}:${batch.endIndex}"
                val result = tts.runCatching {
                    // Match the stable reader's queue contract: flush once, then append.
                    speak(
                        batch.text,
                        if (isAddedText) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH,
                        null,
                        utteranceId
                    )
                }.getOrElse {
                    AppLog.put("tts出错\\n${it.localizedMessage}", it, true)
                    TextToSpeech.ERROR
                }

                if (result == TextToSpeech.ERROR) {
                    recoverFromBatchFailure(batch, "speak() 返回错误")
                    return@execute
                }
                isAddedText = true
            }
            LogUtils.d(TAG, "朗读内容已加入引擎队列，段落数 ${batches.size}")
        }.onError {
            AppLog.put("tts朗读出错\\n${it.localizedMessage}", it, true)
        }
    }

    override fun playStop() {
        playbackGeneration.incrementAndGet()
        speakJob?.cancel()
        textToSpeech?.runCatching {
            stop()
        }
    }

    private fun recoverFromBatchFailure(batch: SpeechParagraphBatch, reason: String) {
        if (retryBatchStartIndex != batch.startIndex) {
            retryBatchStartIndex = batch.startIndex
            retryBatchCount = 0
        }
        AppLog.put("朗读批次异常: $reason，段落 ${batch.startIndex + 1}-${batch.endIndex + 1}")
        if (retryBatchCount < 1) {
            retryBatchCount++
            AppLog.put("正在重新初始化语音引擎并从当前段落重试")
            clearTTS()
            initTts()
        } else {
            AppLog.put("语音引擎重试仍失败，已暂停朗读，保留当前段落位置")
            pauseReadAloud()
        }
    }

    /**
     * 更新朗读速度
     */
    override fun upSpeechRate(reset: Boolean) {
        if (AppConfig.ttsFlowSys) {
            if (reset) {
                clearTTS()
                initTts()
            }
        } else {
            val speechRate = (AppConfig.ttsSpeechRate + 5) / 10f
            textToSpeech?.setSpeechRate(speechRate)
        }
    }

    /**
     * 暂停朗读
     */
    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        playStop()
    }

    /**
     * 恢复朗读
     */
    override fun resumeReadAloud() {
        super.resumeReadAloud()
        play()
    }

    private fun advanceToNextParagraph(lastBatchIndex: Int = nowSpeak) {
        // Advance over every original paragraph represented by this utterance.
        do {
            if (nowSpeak >= contentList.size) {
                nextChapter()
                return
            }
            readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
            paragraphStartPos = 0
            nowSpeak++
            if (nowSpeak >= contentList.size) {
                nextChapter()
                return
            }
        } while (nowSpeak <= lastBatchIndex || contentList[nowSpeak].matches(AppPattern.notReadAloudRegex))
    }

    /**
     * 朗读监听
     */
    private inner class TTSUtteranceListener : UtteranceProgressListener() {

        private val TAG = "TTSUtteranceListener"

        override fun onStart(s: String) {
            dispatchCurrentCallback(s) {
                if (currentBatchEndIndex(s) == null) return@dispatchCurrentCallback
                LogUtils.d(TAG, "onStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$s")
                textChapter?.let {
                    while (pageIndex + 1 < it.pageSize &&
                        readAloudNumber + 1 > it.getReadLength(pageIndex + 1)
                    ) {
                        pageIndex++
                        ReadBook.moveToNextPage()
                    }
                    upTtsProgress(readAloudNumber + 1)
                }
            }
        }

        override fun onDone(s: String) {
            dispatchCurrentCallback(s) {
                val endIndex = currentBatchEndIndex(s) ?: return@dispatchCurrentCallback
                LogUtils.d(TAG, "onDone utteranceId:$s")
                retryBatchStartIndex = -1
                retryBatchCount = 0
                advanceToNextParagraph(endIndex)
            }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            super.onRangeStart(utteranceId, start, end, frame)
            dispatchCurrentCallback(utteranceId) {
                if (currentBatchEndIndex(utteranceId) == null) return@dispatchCurrentCallback
                val msg =
                    "$TAG onRangeStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$utteranceId start:$start end:$end frame:$frame"
                LogUtils.d(TAG, msg)
                textChapter?.let {
                    var pageChangedByProgress = false
                    while (pageIndex + 1 < it.pageSize &&
                        readAloudNumber + start > it.getReadLength(pageIndex + 1)
                    ) {
                        pageIndex++
                        ReadBook.moveToNextPage()
                        pageChangedByProgress = true
                    }
                    if (pageChangedByProgress) {
                        upTtsProgress(readAloudNumber + start)
                    }
                }
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            dispatchCurrentCallback(utteranceId) {
                LogUtils.d(
                    TAG,
                    "onError nowSpeak:$nowSpeak utteranceId:$utteranceId errorCode:$errorCode"
                )
                AppLog.put("语音引擎合成失败，错误代码：$errorCode")
                pauseReadAloud()
            }
        }

        private fun currentBatchEndIndex(utteranceId: String?): Int? {
            val id = utteranceId ?: return null
            val prefix = "${AppConst.APP_TAG}${playbackGeneration.get()}:"
            if (!id.startsWith(prefix)) return null
            return id.substringAfterLast(':').toIntOrNull()
                ?.takeIf { it in nowSpeak until contentList.size }
        }

        private fun dispatchCurrentCallback(utteranceId: String?, block: () -> Unit) {
            val id = utteranceId ?: return
            val prefix = AppConst.APP_TAG
            if (!id.startsWith(prefix)) return
            val generation = id.substring(prefix.length)
                .substringBefore(':')
                .toLongOrNull() ?: return
            if (generation != playbackGeneration.get()) return
            callbackHandler.post {
                synchronized(this@TTSReadAloudService) {
                    if (generation == playbackGeneration.get()) block()
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(s: String) {
            onError(s, TextToSpeech.ERROR)
        }

    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<TTSReadAloudService>(actionStr)
    }

}