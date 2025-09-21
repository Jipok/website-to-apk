package com.myexample.webtoapk; 

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;

import org.unifiedpush.android.connector.FailedReason;
import org.unifiedpush.android.connector.PushService;
import org.unifiedpush.android.connector.data.PushEndpoint;
import org.unifiedpush.android.connector.data.PushMessage;
import org.unifiedpush.android.connector.data.PublicKeySet;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import android.os.Build;
import android.content.SharedPreferences;

// Unified Push v3
public class PushServiceImpl extends PushService {

    private static final String NOTIFICATION_CHANNEL_ID = "unified_push_notifications";
    private static final String NOTIFICATION_CHANNEL_NAME = "UnifiedPush Notifications";

    @Override
    // Method signature uses the 'PushEndpoint' class
    public void onNewEndpoint(PushEndpoint endpoint, String instance) {
        String endpointUrl = endpoint.getUrl();
        PublicKeySet keySet = endpoint.getPubKeySet();

        Log.d("WebToApk", "UnifiedPush:: onNewEndpoint: " + endpointUrl + " for instance " + instance);

        // Safety check
        if (keySet == null) {
            Log.e("WebToApk", "UnifiedPush:: PublicKeySet is null, cannot get WebPush keys.");
            return;
        }

        // The 'pubKey' property from the library corresponds to 'p256dh' in the Web Push standard.
        String p256dh = keySet.getPubKey();
        String auth = keySet.getAuth();

        Log.d("WebToApk", "UnifiedPush:: Received p256dh key: " + p256dh);
        Log.d("WebToApk", "UnifiedPush:: Received auth key: " + auth);

        // Store the full URL and keys
        getSharedPreferences("unifiedpush", Context.MODE_PRIVATE)
            .edit()
            .putString("endpoint_" + instance, endpointUrl)
            .putString("p256dh_" + instance, p256dh)
            .putString("auth_" + instance, auth)
            .apply();

        // Broadcast the new endpoint AND keys to the MainActivity
        Intent intent = new Intent("com.myexample.webtoapk.NEW_ENDPOINT");
        intent.putExtra("endpoint", endpointUrl);
        intent.putExtra("p256dh", p256dh);
        intent.putExtra("auth", auth);
        intent.putExtra("instance", instance);
        sendBroadcast(intent);
    }

    @Override
    // Method signature uses the 'PushMessage' class
    public void onMessage(PushMessage message, String instance) {
        // The message content is a byte array.
        String messageStr = new String(message.getContent());
        Log.d("WebToApk", "UnifiedPush:: onMessage: Received raw message: " + messageStr + " for instance " + instance);

        String title;
        String content;

        // Try to parse the message as JSON
        try {
            JSONObject json = new JSONObject(messageStr);
            title = json.optString("title", "New Push Message"); 
            content = json.optString("body", messageStr);
        } catch (JSONException e) {
            // If parsing fails, it's probably just a plain text message.
            Log.w("WebToApk", "UnifiedPush:: Message is not a JSON object, treating as plain text.", e);
            title = "New Push Message";
            content = messageStr;
        }

        // Create a notification channel if on API 26+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, NOTIFICATION_CHANNEL_NAME, importance);
            channel.setDescription("Channel for UnifiedPush notifications");
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }

        // Create an intent to open the app when the notification is tapped
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);

        // Use instance hashcode + system time for a unique ID
        int notificationId = (instance + String.valueOf(System.currentTimeMillis())).hashCode();
        notificationManager.notify(notificationId, builder.build());
    }

    @Override
    public void onUnregistered(String instance) {
        Log.d("WebToApk", "UnifiedPush:: onUnregistered for instance " + instance);
        // Remove the stored endpoint and keys
        getSharedPreferences("unifiedpush", Context.MODE_PRIVATE)
            .edit()
            .remove("endpoint_" + instance)
            .remove("p256dh_" + instance)
            .remove("auth_" + instance)
            .apply();
    }

    @Override
    // Method signature uses the 'FailedReason' class as required by the error message
    public void onRegistrationFailed(FailedReason reason, String instance) {
        Log.e("WebToApk", "UnifiedPush:: onRegistrationFailed for instance " + instance + ". Reason: " + reason);
    }
}
