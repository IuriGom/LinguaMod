package com.linguamod.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.linguamod.app.data.ThemeCatalog
import com.linguamod.app.data.ThemeStore
import com.linguamod.app.ui.dictionary.DictionaryScreen
import com.linguamod.app.ui.home.HomeScreen
import com.linguamod.app.ui.lesson.LessonScreen
import com.linguamod.app.ui.profile.ProfileScreen
import com.linguamod.app.ui.theme.LinguaModTheme
import com.linguamod.app.ui.unit.UnitDetailScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

object Routes {
    const val HOME = "home"
    const val DICTIONARY = "dictionary"
    const val PROFILE = "profile"
    const val UNIT = "unit/{unit}"
    const val LESSON = "lesson/{unit}/{lesson}"
    const val CHECKPOINT = "checkpoint/{unit}"
    fun unit(n: Int) = "unit/$n"
    fun lesson(unit: Int, lesson: Int) = "lesson/$unit/$lesson"
    fun checkpoint(unit: Int) = "checkpoint/$unit"
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var themeStore: ThemeStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // purchased cosmetic theme applies app-wide (Stage 2B §6)
            val themeId by themeStore.activeTheme.collectAsState(initial = ThemeCatalog.DEFAULT.id)
            val spec = ThemeCatalog.byId(themeId)
            LinguaModTheme(accent = spec.accentArgb?.let { Color(it) }, altDark = spec.altDark) {
                LinguaModAppContent()
            }
        }
    }
}

@Composable
fun LinguaModAppContent() {
    val nav = rememberNavController()
        val backStack by nav.currentBackStackEntryAsState()
        val route = backStack?.destination?.route
        val tabs = listOf(
            Triple(Routes.HOME, "Home", Icons.Filled.Home),
            Triple(Routes.DICTIONARY, "Dictionary", Icons.Filled.List),
            Triple(Routes.PROFILE, "Profile", Icons.Filled.Person),
        )
        val showBar = route in tabs.map { it.first }
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                if (showBar) {
                    NavigationBar {
                        tabs.forEach { (r, label, icon) ->
                            NavigationBarItem(
                                selected = route == r,
                                onClick = {
                                    nav.navigate(r) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(icon, contentDescription = label) },
                                label = { Text(label) },
                                modifier = Modifier.testTag("tab_${r}"),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = nav,
                startDestination = Routes.HOME,
                modifier = Modifier.padding(padding),
            ) {
                composable(Routes.HOME) {
                    HomeScreen(onOpenUnit = { nav.navigate(Routes.unit(it)) })
                }
                composable(Routes.DICTIONARY) { DictionaryScreen() }
                composable(Routes.PROFILE) { ProfileScreen() }
                composable(
                    Routes.UNIT,
                    arguments = listOf(navArgument("unit") { type = NavType.IntType }),
                ) { entry ->
                    val unit = entry.arguments?.getInt("unit") ?: 1
                    UnitDetailScreen(
                        unitNumber = unit,
                        onOpenLesson = { lesson -> nav.navigate(Routes.lesson(unit, lesson)) },
                        onOpenCheckpoint = { nav.navigate(Routes.checkpoint(unit)) },
                        onBack = { nav.popBackStack() },
                    )
                }
                composable(
                    Routes.LESSON,
                    arguments = listOf(
                        navArgument("unit") { type = NavType.IntType },
                        navArgument("lesson") { type = NavType.IntType },
                    ),
                ) {
                    LessonScreen(isCheckpoint = false, onDone = { nav.popBackStack() })
                }
                composable(
                    Routes.CHECKPOINT,
                    arguments = listOf(navArgument("unit") { type = NavType.IntType }),
                ) {
                    LessonScreen(isCheckpoint = true, onDone = { nav.popBackStack() })
                }
            }
        }
}
