package io.github.takusan23.androidcamera2apihdrvideo

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.github.takusan23.androidcamera2apihdrvideo.ui.theme.AndroidCamera2ApiHdrVideoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val isPermissionGranted = remember {
                mutableStateOf(REQUIRED_PERMISSION.all { permission -> ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED })
            }

            AndroidCamera2ApiHdrVideoTheme {
                // 権限があればカメラ画面へ
                if (isPermissionGranted.value) {
                    MainScreen()
                } else {
                    PermissionScreen(onGranted = { isPermissionGranted.value = true })
                }
            }
        }
    }
}

/** 必要な権限 */
private val REQUIRED_PERMISSION = listOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)

/** 権限ください画面 */
@Composable
private fun PermissionScreen(onGranted: () -> Unit) {
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { it ->
        if (it.all { it.value /* == true */ }) {
            onGranted()
        }
    }

    Scaffold { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            Button(onClick = { permissionRequest.launch(REQUIRED_PERMISSION.toTypedArray()) }) {
                Text(text = "権限を付与してください")
            }
        }
    }
}

/** カメラ画面 */
@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val cameraController = remember { CameraController(context) }
    val isRecording = cameraController.isRecording.collectAsState()

    DisposableEffect(key1 = Unit) {
        // プレビュー開始
        cameraController.prepare()
        // 使わなくなったら破棄
        onDispose { cameraController.destroy() }
    }

    // TODO 横画面しか対応できていない
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier
                .align(Alignment.Center)
                .aspectRatio(CameraController.VIDEO_WIDTH / CameraController.VIDEO_HEIGHT.toFloat()),
            factory = {
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            // 解像度を合わせておく
                            holder.setFixedSize(CameraController.VIDEO_WIDTH, CameraController.VIDEO_HEIGHT)
                            cameraController.createSurface(holder.surface)
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            // do nothing
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            cameraController.destroySurface()
                        }
                    })
                }
            }
        )

        Box(
            modifier = Modifier
                .padding(bottom = 30.dp)
                .align(Alignment.BottomCenter)
                .clip(CircleShape)
                .size(80.dp)
                // 録画中は色を変える
                .background(if (isRecording.value) Color.Red else Color.Gray)
                .clickable { if (isRecording.value) cameraController.stopRecord() else cameraController.startRecord() }
        )
    }
}
