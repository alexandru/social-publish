package socialpublish.utils

object TextUtils:
  // Simple HTML to text conversion - removes tags
  def convertHtml(html: String): String =
    html
      .replaceAll("<[^>]*>", "") // Remove HTML tags
      .replaceAll("&nbsp;", " ")
      .replaceAll("&lt;", "<")
      .replaceAll("&gt;", ">")
      .replaceAll("&amp;", "&")
      .replaceAll("&quot;", "\"")
      .replaceAll("&#39;", "'")
      .trim()
  
  // cyrb53 hash function - simplified version
  def hashCode(str: String, seed: Long = 0): Long =
    str.foldLeft(seed) { (acc, ch) =>
      val h = (acc * 31 + ch.toLong) & 0xffffffffL
      h
    }
  
  def hashCodeMod(str: String, mod: Int): Int =
    val h = hashCode(str).abs
    (h % mod).toInt
