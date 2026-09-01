package com.linguamod.app.ui.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onOpenUnit: (Int) -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            !state.pluginLoaded -> Column(
                Modifier.fillMaxSize().padding(24.dp).testTag("empty_plugins_state"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Download an Italian language pack", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Place a .lingua file in Android/data/com.linguamod.app/files/plugins/ " +
                        "on this device, then tap Rescan.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { vm.rescan() }, modifier = Modifier.testTag("rescan_button")) {
                    Text("Rescan")
                }
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 24.dp).testTag("path_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        "LinguaMod",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
                items(state.nodes) { node ->
                    UnitNodeRow(
                        node = node,
                        onClick = {
                            if (node.unlocked) onOpenUnit(node.number)
                            else scope.launch {
                                snackbar.currentSnackbarData?.dismiss()
                                snackbar.showSnackbar("Complete Unit ${node.number - 1} to unlock.")
                            }
                        },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun UnitNodeRow(node: UnitNode, onClick: () -> Unit) {
    // Winding path: alternate horizontal offset by unit index.
    val offsetX = when (node.number % 4) {
        1 -> 0; 2 -> 48; 3 -> 48; else -> 0
    }
    Row(
        Modifier.fillMaxWidth().offset(x = offsetX.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val pulse = if (node.isCurrent) {
            val t = rememberInfiniteTransition(label = "pulse")
            t.animateFloat(
                initialValue = 1f, targetValue = 1.12f,
                animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
                label = "pulse",
            ).value
        } else 1f
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = when {
                node.completed -> MaterialTheme.colorScheme.primary
                node.unlocked -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.size(64.dp).scale(pulse).testTag("unit_node_${node.number}"),
        ) {
            Box(contentAlignment = Alignment.Center) {
                when {
                    node.completed -> Icon(Icons.Filled.Check, contentDescription = "completed")
                    !node.unlocked -> Icon(Icons.Filled.Lock, contentDescription = "locked")
                    else -> Text("${node.number}", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        Column(Modifier.padding(start = 16.dp)) {
            Text("Unit ${node.number}", style = MaterialTheme.typography.labelMedium)
            Text(node.title, style = MaterialTheme.typography.titleMedium)
        }
    }
}
