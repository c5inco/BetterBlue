package com.betterblue.app.ui.screens.addaccount

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.betterblue.app.ui.common.ErrorBox
import com.betterblue.app.ui.common.ErrorDetailsCard
import com.betterblue.app.ui.common.LoadingOverlay
import com.betterblue.kit.HyundaiCanadaVariant
import com.betterblue.kit.betaRegions
import com.betterblue.kit.model.Brand
import com.betterblue.kit.model.Region
import com.betterblue.kit.supportedRegions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    viewModel: AddAccountViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.finished) {
        if (state.finished) onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Account") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::addAccount, enabled = state.canSubmit) {
                        Text("Add")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ServiceConfigurationSection(state, viewModel)
                AccountInformationSection(state, viewModel)

                state.saveError?.let { ErrorDetailsCard(it) }
            }

            if (state.isLoading && state.mfa.step == MfaStep.HIDDEN) {
                LoadingOverlay("Signing in to ${state.brand.displayName}…")
            }
        }
    }

    if (state.mfa.step != MfaStep.HIDDEN) {
        MfaFlowSheet(state = state.mfa, viewModel = viewModel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServiceConfigurationSection(state: AddAccountUiState, viewModel: AddAccountViewModel) {
    SectionHeader("Service Configuration")

    DropdownField(
        label = "Brand",
        value = state.brand.displayName,
        options = state.availableBrands.map { it.displayName },
        onSelected = { index -> viewModel.setBrand(state.availableBrands[index]) },
    )

    if (state.brand != Brand.FAKE) {
        DropdownField(
            label = "Region",
            value = state.region.displayName,
            options = Region.entries.map { it.displayName },
            onSelected = { index -> viewModel.setRegion(Region.entries[index]) },
        )

        if (state.brand == Brand.HYUNDAI && state.region == Region.CANADA) {
            DropdownField(
                label = "Connection",
                value = state.hyundaiCanadaVariant.displayName,
                options = HyundaiCanadaVariant.entries.map { it.displayName },
                onSelected = { index ->
                    viewModel.setHyundaiCanadaVariant(HyundaiCanadaVariant.entries[index])
                },
            )
            Text(
                state.hyundaiCanadaVariant.summary,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val beta = betaRegions(state.brand).contains(state.region)
        val supported = supportedRegions(state.brand).contains(state.region)
        if (beta) {
            ErrorBox(
                headline = "${state.brand.displayName} ${state.region.displayName} is in BETA",
                detail = "If you experience issues, please report them on the BetterBlueKit GitHub page.",
                color = MaterialTheme.colorScheme.primary,
                icon = Icons.Filled.Build,
            )
        } else if (!supported) {
            ErrorBox(
                headline = "${state.brand.displayName} ${state.region.displayName} is unsupported.",
                detail =
                    "If you'd like to help bring BetterBlue to your region, please consider " +
                        "contributing to the open source project.",
                color = MaterialTheme.colorScheme.tertiary,
                icon = Icons.Filled.Warning,
            )
        }
    }
}

@Composable
private fun AccountInformationSection(state: AddAccountUiState, viewModel: AddAccountViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionHeader("Account Information")
        Spacer(Modifier.width(8.dp))
        // Only Hyundai Europe offers refresh-token auth.
        if (state.brand == Brand.HYUNDAI && state.region == Region.EUROPE) {
            var showTokenInfo by remember { mutableStateOf(false) }
            TextButton(
                onClick = {
                    if (state.useToken) {
                        // Switching back to password is the common case.
                        viewModel.setUseToken(false)
                    } else {
                        // Switching INTO refresh-token mode gets an explainer first.
                        showTokenInfo = true
                    }
                },
            ) {
                Text(if (state.useToken) "Use Password" else "Use Refresh Token")
            }
            if (showTokenInfo) {
                AlertDialog(
                    onDismissRequest = { showTokenInfo = false },
                    title = { Text("Refresh Token Sign-In") },
                    text = {
                        Text(
                            "A refresh token is a long-lived credential generated outside the app " +
                                "(for example with the Hyundai developer portal). Most users should " +
                                "sign in with their password instead.",
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.setUseToken(true)
                            showTokenInfo = false
                        }) { Text("Use Refresh Token") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showTokenInfo = false }) { Text("Cancel") }
                    },
                )
            }
        }
    }

    OutlinedTextField(
        value = state.username,
        onValueChange = viewModel::setUsername,
        label = { Text("Username") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
    )

    if (state.useToken) {
        OutlinedTextField(
            value = state.refreshToken,
            onValueChange = viewModel::setRefreshToken,
            label = { Text("Refresh Token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::setPassword,
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    // BetterBlueKit owns the matrix of which brand/region combinations
    // require a PIN (Hyundai USA/CA/EU + Kia EU today).
    if (state.requiresPinForSelection) {
        OutlinedTextField(
            value = state.pin,
            onValueChange = viewModel::setPin,
            label = { Text("PIN") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    val footer =
        if (state.brand == Brand.FAKE) {
            "Using test account - fake data will be used"
        } else {
            "BetterBlue requires an active Hyundai BlueLink or Kia Connect subscription. " +
                "Credentials are stored encrypted on this device. BetterBlue is fully open source: " +
                "github.com/schmidtwmark/BetterBlue"
        }
    Text(footer, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MfaFlowSheet(state: MfaUiState, viewModel: AddAccountViewModel) {
    ModalBottomSheet(onDismissRequest = viewModel::cancelMfa) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state.step) {
                MfaStep.METHOD_PICKER -> {
                    Text("Verify Account", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Your account requires verification. Select where to send the code.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.phone?.let { phone ->
                        ListItem(
                            headlineContent = { Text("Text Message (SMS)") },
                            supportingContent = { Text(phone) },
                            leadingContent = { Icon(Icons.Filled.Sms, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth(),
                            tonalElevation = 2.dp,
                        )
                        TextButton(onClick = { viewModel.sendMfaCode("SMS") }) { Text("Send by SMS") }
                    }
                    state.email?.let { email ->
                        ListItem(
                            headlineContent = { Text("Email") },
                            supportingContent = { Text(email) },
                            leadingContent = { Icon(Icons.Filled.Email, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth(),
                            tonalElevation = 2.dp,
                        )
                        TextButton(onClick = { viewModel.sendMfaCode("EMAIL") }) { Text("Send by Email") }
                    }
                    TextButton(onClick = viewModel::cancelMfa) { Text("Cancel") }
                }

                MfaStep.VERIFICATION -> {
                    Text("Enter Code", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Please enter the verification code sent via ${state.deliveryDescription}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = state.code,
                        onValueChange = viewModel::setMfaCode,
                        label = { Text("Verification Code") },
                        singleLine = true,
                        enabled = !state.isVerifying,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                state.notifyType?.let { viewModel.sendMfaCode(it, isResend = true) }
                            },
                            enabled = state.notifyType != null && !state.isResendingCode && !state.isVerifying,
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Resend Code")
                            if (state.isResendingCode) {
                                Spacer(Modifier.width(8.dp))
                                CircularProgressIndicator(modifier = Modifier.width(16.dp))
                            }
                        }
                        if (state.canChangeMethod) {
                            TextButton(onClick = viewModel::backToMethodPicker) { Text("Change Method") }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(onClick = viewModel::cancelMfa) { Text("Cancel") }
                        Button(
                            onClick = viewModel::verifyMfa,
                            enabled = state.code.isNotEmpty() && !state.isVerifying,
                        ) {
                            if (state.isVerifying) {
                                CircularProgressIndicator(modifier = Modifier.width(16.dp))
                            } else {
                                Text("Verify")
                            }
                        }
                    }
                }

                MfaStep.HIDDEN -> {
                    Unit
                }
            }

            state.actionError?.let { ErrorDetailsCard(it) }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
    label: String,
    value: String,
    options: List<String>,
    onSelected: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelected(index)
                        expanded = false
                    },
                )
            }
        }
    }
}
