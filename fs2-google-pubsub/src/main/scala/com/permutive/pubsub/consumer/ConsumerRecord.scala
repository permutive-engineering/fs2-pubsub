package com.permutive.pubsub.consumer

import cats.Show
import cats.syntax.show._

import scala.concurrent.duration.FiniteDuration

trait ConsumerRecord[F[_], A] {
  def value: A
  def ack: F[Unit]
  def nack: F[Unit]
  def extendDeadline(by: FiniteDuration): F[Unit]
}

object ConsumerRecord {
  implicit def show[F[_], A: Show]: Show[ConsumerRecord[F, A]] =
    (record: ConsumerRecord[F, A]) => s"Record(${record.value.show})"

  abstract private[this] case class RecordImpl[F[_], A](
    value: A,
    ack: F[Unit],
    nack: F[Unit],
  ) extends ConsumerRecord[F, A]

  def apply[F[_], A](
    value: A,
    ack: F[Unit],
    nack: F[Unit],
    extend: FiniteDuration => F[Unit],
  ): ConsumerRecord[F, A] =
    new RecordImpl(value, ack, nack) {
      final override def extendDeadline(by: FiniteDuration): F[Unit] = extend(by)
    }
}
