package dev.cannoli.scorza.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.permissions.PermissionGroup
import dev.cannoli.scorza.permissions.PermissionState
import dev.cannoli.scorza.permissions.visiblePermissions
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.ui.components.ListSection
import dev.cannoli.ui.components.PillRowKeyValue
import dev.cannoli.ui.components.ScreenBackground
import dev.cannoli.ui.components.ScreenTitle
import dev.cannoli.ui.components.SectionedList
import dev.cannoli.ui.components.footerReservation
import dev.cannoli.ui.components.listTitleSpacing
import dev.cannoli.ui.components.pillItemHeight
import dev.cannoli.ui.components.screenInsets

@Composable
fun PermissionsScreen(
    screen: LauncherScreen.Permissions,
    modifier: Modifier = Modifier,
    backgroundImagePath: String? = null,
    backgroundTint: Int = 0,
    listFontSize: TextUnit = 22.sp,
    listLineHeight: TextUnit = 32.sp,
    listVerticalPadding: Dp = 8.dp,
    buttonStyle: ButtonStyle = ButtonStyle(),
) {
    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)
    val visible = visiblePermissions(screen.states)
    val sections = listOf(
        PermissionGroup.ACTIONABLE to R.string.permissions_section_actionable,
        PermissionGroup.VERSION_GATED to R.string.permissions_section_version,
        PermissionGroup.ALWAYS_ON to R.string.permissions_section_always_on,
    ).mapNotNull { (group, headerRes) ->
        val items = visible.filter { it.group == group }
        if (items.isEmpty()) null else ListSection(header = stringResource(headerRes), items = items)
    }

    ScreenBackground(backgroundImagePath = backgroundImagePath, backgroundTint = backgroundTint) {
        Box(modifier = modifier.fillMaxSize().padding(screenInsets())) {
            Column(modifier = Modifier.fillMaxSize().padding(bottom = footerReservation())) {
                ScreenTitle(
                    text = stringResource(R.string.permissions_title),
                    fontSize = listFontSize,
                    lineHeight = listLineHeight,
                )
                Spacer(modifier = Modifier.height(listTitleSpacing()))
                SectionedList(
                    sections = sections,
                    selectedIndex = screen.selectedIndex.coerceIn(0, (visible.size - 1).coerceAtLeast(0)),
                    fontSize = listFontSize,
                    lineHeight = listLineHeight,
                    verticalPadding = listVerticalPadding,
                    itemHeight = itemHeight,
                    scrollTarget = screen.scrollTarget,
                ) { _, permission, isSelected ->
                    PillRowKeyValue(
                        label = stringResource(permission.labelRes),
                        value = stateLabel(screen.states[permission]),
                        isSelected = isSelected,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding,
                    )
                }
            }
            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = listOf(buttonStyle.back to stringResource(R.string.label_back)),
                rightItems = listOf(buttonStyle.confirm to stringResource(R.string.label_select)),
            )
        }
    }
}

@Composable
private fun stateLabel(state: PermissionState?): String = when (state) {
    PermissionState.GRANTED -> stringResource(R.string.permission_state_granted)
    PermissionState.NOT_REQUIRED -> stringResource(R.string.permission_state_not_required)
    PermissionState.NOT_GRANTED, null -> stringResource(R.string.permission_state_not_granted)
}
