package com.dalcourt.jonathan.musicplayer;

public final class MediaPlayerContract {

    public static final String INTENT_TYPE_PLAYER_POS = "typePlayerPos";
    public static final String INTENT_TYPE_SONG_INFO = "typeSongInfo";
    public static final String INTENT_TYPE_POS = "typePos";
    public static final String INTENT_TYPE_UI_INFO = "typeUInfo";

    public static final String CHANNEL_ID = "MusicPlayer";


    public static final String BUNDLE_SONG_INFO = "songInfo";
    public static final String BUNDLE_POS = "position";
    public static final String BUNDLE_PLAYER_DURATION = "playerDuration";

    public static final String INTENT_TYPE = "type";
    public static final String INTENT_EXTRA_SONG = "song";
    public static final String INTENT_EXTRA_POS = "position";
    public static final String INTENT_EXTRA_SONG_INFO = "songInfo";
    public static final String INTENT_EXTRA_DURATION = "songDuration";
    public static final String INTENT_EXTRA_PLAYER_POS = "playerPos";
    public static final String INTENT_EXTRA_IS_PLAYING = "isPlaying";
    public static final String INTENT_EXTRA_LOOP = "isLooping";
    public static final String INTENT_EXTRA_SHUFFLE = "isShuffled";

    public static final int INFO_ARRAY = 0;
    public static final int QUEUE_POS = 1;
    public static final int DURATION = 2;

    public static final String METADATA_KEY_DURATION = "metadataDuration";
    public static final String METADATA_KEY_POS = "metadataPosition";
    public static final String METADATA_KEY_LENGTH = "metadataLength";
    public static final String METADATA_KEY_ALBUM = "metadataAlbum";
    public static final String METADATA_KEY_SHUFFLE = "metadataShuffle";
    public static final String METADATA_KEY_LOOP = "metadataLoop";
    public static final String METADATA_KEY_SHOW_QUEUE = "metadataShowQueue";
    public static final String METADATA_KEY_PLAYING = "metadataPlaying";

    public static final long FALSE = 0;
    public static final long TRUE = 1;



    public static final String INTENT_SERVICE_TO_ACTIVITY = "fromService";
    public static final String INTENT_ACTIVITY_TO_SERVICE = "fromActivity";

    public static final int REQUEST_CODE = 100;

    public static final int MS_TO_SEC = 1000;
    public static final int SEC_TO_MIN = 60;

    public static final int NUM_FIELDS = 4;
    public static final int TITLE = 0;
    public static final int ARTIST = 1;
    public static final int ALBUM = 2;
    public static final int LENGTH = 3;




}
