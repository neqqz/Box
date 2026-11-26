package com.google.ai.edge.gallery.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.ai.edge.gallery.data.local.dao.ConversationDao
import com.google.ai.edge.gallery.data.local.dao.FaceDao
import com.google.ai.edge.gallery.data.local.dao.MessageDao
import com.google.ai.edge.gallery.data.local.dao.RagChunkDao
import com.google.ai.edge.gallery.data.local.dao.RagDocumentDao
import com.google.ai.edge.gallery.data.local.entities.Conversation
import com.google.ai.edge.gallery.data.local.entities.FaceRecord
import com.google.ai.edge.gallery.data.local.entities.Message
import com.google.ai.edge.gallery.data.local.entities.RagChunkEntity
import com.google.ai.edge.gallery.data.local.entities.RagDocumentEntity
import com.google.ai.edge.gallery.security.SecurityUtils
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Box: Encrypted Room database for chat history, RAG documents, and face embeddings.
 * Uses SQLCipher for at-rest encryption with a key derived from device Keystore.
 */
@Database(
    entities = [
        Conversation::class,
        Message::class,
        RagDocumentEntity::class,
        RagChunkEntity::class,
        FaceRecord::class,
    ],
    version = 5,
    exportSchema = false
)
abstract class BoxChatDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun ragDocumentDao(): RagDocumentDao
    abstract fun ragChunkDao(): RagChunkDao
    abstract fun faceDao(): FaceDao

    companion object {
        private const val DB_NAME = "box_chat.db"

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN systemPrompt TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN imagePaths TEXT")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE rag_documents (id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, uri TEXT NOT NULL, created_at INTEGER NOT NULL, chunk_count INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE rag_chunks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, document_id TEXT NOT NULL, ordinal INTEGER NOT NULL, text TEXT NOT NULL, embedding BLOB NOT NULL, FOREIGN KEY (document_id) REFERENCES rag_documents(id) ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX index_rag_chunks_document_id ON rag_chunks(document_id)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE faces (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, embedding BLOB NOT NULL, thumbnail BLOB NOT NULL, created_at INTEGER NOT NULL)")
            }
        }

        @Volatile
        private var INSTANCE: BoxChatDatabase? = null

        fun getInstance(context: Context): BoxChatDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): BoxChatDatabase {
            // Load SQLCipher native libraries
            System.loadLibrary("sqlcipher")

            val passphrase = SecurityUtils.getDatabasePassphrase(context)
            val factory = SupportOpenHelperFactory(passphrase)

            return Room.databaseBuilder(
                context.applicationContext,
                BoxChatDatabase::class.java,
                DB_NAME
            )
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
