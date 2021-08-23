package com.pushengage.pushengage.Database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface DaoInterface {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(ChannelEntity channelEntity);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertClickRequest(ClickRequestEntity clickRequestEntity);

    @Query("SELECT * FROM ClickRequest")
    List<ClickRequestEntity> getAllClick();

    @Query("DELETE FROM ClickRequest WHERE deviceHash = :deviceHash AND tag = :tag")
    void deleteClick(String deviceHash, String tag);

    @Query("SELECT * FROM Channel WHERE channel_id = :channelId")
    ChannelEntity getChannel(String channelId);

    @Query("DELETE FROM Channel")
    void deleteChannels();
}
