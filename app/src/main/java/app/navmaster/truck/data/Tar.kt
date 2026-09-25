package app.navmaster.truck.data

import java.io.File
import java.io.InputStream

/** Minimal reader for the (ustar / pax) tar files of the Europe routing tiles. */
object Tar {
  /** Extracts every regular file into [dest]; returns the relative paths written. */
  fun extract(tar: File, dest: File, onProgress: (Long) -> Unit = {}): List<String> {
    val written = mutableListOf<String>()
    val destCanon = dest.canonicalPath
    tar.inputStream().buffered(1 shl 20).use { input ->
      val header = ByteArray(512)
      var paxPath: String? = null
      var done = 0L
      while (true) {
        if (!readFully(input, header)) break
        if (header.all { it == 0.toByte() }) break
        val size = octal(header, 124, 12)
        val type = header[156].toInt().toChar()
        var name = string(header, 0, 100)
        val magic = string(header, 257, 6)
        if (magic.startsWith("ustar")) {
          val prefix = string(header, 345, 155)
          if (prefix.isNotEmpty()) name = "$prefix/$name"
        }
        done += 512
        when (type) {
          'x' -> {
            paxPath = parsePax(readBytes(input, size))?.get("path")
            skipPadding(input, size)
          }
          'g', 'L', 'K' -> {
            val data = readBytes(input, size)
            if (type == 'L') paxPath = String(data).trimEnd('\u0000')
            skipPadding(input, size)
          }
          '0', '\u0000', '7' -> {
            val rel = (paxPath ?: name).trimStart('/')
            paxPath = null
            val out = File(dest, rel)
            if (!out.canonicalPath.startsWith(destCanon)) throw IllegalStateException("percorso non valido nel pacchetto: $rel")
            out.parentFile?.mkdirs()
            out.outputStream().buffered(1 shl 16).use { o -> copy(input, o, size) }
            skipPadding(input, size)
            written += rel
          }
          else -> {
            paxPath = null
            skip(input, size)
            skipPadding(input, size)
          }
        }
        done += size + pad(size)
        onProgress(done)
      }
    }
    return written
  }

  private fun pad(size: Long) = (512 - size % 512) % 512

  private fun skipPadding(input: InputStream, size: Long) = skip(input, pad(size))

  private fun skip(input: InputStream, n: Long) {
    var left = n
    val buf = ByteArray(8192)
    while (left > 0) {
      val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
      if (r < 0) break
      left -= r
    }
  }

  private fun copy(input: InputStream, out: java.io.OutputStream, n: Long) {
    var left = n
    val buf = ByteArray(1 shl 16)
    while (left > 0) {
      val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
      if (r < 0) throw IllegalStateException("pacchetto troncato")
      out.write(buf, 0, r)
      left -= r
    }
  }

  private fun readBytes(input: InputStream, n: Long): ByteArray {
    val b = ByteArray(n.toInt())
    readFully(input, b)
    return b
  }

  private fun readFully(input: InputStream, b: ByteArray): Boolean {
    var off = 0
    while (off < b.size) {
      val r = input.read(b, off, b.size - off)
      if (r < 0) return off > 0 && off == b.size
      off += r
    }
    return true
  }

  private fun string(b: ByteArray, off: Int, len: Int): String {
    var end = off
    while (end < off + len && b[end] != 0.toByte()) end++
    return String(b, off, end - off, Charsets.UTF_8).trim()
  }

  private fun octal(b: ByteArray, off: Int, len: Int): Long {
    val s = string(b, off, len).trim()
    return if (s.isEmpty()) 0 else s.toLong(8)
  }

  private fun parsePax(data: ByteArray): Map<String, String>? {
    val text = String(data, Charsets.UTF_8)
    val out = mutableMapOf<String, String>()
    var i = 0
    while (i < text.length) {
      val sp = text.indexOf(' ', i)
      if (sp < 0) break
      val len = text.substring(i, sp).toIntOrNull() ?: break
      val rec = text.substring(sp + 1, (i + len).coerceAtMost(text.length)).trimEnd('\n')
      val eq = rec.indexOf('=')
      if (eq > 0) out[rec.substring(0, eq)] = rec.substring(eq + 1)
      i += len
    }
    return out
  }
}
