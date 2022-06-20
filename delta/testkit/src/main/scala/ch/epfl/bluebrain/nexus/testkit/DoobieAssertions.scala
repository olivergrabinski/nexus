package ch.epfl.bluebrain.nexus.testkit

import doobie.postgres.sqlstate
import monix.bio.{IO, UIO}
import munit.{Assertions, Location}
import org.postgresql.util.PSQLException

trait DoobieAssertions { self: Assertions =>

  implicit class DoobieAssertionsOps[E, A](io: IO[E, A])(implicit loc: Location) {
    def expectUniqueViolation: UIO[Unit] = io.attempt.map {
      case Left(p: PSQLException) if p.getSQLState == sqlstate.class23.UNIQUE_VIOLATION.value => ()
      case Left(p: PSQLException)                                                             =>
        fail(
          s"Wrong sql state caught, expected: '${sqlstate.class23.UNIQUE_VIOLATION.value}', actual: '${p.getSQLState}' "
        )
      case Left(err)                                                                          =>
        fail(
          s"Wrong raised error type caught, expected: 'PSQLException', actual: '${err.getClass.getName}'"
        )
      case Right(a)                                                                           =>
        fail(
          s"Expected raising error, but returned successful response with value '$a'"
        )
    }
  }
}