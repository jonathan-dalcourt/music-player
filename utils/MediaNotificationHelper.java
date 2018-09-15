/*
 * Copyright (C) 2014 The Android Open Source Project
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
 *
 * Code taken and edited from
 * https://android.googlesource.com/platform/packages/apps/Music/+/master/src/com/android/music/utils/MediaNotificationManager.java#238
 *
 */

package com.dalcourt.jonathan.musicplayer.utils;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.TaskStackBuilder;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.os.Build;
import android.os.RemoteException;
import android.provider.MediaStore;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaButtonReceiver;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import com.dalcourt.jonathan.musicplayer.BackgroundAudioService;
import com.dalcourt.jonathan.musicplayer.MainActivity;
import com.dalcourt.jonathan.musicplayer.MediaPlayerContract;
import com.dalcourt.jonathan.musicplayer.R;

import java.util.ArrayList;

public class MediaNotificationHelper {

    // TODO comment
    private static final String LOG_TAG = MediaNotificationHelper.class.getName();
    private static final int PLAY_PAUSE_POSIITON = 1;
    private static final int NOTIFICATION_ID = 78705;

    // TODO comment
    private ArrayList<Object> songInfo;
    private final BackgroundAudioService service;
    private final Context context;
    private final IntentActionReceiver receiver;
    private final MediaControllerCallback mediaControllerCallback;

    // TODO comment
    private MediaSessionCompat.Token sessionToken;
    private MediaControllerCompat controllerCompat;
    private MediaControllerCompat.TransportControls transportControls;

    // TODO comment
    private PlaybackStateCompat playbackState;
    private MediaMetadataCompat mediaMetadataCompat;
    private NotificationManager notificationManager;

    // TODO comment
    private static final String ACTION_PAUSE = "actionPause";
    private static final String ACTION_PLAY = "actionPlay";
    private static final String ACTION_PREV = "actionPrev";
    private static final String ACTION_NEXT = "actionNext";
    private static final String ACTION_STOP = "actionStop";

    // TODO comment
    private PendingIntent intentPlay;
    private PendingIntent intentPause;
    private PendingIntent intentNext;
    private PendingIntent intentPrev;
    private PendingIntent intentStop;

    // TODO comment
    private int notificationColor;
    private boolean playbackStarted = false;

    // TODO comment
    public MediaNotificationHelper(BackgroundAudioService service, Context context, ArrayList<Object> songInfo) {
        this.service = service;
        this.context = context;
        this.songInfo = songInfo;
        updateSessionToken();

        notificationColor = ResourceHelper.getThemeColor(service, android.R.attr.colorPrimary, Color.DKGRAY);

        notificationManager = context.getSystemService(NotificationManager.class);

        mediaControllerCallback = new MediaControllerCallback();

        receiver = new IntentActionReceiver();

        String pkg = service.getPackageName();

        intentPlay = PendingIntent.getBroadcast(context, MediaPlayerContract.REQUEST_CODE,
                new Intent(ACTION_PLAY).setPackage(pkg), PendingIntent.FLAG_CANCEL_CURRENT);
        intentPause = PendingIntent.getBroadcast(context, MediaPlayerContract.REQUEST_CODE,
                new Intent(ACTION_PAUSE).setPackage(pkg), PendingIntent.FLAG_CANCEL_CURRENT);
        intentNext = PendingIntent.getBroadcast(context, MediaPlayerContract.REQUEST_CODE,
                new Intent(ACTION_NEXT).setPackage(pkg), PendingIntent.FLAG_CANCEL_CURRENT);
        intentPrev = PendingIntent.getBroadcast(context, MediaPlayerContract.REQUEST_CODE,
                new Intent(ACTION_PREV).setPackage(pkg), PendingIntent.FLAG_CANCEL_CURRENT);
        intentStop = PendingIntent.getBroadcast(context, MediaPlayerContract.REQUEST_CODE,
                new Intent(ACTION_STOP).setPackage(pkg), PendingIntent.FLAG_CANCEL_CURRENT);

//        createNotificationChannel();
    }

//    // creates a notification channel to display the playback notification
//    private void createNotificationChannel() {
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//            CharSequence name = "audio";
//            String description = "playback";
//            NotificationChannel channel = new NotificationChannel(MediaPlayerContract.CHANNEL_ID,
//                    name, NotificationManager.IMPORTANCE_LOW);
//            channel.setDescription(description);
//            channel.setShowBadge(false);
//            notificationManager.createNotificationChannel(channel);
//        }
//    }

