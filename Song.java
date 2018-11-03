package com.dalcourt.jonathan.musicplayer;

import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;

public class Song implements Parcelable {

    private String title;
    private String artist;
    private String album;
    private String lenStr;
    private String path;
    private int bytesLength;
    private Drawable albumCover;

    public static final Parcelable.Creator<Song> CREATOR = new Parcelable.Creator<Song>() {
        public Song createFromParcel(Parcel in) {
            return new Song(in);
        }

        public Song[] newArray(int size) {
            return new Song[size];
        }
    };

    public Song(String title, String artist, String album, String lenStr, String path, int bytesLength, Drawable albumCover) {
        this.album = album;
        this.artist = artist;
        this.lenStr = lenStr;
        this.title = title;
        this.path = path;
        this.bytesLength = bytesLength;
        this.albumCover = albumCover;
    }

    public Song(Parcel in) {
        title = in.readString();
        artist = in.readString();
        album = in.readString();
        lenStr = in.readString();
        path = in.readString();
        bytesLength = in.readInt();
//        albumCover = new byte[bytesLength];
//        in.readByteArray(albumCover);
    }

    public String getAlbum() {
        return album;
    }

    public String getArtist() {
        return artist;
    }

    public String getLenStr() {
        return lenStr;
    }

    public String getTitle() {
        return title;
    }

    public String getPath() {
        return path;
    }

    public Drawable getAlbumCover() {
        return albumCover;
    }

    public void setAlbum(String album) {
        this.album = album;
    }

    public void setArtist(String artist) {
        this.artist = artist;
    }

    public void setLenStr(String length) {
        this.lenStr = length;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setAlbumCover(Drawable albumCover) {
        this.albumCover = albumCover;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeString(title);
        parcel.writeString(artist);
        parcel.writeString(album);
        parcel.writeString(lenStr);
        parcel.writeString(path);
        parcel.writeInt(bytesLength);
//        parcel.writeByteArray(albumCover);
    }

    @Override
    public boolean equals(Object obj) {
        return (obj instanceof Song && path.equals(((Song) obj).getPath()));
    }
}
