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
    private val TAG = "TTSReadAloudService"

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
                isSpeakable = { !it.matches(AppPattern.notReadAloudRegex) }
            )
            var added = false
            for (batch in batches) {
                ensureActive()
                val utteranceId = "${AppConst.APP_TAG}${generation}:${batch.startIndex}:${batch.endIndex}"
                val result = tts.runCatching {
                    speak(
                        batch.text,
                        if (added) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH,
                        null,
                        utteranceId
                    )
                }.getOrElse {
                    AppLog.put("tts出错\n${it.localizedMessage}", it, true)
                    TextToSpeech.ERROR
                }
                if (result == TextToSpeech.ERROR) {
                    if (!added) {
                        AppLog.put("tts出错 尝试重新初始化")
                        clearTTS()
                        initTts()
                        return@execute
                    }
                    AppLog.put("tts朗读出错:${batch.text}")
                }
                added = true
            }
            LogUtils.d(TAG, "朗读内容添加完成，合并后请求数 ${batches.size}")
        }.onError {
            AppLog.put("tts朗读出错\n${it.localizedMessage}", it, true)
        }
    }

    override fun playStop() {
        playbackGeneration.incrementAndGet()
        speakJob?.cancel()
        textToSpeech?.runCatching {
            stop()
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

    /**
     * 朗读监听
     */
    private inner class TTSUtteranceListener : UtteranceProgressListener() {

        private val TAG = "TTSUtteranceListener"

        override fun onStart(s: String) {
            if (currentBatchEndIndex(s) == null) return
            LogUtils.d(TAG, "onStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$s")
            textChapter?.let {
                if (readAloudNumber + 1 > it.getReadLength(pageIndex + 1)) {
                    pageIndex++
                    ReadBook.moveToNextPage()
                }
                upTtsProgress(readAloudNumber + 1)
            }
        }

        override fun onDone(s: String) {
            val batchEndIndex = currentBatchEndIndex(s) ?: return
            LogUtils.d(TAG, "onDone utteranceId:$s batchEndIndex:$batchEndIndex")
            nextParagraph(batchEndIndex)
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            super.onRangeStart(utteranceId, start, end, frame)
            if (currentBatchEndIndex(utteranceId) == null) return
            val msg =
                "$TAG onRangeStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$utteranceId start:$start end:$end frame:$frame"
            LogUtils.d(TAG, msg)
            textChapter?.let {
                if (readAloudNumber + start > it.getReadLength(pageIndex + 1)) {
                    pageIndex++
                    ReadBook.moveToNextPage()
                    upTtsProgress(readAloudNumber + start)
                }
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            val batchEndIndex = currentBatchEndIndex(utteranceId) ?: return
            LogUtils.d(
                TAG,
                "onError nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$utteranceId errorCode:$errorCode"
            )
            nextParagraph(batchEndIndex)
        }

        private fun currentBatchEndIndex(utteranceId: String?): Int? {
            val id = utteranceId ?: return null
            val prefix = "${AppConst.APP_TAG}${playbackGeneration.get()}:"
            if (!id.startsWith(prefix)) return null
            return id.substringAfterLast(':').toIntOrNull()
                ?.takeIf { it in nowSpeak until contentList.size }
        }

        private fun nextParagraph(lastBatchIndex: Int = nowSpeak) {
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

        @Deprecated("Deprecated in Java")
        override fun onError(s: String) {
            val batchEndIndex = currentBatchEndIndex(s) ?: return
            LogUtils.d(TAG, "onError nowSpeak:$nowSpeak pageIndex:$pageIndex s:$s")
            nextParagraph(batchEndIndex)
        }

    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<TTSReadAloudService>(actionStr)
    }

}