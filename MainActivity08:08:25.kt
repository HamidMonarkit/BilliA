package com.example.billai

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
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
import com.github.chrisbanes.photoview.PhotoView
import okhttp3.OkHttpClient
import okhttp3.FormBody
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import android.os.Environment
import org.apache.poi.common.usermodel.HyperlinkType
import org.apache.poi.ss.util.CellReference
import java.io.FileOutputStream

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.services.drive.DriveScopes
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.android.gms.tasks.Tasks


// Nom du fichier JSON de votre clé privée, placé dans le dossier res/raw
//private const val SERVICE_ACCOUNT_KEY_FILE = "service_account_key.json"

import android.content.Intent

class MainActivity : AppCompatActivity() {
    private lateinit var imageView: PhotoView
    private lateinit var etInvoiceNumber: EditText
    private lateinit var etInvoiceDate: EditText
    private lateinit var etSeller: EditText
    private lateinit var etClient: EditText
    private lateinit var etTotalNetAmount: EditText
    private lateinit var etTotalGrossAmount: EditText
    private lateinit var spInvoiceType: Spinner
    private lateinit var btnSendToZohoFlow: Button
    private lateinit var btnExcel: Button
    private var selectedBitmap: Bitmap? = null
    private var currentImageUri: Uri? = null



    private var mDriveServiceHelper: DriveServiceHelper? = null


    //----------------------------
    // URL Webhook de Zoho Flow
    //----------------------------
    private val ZOHO_FLOW_WEBHOOK_URL = "https://flow.zoho.com/856898089/flow/webhook/incoming?zapikey=1001.c6cef4a0d11f4513b6ecb156e5d7836b.82e2ae3f78e9adf2de3cdf0b16a04890&isdebug=false"

