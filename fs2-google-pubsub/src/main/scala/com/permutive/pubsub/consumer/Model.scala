package com.permutive.pubsub.consumer

import cats.Show
import cats.syntax.show._

object Model {
  case class ProjectId(value: String) extends AnyVal
  object ProjectId {
    implicit val show: Show[ProjectId] = Show.fromToString
  }

  case class Subscription(value: String) extends AnyVal
  object Subscription {
    implicit val show: Show[Subscription] = Show.fromToString
  }

  case class Record[F[_], A](value: A, ack: F[Unit], nack: F[Unit])
  object Record {
    implicit def show[F[_], A : Show]: Show[Record[F, A]] = (record: Record[F, A]) => s"Record(${record.value.show})"
  }
}
