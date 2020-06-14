package com.example.codec

import android.media.MediaCodec
import android.util.Log
import java.io.File
import java.lang.Exception
import java.nio.ByteBuffer
import java.util.*

abstract class BaseDecoder : IDecoder {
    companion object {
        val TAG = BaseDecoder::class.java.simpleName
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

    private var filePath: String = ""


    private fun init(): Boolean {
        if (filePath.isEmpty() || !File(filePath).exists()) {
            Log.w(TAG, "文件路径为空")
            mStateListener?.decoderError(this, "文件路径为空")
            return false
        }
    }

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

    private fun release() {

    }

    private fun pullBufferFromDecoder(): Int {

    }

    private fun pushBufferToDecoder(): Boolean {

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