package com.example.nova

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class UpdateActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
            val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            AppUpdateManager.handleDownloadComplete(context.applicationContext, downloadId)
            return
        }
        AppUpdateManager.handleAction(context.applicationContext, intent)
    }
}
