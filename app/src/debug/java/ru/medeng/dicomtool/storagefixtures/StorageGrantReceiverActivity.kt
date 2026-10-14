package ru.medeng.dicomtool.storagefixtures

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.util.concurrent.CountDownLatch

/** Debug-only fixture: receives a real persistable URI grant from the test APK's separate UID. */
class StorageGrantReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        startActivityForResult(Intent().setClassName("$packageName.test", "ru.medeng.dicomtool.storagefixtures.UriGrantActivity")
            .putExtra("root", intent.getStringExtra("root")), 51)
    }
    @Deprecated("Test fixture uses Activity result to exercise actual URI grant flags")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        try {
            check(resultCode == RESULT_OK)
            val uri = checkNotNull(data?.data)
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            granted = uri
        } catch (e: Exception) { error = e.toString() }
        finally { ready.countDown(); finish() }
    }
    companion object {
        @Volatile var ready = CountDownLatch(1)
        @Volatile var granted: Uri? = null
        @Volatile var error: String? = null
    }
}
