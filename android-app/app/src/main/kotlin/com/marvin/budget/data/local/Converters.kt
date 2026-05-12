package com.marvin.budget.data.local

import androidx.room.TypeConverter
import com.marvin.budget.data.model.AccountKind
import com.marvin.budget.data.model.TxCategory
import java.time.LocalDate

class Converters {
    @TypeConverter fun fromLocalDate(date: LocalDate?): String? = date?.toString()
    @TypeConverter fun toLocalDate(value: String?): LocalDate? = value?.let(LocalDate::parse)

    @TypeConverter fun fromAccountKind(kind: AccountKind): String = kind.name
    @TypeConverter fun toAccountKind(value: String): AccountKind = AccountKind.valueOf(value)

    @TypeConverter fun fromCategory(category: TxCategory): String = category.name
    @TypeConverter fun toCategory(value: String): TxCategory = TxCategory.valueOf(value)
}
