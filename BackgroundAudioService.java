package com.dalcourt.jonathan.musicplayer;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaBrowserServiceCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaButtonReceiver;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;
import android.util.Log;

import com.dalcourt.jonathan.musicplayer.utils.MediaNotificationHelper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

public class BackgroundAudioService extends MediaBrowserServiceCompat {

    private static final String LOG_TAG = BackgroundAudioService.class.getName();
    private static final String MEDIA_SESSION_TAG = "Media Session Compat";

    private static final float VOLUME_FULL = 1.0f;
    private static final float VOLUME_DUCK = 0.3f;

    private static final int PREV = -1;
    private static final int SAME = 0;
    private static final int NEXT = 1;


    private LocalBroadcastManager localBroadcastManager;

    private static ArrayList<Song> songLibrary; // list of all discovered songs; once finished, never updated
    private static ArrayList<Song> songQueue; // list of songs currently in queue; can be updated
    private static ArrayList<Object> songInfo;

    private static HashMap<String, Song> songsMap;


    private static int currentQueuePos; // current position in queue
    private static int playerDuration;
    private static boolean loop; // true if the user wants to repeat the current song
    private static boolean shuffle;


    private AudioFocusRequest requestAudioFocusGain;

    private MediaPlayer player;
    private MediaSessionCompat mediaSessionCompat;
    private MediaSessionCallback mediaSessionCallback;
    private HeadphoneReceiver headphoneReceiver;
    private NotificationIntentReceiver notificationReceiver;

    private MediaNotificationHelper notificationHelper;

    @Override
    public void onCreate() {
        super.onCreate();
        ActivityToServiceReceiver receiver = new ActivityToServiceReceiver();
        localBroadcastManager = LocalBroadcastManager.getInstance(getApplicationContext());
        localBroadcastManager.registerReceiver(receiver,
                new IntentFilter(MediaPlayerContract.INTENT_ACTIVITY_TO_SERVICE));
        songInfo = new ArrayList<>();
        updateSeekBar();

        createAudioFocusRequest();
        initMediaSession();
        initHeadphoneReceiver();

        notificationHelper = new MediaNotificationHelper(this, getApplicationContext(), songInfo);
    }

