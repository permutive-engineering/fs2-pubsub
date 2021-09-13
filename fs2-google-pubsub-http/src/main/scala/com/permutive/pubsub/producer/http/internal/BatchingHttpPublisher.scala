package com.permutive.pubsub.producer.http.internal

import cats.effect.kernel.{Concurrent, Deferred, Resource, Temporal}
import cats.effect.std.{Queue, QueueSink, QueueSource}
import cats.effect.syntax.all._
import cats.syntax.all._
import cats.{Foldable, Traverse}
import com.permutive.pubsub.producer.Model.MessageId
import com.permutive.pubsub.producer.http.BatchingHttpProducerConfig
import com.permutive.pubsub.producer.{AsyncPubsubProducer, Model, PubsubProducer}
import fs2.{Chunk, Stream}

private[http] class BatchingHttpPublisher[F[_]: Concurrent, A] private (
  queue: QueueSink[F, Model.AsyncRecord[F, A]]
) extends AsyncPubsubProducer[F, A] {

  override def produceAsync(
    data: A,
    callback: Either[Throwable, Unit] => F[Unit],
    attributes: Map[String, String],
    uniqueId: String
  ): F[Unit] =
    queue.offer(Model.AsyncRecord(data, callback, attributes, uniqueId))

  override def produceManyAsync[G[_]: Foldable](records: G[Model.AsyncRecord[F, A]]): F[Unit] =
    records.traverse_(queue.offer)

  override def produce(
    data: A,
    attributes: Map[String, String],
    uniqueId: String
  ): F[F[Unit]] =
    produceAsync(Model.SimpleRecord(data, attributes, uniqueId))

  override def produceMany[G[_]: Traverse](records: G[Model.SimpleRecord[A]]): F[G[F[Unit]]] =
    records.traverse(produceAsync)

  private def produceAsync(record: Model.SimpleRecord[A]): F[F[Unit]] =
    for {
      d <- Deferred[F, Either[Throwable, Unit]]
      _ <- queue.offer(Model.AsyncRecord(record.data, d.complete(_).void, record.attributes, record.uniqueId))
    } yield d.get.rethrow

}

private[http] object BatchingHttpPublisher {
  def resource[F[_]: Temporal, A](
    publisher: PubsubProducer[F, A],
    config: BatchingHttpProducerConfig
  ): Resource[F, AsyncPubsubProducer[F, A]] =
    for {
      queue <- Resource.eval(Queue.unbounded[F, Model.AsyncRecord[F, A]])
      _     <- Resource.make(consume(publisher, config, queue).start)(_.cancel)
    } yield new BatchingHttpPublisher(queue)

  private def consume[F[_]: Temporal, A](
    underlying: PubsubProducer[F, A],
    config: BatchingHttpProducerConfig,
    queue: QueueSource[F, Model.AsyncRecord[F, A]]
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
            maxAttempts = config.retryTimes
          )
          .compile
          .lastOrError
      }

    Stream
      .fromQueueUnterminated(queue)
      .groupWithin(config.batchSize, config.maxLatency)
      .evalMap { asyncRecords =>
        handler(asyncRecords).void.attempt
          .flatMap(etu => asyncRecords.traverse_(_.callback(etu)))
      }
      .compile
      .drain
  }
}
