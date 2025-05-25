package com.example.edurupiahar.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Data Access Object (DAO) for the [CurrencyInfo] entity.
 * Provides methods to interact with the `currency_info` table in the database.
 * All methods are suspend functions, designed to be called from coroutines.
 */
@Dao
interface CurrencyDao {
    /**
     * Inserts a list of [CurrencyInfo] objects into the database.
     * If a currency with the same primary key already exists, it will be replaced.
     *
     * @param currencies A list of [CurrencyInfo] objects to insert.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(currencies: List<CurrencyInfo>)

    /**
     * Inserts a single [CurrencyInfo] object into the database.
     * If a currency with the same primary key already exists, it will be replaced.
     * Useful for individual insertions, such as during database pre-population callback.
     *
     * @param currency The [CurrencyInfo] object to insert.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(currency: CurrencyInfo)

    /**
     * Finds and retrieves a [CurrencyInfo] object by its `detectedLabel`.
     * As `detectedLabel` is indexed, this query should be efficient.
     *
     * @param detectedLabel The unique label string to search for (e.g., "IDR2000").
     * @return The matching [CurrencyInfo] object if found, otherwise null.
     */
    @Query("SELECT * FROM currency_info WHERE detected_label = :detectedLabel LIMIT 1")
    suspend fun findByDetectedLabel(detectedLabel: String): CurrencyInfo?

    /**
     * Retrieves all [CurrencyInfo] objects currently stored in the database.
     *
     * @return A list of all [CurrencyInfo] objects. The list may be empty if the table is empty.
     */
    @Query("SELECT * FROM currency_info")
    suspend fun getAll(): List<CurrencyInfo>

    /**
     * Finds and retrieves a [CurrencyInfo] object by its `denominationValue`.
     * Note: This might return any one currency if multiple notes share the same denomination value
     * (e.g., different series of Rp 50.000). Use with caution if specific series are important.
     *
     * @param denominationValue The numerical denomination value to search for (e.g., 50000).
     * @return The first matching [CurrencyInfo] object if found, otherwise null.
     */
    @Query("SELECT * FROM currency_info WHERE denomination_value = :denominationValue LIMIT 1")
    suspend fun findByDenominationValue(denominationValue: Int): CurrencyInfo?
}
