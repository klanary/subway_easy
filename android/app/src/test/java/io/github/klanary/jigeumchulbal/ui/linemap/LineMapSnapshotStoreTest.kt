package io.github.klanary.jigeumchulbal.ui.linemap

import android.graphics.Bitmap
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LineMapSnapshotStoreTest {
    private val dir: File = Files.createTempDirectory("snap").toFile()
    private val transform = MapTransform(1.5f, -200f, -300f)
    private fun bitmap() = Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888)

    @Test fun nothingSavedLoadsNull() = assertNull(LineMapSnapshotStore(dir, "v1").load())

    @Test fun saveThenLoadKeepsTransformAndSize() {
        LineMapSnapshotStore(dir, "v1").save(bitmap(), transform)
        val loaded = LineMapSnapshotStore(dir, "v1").load()
        assertNotNull(loaded)
        assertEquals(transform, loaded!!.transform)
        assertEquals(40, loaded.image.width)
        assertEquals(80, loaded.image.height)
    }

    @Test fun otherVersionDiscardsSnapshot() {
        LineMapSnapshotStore(dir, "v1").save(bitmap(), transform)
        assertNull(LineMapSnapshotStore(dir, "v2").load())
        assertFalse(File(dir, LineMapSnapshotStore.IMAGE_FILE).exists())
    }

    /** 노선도가 자리를 잡기 전에 위치·배율을 먼저 알아야 한다(에뮬레이터에서 다시 켜면 시청으로 돌아가던 문제). */
    @Test fun transformLoadsWithoutDecodingImage() {
        LineMapSnapshotStore(dir, "v1").save(bitmap(), transform)
        assertEquals(transform, LineMapSnapshotStore(dir, "v1").loadTransform())
        assertNull(LineMapSnapshotStore(dir, "v2").loadTransform())
    }
}
