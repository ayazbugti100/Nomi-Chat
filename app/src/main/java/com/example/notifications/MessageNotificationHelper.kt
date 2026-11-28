package com.example.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

object MessageNotificationHelper {
    const val CHANNEL_ID = "nomi_messages_alerts"
    const val ADMIN_CHANNEL_ID = "nomi_admin_moderation_alerts"
    const val EXTRA_CONTACT_ID = "extra_contact_id"
    const val EXTRA_OPEN_ADMIN_ALERTS = "extra_open_admin_alerts"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Incoming Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts with sound for new Nomi Chat messages"
                enableVibration(true)
                setSound(soundUri, audioAttributes)
            }
            manager.createNotificationChannel(channel)
        }

        if (manager.getNotificationChannel(ADMIN_CHANNEL_ID) == null) {
            val adminChannel = NotificationChannel(
                ADMIN_CHANNEL_ID,
                "Admin Moderation Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High-priority moderation reports exclusively for verified admin"
                enableVibration(true)
                setSound(soundUri, audioAttributes)
            }
            manager.createNotificationChannel(adminChannel)
        }
    }

    fun showIncomingMessageNotification(
        context: Context,
        contactId: String,
        senderName: String,
        senderHandle: String,
        messageText: String
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        ensureChannel(context)
        val managerCompat = NotificationManagerCompat.from(context)
        if (!managerCompat.areNotificationsEnabled()) return

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_CONTACT_ID, contactId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            contactId.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (senderHandle.isNotBlank()) "$senderName (@$senderHandle)" else senderName
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_chat)
            .setContentTitle(title)
            .setContentText(messageText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(messageText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setSound(soundUri)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE or NotificationCompat.DEFAULT_LIGHTS)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            managerCompat.notify(contactId.hashCode(), notification)
        }
    }

    fun showAdminModerationNotification(
        context: Context,
        alertId: String,
        reportedHandle: String,
        reporterHandle: String,
        reason: String,
        alertType: String = "report",
        aiRecommendation: String = "",
        aiPrompt: String = ""
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        ensureChannel(context)
        val managerCompat = NotificationManagerCompat.from(context)
        if (!managerCompat.areNotificationsEnabled()) return

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_ADMIN_ALERTS, true)
        }
        val requestCode = ("admin_" + alertId).hashCode()
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cleanReported = reportedHandle.trim().removePrefix("@").ifBlank { "user" }
        val cleanReporter = reporterHandle.trim().removePrefix("@").ifBlank { "member" }
        val isAppeal = alertType == "appeal"
        val title = if (isAppeal) {
            "Ban Appeal (AI Review): @$cleanReported"
        } else {
            "Admin Moderation Alert: @$cleanReported"
        }
        val body = if (isAppeal) {
            val aiLabel = if (aiRecommendation == "RESTORE") "AI suggests Restore" else "AI suggests Keep Banned"
            val promptPart = aiPrompt.ifBlank { "Restore/unban @$cleanReported or keep banned?" }
            "$aiLabel • $promptPart"
        } else {
            "Reason: $reason • Reported by @$cleanReporter. Tap to Ignore, Ban, or Recovery."
        }
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val notification = NotificationCompat.Builder(context, ADMIN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSound(soundUri)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE or NotificationCompat.DEFAULT_LIGHTS)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            managerCompat.notify(requestCode, notification)
        }
    }
}