    //-------------------------------------------------------------------
    // Initialisation de OkHttpClient avec la configuration des timeouts
    //-------------------------------------------------------------------
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS) // Timeout de connexion
        .readTimeout(60, TimeUnit.SECONDS)    // Timeout de lecture
        .writeTimeout(60, TimeUnit.SECONDS)   // Timeout d'écriture
        .build()

    //-----------------------------------------------
    // Fonctions de Lancement de la galerie de photos
    //-----------------------------------------------
    private val galleryLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                selectedBitmap = uriToBitmap(it)
                imageView.setImageBitmap(selectedBitmap)
                //----------------------------------------
                // Envouer l'image de la galerie à Gemini
                //----------------------------------------
                sendToGemini()
                //----------------------------------------
            } ?: run {
                Toast.makeText(this, "Sélection d'image annulée.", Toast.LENGTH_SHORT).show()
            }
        }

    //-------------------------------------------------------------------------
    // Fonctions de Lancement de la capture de photo par la caméra du télephone
    //-------------------------------------------------------------------------
    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success) {
                currentImageUri?.let { uri ->
                    selectedBitmap = uriToBitmap(uri)
                    imageView.setImageBitmap(selectedBitmap)
                    //-----------------------------------
                    // Envouer l'image capturée à Gemini
                    //-----------------------------------
                    sendToGemini()
                    //-----------------------------------

                    //-----------------------------------
                    // Enregistrer l'image dans la galerie
                    //-----------------------------------
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

    //----------------------------------------
    // Demande des permissions nécessaires
    //----------------------------------------
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allPermissionsGranted = permissions.entries.all { it.value }

        if (allPermissionsGranted) {
            Toast.makeText(this, "Toutes les permissions nécessaires sont accordées.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Certaines permissions essentielles ont été refusées. L'application pourrait ne pas fonctionner correctement.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        requestRequiredPermissions()
        imageView = findViewById(R.id.imageView)
        //------------------------------------------------
        // Inittialiser les champs EditText et du Spinner
        //------------------------------------------------
        etInvoiceNumber = findViewById(R.id.etInvoiceNumber)
        etInvoiceDate = findViewById(R.id.etInvoiceDate)
        spInvoiceType = findViewById(R.id.spInvoiceType)
        etSeller = findViewById(R.id.etSeller)
        etClient = findViewById(R.id.etClient)
        etTotalNetAmount = findViewById(R.id.etTotalNetAmount)
        etTotalGrossAmount = findViewById(R.id.etTotalGrossAmount)
        btnSendToZohoFlow = findViewById(R.id.btnSendToZohoFlow)
        btnExcel = findViewById(R.id.btnExcel)
        //---------------
        // Bouton caméra
        //---------------
        findViewById<Button>(R.id.btnCamera).setOnClickListener {
            if (hasCameraAndStoragePermissions()) {
                dispatchTakePictureIntent()
            } else {
                requestRequiredPermissions()
            }
        }
        //---------------
        // Bouton galerie
        //---------------
        findViewById<Button>(R.id.btnGallery).setOnClickListener {
            if (hasGalleryPermissions()) {
                galleryLauncher.launch("image/*")
            } else {
                requestRequiredPermissions()
            }
        }
        //-------------------
        // Bouton Zoho Books
        //-------------------
        btnSendToZohoFlow.setOnClickListener {
            sendDataToZohoFlow()
        }
        //--------------
        // Bouton Excel
        //---------------
        btnExcel.setOnClickListener {
            saveDataToExcelXlsx() // Appel de la fonction saveDataToExcelXlsx
        }

        // Initialisation du service Drive avec le compte de service
        //initializeDriveServiceWithServiceAccount()

    }



    private fun initializeDriveServiceWithServiceAccount() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Lire le fichier de clé privée du compte de service depuis res/raw
                resources.openRawResource(R.raw.service_account_key).use { inputStream ->
                    val credential = GoogleCredential.fromStream(inputStream)
                        .createScoped(listOf(DriveScopes.DRIVE_FILE))

                    val transport = NetHttpTransport()
                    val jsonFactory = GsonFactory.getDefaultInstance()

                    val driveService = Drive.Builder(transport, jsonFactory, credential)
                        .setApplicationName(getString(R.string.app_name))
                        .build()

                    mDriveServiceHelper = DriveServiceHelper(driveService)
                    Log.d("DriveService", "Service Drive initialisé avec succès via le compte de service.")

                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Connecté à Google Drive via le compte de service.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("DriveService", "Erreur lors de l'initialisation du service Drive: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Erreur de connexion à Google Drive.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }


    //------------------------------------------------------
    // Vérification des permissions de lecture de la galerie
    //------------------------------------------------------
    private fun hasGalleryPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }
    //---------------------------------------------------------
    // Vérification des permissions de la caméra et du stockage
    //---------------------------------------------------------
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

        // Permission for camera
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }

        // Permissions for gallery (media reading)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13 (API 33) and above
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            // Add READ_MEDIA_VIDEO/AUDIO if needed
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            // WRITE_EXTERNAL_STORAGE is only needed for media writing before Q (API 29)
            // For saving Excel files in public Downloads directory, WRITE_EXTERNAL_STORAGE is still needed below Android Q
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    //----------------------------
    // Convertir URI vers Bitmap
    //----------------------------
    private fun uriToBitmap(uri: Uri): Bitmap {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(contentResolver, uri)
            ImageDecoder.decodeBitmap(source)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(contentResolver, uri)
        }
    }

    // Fonctions pour la qualité d'image
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

    //--------------------------------------------
    // Enregistrement de l'image dans la galerie
    //--------------------------------------------
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
                        Log.d("MainActivity", "Image enregistrée dans la galerie : $uri")
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Image enregistrée dans la galerie !", Toast.LENGTH_SHORT).show()
                        }
                    } ?: throw Exception("Impossible d'ouvrir le flux de sortie pour l'URI.")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Erreur lors de l'enregistrement de l'image dans la galerie: ${e.message}", e)
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

    //--------------------------
    // Envoyer l'image à Gemini
    //-------------------------
    private fun sendToGemini() {
        val bitmap = selectedBitmap ?: run {
            Toast.makeText(this, "Aucune image sélectionnée ou capturée.", Toast.LENGTH_SHORT).show()
            return
        }

        runOnUiThread {
            // Effacer tous les champs avant une nouvelle analyse
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
                    apiKey = "AIzaSyAsHYjdOlWfUmdNr85yMpB-eu0EUqC9Ivk" // Clé API Gemini
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
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Erreur inattendue: ${e.message}", e)
                            Toast.makeText(this@MainActivity, "Erreur inattendue lors de l'affichage des données.", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        Toast.makeText(this@MainActivity, "Gemini n'a pas renvoyé de JSON valide ou identifiable dans la réponse.", Toast.LENGTH_LONG).show()
                        Log.e("MainActivity", "Réponse de Gemini sans JSON identifiable: $geminiResponseText")
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

    //-----------------------------------------------
    // Fonction pour envoyer les données à Zoho Flow
    //-----------------------------------------------
    private fun sendDataToZohoFlow() {
        val customerName = etClient.text.toString()
        val invoiceType = spInvoiceType.selectedItem.toString()
        val itemQuantity = "1"
        var itemRate = etTotalGrossAmount.text.toString()
        val invoiceDate = etInvoiceDate.text.toString()

        val itemName = if (invoiceType == "Facture client") {
            "Produit vendu"
        } else if (invoiceType == "Facture fournisseur") {
            "produit acheté"
        } else {
            "Autre"
        }

        itemRate = itemRate.replace(',', '.')

        if (customerName.isEmpty() || itemRate.isEmpty() || invoiceDate.isEmpty()) {
            Toast.makeText(this, "Nom du client, Montant brut total et Date de facture sont requis pour envoyer à Zoho Books via Flow.", Toast.LENGTH_LONG).show()
            return
        }

        runOnUiThread {
            Toast.makeText(this, "Envoi des données à Zoho Flow...", Toast.LENGTH_SHORT).show()
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val formBody = FormBody.Builder()
                    .add("customer_name", customerName)
                    .add("invoice_type", invoiceType)
                    .add("item_name", itemName)
                    .add("quantity", itemQuantity)
                    .add("price", itemRate)
                    .add("invoice_date", invoiceDate)
                    .build()

                val request = Request.Builder()
                    .url(ZOHO_FLOW_WEBHOOK_URL)
                    .post(formBody)
                    .build()

                httpClient.newCall(request).enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: IOException) {
                        Log.e("MainActivity", "Échec de l'envoi à Zoho Flow: ${e.message}", e)
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Échec de l'envoi à Zoho Flow: ${e.localizedMessage}. Vérifiez votre connexion.", Toast.LENGTH_LONG).show()
                        }
                    }

                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        response.use {
                            val responseBody = it.body?.string()
                            if (it.isSuccessful) {
                                Log.d("MainActivity", "Réponse Zoho Flow (${it.code}): $responseBody")
                                runOnUiThread {
                                    Toast.makeText(this@MainActivity, "Données envoyées à Zoho Flow avec succès! Vérifiez Zoho Books.", Toast.LENGTH_LONG).show()
                                }
                            } else {
                                Log.e("MainActivity", "Erreur Zoho Flow (${it.code}): $responseBody")
                                runOnUiThread {
                                    Toast.makeText(this@MainActivity, "Erreur de Zoho Flow: ${it.code} - ${responseBody?.take(100)}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                })

            } catch (e: Exception) {
                Log.e("MainActivity", "Exception lors de l'envoi à Zoho Flow: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Erreur inattendue lors de l'envoi: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    //----------------------------------------------------------------
    // Enregistrement des données et de l'image dans un fichier XLSX
    //----------------------------------------------------------------
    private fun saveDataToExcelXlsx() {
        val invoiceNumber = etInvoiceNumber.text.toString()
        val invoiceDateStr = etInvoiceDate.text.toString() // Garder en string pour le parsing flexible
        val invoiceType = spInvoiceType.selectedItem.toString()
        val seller = etSeller.text.toString()
        val client = etClient.text.toString()
        val totalNetAmount = etTotalNetAmount.text.toString()
        val totalGrossAmount = etTotalGrossAmount.text.toString()

        // S'assurer que le séparateur décimal est un point pour la cohérence
        val formattedTotalNetAmount = totalNetAmount.replace(',', '.')
        val formattedTotalGrossAmount = totalGrossAmount.replace(',', '.')

        if (invoiceNumber.isEmpty() && invoiceDateStr.isEmpty() && seller.isEmpty() && client.isEmpty() &&
            formattedTotalNetAmount.isEmpty() && formattedTotalGrossAmount.isEmpty() && selectedBitmap == null) {
            runOnUiThread {
                Toast.makeText(this, "Aucune donnée ou image à enregistrer.", Toast.LENGTH_SHORT).show()
            }
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // --- 1. Définir les formats de date pour les noms de fichiers et de feuilles ---
                val currentMonthYearFormat = SimpleDateFormat("MM_yyyy", Locale.getDefault())
                val currentDayMonthYearFormat = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
                val currentDate = Date() // Date actuelle pour le nom du fichier et de la feuille quotidienne

                val excelFileName = "Factures_${invoiceNumber}.xlsx"
                val dailySheetName = "Facture ${invoiceNumber}"
                val summarySheetName = "Résumé de facture ${invoiceNumber}"

                // --- 2. Définir l'URI où le fichier Excel sera sauvegardé/lu et initialiser le Workbook ---
                val targetDirectory = Environment.DIRECTORY_DOWNLOADS + "/Factures"
                val relativePathForMediaStore = targetDirectory + "/" // Chemin relatif pour MediaStore, doit se terminer par /

                Log.d("ExcelSaveDebug", "--- Début de l'opération d'enregistrement Excel ---")
                Log.d("ExcelSaveDebug", "Nom du fichier attendu: $excelFileName")
                Log.d("ExcelSaveDebug", "Chemin relatif attendu: $relativePathForMediaStore")

                val existingFileUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Rechercher le fichier existant via MediaStore
                    val resolver = contentResolver
                    val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    val projection = arrayOf(MediaStore.MediaColumns._ID)
                    val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
                    val selectionArgs = arrayOf(excelFileName, relativePathForMediaStore) // Utilisez relativePathForMediaStore

                    resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                            val id = cursor.getLong(idColumn)
                            val foundUri = Uri.withAppendedPath(collection, id.toString())
                            Log.d("ExcelSaveDebug", "Fichier existant TROUVÉ via MediaStore: $foundUri")
                            foundUri
                        } else {
                            Log.d("ExcelSaveDebug", "Fichier existant NON TROUVÉ via MediaStore pour les critères donnés.")
                            null
                        }
                    }
                } else {
                    // Pour les versions antérieures, vérifier l'existence directe du fichier
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Factures")
                    if (!dir.exists()) dir.mkdirs()
                    val file = File(dir, excelFileName)
                    if (file.exists()) {
                        val foundUri = Uri.fromFile(file)
                        Log.d("ExcelSaveDebug", "Fichier existant TROUVÉ (API < Q) : $foundUri")
                        foundUri
                    } else {
                        Log.d("ExcelSaveDebug", "Fichier existant NON TROUVÉ (API < Q).")
                        null
                    }
                }

                // Initialiser  'workbook' et 'excelFileUri'
                val excelInitResult: Pair<XSSFWorkbook, Uri> = if (existingFileUri != null) {
                    try {
                        contentResolver.openInputStream(existingFileUri)?.use { inputStream ->
                            // Cas 1: Le fichier existant est trouvé et peut être ouvert
                            Log.d("ExcelSaveDebug", "Fichier existant OUVERT avec succès.")
                            Pair(XSSFWorkbook(inputStream), existingFileUri)
                        } ?: run {
                            // Cas 2: Le fichier existant est trouvé mais le flux est null (ex: fichier vide/corrompu)
                            Log.w("ExcelSaveDebug", "Impossible d'ouvrir le fichier existant pour lecture (InputStream null). Création d'un nouveau.")
                            val newFileUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                val contentValues = ContentValues().apply {
                                    put(MediaStore.MediaColumns.DISPLAY_NAME, excelFileName)
                                    put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                                    put(MediaStore.MediaColumns.RELATIVE_PATH, targetDirectory)
                                    put(MediaStore.MediaColumns.IS_PENDING, 1) // Marquer comme pending
                                }
                                contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                                    ?: throw IOException("Impossible de créer le nouveau fichier Excel via MediaStore.")
                            } else {
                                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Factures")
                                if (!dir.exists()) dir.mkdirs()
                                Uri.fromFile(File(dir, excelFileName))
                            }
                            Pair(XSSFWorkbook(), newFileUri) // Retourne un nouveau workbook et la nouvelle URI
                        }
                    } catch (e: Exception) {
                        // Cas 3: Erreur lors de la tentative d'ouverture du fichier existant (ex: permissions, fichier non valide)
                        Log.e("ExcelSaveDebug", "Erreur lors de l'ouverture du fichier existant (${existingFileUri}): ${e.message}. Création d'un nouveau.", e)
                        val newFileUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val contentValues = ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, excelFileName)
                                put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                                put(MediaStore.MediaColumns.RELATIVE_PATH, targetDirectory)
                                put(MediaStore.MediaColumns.IS_PENDING, 1) // Marquer comme pending
                            }
                            contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                                ?: throw IOException("Impossible de créer le nouveau fichier Excel via MediaStore après erreur.")
                        } else {
                            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Factures")
                            if (!dir.exists()) dir.mkdirs()
                            Uri.fromFile(File(dir, excelFileName))
                        }
                        Pair(XSSFWorkbook(), newFileUri) // Retourne un nouveau workbook et la nouvelle URI
                    }
                } else {
                    // Cas 4: Aucun fichier existant trouvé au départ, créer un nouveau fichier
                    Log.d("ExcelSaveDebug", "existingFileUri est null au départ. Création d'un nouveau fichier.")
                    val newFileUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, excelFileName)
                            put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, targetDirectory)
                            put(MediaStore.MediaColumns.IS_PENDING, 1) // Marquer comme pending
                        }
                        contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                            ?: throw IOException("Impossible de créer le fichier Excel via MediaStore.")
                    } else {
                        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Factures")
                        if (!dir.exists()) dir.mkdirs()
                        Uri.fromFile(File(dir, excelFileName))
                    }
                    Pair(XSSFWorkbook(), newFileUri) // Retourne un nouveau workbook et la nouvelle URI
                }

                // Déstructure la paire pour obtenir les variables
                val workbook = excelInitResult.first
                val excelFileUri = excelInitResult.second


                // --- 3. Gérer la feuille quotidienne (créer ou trouver) ---
                val dailySheet: Sheet = workbook.getSheet(dailySheetName) ?: workbook.createSheet(dailySheetName)

                // --- Styles pour les en-têtes ---
                val headerStyle: CellStyle = workbook.createCellStyle().apply {
                    fillForegroundColor = IndexedColors.GREY_25_PERCENT.getIndex()
                    fillPattern = FillPatternType.SOLID_FOREGROUND
                    setFont(workbook.createFont().apply {
                        bold = true
                    })
                }

                // --- En-têtes pour les feuilles quotidiennes ---
                val dailyHeaders = listOf(
                    "Numéro de Facture", "Date de Facture", "Type de Facture",
                    "Vendeur", "Client", "Montant Net Total", "Montant Brut Total"
                )

                // Déterminer la prochaine ligne disponible sur la feuille quotidienne
                var nextRowIndex = 0
                if (dailySheet.physicalNumberOfRows > 0) {
                    // Trouver la dernière ligne non vide pour une "facture" existante
                    var lastExistingDataRow = -1
                    // Itérer sur les lignes pour trouver la dernière qui contient des données de facture
                    // On cherche une ligne où la colonne 0 (Numéro de Facture) n'est pas vide.
                    // Cela permet de gérer les sauts de N lignes.
                    for (i in 0 until dailySheet.physicalNumberOfRows) {
                        val row = dailySheet.getRow(i)
                        val cell = row?.getCell(0)
                        if (cell != null && cell.cellType == CellType.STRING && cell.stringCellValue.isNotBlank()) {
                            val isBoldHeader = cell.cellStyle?.getFontIndex()?.let { fontIndex ->
                                workbook.getFontAt(fontIndex)?.bold
                            } ?: false

                            if (!isBoldHeader) { // C'est une ligne de données, pas un en-tête gras
                                lastExistingDataRow = i
                            }
                        }
                    }

                    if (lastExistingDataRow != -1) {
                        // Si des données existent, le prochain ensemble de données commence N lignes après la dernière ligne de données existante.
                        // (1 ligne d'en-tête + 1 ligne de données + 20 lignes pour l'image = 22 lignes par bloc de facture)
                        nextRowIndex = lastExistingDataRow + 21 // +21 pour se placer sur la ligne de l'en-tête du prochain bloc
                    }
                }

                // Créer la ligne d'en-tête pour la nouvelle entrée
                val headerRow = dailySheet.createRow(nextRowIndex)
                dailyHeaders.forEachIndexed { index, header ->
                    headerRow.createCell(index).apply {
                        setCellValue(header)
                        cellStyle = headerStyle
                    }
                }

                // Écrire les données de la facture une ligne en dessous des en-têtes
                val dataRow = dailySheet.createRow(nextRowIndex + 1)
                dataRow.createCell(0).setCellValue(invoiceNumber)
                dataRow.createCell(1).setCellValue(invoiceDateStr)
                dataRow.createCell(2).setCellValue(invoiceType)
                dataRow.createCell(3).setCellValue(seller)
                dataRow.createCell(4).setCellValue(client)
                dataRow.createCell(5).setCellValue(formattedTotalNetAmount.toDoubleOrNull() ?: 0.0)
                dataRow.createCell(6).setCellValue(formattedTotalGrossAmount.toDoubleOrNull() ?: 0.0)

                // Ajuster la largeur des colonnes pour le texte et l'image
                dailyHeaders.forEachIndexed { index, _ ->
                    dailySheet.setColumnWidth(index, 20 * 256) // Largeur par défaut pour le texte
                }

                // --- Insertion de l'image (si disponible) ---
                if (selectedBitmap != null) {
                    val imageBytes = ByteArrayOutputStream().apply {
                        selectedBitmap?.compress(Bitmap.CompressFormat.JPEG, 90, this)
                    }.toByteArray()

                    val pictureIdx = workbook.addPicture(imageBytes, Workbook.PICTURE_TYPE_JPEG)
                    val drawing = dailySheet.createDrawingPatriarch()

                    // ANCRE MISE À JOUR : 20 lignes de hauteur (de nextRowIndex + 2 à nextRowIndex + 21)
                    // L'ancre est définie par la cellule de début (col1, row1) et la cellule de fin (col2, row2).
                    // Si vous voulez 20 lignes (ex: de la ligne 2 à la ligne 21 incluse), la différence est 19.
                    // L'argument row2 est le dernier index de ligne **inclus**.
                    // Donc pour 20 lignes, (nextRowIndex + 2) + 20 - 1 = nextRowIndex + 21
                    val anchor = drawing.createAnchor(0, 0, 0, 0,
                        0, nextRowIndex + 2,   // Colonne de début 0, Ligne de début (2 lignes après les données)
                        3, nextRowIndex + 21)  // Colonnes 0 à 3 (4 colonnes de large), Lignes (nextRowIndex + 2) à (nextRowIndex + 21) (20 lignes de haut)


                    val picture = drawing.createPicture(anchor, pictureIdx)

                    // SUPPRIMEZ OU COMMENTEZ À NOUVEAU LA LIGNE SUIVANTE :
                    // picture.resize() // C'est cette ligne qui cause le java.lang.NoClassDefFoundError

                    // Ajuster les hauteurs de ligne pour les lignes qui contiendront l'image
                    val rowHeightInPoints = 20.0f // Augmentez la hauteur de chaque ligne pour l'image (essayez 20.0f ou 25.0f si nécessaire)
                    for (r in nextRowIndex + 2..nextRowIndex + 21) { // Boucle pour les 20 lignes de l'image (de R+2 à R+21)
                        val row = dailySheet.getRow(r) ?: dailySheet.createRow(r)
                        row.heightInPoints = rowHeightInPoints
                    }
                    // Ajuster la largeur de la première colonne (où l'image commence) si elle est trop étroite
                    dailySheet.setColumnWidth(0, 40 * 256) // Augmenter encore la largeur de la colonne A
                    dailySheet.setColumnWidth(1, 40 * 256) // Optionnel: augmenter les colonnes suivantes si l'image déborde
                    dailySheet.setColumnWidth(2, 40 * 256)
                    dailySheet.setColumnWidth(3, 40 * 256)
                }

                // --- 4. Gérer la feuille "Résumé" ---
                val summarySheet: Sheet = workbook.getSheet(summarySheetName) ?: workbook.createSheet(summarySheetName)

                // Définir les en-têtes pour la feuille de résumé si c'est une nouvelle feuille
                val summaryHeaders = listOf(
                    "Numéro de Facture", "Date de Facture", "Client", "Montant Brut Total", "Lien vers Facture Complète"
                )

                if (summarySheet.physicalNumberOfRows == 0 || summarySheet.getRow(0)?.getCell(0)?.stringCellValue != summaryHeaders[0]) {
                    // Si la feuille est vide ou les en-têtes sont incorrects, recréer les en-têtes
                    val summaryHeaderRow = summarySheet.createRow(0)
                    summaryHeaders.forEachIndexed { index, header ->
                        summaryHeaderRow.createCell(index).apply {
                            setCellValue(header)
                            cellStyle = headerStyle
                        }
                    }
                    // Ajuster la largeur des colonnes pour le résumé
                    summaryHeaders.forEachIndexed { index, _ ->
                        summarySheet.setColumnWidth(index, 25 * 256)
                    }
                }

                // Trouver la première ligne vide pour ajouter une nouvelle entrée au résumé
                val nextSummaryRowIndex = summarySheet.physicalNumberOfRows

                val summaryDataRow = summarySheet.createRow(nextSummaryRowIndex)

                // Déterminer les références de cellule pour la feuille quotidienne
                // Les données commencent à (nextRowIndex + 1) et les colonnes 0, 1, 4, 6
                val dailySheetCol0 = CellReference.convertNumToColString(0) // A
                val dailySheetCol1 = CellReference.convertNumToColString(1) // B
                val dailySheetCol4 = CellReference.convertNumToColString(4) // E
                val dailySheetCol6 = CellReference.convertNumToColString(6) // G

                // +1 pour 0-indexé et +1 car Excel est 1-indexé
                val dailyDataRowNumber = nextRowIndex + 1 + 1

                // Numéro de Facture (lien direct)
                summaryDataRow.createCell(0).apply {
                    cellFormula = "'$dailySheetName'!${dailySheetCol0}$dailyDataRowNumber"
                }

                // Date de Facture (lien direct)
                summaryDataRow.createCell(1).apply {
                    cellFormula = "'$dailySheetName'!${dailySheetCol1}$dailyDataRowNumber"
                }

                // Client (lien direct)
                summaryDataRow.createCell(2).apply {
                    cellFormula = "'$dailySheetName'!${dailySheetCol4}$dailyDataRowNumber"
                }

                // Montant Brut Total (lien direct)
                summaryDataRow.createCell(3).apply {
                    cellFormula = "'$dailySheetName'!${dailySheetCol6}$dailyDataRowNumber"
                }

                // Créer un hyperlien vers la feuille de facture correspondante
                val createHelper = workbook.creationHelper
                val link = createHelper.createHyperlink(HyperlinkType.DOCUMENT)
                link.address = "'$dailySheetName'!A1" // Lien vers la première cellule de la feuille de facture
                summaryDataRow.createCell(4).apply {
                    setCellValue("Voir Facture")
                    setHyperlink(link)
                    // Optionnel: Ajouter un style pour le lien hypertexte (texte bleu souligné)
                    cellStyle = workbook.createCellStyle().apply {
                        setFont(workbook.createFont().apply {
                            underline = Font.U_SINGLE
                            color = IndexedColors.BLUE.getIndex()
                        })
                    }
                }


                // --- 5. Sauvegarder le classeur ---
                val outputStream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentResolver.openOutputStream(excelFileUri)
                        ?: throw IOException("Impossible d'obtenir le flux de sortie pour le fichier Excel (Uri: $excelFileUri).")
                } else {
                    val file = File(excelFileUri.path!!)
                    FileOutputStream(file)
                }

                outputStream.use {
                    workbook.write(it)
                }

                // Pour Android 10+, marquer le fichier comme non "pending"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0) // Marquer comme non pending
                    }
                    val updatedRows = contentResolver.update(excelFileUri, contentValues, null, null)
                    if (updatedRows > 0) {
                        Log.d("ExcelSaveDebug", "Fichier marqué comme non IS_PENDING: $excelFileUri")
                    } else {
                        Log.w("ExcelSaveDebug", "Échec de la mise à jour de IS_PENDING pour: $excelFileUri")
                    }
                }

                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Données enregistrées dans ${excelFileName} (Dossier Téléchargements/Factures) !", Toast.LENGTH_LONG).show()
                    Log.d("ExcelSaveDebug", "Opération d'enregistrement Excel terminée avec succès.")
                }

                // Appel de la fonction d'envoi vers Google Drive
                //uploadToGoogleDrive(excelFileUri, excelFileName)

                shareExcelFile(excelFileUri, excelFileName)


            } catch (e: Exception) {
                Log.e("ExcelSaveDebug", "Erreur irrécupérable lors de l'enregistrement des données Excel (XLSX): ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Erreur lors de l'enregistrement des données Excel: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            } finally {
                Log.d("ExcelSaveDebug", "--- Fin de l'opération d'enregistrement Excel ---")
            }
        }
    }


    private fun shareExcelFile(excelFileUri: Uri, excelFileName: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, excelFileUri)
            putExtra(Intent.EXTRA_TITLE, excelFileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        // Lancer le sélecteur d’application
        startActivity(Intent.createChooser(shareIntent, "Partager le fichier Excel via"))
    }




    private fun uploadToGoogleDrive(excelFileUri: Uri, excelFileName: String) {
        mDriveServiceHelper?.let { service ->
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    // 1. Copier le contenu de excelFileUri dans un fichier local temporaire
                    val localFileToUpload = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val tempFile = File(cacheDir, excelFileName)
                        contentResolver.openInputStream(excelFileUri)?.use { inputStream ->
                            FileOutputStream(tempFile).use { output ->
                                inputStream.copyTo(output)
                            }
                        }
                        tempFile
                    } else {
                        File(excelFileUri.path!!)
                    }

                    // 2. Vérifier que le fichier local existe bien
                    if (!localFileToUpload.exists()) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Erreur: Fichier local non trouvé pour l'envoi.", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }

                    // 3. Rechercher le dossier "Factures" sur Google Drive (ID déjà connu)
                    val folderId = "1pQHxhcQid14dhsSn7ehgHQ4kUQyuAmQj"

                    // 4. Vérifier si le fichier existe déjà dans ce dossier
                    val existingFileId: String? = try {
                        Tasks.await(service.searchFileInFolder(excelFileName, folderId))
                    } catch (e: Exception) {
                        Log.e("DriveUpload", "Erreur lors de la recherche du fichier dans le dossier : ${e.message}", e)
                        null
                    }

                    // 5. Uploader le fichier (création ou mise à jour)
                    val uploadedFileId: String? = try {
                        Tasks.await(service.uploadFile(localFileToUpload, folderId, existingFileId))
                    } catch (e: Exception) {
                        Log.e("DriveUpload", "Erreur lors de l'upload du fichier : ${e.message}", e)
                        null
                    }

                    // 6. Afficher résultat à l'utilisateur
                    if (uploadedFileId != null) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Fichier envoyé sur Google Drive avec succès !", Toast.LENGTH_SHORT).show()
                            Log.d("DriveUpload", "Upload sur Drive réussi, ID du fichier : $uploadedFileId")
                        }
                    } else {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Erreur lors de l'envoi du fichier sur Drive.", Toast.LENGTH_LONG).show()
                        }
                    }

                    // 7. Supprimer le fichier temporaire si créé dans cacheDir
                    if (localFileToUpload.parentFile == cacheDir) {
                        localFileToUpload.delete()
                    }

                } catch (e: Exception) {
                    Log.e("DriveUpload", "Erreur lors de l'envoi du fichier sur Drive: ${e.message}", e)
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Erreur lors de l'envoi sur Drive: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } ?: runOnUiThread {
            Toast.makeText(this@MainActivity, "Le service Google Drive n'est pas initialisé.", Toast.LENGTH_LONG).show()
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

    private fun bitmapToBase64Bytes(bitmap: Bitmap): ByteArray? {
        return try {
            ByteArrayOutputStream().use { outputStream ->
                // Compress with 80% quality, adjust if needed
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                outputStream.toByteArray()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error converting bitmap to bytes: ${e.message}", e)
            null
        }
    }
}

// version finale