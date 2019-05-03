package com.permutive.pubsub.producer.http.internal

import cats.Traverse
import cats.effect.concurrent.Deferred
import cats.effect.syntax.all._
import cats.effect.{Concurrent, Resource, Timer}
import cats.syntax.all._
import com.permutive.pubsub.producer.Model.MessageId
import com.permutive.pubsub.producer.encoder.MessageEncoder
import com.permutive.pubsub.producer.http.BatchingHttpProducerConfig
import com.permutive.pubsub.producer.http.internal.BatchingHttpPublisher.EnqueuedRecord
import com.permutive.pubsub.producer.{AsyncPubsubProducer, Model, PubsubProducer}
import fs2.Chunk._
import fs2.concurrent.{Enqueue, Queue}
import fs2.{Chunk, Stream}

private[http] class BatchingHttpPublisher[F[_] : Concurrent : Timer, A: MessageEncoder] private(
  queue: Enqueue[F, EnqueuedRecord[F, A]],
) extends AsyncPubsubProducer[F, A] {

  override def produceAsync(
    record: A,
    metadata: Map[String, String],
    uniqueId: String,
  ): F[F[Unit]] =
    produceAsync(Model.Record(record, metadata, uniqueId))

  override def produceManyAsync[G[_] : Traverse](records: G[Model.Record[A]]): F[G[F[Unit]]] =
    records.traverse(produceAsync)

  private def produceAsync(record: Model.Record[A]): F[F[Unit]] =
    for {
      d <- Deferred[F, Either[Throwable, Unit]]
      _ <- queue.enqueue1(EnqueuedRecord(d, record))
    } yield d.get.rethrow

}

private[http] object BatchingHttpPublisher {
  def resource[F[_] : Concurrent : Timer, A: MessageEncoder](
    publisher: PubsubProducer[F, A],
    config: BatchingHttpProducerConfig,
  ): Resource[F, AsyncPubsubProducer[F, A]] = {
    for {
      queue <- Resource.liftF(Queue.unbounded[F, EnqueuedRecord[F, A]])
      _ <- Resource.make(consume(publisher, config, queue).start)(_.cancel)
    } yield new BatchingHttpPublisher(queue)
  }

  private def consume[F[_] : Concurrent : Timer, A: MessageEncoder](
    underlying: PubsubProducer[F, A],
    config: BatchingHttpProducerConfig,
    queue: Queue[F, EnqueuedRecord[F, A]],
  ): F[Unit] = {
    val handler: Chunk[Model.Record[A]] => F[List[MessageId]] =
      if (config.retryTimes == 0) {
        records => underlying.produceMany[Chunk](records)
      } else {
        records =>
          Stream.retry(
            underlying.produceMany[Chunk](records),
            delay = config.retryInitialDelay,
            nextDelay = config.retryNextDelay,
            maxAttempts = config.retryTimes,
          ).compile.lastOrError
      }

    queue
      .dequeue
      .groupWithin(config.batchSize, config.maxLatency)
      .evalMap { enqueuedRecords =>
        handler(enqueuedRecords.map(_.record))
          .void
          .attempt
          .flatMap(etu => enqueuedRecords.traverse(_.deferred.complete(etu)))
      }
      .compile
      .drain
  }

  case class EnqueuedRecord[F[_], A](deferred: Deferred[F, Either[Throwable, Unit]], record: Model.Record[A])
}