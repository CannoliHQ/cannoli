package dev.cannoli.scorza.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.navigation.OnboardingPermission
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.DPAD_HORIZONTAL
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.components.footerReservation
import dev.cannoli.ui.components.screenPadding
import dev.cannoli.ui.theme.CannoliTypography
import dev.cannoli.ui.theme.GrayText
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliTypography
import dev.cannoli.ui.theme.Spacing

private val GrantedGreen = Color(0xFF4CAF50)
private val UnfocusedBorder = Color(0xFF333333)

@Composable
fun OnboardingPermissionsScreen(
    permissions: List<OnboardingPermission>,
    granted: Set<OnboardingPermission>,
    volumes: List<Pair<String, String>>,
    volumeIndex: Int,
    customPath: String?,
    selectedIndex: Int,
    existingInstallVolumeIndex: Int? = null,
    buttonStyle: ButtonStyle = ButtonStyle(),
) {
    val typo = LocalCannoliTypography.current
    val colors = LocalCannoliColors.current
    val accent = colors.accent
    val allGranted = granted.containsAll(permissions)
    val storageRowIndex = permissions.size
    val isStorageRowFocused = allGranted && selectedIndex == storageRowIndex
    val selectedVolume = volumes.getOrNull(volumeIndex)
    val isCustom = selectedVolume?.first == "Custom"
    val continueEnabled = allGranted && volumes.isNotEmpty() && (!isCustom || customPath != null)
    val isContinueFocused = continueEnabled && selectedIndex == storageRowIndex + 1
    val isExistingInstall = existingInstallVolumeIndex != null && volumeIndex == existingInstallVolumeIndex
    val existingFolderPath = if (isExistingInstall) selectedVolume?.second?.plus("Cannoli/") else null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(screenPadding)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = footerReservation())
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.onboarding_header),
                style = typo.labelSmall,
                color = colors.text.copy(alpha = 0.6f),
            )
            Spacer(modifier = Modifier.height(Spacing.Sm))
            Text(
                text = stringResource(R.string.onboarding_permissions_title),
                style = typo.titleLarge,
                color = Color.White,
            )
            Spacer(modifier = Modifier.height(Spacing.Lg))

            permissions.forEachIndexed { index, perm ->
                if (index > 0) Spacer(modifier = Modifier.height(Spacing.Md))
                PermissionCard(
                    label = stringResource(R.string.onboarding_storage_label),
                    rationale = stringResource(R.string.onboarding_storage_rationale),
                    isGranted = perm in granted,
                    isFocused = index == selectedIndex,
                    accent = accent,
                    typo = typo,
                )
            }

            Spacer(modifier = Modifier.height(Spacing.Md))

            StorageCard(
                value = when {
                    isCustom && customPath != null -> customPath
                    isCustom -> stringResource(R.string.setup_folder_unset)
                    else -> selectedVolume?.first ?: stringResource(R.string.setup_folder_unset)
                },
                isLocked = !allGranted,
                isFocused = isStorageRowFocused,
                customPickPrompt = isStorageRowFocused && isCustom && customPath == null,
                existingFolderPath = existingFolderPath,
                accent = accent,
                typo = typo,
            )

            Spacer(modifier = Modifier.height(Spacing.Md))

            ContinueCard(
                isEnabled = continueEnabled,
                isFocused = isContinueFocused,
                accent = accent,
                typo = typo,
            )
        }

        val leftItems = mutableListOf(buttonStyle.back to stringResource(R.string.label_quit))
        if (isStorageRowFocused && volumes.size > 1) {
            leftItems.add(DPAD_HORIZONTAL to stringResource(R.string.label_change))
        }
        val rightItems = mutableListOf<Pair<String, String>>()
        val focusedPerm = permissions.getOrNull(selectedIndex)
        if (focusedPerm != null && focusedPerm !in granted) {
            rightItems.add(buttonStyle.confirm to stringResource(R.string.label_grant))
        }
        if (isStorageRowFocused && isCustom && customPath == null) {
            rightItems.add(buttonStyle.confirm to stringResource(R.string.onboarding_select_folder))
        }
        if (isContinueFocused) {
            rightItems.add(buttonStyle.confirm to stringResource(R.string.label_continue))
        }
        BottomBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            leftItems = leftItems,
            rightItems = rightItems,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PermissionCard(
    label: String,
    rationale: String,
    isGranted: Boolean,
    isFocused: Boolean,
    accent: Color,
    typo: CannoliTypography,
) {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(isFocused) { if (isFocused) requester.bringIntoView() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
            .border(2.dp, if (isFocused) accent else UnfocusedBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
    ) {
        StatusRow(typo = typo, label = label, isGranted = isGranted)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = rationale, style = typo.bodyMedium, color = GrayText)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StorageCard(
    value: String,
    isLocked: Boolean,
    isFocused: Boolean,
    customPickPrompt: Boolean,
    existingFolderPath: String?,
    accent: Color,
    typo: CannoliTypography,
) {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(isFocused) { if (isFocused) requester.bringIntoView() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (isLocked) 0.35f else 1f)
            .bringIntoViewRequester(requester)
            .border(2.dp, if (isFocused) accent else UnfocusedBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = stringResource(R.string.onboarding_location_label), style = typo.bodyLarge, color = Color.White)
            Text(text = value, style = typo.bodyMedium, color = GrayText)
        }
        if (isLocked) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = stringResource(R.string.onboarding_storage_locked_hint), style = typo.bodyMedium, color = GrayText)
        } else if (customPickPrompt) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = stringResource(R.string.onboarding_press_a_to_select_folder), style = typo.bodyMedium, color = accent)
        } else if (existingFolderPath != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = existingFolderPath, style = typo.bodyMedium, color = GrantedGreen)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueCard(
    isEnabled: Boolean,
    isFocused: Boolean,
    accent: Color,
    typo: CannoliTypography,
) {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(isFocused) { if (isFocused) requester.bringIntoView() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (isEnabled) 1f else 0.35f)
            .bringIntoViewRequester(requester)
            .border(2.dp, if (isFocused) accent else UnfocusedBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.label_continue),
            style = typo.bodyMedium,
            color = if (isFocused) accent else Color.White,
        )
    }
}

@Composable
private fun StatusRow(typo: CannoliTypography, label: String, isGranted: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = typo.bodyLarge, color = Color.White)
        Text(
            text = if (isGranted) stringResource(R.string.onboarding_status_granted)
            else stringResource(R.string.onboarding_status_not_granted),
            style = typo.bodyMedium,
            color = if (isGranted) GrantedGreen else GrayText,
        )
    }
}
