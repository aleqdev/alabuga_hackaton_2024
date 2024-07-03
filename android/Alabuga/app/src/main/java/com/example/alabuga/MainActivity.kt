package com.example.alabuga

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import coil.load
import com.example.alabuga.databinding.ActivityMainBinding
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


typealias LumaListener = (luma: Double) -> Unit

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding

    private var imageCapture: ImageCapture? = null

    private lateinit var cameraExecutor: ExecutorService

    private val client: OkHttpClient = OkHttpClient()

    private val activityResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions())
        { permissions ->
            // Handle Permission granted/rejected
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && it.value == false)
                    permissionGranted = false
            }
            if (!permissionGranted) {
                Toast.makeText(baseContext,
                    "Permission request denied",
                    Toast.LENGTH_SHORT).show()
            } else {
                startCamera()
            }
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val policy = ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)

        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }

        viewBinding.imageCaptureButton.setOnClickListener { takePhoto() }
        // viewBinding.videoCaptureButton.setOnClickListener { captureVideo() }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun resizeBitmap(image: Bitmap, maxHeight: Int, maxWidth: Int): Bitmap {
        if (maxHeight > 0 && maxWidth > 0) {

            val sourceWidth: Int = image.width
            val sourceHeight: Int = image.height

            var targetWidth = maxWidth
            var targetHeight = maxHeight

            val sourceRatio = sourceWidth.toFloat() / sourceHeight.toFloat()
            val targetRatio = maxWidth.toFloat() / maxHeight.toFloat()

            if (targetRatio > sourceRatio) {
                targetWidth = (maxHeight.toFloat() * sourceRatio).toInt()
            } else {
                targetHeight = (maxWidth.toFloat() / sourceRatio).toInt()
            }

            return Bitmap.createScaledBitmap(
                image, targetWidth, targetHeight, true
            )

        } else {
            throw RuntimeException()
        }
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun
                        onCaptureSuccess(image: ImageProxy){
                    val bitmap = resizeBitmap(imageProxyToBitmap(image), 800, 800);

                    val stream = ByteArrayOutputStream()

                    bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
                    val bytes = stream.toByteArray()

                    val requestBody = bytes.toRequestBody(
                        "application/octet-stream".toMediaTypeOrNull(),
                        0,
                        bytes.size
                    )

                    val request = Request.Builder()
                        .url("https://alabuga.aleq.dev/process-bytes")
                        .post(requestBody)
                        .build()

                    var workid = "";

                    try {
                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                Toast.makeText(baseContext,
                                    response.message,
                                    Toast.LENGTH_SHORT).show()
                                return
                            }
                            Toast.makeText(baseContext,
                                "Отправлено",
                                Toast.LENGTH_SHORT).show()
                            workid = response.body?.string()!!
                        }
                    } catch (exception: Exception) {
                        Toast.makeText(baseContext,
                            exception.message,
                            Toast.LENGTH_SHORT).show()
                    }

                    image.close()

                    val doneRequest = Request.Builder()
                        .url("https://alabuga.aleq.dev/work/$workid/done")
                        .get()
                        .build()

                    try {
                        client.newCall(doneRequest).execute().use { response ->
                            if (!response.isSuccessful) {
                                Toast.makeText(baseContext,
                                    response.message,
                                    Toast.LENGTH_SHORT).show()
                                return
                            }
                        }
                    } catch (exception: Exception) {
                        Toast.makeText(baseContext,
                            exception.message,
                            Toast.LENGTH_SHORT).show()
                    }

                    viewBinding.imageResult.load("https://alabuga.aleq.dev/work/$workid/results/image.png")

                    super.onCaptureSuccess(image)
                }
            }
        )
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val planeProxy = image.planes[0]
        val buffer: ByteBuffer = planeProxy.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val options = BitmapFactory.Options()
        options.inSampleSize = 1
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

        val exif = ExifInterface(bytes.inputStream())
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val rotatedBitmap = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(bitmap, 90)
            ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(bitmap, 180)
            ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(bitmap, 270)
            else -> bitmap
        }

        return rotatedBitmap
    }

    private fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .build()

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture)

            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }



    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }


    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "Alabuga"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.INTERNET
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }
}
