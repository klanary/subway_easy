package io.github.klanary.jigeumchulbal.ui.linemap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Picture
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 노선도 그림을 준비한 상태. */
sealed interface MapPicture {
    data object Loading : MapPicture
    data class Ready(val picture: Picture) : MapPicture
    data object Failed : MapPicture
}

/**
 * 노선도 SVG를 앱이 시작하자마자(App.onCreate) 해석해 둔다. 해석에 0.5~3초가 걸려 화면이 뜬 뒤 시작하면 그만큼 기다린다
 * (화면 다듬기 Task 3, 에뮬레이터 측정). 한 번 만든 그림은 프로세스가 살아 있는 동안 다시 쓴다.
 */
object LineMapPictureLoader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 저장은 하나씩(겹쳐 쓰면 그림과 위치가 어긋난다). */
    private val saving = Mutex()
    private val _state = MutableStateFlow<MapPicture>(MapPicture.Loading)
    val state: StateFlow<MapPicture> = _state.asStateFlow()
    private var started = false

    @Synchronized
    fun preload(context: Context) {
        if (started) return
        started = true
        load(context.applicationContext)
    }

    /** 실패 화면의 [다시 시도]. */
    @Synchronized
    fun retry(context: Context) {
        started = true
        _state.value = MapPicture.Loading
        load(context.applicationContext)
    }

    private fun load(context: Context) {
        scope.launch {
            _state.value = runCatching { SVG.getFromAsset(context.assets, LINEMAP_SVG_ASSET).renderToPicture() }
                .fold({ MapPicture.Ready(it) }, { MapPicture.Failed })
        }
    }

    /** 보던 화면을 [width]×[height] 그림으로 그려 [store]에 저장한다. 화면을 떠날 때 부른다(메인 스레드를 막지 않는다). */
    fun saveSnapshot(store: LineMapSnapshotStore, picture: Picture, transform: MapTransform, width: Int, height: Int, background: Int = Color.WHITE) {
        if (width <= 0 || height <= 0) return
        scope.launch {
            saving.withLock {
            runCatching {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                // 테마 바탕색으로(다크에서 흰 여백이 번쩍이지 않게, 2차 다듬기 Task 4)
                canvas.drawColor(background)
                canvas.translate(transform.offsetX, transform.offsetY)
                canvas.scale(transform.scale, transform.scale)
                canvas.drawPicture(picture)
                store.save(bitmap, transform)
                bitmap.recycle()
            }
            }
        }
    }
}
