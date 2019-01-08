package com.permutive.pubsub.consumer.http

import cats.effect._
import cats.syntax.all._
import com.permutive.pubsub.consumer.Model
import com.permutive.pubsub.consumer.decoder.MessageDecoder
import org.http4s.client.asynchttpclient.AsyncHttpClient
import fs2.Stream

import scala.util.Try

object Example extends IOApp {
  case class ValueHolder(value: String) extends AnyVal

  implicit val decoder: MessageDecoder[ValueHolder] = (bytes: Array[Byte]) => {
    Try(ValueHolder(new String(bytes))).toEither
  }

  override def run(args: List[String]): IO[ExitCode] = {
    val client = AsyncHttpClient.resource[IO]()

    val mkConsumer = PubsubHttpConsumer.subscribe[IO, ValueHolder](
      Model.ProjectId("test-project"),
      Model.Subscription("example-sub"),
      "/path/to/service/account",
      PubsubHttpConsumerConfig(
        host = "localhost",
        port = 8085,
        isEmulator = true,
      ),
      _,
      (msg, err, ack, _) => IO(println(s"Msg $msg got error $err")) >> ack,
    )

    Stream.resource(client)
      .flatMap(mkConsumer)
      .evalTap(t => t.ack >> IO(println(s"Got: ${t.value}")))
      .as(ExitCode.Success)
      .compile
      .lastOrError
  }
}
