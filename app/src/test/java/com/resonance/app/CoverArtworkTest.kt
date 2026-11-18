package com.resonance.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverArtworkTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun firstLoadKeepsTheCoverAndWritesALocalThumbnail() {
        val cache = temp.newFolder()
        val cover = CoverMemory.decode(jpeg(), cache, "Album1")
        assertEquals(300, cover?.width)
        assertEquals(200, cover?.height)

        val file = requireNotNull(CoverThumbnails.key(cache, "Album1"))
        val thumbnail = BitmapFactory.decodeFile(file.path)
        assertNotNull(thumbnail)
        assertEquals(144, thumbnail.width)
        assertEquals(96, thumbnail.height)
    }

    @Test fun anUnusableThumbnailDirectoryStillReturnsTheCover() {
        val cover = CoverMemory.decode(jpeg(), temp.newFile(), "Album2")
        assertEquals(300, cover?.width)
    }

    private fun jpeg(): ByteArray = ByteArrayOutputStream().use { output ->
        Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, output)
        output.toByteArray()
    }
}
