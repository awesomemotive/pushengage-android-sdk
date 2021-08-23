package com.pushengage.pushengage.Database;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {ClickRequestEntity.class, ChannelEntity.class}, version = 1)
public abstract class PERoomDatabase extends RoomDatabase {
    public abstract DaoInterface daoInterface();

    private static volatile PERoomDatabase peRoomDatabaseInstance;

    public static PERoomDatabase getDatabase(final Context context) {
        if (peRoomDatabaseInstance == null) {
            synchronized (PERoomDatabase.class) {
                if (peRoomDatabaseInstance == null) {
                    peRoomDatabaseInstance = Room.databaseBuilder(context.getApplicationContext(), PERoomDatabase.class, "pe_database").allowMainThreadQueries().build();
                }
            }
        }
        return peRoomDatabaseInstance;
    }
}
