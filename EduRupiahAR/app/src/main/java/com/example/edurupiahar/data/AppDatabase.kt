package com.example.edurupiahar.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The Room database for the EduRupiahAR application.
 * This database stores [CurrencyInfo] entities.
 * It uses a singleton pattern to ensure only one instance is created.
 * Includes a callback to pre-populate the database on creation.
 *
 * @property currencyDao Provides access to the [CurrencyDao] for database operations.
 */
@Database(entities = [CurrencyInfo::class], version = 1, exportSchema = false) // exportSchema is false for simplicity in this project
abstract class AppDatabase : RoomDatabase() {

    /**
     * Abstract method to get the Data Access Object (DAO) for currency information.
     * @return The [CurrencyDao] instance.
     */
    abstract fun currencyDao(): CurrencyDao

    companion object {
        /**
         * Volatile instance of the database to ensure visibility across threads.
         */
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Gets the singleton instance of the [AppDatabase].
         * Creates the database if it doesn't exist, using a synchronized block for thread safety.
         *
         * @param context The application context.
         * @param scope The [CoroutineScope] to run database population tasks.
         * @return The singleton [AppDatabase] instance.
         */
        fun getDatabase(context: Context, scope: CoroutineScope): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "edurupiah_database" // Name of the database file
                )
                // Wipes and rebuilds instead of migrating if no Migration object.
                // Migration is not part of this project scope.
                .fallbackToDestructiveMigration()
                .addCallback(AppDatabaseCallback(scope)) // Attach callback for pre-population
                .build()
                INSTANCE = instance
                // return instance
                instance
            }
        }

        /**
         * Private callback class to handle database creation events, specifically for pre-populating data.
         * @param scope The [CoroutineScope] to launch the population task.
         */
        private class AppDatabaseCallback(
            private val scope: CoroutineScope
        ) : RoomDatabase.Callback() {

            /**
             * Called when the database is created for the first time.
             * This is where initial data can be inserted.
             * @param db The database instance.
             */
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                INSTANCE?.let { database ->
                    scope.launch(Dispatchers.IO) { // Run population in a background IO thread
                        populateDatabase(database.currencyDao())
                    }
                }
            }

            /**
             * Populates the database with sample currency data.
             * This is a suspend function, intended to be called from a coroutine.
             * @param currencyDao The DAO to perform insert operations.
             */
            suspend fun populateDatabase(currencyDao: CurrencyDao) {
                // Add sample currency data
                val sampleCurrencies = listOf(
                    CurrencyInfo(
                        denominationValue = 2000,
                        detectedLabel = "IDR2000",
                        yearOfIssue = "2016",
                        securityFeatures = "Watermark: Prince Antasari, Security Thread, Microprinting, Latent Image, Tactile Marks",
                        description = "Features Prince Antasari, a national hero from South Kalimantan. The reverse side depicts the traditional Dayak dance 'Tari Piring'.",
                        imageFileName = "rp_2000_front.png"
                    ),
                    CurrencyInfo(
                        denominationValue = 100000,
                        detectedLabel = "IDR100000",
                        yearOfIssue = "2016",
                        securityFeatures = "Watermark: Soekarno & Hatta, Optically Variable Ink (OVI), Security Thread with color shifting, Microprinting, Latent Image, Tactile Marks for visually impaired",
                        description = "Features Indonesia's first president and vice-president, Soekarno and Mohammad Hatta. The reverse side shows the Raja Ampat islands, a famous diving destination in Papua.",
                        imageFileName = "rp_100000_front.png"
                    ),
                    CurrencyInfo(
                        denominationValue = 50000,
                        detectedLabel = "IDR50000",
                        yearOfIssue = "2016",
                        securityFeatures = "Watermark: I Gusti Ngurah Rai, Security Thread, Microprinting, Latent Image, Tactile Marks",
                        description = "Features I Gusti Ngurah Rai, a national hero from Bali. The reverse side depicts the Komodo National Park.",
                        imageFileName = "rp_50000_front.png"
                    )
                    // Add more sample data as needed
                )
                currencyDao.insertAll(sampleCurrencies)
                Log.d(TAG, "Database populated with sample data.")
            }
        }
    }
}
