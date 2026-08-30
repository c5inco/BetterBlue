package com.betterblue.app.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.betterblue.app.data.db.entity.AccountEntity
import com.betterblue.app.data.repo.AccountRepository
import com.betterblue.app.ui.common.ErrorDetailsCard
import com.betterblue.kit.HyundaiCanadaVariant
import com.betterblue.kit.model.Brand
import com.betterblue.kit.model.Region
import com.betterblue.kit.requiresPin

/** Credentials, connection variant, and the destructive account actions. */
@Composable
fun AccountInfoSheet(account: AccountEntity, state: SheetUiState, viewModel: SheetsViewModel) {
    var password by remember(account.id) { mutableStateOf("") }
    var pin by remember(account.id) { mutableStateOf("") }
    var refreshToken by remember(account.id) { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    val brand = AccountRepository.brandFromRaw(account.brand)
    val region = AccountRepository.regionFromRaw(account.region)

    SheetScaffold(title = "Account") {
        SheetSection("Identity") {
            InfoRow("Username", account.username)
            InfoRow("Brand", brand.displayName)
            InfoRow("Region", region.displayName)
            if (brand == Brand.HYUNDAI && region == Region.CANADA) {
                InfoRow(
                    "Connection",
                    if (account.hyundaiCanadaVariant == "nativeApp") {
                        HyundaiCanadaVariant.NATIVE_APP.displayName
                    } else {
                        HyundaiCanadaVariant.WEB_PORTAL.displayName
                    },
                )
            }
        }

        SheetSection("Update credentials") {
            // Stored values are encrypted and never surfaced; blank means
            // "leave unchanged".
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("New password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (requiresPin(brand, region)) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    label = { Text("New PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (brand == Brand.HYUNDAI && region == Region.EUROPE) {
                OutlinedTextField(
                    value = refreshToken,
                    onValueChange = { refreshToken = it },
                    label = { Text("New refresh token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(
                onClick = {
                    viewModel.updateAccount(
                        accountId = account.id,
                        password = password,
                        pin = pin,
                        refreshToken = refreshToken.ifBlank { null },
                    )
                },
                enabled = !state.isBusy && (password.isNotBlank() || pin.isNotBlank() || refreshToken.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save credentials")
            }
        }

        SheetSection("Session") {
            OutlinedButton(
                onClick = { viewModel.resetSession(account.id) },
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.isBusy) "Working…" else "Reset session")
            }
            Text(
                "Signs out and back in, clearing the stored token, remember-me token and device " +
                    "registration. Use this if the app keeps failing to authenticate.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SheetSection("Danger zone") {
            Button(
                onClick = { confirmDelete = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Remove account")
            }
        }

        state.error?.let { ErrorDetailsCard(it) }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove account?") },
            text = {
                Text(
                    "This removes ${account.username} and its vehicles from this device. " +
                        "Your Hyundai or Kia account is unaffected.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.removeAccount(account.id)
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}
