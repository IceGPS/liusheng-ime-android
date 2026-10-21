package android.content.res

import java.io.File
import java.io.InputStream

/**
 * HARNESS-ONLY stub (not part of the app).
 * Lets the verbatim app sources run on a plain JVM: open() reads the file
 * with the given name from the directory in system property "eval.assetsDir"
 * (point it at the app's real src/main/assets, so the real pinyin_dict.bin
 * is loaded byte-for-byte).
 */
class AssetManager {
    fun open(fileName: String): InputStream {
        val dir = System.getProperty("eval.assetsDir")
            ?: error("system property eval.assetsDir not set")
        return File(dir, fileName).inputStream()
    }
}
