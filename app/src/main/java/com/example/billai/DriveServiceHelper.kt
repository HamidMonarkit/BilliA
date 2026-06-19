package com.example.billai

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.api.client.http.FileContent
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import com.google.api.services.drive.model.FileList
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class DriveServiceHelper(private val mDriveService: Drive) {

    private val mExecutor: Executor = Executors.newSingleThreadExecutor()

    /**
     * Recherche un dossier par son nom.
     * @return L'ID du dossier s'il est trouvé, sinon null.
     */
    fun searchFolder(folderName: String): Task<String?> {
        return Tasks.call(mExecutor) {
            val fileList: FileList = mDriveService.files().list()
                .setQ("mimeType='application/vnd.google-apps.folder' and name='$folderName'")
                .setSpaces("drive")
                .setFields("nextPageToken, files(id, name)")
                .execute()
            if (fileList.files.isNotEmpty()) {
                fileList.files[0].id
            } else {
                null
            }
        }
    }

    /**
     * Crée un nouveau dossier.
     * @return L'ID du nouveau dossier.
     */
    fun createFolder(folderName: String): Task<String?> {
        return Tasks.call(mExecutor) {
            val metadata = File().apply {
                name = folderName
                mimeType = "application/vnd.google-apps.folder"
            }
            val folder: File = mDriveService.files().create(metadata).setFields("id").execute()
            folder.id
        }
    }

    /**
     * Recherche un fichier par son nom dans un dossier donné.
     * @return L'ID du fichier s'il est trouvé, sinon null.
     */
    fun searchFileInFolder(fileName: String, folderId: String): Task<String?> {
        return Tasks.call(mExecutor) {
            val q = "name='$fileName' and '$folderId' in parents"
            val fileList: FileList = mDriveService.files().list()
                .setQ(q)
                .setSpaces("drive")
                .setFields("nextPageToken, files(id, name)")
                .execute()
            if (fileList.files.isNotEmpty()) {
                fileList.files[0].id
            } else {
                null
            }
        }
    }

    /**
     * Télécharge un fichier sur Drive. Met à jour si le fichier existe, sinon le crée.
     * @param localFile Le fichier local à uploader.
     * @param folderId L'ID du dossier de destination.
     * @param existingFileId L'ID du fichier existant (pour mise à jour), ou null pour créer.
     */
    fun uploadFile(localFile: java.io.File, folderId: String, existingFileId: String?): Task<String> {
        return Tasks.call(mExecutor) {
            val fileMetadata = File().apply {
                name = localFile.name
                parents = listOf(folderId)
            }
            val mediaContent = FileContent("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", localFile)

            val file: File = if (existingFileId != null) {
                // Mettre à jour le fichier existant
                mDriveService.files().update(existingFileId, fileMetadata, mediaContent).execute()
            } else {
                // Créer un nouveau fichier
                mDriveService.files().create(fileMetadata, mediaContent).setFields("id").execute()
            }
            file.id
        }
    }
}