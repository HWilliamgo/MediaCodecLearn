package com.example.codec

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

abstract class BaseDecoder : IDecoder {
    companion object {
        val TAG = BaseDecoder::class.java.simpleName
        const val TIMEOUT_FOR_AVAILABLE_CODEC_BUFFER = 2000L
    }

    private var isRunning = true
    private val lock = Object()
    private var isReadyForDecode = false
    protected var codec: MediaCodec? = null
    protected var extractor: IExtractor? = null
    protected var inputBuffers: Array<ByteBuffer>? = null
    protected var outputBuffers: Array<ByteBuffer>? = null

    private var bufferInfo = MediaCodec.BufferInfo()
    private var curState = DecodeState.STOP
    private var mStateListener: IDecoderStateListener? = null
    private var isEOS = false
    private var mVideoWidth = 0
    private var mVideoHeight = 0;
    private var duration: Long = 0L
    private var mEndPos: Long = 0


    private var filePath: String = ""


    // <editor-fold defaultstate="collapsed" desc="初始化">
    private fun init(): Boolean {
        //1. 参数检查
        if (filePath.isEmpty() || !File(filePath).exists()) {
            Log.w(TAG, "文件路径为空")
            mStateListener?.decoderError(this, "文件路径为空")
            return false
        }

        //给子类检查
        if (!check()) return false

        //2. 初始化数据提取器
        extractor = initExtractor(filePath)
        if (extractor == null || extractor?.getFormats() == null) return false

        //3. 初始化参数
        if (!initParams()) return false

        //4. 初始化渲染器
        if (!initRender()) return false

        //5. 初始化解码器
        if (!initCodec()) return false


        return true
    }

    private fun initCodec(): Boolean {
        val valExtractor = extractor
        if (valExtractor == null) {
            return false
        } else {
            //1. 根据编码格式创建解码器
            val mimeType = valExtractor.getFormats().getString(MediaFormat.KEY_MIME)
            if (mimeType == null) {
                return false
            }
            try {
                codec = MediaCodec.createDecoderByType(mimeType)
            } catch (e: IOException) {
                e.printStackTrace()
                return false
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "输入了无效的编码格式=$mimeType")
                e.printStackTrace()
                return false
            }

            //2. 配置解码器
            codec?.let {
                if (!configCodec(it, valExtractor.getFormats())) {
                    waitDecode()
                }
                //3. 启动解码器
                it.start()
                //4. 获取解码器缓冲区
                inputBuffers = it.inputBuffers
                outputBuffers = it.outputBuffers
            }
            return true
        }
    }

    private fun initParams(): Boolean {
        val valExtractor = extractor
        if (valExtractor == null) {
            return false
        } else {
            try {
                valExtractor.getFormats()
                val format = valExtractor.getFormats()
                duration = format.getLong(MediaFormat.KEY_DURATION) / 1000
                if (mEndPos == 0L) {
                    mEndPos = duration
                }
                initSpecParams(valExtractor.getFormats())
            } catch (e: Exception) {
                e.printStackTrace()
                return false
            }
            return true
        }
    }
    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="子类实现">

    /**
     * 初始化渲染器
     */
    abstract fun initRender(): Boolean

    /**
     * 初始化出具提取器
     */
    abstract fun initExtractor(filePath: String): IExtractor?

    /**
     * 检查子类参数
     */
    abstract fun check(): Boolean

    /**
     * 初始化子类特有参数
     */
    abstract fun initSpecParams(formats: MediaFormat)

    /**
     * 配置解码器
     */
    abstract fun configCodec(codec: MediaCodec, formats: MediaFormat): Boolean

    // </editor-fold>

    /**
     * 解码主流程
     */
    override fun run() {
        curState = DecodeState.START
        mStateListener?.decoderPrepare(this)

        while (isRunning) {
            if (curState != DecodeState.START &&
                curState != DecodeState.DECODING &&
                curState != DecodeState.SEEKING
            ) {
                waitDecode()
            }
            if (!isRunning || curState == DecodeState.STOP) {
                isRunning = false;
                break
            }

            if (!isEOS) {
                //将数据输入解码器输入缓冲区
                isEOS = pushBufferToDecoder()
            }
            //将解码好的数据从缓冲区取出
            val index: Int = pullBufferFromDecoder()
            if (index >= 0) {
                outputBuffers?.get(index)?.let { outputBuffer ->
                    render(outputBuffer, bufferInfo)
                }
                codec?.releaseOutputBuffer(index, true)
                if (curState == DecodeState.START) {
                    curState = DecodeState.PAUSE
                }
            }
            if (bufferInfo.flags == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                curState = DecodeState.FINISH
                mStateListener?.decoderFinish(this)
            }
        }
        doneDecode()
        release()
    }

    override fun getFilePath(): String {
        return filePath
    }

    override fun getDuration(): Long {
        return duration
    }

    private fun release() {
        curState = DecodeState.STOP
        isEOS = false
        extractor?.stop()
        try {
            codec?.stop()
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            Log.e(TAG,"codec has been released before")
        }
        codec?.release()
        mStateListener?.decoderDestroy(this)
    }

    private fun pullBufferFromDecoder(): Int {
        val valCodec = codec ?: return -1
        when (val index = valCodec.dequeueOutputBuffer(bufferInfo, 1000)) {
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
            }
            MediaCodec.INFO_TRY_AGAIN_LATER -> {
            }
            MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                outputBuffers = valCodec.outputBuffers
            }
            else -> return index
        }
        return -1;
    }

    /**
     * 从IO读取未解码数据，并将未解码数据传递到解码器
     * @return isEndOfStream
     */
    private fun pushBufferToDecoder(): Boolean {
        val valCodec = codec;
        val valExtractor = extractor;
        if (valCodec == null || valExtractor == null) {
            return false
        }
        //取出解码器返回的缓冲区的下标。当IO读取到未解码数据后，写入缓冲区，并将缓冲区和下标一并传递给解码器解码
        val inputBufferIndex = valCodec.dequeueInputBuffer(TIMEOUT_FOR_AVAILABLE_CODEC_BUFFER)

        var isEndOfStream = false
        if (inputBufferIndex < 0) {
            return isEndOfStream
        }
        val inputBuffer = inputBuffers?.get(inputBufferIndex)
        if (inputBuffer != null) {
            val sampleSize = valExtractor.readBuffer(inputBuffer)
            if (sampleSize < 0) {
                valCodec.queueInputBuffer(
                    inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
                isEndOfStream = true
            } else {
                valCodec.queueInputBuffer(
                    inputBufferIndex, 0, sampleSize, valExtractor.getCurrentTimeStamp(), 0
                )
            }
        }
        return isEndOfStream;
    }

    /**
     * 渲染
     */
    abstract fun render(
        outputBuffers: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo
    )


    private fun waitDecode() {
        try {
            if (curState == DecodeState.PAUSE) {
                mStateListener?.decoderPause(this)
            }
            synchronized(lock) {
                lock.wait()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    protected fun notifyDecode() {
        synchronized(lock) {
            lock.notifyAll()
        }
        if (curState == DecodeState.DECODING) {
            mStateListener?.decoderRunning(this)
        }
    }

    /**
     * 结束解码
     */
    abstract fun doneDecode()
}