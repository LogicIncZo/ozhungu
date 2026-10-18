package `in`.cashlessconsumer.ozhungu

import android.app.Application
import android.content.ContentResolver

/** ContentResolver holder so pure helper objects can call SAF without Context. */
object AppResolver {
    lateinit var query: ContentResolver
        private set

    fun init(app: Application) {
        query = app.contentResolver
    }
}
