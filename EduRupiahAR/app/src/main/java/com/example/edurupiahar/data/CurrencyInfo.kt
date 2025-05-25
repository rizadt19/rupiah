package com.example.edurupiahar.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a single currency note's information in the database.
 * This data class is used as a Room Entity.
 *
 * @property id Unique identifier for the currency entry (auto-generated).
 * @property denominationValue The numerical value of the currency denomination (e.g., 2000, 50000, 100000).
 * @property detectedLabel A unique string label used for identifying this currency,
 *                         typically matching an ML model's output or a predefined key (e.g., "IDR2000"). Indexed for faster queries.
 * @property yearOfIssue The year the specific series of this currency note was issued.
 * @property securityFeatures A textual description of the security features present on the note.
 * @property description A general description of the note, often including depicted heroes or imagery.
 * @property imageFileName Optional file name for an image representing this currency note,
 *                         which could be stored in assets or another location.
 */
@Entity(tableName = "currency_info")
data class CurrencyInfo(
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0,

    @ColumnInfo(name = "denomination_value")
    val denominationValue: Int,

    @ColumnInfo(name = "detected_label", index = true) // Indexing for faster lookups by label
    val detectedLabel: String,

    @ColumnInfo(name = "year_of_issue")
    val yearOfIssue: String,

    @ColumnInfo(name = "security_features")
    val securityFeatures: String,

    @ColumnInfo(name = "description")
    val description: String,

    @ColumnInfo(name = "image_file_name")
    val imageFileName: String? = null // Optional field for an image asset name
)
