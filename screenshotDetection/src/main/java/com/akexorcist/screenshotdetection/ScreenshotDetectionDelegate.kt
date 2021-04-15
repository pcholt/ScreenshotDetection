package com.akexorcist.screenshotdetection

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import java.io.File
import java.lang.ref.WeakReference
import java.util.*

open class ScreenshotDetectionDelegate(
        private val activityReference: WeakReference<Activity>,
        private val listener: ScreenshotDetectionListener
) {
    companion object {
        private const val TAG = "ScreenshotDetection"
    }

    constructor(
            activity: Activity,
            listener: ScreenshotDetectionListener
    ) : this(WeakReference(activity), listener)

    constructor(
            activity: Activity,
            onScreenCaptured: (path: String) -> Unit,
            onScreenCapturedWithDeniedPermission: () -> Unit
    ) : this(
            WeakReference(activity),
            object : ScreenshotDetectionListener {
                override fun onScreenCaptured(path: String) {
                    onScreenCaptured(path)
                }

                override fun onScreenCapturedWithDeniedPermission() {
                    onScreenCapturedWithDeniedPermission()
                }
            }
    )

    @Suppress("DEPRECATION")
    val screenshotDirectoryName = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_SCREENSHOTS).name.toLowerCase(Locale.getDefault())
        } else {
            "screenshot"
        }

    @Suppress("DEPRECATION")
    private val contentType = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.RELATIVE_PATH
    } else {
        MediaStore.Images.Media.DATA
    }
    var job: Job? = null

    @FlowPreview
    @ExperimentalCoroutinesApi
    fun startScreenshotDetection() {

        job = GlobalScope.launch(Dispatchers.Main) {
            createContentObserverFlow()
                .debounce(500)
                .collect { uri ->
                    activityReference.get()?.let { activity ->
                        onContentChanged(activity, uri)
                    }
                }
        }
    }

    fun stopScreenshotDetection() {
        job?.cancel()
    }

    @ExperimentalCoroutinesApi
    fun createContentObserverFlow() = channelFlow<Uri> {
        val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                uri?.let {
                    offer(it)
                }
            }
        }
        activityReference.get()
            ?.contentResolver
            ?.registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    true,
                    contentObserver
            )
        awaitClose {
            activityReference.get()
                ?.contentResolver
                ?.unregisterContentObserver(contentObserver)
        }
    }

    private fun onContentChanged(context: Context, uri: Uri) {
        if (isReadExternalStoragePermissionGranted()) {
            getFilePathFromContentResolver(context, uri)?.let { path ->
                if (isScreenshotPath(path)) {
                    onScreenCaptured(path)
                }
            }
        } else {
            onScreenCapturedWithDeniedPermission()
        }
    }

    private fun onScreenCaptured(path: String) {
        listener.onScreenCaptured(path)
    }

    private fun onScreenCapturedWithDeniedPermission() {
        listener.onScreenCapturedWithDeniedPermission()
    }

    private fun isScreenshotPath(path: String): Boolean =
        path.toLowerCase(Locale.getDefault()).contains(screenshotDirectoryName)

    private fun getFilePathFromContentResolver(context: Context, uri: Uri): String? {
        try {
            context.contentResolver.query(
                    uri, arrayOf(contentType),
                    null,
                    null,
                    null
            )?.use { cursor ->
                cursor.moveToFirst()
                return File(cursor.getString(0)).absolutePath
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, e.message ?: "")
        }
        return null
    }

    private fun isReadExternalStoragePermissionGranted(): Boolean {
        return activityReference.get()?.let { activity ->
            ContextCompat.checkSelfPermission(
                    activity,
                    Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        } ?: run {
            false
        }
    }

    interface ScreenshotDetectionListener {
        fun onScreenCaptured(path: String)

        fun onScreenCapturedWithDeniedPermission()
    }
}
