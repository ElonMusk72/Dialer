package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultFileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(file: VaultFileEntity): Long

    @Delete
    suspend fun delete(file: VaultFileEntity)

    @Query("DELETE FROM vault_files WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM vault_files")
    suspend fun deleteAll()

    @Query("SELECT * FROM vault_files ORDER BY dateAdded DESC")
    fun getAllFiles(): Flow<List<VaultFileEntity>>

    @Query("SELECT * FROM vault_files WHERE fileType = :fileType ORDER BY dateAdded DESC")
    fun getFilesByType(fileType: String): Flow<List<VaultFileEntity>>

    @Query("SELECT * FROM vault_files")
    suspend fun getAllFilesSync(): List<VaultFileEntity>

    @Query("SELECT COUNT(*) FROM vault_files WHERE fileType = :fileType")
    fun getCountByType(fileType: String): Flow<Int>

    @Query("SELECT SUM(fileSize) FROM vault_files")
    fun getTotalStorageUsed(): Flow<Long?>
}