    @Override
    // TODO comment
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() != null) {
            MediaButtonReceiver.handleIntent(mediaSessionCompat, intent);
        } else {
            Song song = songsMap.get(intent.getStringExtra(MediaPlayerContract.INTENT_EXTRA_SONG).toLowerCase());
            currentQueuePos = intent.getIntExtra(MediaPlayerContract.INTENT_EXTRA_POS, 0);
            mediaSessionCallback.playAudio(song);
        }
        return super.onStartCommand(intent, flags, startId);
    }


    // updates the seekbar every second
    private void updateSeekBar() {
        final Handler handler = new Handler(Looper.getMainLooper());

        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                if (player != null && player.isPlaying()) {
                    sendPlayerPos();
                }
                handler.postDelayed(this, MediaPlayerContract.MS_TO_SEC);
            }
        };
        runnable.run();
    }

    // creates an audio focus request with an audio focus change listener
    private void createAudioFocusRequest() {
        // TODO set attributes?
        AudioFocusRequest.Builder builder  = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN);
        builder.setOnAudioFocusChangeListener(new AudioFocusChangeListener());
        requestAudioFocusGain = builder.build();
    }

    // initializes the Media Session
    // associates the callback & intent flags
    private void initMediaSession() {
        Context context = getApplicationContext();
        ComponentName mediaButtonReceiver = new ComponentName(context, MediaButtonReceiver.class);
        mediaSessionCompat = new MediaSessionCompat(getApplicationContext(), MEDIA_SESSION_TAG,
                mediaButtonReceiver, null);
        mediaSessionCallback = new MediaSessionCallback();

        mediaSessionCompat.setCallback(mediaSessionCallback);
        mediaSessionCompat.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

        Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        mediaButtonIntent.setClass(this, MediaButtonReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, mediaButtonIntent, 0);
        mediaSessionCompat.setMediaButtonReceiver(pendingIntent);
        setSessionToken(mediaSessionCompat.getSessionToken());
        Log.e(LOG_TAG, "token set. token null: " + (getSessionToken() == null));
        setSessionActivity(context);
    }

    // TODO comment
    private void setSessionActivity(Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, MediaPlayerContract.REQUEST_CODE,
                intent, PendingIntent.FLAG_UPDATE_CURRENT);
        mediaSessionCompat.setSessionActivity(pendingIntent);
    }

    // TODO comment
    private void initHeadphoneReceiver() {
        headphoneReceiver = new HeadphoneReceiver();
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        registerReceiver(headphoneReceiver, filter);
    }

    // TODO comment
    private void initNotificationReceiver() {

    }

    // sets the song library to the passed list
    public static void setSongLibrary(ArrayList<Song> songLibrary) {
        BackgroundAudioService.songLibrary = songLibrary;
        BackgroundAudioService.createSongsMap();
    }

    // sets the song queue to be the passed list
    public static void setSongQueue(ArrayList<Song> songQueue) {
        BackgroundAudioService.songQueue = songQueue;
//        MediaSessionCallback.playAudio()
    }

    // creatse a HashMap of songs
    // k = song title, v = song
    private static void createSongsMap() {
        songsMap = new HashMap<>();
        for (Song song : songLibrary) {
            songsMap.put(song.getTitle().toLowerCase(), song);
        }
    }

    @Override
    public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result, @NonNull Bundle options) {
        super.onLoadChildren(parentId, result, options);
    }

    @Override
    public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        result.sendResult(null);
        // TODO something
    }

    @Nullable
    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
        if (TextUtils.equals(clientPackageName, getPackageName())) {
            return new BrowserRoot(getString(R.string.app_name), null);
        }
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioManager.abandonAudioFocusRequest(requestAudioFocusGain);
        unregisterReceiver(headphoneReceiver);
        mediaSessionCompat.release();
        stopSelf();
    }

    // TODO comment
    private void setMediaPlaybackState(int state) {
        PlaybackStateCompat.Builder playbackstateBuilder = new PlaybackStateCompat.Builder();
        if (state == PlaybackStateCompat.STATE_PLAYING) {
            playbackstateBuilder.setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_PAUSE
                    | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS);
        } else {
            playbackstateBuilder.setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_PAUSE
                    | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS);
        }
        playbackstateBuilder.setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 0);
        mediaSessionCompat.setPlaybackState(playbackstateBuilder.build());
    }

    // TODO comment
    class AudioFocusChangeListener implements AudioManager.OnAudioFocusChangeListener {

        @Override
        // handles changes in audio focus
        public void onAudioFocusChange(int focusChange) {
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_LOSS:
                    setMediaPlaybackState(PlaybackStateCompat.STATE_PAUSED);
                    Log.e(LOG_TAG, "loss");
                    if (player != null && player.isPlaying()) {
                        player.pause();
                    }
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    setMediaPlaybackState(PlaybackStateCompat.STATE_PAUSED);
                    Log.e(LOG_TAG, "loss transient");
                    player.pause();
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    setMediaPlaybackState(PlaybackStateCompat.STATE_PLAYING);
                    Log.e(LOG_TAG, "loss duck");
                    if( player != null ) {
                        player.setVolume(VOLUME_DUCK, VOLUME_DUCK);
                    }
                    break;
                case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT:
                case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE:
                case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK:
                case AudioManager.AUDIOFOCUS_GAIN: {
                    setMediaPlaybackState(PlaybackStateCompat.STATE_PLAYING);
                    Log.e(LOG_TAG, "gain");
                    if( player != null ) {
                        if( !player.isPlaying() ) {
                            player.start();
                        }
                        player.setVolume(VOLUME_FULL, VOLUME_FULL);
                    }
                    break;
                }
                default:
                    Log.e(LOG_TAG, "other focus change: " + focusChange);
            }
        }
    }

    // MediaSession Callback class for handling various playback events
    class MediaSessionCallback extends MediaSessionCompat.Callback {
        @Override
        public void onPlay() {
            super.onPlay();
            play();
        }

        @Override
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
            super.onPlayFromMediaId(mediaId, extras);
        }

        @Override
        public void onPlayFromSearch(String query, Bundle extras) {
            super.onPlayFromSearch(query, extras);
            playFromSearch(query.toLowerCase());
        }

        @Override
        public void onPause() {
            super.onPause();
            pause();
        }

        @Override
        public void onStop() {
            // TODO reset buttons//now playing//notification//seekbar
            super.onStop();
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                audioManager.abandonAudioFocusRequest(requestAudioFocusGain);
            }
            if (player != null) {
                player.stop();
            }
            if (notificationHelper != null) {
                notificationHelper.stopNotification();
            }
            unregisterReceiver(headphoneReceiver);
            mediaSessionCompat.release();
            stopSelf();

        }

        @Override
        public void onSkipToNext() {
            super.onSkipToNext();
            skipToNext();
        }

        @Override
        public void onSkipToPrevious() {
            super.onSkipToPrevious();
            skipToPrevious();
        }

        @Override
        public void onSeekTo(long pos) {
            super.onSeekTo(pos);
            seekTo((int) pos);
        }

        @Override
        public void onSetShuffleMode(int shuffleMode) {
            super.onSetShuffleMode(shuffleMode);
            if (currentQueuePos > -1) {
                Song currentSong = songQueue.get(currentQueuePos % songQueue.size());
                switch (shuffleMode) {
                    case PlaybackStateCompat.SHUFFLE_MODE_ALL:
                        shuffle = true;
                        Collections.shuffle(songQueue);
                        break;
                    case PlaybackStateCompat.SHUFFLE_MODE_NONE:
                        shuffle = false;
                        songQueue.clear();
                        songQueue.addAll(songLibrary);
                        break;
                }
                // TODO wtf
                currentQueuePos = songQueue.indexOf(currentSong);
            } else {
                currentQueuePos++;
            }
            sendQueuePos();
        }

        @Override
        public void onSetRepeatMode(int repeatMode) {
            super.onSetRepeatMode(repeatMode);
            switch (repeatMode) {
                case PlaybackStateCompat.REPEAT_MODE_ONE:
                    loop = true;
                    break;
                case PlaybackStateCompat.REPEAT_MODE_NONE:
                    loop = false;
                    break;
            }
        }

        // TODO add onStop

        // returns true if audio focus request was granted, false otherwise
        private boolean retrievedAudioFocus() {
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

            return audioManager != null && audioManager
                    .requestAudioFocus(requestAudioFocusGain) == AudioManager.AUDIOFOCUS_GAIN;
        }

        // begins playback with a new MediaPlayer
        private void playAudio(final Song song) {
            String path = song.getPath();
            if (player == null) {
                initMediaPlayer();
                try {
                    player.setDataSource(path);
                } catch (IOException e) {
                    Log.e(LOG_TAG, "error playing song", e);
                    Log.e(LOG_TAG, "path: " + path);
                    setMediaPlaybackState(PlaybackStateCompat.STATE_STOPPED);
                    return;
                } catch (NullPointerException e) {
                    Log.e(LOG_TAG, "path: " + path);
                    return;
                }
                player.prepareAsync();
                player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                    @Override
                    public void onPrepared(MediaPlayer mp) {
                        player.start();
                        playerDuration = player.getDuration();
                        initMediaMetadata();
//                        updateSongInfo();
//                        sendSongInfo(song, playerDuration);
//                        if (!playbackActive()) {
                            setMediaPlaybackState(PlaybackStateCompat.STATE_PLAYING);
                            notificationHelper.startNotification();
//                        }
//                        mediaSessionCompat.getController().getTransportControls().stop();
                    }
                });
                player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                    @Override
                    public void onCompletion(MediaPlayer mp) {
                        Song nextSong = loop ? getSong(SAME) : getSong(NEXT);
                        playAudio(nextSong);
                        if (!loop) {
                            currentQueuePos++;
                        }
                    }
                });
            } else {
                // if a song is currently playing, stop playback and retry
                stop();
                playAudio(song);
            }
        }

        // begins playback
        private void play() {
            if (retrievedAudioFocus()) {
                mediaSessionCompat.setActive(true);
                setMediaPlaybackState(PlaybackStateCompat.STATE_PLAYING);
                if (player == null) {
                    Song nextSong = songQueue.get(0);
                    playAudio(nextSong);
                    notificationHelper.startNotification();
//                    updateSongInfo();
//                    sendSongInfo(nextSong, player.getDuration());
                } else {
//                    setMediaPlaybackState(PlaybackStateCompat.STATE_PLAYING);
                    player.start();
                }
//                notificationHelper.startNotification();
            }
        }

        // plays the given song
        private void playFromSearch(String query) {
            // TODO make this work
            if (songsMap.containsKey(query)) {
//                setMediaPlaybackState(PlaybackStateCompat.STATE_PLAYING);
                playAudio(songsMap.get(query));
            }
        }

        // pauses playback
        private void pause() {
            if (player != null && player.isPlaying()) {
                player.pause();
                setMediaPlaybackState(PlaybackStateCompat.STATE_PAUSED);
            }
        }

        // stops playback and releases MediaPlayer
        private void stop() {
            if (player != null) {
                player.stop();
                player.release();
                player = null;
            }
        }

        // restarts playback or plays the previous song
        private void skipToPrevious() {
            final int REWIND_DELAY_MS = 300;
            if (player != null) {
//                setMediaPlaybackState(PlaybackStateCompat.STATE_PLAYING);
                if (player.getCurrentPosition() < REWIND_DELAY_MS) {
                    Song prevSong = getSong(PREV);
                    playAudio(prevSong);
                    currentQueuePos--;
                } else {
                    player.seekTo(0);
                    player.start();
                }
            }
        }

        // skips the current song
        private void skipToNext() {
            if (player != null) {
//                setMediaPlaybackState(PlaybackStateCompat.STATE_PLAYING);
                player.seekTo(player.getDuration());
                // ensures playback will complete and the next song will begin
                if (!player.isPlaying()) {
                    player.start();
                }
            }
        }

        // sets the Media Player's current position the given position
        private void seekTo(int pos) {
            if (player != null) {
                player.seekTo(pos * MediaPlayerContract.MS_TO_SEC);
                sendPlayerPos();
            }
        }
    }

    // BroadcastReceiver class for handling pause/play headphone events
    class HeadphoneReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (player != null && player.isPlaying()) {
                player.pause();
                setMediaPlaybackState(PlaybackStateCompat.STATE_PAUSED);
            }
        }
    }

    // TODO comment
    class ActivityToServiceReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String type = intent.getStringExtra(MediaPlayerContract.INTENT_TYPE);
            switch (type) {
                case (MediaPlayerContract.INTENT_TYPE_UI_INFO):
                    sendUIInfo();
                    MainActivity.setQueue(songQueue);
                    break;
                case (MediaPlayerContract.INTENT_TYPE_POS):
                    currentQueuePos = intent.getIntExtra(MediaPlayerContract.INTENT_EXTRA_POS, 0);
            }
        }

        // TODO comment
        private void sendUIInfo() {
            Intent intent = new Intent(MediaPlayerContract.INTENT_SERVICE_TO_ACTIVITY);
            intent.putExtra(MediaPlayerContract.INTENT_TYPE, MediaPlayerContract.INTENT_TYPE_UI_INFO);
            intent.putExtra(MediaPlayerContract.INTENT_EXTRA_IS_PLAYING, (player != null && player.isPlaying()));
            intent.putExtra(MediaPlayerContract.INTENT_EXTRA_LOOP, loop);
            intent.putExtra(MediaPlayerContract.INTENT_EXTRA_SHUFFLE, shuffle);
            intent.putExtra(MediaPlayerContract.INTENT_EXTRA_POS, currentQueuePos);
            localBroadcastManager.sendBroadcast(intent);
        }
    }

    // TODO comment
    class NotificationIntentReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Song song = songQueue.get(currentQueuePos);
