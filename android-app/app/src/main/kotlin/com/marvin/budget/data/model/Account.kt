package com.marvin.budget.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class AccountKind { CHECKING, SAVINGS, CREDIT_CARD, JOINT, OTHER }

@Entity(tableName = "accounts")
data class Account(
    @PrimaryKey val id: String,
    val institutionName: String,
    val displayName: String,
    val iban: String?,
    val currency: String,
    val balance: Double,
    val kind: AccountKind,
    /** True if this account is a joint / shared account (compte commun). */
    val isJoint: Boolean,
    val colorHex: String,
    val lastSyncedAt: Long
)
