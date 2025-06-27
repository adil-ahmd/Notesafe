package com.example.notesafe

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.location.Location
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
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.firebase.auth.FirebaseAuth
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var currentPhotoPath: String
    private lateinit var tflite: Interpreter
    private lateinit var imgPreview: ImageView
    private lateinit var resultTextView: TextView
    private lateinit var btnCapturePhoto: Button
    private lateinit var btnUploadPhoto: Button
    private lateinit var btnEnglish: Button
    private lateinit var btnHindi: Button
    private lateinit var btnIdentifyYourself: Button
    private lateinit var btnLogout: Button
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var lastKnownLocation: Location? = null // Store last known location

    // Register ActivityResultLaunchers once
    private val locationPermissionRequestLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        var hasFineLocation = false
        var hasCoarseLocation = false
        permissions.forEach { (permission, isGranted) ->
            when (permission) {
                Manifest.permission.ACCESS_FINE_LOCATION -> hasFineLocation = isGranted
                Manifest.permission.ACCESS_COARSE_LOCATION -> hasCoarseLocation = isGranted
            }
        }
        if (hasFineLocation || hasCoarseLocation) {
            getLastLocation()
        } else {
            Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val cameraPermissionRequestLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val writeStorageGranted = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { // Q is Android 10
            permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] ?: false
        } else {
            true // On Android 10+, WRITE_EXTERNAL_STORAGE is deprecated for app-specific directories. FileProvider handles it.
        }

        if (cameraGranted && writeStorageGranted) {
            dispatchTakePictureIntent()
        } else {
            Toast.makeText(this, getString(R.string.permissions_required), Toast.LENGTH_LONG).show()
        }
    }

    private val galleryPermissionRequestLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val readMediaImagesGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.READ_MEDIA_IMAGES] ?: false
        } else {
            permissions[Manifest.permission.READ_EXTERNAL_STORAGE] ?: false
        }

        if (readMediaImagesGranted) {
            galleryLauncher.launch("image/*")
        } else {
            Toast.makeText(this, getString(R.string.permissions_required), Toast.LENGTH_LONG).show()
        }
    }

    private val cameraLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val photoFile = File(currentPhotoPath)
            if (photoFile.exists()) {
                val photoUri = FileProvider.getUriForFile(this, "com.example.notesafe.fileprovider", photoFile)
                // Grant URI permissions for the cropping activity
                val intent = Intent().setData(photoUri)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                grantUriPermission("com.canhub.cropper", photoUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                startCrop(photoUri)
            } else {
                Toast.makeText(this, getString(R.string.error_photo_metadata), Toast.LENGTH_SHORT).show()
                Log.e("CameraDebug", "File not found at: $currentPhotoPath")
                resultTextView.text = getString(R.string.error_photo_metadata) // Display error in TextView
            }
        } else {
            Toast.makeText(this, getString(R.string.error_photo_capture), Toast.LENGTH_SHORT).show()
            resultTextView.text = getString(R.string.error_photo_capture) // Display error in TextView
        }
    }

    private val galleryLauncher: ActivityResultLauncher<String> = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { startCrop(it) } ?: run {
            Toast.makeText(this, getString(R.string.error_no_image_selected), Toast.LENGTH_SHORT).show()
            resultTextView.text = getString(R.string.error_no_image_selected) // Display error in TextView
        }
    }

    private val cropImageLauncher: ActivityResultLauncher<CropImageContractOptions> = registerForActivityResult(CropImageContract()) { result ->
        if (result.isSuccessful) {
            result.uriContent?.let { handleImage(it) } ?: run {
                Toast.makeText(this, getString(R.string.error_cropped_image_null), Toast.LENGTH_SHORT).show()
                resultTextView.text = getString(R.string.error_cropped_image_null) // Display error in TextView
            }
        } else {
            val exception = result.error
            Toast.makeText(this, getString(R.string.error_crop, exception?.message), Toast.LENGTH_SHORT).show()
            Log.e("CropError", "Error during cropping: ${exception?.message}", exception)
            resultTextView.text = getString(R.string.error_crop, exception?.message) // Display error in TextView
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        loadModel()

        imgPreview = findViewById(R.id.imgPreview)
        resultTextView = findViewById(R.id.resultTextView)
        btnCapturePhoto = findViewById(R.id.btnCapturePhoto)
        btnUploadPhoto = findViewById(R.id.btnUploadPhoto)
        btnEnglish = findViewById(R.id.btnEnglish)
        btnHindi = findViewById(R.id.btnHindi)
        btnIdentifyYourself = findViewById(R.id.btnIdentifyYourself)
        btnLogout = findViewById(R.id.btnLogout)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        Log.d("LanguageButtons", "btnEnglish: $btnEnglish, btnHindi: $btnHindi")
        Log.d("IdentifyButton", "btnIdentifyYourself: $btnIdentifyYourself")

        // Request location on startup if permissions are not granted, otherwise get last location
        if (checkLocationPermission()) {
            getLastLocation()
        } else {
            locationPermissionRequestLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }

        btnLogout.setOnClickListener {
            auth.signOut()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            Toast.makeText(this, getString(R.string.logged_out), Toast.LENGTH_SHORT).show()
        }

        btnCapturePhoto.setOnClickListener {
            val permissionsToRequest = mutableListOf(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { // For older Android versions, we might need WRITE_EXTERNAL_STORAGE for saving photos directly to public storage, though FileProvider handles app-specific storage.
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }

            if (checkCameraPermissions()) {
                dispatchTakePictureIntent()
            } else {
                cameraPermissionRequestLauncher.launch(permissionsToRequest.toTypedArray())
            }
        }

        btnUploadPhoto.setOnClickListener {
            val permissionsToRequest = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }

            if (checkStoragePermissionsForGallery()) {
                galleryLauncher.launch("image/*")
            } else {
                galleryPermissionRequestLauncher.launch(permissionsToRequest.toTypedArray())
            }
        }

        btnEnglish.setOnClickListener {
            Log.d("LanguageButtons", "English button clicked")
            setLocale("en")
        }

        btnHindi.setOnClickListener {
            Log.d("LanguageButtons", "Hindi button clicked")
            setLocale("hi")
        }

        btnIdentifyYourself.setOnClickListener {
            Log.d("IdentifyButton", "Identify Yourself button clicked")
            // Assuming this button navigates to an external link or activity.
            // If it's intended to identify the user for authentication, it should be adjusted.
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://paisaboltahai.rbi.org.in"))
            startActivity(intent)
        }
    }

    private fun checkLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkCameraPermissions(): Boolean {
        // For API 23 and above, CAMERA permission is typically sufficient for app-specific storage when using FileProvider.
        // WRITE_EXTERNAL_STORAGE is less relevant for app-specific directories on newer APIs.
        val cameraPermissionGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val writeStorageGranted = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { // Android 10 (API 29)
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        } else {
            true // WRITE_EXTERNAL_STORAGE is deprecated for app-specific directories on Android 10+
        }
        return cameraPermissionGranted && writeStorageGranted
    }


    private fun checkStoragePermissionsForGallery(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    @Suppress("MissingPermission") // Suppressed because permission is checked before calling this
    private fun getLastLocation() {
        if (checkLocationPermission()) {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location: Location? ->
                    lastKnownLocation = location
                    if (location != null) {
                        Log.d("Location", "Last known location: Lat=${location.latitude}, Lng=${location.longitude}")
                    } else {
                        Log.d("Location", "Last known location is null.")
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("Location", "Failed to get last known location", e)
                    Toast.makeText(this, "Failed to get location", Toast.LENGTH_SHORT).show()
                }
        } else {
            Toast.makeText(this, "Location permissions not granted.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setLocale(languageCode: String) {
        Log.d("LanguageButtons", "Setting locale to: $languageCode")
        try {
            val locale = Locale(languageCode)
            Locale.setDefault(locale)
            val config = resources.configuration
            config.setLocale(locale)
            resources.updateConfiguration(config, resources.displayMetrics)
            Log.d("LanguageButtons", "Locale set, recreating activity")
            recreate()
        } catch (e: Exception) {
            Log.e("LanguageButtons", "Error setting locale: ${e.message}", e)
            Toast.makeText(this, "Error changing language", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createImageFile(): File {
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        // Ensure the directory exists
        storageDir?.mkdirs()
        return File.createTempFile("photo_${System.currentTimeMillis()}", ".jpg", storageDir).also {
            currentPhotoPath = it.absolutePath
            Log.d("CameraDebug", "Created photo file at: ${it.absolutePath}")
        }
    }

    private fun dispatchTakePictureIntent() {
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).also { takePictureIntent ->
            takePictureIntent.resolveActivity(packageManager)?.also {
                val photoFile: File? = try {
                    createImageFile()
                } catch (ex: Exception) {
                    Toast.makeText(this, getString(R.string.error_creating_image_file, ex.message), Toast.LENGTH_SHORT).show()
                    Log.e("CameraDebug", "Error creating image file: ${ex.message}", ex)
                    null
                }
                photoFile?.also {
                    val photoUri: Uri = FileProvider.getUriForFile(
                        this,
                        "com.example.notesafe.fileprovider",
                        it
                    )
                    takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                    takePictureIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    cameraLauncher.launch(takePictureIntent)
                }
            } ?: run {
                Toast.makeText(this, getString(R.string.error_no_camera_app), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadModel() {
        try {
            val assetFileDescriptor = assets.openFd("model-2.tflite")
            val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val fileChannel = fileInputStream.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.declaredLength
            val mappedByteBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
            tflite = Interpreter(mappedByteBuffer)
            Log.d("ModelLoading", "model-2.tflite loaded successfully.")
        } catch (e: Exception) {
            Log.e("ModelLoading", "Error loading model-2.tflite: ${e.message}", e)
            Toast.makeText(this, getString(R.string.error_loading_model), Toast.LENGTH_LONG).show()
            btnCapturePhoto.isEnabled = false
            btnUploadPhoto.isEnabled = false
            resultTextView.text = getString(R.string.error_loading_model) // Display error in TextView
        }
    }

    private fun startCrop(imageUri: Uri) {
        val cropImageOptions = CropImageOptions().apply {
            guidelines = CropImageView.Guidelines.ON
            autoZoomEnabled = true
            multiTouchEnabled = true
            allowRotation = true
            allowCounterRotation = true
            rotationDegrees = 2
            cropShape = CropImageView.CropShape.RECTANGLE
            outputCompressFormat = Bitmap.CompressFormat.JPEG
            outputCompressQuality = 90
            initialCropWindowPaddingRatio = 0f
            fixAspectRatio = false // Allow free aspect ratio unless specified
        }

        val cropImageContractOptions = CropImageContractOptions(imageUri, cropImageOptions)
        cropImageLauncher.launch(cropImageContractOptions)
    }

    private fun handleImage(imageUri: Uri) {
        try {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(contentResolver, imageUri)
                ImageDecoder.decodeBitmap(source)
            } else {
                MediaStore.Images.Media.getBitmap(contentResolver, imageUri)
            }

            imgPreview.setImageBitmap(bitmap)
            processImage(bitmap)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_loading_image), Toast.LENGTH_SHORT).show()
            Log.e("ImageError", "Error loading image from URI: $imageUri", e)
            resultTextView.text = getString(R.string.error_loading_image)
        }
    }

    private fun preprocessImage(bitmap: Bitmap): ByteBuffer {
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val resizedBitmap = Bitmap.createScaledBitmap(mutableBitmap, 300, 100, true)

        Log.d("BitmapDimensions", "Width: ${resizedBitmap.width}, Height: ${resizedBitmap.height}")

        val buffer = ByteBuffer.allocateDirect(300 * 100 * 3 * 4).order(ByteOrder.nativeOrder())

        for (y in 0 until 100) {
            for (x in 0 until 300) {
                val pixel = resizedBitmap.getPixel(x, y)
                val r = Color.red(pixel) / 255.0f
                val g = Color.green(pixel) / 255.0f
                val b = Color.blue(pixel) / 255.0f
                buffer.putFloat(r)
                buffer.putFloat(g)
                buffer.putFloat(b)
            }
        }
        buffer.rewind() // Important: Rewind the buffer to read from the beginning
        return buffer
    }

    private fun processImage(bitmap: Bitmap) {
        try {
            val inputBuffer = preprocessImage(bitmap)
            // Assuming your model has a single output tensor and its shape is known
            // Example: [1, number_of_classes]
            val outputShape = tflite.getOutputTensor(0).shape()
            val outputBuffer = Array(outputShape[0]) { FloatArray(outputShape[1]) }

            tflite.run(inputBuffer, outputBuffer)

            Log.d("OutputShape", outputShape.contentToString())
            Log.d("Probabilities", outputBuffer[0].contentToString())

            val probabilities = outputBuffer[0]
            val maxProbability = probabilities.maxOrNull()
            if (maxProbability != null) {
                val predictedClassIndex = probabilities.indexOfFirst { it == maxProbability }
                val labels = listOf("fake200", "real200", "fake500", "real500")

                if (predictedClassIndex in labels.indices) {
                    val predictedLabel = labels[predictedClassIndex]
                    val confidenceScore = maxProbability * 100

                    val resultMessage = getString(R.string.prediction_format, predictedLabel, confidenceScore)
                    Toast.makeText(this, resultMessage, Toast.LENGTH_LONG).show()

                    resultTextView.text = resultMessage
                    if (confidenceScore < 70) {
                        resultTextView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                    } else {
                        resultTextView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
                    }
                    // Extract relevant data for Firestore record
                    val isFake = predictedLabel.startsWith("fake")
                    if (isFake) {
                        // Extract relevant data for Firestore record
                        val denomination = when {
                            predictedLabel.contains("200") -> "200"
                            predictedLabel.contains("500") -> "500"
                            else -> "Unknown" // Fallback if denomination isn't clear
                        }
                        val userId = auth.currentUser?.uid ?: "anonymous" // Get current user's ID

                        // Create the FakeCurrencyRecord
                        val record = FirestoreManager.FakeCurrencyRecord(
                            userId = userId,
                            denomination = denomination,
                            isFake = isFake,
                            confidenceScore = confidenceScore,
                            location = lastKnownLocation // Pass the last known location
                        )

                        // Save the record to Firestore
                        FirestoreManager.saveDetectionResult(record) { success ->
                            if (success) {
                                Toast.makeText(this, "Fake currency detection data saved!", Toast.LENGTH_SHORT).show()
                                Log.d("FirestoreSave", "Successfully saved fake currency detection data to Firestore.")
                            } else {
                                Toast.makeText(this, "Failed to save fake currency detection data.", Toast.LENGTH_SHORT).show()
                                Log.e("FirestoreSave", "Failed to save fake currency detection data to Firestore.")
                            }
                        }
                    } else {
                        Log.d("FirestoreSave", "Currency detected as real, not saving to Firestore.")
                        Toast.makeText(this, "Currency detected as real.", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, getString(R.string.error_processing_image_model), Toast.LENGTH_SHORT).show()
            resultTextView.text = getString(R.string.error_processing_image_model)
            Log.e("TensorFlowError", "Error processing image with model", e)
        }
    }
}