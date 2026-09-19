package com.example.emoo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.emoo.ui.gallery.GalleryScreen
import com.example.emoo.ui.gallery.GalleryViewModel
import com.example.emoo.ui.gallery.ImageViewerScreen
import com.example.emoo.ui.imports.ImportScreen
import com.example.emoo.ui.settings.SettingsScreen
import com.example.emoo.ui.settings.SettingsViewModel
import kotlinx.coroutines.launch

/** 底部三个 Tab 对应的 Pager 页序号 */
private object Tabs {
    const val GALLERY = 0
    const val IMPORT = 1
    const val SETTINGS = 2
    const val PAGE_COUNT = 3
}

/**
 * 应用主骨架：纵向 9:1 划分——上部 9/10 为内容区（HorizontalPager 左右滑动切换
 * 图片 / 导入 / 设置三页，三页常驻组合，切换无重组闪烁且滚动位置不丢失），
 * 下部 1/10 为主导航栏。点击 Tab 与滑动页面双向联动；大图浏览页为全屏覆盖层。
 */
@Composable
fun EMOOApp(
    settingsViewModel: SettingsViewModel,
    galleryViewModel: GalleryViewModel
) {
    // 设置页修改每行列数后即时同步到图片页（Tab 切换不触发 ON_RESUME）
    val gridColumns by settingsViewModel.gridColumns.collectAsStateWithLifecycle()
    LaunchedEffect(gridColumns) { galleryViewModel.setGridColumns(gridColumns) }

    // 设置页清理/撤销“最近”后即时刷新图片页
    val recentVersion by settingsViewModel.recentVersion.collectAsStateWithLifecycle()
    LaunchedEffect(recentVersion) {
        if (recentVersion > 0) galleryViewModel.refresh()
    }

    val pagerState = rememberPagerState(initialPage = Tabs.GALLERY) { Tabs.PAGE_COUNT }
    val scope = rememberCoroutineScope()
    /** 非空时显示全屏大图浏览覆盖层 */
    var viewerIndex by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // 上部内容区：viewer 打开时占满全屏，否则 9/10
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(if (viewerIndex == null) 9f else 10f)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    Tabs.GALLERY -> GalleryScreen(
                        viewModel = galleryViewModel,
                        onNavigateToImport = {
                            scope.launch { pagerState.animateScrollToPage(Tabs.IMPORT) }
                        },
                        onOpenImage = { viewerIndex = it }
                    )

                    Tabs.IMPORT -> ImportScreen()
                    else -> SettingsScreen(settingsViewModel = settingsViewModel)
                }
            }

            // 全屏大图浏览覆盖层（保持 Pager 与画廊状态不被销毁）
            viewerIndex?.let { index ->
                ImageViewerScreen(
                    viewModel = galleryViewModel,
                    initialIndex = index,
                    onBack = { viewerIndex = null }
                )
            }
        }

        if (viewerIndex == null) {
            BottomBar(
                currentPage = pagerState.currentPage,
                modifier = Modifier.weight(1f),
                onTabSelected = { page ->
                    scope.launch { pagerState.animateScrollToPage(page) }
                }
            )
        }
    }
}

/** 主导航栏：三个大按钮，当前选中项高亮；随 Pager 滑动同步高亮位置 */
@Composable
private fun BottomBar(
    currentPage: Int,
    modifier: Modifier = Modifier,
    onTabSelected: (Int) -> Unit
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            BottomBarItem(
                page = Tabs.GALLERY,
                label = "图片",
                selectedIcon = Icons.Filled.PhotoLibrary,
                unselectedIcon = Icons.Outlined.PhotoLibrary,
                selected = currentPage == Tabs.GALLERY,
                modifier = Modifier.weight(1f),
                onTabSelected = onTabSelected
            )
            BottomBarItem(
                page = Tabs.IMPORT,
                label = "导入",
                selectedIcon = Icons.Filled.AddCircle,
                unselectedIcon = Icons.Outlined.AddCircle,
                selected = currentPage == Tabs.IMPORT,
                modifier = Modifier.weight(1f),
                onTabSelected = onTabSelected
            )
            BottomBarItem(
                page = Tabs.SETTINGS,
                label = "设置",
                selectedIcon = Icons.Filled.Settings,
                unselectedIcon = Icons.Outlined.Settings,
                selected = currentPage == Tabs.SETTINGS,
                modifier = Modifier.weight(1f),
                onTabSelected = onTabSelected
            )
        }
    }
}

@Composable
private fun RowScope.BottomBarItem(
    page: Int,
    label: String,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onTabSelected: (Int) -> Unit
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        Color.Transparent
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(containerColor)
            .fillMaxHeight()
            .clickable { onTabSelected(page) },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = if (selected) selectedIcon else unselectedIcon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.padding(bottom = 1.dp)
        )
        Text(
            text = label,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            color = contentColor,
            maxLines = 1
        )
    }
}
