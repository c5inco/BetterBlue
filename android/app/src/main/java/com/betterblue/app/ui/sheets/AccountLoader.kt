package com.betterblue.app.ui.sheets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import com.betterblue.app.data.db.entity.AccountEntity

/** Loads the account behind an [SheetRoute.AccountInfo] route. */
@Composable
internal fun produceAccount(viewModel: SheetsViewModel, accountId: String): State<AccountEntity?> =
    produceState<AccountEntity?>(initialValue = null, accountId) {
        value = viewModel.account(accountId)
    }
