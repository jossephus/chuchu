package com.jossephus.chuchu.ui.screens.AddServer

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.jossephus.chuchu.R
import com.jossephus.chuchu.ui.components.ChuButton
import com.jossephus.chuchu.ui.components.ChuButtonVariant
import com.jossephus.chuchu.ui.components.ChuText
import com.jossephus.chuchu.ui.theme.ChuColors
import com.jossephus.chuchu.ui.theme.ChuTypography
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun QrCodeScanner(
    title: String,
    hint: String,
    onResult: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = ChuColors.current
    val typography = ChuTypography.current
    var permission by remember {
        mutableStateOf(
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                CameraPermission.Granted
            } else {
                CameraPermission.Unknown
            }
        )
    }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permission = if (granted) CameraPermission.Granted else CameraPermission.Denied
        }
    LaunchedEffect(Unit) {
        if (permission == CameraPermission.Unknown) permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    BackHandler(onBack = onClose)

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (permission == CameraPermission.Granted) {
            CameraPreview(onResult = onResult, modifier = Modifier.fillMaxSize())
            Box(
                modifier =
                    Modifier.align(Alignment.Center)
                        .size(240.dp)
                        .border(2.dp, colors.accent)
            )
        }
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .background(colors.background.copy(alpha = 0.85f))
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChuText(title, style = typography.headline)
            ChuText(
                if (permission == CameraPermission.Denied) {
                    "Camera access was denied. Allow it in system settings, or type the address instead."
                } else {
                    hint
                },
                style = typography.bodySmall,
                color = if (permission == CameraPermission.Denied) colors.error else colors.textMuted,
            )
        }
        ChuButton(
            onClick = onClose,
            variant = ChuButtonVariant.Outlined,
            bracketed = true,
            backgroundColor = colors.background,
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(24.dp),
        ) {
            ChuText(stringResource(R.string.action_cancel), style = typography.label)
        }
    }
}

private enum class CameraPermission {
    Unknown,
    Granted,
    Denied,
}

@Composable
private fun CameraPreview(onResult: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnResult by rememberUpdatedState(onResult)
    val previewView = remember { PreviewView(context) }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val delivered = AtomicBoolean(false)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis =
            ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
        val reader = QrFrameReader()
        analysis.setAnalyzer(executor) { image ->
            val text = image.use { if (delivered.get()) null else reader.decode(it) }
            if (text != null && delivered.compareAndSet(false, true)) {
                mainExecutor.execute { currentOnResult(text) }
            }
        }
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false
        providerFuture.addListener(
            {
                if (disposed) return@addListener
                val cameraProvider = providerFuture.get()
                provider = cameraProvider
                cameraProvider.unbind(preview, analysis)
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            },
            mainExecutor,
        )
        onDispose {
            disposed = true
            provider?.unbind(preview, analysis)
            analysis.clearAnalyzer()
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

private class QrFrameReader {
    private val reader =
        MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        }
    private var luminance = ByteArray(0)

    fun decode(image: ImageProxy): String? {
        val plane = image.planes.firstOrNull() ?: return null
        val rowStride = plane.rowStride
        val needed = rowStride * image.height
        if (luminance.size != needed) luminance = ByteArray(needed)
        val buffer = plane.buffer
        buffer.rewind()
        buffer.get(luminance, 0, minOf(buffer.remaining(), needed))
        val source =
            PlanarYUVLuminanceSource(luminance, rowStride, image.height, 0, 0, image.width, image.height, false)
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: ReaderException) {
            null
        } finally {
            reader.reset()
        }
    }
}
