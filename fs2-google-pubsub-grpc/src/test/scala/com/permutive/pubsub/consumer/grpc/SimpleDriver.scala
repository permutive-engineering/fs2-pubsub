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

package com.permutive.pubsub.consumer.grpc

import cats.effect.{ExitCode, IO, IOApp}
import com.permutive.pubsub.consumer.Model
import com.permutive.pubsub.consumer.decoder.MessageDecoder

object SimpleDriver extends IOApp {
  case class ValueHolder(value: String) extends AnyVal

  implicit val decoder: MessageDecoder[ValueHolder] = (bytes: Array[Byte]) => {
    Right(ValueHolder(new String(bytes)))
  }

  override def run(args: List[String]): IO[ExitCode] = {
    val stream = for {
      _ <-
        PubsubGoogleConsumer
          .subscribe[IO, ValueHolder](
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
