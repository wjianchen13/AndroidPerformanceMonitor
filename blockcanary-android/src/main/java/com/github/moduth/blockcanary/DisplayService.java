/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.moduth.blockcanary;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

import com.github.moduth.blockcanary.internal.BlockInfo;
import com.github.moduth.blockcanary.ui.DisplayActivity;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;
import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.HONEYCOMB;
import static android.os.Build.VERSION_CODES.JELLY_BEAN;
import static android.os.Build.VERSION_CODES.O;
import static android.os.Build.VERSION_CODES.TIRAMISU;
import com.github.moduth.blockcanary_android.R;

final class DisplayService implements BlockInterceptor {

    private static final String TAG = "DisplayService";
    private static final String CHANNEL_ID = "blockcanary_channel";
    private static final String CHANNEL_NAME = "BlockCanary Notifications";
    private static final String CHANNEL_DESCRIPTION = "Notifications for main thread blocking events";

    @Override
    public void onBlock(Context context, BlockInfo blockInfo) {
        Intent intent = new Intent(context, DisplayActivity.class);
        intent.putExtra("show_latest", blockInfo.timeStart);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        
        // On Android 12+ (API 31+), FLAG_IMMUTABLE or FLAG_MUTABLE must be specified
        // FLAG_IMMUTABLE is available from API 23+, but required from API 31+
        int flags = FLAG_UPDATE_CURRENT;
        if (SDK_INT >= 31) {
            flags |= FLAG_IMMUTABLE;
        }
        
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 1, intent, flags);
        String contentTitle = context.getString(R.string.block_canary_class_has_blocked, blockInfo.timeStart);
        String contentText = context.getString(R.string.block_canary_notification_message);
        show(context, contentTitle, contentText, pendingIntent);
    }

    /**
     * Create notification channel for Android 8.0+ (API 26+)
     */
    private void createNotificationChannel(Context context) {
        if (SDK_INT >= O) {
            NotificationManager notificationManager = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            
            if (notificationManager == null) {
                return;
            }
            
            // Check if channel already exists
            if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_DEFAULT);
                channel.setDescription(CHANNEL_DESCRIPTION);
                channel.enableVibration(false);
                channel.enableLights(false);
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * Check if notification permission is granted
     * On Android 13+ (API 33+), POST_NOTIFICATIONS permission is required
     */
    private boolean hasNotificationPermission(Context context) {
        if (SDK_INT >= TIRAMISU) {
            // Android 13+ requires POST_NOTIFICATIONS permission
            return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) 
                    == PackageManager.PERMISSION_GRANTED;
        }
        // For Android 12 and below, notifications are allowed by default
        return true;
    }

    @TargetApi(HONEYCOMB)
    private void show(Context context, String contentTitle, String contentText, PendingIntent pendingIntent) {
        // Check notification permission first
        if (!hasNotificationPermission(context)) {
            Log.w(TAG, "Notification permission not granted. Please request POST_NOTIFICATIONS permission in your app.");
            return;
        }

        NotificationManager notificationManager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);

        if (notificationManager == null) {
            Log.w(TAG, "NotificationManager is null");
            return;
        }

        // Create notification channel for Android 8.0+
        createNotificationChannel(context);

        Notification notification;
        if (SDK_INT < HONEYCOMB) {
            notification = new Notification();
            notification.icon = R.drawable.block_canary_notification;
            notification.when = System.currentTimeMillis();
            notification.flags |= Notification.FLAG_AUTO_CANCEL;
            notification.defaults = Notification.DEFAULT_SOUND;
            try {
                Method deprecatedMethod = notification.getClass().getMethod("setLatestEventInfo", Context.class, CharSequence.class, CharSequence.class, PendingIntent.class);
                deprecatedMethod.invoke(notification, context, contentTitle, contentText, pendingIntent);
            } catch (NoSuchMethodException | IllegalAccessException | IllegalArgumentException
                    | InvocationTargetException e) {
                Log.w(TAG, "Method not found", e);
            }
        } else {
            Notification.Builder builder = new Notification.Builder(context)
                    .setSmallIcon(R.drawable.block_canary_notification)
                    .setWhen(System.currentTimeMillis())
                    .setContentTitle(contentTitle)
                    .setContentText(contentText)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .setDefaults(Notification.DEFAULT_SOUND);
            
            // Set notification channel for Android 8.0+
            if (SDK_INT >= O) {
                builder.setChannelId(CHANNEL_ID);
            }
            
            if (SDK_INT < JELLY_BEAN) {
                notification = builder.getNotification();
            } else {
                notification = builder.build();
            }
        }
        notificationManager.notify(0xDEAFBEEF, notification);
    }
}