    // TODO comment
    public void startNotification() {
        Log.e(LOG_TAG, "attempting to start");
//        if (!playbackStarted) {
            mediaMetadataCompat = controllerCompat.getMetadata();
            playbackState = controllerCompat.getPlaybackState();

            Notification notification = createNotification();
            if (notification != null) {
                Log.e(LOG_TAG, "attempting to notification NOT null");
                controllerCompat.registerCallback(mediaControllerCallback);
                IntentFilter filter = new IntentFilter();
                filter.addAction(ACTION_PLAY);
                filter.addAction(ACTION_PAUSE);
                filter.addAction(ACTION_NEXT);
                filter.addAction(ACTION_PREV);
                service.registerReceiver(receiver, filter);
                service.startForeground(NOTIFICATION_ID, notification);
//                notificationManager.notify(NOTIFICATION_ID, notification);
                playbackStarted = true;
            } else {
                Log.e(LOG_TAG, "attempting to notification null");
            }
//        }
    }

    // TODO comment, use elsewhere
    public void stopNotification() {
        if (playbackStarted) {
            playbackStarted = false;
            controllerCompat.unregisterCallback(mediaControllerCallback);
            try {
                notificationManager.cancel(NOTIFICATION_ID);
                service.unregisterReceiver(receiver);
            } catch (IllegalArgumentException e) {
                Log.i(LOG_TAG, "No registered receiver.");
            }
            service.stopForeground(false);
        }
    }

    // returns a Notification object
    private Notification createNotification() {
        if (mediaMetadataCompat == null || playbackState == null) {
            return null;
        }

        MediaDescriptionCompat description = mediaMetadataCompat.getDescription();

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, MediaPlayerContract.CHANNEL_ID);

        builder.addAction(new NotificationCompat.Action(android.R.drawable.ic_media_previous,
                "Previous", intentPrev));
        addPlayPauseAction(builder);
        builder.addAction(new NotificationCompat.Action(android.R.drawable.ic_media_next,
                "Next", intentNext));

        builder.setStyle(new android.support.v4.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(PLAY_PAUSE_POSIITON).setMediaSession(sessionToken))
                .setColor(notificationColor)
                .setSmallIcon(R.drawable.ic_notification)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setShowWhen(false)
                .setContentIntent(getPendingIntent())
                .setDeleteIntent(intentStop)
                .setContentTitle(description.getTitle())
                .setContentText(description.getSubtitle())
                .setSubText(description.getDescription())
                .setLargeIcon(description.getIconBitmap());

        setNotificationPlayBackState(builder);

        return builder.build();
    }

    private android.support.v4.media.app.NotificationCompat.MediaStyle setStyle() {
        android.support.v4.media.app.NotificationCompat.MediaStyle mediaStyle =
                new android.support.v4.media.app.NotificationCompat.MediaStyle();

        mediaStyle.setShowActionsInCompactView(PLAY_PAUSE_POSIITON);
        mediaStyle.setMediaSession(sessionToken);
        mediaStyle.setShowCancelButton(playbackState.getState() != PlaybackStateCompat.STATE_PLAYING);

        return mediaStyle;
    }

    // TODO comment
    private void addPlayPauseAction(NotificationCompat.Builder builder) {
        String label;
        int icon;
        PendingIntent intent;
        if (playbackState.getState() == PlaybackStateCompat.STATE_PLAYING) {
            label = service.getString(R.string.play_pause);
            icon = android.R.drawable.ic_media_pause;
            intent = intentPause;
        } else {
            label = service.getString(R.string.play_pause);
            icon = android.R.drawable.ic_media_play;
            intent = intentPlay;
            service.stopForeground(false);
        }
        builder.addAction(new NotificationCompat.Action(icon, label, intent));
    }

    // creates an intent which opens the application when the notification is pressed
    private PendingIntent getPendingIntent() {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
//        setExtras(intent);

        return PendingIntent.getActivity(context, MediaPlayerContract.REQUEST_CODE,
                intent, PendingIntent.FLAG_CANCEL_CURRENT);
    }

//    // TODO comment
//    private void setExtras(Intent intent) {
//        String[] info = (String[]) songInfo.get(MediaPlayerContract.INFO_ARRAY);
//        int pos = (int) songInfo.get(MediaPlayerContract.QUEUE_POS);
//        int duration = (int) songInfo.get(MediaPlayerContract.DURATION);
//        intent.putExtra(MediaPlayerContract.INTENT_EXTRA_SONG_INFO, info);
//        intent.putExtra(MediaPlayerContract.INTENT_EXTRA_POS, pos);
//        intent.putExtra(MediaPlayerContract.INTENT_EXTRA_DURATION, duration);
//        Log.e(LOG_TAG, "duration: " + duration);
//        intent.putExtra(MediaPlayerContract.INTENT_EXTRA_IS_PLAYING, isPlaying());
//    }

