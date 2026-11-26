package com.google.ai.edge.gallery.security

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * Stores and loads chat image attachments encrypted on disk via Jetpack Security EncryptedFile.
 */
object ChatImageStore {

    private const val TAG = "ChatImageStore"
    private const val DIR_NAME = "chat_images"

    private fun getEncryptedFile(context: Context, file: File): EncryptedFile {
        val appContext = context.applicationContext
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedFile.Builder(
            appContext,
            file,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()
    }

    fun saveImage(context: Context, bitmap: Bitmap): String? {
        return try {
            val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
            val target = File(dir, "${UUID.randomUUID()}.png.enc")
            val bytes = ByteArrayOutputStream().use { baos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
                baos.toByteArray()
            }
            getEncryptedFile(context, target).openFileOutput().use { out ->
                out.write(bytes)
            }
            target.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save encrypted chat image", e)
            null
        }
    }

    fun loadImage(context: Context, path: String): Bitmap? {
        return try {
            val file = File(path)
            if (!file.exists()) return null
            if (!path.endsWith(".enc")) {
                return BitmapFactory.decodeFile(path)
            }
            val bytes = getEncryptedFile(context, file).openFileInput().use { input ->
                input.readBytes()
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load encrypted chat image", e)
            null
        }
    }

    fun deleteImage(path: String) {
        try {
            SecurityUtils.secureDelete(File(path))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete chat image", e)
        }
    }
}
