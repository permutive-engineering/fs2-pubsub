/*
 * Copyright 2019-2024 Permutive Ltd. <https://permutive.com>
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

package fs2.pubsub

import io.circe.Decoder
import io.circe.Encoder
import io.circe.syntax._

package object circe {

  implicit def CirceDecoder2MessageDecoder[A: Decoder]: MessageDecoder[A] =
    MessageDecoder.json.emap(_.as[A])

  implicit def CirceEncoder2MessageEncoder[A: Encoder]: MessageEncoder[A] =
    MessageEncoder.json.contramap(_.asJson)

}
