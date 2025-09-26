package com.myexample.webtoapk;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import android.util.Base64;
import android.media.AudioManager;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.NotificationCompat.Action;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.media.app.NotificationCompat.MediaStyle;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MediaPlaybackService extends Service {
    public static final String NOTIFICATION_CHANNEL_ID = "web_app_notifications";
    public static final String ACTION_UPDATE_METADATA = "com.myexample.webtoapk.UPDATE_METADATA";
    public static final String ACTION_UPDATE_STATE = "com.myexample.webtoapk.UPDATE_STATE";
    public static final String ACTION_SET_HANDLERS = "com.myexample.webtoapk.SET_HANDLERS";
    public static final String ACTION_STOP_SERVICE = "com.myexample.webtoapk.STOP_SERVICE";
    public static final String ACTION_UPDATE_POSITION = "com.myexample.webtoapk.UPDATE_POSITION";

    // Actions from notification buttons
    public static final String ACTION_PLAY = "com.myexample.webtoapk.PLAY";
    public static final String ACTION_PAUSE = "com.myexample.webtoapk.PAUSE";
    public static final String ACTION_NEXT = "com.myexample.webtoapk.NEXT";
    public static final String ACTION_PREVIOUS = "com.myexample.webtoapk.PREVIOUS";

    // Action for broadcasting to MainActivity
    public static final String BROADCAST_MEDIA_ACTION = "com.myexample.webtoapk.BROADCAST_MEDIA_ACTION";
    public static final String EXTRA_MEDIA_ACTION = "EXTRA_MEDIA_ACTION";


    private static final int NOTIFICATION_ID = 101;
    private MediaSessionCompat mediaSession;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private BroadcastReceiver becomingNoisyReceiver;

    // Inner class to handle the BECOMING_NOISY event
    private class BecomingNoisyReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                // Audio output is changing (e.g., headphones unplugged, bluetooth disconnected)
                // Let's pause the playback.
                Log.d("WebToApk", "Audio becoming noisy, sending 'pause' action.");
                sendActionToWebView("pause");
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mediaSession = new MediaSessionCompat(this, "WebToApkMediaSession");
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                              MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

        // Set initial state. It must be something other than STATE_NONE.
        PlaybackStateCompat initialState = new PlaybackStateCompat.Builder()
                .setActions(0) // No actions available initially
                .setState(PlaybackStateCompat.STATE_NONE, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 0)
                .build();
        mediaSession.setPlaybackState(initialState);

        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                sendActionToWebView("play");
            }

            @Override
            public void onPause() {
                sendActionToWebView("pause");
            }

            @Override
            public void onSkipToNext() {
                sendActionToWebView("nexttrack");
            }

            @Override
            public void onSkipToPrevious() {
                sendActionToWebView("previoustrack");
            }

            @Override
            public void onStop() {
                sendActionToWebView("stop");
            }
        });

        mediaSession.setActive(true);

        becomingNoisyReceiver = new BecomingNoisyReceiver();
        IntentFilter intentFilter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        registerReceiver(becomingNoisyReceiver, intentFilter);
    }
    

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            return START_STICKY;
        }

        String action = intent.getAction();

        switch (action) {
            case ACTION_UPDATE_METADATA:
                updateMetadata(
                    intent.getStringExtra("title"),
                    intent.getStringExtra("artist"),
                    intent.getStringExtra("album"),
                    intent.getStringExtra("artworkUrl")
                );
                break;
            case ACTION_UPDATE_STATE:
                updatePlaybackState(intent.getStringExtra("state"));
                break;
            case ACTION_UPDATE_POSITION:
                updatePositionState(
                    intent.getDoubleExtra("duration", 0),
                    intent.getDoubleExtra("playbackRate", 1.0),
                    intent.getDoubleExtra("position", 0)
                );
                break;
            case ACTION_SET_HANDLERS:
                setMediaActionHandlers(intent.getStringArrayExtra("actions"));
                break;
            case ACTION_STOP_SERVICE:
                stopSelf();
                break;
            // Handle actions from notification buttons
            case ACTION_PLAY:
                sendActionToWebView("play");
                break;
            case ACTION_PAUSE:
                sendActionToWebView("pause");
                break;
            //
            // MODIFIED: Added handlers for next/previous actions from notification
            //
            case ACTION_NEXT:
                sendActionToWebView("nexttrack");
                break;
            case ACTION_PREVIOUS:
                sendActionToWebView("previoustrack");
                break;
        }

        return START_STICKY;
    }

    private void updateMetadata(String title, String artist, String album, @Nullable String artworkUrl) {
        MediaMetadataCompat.Builder metadataBuilder = new MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album);

        if (artworkUrl != null && !artworkUrl.isEmpty()) {
            try {
                // The format is "data:image/png;base64,iVBORw0KGgo..."
                // We need to find the comma and decode the part after it.
                String base64String = artworkUrl.substring(artworkUrl.indexOf(',') + 1);
                byte[] decodedBytes = Base64.decode(base64String, Base64.DEFAULT);
                Bitmap artworkBitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);

                metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artworkBitmap);
                mediaSession.setMetadata(metadataBuilder.build());
                updateNotification(); // Refresh notification with artwork
            } catch (Exception e) {
                Log.e("WebToApk", "Error decoding Base64 artwork", e);
                // If decoding fails, continue without artwork.
                mediaSession.setMetadata(metadataBuilder.build());
                updateNotification();
            }
        } else {
            // No artwork URL provided. Update metadata immediately without an image.
            mediaSession.setMetadata(metadataBuilder.build());
            updateNotification();
        }
    }

    private void updatePositionState(double duration, double playbackRate, double position) {
        PlaybackStateCompat currentState = mediaSession.getController().getPlaybackState();
        if (currentState == null || currentState.getState() == PlaybackStateCompat.STATE_NONE) {
            // Can't set position on a 'none' state. Wait for a play/pause state.
            return;
        }

        long durationMs = (long) (duration * 1000);
        long positionMs = (long) (position * 1000);
        float rate = (float) playbackRate;

        // Duration is part of MediaMetadata. We need to update it.
        MediaMetadataCompat currentMetadata = mediaSession.getController().getMetadata();
        MediaMetadataCompat.Builder metadataBuilder;
        if (currentMetadata == null) {
            metadataBuilder = new MediaMetadataCompat.Builder();
        } else {
            // Important: build from existing metadata to preserve title, artwork, etc.
            metadataBuilder = new MediaMetadataCompat.Builder(currentMetadata);
        }
        metadataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs);
        mediaSession.setMetadata(metadataBuilder.build());

        // Position and rate are part of PlaybackState.
        PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder(currentState);
        stateBuilder.setState(currentState.getState(), positionMs, rate);
        mediaSession.setPlaybackState(stateBuilder.build());

        // The notification needs to be rebuilt to show the progress bar,
        // which appears when duration is available in the metadata.
        updateNotification();
    }

    private void updatePlaybackState(String stateStr) {
        PlaybackStateCompat currentState = mediaSession.getController().getPlaybackState();
        if (currentState == null) {
            // Should not happen if initialized in onCreate, but good to be safe.
            currentState = new PlaybackStateCompat.Builder()
                .setActions(0)
                .setState(PlaybackStateCompat.STATE_NONE, 0, 1.0f)
                .build();
        }

        int state;
        switch (stateStr) {
            case "playing":
                state = PlaybackStateCompat.STATE_PLAYING;
                break;
            case "paused":
                state = PlaybackStateCompat.STATE_PAUSED;
                break;
            default: // "none" or "stopped"
                state = PlaybackStateCompat.STATE_STOPPED;
                break;
        }

        PlaybackStateCompat.Builder newStateBuilder = new PlaybackStateCompat.Builder(currentState);
        newStateBuilder.setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f);
        mediaSession.setPlaybackState(newStateBuilder.build());

        if (state == PlaybackStateCompat.STATE_PLAYING || state == PlaybackStateCompat.STATE_PAUSED) {
            // Either playing or paused, show notification and run as foreground
            startForeground(NOTIFICATION_ID, buildNotification());
        } else {
            // Stopped or None, we can stop the foreground service.
            // false = do not remove the notification. It will be removed by swiping.
            stopForeground(false);
            NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID);
            // If the state is stopped, the service might no longer be needed.
            if (state == PlaybackStateCompat.STATE_STOPPED) {
                 stopSelf();
            }
        }
    }

    private void setMediaActionHandlers(String[] actions) {
        PlaybackStateCompat currentState = mediaSession.getController().getPlaybackState();
         if (currentState == null) return;
        
        long supportedActions = 0;
        if (actions != null) {
            for (String action : actions) {
                switch (action) {
                    case "play":
                        supportedActions |= PlaybackStateCompat.ACTION_PLAY;
                        break;
                    case "pause":
                        supportedActions |= PlaybackStateCompat.ACTION_PAUSE;
                        break;
                    case "previoustrack":
                        supportedActions |= PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;
                        break;
                    case "nexttrack":
                        supportedActions |= PlaybackStateCompat.ACTION_SKIP_TO_NEXT;
                        break;
                }
            }
        }
        if ((supportedActions & PlaybackStateCompat.ACTION_PLAY) != 0 && (supportedActions & PlaybackStateCompat.ACTION_PAUSE) != 0) {
            supportedActions |= PlaybackStateCompat.ACTION_PLAY_PAUSE;
        }

        PlaybackStateCompat.Builder newStateBuilder = new PlaybackStateCompat.Builder(currentState);
        newStateBuilder.setActions(supportedActions);
        mediaSession.setPlaybackState(newStateBuilder.build());

        updateNotification(); // Re-build notification with new actions
    }


    //
    // MODIFIED: Fully rewrote the buildNotification method.
    //
    private Notification buildNotification() {
        MediaMetadataCompat metadata = mediaSession.getController().getMetadata();
        PlaybackStateCompat playbackState = mediaSession.getController().getPlaybackState();

        if (playbackState == null || (playbackState.getState() != PlaybackStateCompat.STATE_PLAYING && playbackState.getState() != PlaybackStateCompat.STATE_PAUSED)) {
            // Should not happen if called correctly, but as a safeguard.
            return null;
        }

        boolean isPlaying = playbackState.getState() == PlaybackStateCompat.STATE_PLAYING;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID);
        List<Integer> compactActionIndices = new ArrayList<>();

        // Action: Previous
        if ((playbackState.getActions() & PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS) != 0) {
            builder.addAction(
                R.drawable.ic_skip_previous, "Previous",
                createActionIntent(ACTION_PREVIOUS)
            );
            compactActionIndices.add(compactActionIndices.size());
        }

        // Action: Play/Pause
        if ((playbackState.getActions() & PlaybackStateCompat.ACTION_PLAY_PAUSE) != 0) {
            builder.addAction(new Action(
                isPlaying ? R.drawable.ic_pause : R.drawable.ic_play_arrow,
                isPlaying ? "Pause" : "Play",
                createActionIntent(isPlaying ? ACTION_PAUSE : ACTION_PLAY)
            ));
            compactActionIndices.add(compactActionIndices.size());
        }

        // Action: Next
        if ((playbackState.getActions() & PlaybackStateCompat.ACTION_SKIP_TO_NEXT) != 0) {
            builder.addAction(
                R.drawable.ic_skip_next, "Next",
                createActionIntent(ACTION_NEXT)
            );
            compactActionIndices.add(compactActionIndices.size());
        }

        // Convert List to int[]
        int[] compactIndices = new int[compactActionIndices.size()];
        for (int i = 0; i < compactActionIndices.size(); i++) {
            compactIndices[i] = compactActionIndices.get(i);
        }

        // --- Intent to open MainActivity when notification is tapped ---
        Intent contentIntent = new Intent(this, MainActivity.class);
        PendingIntent contentPendingIntent = PendingIntent.getActivity(this, 0, contentIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // --- Intent for the stop/delete action (when user swipes notification away) ---
        Intent stopIntent = new Intent(this, MediaPlaybackService.class);
        stopIntent.setAction(ACTION_STOP_SERVICE);
        PendingIntent deletePendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);


        // --- Build the notification ---
        builder.setSmallIcon(R.mipmap.ic_launcher) // Mandatory small icon
            .setContentTitle(metadata != null ? metadata.getDescription().getTitle() : "Radio")
            .setContentText(metadata != null ? metadata.getDescription().getSubtitle() : "...")
            .setLargeIcon(metadata != null ? metadata.getDescription().getIconBitmap() : null)
            .setContentIntent(contentPendingIntent)
            .setDeleteIntent(deletePendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setStyle(new MediaStyle()
                .setMediaSession(mediaSession.getSessionToken())
                // This is the key for lock screen controls
                .setShowActionsInCompactView(compactIndices)
            );

        return builder.build();
    }

    private void updateNotification() {
        if (mediaSession.getController().getPlaybackState() == null) {
            return;
        }

        PlaybackStateCompat state = mediaSession.getController().getPlaybackState();
        if (state.getState() == PlaybackStateCompat.STATE_PLAYING || state.getState() == PlaybackStateCompat.STATE_PAUSED) {
            Notification notification = buildNotification();
            if (notification != null) {
                 NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification);
            }
        } else {
            // If stopped or in other state, remove the notification
            NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID);
        }
    }
    
    private PendingIntent createActionIntent(String action) {
        Intent intent = new Intent(this, MediaPlaybackService.class);
        intent.setAction(action);
        // Use a unique request code for each action to prevent them from being overwritten.
        int requestCode = action.hashCode();
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    
    // --- Communication back to WebView ---
    private void sendActionToWebView(String action) {
        Intent intent = new Intent(BROADCAST_MEDIA_ACTION);
        intent.putExtra(EXTRA_MEDIA_ACTION, action);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        mediaSession.release();
        executor.shutdown();
        unregisterReceiver(becomingNoisyReceiver);
    }
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
