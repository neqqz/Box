package com.google.ai.edge.gallery.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "rag_documents")
data class RagDocumentEntity(
    @PrimaryKey val id: String,
    val title: String,
    val uri: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "chunk_count") val chunkCount: Int,
)
