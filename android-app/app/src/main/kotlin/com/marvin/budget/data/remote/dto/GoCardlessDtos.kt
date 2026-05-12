package com.marvin.budget.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TokenRequest(
    @SerialName("secret_id") val secretId: String,
    @SerialName("secret_key") val secretKey: String
)

@Serializable
data class TokenResponse(
    val access: String,
    @SerialName("access_expires") val accessExpires: Int,
    val refresh: String,
    @SerialName("refresh_expires") val refreshExpires: Int
)

@Serializable
data class Institution(
    val id: String,
    val name: String,
    val bic: String? = null,
    val countries: List<String> = emptyList(),
    val logo: String? = null
)

@Serializable
data class RequisitionRequest(
    val redirect: String,
    @SerialName("institution_id") val institutionId: String,
    val reference: String,
    @SerialName("user_language") val userLanguage: String = "FR"
)

@Serializable
data class Requisition(
    val id: String,
    val status: String,
    val link: String,
    val accounts: List<String> = emptyList()
)

@Serializable
data class AccountDetailsResponse(val account: AccountDetails)

@Serializable
data class AccountDetails(
    val iban: String? = null,
    val currency: String,
    @SerialName("ownerName") val ownerName: String? = null,
    val name: String? = null,
    val product: String? = null
)

@Serializable
data class AccountBalancesResponse(val balances: List<Balance>)

@Serializable
data class Balance(
    val balanceAmount: Amount,
    val balanceType: String,
    val referenceDate: String? = null
)

@Serializable
data class Amount(val amount: String, val currency: String)

@Serializable
data class TransactionsResponse(val transactions: TransactionsList)

@Serializable
data class TransactionsList(
    val booked: List<RemoteTransaction> = emptyList(),
    val pending: List<RemoteTransaction> = emptyList()
)

@Serializable
data class RemoteTransaction(
    val transactionId: String? = null,
    val internalTransactionId: String? = null,
    val bookingDate: String? = null,
    val valueDate: String? = null,
    val transactionAmount: Amount,
    val creditorName: String? = null,
    val debtorName: String? = null,
    val remittanceInformationUnstructured: String? = null,
    val bankTransactionCode: String? = null
)
