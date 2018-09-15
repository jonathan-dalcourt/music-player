package com.dalcourt.jonathan.musicplayer;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.RemoteException;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String SEARCH_TYPE_SONG = "vnd.android.cursor.item/*";

    private static final int PERMISSION_READ_STORAGE = 0;

    private static final int ON_PAUSE = 0;
    private static final int ON_PLAY = 1;

    private static final int STATE_ERROR = -1;
    private static final int STATE_PAUSED = 0;
    private static final int STATE_PLAYING = 1;
    private static final int STATE_NONE = 3;
    private static final int STATE_STOPPED = 2;

    private int currentState;

    private MediaBrowserCompat mediaBrowserCompat;
    private MediaControllerCompat mediaControllerCompat;
    private MediaConnectionCallback mediaConnectionCallback;
    //private MediaControllerCallback mediaControllerCallback;

    //    private LocalBroadcastManager localBroadcastManager;
    private ServiceToActivityReceiver receiver;

    private static final String LOG_TAG = MainActivity.class.getName();

    private static int currentQueuePos; // current position in queue
    private static boolean shuffle; // true if the queue is currently shuffled
    private static boolean showQueue; // true if the queue is currently displayed
    private static boolean loop; // true if the user wants to repeat the current song

    private static ArrayList<Song> songLibrary; // list of all discovered songs; once finished, never updated
    private static ArrayList<Song> songQueue; // list of songs currently in queue; can be updated
    private static ArrayList<Song> playbackSource; // if showQueue, is equal to songQueue; else, songLibrary
    private static LibraryAdapter libraryAdapter;
    private static QueueAdapter queueAdapter;
    private SeekBar seekBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.e(LOG_TAG, "flag on create");
        setContentView(R.layout.activity_main);
        checkPermission();
    }

    // sends the given array of song information to update the now playing and total time views
    private void unpackageSongInfo(String[] info) {
        String title = info[MediaPlayerContract.TITLE];
        String artist = info[MediaPlayerContract.ARTIST];
        String album = info[MediaPlayerContract.ALBUM];
        String lenStr = info[MediaPlayerContract.LENGTH];

        updateSongInfoViews(title, artist, album, lenStr);
    }

    // requests permission to read storage
    private void checkPermission() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, PERMISSION_READ_STORAGE);
        } else {
            prepareApp();
        }
    }

    // if the user denies storage access, let them know the app requires it and ask again.
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch (requestCode) {
            case PERMISSION_READ_STORAGE:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    prepareApp();
                } else {
                    AlertDialog.Builder builder = new AlertDialog.Builder(this);

                    builder.setTitle(R.string.permission_storage_title);
                    builder.setMessage(R.string.permission_storage_message);
                    builder.setPositiveButton(R.string.allow, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            checkPermission();
                        }
                    });
                    builder.setNegativeButton(R.string.deny, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Toast.makeText(MainActivity.this, "Permission denied.", Toast.LENGTH_SHORT).show();
                        }
                    });
                    builder.create().show();
                }
                break;
        }
    }

    // discovers user library and initializes UI & global variables
    private void prepareApp() {
        receiver = new ServiceToActivityReceiver();
        LocalBroadcastManager.getInstance(this).registerReceiver(receiver,
                new IntentFilter(MediaPlayerContract.INTENT_SERVICE_TO_ACTIVITY));
        songLibrary = new ArrayList<>();

        discoverSongs();
        initMediaCompat();

        ProgressBar progress = findViewById(R.id.indeterminateBar);
        progress.setVisibility(View.GONE);
        // if the user doesn't have any songs on their phone, nothing we can do
        if (songLibrary.size() > 0) {
            Collections.sort(songLibrary, new SongComparator());
            playbackSource = new ArrayList<>(songLibrary);
            songQueue = new ArrayList<>(songLibrary);
            BackgroundAudioService.setSongLibrary(songLibrary);
            BackgroundAudioService.setSongQueue(songQueue);
            initUI();
        } else {
            Toast.makeText(this, "No songs found on device.", Toast.LENGTH_LONG).show();
        }

        handleIntents();
    }

    // handle any intent that might have started this activity
    private void handleIntents() {
        Intent intent = this.getIntent();
        String intentAction = intent.getAction();
        if (intentAction != null) {
            if (intentAction.compareTo(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) == 0) {
                String mediaFocus = intent.getStringExtra(MediaStore.EXTRA_MEDIA_FOCUS);
                String title = intent.getStringExtra(MediaStore.EXTRA_MEDIA_TITLE);
                if (mediaFocus != null && mediaFocus.compareTo(SEARCH_TYPE_SONG) == 0) {
                    MediaControllerCompat.getMediaController(this).getTransportControls()
                            .playFromSearch(title.toLowerCase(), new Bundle());
                }
            }
        }
    }

    // initializes the media connection and media controller callbacks and the media browser
    private void initMediaCompat() {
        mediaConnectionCallback = new MediaConnectionCallback();
        mediaBrowserCompat = new MediaBrowserCompat(this, new ComponentName(this,
                BackgroundAudioService.class), mediaConnectionCallback, getIntent().getExtras());
        mediaBrowserCompat.connect();
    }

    // discovers all music files on user's device
    private void discoverSongs() {
        ContentResolver contentResolver = getContentResolver();
        Uri songUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
        Cursor cursor = contentResolver.query(songUri, null, selection, null, null);

        if (cursor != null) {
            if (cursor.moveToFirst()) {
                do {
                    String title = trimExtension(cursor.getString(cursor.
                            getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)));
                    String path = cursor.getString(cursor
                            .getColumnIndex(MediaStore.Audio.Media.DATA));
                    String album = cursor.getString(cursor
                            .getColumnIndex(MediaStore.Audio.Media.ALBUM));
                    String artist = cursor.getString(cursor
                            .getColumnIndex(MediaStore.Audio.Media.ARTIST));
                    int length = Integer.parseInt(cursor.getString(cursor
                            .getColumnIndex(MediaStore.Audio.Media.DURATION)));
                    Song song = new Song(title, artist, album, convertDuration(length), path, 0, getAlbumCover(path));
                    songLibrary.add(song);
                } while (cursor.moveToNext());
            }
            cursor.close();
        }
    }

    // gets the album cover associated with the song path
    private Drawable getAlbumCover(String path) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        mmr.setDataSource(path);

        byte[] bytes = mmr.getEmbeddedPicture();

        Drawable albumCover = null;
        if (bytes != null) {
            albumCover = new BitmapDrawable(getResources(), BitmapFactory.decodeByteArray(bytes, 0, bytes.length));
        }
        return albumCover;
    }

    // trims the extension from the file name
    private String trimExtension(String fileName) {
        return fileName.substring(0, (fileName.length() - 4));
    }

    // converts the data received my the MMR to a more human-legible format
    private String convertDuration(int len) {
        len /= MediaPlayerContract.MS_TO_SEC;

        int numMins = len / MediaPlayerContract.SEC_TO_MIN;
        int numSecs = len % MediaPlayerContract.SEC_TO_MIN;

        // add a leading zero if remainder seconds < 10
        if (numSecs < 10) {
            return numMins + ":0" + numSecs;
        } else {
            return numMins + ":" + numSecs;
        }
    }

    // initializes all necessary views
    private void initUI() {
        initListView();
        initButtons();
        initInfoViews();
    }

    // initializes the queue, shuffle, restart, pause, play, and skip buttons
    private void initButtons() {
        final CheckBox shuffleCheckBox = findViewById(R.id.shuffle_checkbox);
        final CheckBox queueCheckBox = findViewById(R.id.queue_checkbox);
        final CheckBox loopCheckBox = findViewById(R.id.loop_checkbox);
        final Button playButton = findViewById(R.id.start);
        Button restartButton = findViewById(R.id.restart);
        Button pauseButton = findViewById(R.id.pause);
        Button skipButton = findViewById(R.id.skip);

        // begins or resumes playback
        playButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.e(LOG_TAG, "PLAYPLAYPLAYPLAY");
                if (currentState == STATE_PAUSED) {
                    MediaControllerCompat.getMediaController(MainActivity.this).getTransportControls().play();
                    currentState = STATE_PLAYING;
                    resetPausePlay(ON_PLAY);
                }
            }
        });

        // pauses playback
        pauseButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.e(LOG_TAG, "PAAAAAAAUUUUUUUUUUUSEEEEEEE");
                if (currentState == STATE_PLAYING) {
                    MediaControllerCompat.getMediaController(MainActivity.this).getTransportControls().pause();
                    currentState = STATE_PAUSED;
                    resetPausePlay(ON_PAUSE);
                }
            }
        });

        // begins playing the next song
        skipButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MediaControllerCompat.getMediaController(MainActivity.this).getTransportControls().skipToNext();
                currentState = STATE_PLAYING;
                resetPausePlay(ON_PLAY);
            }
        });

        // restarts the current song at the beginning
        restartButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MediaControllerCompat.getMediaController(MainActivity.this).getTransportControls().skipToPrevious();
                currentState = STATE_PLAYING;
                resetPausePlay(ON_PLAY);
            }
        });

        // updates the boolean shuffled and changes the song queue as necessary
        shuffleCheckBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shuffle = shuffleCheckBox.isChecked();
                if (shuffle) { // shuffle the queue
                    shuffleCheckBox.setBackground(getDrawable(R.drawable.shuffle_orange));
                    MediaControllerCompat.getMediaController(MainActivity.this).getTransportControls()
                            .setShuffleMode(PlaybackStateCompat.SHUFFLE_MODE_ALL);
                } else { // reset the queue to its original state
                    shuffleCheckBox.setBackground(getDrawable(R.drawable.shuffle_black));
                    MediaControllerCompat.getMediaController(MainActivity.this).getTransportControls()
                            .setShuffleMode(PlaybackStateCompat.SHUFFLE_MODE_NONE);
                }
                queueAdapter.notifyDataSetChanged();
            }
        });

        // displays the queue if checked and updates currently playing song
        queueCheckBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showQueue = queueCheckBox.isChecked();
                if (showQueue) {
                    animateListViews(true);
                    queueCheckBox.setBackground(getDrawable(R.drawable.queue_orange));
                } else {
                    animateListViews(false);
                    queueCheckBox.setBackground(getDrawable(R.drawable.queue_black));
                }
            }
        });

        loopCheckBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loop = loopCheckBox.isChecked();
                if (loop) {
                    loopCheckBox.setBackground(getDrawable(R.drawable.repeat_orange));
                    MediaControllerCompat.getMediaController(MainActivity.this).getTransportControls()
                            .setRepeatMode(PlaybackStateCompat.REPEAT_MODE_ONE);
                } else {
                    loopCheckBox.setBackground(getDrawable(R.drawable.repeat_black));
                    MediaControllerCompat.getMediaController(MainActivity.this).getTransportControls()
                            .setRepeatMode(PlaybackStateCompat.REPEAT_MODE_NONE);
                }
            }
        });
    }

    // initializes the seekbar and the now playing view group
    private void initInfoViews() {
        RelativeLayout nowPlayingGroup = findViewById(R.id.now_playing_group);
        seekBar = findViewById(R.id.seekbar);

        // updates the player to the location on the seekbar
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    MediaControllerCompat.getMediaController(MainActivity.this).getTransportControls()
                            .seekTo(progress);
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // scrolls to the currently playing song in the appropriate ListView
        nowPlayingGroup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scrollToSong();
            }
        });
    }

    // scrolls to the current position in the appropriate ListView
    private void scrollToSong() {
        if (playerActive()) {
            ListView songsListView = findViewById(R.id.library_listview);
            ListView queueListView = findViewById(R.id.queue_listview);
            int scrollToPos = currentQueuePos % songQueue.size();
//            Log.e(LOG_TAG, "current pos: " + currentQueuePos + " scrollPos: " + scrollToPos);
            if (!showQueue) {
                Song currentSong = songQueue.get(scrollToPos);
                scrollToPos = songLibrary.indexOf(currentSong);
                songsListView.smoothScrollToPositionFromTop(scrollToPos, 0);
            } else {
                queueListView.smoothScrollToPositionFromTop(scrollToPos, 0);
            }
        } else {
            Toast.makeText(MainActivity.this, "No song is playing.", Toast.LENGTH_SHORT)
                    .show();
        }
    }

    // initializes the queue and library ListViews
    private void initListView() {
        ListView songsListView = findViewById(R.id.library_listview);
        final ListView queueListView = findViewById(R.id.queue_listview);

        libraryAdapter = new LibraryAdapter(this, songLibrary);
        queueAdapter = new QueueAdapter(this, songQueue);

        songsListView.setAdapter(libraryAdapter);
        queueListView.setAdapter(queueAdapter);

        // plays the selected song from the library on click
        songsListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            // TODO reset queue
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Song selectedSong = libraryAdapter.getItem(position);
                if (selectedSong != null) {
                    getCurrentQueuePos(position);
                    sendSongIntent(selectedSong.getTitle());
                    updateSongInfoViews(selectedSong);
                    resetPausePlay(ON_PLAY);
                    resetQueue();
                } else {
                    Log.e(LOG_TAG, "Error playing song");
                    Toast.makeText(MainActivity.this, "Error playing song.", Toast.LENGTH_SHORT).show();
                }
            }
        });

        queueListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Song selectedSong = queueAdapter.getItem(position);
                if (selectedSong != null) {
                    getCurrentQueuePos(position);
                    sendSongIntent(selectedSong.getTitle());
                    updateSongInfoViews(selectedSong);
                    resetPausePlay(ON_PLAY);
                    queueAdapter.notifyDataSetChanged();
                } else {
                    Log.e(LOG_TAG, "Error playing song");
                    Toast.makeText(MainActivity.this, "Error playing song.", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    // sends the selected song's info to the service
    private void sendSongIntent(String songTitle) {
        Intent intent = new Intent(this, BackgroundAudioService.class);
        intent.putExtra(MediaPlayerContract.INTENT_EXTRA_SONG, songTitle);
        intent.putExtra(MediaPlayerContract.INTENT_EXTRA_POS, currentQueuePos);
        startService(intent);
        currentState = STATE_PLAYING;
    }

    // sets the current position in the queue to the currently playing song
    private void getCurrentQueuePos(int position) {
        if (showQueue) {
            currentQueuePos = position;
        } else {
            Song currentSong = songLibrary.get(position);
            currentQueuePos = songQueue.indexOf(currentSong);
        }
    }

    // animates the swapping of the queue and library views
    private void animateListViews(boolean show) {
        final int DURATION = 300;
        ListView libraryListView = findViewById(R.id.library_listview);
        ListView queueListView = findViewById(R.id.queue_listview);

        final ListView disappearingView;
        final ListView appearingView;

        if (show) {
            appearingView = queueListView;
            disappearingView = libraryListView;
        } else {
            appearingView = libraryListView;
            disappearingView = queueListView;
        }

        // slides the dissapearing view down the screen, sets its visibility to GONE,
        // and makes the appearing view visible & slides it up the screen
        disappearingView.setVisibility(View.VISIBLE);
        disappearingView.animate().alpha(0.0f).translationY(libraryListView.getHeight()).setDuration(DURATION)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        queueAdapter.notifyDataSetChanged();
                        disappearingView.setVisibility(View.GONE);
                        appearingView.setVisibility(View.VISIBLE);
                        appearingView.animate().translationY(0).alpha(1.0f).setListener(null);
                    }
                });
    }

    // swaps the pause and play buttons as needed
    private void resetPausePlay(int flag) {
        Button play = findViewById(R.id.start);
        Button pause = findViewById(R.id.pause);

        switch (flag) {
            case ON_PAUSE:
                pause.setVisibility(View.GONE);
                play.setVisibility(View.VISIBLE);
                break;
            case ON_PLAY:
                pause.setVisibility(View.VISIBLE);
                play.setVisibility(View.GONE);
                break;
        }
    }

    // returns the next index in the queue
    private int getNextPos() {
        int nextPos;
        if (currentQueuePos != 0 && currentQueuePos % songQueue.size() == 0) {
            nextPos = currentQueuePos;
            currentQueuePos--;
        } else {
            nextPos = (currentQueuePos + 1) % songQueue.size();
        }

        return nextPos;
    }

    // sets info for the Now Playing and total time views
    private void updateSongInfoViews(Song song) {
        TextView nowPlayingTitle = findViewById(R.id.np_title);
        TextView nowPlayingAlbum = findViewById(R.id.np_album);
        TextView totalPlaybackTime = findViewById(R.id.total_time);

        totalPlaybackTime.setText(song.getLenStr());
        nowPlayingAlbum.setText(song.getAlbum());

        String songTitleArtist = song.getTitle() + " by " + song.getArtist();
        nowPlayingTitle.setText(songTitleArtist);
        nowPlayingTitle.setSelected(true);
    }

    // sets info for the Now Playing and total time views
    private void updateSongInfoViews(String title, String artist, String album, String lenStr) {
        TextView nowPlayingTitle = findViewById(R.id.np_title);
        TextView nowPlayingAlbum = findViewById(R.id.np_album);
        TextView totalPlaybackTime = findViewById(R.id.total_time);

        totalPlaybackTime.setText(lenStr);
        nowPlayingAlbum.setText(album);

        String songTitleArtist = title + " by " + artist;
        nowPlayingTitle.setText(songTitleArtist);
        nowPlayingTitle.setSelected(true);
    }

    // resets the queue to include entire library
    private void resetQueue() {
        if (showQueue) {
            playbackSource = songQueue;
            queueAdapter.notifyDataSetChanged();
        }
    }

    // returns true if the player is paused or playing, false otherwise
    private boolean playerActive() {
        return currentState == STATE_PAUSED || currentState == STATE_PLAYING;
    }

    @Override
    // unregisters the receiver and kills the service
    protected void onDestroy() {
        super.onDestroy();
        Log.e(LOG_TAG, "flag destroying");
        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver);
    }

    // TODO comment
    class LibraryAdapter extends ArrayAdapter<Song> {

        public LibraryAdapter(Context context, List<Song> songs) {
            super(context, 0, songs);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(R.layout.song_item,
                        parent, false);
            }

            TextView titleView = convertView.findViewById(R.id.song_title);
            TextView artistView = convertView.findViewById(R.id.song_artist);
            TextView lengthView = convertView.findViewById(R.id.song_length);
            ImageView albumCoverView = convertView.findViewById(R.id.album_cover);

            Song currentSong = getItem(position);

            titleView.setText(currentSong.getTitle());
            artistView.setText(currentSong.getArtist());
            lengthView.setText(currentSong.getLenStr());

            titleView.setSelected(true);

            Drawable albumCover = currentSong.getAlbumCover();
            if (albumCover != null) {
                albumCoverView.setBackground(albumCover);
            } else {
                albumCoverView.setBackground(getDrawable(R.drawable.missing_cover));
            }

            return convertView;
        }
    }

    // TODO comment
    class QueueAdapter extends ArrayAdapter<Song> {

        public QueueAdapter(Context context, List<Song> songs) {
            super(context, 0, songs);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(R.layout.song_item,
                        parent, false);
            }

            TextView titleView = convertView.findViewById(R.id.song_title);
            TextView artistView = convertView.findViewById(R.id.song_artist);
            TextView lengthView = convertView.findViewById(R.id.song_length);
            ImageView albumCoverView = convertView.findViewById(R.id.album_cover);

            Button optionsButton = convertView.findViewById(R.id.options_button);
            optionsButton.setVisibility(View.VISIBLE);

            optionsButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    View parentRow = (View) v.getParent();
                    ListView queueListView = (ListView) parentRow.getParent();
                    int position = queueListView.getPositionForView(parentRow);

                    AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                    builder.setItems(R.array.queue_dialog, new QueueOptionsClickListener(position));
                    builder.create().show();

                }
            });

            Song currentSong = getItem(position);

            titleView.setText(currentSong.getTitle());
            artistView.setText(currentSong.getArtist());
            lengthView.setText(currentSong.getLenStr());

            titleView.setSelected(true);

            Drawable albumCover = currentSong.getAlbumCover();
            if (albumCover != null) {
                albumCoverView.setBackground(albumCover);
            } else {
                albumCoverView.setBackground(getDrawable(R.drawable.missing_cover));
            }

            setViewAppearance(position, titleView, artistView, lengthView);

            return convertView;
        }

        // sets the text appearance of the passed TextViews to bold
        private void setViewAppearance(int position, TextView titleView, TextView artistView, TextView lengthView) {
            int pos = currentQueuePos % playbackSource.size();

            Typeface tf;
            if (position == pos && playerActive()) {
                tf = Typeface.defaultFromStyle(Typeface.BOLD);
            } else {
                tf = Typeface.defaultFromStyle(Typeface.NORMAL);
            }

            titleView.setTypeface(tf);
            artistView.setTypeface(tf);
            lengthView.setTypeface(tf);
        }
    }

    // custom Comparator class for Songs
    // compares the titles of both objects
    class SongComparator implements Comparator<Song> {
        @Override
        public int compare(Song s1, Song s2) {
            return s1.getTitle().compareTo(s2.getTitle());
        }
    }

    // TODO comment
    class QueueOptionsClickListener implements DialogInterface.OnClickListener {
        final int PLAY_NEXT = 0;
        final int REMOVE = 1;
        int position;

        public QueueOptionsClickListener(int position) {
            this.position = position;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            // moves the selected selected song directly in front of the current song
            // in the queue.
            if (which == PLAY_NEXT) {
                Song song = queueAdapter.getItem(position);
                if (position != currentQueuePos % playbackSource.size()) {
                    if (position < (currentQueuePos % playbackSource.size())) {
                        currentQueuePos--;
                    }
                    songQueue.remove(song);
                    songQueue.add(getNextPos(), song);
                } else {
                    Toast.makeText(MainActivity.this, "Song is currently playing.", Toast.LENGTH_SHORT).show();
                }
            } else if (which == REMOVE) { // removes the selected song from the queue.
                songQueue.remove(position);
                // remove the current song from the queue and begin playing the next
                if (position == currentQueuePos % playbackSource.size()) {
                    currentQueuePos--;
                    MediaControllerCompat.getMediaController(MainActivity.this).getTransportControls().skipToNext();
                    resetPausePlay(ON_PLAY);
                }
                if (position < (currentQueuePos % playbackSource.size())) {
                    currentQueuePos--;
                }
            }
            playbackSource = songQueue;
            queueAdapter.notifyDataSetChanged();
        }
    }

    // TODO comment
    class MediaConnectionCallback extends MediaBrowserCompat.ConnectionCallback {
        @Override
        public void onConnected() {
            super.onConnected();
            try {
                mediaControllerCompat = new MediaControllerCompat(MainActivity.this, mediaBrowserCompat.getSessionToken());
                mediaControllerCompat.registerCallback(new MediaControllerCallback());
                MediaControllerCompat.setMediaController(MainActivity.this, mediaControllerCompat);

                MediaMetadataCompat metadata = mediaControllerCompat.getMetadata();
                if (metadata != null) {
                    updateUIFromMetadata(metadata);
//                    resetButtons(metadata);
                }
            } catch (RemoteException e) {
                // TODO add something
            }
        }

//        // TODO comment
//        private void restoreUI(Intent intent) {
//            if (intent.getAction() == null) {
//                restoreUIFromIntent(intent);
//            } else {
//                updateUIFromMetadata(mediaControllerCompat.getMetadata());
//            }
//        }
//
//        // TODO comment
//        private void restoreUIFromService(Intent intent) {
//        // TODO use MediaControllCallback onMetadataChanged
//            // TODO add length metadata extra
//        }
//
//        // TODO remove intent once loaded info
//        // updates the UI with information from the notification
//        private void restoreUIFromIntent(Intent intent) {
//            Log.e(LOG_TAG, "intent action: " + intent.getAction());
//            Log.e(LOG_TAG, "flag got intent");
//            String[] info = intent.getStringArrayExtra(MediaPlayerContract.INTENT_EXTRA_SONG_INFO);
//            if (info != null) {
//                int pos = intent.getIntExtra(MediaPlayerContract.INTENT_EXTRA_POS, 0);
//                int duration = intent.getIntExtra(MediaPlayerContract.INTENT_EXTRA_DURATION, 0);
//                boolean playing = intent.getBooleanExtra(MediaPlayerContract.INTENT_EXTRA_IS_PLAYING, false);
//                unpackageSongInfo(info);
//                currentQueuePos = pos;
//                Log.e(LOG_TAG, "duration: " + duration);
//                seekBar.setMax(duration / MediaPlayerContract.MS_TO_SEC);
//                Log.e(LOG_TAG, "duration: " +  seekBar.getMax());
//                if (playing) {
//                MediaControllerCompat.getMediaController(MainActivity.this).getTransportControls().play();
//                }
//                setIntent(intent);
//            } else {
//                Log.e(LOG_TAG, "ATTN: info null");
//            }
//        }
    }

    // TODO comment
    private void updateUIFromMetadata(MediaMetadataCompat metadata) {
            MediaDescriptionCompat description = metadata.getDescription();
            String title = (String) description.getTitle();
            String artist = (String) description.getSubtitle();
            String len = metadata.getString(MediaPlayerContract.METADATA_KEY_LENGTH);
            String album = metadata.getString(MediaPlayerContract.METADATA_KEY_ALBUM);
            updateSongInfoViews(title, artist, album, len);
            currentQueuePos = (int) metadata.getLong(MediaPlayerContract.METADATA_KEY_POS);
            queueAdapter.notifyDataSetChanged();
            int duration = (int) metadata.getLong(MediaPlayerContract.METADATA_KEY_DURATION);
            seekBar.setMax(duration / MediaPlayerContract.MS_TO_SEC);
    }

    // TODO comment
    class MediaControllerCallback extends MediaControllerCompat.Callback {
        @Override
        public void onPlaybackStateChanged(PlaybackStateCompat state) {
            super.onPlaybackStateChanged(state);
            if (state == null) { return; }

            switch (state.getState()) {
                case PlaybackStateCompat.STATE_PLAYING:
                    currentState = STATE_PLAYING;
                    resetPausePlay(ON_PLAY);
                    break;
                case PlaybackStateCompat.STATE_PAUSED:
                    currentState = STATE_PAUSED;
                    resetPausePlay(ON_PAUSE);
                    break;
            }
        }

        @Override
        // TODO comment
        public void onMetadataChanged(MediaMetadataCompat metadata) {
            super.onMetadataChanged(metadata);
            updateUIFromMetadata(metadata);
        }
    }

    // BroadcastReceiver which handles local broadcasts from the Audio Service to this activity
    class ServiceToActivityReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String intentType = intent.getStringExtra(MediaPlayerContract.INTENT_TYPE);
            switch (intentType) {
                case MediaPlayerContract.INTENT_TYPE_PLAYER_POS:
                    int pos = intent.getIntExtra(MediaPlayerContract.INTENT_EXTRA_PLAYER_POS, -1);
                    if (pos > -1) {
                        updateSeekBar(pos);
                    }
                    break;
                case MediaPlayerContract.INTENT_TYPE_SONG_INFO:
                    String[] info = intent.getStringArrayExtra(MediaPlayerContract.INTENT_EXTRA_SONG_INFO);
                    unpackageSongInfo(info);
                    currentQueuePos = intent.getIntExtra(MediaPlayerContract.INTENT_EXTRA_POS, 0);
                    int duration = intent.getIntExtra(MediaPlayerContract.INTENT_EXTRA_DURATION, 0);
                    seekBar.setProgress(0);
                    seekBar.setMax(duration / MediaPlayerContract.MS_TO_SEC);
                    if (showQueue) {
                        queueAdapter.notifyDataSetChanged();
                    }
                    break;
                case MediaPlayerContract.INTENT_TYPE_POS:
                    currentQueuePos = intent.getIntExtra(MediaPlayerContract.INTENT_EXTRA_POS, 0);
                    queueAdapter.notifyDataSetChanged();
                    break;
                case MediaPlayerContract.INTENT_TYPE_UI_INFO:
                    shuffle = intent.getBooleanExtra(MediaPlayerContract.INTENT_EXTRA_SHUFFLE, false);
                    loop = intent.getBooleanExtra(MediaPlayerContract.INTENT_EXTRA_LOOP, false);
                    boolean playing = intent.getBooleanExtra(MediaPlayerContract.INTENT_EXTRA_IS_PLAYING, false);
                    resetButtons(shuffle, loop, playing);
                    break;

            }
        }

        // sends the given array of song information to update the now playing and total time views
        private void unpackageSongInfo(String[] info) {
            String title = info[MediaPlayerContract.TITLE];
            String artist = info[MediaPlayerContract.ARTIST];
            String album = info[MediaPlayerContract.ALBUM];
            String lenStr = info[MediaPlayerContract.LENGTH];

            updateSongInfoViews(title, artist, album, lenStr);
        }

        // updates the seekbar every second
        private void updateSeekBar(int pos) {
            TextView currentTime = findViewById(R.id.current_time);
            seekBar.setProgress(pos / MediaPlayerContract.MS_TO_SEC);
            currentTime.setText(convertDuration(pos));
        }

        // TODO comment
        private void resetButtons(boolean shuffle, boolean loop, boolean isPlaying) {
            CheckBox shuffleCheckBox = findViewById(R.id.shuffle_checkbox);
//        CheckBox queueCheckBox = findViewById(R.id.queue_checkbox);
            CheckBox loopCheckBox = findViewById(R.id.loop_checkbox);
//        Button playButton = findViewById(R.id.start);
//        Button pauseButton = findViewById(R.id.pause);

            shuffleCheckBox.setChecked(shuffle);
            loopCheckBox.setChecked(loop);
            int flag = isPlaying ? ON_PLAY : ON_PAUSE;
            resetPausePlay(flag);
        }
    }

    // TODO killing activity after shuffling/showing queue etc destroys views
    // TODO using play button isntead of clicking song doesn't start notification
}
