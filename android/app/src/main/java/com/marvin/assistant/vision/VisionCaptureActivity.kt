package com.marvin.assistant.vision

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Activity invisible qui ouvre l'app caméra système, attend la photo,
 * la stocke localement, puis renvoie le path à l'appelant via le résultat.
 *
 * On NE prend pas la photo nous-mêmes (gestion preview / surface complexe).
 * On laisse l'utilisateur cadrer avec son app caméra — beaucoup plus simple
 * et fiable sur les variantes Samsung.
 *
 * Le résultat est passé via SharedPreferences "vision_last_capture" lu par
 * AssistantService — on ne peut pas faire de bindService propre depuis une
 * activity transparent.
 */
class VisionCaptureActivity : ComponentActivity() {

    private var imageUri: Uri? = null

    private val launcher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = imageUri
        if (success && uri != null) {
            Log.i(TAG, "Picture captured: $uri")
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_LAST_URI, uri.toString())
                .putLong(KEY_LAST_AT, System.currentTimeMillis())
                .apply()
        } else {
            Log.i(TAG, "Picture capture cancelled / failed")
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Crée un fichier image vide dans MediaStore (gestion scoped storage)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "marvin_vision_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            Log.e(TAG, "Failed to create image URI in MediaStore")
            finish(); return
        }
        imageUri = uri
        launcher.launch(uri)
    }

    companion object {
        private const val TAG = "VisionCapture"
        const val PREFS = "marvin_vision"
        const val KEY_LAST_URI = "last_uri"
        const val KEY_LAST_AT = "last_at"

        /** Renvoie l'URI de la dernière capture, ou null si > 60 s ou absente. */
        fun lastCapture(context: android.content.Context): Uri? {
            val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            val uriStr = prefs.getString(KEY_LAST_URI, null) ?: return null
            val at = prefs.getLong(KEY_LAST_AT, 0L)
            if (System.currentTimeMillis() - at > 60_000L) return null
            return Uri.parse(uriStr)
        }

        fun launchFromService(context: android.content.Context) {
            val intent = Intent(context, VisionCaptureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(intent)
        }
    }
}
