package com.vibetoon.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import com.vibetoon.domain.ui.TabIndex

/**
 * Спринт 54: строки вкладок без вылета при смене числа вкладок. Индикатор берёт позицию через
 * [TabIndex.positionOrNull]: если позиций ещё старое число, индикатор просто пропускает один кадр.
 */
@Composable
fun SafeScrollableTabRow(selectedTabIndex: Int, tabCount: Int, modifier: Modifier = Modifier, edgePadding: Dp = 0.dp, tabs: @Composable () -> Unit) {
    val index = TabIndex.clamp(selectedTabIndex, tabCount)
    ScrollableTabRow(
        selectedTabIndex = index, modifier = modifier, edgePadding = edgePadding,
        indicator = { positions -> TabIndex.positionOrNull(positions, index)?.let { TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(it)) } },
        tabs = tabs
    )
}

@Composable
fun SafeTabRow(selectedTabIndex: Int, tabCount: Int, modifier: Modifier = Modifier, tabs: @Composable () -> Unit) {
    val index = TabIndex.clamp(selectedTabIndex, tabCount)
    TabRow(
        selectedTabIndex = index, modifier = modifier,
        indicator = { positions -> TabIndex.positionOrNull(positions, index)?.let { TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(it)) } },
        tabs = tabs
    )
}
