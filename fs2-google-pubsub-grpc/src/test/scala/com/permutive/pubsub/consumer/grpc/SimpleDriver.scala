package com.permutive.pubsub.consumer.grpc

import cats.effect.{Blocker, ExitCode, IO, IOApp}
import cats.syntax.all._
import com.permutive.pubsub.consumer.Model
import com.permutive.pubsub.consumer.decoder.MessageDecoder

import fs2.Stream

object SimpleDriver extends IOApp {
  case class ValueHolder(value: String) extends AnyVal

  implicit val decoder: MessageDecoder[ValueHolder] = (bytes: Array[Byte]) => {
    Right(ValueHolder(new String(bytes)))
  }

  override def run(args: List[String]): IO[ExitCode] = {
    val stream = for {
      blocker <- Stream.resource(Blocker[IO])
      _ <-
        PubsubGoogleConsumer
          .subscribe[IO, ValueHolder](
            blocker,
            Model.ProjectId("test-project"),
            Model.Subscription("example-sub"),
            (msg, err, ack, _) => IO(println(s"Msg $msg got error $err")) >> ack,
            config = PubsubGoogleConsumerConfig(
              onFailedTerminate = _ => IO.unit
            )
          )
          .evalTap(t => t.ack >> IO(println(s"Got: ${t.value}")))
    } yield ExitCode.Success

    stream.compile.lastOrError
  }
}
