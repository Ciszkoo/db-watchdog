package dbwatchdog.database

import java.util.UUID

import cats.effect.IO
import weaver.SimpleIOSuite

object TableFragmentSuite extends SimpleIOSuite {
  test("builds reusable SQL fragments") {
    val fragment = new TableFragment[UUID, TestPersisted] {
      val tableName = "widgets"
      val columns = List("id", "name", "created_at")
    }
    val tableSql = renderSql(fragment.tableF)
    val columnsSql = renderSql(fragment.columnsF)
    val columnsFullSql = renderSql(fragment.columnsFullF)
    val insertColsSql = renderSql(fragment.insertColsF)
    val returningSql = renderSql(fragment.returningF)
    val selectSql = renderSql(fragment.selectF)

    IO.pure(
      expect(tableSql.contains("widgets")) and
        expect(columnsSql.contains("id")) and
        expect(columnsSql.contains("created_at")) and
        expect(columnsFullSql.contains("widgets.id")) and
        expect(columnsFullSql.contains("widgets.created_at")) and
        expect(insertColsSql.contains("name")) and
        expect(!insertColsSql.contains("id")) and
        expect(returningSql.contains("RETURNING")) and
        expect(returningSql.contains("widgets.name")) and
        expect(selectSql.contains("SELECT")) and
        expect(selectSql.contains("widgets"))
    )
  }

  final case class TestPersisted(id: UUID) extends Persisted[UUID]

  private def renderSql(fragment: doobie.Fragment): String =
    fragment.query[Unit].sql
}