//            sendSongInfo(song, playerDuration);
        }
    }

    // TODO comment
    private boolean playbackActive() {
        PlaybackStateCompat state = mediaSessionCompat.getController().getPlaybackState();
        return (state != null && state.getState()
                == PlaybackStateCompat.STATE_PLAYING);
    }

    // initializes the MediaPlayer
    private void initMediaPlayer() {
        player = new MediaPlayer();
        player.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        player.setAudioAttributes(audioAttributes());
        player.setVolume(VOLUME_FULL, VOLUME_FULL);
    }

    // returns an AudioAttributes object for streaming music
    private AudioAttributes audioAttributes() {
        AudioAttributes.Builder builder = new AudioAttributes.Builder();
        builder.setContentType(AudioAttributes.CONTENT_TYPE_MUSIC);
        builder.setUsage(AudioAttributes.USAGE_MEDIA);
        return builder.build();
    }

    // gets the metadata of the current song for the notification
    private void initMediaMetadata() {
        Song currentSong = songQueue.get(currentQueuePos % songQueue.size());
        Bitmap albumCover = getBitmapAlbumCover(currentSong);

        MediaMetadataCompat.Builder metadataBuilder = new MediaMetadataCompat.Builder();

        //Notification icon in card
        metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher));
        metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumCover);

        //lock screen icon for pre lollipop
        metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, currentSong.getTitle());
        metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, currentSong.getArtist());
        metadataBuilder.putString(MediaPlayerContract.METADATA_KEY_ALBUM, currentSong.getAlbum());
        metadataBuilder.putString(MediaPlayerContract.METADATA_KEY_LENGTH, currentSong.getLenStr());
        metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, albumCover);
        metadataBuilder.putLong(MediaPlayerContract.METADATA_KEY_DURATION, (long) playerDuration);
        metadataBuilder.putLong(MediaPlayerContract.METADATA_KEY_POS, currentQueuePos);
