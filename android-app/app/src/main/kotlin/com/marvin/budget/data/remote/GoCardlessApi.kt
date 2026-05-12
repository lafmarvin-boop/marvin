package com.marvin.budget.data.remote

import com.marvin.budget.data.remote.dto.AccountBalancesResponse
import com.marvin.budget.data.remote.dto.AccountDetailsResponse
import com.marvin.budget.data.remote.dto.Institution
import com.marvin.budget.data.remote.dto.Requisition
import com.marvin.budget.data.remote.dto.RequisitionRequest
import com.marvin.budget.data.remote.dto.TokenRequest
import com.marvin.budget.data.remote.dto.TokenResponse
import com.marvin.budget.data.remote.dto.TransactionsResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * GoCardless Bank Account Data (formerly Nordigen).
 * Base URL: https://bankaccountdata.gocardless.com/api/v2/
 * Docs: https://developer.gocardless.com/bank-account-data/overview
 *
 * IMPORTANT: This client is READ-ONLY. No endpoint of the AISP API can
 * initiate a payment — only account, balance and transaction reads.
 */
interface GoCardlessApi {

    @POST("token/new/")
    suspend fun newToken(@Body body: TokenRequest): TokenResponse

    @GET("institutions/")
    suspend fun listInstitutions(
        @Header("Authorization") bearer: String,
        @Query("country") country: String = "FR"
    ): List<Institution>

    @POST("requisitions/")
    suspend fun createRequisition(
        @Header("Authorization") bearer: String,
        @Body body: RequisitionRequest
    ): Requisition

    @GET("requisitions/{id}/")
    suspend fun getRequisition(
        @Header("Authorization") bearer: String,
        @Path("id") id: String
    ): Requisition

    @GET("accounts/{id}/details/")
    suspend fun getAccountDetails(
        @Header("Authorization") bearer: String,
        @Path("id") accountId: String
    ): AccountDetailsResponse

    @GET("accounts/{id}/balances/")
    suspend fun getAccountBalances(
        @Header("Authorization") bearer: String,
        @Path("id") accountId: String
    ): AccountBalancesResponse

    @GET("accounts/{id}/transactions/")
    suspend fun getAccountTransactions(
        @Header("Authorization") bearer: String,
        @Path("id") accountId: String,
        @Query("date_from") dateFrom: String? = null,
        @Query("date_to") dateTo: String? = null
    ): TransactionsResponse
}
