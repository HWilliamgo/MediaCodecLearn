package com.example.codec

import android.media.MediaFormat
import androidx.annotation.WorkerThread
import java.nio.ByteBuffer

@WorkerThread
interface IExtractor {
    /**
     * 获取音视频格式参数
     */
    fun getFormats(): MediaFormat

    /**
     * 读取音视频数据
     */
    fun readBuffer(byteBuffer: ByteBuffer): Int

    /**
     * 获取当前帧时间
     */
    fun getCurrentTimeStamp(): Long

    /**
     * Seek到指定位置，并返回实际帧的时间戳
     */
    fun seek(pos: Long): Long

    /**
     * 设置开始的位置
     */
    fun setStartPos(pos: Long)

    /**
     * 停止
     */
    fun stop()
}