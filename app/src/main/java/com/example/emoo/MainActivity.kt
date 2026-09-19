package com.example.emoo

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import com.example.emoo.model.ThemeMode
import com.example.emoo.send.ImageSender
import com.example.emoo.ui.EMOOApp
import com.example.emoo.ui.gallery.GalleryViewModel
import com.example.emoo.ui.settings.SettingsViewModel
import com.example.emoo.ui.theme.EMOOTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MultiWindowState.isInMultiWindow = isInMultiWindowMode

        // 清理上次发送中断遗留的 Download 临时文件
        lifecycleScope.launch {
            ImageSender.cleanupStaleStaged(applicationContext)
        }

        setContent {
            // 全局 ImageLoader：注册 GIF 解码器（API 28+ 用系统 ImageDecoder，26-27 用 GifDecoder）
            // （Coil 3 的该工厂为 @Composable，需在组合内设置）
            setSingletonImageLoaderFactory { context -> buildEmooImageLoader(context) }

            // Activity 作用域：主题全局生效，图片页与大图页共享同一份画廊状态
            val settingsViewModel: SettingsViewModel = viewModel()
            val galleryViewModel: GalleryViewModel = viewModel()
            val themeMode by settingsViewModel.themeMode.collectAsStateWithLifecycle()

            // 应用内切换主题时即时更新状态栏/导航栏图标深浅
            // （enableEdgeToEdge 仅在启动时按系统 uiMode 决定，不会跟随应用内切换）
            val darkTheme = themeMode == ThemeMode.DARK ||
                (themeMode == ThemeMode.FOLLOW_SYSTEM && isSystemInDarkTheme())
            val activity = LocalContext.current as? ComponentActivity
            LaunchedEffect(darkTheme) {
                val scrim = android.graphics.Color.TRANSPARENT
                activity?.enableEdgeToEdge(
                    statusBarStyle = if (darkTheme) {
                        SystemBarStyle.dark(scrim)
                    } else {
                        SystemBarStyle.light(scrim, scrim)
                    },
                    navigationBarStyle = if (darkTheme) {
                        SystemBarStyle.dark(scrim)
                    } else {
                        SystemBarStyle.light(scrim, scrim)
                    }
                )
            }

            EMOOTheme(themeMode = themeMode) {
                EMOOApp(
                    settingsViewModel = settingsViewModel,
                    galleryViewModel = galleryViewModel
                )
            }
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        MultiWindowState.isInMultiWindow = isInMultiWindowMode
    }
}

/** 图片加载器：GIF 实时循环播放 + 内存缓存（20%）+ 默认磁盘缓存 */
private fun buildEmooImageLoader(context: Context): ImageLoader =
    ImageLoader.Builder(context)
        .components {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(AnimatedImageDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
        }
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(context, 0.20)
                .build()
        }
        .build()
