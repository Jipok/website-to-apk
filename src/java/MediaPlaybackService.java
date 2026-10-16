package com.myexample.webtoapk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.util.Log;
import android.util.Base64;
import android.media.AudioManager;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class MediaPlaybackService extends Service {
    public static final String NOTIFICATION_CHANNEL_ID = "web_app_notifications";
    /* The media notification gets its own channel: it is posted afresh on every playback start, and
       on the web-notification channel (IMPORTANCE_DEFAULT) each of those alerts with a sound */
    public static final String MEDIA_CHANNEL_ID = "web_app_media";
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

    /* The notification's buttons come back through MediaSession and the page has to hear about them.
       That used to be a LocalBroadcastManager broadcast; androidx deprecated the library (no further
       releases) and it is ~12 KB of classes for one delivery inside this process, so the activity —
       the only consumer, and in the same process, which is what LocalBroadcastManager required
       anyway — registers a plain listener instead. */
    public interface MediaActionListener {
        void onMediaAction(String action);
    }

    private static final List<MediaActionListener> mediaActionListeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static void addMediaActionListener(MediaActionListener listener) {
        mediaActionListeners.add(listener);
    }

    public static void removeMediaActionListener(MediaActionListener listener) {
        mediaActionListeners.remove(listener);
    }


    private static final int NOTIFICATION_ID = 101;
    private MediaSession mediaSession;
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

    /* Both channels, in one place. The activity creates them before anything can post — it is the
       only starter of this service, which is no longer sticky — and posting to a channel that does
       not exist is silently dropped, which is what used to justify a second caller here (D42). */
    public static void createNotificationChannels(Context context) {
        NotificationManagerCompat manager = NotificationManagerCompat.from(context);
        NotificationChannel web = new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                "Web App Notifications", NotificationManager.IMPORTANCE_DEFAULT);
        web.setDescription("Channel for web app notifications");
        manager.createNotificationChannel(web);
        NotificationChannel media = new NotificationChannel(MEDIA_CHANNEL_ID,
                "Media playback", NotificationManager.IMPORTANCE_LOW);
        media.setDescription("Playback controls for audio and video");
        manager.createNotificationChannel(media);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // Idempotent, and needed here as well as in MainActivity: a media-button PendingIntent can
        // start this service in a fresh process, where the activity never ran and the channel a
        // notification needs would not exist yet.
        createNotificationChannels(this);

        mediaSession = new MediaSession(this, "WebToApkMediaSession");
        // No setFlags(): FLAG_HANDLES_MEDIA_BUTTONS/TRANSPORT_CONTROLS are deprecated with "no longer
        // used" at API 26 and read by nothing since (D27), so setting them is dead weight.

        // Set initial state. It must be something other than STATE_NONE.
        PlaybackState initialState = new PlaybackState.Builder()
                .setActions(0) // No actions available initially
                .setState(PlaybackState.STATE_NONE, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0)
                .build();
        mediaSession.setPlaybackState(initialState);

        mediaSession.setCallback(new MediaSession.Callback() {
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
        // Not a protected broadcast, so from targetSdk 34 the export flag is mandatory
        ContextCompat.registerReceiver(this, becomingNoisyReceiver, intentFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED);
    }
    

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // A null intent means the system restarted us; START_NOT_STICKY below is what stops that
        if (intent == null || intent.getAction() == null) {
            return START_NOT_STICKY;
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
            case ACTION_NEXT:
                sendActionToWebView("nexttrack");
                break;
            case ACTION_PREVIOUS:
                sendActionToWebView("previoustrack");
                break;
        }

        return START_NOT_STICKY;
    }

    private void updateMetadata(String title, String artist, String album, @Nullable String artworkUrl) {
        MediaMetadata.Builder metadataBuilder = new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, album);

        if (artworkUrl != null && !artworkUrl.isEmpty()) {
            try {
                // The format is "data:image/png;base64,iVBORw0KGgo..."
                // We need to find the comma and decode the part after it.
                String base64String = artworkUrl.substring(artworkUrl.indexOf(',') + 1);
                byte[] decodedBytes = Base64.decode(base64String, Base64.DEFAULT);
                Bitmap artworkBitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);

                metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artworkBitmap);
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
        PlaybackState currentState = mediaSession.getController().getPlaybackState();
        if (currentState == null || currentState.getState() == PlaybackState.STATE_NONE) {
            // Can't set position on a 'none' state. Wait for a play/pause state.
            return;
        }

        long durationMs = (long) (duration * 1000);
        long positionMs = (long) (position * 1000);
        float rate = (float) playbackRate;

        // Duration is part of MediaMetadata. We need to update it.
        MediaMetadata currentMetadata = mediaSession.getController().getMetadata();
        MediaMetadata.Builder metadataBuilder;
        if (currentMetadata == null) {
            metadataBuilder = new MediaMetadata.Builder();
        } else {
            // Important: build from existing metadata to preserve title, artwork, etc.
            metadataBuilder = new MediaMetadata.Builder(currentMetadata);
        }
        metadataBuilder.putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs);
        mediaSession.setMetadata(metadataBuilder.build());

        // Position and rate are part of PlaybackState.
        PlaybackState.Builder stateBuilder = new PlaybackState.Builder(currentState);
        stateBuilder.setState(currentState.getState(), positionMs, rate);
        mediaSession.setPlaybackState(stateBuilder.build());

        // The notification needs to be rebuilt to show the progress bar,
        // which appears when duration is available in the metadata.
        updateNotification();
    }

    private void updatePlaybackState(String stateStr) {
        if (stateStr == null) {
            // The bridge forwards the page's argument untouched, so a call without one arrives as
            // null here — and `switch` on a null String is an NPE in the service.
            Log.w("WebToApk", "Ignoring null media playback state");
            return;
        }
        PlaybackState currentState = mediaSession.getController().getPlaybackState();
        if (currentState == null) {
            // Should not happen if initialized in onCreate, but good to be safe.
            currentState = new PlaybackState.Builder()
                .setActions(0)
                .setState(PlaybackState.STATE_NONE, 0, 1.0f)
                .build();
        }

        int state;
        switch (stateStr) {
            case "playing":
                state = PlaybackState.STATE_PLAYING;
                break;
            case "paused":
                state = PlaybackState.STATE_PAUSED;
                break;
            case "none":
            case "stopped":
                state = PlaybackState.STATE_STOPPED;
                break;
            default:
                // Anything else used to be read as "stopped", which tore the notification down and
                // stopped the service: a page typo ("Paused") killed playback. Unknown stays unknown.
                Log.w("WebToApk", "Ignoring unknown media playback state: " + stateStr);
                return;
        }

        PlaybackState.Builder newStateBuilder = new PlaybackState.Builder(currentState);
        newStateBuilder.setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f);
        mediaSession.setPlaybackState(newStateBuilder.build());

        if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_PAUSED) {
            // Either playing or paused, show notification and run as foreground
            // Android 12+ refuses a foreground start from the background
            // (ForegroundServiceStartNotAllowedException): log it rather than kill the process
            try {
                Notification notification = buildNotification();
                if (notification == null) {
                    // startForeground(id, null) throws, and buildNotification() returns null whenever
                    // the session has no playing/paused state to show. Unreachable today; a guard
                    // because the next refactor will not remember that.
                    Log.w("WebToApk", "No notification to post for state " + state);
                    return;
                }
                startForeground(NOTIFICATION_ID, notification);
            } catch (Exception e) {
                Log.e("WebToApk", "Cannot bring the media service to the foreground", e);
            }
        } else {
            // Stopped or None, we can stop the foreground service.
            // false = do not remove the notification. It will be removed by swiping.
            stopForeground(false);
            NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID);
            // If the state is stopped, the service might no longer be needed.
            if (state == PlaybackState.STATE_STOPPED) {
                 stopSelf();
            }
        }
    }

    private void setMediaActionHandlers(String[] actions) {
        PlaybackState currentState = mediaSession.getController().getPlaybackState();
         if (currentState == null) return;
        
        long supportedActions = 0;
        if (actions != null) {
            for (String action : actions) {
                switch (action) {
                    case "play":
                        supportedActions |= PlaybackState.ACTION_PLAY;
                        break;
                    case "pause":
                        supportedActions |= PlaybackState.ACTION_PAUSE;
                        break;
                    case "previoustrack":
                        supportedActions |= PlaybackState.ACTION_SKIP_TO_PREVIOUS;
                        break;
                    case "nexttrack":
                        supportedActions |= PlaybackState.ACTION_SKIP_TO_NEXT;
                        break;
                }
            }
        }
        if ((supportedActions & PlaybackState.ACTION_PLAY) != 0 && (supportedActions & PlaybackState.ACTION_PAUSE) != 0) {
            supportedActions |= PlaybackState.ACTION_PLAY_PAUSE;
        }

        PlaybackState.Builder newStateBuilder = new PlaybackState.Builder(currentState);
        newStateBuilder.setActions(supportedActions);
        mediaSession.setPlaybackState(newStateBuilder.build());

        updateNotification(); // Re-build notification with new actions
    }


    private Notification buildNotification() {
        MediaMetadata metadata = mediaSession.getController().getMetadata();
        PlaybackState playbackState = mediaSession.getController().getPlaybackState();

        if (playbackState == null || (playbackState.getState() != PlaybackState.STATE_PLAYING && playbackState.getState() != PlaybackState.STATE_PAUSED)) {
            // Should not happen if called correctly, but as a safeguard.
            return null;
        }

        boolean isPlaying = playbackState.getState() == PlaybackState.STATE_PLAYING;

        Notification.Builder builder = new Notification.Builder(this, MEDIA_CHANNEL_ID);
        List<Integer> compactActionIndices = new ArrayList<>();

        // Action: Previous
        if ((playbackState.getActions() & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0) {
            builder.addAction(new Notification.Action(
                R.drawable.ic_skip_previous, "Previous",
                createActionIntent(ACTION_PREVIOUS)
            ));
            compactActionIndices.add(compactActionIndices.size());
        }

        // Action: Play/Pause
        if ((playbackState.getActions() & PlaybackState.ACTION_PLAY_PAUSE) != 0) {
            builder.addAction(new Notification.Action(
                isPlaying ? R.drawable.ic_pause : R.drawable.ic_play_arrow,
                isPlaying ? "Pause" : "Play",
                createActionIntent(isPlaying ? ACTION_PAUSE : ACTION_PLAY)
            ));
            compactActionIndices.add(compactActionIndices.size());
        }

        // Action: Next
        if ((playbackState.getActions() & PlaybackState.ACTION_SKIP_TO_NEXT) != 0) {
            builder.addAction(new Notification.Action(
                R.drawable.ic_skip_next, "Next",
                createActionIntent(ACTION_NEXT)
            ));
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
        builder.setSmallIcon(R.drawable.ic_stat) // mandatory, and monochrome (ic_stat.xml)
            .setContentTitle(metadata != null ? metadata.getString(MediaMetadata.METADATA_KEY_TITLE) : "Radio")
            .setContentText(metadata != null ? metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) : "...")
            .setLargeIcon(metadata != null ? metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) : null)
            .setContentIntent(contentPendingIntent)
            .setDeleteIntent(deletePendingIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setStyle(new Notification.MediaStyle()
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

        PlaybackState state = mediaSession.getController().getPlaybackState();
        if (state.getState() == PlaybackState.STATE_PLAYING || state.getState() == PlaybackState.STATE_PAUSED) {
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
        // LocalBroadcastManager always delivered on the main thread, and the listener ends in
        // WebView.evaluateJavascript, which has to run there. A MediaSession callback is not
        // promised to be on the main thread, so hop — one post per button press.
        mainHandler.post(() -> {
            for (MediaActionListener listener : mediaActionListeners) {
                listener.onMediaAction(action);
            }
        });
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        mediaSession.release();
        unregisterReceiver(becomingNoisyReceiver);
    }
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
