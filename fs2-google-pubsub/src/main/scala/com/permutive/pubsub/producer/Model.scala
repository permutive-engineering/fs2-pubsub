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

package com.permutive.pubsub.producer

import java.util.UUID

object Model {
  @deprecated(
    "Use `fs2-pubsub` instead. Replace with: `\"com.permutive\" %% \"fs2-pubsub\" % \"1.0.0\"`",
    since = "0.22.2"
  )
  case class MessageId(value: String) extends AnyVal

  @deprecated(
    "Use `fs2-pubsub` instead. Replace with: `\"com.permutive\" %% \"fs2-pubsub\" % \"1.0.0\"`",
    since = "0.22.2"
  )
  case class ProjectId(value: String) extends AnyVal

  @deprecated(
    "Use `fs2-pubsub` instead. Replace with: `\"com.permutive\" %% \"fs2-pubsub\" % \"1.0.0\"`",
    since = "0.22.2"
  )
  case class Topic(value: String) extends AnyVal

  @deprecated(
    "Use `fs2-pubsub` instead. Replace with: `\"com.permutive\" %% \"fs2-pubsub\" % \"1.0.0\"`",
    since = "0.22.2"
  )
  trait Record[A] {
    def data: A
    def attributes: Map[String, String]
    def uniqueId: String
  }

  @deprecated(
    "Use `fs2-pubsub` instead. Replace with: `\"com.permutive\" %% \"fs2-pubsub\" % \"1.0.0\"`",
    since = "0.22.2"
  )
  final case class SimpleRecord[A](
    data: A,
    attributes: Map[String, String] = Map.empty,
    uniqueId: String = UUID.randomUUID().toString
  ) extends Record[A]

  @deprecated(
    "Use `fs2-pubsub` instead. Replace with: `\"com.permutive\" %% \"fs2-pubsub\" % \"1.0.0\"`",
    since = "0.22.2"
  )
  final case class AsyncRecord[F[_], A](
    data: A,
    callback: Either[Throwable, Unit] => F[Unit],
    attributes: Map[String, String] = Map.empty,
    uniqueId: String = UUID.randomUUID().toString
  ) extends Record[A]
}