//        getButtonStates(metadataBuilder);


        mediaSessionCompat.setMetadata(metadataBuilder.build());
    }

    // TODO comment
    private void getButtonStates(MediaMetadataCompat.Builder builder) {
        long longShuffle = shuffle ? MediaPlayerContract.TRUE : MediaPlayerContract.FALSE;
        long longLoop = loop ? MediaPlayerContract.TRUE : MediaPlayerContract.FALSE;
        long longPlaying = playbackActive() ? MediaPlayerContract.TRUE : MediaPlayerContract.FALSE;

        builder.putLong(MediaPlayerContract.METADATA_KEY_SHUFFLE, longShuffle);
        builder.putLong(MediaPlayerContract.METADATA_KEY_LOOP, longLoop);
        builder.putLong(MediaPlayerContract.METADATA_KEY_PLAYING, longPlaying);
    }

    // converts the Drawable album cover associated with a song to a Bitmap
    private Bitmap getBitmapAlbumCover(Song song) {
        Drawable albumCover = song.getAlbumCover().getConstantState().newDrawable().mutate();
        int width = albumCover.getIntrinsicWidth();
        int height = albumCover.getIntrinsicHeight();
        Bitmap mutableBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(mutableBitmap);
        albumCover.setBounds(0, 0, width, height);
        albumCover.draw(canvas);
        return mutableBitmap;
    }

