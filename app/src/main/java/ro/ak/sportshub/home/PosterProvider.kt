package ro.ak.sportshub.home

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Cardurile meciurilor din rândul de pe ecranul principal: imagini generate de aplicație (doar echipele,
 * competiția și ora), citite de launcher. Nu dă acces la alte fișiere.
 */
class PosterProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        if (!NAME.matches(name) || mode != "r") throw FileNotFoundException()
        val dir = File(context?.filesDir ?: throw FileNotFoundException(), DIR)
        val file = File(dir, name)
        if (!file.exists()) throw FileNotFoundException()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/png"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "ro.ak.sportshub.posters"
        const val DIR = "posters"
        private val NAME = Regex("""p\d+-\d+\.png""")

        fun uri(name: String): Uri = Uri.parse("content://$AUTHORITY/$name")
    }
}
