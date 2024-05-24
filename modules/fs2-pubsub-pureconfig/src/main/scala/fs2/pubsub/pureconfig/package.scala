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

import _root_.pureconfig.ConfigReader
import _root_.pureconfig.ConfigReader._
import _root_.pureconfig.module.http4s._
import com.permutive.common.types.gcp.pureconfig._

package object pureconfig {

  implicit val TopicConfigReader: ConfigReader[Topic] = ConfigReader[String].map(Topic(_))

  implicit val SubscriptionConfigReader: ConfigReader[Subscription] = ConfigReader[String].map(Subscription(_))

  implicit val PubSubClientConfigConfigReader: ConfigReader[PubSubClient.Config] =
    forProduct2("project-id", "uri")(PubSubClient.Config.apply)

  implicit val PubSubPublisherConfigConfigReader: ConfigReader[PubSubPublisher.Config] =
    forProduct3("project-id", "topic", "uri")(PubSubPublisher.Config.apply)

  implicit val PubSubPublisherAsyncConfigConfigReader: ConfigReader[PubSubPublisher.Async.Config] =
    forProduct5("project-id", "topic", "uri", "batch-size", "max-latency")(PubSubPublisher.Async.Config.apply)

  implicit val PubSubSubscriberConfigConfigReader: ConfigReader[PubSubSubscriber.Config] =
    forProduct7("project-id", "subscription", "uri", "batch-size", "max-latency", "read-max-messages",
      "read-concurrency")(PubSubSubscriber.Config.apply)

}
