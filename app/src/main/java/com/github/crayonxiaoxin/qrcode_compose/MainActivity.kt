package com.github.crayonxiaoxin.qrcode_compose

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.github.crayonxiaoxin.qrcode_compose.ui.theme.QRCode_ComposeTheme
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch


class MainActivity : ComponentActivity() {

    private val permissions = arrayOf(
        Manifest.permission.CAMERA,
    )

    @SuppressLint("RestrictedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {

            val isGranted = remember {
                mutableStateOf(false)
            }

            val requestPermissions =
                rememberLauncherForActivityResult(contract = ActivityResultContracts.RequestMultiplePermissions()) {
                    isGranted.value = !it.values.contains(false)
                }

            // 申请权限
            LaunchedEffect(Unit) {
                if (!isGranted.value) {
                    requestPermissions.launch(permissions)
                }
            }

            val context = LocalContext.current
            val cameraController = remember {
                LifecycleCameraController(context)
            }

            val qrcodeScope = rememberCoroutineScope()

            QRCode_ComposeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            // 初始化
                            previewView(context, cameraController, qrcodeScope)
                        },
                        update = {
                            // 这里可以和 compose 沟通，进行状态更新
                        }
                    )
                }
            }
        }
    }

    private fun previewView(
        context: Context,
        cameraController: LifecycleCameraController,
        qrcodeScope: CoroutineScope
    ): PreviewView {
        // 预览状态
        var isPause = false

        // 预览
        val previewView = PreviewView(context)
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        // 相机选择
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()
        cameraController.cameraSelector = cameraSelector

        // 条形码设置
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        val barcodeScanner = BarcodeScanning.getClient(options)

        // 图片分析器
        cameraController.setImageAnalysisAnalyzer(
            ContextCompat.getMainExecutor(this@MainActivity),
            MlKitAnalyzer(
                listOf(barcodeScanner),
                COORDINATE_SYSTEM_VIEW_REFERENCED,
                ContextCompat.getMainExecutor(this@MainActivity)
            ) { result: MlKitAnalyzer.Result? ->
                // 获取结果
                result?.getValue(barcodeScanner)?.let {
                    // 清空标记
                    previewView.overlay.clear()
                    if (it.isEmpty()) {
                        // 如果没有目标，清除触摸事件
                        previewView.setOnTouchListener { view, _ ->
                            view.performClick()
                            false
                        }
                        return@MlKitAnalyzer
                    }
                    // 如果有目标，画 marker
                    val models = arrayListOf<QrCodeViewModel>()
                    it.forEach {
                        val qrCodeViewModel = QrCodeViewModel(it)
                        val qrCodeDrawable = QrCodeDrawable(qrCodeViewModel)
                        previewView.overlay.add(qrCodeDrawable)
                        models.add(qrCodeViewModel)
                    }

                    // 并设置触摸事件
                    previewView.setOnTouchListener { view, motionEvent ->
                        view.performClick()
                        // 暂停状态下，触摸屏幕将重新预览
                        if (isPause) {
                            isPause = false
                            cameraController.bindToLifecycle(this@MainActivity)
                        }
                        // 为每个目标区域添加触摸监听
                        models.forEach {
                            it.qrCodeTouchCallback.invoke(view, motionEvent)
                        }

                        true
                    }

                    // 捕获目标后停止预览
                    // todo: 太过灵敏，应该做一个延迟
                    qrcodeScope.launch {
                        cameraController.unbind()
                        isPause = true
                    }

                }
            }
        )

        // 生命周期监听
        this@MainActivity.lifecycle.addObserver(object :
            LifecycleEventObserver {
            override fun onStateChanged(
                source: LifecycleOwner,
                event: Lifecycle.Event
            ) {
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> { // 暂停 preview
                        cameraController.unbind()
                    }

                    Lifecycle.Event.ON_RESUME -> { // 重启 preview
                        cameraController.bindToLifecycle(this@MainActivity)
                    }

                    Lifecycle.Event.ON_DESTROY -> { // 移除监听
                        this@MainActivity.lifecycle.removeObserver(this)
                    }

                    else -> {}
                }
            }
        })

        previewView.controller = cameraController

        return previewView
    }
}