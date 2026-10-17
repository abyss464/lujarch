/*
 * Copyright (C) 2026 The Lujarch Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lujarch.linux.terminal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Display
import java.io.IOException
import java.util.concurrent.Executor
import org.lujarch.linux.ILinux
import org.lujarch.linux.client.Linux
import org.lujarch.linux.client.LinuxException
import org.lujarch.linux.client.TAG

/**
 * The phone's cameras for Linux (LNX-7, Camera): while a program in Linux reads
 * ILinux.STREAM_CAMERA_BACK or STREAM_CAMERA_FRONT, that camera films into it through Camera2,
 * YUV_420_888 at 30 fps converted to NV12 ([Nv12]), every frame STREAM_CAMERA_WIDTH x
 * STREAM_CAMERA_HEIGHT behind its header; Android's camera indicator shows meanwhile. One camera
 * films at a time: Linux's desktop reads one at a time. Android's own dialog asks for the
 * permission the first time.
 *
 * The picture is upright for the display's rotation when filming starts (the sensor is mounted
 * sideways): in landscape the camera's own 1280 x 720 is turned as it is (0 or 180 degrees); in
 * portrait the camera gives 1920 x 1080 (or its nearest 16:9 size), and the upright picture's
 * middle fills the landscape frame.
 */
class Cameras(private val context: Context, private val parts: LinuxParts) {
    private val manager: CameraManager? = context.getSystemService(CameraManager::class.java)
    private val displays: DisplayManager? = context.getSystemService(DisplayManager::class.java)
    private val wanted = LinkedHashSet<String>()
    private var filming: Film? = null

