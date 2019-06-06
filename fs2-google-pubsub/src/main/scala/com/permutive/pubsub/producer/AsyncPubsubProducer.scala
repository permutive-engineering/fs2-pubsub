package com.permutive.pubsub.producer

import java.util.UUID

import cats.{Foldable, Traverse}

trait AsyncPubsubProducer[F[_], A] {
  def produceAsync(
    record: A,
    callback: Either[Throwable, Unit] => F[Unit],
    metadata: Map[String, String] = Map.empty,
    uniqueId: String = UUID.randomUUID().toString,
  ): F[Unit]

  def produceManyAsync[G[_]: Foldable](
    records: G[Model.AsyncRecord[F, A]],
  ): F[Unit]

  def produce(
    record: A,
    metadata: Map[String, String] = Map.empty,
    uniqueId: String = UUID.randomUUID().toString,
  ): F[F[Unit]]

  def produceMany[G[_]: Traverse](
    records: G[Model.SimpleRecord[A]],
  ): F[G[F[Unit]]]
}