//    // TODO comment
//    public void setSongInfo(ArrayList<Object> info) {
//        songInfo = info;
//    }

//    // TODO comment
//    private boolean isPlaying() {
//        return (playbackState != null &&
//                playbackState.getState() == PlaybackStateCompat.STATE_PLAYING);
//    }

    // TODO comment
    private void setNotificationPlayBackState(NotificationCompat.Builder builder) {
        if (playbackState == null || !playbackStarted) {
            service.stopForeground(false);
        }
//        if (playbackState.getState() == PlaybackStateCompat.STATE_PLAYING) {
//            Log.e(LOG_TAG, "ongoing TRUE");
//            builder.setOngoing(true);
//        } else {
//            Log.e(LOG_TAG, "ongoing false");
//            builder.setOngoing(false);
//        }
    }

    // update state based on change in session token
    // called either when running for first time or when media session owner has destroyed session
    private void updateSessionToken() {
        MediaSessionCompat.Token token = service.getSessionToken();
        if (sessionToken == null || !sessionToken.equals(token)) {
            Log.e(LOG_TAG, "token null: " + (token == null));
            if (controllerCompat != null) {
                controllerCompat.unregisterCallback(mediaControllerCallback);
            }
            sessionToken = token;
            try {
                controllerCompat = new MediaControllerCompat(service, sessionToken);
                transportControls = controllerCompat.getTransportControls();
                if (playbackStarted) {
                    controllerCompat.registerCallback(mediaControllerCallback);
                }
            } catch (RemoteException e) {
                // TODO something
                Log.e(LOG_TAG, "oops", e);
            }

        }
    }

    // TODO comment
    class IntentActionReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action != null) {
                switch (action) {
                    case ACTION_PLAY:
                        transportControls.play();
                        break;
                    case ACTION_PAUSE:
                        transportControls.pause();
                        break;
                    case ACTION_NEXT:
                        transportControls.skipToNext();
                        break;
                    case ACTION_PREV:
                        transportControls.skipToPrevious();
                        break;
                    case ACTION_STOP:
                        transportControls.stop();
                        break;
                    default:
                        Log.d(LOG_TAG, "unknown action: " + action);
                }
            }
        }
    }

    // TODO comment
    class MediaControllerCallback extends MediaControllerCompat.Callback {
        @Override
        public void onPlaybackStateChanged(PlaybackStateCompat state) {
            // TODO update state BEFORE startnotification
            playbackState = state;
            if (state != null && stateInactive(state)) {
                stopNotification();
            } else {
                sendNotification();
            }
        }

        @Override
        public void onMetadataChanged(MediaMetadataCompat metadata) {
            super.onMetadataChanged(metadata);
            mediaMetadataCompat = metadata;
            sendNotification();
        }

        @Override
        public void onSessionDestroyed() {
            super.onSessionDestroyed();
            updateSessionToken();
        }

        // returns true if the media player is inactive
        private boolean stateInactive(PlaybackStateCompat state) {
            return (state.getState() == PlaybackStateCompat.STATE_STOPPED
                    || state.getState() == PlaybackStateCompat.STATE_NONE);
        }

        // updates the currently displayed notification
        private void sendNotification() {
            Notification notification = createNotification();
            if (notification != null) {
                notificationManager.notify(NOTIFICATION_ID, notification);
            }
        }
    }

} class ResourceHelper {
    /**
     * Get a color value from a theme attribute.
     * @param context used for getting the color.
     * @param attribute theme attribute.
     * @param defaultColor default to use.
     * @return color value
     */
    public static int getThemeColor(Context context, int attribute, int defaultColor) {
        int themeColor = 0;
        String packageName = context.getPackageName();
        try {
            Context packageContext = context.createPackageContext(packageName, 0);
            ApplicationInfo applicationInfo =
                    context.getPackageManager().getApplicationInfo(packageName, 0);
            packageContext.setTheme(applicationInfo.theme);
            Resources.Theme theme = packageContext.getTheme();
            TypedArray ta = theme.obtainStyledAttributes(new int[] {attribute});
            themeColor = ta.getColor(0, defaultColor);
            ta.recycle();
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return themeColor;
    }
}
