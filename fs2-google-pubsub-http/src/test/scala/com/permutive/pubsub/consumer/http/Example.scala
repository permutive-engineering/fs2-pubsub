package com.permutive.pubsub.consumer.http

import java.util.concurrent.Executors

import cats.effect._
import cats.syntax.all._
import com.permutive.pubsub.consumer.Model
import com.permutive.pubsub.consumer.decoder.MessageDecoder
import fs2.Stream
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.http4s.client.okhttp.OkHttpBuilder

import scala.concurrent.ExecutionContext
import scala.util.Try

object Example extends IOApp {
  case class ValueHolder(value: String) extends AnyVal

  implicit val decoder: MessageDecoder[ValueHolder] = (bytes: Array[Byte]) => {
    Try(ValueHolder(new String(bytes))).toEither
  }

  def blockingThreadPool[F[_]](implicit F: Sync[F]): Resource[F, ExecutionContext] = {
    Resource
      .make(F.delay(Executors.newCachedThreadPool()))(e => F.delay(e.shutdown()))
      .map(ExecutionContext.fromExecutor)
  }

  override def run(args: List[String]): IO[ExitCode] = {
    val client = blockingThreadPool[IO].flatMap(
      OkHttpBuilder
        .withDefaultClient[IO](_)
        .flatMap(_.resource)
    )

    implicit val unsafeLogger: Logger[IO] = Slf4jLogger.unsafeFromName("fs2-google-pubsub")

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
