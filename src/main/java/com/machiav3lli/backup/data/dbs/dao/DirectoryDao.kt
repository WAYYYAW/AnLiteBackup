package com.machiav3lli.backup.data.dbs.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.machiav3lli.backup.data.dbs.entity.BackupDirectory
import kotlinx.coroutines.flow.Flow

@Dao
interface DirectoryDao {
    @Query("SELECT * FROM BackupDirectory ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BackupDirectory>>

    @Query("SELECT * FROM BackupDirectory ORDER BY createdAt DESC")
    suspend fun getAll(): List<BackupDirectory>

    @Query("SELECT * FROM BackupDirectory WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): BackupDirectory?

    @Query("SELECT * FROM BackupDirectory WHERE path = :path LIMIT 1")
    suspend fun getByPath(path: String): BackupDirectory?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(directory: BackupDirectory): Long

    @Update
    suspend fun update(directory: BackupDirectory)

    @Delete
    suspend fun delete(directory: BackupDirectory)

    @Query("DELETE FROM BackupDirectory WHERE id = :id")
    suspend fun deleteById(id: Long)
}
