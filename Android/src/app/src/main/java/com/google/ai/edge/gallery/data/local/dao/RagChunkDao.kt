package com.google.ai.edge.gallery.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.google.ai.edge.gallery.data.local.entities.RagChunkEntity

@Dao
interface RagChunkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<RagChunkEntity>)

    @Query("SELECT * FROM rag_chunks WHERE document_id IN (:ids)")
    suspend fun getForDocuments(ids: List<String>): List<RagChunkEntity>

    @Query("SELECT * FROM rag_chunks")
    suspend fun getAll(): List<RagChunkEntity>
}
