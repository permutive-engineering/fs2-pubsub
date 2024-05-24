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

package fs2.pubsub.grpc

import scalapb.TypeMapper

package object internal {

  implicit val FloatValueTypeMapper: TypeMapper[FloatValue, Float] =
    TypeMapper[FloatValue, Float](_.value)(FloatValue.apply(_))

  implicit val Int64ValueTypeMapper: TypeMapper[Int64Value, Long] =
    TypeMapper[Int64Value, Long](_.value)(Int64Value.apply(_))

  implicit val UInt64ValueTypeMapper: TypeMapper[UInt64Value, Long] =
    TypeMapper[UInt64Value, Long](_.value)(UInt64Value.apply(_))

  implicit val Int32ValueTypeMapper: TypeMapper[Int32Value, Int] =
    TypeMapper[Int32Value, Int](_.value)(Int32Value.apply(_))

  implicit val UInt32ValueTypeMapper: TypeMapper[UInt32Value, Int] =
    TypeMapper[UInt32Value, Int](_.value)(UInt32Value.apply(_))

}
