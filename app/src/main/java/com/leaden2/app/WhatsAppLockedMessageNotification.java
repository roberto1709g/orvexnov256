package com.leaden2.app;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

/** Notifica que un mensaje dictado está pendiente porque el teléfono está bloqueado. */
public final class WhatsAppLockedMessageNotification {
    private static final String CHANNEL_ID = "orvex_locked_message_v177";
    private static final int NOTIFICATION_ID = 7717;

    private WhatsAppLockedMessageNotification() { }

    public static boolean show(Context context, String phone, long armedAt, long expiresAt) {
        if (context == null || phone == null || phone.trim().isEmpty() || armedAt <= 0L) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false;

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Mensajes pendientes de WhatsApp", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Aviso para desbloquear el teléfono y continuar un mensaje dictado.");
            channel.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
            manager.createNotificationChannel(channel);
            NotificationChannel current = manager.getNotificationChannel(CHANNEL_ID);
            if (current != null && current.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
        }

        Intent resumeIntent = new Intent(context, WakeAndLaunchActivity.class);
        resumeIntent.putExtra(WakeAndLaunchActivity.EXTRA_AUTO_MESSAGE, true);
        resumeIntent.putExtra(WakeAndLaunchActivity.EXTRA_MESSAGE_ARMED_AT, armedAt);
        resumeIntent.setData(Uri.parse("orvex://whatsapp-message-unlock/" + armedAt));
        resumeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, (int) (armedAt & 0x7fffffff), resumeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("ORVEX: mensaje pendiente")
                .setContentText("Desbloquea tu teléfono para finalizar el envío del mensaje.")
                .setStyle(new NotificationCompat.BigTextStyle().bigText(
                        "El mensaje está preparado. Desbloquea tu teléfono y toca esta notificación para continuar el envío en WhatsApp."))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setTimeoutAfter(Math.max(1000L, expiresAt - System.currentTimeMillis()))
                .addAction(android.R.drawable.ic_dialog_info, "DESBLOQUEAR Y CONTINUAR", pendingIntent);
        try {
            manager.notify(NOTIFICATION_ID, builder.build());
            return true;
        } catch (SecurityException exception) {
            return false;
        }
    }

    public static void cancel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(NOTIFICATION_ID);
    }
}
