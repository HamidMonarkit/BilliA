package com.example.billai

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import android.widget.Spinner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.github.chrisbanes.photoview.PhotoView
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var imageView: PhotoView
    private lateinit var etInvoiceNumber: EditText
    private lateinit var etInvoiceDate: EditText
    private lateinit var etSeller: EditText
    private lateinit var etClient: EditText
    private lateinit var etTotalNetAmount: EditText
    private lateinit var etTotalGrossAmount: EditText
    private lateinit var spInvoiceType: Spinner
    private var selectedBitmap: Bitmap? = null
    private var currentImageUri: Uri? = null

    // Lancement de la galerie de photos
    private val galleryLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                selectedBitmap = uriToBitmap(it)
                imageView.setImageBitmap(selectedBitmap)
                sendToGemini()
            } ?: run {
                Toast.makeText(this, "Sélection d'image annulée.", Toast.LENGTH_SHORT).show()
            }
        }

    // Lancement de la capture de photo par la caméra
    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success) {
                currentImageUri?.let { uri ->
                    selectedBitmap = uriToBitmap(uri)
                    imageView.setImageBitmap(selectedBitmap)
                    sendToGemini()
                    selectedBitmap?.let { bitmap ->
                        saveImageToGallery(bitmap, "Facture_${System.currentTimeMillis()}")
                    }
                } ?: run {
                    Toast.makeText(this, "Erreur: URI de l'image nulle après capture.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Capture photo annulée ou échouée.", Toast.LENGTH_SHORT).show()
                currentImageUri = null
            }
        }

    // Demande des permissions nécessaires
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allPermissionsGranted = permissions.entries.all { it.value }
        if (allPermissionsGranted) {
            Toast.makeText(this, "Toutes les permissions nécessaires sont accordées.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Certaines permissions essentielles ont été refusées.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        requestRequiredPermissions()

        imageView = findViewById(R.id.imageView)
        etInvoiceNumber = findViewById(R.id.etInvoiceNumber)
        etInvoiceDate = findViewById(R.id.etInvoiceDate)
        spInvoiceType = findViewById(R.id.spInvoiceType)
        etSeller = findViewById(R.id.etSeller)
        etClient = findViewById(R.id.etClient)
        etTotalNetAmount = findViewById(R.id.etTotalNetAmount)
        etTotalGrossAmount = findViewById(R.id.etTotalGrossAmount)

        findViewById<Button>(R.id.btnCamera).setOnClickListener {
            if (hasCameraAndStoragePermissions()) {
                dispatchTakePictureIntent()
            } else {
                requestRequiredPermissions()
            }
        }
        findViewById<Button>(R.id.btnGallery).setOnClickListener {
            if (hasGalleryPermissions()) {
                galleryLauncher.launch("image/*")
            } else {
                requestRequiredPermissions()
            }
        }
    }

    // Vérification des permissions
    private fun hasGalleryPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasCameraAndStoragePermissions(): Boolean {
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val storageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            true
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        return cameraGranted && storageGranted
    }

    private fun requestRequiredPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    // Convertir URI en Bitmap
    private fun uriToBitmap(uri: Uri): Bitmap {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(contentResolver, uri)
            ImageDecoder.decodeBitmap(source)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(contentResolver, uri)
        }
    }

    // Capture de photo
    private fun dispatchTakePictureIntent() {
        val photoFile: File? = try {
            createImageFile()
        } catch (ex: Exception) {
            Log.e("MainActivity", "Erreur lors de la création du fichier image: ${ex.message}", ex)
            Toast.makeText(this, "Erreur lors de la préparation de l'appareil photo.", Toast.LENGTH_SHORT).show()
            null
        }
        photoFile?.also { file ->
            val photoUri: Uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.provider",
                file
            )
            currentImageUri = photoUri
            takePictureLauncher.launch(photoUri)
        }
    }

    private fun createImageFile(): File {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir: File? = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
        if (storageDir != null && !storageDir.exists()) {
            storageDir.mkdirs()
        }
        return File.createTempFile(
            "JPEG_${timeStamp}_",
            ".jpg",
            storageDir
        )
    }

    // Enregistrement de l'image dans la galerie
    private fun saveImageToGallery(bitmap: Bitmap, title: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val imageCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$title.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            var imageUri: Uri? = null
            try {
                contentResolver.insert(imageCollection, contentValues)?.also { uri ->
                    imageUri = uri
                    contentResolver.openOutputStream(uri)?.use { outputStream ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Image enregistrée dans la galerie !", Toast.LENGTH_SHORT).show()
                        }
                    } ?: throw Exception("Impossible d'ouvrir le flux de sortie pour l'URI.")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Échec de l'enregistrement dans la galerie: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            } finally {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && imageUri != null) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(imageUri!!, contentValues, null, null)
                }
            }
        }
    }

    // Envoyer l'image à Gemini pour l'extraction
    private fun sendToGemini() {
        val bitmap = selectedBitmap ?: run {
            Toast.makeText(this, "Aucune image sélectionnée ou capturée.", Toast.LENGTH_SHORT).show()
            return
        }

        runOnUiThread {
            etInvoiceNumber.setText("")
            etInvoiceDate.setText("")
            etSeller.setText("")
            etClient.setText("")
            etTotalNetAmount.setText("")
            etTotalGrossAmount.setText("")
            Toast.makeText(this, "Traitement de l'image avec Gemini...", Toast.LENGTH_SHORT).show()
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val gemini = GenerativeModel(
                    modelName = "gemini-1.5-flash-latest",
                    apiKey = "AIzaSyAsHYjdOlWfUmdNr85yMpB-eu0EUqC9Ivk"
                )

                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                val imageBytes = outputStream.toByteArray()

                val prompt = """
                   Extract the following fields from this invoice:
                   - Invoice Number
                   - Invoice Date (format YYYY-MM-DD if possible)
                   - Seller
                   - Client
                   - Total Net Amount
                   - Total Gross Amount

                   Provide the output as a JSON object, **and ONLY the JSON object, nothing else**.
                   For example:
                   ```json
                   {
                     "Invoice Number": "INV-12345",
                     "Invoice Date": "2023-10-26",
                     "Seller": "ABC Corp",
                     "Client": "XYZ Ltd",
                     "Total Net Amount": "123.45",
                     "Total Gross Amount": "145.67"
                   }
                   ```
                   If a field is not found, use an empty string for its value.
               """.trimIndent()

                val response = gemini.generateContent(
                    content {
                        image(BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size))
                        text(prompt)
                    }
                )

                val geminiResponseText = response.text ?: ""
                val jsonString = extractJsonFromString(geminiResponseText)

                runOnUiThread {
                    if (jsonString.isNotEmpty()) {
                        try {
                            val jsonObject = JSONObject(jsonString)
                            etInvoiceNumber.setText(jsonObject.optString("Invoice Number", ""))
                            etInvoiceDate.setText(jsonObject.optString("Invoice Date", ""))
                            etSeller.setText(jsonObject.optString("Seller", ""))
                            etClient.setText(jsonObject.optString("Client", ""))
                            val netAmount = jsonObject.optString("Total Net Amount", "").replace(" ", "")
                            val grossAmount = jsonObject.optString("Total Gross Amount", "").replace(" ", "")
                            etTotalNetAmount.setText(netAmount)
                            etTotalGrossAmount.setText(grossAmount)
                            Toast.makeText(this@MainActivity, "Données extraites et affichées !", Toast.LENGTH_SHORT).show()
                        } catch (e: JSONException) {
                            Log.e("MainActivity", "Erreur lors du parsing JSON: ${e.message}. Réponse brute: $geminiResponseText", e)
                            Toast.makeText(this@MainActivity, "Erreur: Impossible de parser la réponse de Gemini. Vérifiez le format.", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        Toast.makeText(this@MainActivity, "Gemini n'a pas renvoyé de JSON valide ou identifiable dans la réponse.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Erreur lors de l'appel à Gemini: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Erreur lors du traitement: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Vos fonctions d'aide existantes (inchangées)
    private fun extractJsonFromString(text: String): String {
        val startIndex = text.indexOf("```json")
        val endIndex = text.lastIndexOf("```")
        return if (startIndex != -1 && endIndex != -1 && startIndex < endIndex) {
            text.substring(startIndex + "```json".length, endIndex).trim()
        } else {
            val firstBrace = text.indexOf('{')
            val lastBrace = text.lastIndexOf('}')
            if (firstBrace != -1 && lastBrace != -1 && firstBrace < lastBrace) {
                text.substring(firstBrace, lastBrace + 1).trim()
            } else {
                ""
            }
        }
    }
}
