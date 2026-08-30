package com.betterblue.app.ui.screens.addaccount

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betterblue.app.data.repo.AccountRepository
import com.betterblue.app.ui.common.ActionError
import com.betterblue.kit.ApiErrorType
import com.betterblue.kit.ApiException
import com.betterblue.kit.HyundaiCanadaVariant
import com.betterblue.kit.MfaMethod
import com.betterblue.kit.log.BBLogCategory
import com.betterblue.kit.log.BBLogger
import com.betterblue.kit.model.Brand
import com.betterblue.kit.model.Region
import com.betterblue.kit.model.isTestAccount
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** Where the MFA sheet currently is. */
enum class MfaStep { HIDDEN, METHOD_PICKER, VERIFICATION }

data class MfaUiState(
    val step: MfaStep = MfaStep.HIDDEN,
    val xid: String? = null,
    val otpKey: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val notifyType: String? = null,
    val code: String = "",
    val isResendingCode: Boolean = false,
    val isVerifying: Boolean = false,
    val actionError: ActionError? = null,
) {
    val canChangeMethod: Boolean get() = email != null && phone != null

    val deliveryDescription: String
        get() =
            when {
                notifyType == "SMS" && phone != null -> "text message to $phone"
                notifyType == "EMAIL" && email != null -> "email to $email"
                else -> "email or phone"
            }
}

data class AddAccountUiState(
    val username: String = "",
    val password: String = "",
    val refreshToken: String = "",
    val pin: String = "",
    val brand: Brand = Brand.HYUNDAI,
    val region: Region = Region.USA,
    val hyundaiCanadaVariant: HyundaiCanadaVariant = HyundaiCanadaVariant.DEFAULT,
    /** Hyundai Europe only: sign in with a pre-generated refresh token. */
    val useToken: Boolean = false,
    val isLoading: Boolean = false,
    val saveError: ActionError? = null,
    val mfa: MfaUiState = MfaUiState(),
    val finished: Boolean = false,
) {
    val isTestAccount: Boolean get() = isTestAccount(username, password)
    val availableBrands: List<Brand> get() = Brand.availableBrands(username, password)

    val canSubmit: Boolean
        get() =
            username.isNotEmpty() &&
                !(password.isEmpty() && refreshToken.isEmpty()) &&
                !(brand != Brand.KIA && brand != Brand.FAKE && pin.isEmpty() && requiresPinForSelection) &&
                !isLoading

    val requiresPinForSelection: Boolean
        get() = com.betterblue.kit.requiresPin(brand, region)
}

