package dev.cannoli.scorza.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.navigation.OnboardingPermission
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.START_GLYPH
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.components.footerReservation
import dev.cannoli.ui.components.screenPadding
import dev.cannoli.ui.theme.CannoliTypography
import dev.cannoli.ui.theme.GrayText
import dev.cannoli.ui.theme.LocalCannoliColors
import dev.cannoli.ui.theme.LocalCannoliTypography
import dev.cannoli.ui.theme.Spacing

private val GrantedGreen = Color(0xFF4CAF50)

@Composable
fun OnboardingPermissionsScreen(
    permissions: List<OnboardingPermission>,
    granted: Set<OnboardingPermission>,
    selectedIndex: Int,
    buttonStyle: ButtonStyle = ButtonStyle(),
) {
    val typo = LocalCannoliTypography.current
    val colors = LocalCannoliColors.current
    val accent = colors.accent
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
                    label = stringResource(
                        if (perm == OnboardingPermission.STORAGE) R.string.onboarding_storage_label
                        else R.string.onboarding_bluetooth_label
                    ),
                    rationale = stringResource(
                        if (perm == OnboardingPermission.STORAGE) R.string.onboarding_storage_rationale
                        else R.string.onboarding_bluetooth_rationale
                    ),
                    isGranted = perm in granted,
                    isFocused = index == selectedIndex,
                    accent = accent,
                    typo = typo,
                )
            }
        }

        val leftItems = listOf(buttonStyle.back to stringResource(R.string.label_quit))
        val rightItems = mutableListOf<Pair<String, String>>()
        val focused = permissions.getOrNull(selectedIndex)
        if (focused != null && focused !in granted) {
            rightItems.add(buttonStyle.confirm to stringResource(R.string.label_grant))
        }
        if (granted.containsAll(permissions)) {
            rightItems.add(START_GLYPH to stringResource(R.string.label_continue))
        }
        BottomBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            leftItems = leftItems,
            rightItems = rightItems,
        )
    }
}

@Composable
private fun PermissionCard(
    label: String,
    rationale: String,
    isGranted: Boolean,
    isFocused: Boolean,
    accent: Color,
    typo: CannoliTypography,
) {
    val borderColor = if (isFocused) accent else Color(0xFF333333)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
    ) {
        PermissionStatusRow(typo = typo, label = label, isGranted = isGranted)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = rationale, style = typo.bodyMedium, color = GrayText)
        if (isFocused && !isGranted) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = stringResource(R.string.onboarding_press_a_to_grant), style = typo.bodyMedium, color = accent)
        }
    }
}

@Composable
private fun PermissionStatusRow(typo: CannoliTypography, label: String, isGranted: Boolean) {
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
