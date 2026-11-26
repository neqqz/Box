package com.google.ai.edge.gallery.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.google.ai.edge.gallery.data.local.entities.FaceRecord

@Dao
interface FaceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(face: FaceRecord): Long

    @Query("SELECT * FROM faces ORDER BY created_at DESC")
    suspend fun getAll(): List<FaceRecord>

    @Query("UPDATE faces SET name = :newName WHERE name = :oldName")
    suspend fun rename(oldName: String, newName: String)

    @Query("DELETE FROM faces WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM faces WHERE name = :name")
    suspend fun deleteByName(name: String)

    @Query("DELETE FROM faces")
    suspend fun deleteAll()
}
