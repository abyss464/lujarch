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

package org.lujarch.vault.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executor
import org.lujarch.settings.pages.HeldBytes
import org.lujarch.vault.R
import org.lujarch.vault.TAG

/**
 * A scanned QR code or an opened otpauth link on its way to the codes page:
 * kept in this process only, never in an intent (an intent's extras pass
 * through the system and stay with the task).
 */
object PendingOtp {
    private var held: HeldBytes? = null

    @Synchronized
    fun put(text: String) {
        held?.wipe()
        held = HeldBytes(text.toByteArray(Charsets.UTF_8))
    }

    @Synchronized
    fun take(): HeldBytes? = held.also { held = null }
}

/** QR codes in pictures (a screenshot of a site's 2FA setup). */
object QrImage {
    private val HINTS = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )
    private const val MAX_SIDE = 2048

    /** The text of the first QR code in the image at [uri]; null when there is none. */
    fun decode(context: Context, uri: Uri): String? {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val side = maxOf(info.size.width, info.size.height)
            if (side > MAX_SIDE) {
                decoder.setTargetSize(info.size.width * MAX_SIDE / side, info.size.height * MAX_SIDE / side)
            }
        }
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        bitmap.recycle()
        try {
            return decode(RGBLuminanceSource(w, h, pixels))
        } finally {
            pixels.fill(0)
        }
    }

    fun decode(source: LuminanceSource, tryHarder: Boolean = true): String? {
        val reader = QRCodeReader()
        val hints = if (tryHarder) HINTS else null
        for (bitmap in listOf(BinaryBitmap(HybridBinarizer(source)), BinaryBitmap(GlobalHistogramBinarizer(source)))) {
            try {
                return reader.decode(bitmap, hints).text
            } catch (e: ReaderException) {
                reader.reset()
            }
            if (!tryHarder) break
        }
        return null
    }
}

/**
 * The camera's QR scanner for 2FA accounts: a preview and a frame reader
 * (Camera2), the first QR code read goes to [PendingOtp] and the activity
 * returns RESULT_OK. FLAG_SECURE: the code on screen is the account's secret.
 */
