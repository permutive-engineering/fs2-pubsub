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

import cats.{Foldable, Traverse}

trait AsyncPubsubProducer[F[_], A] {
  def produceAsync(
    data: A,
    callback: Either[Throwable, Unit] => F[Unit],
    attributes: Map[String, String] = Map.empty,
    uniqueId: String = UUID.randomUUID().toString
  ): F[Unit]

  def produceManyAsync[G[_]: Foldable](
    records: G[Model.AsyncRecord[F, A]]
  ): F[Unit]

  def produce(
    data: A,
    attributes: Map[String, String] = Map.empty,
    uniqueId: String = UUID.randomUUID().toString
  ): F[F[Unit]]

  def produceMany[G[_]: Traverse](
    records: G[Model.SimpleRecord[A]]
  ): F[G[F[Unit]]]
}
