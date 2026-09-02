package com.linguamod.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
        // "Your Records" (Stage 4 §5): personal stats only, no fake competitors.
        if (state.recordsVisible) {
            RecordsCard(state)
            Spacer(Modifier.height(16.dp))
        }
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

/**
 * "Your Records" (Stage 4 §5): personal stats only — XP per day for the last
 * 14 days (simple bar chart), best checkpoint scores, longest streak, and the
 * most-looked-up OCR words. No server, no fabricated competitors.
 */
@Composable
private fun RecordsCard(state: ProfileState) {
    Card(Modifier.fillMaxWidth().testTag("records_card")) {
        Column(Modifier.padding(16.dp)) {
            Text("Your Records", style = MaterialTheme.typography.titleMedium)
            Text(
                "Personal stats — stored only on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            Text("XP per day (last 14 days)", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            val maxXp = state.dailyXp.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
            state.dailyXp.forEach { (date, xp) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        date.drop(5), // MM-dd
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(44.dp),
                    )
                    Box(
                        Modifier
                            .height(10.dp)
                            .fillMaxWidth((xp.toFloat() / maxXp).coerceAtLeast(0.02f))
                            .background(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.shapes.small,
                            )
                            .testTag("xp_bar_$date"),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("$xp", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(12.dp))

            Text("Best checkpoint scores", style = MaterialTheme.typography.labelLarge)
            if (state.bestCheckpointScores.isEmpty()) {
                Text("No checkpoints passed yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                state.bestCheckpointScores.forEach { (unit, score) ->
                    Text(
                        "Unit $unit: ${(score * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("records_score_$unit"),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            Text(
                "Longest streak: ${state.progress.longestStreak} days",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("records_longest_streak"),
            )
            Spacer(Modifier.height(12.dp))

            Text("Most looked-up words", style = MaterialTheme.typography.labelLarge)
            if (state.mostLookedUp.isEmpty()) {
                Text(
                    "No dictionary lookups yet.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("records_no_lookups"),
                )
            } else {
                state.mostLookedUp.forEach { entry ->
                    Text(
                        "${entry.word} — ${entry.lookupCount}×",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("records_lookup_${entry.id}"),
                    )
                }
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
            // Dynamic per-story / per-boss badges (Stage 4 §2, §3), only once earned.
            state.unlockedBadges
                .filter { id -> Badges.ALL.none { it.id == id } }
                .sorted()
                .forEach { id ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("badge_$id"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            Badges.displayNameFor(id),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "Unlocked",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
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
