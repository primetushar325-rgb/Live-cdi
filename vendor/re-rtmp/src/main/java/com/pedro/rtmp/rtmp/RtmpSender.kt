/*
 * Copyright (C) 2024 pedroSG94.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pedro.rtmp.rtmp

import android.util.Log
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.common.base.BaseSender
import com.pedro.common.frame.MediaFrame
import com.pedro.common.onMainThread
import com.pedro.rtmp.flv.BasePacket
import com.pedro.rtmp.flv.FlvPacket
import com.pedro.rtmp.flv.FlvType
import com.pedro.rtmp.flv.audio.packet.AacPacket
import com.pedro.rtmp.flv.audio.packet.G711Packet
import com.pedro.rtmp.flv.audio.packet.OpusPacket
import com.pedro.rtmp.flv.video.packet.Av1Packet
import com.pedro.rtmp.flv.video.packet.H264Packet
import com.pedro.rtmp.flv.video.packet.H265Packet
import com.pedro.rtmp.utils.socket.RtmpSocket
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Created by pedro on 8/04/21.
 */
class RtmpSender(
  connectChecker: ConnectChecker,
  private val commandsManager: CommandsManager,
  private val reportStage: (String) -> Unit = {}
): BaseSender(connectChecker, "RtmpSender") {

  private var audioPacket: BasePacket = AacPacket()
  private var videoPacket: BasePacket = H264Packet()
  var socket: RtmpSocket? = null

  // These counters advance only after the corresponding FLV packet's socket flush succeeds.
  // Unlike BaseSender's per-connection counters, they remain cumulative across RTMP retries.
  private val successfulVideoPackets = AtomicLong(0)
  private val successfulAudioPackets = AtomicLong(0)
  private val successfulVideoConfigs = AtomicLong(0)
  private val successfulAudioConfigs = AtomicLong(0)
  private val successfulVideoKeyframes = AtomicLong(0)
  private val successfulMediaBytes = AtomicLong(0)
  private val videoConfigReported = AtomicBoolean(false)
  private val audioConfigReported = AtomicBoolean(false)
  private val videoPacketReported = AtomicBoolean(false)
  private val audioPacketReported = AtomicBoolean(false)
  private val keyframeReported = AtomicBoolean(false)

  fun getSuccessfulVideoPackets(): Long = successfulVideoPackets.get()
  fun getSuccessfulAudioPackets(): Long = successfulAudioPackets.get()
  fun getSuccessfulVideoConfigs(): Long = successfulVideoConfigs.get()
  fun getSuccessfulAudioConfigs(): Long = successfulAudioConfigs.get()
  fun getSuccessfulVideoKeyframes(): Long = successfulVideoKeyframes.get()
  fun getSuccessfulMediaBytes(): Long = successfulMediaBytes.get()

  override fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
    videoPacket = when (commandsManager.videoCodec) {
      VideoCodec.H265 -> {
        if (vps == null || pps == null) throw IllegalArgumentException("pps or vps can't be null with h265")
        H265Packet().apply { sendVideoInfo(sps, pps, vps) }
      }
      VideoCodec.AV1 -> {
        Av1Packet().apply { sendVideoInfo(sps) }
      }
      else -> {
        if (pps == null) throw IllegalArgumentException("pps can't be null with h264")
        H264Packet().apply { sendVideoInfo(sps, pps) }
      }
    }
  }

  override fun setAudioInfo(sampleRate: Int, isStereo: Boolean) {
    audioPacket = when (commandsManager.audioCodec) {
      AudioCodec.G711 -> G711Packet().apply { sendAudioInfo() }
      AudioCodec.AAC -> AacPacket().apply { sendAudioInfo(sampleRate, isStereo) }
      AudioCodec.OPUS -> OpusPacket().apply { sendAudioInfo(sampleRate, isStereo) }
    }
  }

  override suspend fun onRun() {
    while (scope.isActive && running) {
      val error = runCatching {
        val mediaFrame = runInterruptible { queue.take() }
        getFlvPacket(mediaFrame) { flvPacket ->
          val activeSocket = socket ?: return@getFlvPacket
          val size = if (flvPacket.type == FlvType.VIDEO) {
            commandsManager.sendVideoPacket(flvPacket, activeSocket).toLong()
          } else {
            commandsManager.sendAudioPacket(flvPacket, activeSocket).toLong()
          }
          if (size <= 0L) return@getFlvPacket

          // The send methods flush before returning. Count only those completed writes.
          bytesSend.addAndGet(size)
          bytesSendPerSecond.addAndGet(size)
          successfulMediaBytes.addAndGet(size)
          if (flvPacket.type == FlvType.VIDEO) {
            val packetType = flvPacket.buffer.getOrNull(1)
            if (packetType == H264Packet.Type.SEQUENCE.value) {
              successfulVideoConfigs.incrementAndGet()
              if (videoConfigReported.compareAndSet(false, true)) reportStage("H264_CONFIG_SENT")
            } else if (packetType == H264Packet.Type.NALU.value) {
              successfulVideoPackets.incrementAndGet()
              videoFramesSent.incrementAndGet()
              if (videoPacketReported.compareAndSet(false, true)) reportStage("VIDEO_PACKET_SENT")
              val isKeyframe = ((flvPacket.buffer[0].toInt() and 0xF0) shr 4) == 1
              if (isKeyframe) {
                successfulVideoKeyframes.incrementAndGet()
                if (keyframeReported.compareAndSet(false, true)) reportStage("VIDEO_KEYFRAME_SENT")
              }
            }
            if (isEnableLogs) Log.i(TAG, "wrote Video packet, size $size")
          } else {
            val packetType = flvPacket.buffer.getOrNull(1)
            if (packetType == AacPacket.Type.SEQUENCE.mark) {
              successfulAudioConfigs.incrementAndGet()
              if (audioConfigReported.compareAndSet(false, true)) reportStage("AAC_CONFIG_SENT")
            } else if (packetType == AacPacket.Type.RAW.mark) {
              successfulAudioPackets.incrementAndGet()
              audioFramesSent.incrementAndGet()
              if (audioPacketReported.compareAndSet(false, true)) reportStage("AUDIO_PACKET_SENT")
            }
            if (isEnableLogs) Log.i(TAG, "wrote Audio packet, size $size")
          }
        }
      }.exceptionOrNull()
      if (error != null) {
        reportStage("TRANSPORT_ERROR")
        onMainThread {
          connectChecker.onConnectionFailed("RTMP packet write failed")
        }
        Log.e(TAG, "RTMP media packet write failed (${error.javaClass.simpleName})")
        running = false
        return
      }
    }
  }

  override suspend fun stopImp(clear: Boolean) {
    audioPacket.reset(clear)
    videoPacket.reset(clear)
  }

  private suspend fun getFlvPacket(mediaFrame: MediaFrame?, callback: suspend (FlvPacket) -> Unit) {
    if (mediaFrame == null) return
    when (mediaFrame.type) {
      MediaFrame.Type.VIDEO -> videoPacket.createFlvPacket(mediaFrame) { callback(it) }
      MediaFrame.Type.AUDIO -> audioPacket.createFlvPacket(mediaFrame) { callback(it) }
    }
  }
}