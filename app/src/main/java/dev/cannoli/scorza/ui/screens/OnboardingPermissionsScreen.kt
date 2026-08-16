package dev.cannoli.scorza.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.onboarding.OnboardingPermission
import dev.cannoli.scorza.onboarding.OnboardingPermissionsAction
import dev.cannoli.scorza.onboarding.OnboardingStep
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.theme.Spacing

@Composable
fun OnboardingPermissionsScreen(
    screen: LauncherScreen.OnboardingPermissions,
    listFontSize: TextUnit = 22.sp,
    listLineHeight: TextUnit = 32.sp,
    listVerticalPadding: Dp = 8.dp,
    buttonStyle: ButtonStyle = ButtonStyle(),
) {
    // One contextual action, read from the same value the handler runs.
    val actionLabel = when (screen.action) {
        OnboardingPermissionsAction.GRANT -> stringResource(R.string.label_grant)
        OnboardingPermissionsAction.CONTINUE -> stringResource(R.string.label_continue)
        OnboardingPermissionsAction.NONE -> null
    }

    OnboardingScaffold(
        step = OnboardingStep.PERMISSIONS,
        title = stringResource(R.string.onboarding_permissions_title),
        listFontSize = listFontSize,
        listLineHeight = listLineHeight,
        leftItems = listOf(buttonStyle.back to stringResource(R.string.label_back)),
        rightItems = actionLabel?.let { listOf(buttonStyle.confirm to it) } ?: emptyList(),
    ) {
        screen.permissions.forEachIndexed { index, permission ->
            if (index > 0) Spacer(modifier = Modifier.height(Spacing.Md))
            PermissionRow(
                permission = permission,
                isGranted = permission in screen.granted,
                isSelected = index == screen.selectedIndex,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                listVerticalPadding = listVerticalPadding,
            )
        }
    }
}

@Composable
private fun PermissionRow(
    permission: OnboardingPermission,
    isGranted: Boolean,
    isSelected: Boolean,
    listFontSize: TextUnit,
    listLineHeight: TextUnit,
    listVerticalPadding: Dp,
) {
    val explanation = stringResource(permission.appPermission.explanationRes)
    Column(modifier = Modifier.fillMaxWidth().revealWhenFocused(isSelected)) {
        PillRowKeyValue(
            label = stringResource(permission.appPermission.labelRes),
            value = if (isGranted) {
                stringResource(R.string.permission_state_granted)
            } else {
                stringResource(R.string.permission_state_not_granted)
            },
            isSelected = isSelected,
            fontSize = listFontSize,
            lineHeight = listLineHeight,
            verticalPadding = listVerticalPadding,
        )
        OnboardingBodyText(
            if (permission.required) {
                stringResource(R.string.onboarding_permission_required, explanation)
            } else {
                stringResource(R.string.onboarding_permission_optional, explanation)
            }
        )
    }
}
