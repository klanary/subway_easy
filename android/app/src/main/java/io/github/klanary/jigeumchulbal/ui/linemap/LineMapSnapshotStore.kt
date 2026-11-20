package io.github.klanary.jigeumchulbal.ui.linemap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** 저장해 둔 마지막 노선도 화면과 그때의 위치·배율. */
class LineMapSnapshot(val image: Bitmap, val transform: MapTransform)

/**
 * 앱을 떠날 때 보던 노선도 화면을 기기에 저장하고, 다음 실행 때 진짜 노선도가 준비될 때까지 대신 보인다
 * (화면 다듬기 Task 3 합의). [versionKey]가 다르면(앱이 바뀌어 노선도 파일이 달라졌을 수 있으면) 저장본을 버린다.
 */
class LineMapSnapshotStore(private val dir: File, private val versionKey: String) {
    private val image get() = File(dir, IMAGE_FILE)
    private val meta get() = File(dir, META_FILE)

    fun save(bitmap: Bitmap, transform: MapTransform) {
        dir.mkdirs()
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        meta.writeText(listOf(versionKey, transform.scale, transform.offsetX, transform.offsetY).joinToString("\n"))
    }

    /** 위치·배율만 읽는다(작은 글자 파일이라 노선도를 그리기 전에 바로 읽어도 된다). 버전이 다르면 저장본을 버린다. */
    fun loadTransform(): MapTransform? {
        val lines = runCatching { meta.readLines() }.getOrNull() ?: return null
        val transform = lines.takeIf { it.size == 4 && it[0] == versionKey }?.let {
            runCatching { MapTransform(it[1].toFloat(), it[2].toFloat(), it[3].toFloat()) }.getOrNull()
        }
        if (transform == null) clear()
        return transform
    }

    fun load(): LineMapSnapshot? {
        val transform = loadTransform() ?: return null
        val bitmap = runCatching { BitmapFactory.decodeFile(image.path) }.getOrNull()
        if (bitmap == null) {
            clear()
            return null
        }
        return LineMapSnapshot(bitmap, transform)
    }

    fun clear() {
        image.delete()
        meta.delete()
    }

    companion object {
        const val IMAGE_FILE = "linemap_snapshot.png"
        const val META_FILE = "linemap_snapshot.txt"
    }
}
