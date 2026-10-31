package specular.editor

/** Scan `text` for string hosts and `def doc = page(...)` trees.
  *
  * `Left` is only an unclosed string, comment, or delimiter. A legal Scala file that is not a `DocSpec` is `Right` with
  * empty `pages`. The lexer and the page walk live in [[Lex]] and [[Recognize]]; this is the public entry.
  */
def analyze(text: String): Either[AnalyzeError, FileAnalysis] =
  Recognize.scan(text).map(_.analysis)
