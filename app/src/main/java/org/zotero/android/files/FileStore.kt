package org.zotero.android.files

import android.content.Context
import android.content.res.AssetManager
import android.net.Uri
import com.google.common.base.Charsets
import com.google.common.io.Files
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.internal.closeQuietly
import org.zotero.android.androidx.content.getFileSize
import org.zotero.android.architecture.SdkPrefs
import org.zotero.android.backgrounduploader.BackgroundUpload
import org.zotero.android.helpers.MimeType
import org.zotero.android.sync.LibraryIdentifier
import org.zotero.android.sync.SyncObject
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Used to store objects and object trees in files.
 */
@Singleton
class FileStore @Inject constructor (
    private val context: Context,
    private val sdkPrefs: SdkPrefs,
    val dataMarshaller: DataMarshaller,
) {

    private lateinit var rootDirectory: File
    private lateinit var cachesDirectory: File

    private lateinit var dbFile: File
    private lateinit var bundledDataDbFile: File

    companion object {
        private const val BUNDLED_SCHEMA_FILE = "schema.json"

        private const val ACTIVE_KEY_FILE = "uploads"
        private const val SESSION_IDS_KEY_FILE = "activeUrlSessionIds"
        private const val EXTENSION_SESSION_IDS_KEY = "shareExtensionObservedUrlSessionIds"
    }

    fun init() {
        initializeDirectories()
        initDbFiles()
    }

    private fun initDbFiles() {
        val userId = sdkPrefs.getUserId()
        dbFile = fileURLForFilename("maindb_$userId.realm")!!
        bundledDataDbFile = fileURLForFilename("translators.realm")!!
    }

    private fun initializeDirectories() {
        val filesDir = context.filesDir
        rootDirectory = filesDir
        rootDirectory.mkdirs()

        cachesDirectory = File(context.cacheDir, "cache")
        cachesDirectory.mkdirs()
    }

    fun pathForFilename(filename: String?): String {
        return File(rootDirectory, filename).absolutePath
    }

    /**
     * Creates a java file object for a given filename.
     */
    fun fileURLForFilename(filename: String?): File? {
        return if (filename == null || filename.isEmpty()) {
            null
        } else File(pathForFilename(filename))
    }

    fun getDbFile() = dbFile
    fun getBundledDataDbFile() = bundledDataDbFile

    fun getRootDirectory() = rootDirectory


    @Throws(IOException::class)
    fun loadAssetIntoJsonObject(assetFileName: String): JsonObject {
        try {
            val assetManager: AssetManager = context.assets
            val assetFileDescriptor = assetManager.openFd(assetFileName)
            val inputStream = assetFileDescriptor.createInputStream()
            val fromJson = Gson().fromJson(InputStreamReader(inputStream), JsonObject::class.java)
            inputStream.closeQuietly()
            return fromJson
        } catch (e: IOException) {
            throw e
        }
    }

    fun getBundledSchema(): JsonObject? {
        return try {
            loadAssetIntoJsonObject(BUNDLED_SCHEMA_FILE)
        } catch (e: Exception) {
            Timber.d("Failed to load cached bundled data")
            null
        }
    }

    fun saveBundledSchema(data: JsonObject) {
        try {
            saveObject(data, BUNDLED_SCHEMA_FILE)
        } catch (e: Exception) {
            Timber.e(e, "Failed to cache bundled schema")
        }
    }

    /**
     * Serializes any object into json and stores it in a given file
     * @param objectToStore object that needs to be stored
     * @param filename name of the file to store json
     */
    fun saveObject(objectToStore: Any?, filename: String) {
        if (objectToStore == null) {
            return
        }
        val data = dataMarshaller.marshal(objectToStore)
        writeDataToFileWithName(filename, data)
    }

    private fun writeDataToFileWithName(filename: String, data: String) {
        try {
            Files.asCharSink(File(pathForFilename(filename)), Charsets.UTF_8).write(data)
        } catch (e: IOException) {
            Timber.e(e, "Unable to write data to file = $filename")
        }
    }

    fun attachmentFile(libraryId: LibraryIdentifier, key: String, filename: String, contentType: String): File {
        val name = split(filename = filename).first
        val folderPath = File(getRootDirectory(), "downloads/${libraryId.folderName}/$key")
        folderPath.mkdirs()
        val result = File(folderPath, name)
        return result
    }

    fun annotationPreview(annotationKey: String, pdfKey: String, libraryId: LibraryIdentifier, isDark: Boolean): File {
        val folderPath = File(getRootDirectory(), "annotations/${libraryId.folderName}/$pdfKey")
        folderPath.mkdirs()
        val name = annotationKey + (if(isDark) "_dark" else "") + ".png"
        val result = File(folderPath, name)
        return result
    }

    fun annotationPreviews(pdfKey: String, libraryId: LibraryIdentifier): File {
        val folderPath = File(getRootDirectory(), "annotations/${libraryId.folderName}/$pdfKey")
        folderPath.mkdirs()
        return folderPath
    }

    fun annotationPreviews(libraryId: LibraryIdentifier): File {
        val folderPath = File(getRootDirectory(), "annotations/${libraryId.folderName}")
        folderPath.mkdirs()
        return folderPath
    }

    val annotationPreviews: File get() {
        val folderPath = File(getRootDirectory(), "annotations")
        folderPath.mkdirs()
        return folderPath
    }

    fun jsonCacheFile(objectS: SyncObject, libraryId: LibraryIdentifier, key: String): File {
        val objectName: String
                when(objectS) {
            SyncObject.collection ->
            objectName = "collection"
            SyncObject.item, SyncObject.trash ->
            objectName = "item"
            SyncObject.search ->
            objectName = "search"
            SyncObject.settings ->
            objectName = "settings"
        }
        val folderPath = File(getRootDirectory(), "jsons/${libraryId.folderName}/$objectName")
        folderPath.mkdirs()
        val result = File(folderPath, "$key.json")
        return result
    }

    private fun split(filename: String): Pair<String, String> {
        val index = filename.lastIndexOf(".")
        if (index != -1) {
            val name = filename.substring(0, index)
            val ext = filename.substring(index + 1, filename.length)
            return name to ext
        }
        return filename to ""
    }

    fun md5(file: File): String {
        val inputStream = FileInputStream(file)
        val md5: String = org.apache.commons.codec.digest.DigestUtils.md5Hex(inputStream)
        return md5
    }

    fun getShareExtensionSessionIds(): List<String>? {
        return loadListDataWithFilename(
            EXTENSION_SESSION_IDS_KEY,
        )
    }

    fun saveShareExtensionSessions(identifiers: List<String>){
        saveObject(
            identifiers,
            EXTENSION_SESSION_IDS_KEY
        )
    }

    fun getSessionIds(): List<String>? {
        return loadListDataWithFilename(
            SESSION_IDS_KEY_FILE,
        )
    }

    fun saveSessions(identifiers: List<String>){
        saveObject(
            identifiers,
            SESSION_IDS_KEY_FILE
        )
    }

    fun deleteAllSessionIds(){
        deleteDataWithFilename(SESSION_IDS_KEY_FILE)
    }

    fun getUploads(): Map<Int, BackgroundUpload>? {
        return loadMapDataWithFilename(
            ACTIVE_KEY_FILE,
        )
    }

    fun saveUploads(uploads: Map<Int, BackgroundUpload>){
        saveObject(
            uploads,
            ACTIVE_KEY_FILE
        )
    }

    fun deleteAllUploads(){
        deleteDataWithFilename(ACTIVE_KEY_FILE)
    }

    inline fun <reified T> loadListDataWithFilename(filename: String): MutableList<T>? {
        if (!fileExists(filename)) {
            return null
        }
        val path = pathForFilename(filename)
        return try {
            val json = Files.asCharSource(File(path), Charsets.UTF_8).read()
            dataMarshaller.unmarshalList(json)
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
            null
        }
    }

    inline fun <reified K, reified V> loadMapDataWithFilename(
        filename: String,
    ): Map<K, V>? {
        if (!fileExists(filename)) {
            return null
        }
        val path = pathForFilename(filename)
        return try {
            val json = Files.asCharSource(File(path), Charsets.UTF_8).read()
            dataMarshaller.unmarshalMap(json)
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
            null
        }
    }

    fun fileExists(filename: String): Boolean {
        return fileURLForFilename(filename)?.exists() ?: false
    }

    fun deleteDataWithFilename(filename: String?) {
        if (filename == null || !fileExists(filename)) {
            return
        }
        val file = fileURLForFilename(filename)
        file?.delete()
    }

    fun isPdf(file: File): Boolean {
        val buffer = ByteArray(4)
        val inputStream: InputStream = FileInputStream(file)
        if (inputStream.read(buffer) != buffer.size) {
            // do something
        }
        inputStream.closeQuietly()
        return buffer.contentEquals(byteArrayOf(0x25, 0x50, 0x44, 0x46))
    }

    fun getCachesDirectory(): File {
        cachesDirectory.mkdirs()
        return cachesDirectory
    }

    fun getFileSize(uri: Uri): Long? = context.getFileSize(uri)

    fun openInputStream(uri: Uri): InputStream? =
        context.contentResolver.openInputStream(uri)

    fun getType(uri: Uri): MimeType? = context.contentResolver.getType(uri)

}
