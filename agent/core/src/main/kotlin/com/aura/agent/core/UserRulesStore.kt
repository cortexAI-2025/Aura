package com.aura.agent.core

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.aura.core.domain.model.AutonomyLevel
import com.aura.core.domain.model.BudgetRule
import com.aura.core.domain.model.UserRules
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.rulesDataStore by preferencesDataStore("aura_user_rules")

@Singleton
class UserRulesStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val ds = context.rulesDataStore

    companion object {
        val KEY_AUTONOMY = stringPreferencesKey("autonomy_level")
        val KEY_BUDGET_CENTS = longPreferencesKey("budget_max_cents")
        val KEY_BUDGET_CURRENCY = stringPreferencesKey("budget_currency")
        val KEY_CONFIRM_SEND = booleanPreferencesKey("confirm_before_send")
        val KEY_QUIET_START = intPreferencesKey("quiet_hours_start")
        val KEY_QUIET_END = intPreferencesKey("quiet_hours_end")
    }

    suspend fun getRules(): UserRules = observeRules().first()

    fun observeRules(): Flow<UserRules> = ds.data.map { prefs ->
        val autonomy = runCatching {
            AutonomyLevel.valueOf(prefs[KEY_AUTONOMY] ?: "SUPERVISED")
        }.getOrDefault(AutonomyLevel.SUPERVISED)

        val budgetCents = prefs[KEY_BUDGET_CENTS]
        val budget = if (budgetCents != null && budgetCents > 0) BudgetRule(
            maxAmountCents = budgetCents,
            currency = prefs[KEY_BUDGET_CURRENCY] ?: "EUR",
        ) else null

        UserRules(
            budget = budget,
            requireConfirmationForSend = prefs[KEY_CONFIRM_SEND] ?: true,
            quietHoursStart = prefs[KEY_QUIET_START] ?: 22,
            quietHoursEnd = prefs[KEY_QUIET_END] ?: 8,
            autonomyLevel = autonomy,
        )
    }

    suspend fun updateAutonomy(level: AutonomyLevel) = ds.edit { it[KEY_AUTONOMY] = level.name }
    suspend fun updateBudget(cents: Long, currency: String = "EUR") = ds.edit {
        it[KEY_BUDGET_CENTS] = cents
        it[KEY_BUDGET_CURRENCY] = currency
    }
    suspend fun updateConfirmSend(require: Boolean) = ds.edit { it[KEY_CONFIRM_SEND] = require }
    suspend fun updateQuietHours(start: Int, end: Int) = ds.edit {
        it[KEY_QUIET_START] = start; it[KEY_QUIET_END] = end
    }
}
