package com.permutive.pubsub.producer.http.internal

import cats.{Foldable, Traverse}
import cats.effect.concurrent.Deferred
import cats.effect.syntax.all._
import cats.effect.{Concurrent, Resource, Timer}
import cats.syntax.all._
import com.permutive.pubsub.producer.Model.MessageId
import com.permutive.pubsub.producer.encoder.MessageEncoder
import com.permutive.pubsub.producer.http.BatchingHttpProducerConfig
import com.permutive.pubsub.producer.{AsyncPubsubProducer, Model, PubsubProducer}
import fs2.Chunk._
import fs2.concurrent.{Dequeue, Enqueue, Queue}
import fs2.{Chunk, Stream}

private[http] class BatchingHttpPublisher[F[_]: Timer, A: MessageEncoder] private (
  queue: Enqueue[F, Model.AsyncRecord[F, A]],
)(implicit F: Concurrent[F])
    extends AsyncPubsubProducer[F, A] {

  override def produceAsync(
    record: A,
    callback: Either[Throwable, Unit] => F[Unit],
    metadata: Map[String, String],
    uniqueId: String,
  ): F[Unit] =
    queue.enqueue1(Model.AsyncRecord(record, callback, metadata, uniqueId))

  override def produceManyAsync[G[_]: Foldable](records: G[Model.AsyncRecord[F, A]]): F[Unit] =
    records.traverse_(queue.enqueue1)

  override def produce(
    record: A,
    metadata: Map[String, String],
    uniqueId: String,
  ): F[F[Unit]] =
    produceAsync(Model.SimpleRecord(record, metadata, uniqueId))

  override def produceMany[G[_]: Traverse](records: G[Model.SimpleRecord[A]]): F[G[F[Unit]]] =
    records.traverse(produceAsync)

  private def produceAsync(record: Model.SimpleRecord[A]): F[F[Unit]] =
    for {
      d <- Deferred[F, Either[Throwable, Unit]]
      _ <- queue.enqueue1(Model.AsyncRecord(record.value, d.complete, record.metadata, record.uniqueId))
    } yield d.get.rethrow

}

private[http] object BatchingHttpPublisher {
  def resource[F[_]: Concurrent: Timer, A: MessageEncoder](
    publisher: PubsubProducer[F, A],
    config: BatchingHttpProducerConfig,
  ): Resource[F, AsyncPubsubProducer[F, A]] =
    for {
      queue <- Resource.liftF(Queue.unbounded[F, Model.AsyncRecord[F, A]])
      _     <- Resource.make(consume(publisher, config, queue).start)(_.cancel)
    } yield new BatchingHttpPublisher(queue)

  private def consume[F[_]: Concurrent: Timer, A: MessageEncoder](
    underlying: PubsubProducer[F, A],
    config: BatchingHttpProducerConfig,
    queue: Dequeue[F, Model.AsyncRecord[F, A]],
  ): F[Unit] = {
    val handler: Chunk[Model.AsyncRecord[F, A]] => F[List[MessageId]] =
      if (config.retryTimes == 0) { records =>
        underlying.produceMany[Chunk](records)
      } else { records =>
        Stream
          .retry(
            underlying.produceMany[Chunk](records),
            delay = config.retryInitialDelay,
            nextDelay = config.retryNextDelay,
            maxAttempts = config.retryTimes,
          )
          .compile
          .lastOrError
      }

    queue.dequeue
      .groupWithin(config.batchSize, config.maxLatency)
      .evalMap { asyncRecords =>
        handler(asyncRecords).void.attempt
          .flatMap(etu => asyncRecords.traverse_(_.callback(etu)))
      }
      .compile
      .drain
  }
}
