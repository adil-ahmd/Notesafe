package com.example.notesafe

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var currentPhotoPath: String
    private lateinit var tflite: Interpreter
    private lateinit var imgPreview: ImageView
    private lateinit var resultTextView: TextView
    private lateinit var cameraLauncher: ActivityResultLauncher<Intent>
    private lateinit var galleryLauncher: ActivityResultLauncher<String>
    private lateinit var cropImageLauncher: ActivityResultLauncher<CropImageContractOptions>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Load model
        loadModel()

        // Initialize UI elements
        // In onCreate
        val btnIdentifyYourself: Button = findViewById(R.id.btnIdentifyYourself)
        Log.d("IdentifyButton", "btnIdentifyYourself: $btnIdentifyYourself")
        btnIdentifyYourself.setOnClickListener {
            Log.d("IdentifyButton", "Identify Yourself button clicked")
            val intent = Intent(this, NoteIdentificationActivity::class.java)
            startActivity(intent)
        }
        val btnCapturePhoto: Button = findViewById(R.id.btnCapturePhoto)
        val btnUploadPhoto: Button = findViewById(R.id.btnUploadPhoto)
        val btnEnglish: Button = findViewById(R.id.btnEnglish)
        val btnHindi: Button = findViewById(R.id.btnHindi)
        imgPreview = findViewById(R.id.imgPreview)
        resultTextView = findViewById(R.id.resultTextView)

        // Log to verify button initialization
        Log.d("LanguageButtons", "btnEnglish: $btnEnglish, btnHindi: $btnHindi")

        // Language switcher button clicks
        btnEnglish.setOnClickListener {
            Log.d("LanguageButtons", "English button clicked")
            setLocale("en")
        }
        btnHindi.setOnClickListener {
            Log.d("LanguageButtons", "Hindi button clicked")
            setLocale("hi")
        }

        // Register the camera launcher
        cameraLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    val photoFile = File(currentPhotoPath)
                    if (photoFile.exists()) {
                        val photoUri = FileProvider.getUriForFile(
                            this,
                            "com.example.notesafe.fileprovider",
                            photoFile
                        )
                        startCrop(photoUri) // Start cropping after capturing the photo
                    } else {
                        Toast.makeText(
                            this,
                            getString(R.string.error_photo_metadata),
                            Toast.LENGTH_SHORT
                        ).show()
                        Log.e("CameraDebug", "File not found at: $currentPhotoPath")
                    }
                } else {
                    Toast.makeText(this, getString(R.string.error_photo_capture), Toast.LENGTH_SHORT).show()
                }
            }

        // Register the gallery launcher
        galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                startCrop(it) // Start cropping after selecting an image from the gallery
            }
        }

        // Register the crop result launcher
        cropImageLauncher = registerForActivityResult(CropImageContract()) { result ->
            if (result.isSuccessful) {
                // Use the returned cropped image Uri
                val croppedUri = result.uriContent
                croppedUri?.let {
                    handleImage(it) // Handle the cropped image
                }
            } else {
                // Handle any errors
                val exception = result.error
                Toast.makeText(this, getString(R.string.error_crop, exception?.message), Toast.LENGTH_SHORT).show()
            }
        }

        // Handle button clicks
        btnCapturePhoto.setOnClickListener {
            if (checkPermissions()) {
                try {
                    val photoFile = createImageFile()
                    val photoUri = FileProvider.getUriForFile(
                        this,
                        "com.example.notesafe.fileprovider",
                        photoFile
                    )

                    val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    cameraLauncher.launch(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, getString(R.string.error_camera, e.message), Toast.LENGTH_SHORT)
                        .show()
                }
            } else {
                requestPermissions()
            }
        }

        btnUploadPhoto.setOnClickListener {
            if (checkPermissions()) {
                galleryLauncher.launch("image/*")
            } else {
                requestPermissions()
            }
        }
    }

    // Set locale and refresh activity
    private fun setLocale(languageCode: String) {
        Log.d("LanguageButtons", "Setting locale to: $languageCode")
        try {
            val locale = Locale(languageCode)
            Locale.setDefault(locale)
            val config = resources.configuration
            config.setLocale(locale)
            resources.updateConfiguration(config, resources.displayMetrics)
            Log.d("LanguageButtons", "Locale set, recreating activity")
            recreate() // Refresh activity
        } catch (e: Exception) {
            Log.e("LanguageButtons", "Error setting locale: ${e.message}", e)
            Toast.makeText(this, "Error changing language", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createImageFile(): File {
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val photoFile = File.createTempFile(
            "photo_${System.currentTimeMillis()}", // Prefix for unique file name
            ".jpg", // Suffix for file type
            storageDir // Directory
        )
        currentPhotoPath = photoFile.absolutePath // Save the path globally
        return photoFile
    }

    // Load the TFLite model
    private fun loadModel() {
        val assetFileDescriptor = assets.openFd("model-2.tflite")
        val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = fileInputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        val mappedByteBuffer =
            fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        tflite = Interpreter(mappedByteBuffer)
    }

    private fun checkPermissions(): Boolean {
        val cameraPermission = ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
        val readPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            PackageManager.PERMISSION_GRANTED
        }
        return cameraPermission == PackageManager.PERMISSION_GRANTED && readPermission == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(android.Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.READ_MEDIA_IMAGES
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(android.Manifest.permission.READ_MEDIA_IMAGES)
        }
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            // Permissions granted
        } else {
            Toast.makeText(
                this,
                getString(R.string.permissions_required),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun startCrop(imageUri: Uri) {
        // Create CropImageOptions using the constructor
        val cropImageOptions = CropImageOptions().apply {
            guidelines = CropImageView.Guidelines.ON // Show guidelines
            autoZoomEnabled = true // Enable auto-zoom
            multiTouchEnabled = true // Enable multi-touch
            allowRotation = true // Allow image rotation
            allowCounterRotation = true // Allow counter-rotation
            rotationDegrees = 2
            cropShape = CropImageView.CropShape.RECTANGLE // Set crop shape (RECTANGLE or OVAL)
            outputCompressFormat = Bitmap.CompressFormat.JPEG // Set output format
            outputCompressQuality = 90 // Set output quality (0-100)
            initialCropWindowPaddingRatio = 0f
        }

        // Create CropImageContractOptions
        val cropImageContractOptions = CropImageContractOptions(imageUri, cropImageOptions)

        // Launch the cropping activity
        cropImageLauncher.launch(cropImageContractOptions)
    }

    private fun handleImage(imageUri: Uri) {
        try {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // For Android 9.0 (Pie) and above
                val source = ImageDecoder.createSource(contentResolver, imageUri)
                ImageDecoder.decodeBitmap(source)
            } else {
                // For older versions
                MediaStore.Images.Media.getBitmap(contentResolver, imageUri)
            }

            // Display the image in the ImageView
            imgPreview.setImageBitmap(bitmap)

            // Process the image using the TensorFlow Lite model
            processImage(bitmap)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_loading_image), Toast.LENGTH_SHORT).show()
            Log.e("ImageError", "Error loading image", e)
            resultTextView.text = getString(R.string.error_loading_image)
        }
    }

    private fun preprocessImage(bitmap: Bitmap): ByteBuffer {
        // Copy the bitmap to ensure it's mutable
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)

        // Resize the bitmap to (300, 100) as expected by the model
        val resizedBitmap = Bitmap.createScaledBitmap(mutableBitmap, 300, 100, true)

        // Log the dimensions for debugging
        Log.d("BitmapDimensions", "Width: ${resizedBitmap.width}, Height: ${resizedBitmap.height}")

        // Allocate a ByteBuffer to hold the image data
        val buffer = ByteBuffer.allocateDirect(300 * 100 * 3 * 4).order(ByteOrder.nativeOrder())

        // Iterate over the bitmap and normalize pixel values to [0, 1]
        for (y in 0 until 100) {  // Height = 100
            for (x in 0 until 300) {  // Width = 300
                val pixel = resizedBitmap.getPixel(x, y)
                val r = Color.red(pixel) / 255.0f
                val g = Color.green(pixel) / 255.0f
                val b = Color.blue(pixel) / 255.0f
                buffer.putFloat(r)
                buffer.putFloat(g)
                buffer.putFloat(b)
            }
        }

        return buffer
    }

    private fun processImage(bitmap: Bitmap) {
        try {
            val inputBuffer = preprocessImage(bitmap)
            val outputTensor = tflite.getOutputTensor(0) // Get the output tensor
            val outputShape = outputTensor.shape() // Get the shape of the output tensor
            val outputBuffer = Array(outputShape[0]) { FloatArray(outputShape[1]) } // Create buffer with correct shape

            // Run inference
            tflite.run(inputBuffer, outputBuffer)

            // Log output shape and probabilities for debugging
            Log.d("OutputShape", outputShape.contentToString())
            Log.d("Probabilities", outputBuffer[0].contentToString())

            // Interpret the output
            val probabilities = outputBuffer[0] // Get the probability array
            val maxProbability = probabilities.maxOrNull() // Find the highest probability
            if (maxProbability != null) {
                val predictedClassIndex = probabilities.indexOfFirst { it == maxProbability }
                val labels = listOf("fake200", "real200", "fake500", "real500") // Note: Labels not localized
                if (predictedClassIndex in labels.indices) {
                    val predictedLabel = labels[predictedClassIndex]
                    val confidenceScore = maxProbability * 100 // Convert to percentage

                    // Display prediction and confidence in Toast
                    val resultMessage = getString(R.string.prediction_format, predictedLabel, confidenceScore)
                    Toast.makeText(this, resultMessage, Toast.LENGTH_LONG).show()

                    // Display in TextView with color based on confidence
                    resultTextView.text = resultMessage
                    if (confidenceScore < 70) {
                        resultTextView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                    } else {
                        resultTextView.setTextColor(ContextCompat.getColor(this, android.R.color.black))
                    }
                } else {
                    Toast.makeText(this, getString(R.string.error_invalid_index), Toast.LENGTH_LONG).show()
                    resultTextView.text = getString(R.string.error_invalid_index)
                }
            } else {
                Toast.makeText(this, getString(R.string.error_no_predictions), Toast.LENGTH_LONG).show()
                resultTextView.text = getString(R.string.error_no_predictions)
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_loading_image), Toast.LENGTH_SHORT).show()
            resultTextView.text = getString(R.string.error_loading_image)
            Log.e("TensorFlowError", "Error processing image", e)
        }
    }
}