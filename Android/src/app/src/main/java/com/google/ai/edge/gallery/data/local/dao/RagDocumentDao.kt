package com.google.ai.edge.gallery.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.google.ai.edge.gallery.data.local.entities.RagDocumentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RagDocumentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(doc: RagDocumentEntity)

    @Delete
    suspend fun delete(doc: RagDocumentEntity)

    @Query("SELECT * FROM rag_documents ORDER BY created_at DESC")
    fun observeAll(): Flow<List<RagDocumentEntity>>

    @Query("SELECT * FROM rag_documents WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): RagDocumentEntity?
}