    /** On the parts' thread: a program reads one camera's stream, or none does any more. */
    fun want(stream: String, on: Boolean) {
        if (on) wanted.add(stream) else wanted.remove(stream)
        val current = filming
        if (current != null && (current.stream !in wanted || current.ended)) {
            current.close()
            filming = null
        }
        if (filming != null) return
        val next = wanted.firstOrNull() ?: return
        if (!Linux.lent(context, "camera")) return
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            parts.requestPermissions(arrayOf(Manifest.permission.CAMERA)) { granted ->
                if (granted) want(next, next in wanted)
                else Log.i(TAG, "camera: the permission is not granted; Linux's cameras stay black")
            }
            return
        }
        val m = manager ?: return
        val facing = if (next == ILinux.STREAM_CAMERA_FRONT) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = try {
            m.cameraIdList.firstOrNull { m.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing }
        } catch (e: CameraAccessException) {
            Log.w(TAG, "camera: the list of cameras: ${e.message}")
            null
        }
        if (id == null) {
            Log.i(TAG, "camera: the phone has no camera for $next")
            return
        }
        val fifo = try {
            Linux.blocking("openStream") { it.openStream(next) }
        } catch (e: LinuxException) {
            Log.w(TAG, e.message ?: "openStream")
            return
        }
        filming = try {
            Film(m, next, id, fifo).also { it.open() }
        } catch (e: CameraAccessException) {
            Log.w(TAG, "camera: $next: ${e.message}")
            closeQuietly(fifo)
            null
        } catch (e: RuntimeException) {
            Log.w(TAG, "camera: $next: ${e.message}")
            closeQuietly(fifo)
            null
        }
    }

    fun stop() {
        wanted.clear()
        filming?.close()
        filming = null
    }

    private fun closeQuietly(fifo: ParcelFileDescriptor) {
        try {
            fifo.close()
        } catch (e: IOException) {
            Log.w(TAG, "camera: close: ${e.message}")
        }
    }

    /** One camera filming into one stream: its callbacks on one thread, the frames on another. */
    private inner class Film(
        private val manager: CameraManager,
        val stream: String,
        private val id: String,
        private val fifo: ParcelFileDescriptor,
    ) {
        private val chars = manager.getCameraCharacteristics(id)
        private val rotation: Int = rotationFor(chars)
        private val size: Size = pickSize(chars, rotation % 180 != 0)
        private val range: Range<Int>? = pickRange(chars)
        /** The camera's frames are Linux's size and need only a turn by 0 or 180 degrees. */
        private val direct = rotation % 180 == 0 && size.width == OUT_W && size.height == OUT_H
        private val cameraThread = HandlerThread("LinuxCamera").apply { start() }
        private val framesThread = HandlerThread("LinuxCameraFrames").apply { start() }
        private val cameraHandler = Handler(cameraThread.looper)
        private val framesHandler = Handler(framesThread.looper)
        private val frame = ByteArray(HEADER + OUT_W * OUT_H * 3 / 2)
        private val lock = Any()
        private var device: CameraDevice? = null
        private var session: CameraCaptureSession? = null
        private var reader: ImageReader? = null
        @Volatile private var closed = false
        /** Linux stopped reading, or the camera ended on Android's side. */
        @Volatile var ended = false
            private set
        private var warnedLayout = false
        private var frames = 0L

        init {
            // The header: magic, then the frame's size, little-endian.
            val magic = ILinux.STREAM_FRAME_MAGIC.toByteArray(Charsets.US_ASCII)
            System.arraycopy(magic, 0, frame, 0, 4)
            val n = OUT_W * OUT_H * 3 / 2
            for (i in 0 until 4) frame[4 + i] = (n ushr (8 * i)).toByte()
        }

        fun open() {
            val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
            r.setOnImageAvailableListener({ onImage(it) }, framesHandler)
            synchronized(lock) { reader = r }
            val executor = Executor { cameraHandler.post(it) }
            val sessionCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    val d = synchronized(lock) {
                        if (closed) null else { session = s; device }
                    }
                    if (d == null) {
                        s.close()
                        return
                    }
                    try {
                        val request = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(r.surface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            if (range != null) set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                        }
                        s.setRepeatingRequest(request.build(), null, cameraHandler)
                        Log.i(TAG, "camera: $stream films for Linux, ${size.width} x ${size.height}, turned $rotation")
                    } catch (e: CameraAccessException) {
                        gone("the repeating request: ${e.message}")
                    } catch (e: RuntimeException) {
                        gone("the repeating request: ${e.message}")
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) = gone("the session could not be configured")
            }
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    val keep = synchronized(lock) {
                        if (closed) false else { device = d; true }
                    }
                    if (!keep) {
                        d.close()
                        return
                    }
                    try {
                        d.createCaptureSession(SessionConfiguration(
                            SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(r.surface)), executor, sessionCallback))
                    } catch (e: CameraAccessException) {
                        gone("the session: ${e.message}")
                    } catch (e: RuntimeException) {
                        gone("the session: ${e.message}")
                    }
                }

                override fun onDisconnected(d: CameraDevice) = gone("another app took the camera")

                override fun onError(d: CameraDevice, code: Int) = gone("camera error $code")
            }, cameraHandler)
        }

        /** The camera ended on Android's side (another app took it): it is asked for again soon. */
        private fun gone(why: String) {
            Log.w(TAG, "camera: $stream: $why")
            ended = true
            close()
            parts.handler.postDelayed({ want(stream, stream in wanted) }, RETRY_MS)
        }

        /** The frames' thread: the newest image, converted and written whole. */
        private fun onImage(r: ImageReader) {
            val image = try {
                r.acquireLatestImage()
            } catch (e: IllegalStateException) {
                null
            } ?: return
            val ok = try {
                val p = image.planes
                p.size == 3 && p[0].pixelStride == 1 && if (direct && image.width == OUT_W && image.height == OUT_H) {
                    Nv12.nativeConvert(
                        p[0].buffer, p[0].rowStride, p[1].buffer, p[2].buffer, p[1].rowStride, p[1].pixelStride,
                        image.width, image.height, rotation, frame, HEADER,
                    )
                } else {
                    Nv12.nativeFit(
                        p[0].buffer, p[0].rowStride, p[1].buffer, p[2].buffer, p[1].rowStride, p[1].pixelStride,
                        image.width, image.height, rotation, OUT_W, OUT_H, frame, HEADER,
                    )
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "camera: a frame: ${e.message}")
                false
            } finally {
                image.close()
            }
            if (!ok) {
                if (!warnedLayout) Log.w(TAG, "camera: $stream gives a YUV layout the converter cannot read")
                warnedLayout = true
                return
            }
            write()
        }

        /**
         * One frame whole into the FIFO, or none when Linux lags behind at its start; a frame
         * begun is finished unless Linux stops reading or filming ends, and Linux finds the next
         * frame by its header then.
         */
        private fun write() {
            val fd = fifo.fileDescriptor
            val poll = StructPollfd().apply {
                this.fd = fd
                events = OsConstants.POLLOUT.toShort()
            }
            var off = 0
            while (off < frame.size && !closed) {
                poll.revents = 0
                val ready = try {
                    Os.poll(arrayOf(poll), if (off == 0) 0 else WRITE_WAIT_MS)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue
                    0
                }
                if (poll.revents.toInt() and OsConstants.POLLERR != 0) {
                    stopped()
                    return
                }
                if (ready == 0) {
                    if (off == 0) return
                    continue
                }
                try {
                    off += Os.write(fd, frame, off, frame.size - off)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue
                    if (e.errno != OsConstants.EPIPE) Log.w(TAG, "camera: $stream: ${e.message}")
                    stopped()
                    return
                }
            }
            if (++frames % 900 == 0L) Log.i(TAG, "camera: $stream: $frames frames for Linux")
        }

        /** Linux stopped reading: lujarch_linux tells the parts, which close this. */
        private fun stopped() {
            ended = true
        }

        /** Closes the camera, its threads and the stream; any thread, any number of times. */
        fun close() {
            val s: CameraCaptureSession?
            val d: CameraDevice?
            val r: ImageReader?
            synchronized(lock) {
                if (closed) return
                closed = true
                s = session
                session = null
                d = device
                device = null
                r = reader
                reader = null
            }
            try {
                s?.close()
            } catch (e: RuntimeException) {
                Log.w(TAG, "camera: close: ${e.message}")
            }
            try {
                d?.close()
            } catch (e: RuntimeException) {
                Log.w(TAG, "camera: close: ${e.message}")
            }
            // The frames' thread ends with the reader and the stream.
            framesHandler.post {
                try {
                    r?.close()
                } catch (e: RuntimeException) {
                    Log.w(TAG, "camera: close: ${e.message}")
                }
                closeQuietly(fifo)
                framesThread.quitSafely()
            }
            cameraThread.quitSafely()
            Log.i(TAG, "camera: $stream stopped after $frames frames")
        }
    }

    /**
     * The capture size: Linux's 1280 x 720 for a picture turned by 0 or 180 degrees; 1920 x 1080
     * when it is turned sideways ([turned]: its middle third then fills the frame). Else the
     * camera's 16:9 size nearest to that width, else the nearest in area.
     */
    private fun pickSize(chars: CameraCharacteristics, turned: Boolean): Size {
        val w = if (turned) PORTRAIT_W else OUT_W
        val h = if (turned) PORTRAIT_H else OUT_H
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.filter { it.width % 2 == 0 && it.height % 2 == 0 }.orEmpty()
        if (sizes.isEmpty()) return Size(OUT_W, OUT_H)
        sizes.firstOrNull { it.width == w && it.height == h }?.let { return it }
        val wide = sizes.filter { it.width * 9 == it.height * 16 }
        if (wide.isNotEmpty()) return wide.minByOrNull { kotlin.math.abs(it.width - w) }!!
        return sizes.minByOrNull { kotlin.math.abs(it.width.toLong() * it.height - w.toLong() * h) }!!
    }

    /** A steady 30 fps if the camera offers it, else the 30 fps range with the highest floor. */
    private fun pickRange(chars: CameraCharacteristics): Range<Int>? {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        ranges.firstOrNull { it.lower == TARGET_FPS && it.upper == TARGET_FPS }?.let { return it }
        return ranges.filter { it.upper == TARGET_FPS }.maxByOrNull { it.lower }
    }

    /**
     * The clockwise turn that makes the sensor's image upright on the display as it is now (the
     * camera's documented orientation formula; a front camera turns the other way, unmirrored).
     */
    private fun rotationFor(chars: CameraCharacteristics): Int {
        val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val displayRotation = displays?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0
        // The display turned by 90 degrees: the phone turned the other way, 270 degrees clockwise.
        val device = (360 - displayRotation * 90) % 360
        val front = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        return ((sensor + (if (front) -device else device)) % 360 + 360) % 360
    }

    private companion object {
        val OUT_W = ILinux.STREAM_CAMERA_WIDTH
        val OUT_H = ILinux.STREAM_CAMERA_HEIGHT
        /** The magic and the frame's size. */
        const val HEADER = 8
        const val PORTRAIT_W = 1920
        const val PORTRAIT_H = 1080
        const val TARGET_FPS = 30
        /** How long a frame begun waits for Linux to take more of it. */
        const val WRITE_WAIT_MS = 200
        const val RETRY_MS = 3000L
    }
}
