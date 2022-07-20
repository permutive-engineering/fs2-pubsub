/*
 * Copyright 2018 Permutive
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.permutive.pubsub.consumer

import cats.Show
import cats.syntax.show._

import scala.concurrent.duration.FiniteDuration

trait ConsumerRecord[F[_], A] {
  def value: A
  def attributes: Map[String, String]
  def ack: F[Unit]
  def nack: F[Unit]
  def extendDeadline(by: FiniteDuration): F[Unit]
}

object ConsumerRecord {
  implicit def show[F[_], A: Show]: Show[ConsumerRecord[F, A]] =
    (record: ConsumerRecord[F, A]) => s"Record(${record.value.show})"

  abstract private[this] case class RecordImpl[F[_], A](
    value: A,
    attributes: Map[String, String],
    ack: F[Unit],
    nack: F[Unit],
  ) extends ConsumerRecord[F, A]

  def apply[F[_], A](
    value: A,
    attributes: Map[String, String],
    ack: F[Unit],
    nack: F[Unit],
    extend: FiniteDuration => F[Unit],
  ): ConsumerRecord[F, A] =
    new RecordImpl(value, attributes, ack, nack) {
      final override def extendDeadline(by: FiniteDuration): F[Unit] = extend(by)
    }
}
