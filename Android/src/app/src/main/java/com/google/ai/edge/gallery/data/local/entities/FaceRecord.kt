package com.google.ai.edge.gallery.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "faces")
data class FaceRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val embedding: ByteArray,
    val thumbnail: ByteArray,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FaceRecord) return false
        return id == other.id &&
            name == other.name &&
            embedding.contentEquals(other.embedding) &&
            thumbnail.contentEquals(other.thumbnail) &&
            createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + thumbnail.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }
}
