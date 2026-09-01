package com.linguamod.app.ui.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.linguamod.app.core.Badges
import com.linguamod.app.data.CourseRepository
import com.linguamod.app.data.ThemeCatalog
import com.linguamod.app.data.ThemeSpec

@Composable
fun ProfileScreen(vm: ProfileViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        Text("Profile", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Streak: ${state.progress.streakCount} days", modifier = Modifier.testTag("streak_value"))
                Text("Total XP: ${state.progress.totalXp}", modifier = Modifier.testTag("xp_value"))
                Text(
                    "Hearts: ${state.progress.hearts}/${CourseRepository.MAX_HEARTS}",
                    modifier = Modifier.testTag("hearts_value"),
                )
                Text("Gems: ${state.progress.gems}", modifier = Modifier.testTag("gems_value"))
                Text("Units completed: ${state.unitsCompleted}")
            }
        }
        Spacer(Modifier.height(16.dp))
        LevelCard(state)
        Spacer(Modifier.height(16.dp))
        BadgesCard(state)
        Spacer(Modifier.height(16.dp))
        AppearanceCard(state, vm)
        Spacer(Modifier.height(16.dp))
        Text("Language packs", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        state.plugins.forEach { p ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row {
                        Text(p.fileName, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (p.valid) "OK (${p.unitCount} units)" else "INVALID",
                            color = if (p.valid) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                    if (!p.valid && p.errors.isNotBlank()) {
                        Text(
                            p.errors.lines().first(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.rescan() }, modifier = Modifier.testTag("rescan_button")) {
            Text("Rescan plugins")
        }
    }
}

@Composable
private fun LevelCard(state: ProfileState) {
    Card(Modifier.fillMaxWidth().testTag("level_card")) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Level ${state.level}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("level_value"),
            )
            val next = state.nextLevelThreshold
            if (next == null) {
                Text("Max level reached.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Next level at checkpoint $next (${state.highestCheckpoint}/$next)",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { (state.highestCheckpoint.toFloat() / next).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().testTag("level_progress"),
                )
            }
        }
    }
}

@Composable
private fun BadgesCard(state: ProfileState) {
    Card(Modifier.fillMaxWidth().testTag("badges_card")) {
        Column(Modifier.padding(16.dp)) {
            Text("Badges", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Badges.ALL.forEach { badge ->
                val unlocked = badge.id in state.unlockedBadges
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("badge_${badge.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        badge.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (unlocked) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (unlocked) "Unlocked" else badge.condition,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (unlocked) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }
}

@Composable
private fun AppearanceCard(state: ProfileState, vm: ProfileViewModel) {
    Card(Modifier.fillMaxWidth().testTag("appearance_card")) {
        Column(Modifier.padding(16.dp)) {
            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            ThemeRow(ThemeCatalog.DEFAULT, owned = true, state = state, vm = vm)
            ThemeCatalog.ALL.forEach { theme ->
                ThemeRow(theme, owned = theme.id in state.purchasedThemes, state = state, vm = vm)
            }
        }
    }
}

@Composable
private fun ThemeRow(theme: ThemeSpec, owned: Boolean, state: ProfileState, vm: ProfileViewModel) {
    val active = state.activeTheme == theme.id
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(theme.name, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.weight(1f))
        when {
            active -> Text("Active", color = MaterialTheme.colorScheme.primary)
            owned -> Button(
                onClick = { vm.applyTheme(theme.id) },
                modifier = Modifier.testTag("theme_apply_${theme.id}"),
            ) { Text("Apply") }
            else -> Button(
                onClick = { vm.buyTheme(theme.id) },
                enabled = state.progress.gems >= theme.priceGems,
                modifier = Modifier.testTag("theme_buy_${theme.id}"),
            ) { Text("${theme.priceGems} gems") }
        }
    }
}
