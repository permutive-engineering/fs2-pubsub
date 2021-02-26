package com.permutive.pubsub

import cats.effect._
import cats.effect.concurrent.{Deferred, Ref}
import cats.syntax.all._
import com.permutive.pubsub.consumer.http.Example.ValueHolder
import fs2.Stream
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration._

class PingPongSpec extends PubSubSpec {

  implicit val logger: Logger[IO] = Slf4jLogger.getLogger

  it should "work" in {
    Stream
      .resource(client)
      .flatMap { client =>
        for {
          _        <- Stream.resource(createTopic(project, topic))
          _        <- Stream.resource(createSubscription(project, topic, subscription))
          producer <- Stream.resource(producer(client))
          _        <- Stream.eval(producer.produce(ValueHolder("ping")))
          defer    <- Stream.eval(Deferred[IO, Either[Throwable, Unit]])
          ref      <- Stream.eval(Ref.of[IO, Option[ValueHolder]](None))
          _ <- consumer(client)
            .evalMap(record => record.ack >> ref.set(Some(record.value)) >> defer.complete(Right(())))
            .interruptWhen(defer)
            .timeout(10.seconds)
          out <- Stream.eval(ref.get)
        } yield out should ===(Some(ValueHolder("ping")))
      }
      .as(ExitCode.Success)
      .compile
      .drain
      .unsafeRunSync()
  }

}
