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

    IO.pure(
      expect(fragment.tableF != null) and
        expect(fragment.columnsF != null) and
        expect(fragment.columnsFullF != null) and
        expect(fragment.insertColsF != null) and
        expect(fragment.returningF != null) and
        expect(fragment.selectF != null)
    )
  }

  final case class TestPersisted(id: UUID) extends Persisted[UUID]
}