@HiltViewModel
class AddAccountViewModel
    @Inject
    constructor(
        private val accounts: AccountRepository,
    ) : ViewModel() {
        private val _state = MutableStateFlow(AddAccountUiState())
        val state: StateFlow<AddAccountUiState> = _state.asStateFlow()

        private var pendingAccountId: String? = null

        fun setUsername(value: String) = _state.update { it.copy(username = value) }

        fun setPassword(value: String) = _state.update { it.copy(password = value) }

        fun setRefreshToken(value: String) = _state.update { it.copy(refreshToken = value) }

        fun setPin(value: String) = _state.update { it.copy(pin = value) }

        fun setRegion(value: Region) = _state.update { it.copy(region = value) }

        fun setUseToken(value: Boolean) = _state.update { it.copy(useToken = value) }

        fun setHyundaiCanadaVariant(value: HyundaiCanadaVariant) =
            _state.update { it.copy(hyundaiCanadaVariant = value) }

        fun setBrand(value: Brand) =
            _state.update { state ->
                var next = state.copy(brand = value)
                if (value == Brand.FAKE && state.username.isEmpty() && state.password.isEmpty()) {
                    next =
                        next.copy(
                            username = "fake-${UUID.randomUUID().toString().take(8).lowercase()}@betterblue.com",
                            password = "betterblue",
                        )
                }
                next
            }

        fun addAccount() {
            val snapshot = _state.value
            _state.update { it.copy(isLoading = true, saveError = null) }

            viewModelScope.launch {
                val entity =
                    try {
                        accounts.addAccount(
                            username = snapshot.username,
                            password = snapshot.password,
                            pin = snapshot.pin,
                            refreshToken = snapshot.refreshToken.takeUnless { it.isEmpty() },
                            brand = snapshot.brand,
                            region = snapshot.region,
                            hyundaiCanadaVariant = snapshot.hyundaiCanadaVariant,
                        )
                    } catch (e: Exception) {
                        _state.update {
                            it.copy(isLoading = false, saveError = ActionError("Save account", e))
                        }
                        return@launch
                    }
                pendingAccountId = entity.id

                try {
                    accounts.initialize(entity.id)
                    finishAccount(entity.id)
                } catch (e: ApiException) {
                    if (e.errorType == ApiErrorType.REQUIRES_MFA) {
                        // MFA is handled out-of-band via the sheet, not as an error.
                        startMfa(e)
                    } else {
                        // Sign-in failed: remove the just-saved account so retries
                        // start clean.
                        accounts.removeAccount(entity.id)
                        pendingAccountId = null
                        _state.update {
                            it.copy(
                                isLoading = false,
                                saveError = ActionError("Sign in to ${snapshot.brand.displayName}", e, entity.id),
                            )
                        }
                    }
                } catch (e: Exception) {
                    accounts.removeAccount(entity.id)
                    pendingAccountId = null
                    _state.update {
                        it.copy(
                            isLoading = false,
                            saveError = ActionError("Sign in to ${snapshot.brand.displayName}", e, entity.id),
                        )
                    }
                }
            }
        }

        private suspend fun finishAccount(accountId: String) {
            try {
                accounts.loadVehicles(accountId)
                _state.update { it.copy(isLoading = false, finished = true) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(isLoading = false, saveError = ActionError("Load vehicles", e, accountId))
                }
            }
        }

        // MFA flow (port of the iOS MFAFlowState)

        private fun startMfa(error: ApiException) {
            val info = error.userInfo
            if (info == null) {
                BBLogger.error(BBLogCategory.MFA, "MFA error missing userInfo: $error")
                _state.update { it.copy(isLoading = false) }
                return
            }
            val email = info["email"]
            val phone = info["phone"]
            _state.update {
                it.copy(
                    mfa =
                        MfaUiState(
                            step = MfaStep.HIDDEN,
                            xid = info["xid"],
                            otpKey = info["otpKey"],
                            email = email,
                            phone = phone,
                        ),
                )
            }

            BBLogger.info(BBLogCategory.MFA, "MFA flow started - email: $email, phone: $phone")
            when {
                phone != null && email == null -> {
                    sendMfaCode("SMS", showPickerFirst = false)
                }

                email != null && phone == null -> {
                    sendMfaCode("EMAIL", showPickerFirst = false)
                }

                phone != null || email != null -> {
                    _state.update { it.copy(mfa = it.mfa.copy(step = MfaStep.METHOD_PICKER)) }
                }

                else -> {
                    BBLogger.error(BBLogCategory.MFA, "MFA required but no contact options available")
                    _state.update { it.copy(isLoading = false) }
                }
            }
        }

        fun sendMfaCode(notifyType: String, isResend: Boolean = false, showPickerFirst: Boolean = true) {
            // `otpKey` is intentionally NOT required: Kia USA returns one in the
            // initial challenge, but Hyundai Canada only issues one AFTER sendotp
            // runs. Per-region clients ignore the value when they don't need it.
            val accountId = pendingAccountId
            val mfa = _state.value.mfa
            val xid = mfa.xid
            if (accountId == null || xid == null) {
                _state.update {
                    it.copy(
                        mfa =
                            it.mfa.copy(
                                actionError = ActionError("Start verification", ApiException("MFA context missing")),
                            ),
                    )
                }
                return
            }

            if (isResend) _state.update { it.copy(mfa = it.mfa.copy(isResendingCode = true)) }

            viewModelScope.launch {
                val method = if (notifyType == "EMAIL") MfaMethod.EMAIL else MfaMethod.SMS
                try {
                    accounts.sendMfa(accountId, otpKey = mfa.otpKey ?: "", xid = xid, method = method)
                    _state.update {
                        it.copy(
                            mfa =
                                it.mfa.copy(
                                    notifyType = notifyType,
                                    isResendingCode = false,
                                    actionError = null,
                                    step = MfaStep.VERIFICATION,
                                ),
                        )
                    }
                } catch (e: Exception) {
                    val actionName =
                        if (method == MfaMethod.EMAIL) {
                            "Send verification code by email"
                        } else {
                            "Send verification code by SMS"
                        }
                    _state.update {
                        it.copy(
                            mfa =
                                it.mfa.copy(
                                    isResendingCode = false,
                                    actionError = ActionError(actionName, e, accountId),
                                    // If the sheet isn't visible yet, show it with the error.
                                    step = if (it.mfa.step == MfaStep.HIDDEN) MfaStep.METHOD_PICKER else it.mfa.step,
                                ),
                        )
                    }
                }
            }
        }

        fun setMfaCode(code: String) = _state.update { it.copy(mfa = it.mfa.copy(code = code)) }

        fun verifyMfa() {
            val accountId = pendingAccountId ?: return
            val mfa = _state.value.mfa
            val xid = mfa.xid ?: return

            _state.update { it.copy(mfa = it.mfa.copy(isVerifying = true, actionError = null)) }

            viewModelScope.launch {
                try {
                    accounts.verifyMfa(accountId, otpKey = mfa.otpKey ?: "", xid = xid, otp = mfa.code)
                    _state.update { it.copy(mfa = MfaUiState()) }
                    finishAccount(accountId)
                } catch (e: Exception) {
                    _state.update {
                        it.copy(
                            mfa =
                                it.mfa.copy(
                                    isVerifying = false,
                                    actionError = ActionError("Verify code", e, accountId),
                                ),
                        )
                    }
                }
            }
        }

        fun cancelMfa() {
            // Drop the half-created account; the user can retry cleanly.
            pendingAccountId?.let { id ->
                viewModelScope.launch { accounts.removeAccount(id) }
            }
            pendingAccountId = null
            _state.update { it.copy(isLoading = false, mfa = MfaUiState()) }
        }

        fun backToMethodPicker() {
            _state.update { it.copy(mfa = it.mfa.copy(step = MfaStep.METHOD_PICKER, code = "")) }
        }
    }