class ScanActivity : Activity() {
    private lateinit var preview: TextureView
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    // Set on the main thread, read by the camera callbacks on the scanner thread.
    @Volatile
    private var camera: CameraDevice? = null
    @Volatile
    private var session: CameraCaptureSession? = null
    @Volatile
    private var reader: ImageReader? = null
    @Volatile
    private var found = false
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setRecentsScreenshotEnabled(false)
        preview = TextureView(this)
        val hint = TextView(this).apply {
            setText(R.string.scan_hint)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 16f
        }
        val bottom = (96 * resources.displayMetrics.density).toInt()
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(preview, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            addView(hint, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
                .apply { bottomMargin = bottom })
        })
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (isFinishing) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
            return
        }
        if (preview.isAvailable) {
            open()
        } else {
            preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = open()
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQUEST_CAMERA) return
        if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "QR scanner: camera permission refused")
            Toast.makeText(this, R.string.scan_no_permission, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onPause() {
        resumed = false
        close()
        super.onPause()
    }

    private fun open() {
        if (camera != null || reader != null || found || isFinishing || !resumed) return
        try {
            val cm = getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull() ?: throw IllegalStateException("no camera")
            val map = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw IllegalStateException("no stream configuration")
            val previewSizes = map.getOutputSizes(SurfaceTexture::class.java).toSet()
            val size = map.getOutputSizes(ImageFormat.YUV_420_888)
                .filter { it in previewSizes && it.width <= 1920 }
                .minByOrNull { Math.abs(it.width.toLong() * it.height - 1280L * 720) }
                ?: Size(640, 480)
            fit(size)
            val t = HandlerThread("qr-scan").also { it.start() }
            val h = Handler(t.looper)
            thread = t
            handler = h
            val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
            r.setOnImageAvailableListener({ decodeFrame(it) }, h)
            reader = r
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (reader !== r) {
                        device.close()
                        return
                    }
                    camera = device
                    startSession(device, r, size, h)
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    if (camera === device) camera = null
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    if (camera === device) camera = null
                    fail("camera error $error")
                }
            }, h)
        } catch (e: Exception) {
            fail("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun startSession(device: CameraDevice, r: ImageReader, size: Size, h: Handler) {
        try {
            val st = preview.surfaceTexture ?: throw IllegalStateException("no preview surface")
            st.setDefaultBufferSize(size.width, size.height)
            val surface = Surface(st)
            val executor = Executor { h.post(it) }
            val outputs = listOf(OutputConfiguration(surface), OutputConfiguration(r.surface))
            device.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (camera !== device) {
                            s.close()
                            return
                        }
                        session = s
                        try {
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(surface)
                                addTarget(r.surface)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            }.build()
                            s.setRepeatingRequest(request, null, h)
                        } catch (e: Exception) {
                            fail("${e.javaClass.simpleName}: ${e.message}")
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) = fail("capture session not configured")
                }))
        } catch (e: Exception) {
            fail("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Portrait page, landscape sensor frames: the preview keeps the frame's aspect across the width. */
    private fun fit(size: Size) {
        val parent = preview.parent as View
        val w = parent.width
        if (w == 0) return
        preview.layoutParams = FrameLayout.LayoutParams(w, w * maxOf(size.width, size.height) / minOf(size.width, size.height), Gravity.CENTER)
    }

    /** On the scanner thread: the frame's luminance plane through the QR reader. */
    private fun decodeFrame(r: ImageReader) {
        val image = try {
            r.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return
        try {
            if (found) return
            val plane = image.planes[0]
            val stride = plane.rowStride
            val buf = plane.buffer
            val data = ByteArray(stride * image.height)
            buf.get(data, 0, minOf(buf.remaining(), data.size))
            try {
                val text = QrImage.decode(PlanarYUVLuminanceSource(data, stride, image.height, 0, 0, image.width, image.height, false), tryHarder = false)
                if (text != null && !found) {
                    found = true
                    runOnUiThread { deliver(text) }
                }
            } finally {
                data.fill(0)
            }
        } catch (e: IllegalStateException) {
            // The reader was closed while the frame was read (the page paused).
        } finally {
            image.close()
        }
    }

    private fun deliver(text: String) {
        if (isFinishing) return
        Log.i(TAG, "QR scanner: a code was read")
        PendingOtp.put(text)
        setResult(RESULT_OK)
        finish()
    }

    private fun fail(why: String) {
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            Log.e(TAG, "QR scanner: the camera cannot be used: $why")
            Toast.makeText(this, R.string.scan_no_camera, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun close() {
        try {
            session?.close()
        } catch (e: Exception) {
            Log.w(TAG, "QR scanner: closing the session: ${e.message}")
        }
        session = null
        camera?.close()
        camera = null
        val r = reader
        reader = null
        val t = thread
        thread = null
        handler = null
        // The reader goes on its own thread, after a frame it may be reading.
        if (t != null) {
            Handler(t.looper).post { r?.close() }
            t.quitSafely()
        } else {
            r?.close()
        }
    }

    companion object {
        private const val REQUEST_CAMERA = 1
    }
}

/**
 * otpauth:// links (a site's "add to authenticator" link, a QR code another
 * scanner read): hands the link to the codes page, which asks before adding.
 */
class OtpLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = intent?.dataString
        if (link != null) {
            PendingOtp.put(link)
            Log.i(TAG, "an otpauth link was opened")
            try {
                startActivity(
                    Pages.intent(this, CodesFragment::class.java, R.string.codes_title,
                        arguments = Bundle().apply { putBoolean(CodesFragment.ARG_TAKE_PENDING, true) })
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e: RuntimeException) {
                Log.e(TAG, "the codes page cannot be opened for an otpauth link", e)
            }
        }
        finish()
    }
}
