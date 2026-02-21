package dbwatchdog.database

import doobie.*
import doobie.implicits.*

trait Persisted[ID] {
  def id: ID
}

trait TableFragment[ID, A <: Persisted[ID]] {
  val tableName: String
  val columns: List[String]

  lazy val tableF: Fragment =
    Fragment.const(tableName)

  lazy val columnsF: Fragment =
    Fragment.const(columns.mkString(", "))

  lazy val columnsFullF: Fragment =
    Fragment.const(columns.map(c => s"$tableName.$c").mkString(", "))

  lazy val insertColsF: Fragment =
    fr"(" ++ Fragment.const(
      columns.filterNot(_ == "id").mkString(", ")
    ) ++ fr")"

  lazy val returningF: Fragment =
    fr"RETURNING " ++ columnsFullF

  lazy val selectF: Fragment =
    fr"SELECT " ++ columnsFullF ++ fr" FROM " ++ tableF
}
