package jp.jy05haruto.ticketlog

import android.app.Activity
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ValueCallback
import android.webkit.JavascriptInterface
import android.net.Uri
import android.content.Intent
import android.provider.MediaStore
import androidx.core.content.FileProvider
import android.view.ViewGroup
import android.view.WindowManager
import android.graphics.Color
import android.util.Base64
import java.io.File

// Ticket photos are stored in the app-private files directory, not shared media storage.
// Build marker: include the latest ticket-management web app changes.
// Build marker: company history, safe system-bar layout, one-row filters and search reset.
class MainActivity : Activity() {
    private lateinit var webView: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val fileRequestCode = 1001
    private val cameraRequestCode = 1002
    private var pendingCameraUri: Uri? = null
    private var pendingCameraFile: File? = null
    private lateinit var photoDir: File

    inner class PhotoStore {
        @JavascriptInterface
        fun savePhoto(dataUrl: String, originalName: String): String {
            return try {
                val encoded = dataUrl.substringAfter(",", "")
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                val name = "ticket_" + java.util.UUID.randomUUID().toString() + ".img"
                File(photoDir, name).writeBytes(bytes)
                name
            } catch (_: Exception) { "" }
        }

        @JavascriptInterface
        fun getPhoto(name: String): String {
            return try {
                if (!name.matches(Regex("ticket_[a-f0-9-]+\\.img"))) return ""
                val file = File(photoDir, name)
                if (!file.isFile) return ""
                val mime = when {
                    file.readBytes().take(3).map { it.toInt() and 0xff } == listOf(0xff,0xd8,0xff) -> "image/jpeg"
                    else -> "image/png"
                }
                "data:$mime;base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
            } catch (_: Exception) { "" }
        }

        @JavascriptInterface
        fun deletePhoto(name: String) {
            if (name.matches(Regex("ticket_[a-f0-9-]+\\.img"))) File(photoDir, name).delete()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep Android status/navigation bars visible; do not run the app in immersive fullscreen.
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = 0
        window.statusBarColor = Color.rgb(11, 20, 35)
        window.navigationBarColor = Color.rgb(11, 20, 35)
        photoDir = File(filesDir, "ticket_photos").apply { mkdirs() }
        webView = WebView(this)
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        webView.settings.javaScriptEnabled = true
        // Match the WebView viewport to the physical device width and keep text sizing consistent.
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = false
        webView.settings.textZoom = 100
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        // Always fetch the current GitHub Pages UI instead of a stale WebView cache.
        webView.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        webView.settings.allowFileAccess = false
        webView.addJavascriptInterface(PhotoStore(), "AndroidPhotoStore")
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                if (params?.isCaptureEnabled == true) {
                    try {
                        val cameraDir = File(cacheDir, "camera").apply { mkdirs() }
                        val photo = File.createTempFile("ticket_camera_", ".jpg", cameraDir)
                        val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", photo)
                        pendingCameraFile = photo
                        pendingCameraUri = uri
                        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                            putExtra(MediaStore.EXTRA_OUTPUT, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        }
                        startActivityForResult(intent, cameraRequestCode)
                    } catch (_: Exception) {
                        fileCallback?.onReceiveValue(null)
                        fileCallback = null
                        pendingCameraUri = null
                        pendingCameraFile?.delete()
                        pendingCameraFile = null
                    }
                } else {
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "image/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    startActivityForResult(Intent.createChooser(intent, "端末内の写真を選択"), fileRequestCode)
                }
                return true
            }
        }
        setContentView(webView)
        if (savedInstanceState == null) {
            webView.loadUrl("https://jy05haruto.github.io/ticket-management/?v=20261119-3")
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == fileRequestCode) {
            val result = if (resultCode == RESULT_OK && data != null) {
                val clip = data.clipData
                when {
                    clip != null -> Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                    data.data != null -> arrayOf(data.data!!)
                    else -> null
                }
            } else null
            fileCallback?.onReceiveValue(result)
            fileCallback = null
        } else if (requestCode == cameraRequestCode) {
            val uri = if (resultCode == RESULT_OK && pendingCameraUri != null) pendingCameraUri else null
            fileCallback?.onReceiveValue(uri?.let { arrayOf(it) })
            fileCallback = null
            pendingCameraUri = null
            pendingCameraFile = null
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        webView.destroy()
        super.onDestroy()
    }
}