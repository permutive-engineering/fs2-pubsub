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

package com.permutive.pubsub.consumer.http

import cats.effect._
import com.permutive.pubsub.consumer.Model
import com.permutive.pubsub.consumer.decoder.MessageDecoder
import com.permutive.pubsub.producer.encoder.MessageEncoder
import fs2.Stream
import org.http4s.client.Client
import org.http4s.okhttp.client.OkHttpBuilder
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.util.Try

object Example extends IOApp {
  case class ValueHolder(value: String) extends AnyVal

  implicit val decoder: MessageDecoder[ValueHolder] = (bytes: Array[Byte]) => {
    Try(ValueHolder(new String(bytes))).toEither
  }

  implicit val encoder: MessageEncoder[ValueHolder] =
    (a: ValueHolder) => Right(a.value.getBytes())

  override def run(args: List[String]): IO[ExitCode] = {
    val client = OkHttpBuilder
      .withDefaultClient[IO]
      .flatMap(_.resource)

    implicit val unsafeLogger: Logger[IO] = Slf4jLogger.getLoggerFromName("fs2-google-pubsub")

    def mkConsumer(client: Client[IO]) =
      PubsubHttpConsumer.subscribe[IO, ValueHolder](
        Model.ProjectId("test-project"),
        Model.Subscription("example-sub"),
        Some("/path/to/service/account"),
        PubsubHttpConsumerConfig(
          host = "localhost",
          port = 8085,
          isEmulator = true
        ),
        client,
        (msg, err, ack, _) => IO(println(s"Msg $msg got error $err")) >> ack,
      )

    Stream
      .resource(client)
      .flatMap(mkConsumer)
      .evalTap(t => t.ack >> IO(println(s"Got: ${t.value}")))
      .as(ExitCode.Success)
      .compile
      .lastOrError
  }
}
