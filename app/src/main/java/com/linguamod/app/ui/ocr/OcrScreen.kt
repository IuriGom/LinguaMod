package com.linguamod.app.ui.ocr

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.linguamod.app.data.db.DictionaryEntryEntity
import com.linguamod.app.ocr.OcrFrame

/**
 * OCR camera screen (Stage 4 §1). All recognition goes through the injected
 * OcrGateway — with the scripted fake this whole screen works without real
 * camera frames; the CameraX preview/analysis only feeds the real gateway and
 * degrades to a placeholder when no camera is available.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrScreen(
    onBack: () -> Unit,
    vm: OcrViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    // CAMERA permission with rationale (§1); denial hides the feature gracefully.
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) vm.onPermissionResult(true)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> vm.onPermissionResult(granted) }

    Column(Modifier.fillMaxSize().padding(16.dp).testTag("ocr_screen")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("ocr_back")) {
                Text("← Back")
            }
            Spacer(Modifier.weight(1f))
            Text("Scan text", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))

        when {
            state.checking -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.testTag("ocr_loading"))
            }
            state.unavailable -> UnavailableCard(onBack)
            !state.permissionGranted && !state.permissionDenied -> PermissionRationale(
                onGrant = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onDeny = { vm.onPermissionResult(false) },
            )
            state.permissionDenied -> PermissionDeniedCard(onBack)
            else -> ScannerContent(state, vm)
        }
    }

    state.sheet?.let { sheet ->
        ModalBottomSheet(
            onDismissRequest = { vm.dismissSheet() },
            modifier = Modifier.testTag("ocr_word_sheet"),
        ) {
            when (sheet) {
                is OcrViewModel.WordSheet.Known -> KnownWordSheet(sheet.entry) { vm.dismissSheet() }
                is OcrViewModel.WordSheet.Unknown -> UnknownWordSheet(sheet) { vm.dismissSheet() }
            }
        }
    }
}

@Composable
private fun UnavailableCard(onBack: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("ocr_unavailable")) {
        Column(Modifier.padding(16.dp)) {
            Text("Camera OCR unavailable", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Text recognition needs Google Play Services, which this device doesn't have. " +
                    "The scanner stays hidden — everything else works offline.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onBack, modifier = Modifier.testTag("ocr_unavailable_back")) {
                Text("Back")
            }
        }
    }
}

@Composable
private fun PermissionRationale(onGrant: () -> Unit, onDeny: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("ocr_permission_rationale")) {
        Column(Modifier.padding(16.dp)) {
            Text("Camera access needed", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Point the camera at Italian text — a menu, a sign, a book page — and tap " +
                    "any recognized word to look it up in your dictionary. Recognition runs " +
                    "on-device; photos never leave your phone.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Row {
                Button(onClick = onGrant, modifier = Modifier.testTag("ocr_grant_permission")) {
                    Text("Allow camera")
                }
                Spacer(Modifier.padding(start = 8.dp))
                TextButton(onClick = onDeny, modifier = Modifier.testTag("ocr_rationale_not_now")) {
                    Text("Not now")
                }
            }
        }
    }
}

/** Denial → the feature hides gracefully (§1): no camera UI, no crash. */
@Composable
private fun PermissionDeniedCard(onBack: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("ocr_permission_denied")) {
        Column(Modifier.padding(16.dp)) {
            Text("Camera access denied", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Without the camera the scanner stays off. You can enable it later in the " +
                    "system settings — everything else works as usual.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onBack, modifier = Modifier.testTag("ocr_denied_back")) {
                Text("Back")
            }
        }
    }
}

@Composable
private fun ScannerContent(state: OcrViewModel.UiState, vm: OcrViewModel) {
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { CameraPreview(Modifier.fillMaxWidth().height(220.dp), vm) }
        item {
            Button(
                onClick = { vm.onScanClicked() },
                enabled = !state.scanning,
                modifier = Modifier.fillMaxWidth().testTag("ocr_scan_button"),
            ) {
                Text(if (state.scanning) "Scanning…" else "Scan")
            }
        }
        state.info?.let { info ->
            item {
                Text(
                    info,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(
                        if (info.startsWith("downloading")) "ocr_downloading" else "ocr_info",
                    ),
                )
            }
        }
        val blocks = state.blocks
        if (blocks != null) {
            item {
                Text(
                    "Tap a word to look it up:",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.testTag("ocr_results_list"),
                )
            }
            // deterministic global word indices (recomposition-safe)
            val tokenLists = blocks.map { com.linguamod.app.ocr.OcrWordMatcher.tokens(it.text) }
            val offsets = tokenLists.runningFold(0) { acc, l -> acc + l.size }
            itemsIndexed(blocks) { blockIdx, block ->
                Card(Modifier.fillMaxWidth().testTag("ocr_block_$blockIdx")) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            block.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        tokenLists[blockIdx].forEachIndexed { wordIdx, token ->
                            Surface(
                                onClick = { vm.onWordTapped(OcrViewModel.RecognizedWord(token, block.text)) },
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .testTag("ocr_word_${offsets[blockIdx] + wordIdx}"),
                            ) {
                                Text(token, Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * CameraX preview + latest-frame analysis. Binding failures (no camera HAL,
 * headless emulator) degrade to a placeholder; scanning is gateway-driven and
 * keeps working with the fake gateway regardless.
 */
@SuppressLint("UnsafeOptInUsageError")
@Composable
private fun CameraPreview(modifier: Modifier, vm: OcrViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var bindFailed by remember { mutableStateOf(false) }
    if (bindFailed) {
        Box(modifier.testTag("ocr_preview_unavailable"), contentAlignment = Alignment.Center) {
            Text("Camera preview unavailable on this device", style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    AndroidView(
        modifier = modifier.testTag("ocr_preview"),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                runCatching {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { proxy ->
                        proxy.image?.let { image ->
                            vm.onCameraFrame(
                                OcrFrame(InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees)),
                            )
                        }
                        proxy.close()
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
                    )
                }.onFailure { bindFailed = true }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

/** Known word: the dictionary entry; the tap already awarded +2 XP. */
@Composable
private fun KnownWordSheet(entry: DictionaryEntryEntity, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp).testTag("ocr_word_known")) {
        val display = if (entry.article != null) "${entry.article} ${entry.word}" else entry.word
        Text(display, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(entry.translation, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            entry.partOfSpeech,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.testTag("ocr_word_pos"),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "+${com.linguamod.app.data.CourseRepository.XP_PER_OCR_LOOKUP} XP",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("ocr_word_xp"),
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onClose, modifier = Modifier.testTag("ocr_sheet_close")) { Text("Close") }
        Spacer(Modifier.height(16.dp))
    }
}

/** Unknown word: recognized text + the block it was seen in (§1). */
@Composable
private fun UnknownWordSheet(sheet: OcrViewModel.WordSheet.Unknown, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp).testTag("ocr_word_unknown")) {
        Text(sheet.token, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text("not in your dictionary yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Recognized in: «${sheet.context}»",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("ocr_word_context"),
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onClose, modifier = Modifier.testTag("ocr_sheet_close")) { Text("Close") }
        Spacer(Modifier.height(16.dp))
    }
}