//    // TODO comment
//    private void updateSongInfo() {
//        Song song = songQueue.get(currentQueuePos % songQueue.size());
//        songInfo.clear();
//        songInfo.add(packageSongInfo(song));
//        songInfo.add(currentQueuePos);
//        songInfo.add(playerDuration);
//        notificationHelper.setSongInfo(songInfo);
//        Log.e(LOG_TAG, "duration: " + playerDuration);
//    }

    // returns the next or previous song in the queue
    // parameter 'factor' will either be -1 or 1, indicating previous or next
    private Song getSong(int factor) {
        // if at beginning of queue & looking for previous song, loop back to end
        if (factor == PREV && currentQueuePos <= 0) {
            currentQueuePos = songQueue.size();
        }

        int nextPos = (currentQueuePos + factor) % songQueue.size();
        return songQueue.get(nextPos);
    }

    // sends the current song info to the main activity to update the UI.
    private void sendSongInfo(Song song, int duration) {
        Intent intent = new Intent(MediaPlayerContract.INTENT_SERVICE_TO_ACTIVITY);
        intent.putExtra(MediaPlayerContract.INTENT_TYPE, MediaPlayerContract.INTENT_TYPE_SONG_INFO);
        intent.putExtra(MediaPlayerContract.INTENT_EXTRA_SONG_INFO, packageSongInfo(song));
        intent.putExtra(MediaPlayerContract.INTENT_EXTRA_POS, currentQueuePos);
        intent.putExtra(MediaPlayerContract.INTENT_EXTRA_DURATION, duration);
        localBroadcastManager.sendBroadcast(intent);
    }

    // TODO comment
    private void sendQueuePos() {
        Intent intent = new Intent((MediaPlayerContract.INTENT_SERVICE_TO_ACTIVITY));
        intent.putExtra(MediaPlayerContract.INTENT_TYPE, MediaPlayerContract.INTENT_TYPE_POS);
        intent.putExtra(MediaPlayerContract.INTENT_EXTRA_POS, currentQueuePos);
        localBroadcastManager.sendBroadcast(intent);
    }

    // sends the MediaPlayer's current position to the main activity
    private void sendPlayerPos() {
        Intent intent = new Intent(MediaPlayerContract.INTENT_SERVICE_TO_ACTIVITY);
        intent.putExtra(MediaPlayerContract.INTENT_TYPE, MediaPlayerContract.INTENT_TYPE_PLAYER_POS);
        intent.putExtra(MediaPlayerContract.INTENT_EXTRA_PLAYER_POS, player.getCurrentPosition());
        localBroadcastManager.sendBroadcast(intent);
    }

    // puts all the necessary song info into a String[]
    private String[] packageSongInfo(Song song) {
        String[] info = new String[MediaPlayerContract.NUM_FIELDS];
        info[MediaPlayerContract.TITLE] = song.getTitle();
        info[MediaPlayerContract.ARTIST] = song.getArtist();
        info[MediaPlayerContract.ALBUM] = song.getAlbum();
        info[MediaPlayerContract.LENGTH] = song.getLenStr();
        return info;
    }
}
