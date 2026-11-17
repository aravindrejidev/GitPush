package com.gitpush.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sourceKind: String,
    val sourceUri: String,
    val sourceLabel: String,
    val owner: String,
    val repo: String,
    val branch: String,
    val baseBranch: String,
    val targetSubdir: String,
    val deleteMissing: Boolean,
    val lastMessage: String,
    val lastPushAt: Long?,
    val lastCommitSha: String?,
    val createdAt: Long,
)

@Entity(tableName = "pushes")
data class PushEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val repoFullName: String,
    val branch: String,
    val commitSha: String?,
    val message: String,
    val status: String, // pushed | up_to_date | failed
    val added: Int,
    val modified: Int,
    val deleted: Int,
    val bytes: Long,
    val durationMs: Long,
    val error: String?,
    val createdAt: Long,
)

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY COALESCE(lastPushAt, createdAt) DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: Long): ProjectEntity?

    @Query("SELECT * FROM projects WHERE sourceUri = :uri AND owner = :owner AND repo = :repo AND branch = :branch LIMIT 1")
    suspend fun find(uri: String, owner: String, repo: String, branch: String): ProjectEntity?

    @Insert
    suspend fun insert(p: ProjectEntity): Long

    @Update
    suspend fun update(p: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface PushDao {
    @Query("SELECT * FROM pushes ORDER BY createdAt DESC LIMIT 300")
    fun observeRecent(): Flow<List<PushEntity>>

    @Insert
    suspend fun insert(e: PushEntity): Long

    @Query("DELETE FROM pushes")
    suspend fun clear()
}

@Database(entities = [ProjectEntity::class, PushEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projects(): ProjectDao
    abstract fun pushes(): PushDao
}
