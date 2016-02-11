
object Const {
  val LIST_SEP = "  "
  val SCORE_SEP = ':'
  val HOLE = "@"
  val HOLE_DEPRECATED = "@@"

  object Resources{
    val STOPWORDS = "/stoplist_en.csv"
    val STOP_DEPENDENCIES = Set("dep", "punct", "cc", "possessive")
  }
}